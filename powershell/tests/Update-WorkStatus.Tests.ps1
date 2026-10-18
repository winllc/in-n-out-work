#Requires -Modules @{ ModuleName = 'Pester'; ModuleVersion = '5.5.0' }
<#
  Tests for Update-WorkStatus.ps1, the agent the scheduled tasks run.

  The script is dot-sourced, which defines its functions and stops before the
  main block (see the guard above "Main" in the script). Most of this file runs
  anywhere PowerShell does - Linux included - with the Windows-only pieces
  (Get-Acl, Get-CimInstance, SID translation) mocked. Those mocks test the
  script's decisions, not Windows: the "Windows" tagged tests at the end use the
  real ACLs, SIDs and junctions, and only run on Windows.
#>

# The analyzer cannot follow Pester's scoping, and its compatibility profiles
# know only the Pester 3.4 that ships with Windows. Each suppression is for that.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Variables set in one Pester block are read in another, or by the functions under test through dynamic scoping.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseCompatibleCommands', '',
    Justification = 'The profiles list the Pester 3.4 that ships with Windows; these tests need Pester 5.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSReviewUnusedParameter', '',
    Justification = 'Stand-ins for Windows-only commands keep their real parameters so Pester can mock them.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidOverwritingBuiltInCmdlets', '',
    Justification = 'Stand-ins are defined only where the Windows-only cmdlet does not exist, for Pester to mock.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '',
    Justification = 'Test helpers that only build objects in memory.')]
param()

BeforeDiscovery {
    $script:OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
}

BeforeAll {
    $script:OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
    $script:AgentPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'Update-WorkStatus.ps1'

    # Get-Acl and Get-CimInstance are Windows-only. Pester can only mock a
    # command that exists, so elsewhere stand in an empty one to mock.
    if (-not (Get-Command Get-Acl -ErrorAction SilentlyContinue)) {
        function Get-Acl { param([string]$LiteralPath) }
    }
    if (-not (Get-Command Get-CimInstance -ErrorAction SilentlyContinue)) {
        function Get-CimInstance { param([string]$ClassName, [string]$Filter) }
    }

    . $AgentPath -Action login

    function New-TestCertificate {
        param(
            [string]$Subject,
            [datetime]$NotBefore = (Get-Date).AddDays(-1),
            [datetime]$NotAfter = (Get-Date).AddDays(30),
            [switch]$WithoutPrivateKey
        )
        $rsa = [System.Security.Cryptography.RSA]::Create(2048)
        $request = New-Object System.Security.Cryptography.X509Certificates.CertificateRequest(
            $Subject, $rsa, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $cert = $request.CreateSelfSigned([datetimeoffset]$NotBefore, [datetimeoffset]$NotAfter)
        if ($WithoutPrivateKey) {
            return New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(, $cert.Export('Cert'))
        }
        return $cert
    }

    function New-FakeAcl {
        # What Test-TrustedPath and Test-InstallerFolder read from Get-Acl.
        param([string]$OwnerSid, [bool]$Protected = $true)
        $acl = [pscustomobject]@{ AreAccessRulesProtected = $Protected; OwnerSid = $OwnerSid }
        $acl | Add-Member -MemberType ScriptMethod -Name GetOwner -Value {
            param($Type)
            [pscustomobject]@{ Value = $this.OwnerSid }
        }
        return $acl
    }

    $script:AliceSid = 'S-1-5-21-1004336348-1177238915-682003330-1001'
    $script:BobSid   = 'S-1-5-21-1004336348-1177238915-682003330-1002'
}

Describe 'Command line' {
    BeforeAll {
        $script:Parameters = (Get-Command $AgentPath).Parameters
    }

    It 'requires -Action' {
        $attribute = $Parameters['Action'].Attributes |
            Where-Object { $_ -is [System.Management.Automation.ParameterAttribute] }
        $attribute.Mandatory | Should -BeTrue
    }

    It 'accepts only the four session events as -Action' {
        $set = $Parameters['Action'].Attributes |
            Where-Object { $_ -is [System.Management.Automation.ValidateSetAttribute] }
        @($set.ValidValues | Sort-Object) | Should -Be @('lock', 'login', 'logout', 'unlock')
    }

    It 'rejects any other -Action before running anything' {
        { & $AgentPath -Action 'checkin' -WhatIf } |
            Should -Throw -ExceptionType ([System.Management.Automation.ParameterBindingException])
    }

    It 'accepts only Certificate, Windows or Auto as -Authentication' {
        $set = $Parameters['Authentication'].Attributes |
            Where-Object { $_ -is [System.Management.Automation.ValidateSetAttribute] }
        @($set.ValidValues | Sort-Object) | Should -Be @('Auto', 'Certificate', 'Windows')
        { & $AgentPath -Action login -Authentication 'Basic' -WhatIf } |
            Should -Throw -ExceptionType ([System.Management.Automation.ParameterBindingException])
    }

    It 'supports -WhatIf' {
        $Parameters.ContainsKey('WhatIf') | Should -BeTrue
    }
}

Describe 'Action map' {
    It 'posts <action> to /<endpoint>' -TestCases @(
        @{ Action = 'login';  Endpoint = 'in' }
        @{ Action = 'logout'; Endpoint = 'out' }
        @{ Action = 'lock';   Endpoint = 'lock' }
        @{ Action = 'unlock'; Endpoint = 'unlock' }
    ) {
        $ActionMap[$Action].Endpoint | Should -Be $Endpoint
    }

    It 'signs in for everything but logout, the one anonymous endpoint' {
        $ActionMap['login'].Authenticated  | Should -BeTrue
        $ActionMap['lock'].Authenticated   | Should -BeTrue
        $ActionMap['unlock'].Authenticated | Should -BeTrue
        $ActionMap['logout'].Authenticated | Should -BeFalse
    }

    It 'waits for the profile (and the certificate store) only at login and unlock' {
        @($ActionMap.Keys | Where-Object { $ActionMap[$_].WaitForProfile } | Sort-Object) |
            Should -Be @('login', 'unlock')
    }

    It 'defaults to certificate sign-in with server certificate checks on' {
        $Defaults.Authentication | Should -Be 'Certificate'
        $Defaults.SkipCertificateCheck | Should -BeFalse
    }
}

Describe 'Resolve-Configuration' {
    BeforeEach {
        Mock Write-StatusLog {}
        $script:Root = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $Root | Out-Null
        $script:BesideScript = Join-Path $Root 'inoutworker.config.json'
    }

    It 'uses the defaults when there is no config file and no parameter' {
        $config = Resolve-Configuration -Parameters @{} -ScriptRoot $Root
        $config.BaseUrl | Should -Be $Defaults.BaseUrl
        $config.Authentication | Should -Be 'Certificate'
        $config.SkipCertificateCheck | Should -BeFalse
    }

    It 'reads the config file beside the script' {
        '{ "BaseUrl": "https://readyroom.example.com/api/check", "Authentication": "Auto",
           "CertificateThumbprint": "AB12", "SkipCertificateCheck": true }' | Set-Content -Path $BesideScript
        $config = Resolve-Configuration -Parameters @{} -ScriptRoot $Root
        $config.BaseUrl | Should -Be 'https://readyroom.example.com/api/check'
        $config.Authentication | Should -Be 'Auto'
        $config.CertificateThumbprint | Should -Be 'AB12'
        $config.SkipCertificateCheck | Should -BeTrue
        Should -Invoke Write-StatusLog -ParameterFilter { $Message -like "Loaded configuration from*$BesideScript" }
    }

    It 'prefers -ConfigPath and then reads no other file' {
        '{ "BaseUrl": "https://beside.example.com/api/check" }' | Set-Content -Path $BesideScript
        $explicit = Join-Path $Root 'explicit.json'
        '{ "Authentication": "Windows" }' | Set-Content -Path $explicit
        $config = Resolve-Configuration -Parameters @{ ConfigPath = $explicit } -ScriptRoot $Root
        $config.Authentication | Should -Be 'Windows'
        $config.BaseUrl | Should -Be $Defaults.BaseUrl
    }

    It 'falls back to the file beside the script when -ConfigPath does not exist' {
        '{ "BaseUrl": "https://beside.example.com/api/check" }' | Set-Content -Path $BesideScript
        $config = Resolve-Configuration -Parameters @{ ConfigPath = (Join-Path $Root 'missing.json') } -ScriptRoot $Root
        $config.BaseUrl | Should -Be 'https://beside.example.com/api/check'
    }

    It 'ignores keys it does not know' {
        '{ "BaseUrl": "https://readyroom.example.com/api/check", "SessionDir": "C:\\Users\\Public" }' |
            Set-Content -Path $BesideScript
        $config = Resolve-Configuration -Parameters @{} -ScriptRoot $Root
        $config.ContainsKey('SessionDir') | Should -BeFalse
        @($config.Keys | Sort-Object) | Should -Be @(@($Defaults.Keys) | Sort-Object)
    }

    It 'warns about, and ignores, a file that is not JSON' {
        'BaseUrl = https://evil.example.com' | Set-Content -Path $BesideScript
        $config = Resolve-Configuration -Parameters @{} -ScriptRoot $Root
        $config.BaseUrl | Should -Be $Defaults.BaseUrl
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' -and $Message -like 'Ignoring unreadable config*' }
    }

    It 'lets parameters override the file' {
        '{ "BaseUrl": "https://file.example.com/api/check", "Authentication": "Windows",
           "CertificateThumbprint": "FILE", "CertificateSubjectContains": "file" }' | Set-Content -Path $BesideScript
        $config = Resolve-Configuration -ScriptRoot $Root -Parameters @{
            BaseUrl                    = 'https://param.example.com/api/check'
            Authentication             = 'Certificate'
            CertificateThumbprint      = 'PARAM'
            CertificateSubjectContains = 'param'
        }
        $config.BaseUrl | Should -Be 'https://param.example.com/api/check'
        $config.Authentication | Should -Be 'Certificate'
        $config.CertificateThumbprint | Should -Be 'PARAM'
        $config.CertificateSubjectContains | Should -Be 'param'
    }

    It 'treats an empty parameter as not given' {
        '{ "BaseUrl": "https://file.example.com/api/check" }' | Set-Content -Path $BesideScript
        $config = Resolve-Configuration -Parameters @{ BaseUrl = '' } -ScriptRoot $Root
        $config.BaseUrl | Should -Be 'https://file.example.com/api/check'
    }

    It 'turns certificate checks off with -SkipCertificateCheck' {
        $switch = [System.Management.Automation.SwitchParameter]::new($true)
        $config = Resolve-Configuration -Parameters @{ SkipCertificateCheck = $switch } -ScriptRoot $Root
        $config.SkipCertificateCheck | Should -BeTrue
    }

    It 'turns them back on with -SkipCertificateCheck:$false over a file that skips them' {
        '{ "SkipCertificateCheck": true }' | Set-Content -Path $BesideScript
        $switch = [System.Management.Automation.SwitchParameter]::new($false)
        $config = Resolve-Configuration -Parameters @{ SkipCertificateCheck = $switch } -ScriptRoot $Root
        $config.SkipCertificateCheck | Should -BeFalse
    }

    It 'drops trailing slashes from BaseUrl' {
        $config = Resolve-Configuration -Parameters @{ BaseUrl = 'https://readyroom.example.com/api/check//' } -ScriptRoot $Root
        $config.BaseUrl | Should -Be 'https://readyroom.example.com/api/check'
    }

    It 'refuses BaseUrl <url>' -TestCases @(
        @{ Url = 'ftp://readyroom.example.com/api/check' }
        @{ Url = 'readyroom.example.com/api/check' }
        @{ Url = 'file:///C:/Windows/win.ini' }
    ) {
        { Resolve-Configuration -Parameters @{ BaseUrl = $Url } -ScriptRoot $Root } | Should -Throw '*is not a valid URL*'
    }

    It 'refuses a blank BaseUrl from the file' {
        '{ "BaseUrl": "  " }' | Set-Content -Path $BesideScript
        { Resolve-Configuration -Parameters @{} -ScriptRoot $Root } | Should -Throw 'BaseUrl is not configured.'
    }

    It 'normalises the case of Authentication from the file' {
        '{ "Authentication": "windows" }' | Set-Content -Path $BesideScript
        (Resolve-Configuration -Parameters @{} -ScriptRoot $Root).Authentication | Should -BeExactly 'Windows'
    }

    It 'refuses an Authentication it does not know' {
        '{ "Authentication": "Basic" }' | Set-Content -Path $BesideScript
        { Resolve-Configuration -Parameters @{} -ScriptRoot $Root } | Should -Throw "Authentication 'Basic' is not one of*"
    }

    It 'warns that Windows sign-in needs a host name, not an IP address' {
        $null = Resolve-Configuration -ScriptRoot $Root -Parameters @{
            BaseUrl = 'https://192.0.2.10:8444/api/check'; Authentication = 'Auto' }
        Should -Invoke Write-StatusLog -Times 1 -ParameterFilter { $Level -eq 'WARN' -and $Message -like '*IP address*' }
    }

    It 'does not warn for a host name, or for certificate sign-in' {
        $null = Resolve-Configuration -ScriptRoot $Root -Parameters @{
            BaseUrl = 'https://readyroom.example.com/api/check'; Authentication = 'Windows' }
        $null = Resolve-Configuration -ScriptRoot $Root -Parameters @{
            BaseUrl = 'https://192.0.2.10:8444/api/check'; Authentication = 'Certificate' }
        Should -Invoke Write-StatusLog -Times 0 -ParameterFilter { $Message -like '*IP address*' }
    }
}

Describe 'Select-ClientCertificate' {
    BeforeAll {
        $script:Old      = New-TestCertificate -Subject 'CN=alice old' -NotBefore (Get-Date).AddDays(-30)
        $script:New      = New-TestCertificate -Subject 'CN=alice new' -NotBefore (Get-Date).AddDays(-2)
        $script:Card     = New-TestCertificate -Subject 'CN=alice, OU=PIV' -NotBefore (Get-Date).AddDays(-20)
        $script:Expired  = New-TestCertificate -Subject 'CN=expired' -NotBefore (Get-Date).AddDays(-60) -NotAfter (Get-Date).AddDays(-1)
        $script:NotYet   = New-TestCertificate -Subject 'CN=not yet' -NotBefore (Get-Date).AddDays(1)
        $script:NoKey    = New-TestCertificate -Subject 'CN=no key' -NotBefore (Get-Date).AddHours(-1) -WithoutPrivateKey
    }
    BeforeEach { Mock Write-StatusLog {} }

    It 'returns nothing when no certificate is usable' {
        Select-ClientCertificate -Certificates @($Expired, $NotYet, $NoKey) | Should -BeNullOrEmpty
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' }
    }

    It 'returns nothing for an empty store' {
        Select-ClientCertificate -Certificates @() | Should -BeNullOrEmpty
    }

    It 'skips expired, not-yet-valid and keyless certificates, and takes the most recent' {
        $chosen = Select-ClientCertificate -Certificates @($Old, $Expired, $NotYet, $NoKey, $New, $Card)
        $chosen.Thumbprint | Should -Be $New.Thumbprint
    }

    It 'takes the thumbprint, whatever its case or separators' {
        $spaced = (($Old.Thumbprint.ToLower() -split '(..)' | Where-Object { $_ }) -join ' ')
        $chosen = Select-ClientCertificate -Certificates @($Old, $New, $Card) -Thumbprint $spaced
        $chosen.Thumbprint | Should -Be $Old.Thumbprint
    }

    It 'never takes an unusable certificate by thumbprint' {
        $chosen = Select-ClientCertificate -Certificates @($Expired, $New) -Thumbprint $Expired.Thumbprint
        $chosen.Thumbprint | Should -Be $New.Thumbprint
        Should -Invoke Write-StatusLog -ParameterFilter { $Message -like 'No certificate matched thumbprint*' }
    }

    It 'falls back to the subject when the thumbprint does not match' {
        $chosen = Select-ClientCertificate -Certificates @($Old, $New, $Card) -Thumbprint 'FFFF' -SubjectContains 'OU=PIV'
        $chosen.Thumbprint | Should -Be $Card.Thumbprint
    }

    It 'falls back to the most recent when the subject does not match' {
        $chosen = Select-ClientCertificate -Certificates @($Old, $New, $Card) -SubjectContains 'bob'
        $chosen.Thumbprint | Should -Be $New.Thumbprint
    }
}

Describe 'Get-SignInPlan' {
    BeforeAll {
        $script:Cert = New-TestCertificate -Subject 'CN=alice'
    }
    BeforeEach {
        Mock Write-StatusLog {}
        $script:Config = @{ Authentication = 'Certificate'; CertificateThumbprint = 'AB12'; CertificateSubjectContains = 'alice' }
    }

    It 'signs nothing in for logout, and looks for no certificate' {
        Mock Find-UserCertificate { $Cert }
        $signIn = Get-SignInPlan -Plan $ActionMap['logout'] -Config $Config
        @($signIn.Attempts) | Should -Be @('Anonymous')
        $signIn.Certificate | Should -BeNullOrEmpty
        Should -Invoke Find-UserCertificate -Times 0
    }

    It 'uses the certificate the config selects' {
        Mock Find-UserCertificate { $Cert }
        $signIn = Get-SignInPlan -Plan $ActionMap['login'] -Config $Config
        @($signIn.Attempts) | Should -Be @('Certificate')
        $signIn.Certificate.Thumbprint | Should -Be $Cert.Thumbprint
        Should -Invoke Find-UserCertificate -Times 1 -ParameterFilter { $Thumbprint -eq 'AB12' -and $SubjectContains -eq 'alice' }
    }

    It 'gives up (exit code 2) when certificate sign-in has no certificate' {
        Mock Find-UserCertificate { $null }
        Get-SignInPlan -Plan $ActionMap['lock'] -Config $Config | Should -BeNullOrEmpty
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'ERROR' }
    }

    It 'uses only the Windows logon with Authentication Windows' {
        Mock Find-UserCertificate { $Cert }
        $Config.Authentication = 'Windows'
        $signIn = Get-SignInPlan -Plan $ActionMap['unlock'] -Config $Config
        @($signIn.Attempts) | Should -Be @('Windows')
        Should -Invoke Find-UserCertificate -Times 0
    }

    It 'tries Windows, then the certificate, with Authentication Auto' {
        Mock Find-UserCertificate { $Cert }
        $Config.Authentication = 'Auto'
        $signIn = Get-SignInPlan -Plan $ActionMap['login'] -Config $Config
        @($signIn.Attempts) | Should -Be @('Windows', 'Certificate')
        $signIn.Certificate.Thumbprint | Should -Be $Cert.Thumbprint
    }

    It 'still tries Windows with Authentication Auto and no certificate' {
        Mock Find-UserCertificate { $null }
        $Config.Authentication = 'Auto'
        $signIn = Get-SignInPlan -Plan $ActionMap['login'] -Config $Config
        @($signIn.Attempts) | Should -Be @('Windows')
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' }
    }
}

Describe 'Session file naming' {
    BeforeEach { $SessionDir = Join-Path $TestDrive 'sessions' }

    It 'names each user''s file after their SID' {
        Get-SessionFilePath -Sid $AliceSid | Should -Be (Join-Path $SessionDir "$AliceSid.json")
    }

    It 'gives two users two files' {
        Get-SessionFilePath -Sid $AliceSid | Should -Not -Be (Get-SessionFilePath -Sid $BobSid)
    }

    It 'turns <value> into no SID at all' -TestCases @(
        @{ Value = '$(TargetUserSid)' }   # the task started by hand, not by the event
        @{ Value = '' }
        @{ Value = 'alice' }
        @{ Value = 'S-1-5-21-1-2-3\..\..\logs\x' }
        @{ Value = 'S-1-5-21-1-2-3/../x' }
        @{ Value = 'S-1-5-21-1-2-3.json' }
        @{ Value = 'S-1-5-21-1-2-3 ' }
        @{ Value = 'S-1-5-' }
    ) {
        ConvertTo-Sid $Value | Should -BeNullOrEmpty
    }
}

Describe 'Save-SessionFile' {
    BeforeEach {
        Mock Write-StatusLog {}
        $SessionDir = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $SessionDir | Out-Null
        $script:Path = Join-Path $SessionDir "$AliceSid.json"
    }

    It 'writes only what the logout run needs, to its SID-named file' {
        $response = [pscustomobject]@{ sessionId = 'sess-123'; action = 'in'; dn = 'CN=alice'; notes = 'x' }
        Save-SessionFile -Response $response -Sid $AliceSid -WindowsUserId 'alice'
        $saved = Get-Content -Raw -Path $Path | ConvertFrom-Json
        @($saved.PSObject.Properties.Name | Sort-Object) | Should -Be @('savedAtUtc', 'sessionId', 'userSid', 'windowsUserId')
        $saved.sessionId | Should -Be 'sess-123'
        $saved.windowsUserId | Should -Be 'alice'
        $saved.userSid | Should -Be $AliceSid
    }

    It 'never logs the sessionId' {
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 'sess-secret-42' }) -Sid $AliceSid -WindowsUserId 'alice'
        Should -Invoke Write-StatusLog -Times 0 -ParameterFilter { $Message -like '*sess-secret-42*' }
    }

    It 'replaces the previous login''s file' {
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 'first' }) -Sid $AliceSid -WindowsUserId 'alice'
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 'second' }) -Sid $AliceSid -WindowsUserId 'alice'
        (Get-Content -Raw -Path $Path | ConvertFrom-Json).sessionId | Should -Be 'second'
    }

    It 'still writes the file, with a warning, when the response has no sessionId' {
        Save-SessionFile -Response ([pscustomobject]@{ action = 'in' }) -Sid $AliceSid -WindowsUserId 'alice'
        (Get-Content -Raw -Path $Path | ConvertFrom-Json).sessionId | Should -BeNullOrEmpty
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' -and $Message -like '*no sessionId*' }
    }

    It 'creates nothing when the installer has not made sessions\' {
        $SessionDir = Join-Path $TestDrive 'never-installed'
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 'x' }) -Sid $AliceSid -WindowsUserId 'alice'
        Test-Path $SessionDir | Should -BeFalse
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' -and $Message -like '*re-run Install-InOutWorker.ps1*' }
    }
}

Describe 'Read-SessionFile' {
    BeforeEach {
        Mock Write-StatusLog {}
        Mock Test-TrustedPath { $true }
        $RunningAsSystem = $true
        $SessionDir = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $SessionDir | Out-Null
        $script:Path = Join-Path $SessionDir "$AliceSid.json"
        function Write-Session([string]$Json) { [System.IO.File]::WriteAllText($Path, $Json) }
    }

    It 'reads back what Save-SessionFile wrote' {
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 'sess-123' }) -Sid $AliceSid -WindowsUserId 'alice'
        $session = Read-SessionFile -Sid $AliceSid
        $session.SessionId | Should -Be 'sess-123'
        $session.WindowsUserId | Should -Be 'alice'
    }

    It 'checks the folder (SYSTEM or Administrators) and the file (the user too) before reading' {
        Write-Session "{ ""userSid"": ""$AliceSid"", ""sessionId"": ""s"" }"
        $null = Read-SessionFile -Sid $AliceSid
        Should -Invoke Test-TrustedPath -Times 1 -ParameterFilter {
            $Path -eq $SessionDir -and (@($Owners) -join ',') -eq "$SystemSid,$AdminsSid" }
        Should -Invoke Test-TrustedPath -Times 1 -ParameterFilter {
            $Path -like "*$AliceSid.json" -and (@($Owners) -join ',') -eq "$AliceSid,$SystemSid,$AdminsSid" }
    }

    It 'does not read anything when SYSTEM finds sessions\ untrusted' {
        Write-Session "{ ""userSid"": ""$AliceSid"", ""sessionId"": ""s"" }"
        Mock Test-TrustedPath { $false } -ParameterFilter { $Path -eq $SessionDir }
        Mock Get-Content { throw 'read' }
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
        Should -Invoke Get-Content -Times 0
    }

    It 'skips the folder check when run as the user' {
        $RunningAsSystem = $false
        Write-Session "{ ""userSid"": ""$AliceSid"", ""sessionId"": ""s"" }"
        (Read-SessionFile -Sid $AliceSid).SessionId | Should -Be 's'
        Should -Invoke Test-TrustedPath -Times 0 -ParameterFilter { $Path -eq $SessionDir }
    }

    It 'ignores a file that is a link or owned by someone else' {
        Write-Session "{ ""userSid"": ""$AliceSid"", ""sessionId"": ""s"" }"
        Mock Test-TrustedPath { $false } -ParameterFilter { $Path -like '*.json' }
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
    }

    It 'returns nothing, with a warning, when there is no file' {
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
        Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' -and $Message -like 'No session file*' }
    }

    It 'ignores a file over 64 KB' {
        Write-Session ("{ ""userSid"": ""$AliceSid"", ""sessionId"": ""s"", ""pad"": """ + ('x' * 70000) + '" }')
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
    }

    It 'ignores an empty file' {
        Write-Session ''
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
    }

    It 'ignores a file that is not JSON' {
        Write-Session 'sessionId=s'
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
        Should -Invoke Write-StatusLog -ParameterFilter { $Message -like 'Session file unreadable*' }
    }

    It 'ignores a file written for another SID' {
        Write-Session "{ ""userSid"": ""$BobSid"", ""sessionId"": ""theirs"" }"
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
    }

    It 'ignores a file with no userSid' {
        Write-Session '{ "sessionId": "s" }'
        Read-SessionFile -Sid $AliceSid | Should -BeNullOrEmpty
    }

    It 'takes sessionId and windowsUserId only when they are strings' {
        Write-Session "{ ""userSid"": ""$AliceSid"", ""sessionId"": { ""a"": 1 }, ""windowsUserId"": 42 }"
        $session = Read-SessionFile -Sid $AliceSid
        $session | Should -Not -BeNullOrEmpty
        $session.SessionId | Should -BeNullOrEmpty
        $session.WindowsUserId | Should -BeNullOrEmpty
    }
}

Describe 'Get-StatusBody' {
    BeforeEach {
        Mock Write-StatusLog {}
        Mock Get-AccountName { 'alice-from-sid' }
        Mock Read-SessionFile { $null }
    }

    It 'sends only windowsUserId for <action>' -TestCases @(
        @{ Action = 'login' }, @{ Action = 'lock' }, @{ Action = 'unlock' }
    ) {
        $body = Get-StatusBody -Action $Action -WindowsUserId 'alice' -UserSid '' -CurrentSid $AliceSid -RunningAsSystem $false
        @($body.Keys) | Should -Be @('windowsUserId')
        $body.windowsUserId | Should -Be 'alice'
        Should -Invoke Read-SessionFile -Times 0
    }

    Context 'logout run as the user (a remote disconnect)' {
        It 'reads the user''s own session file and sends its sessionId and name' {
            Mock Read-SessionFile { [pscustomobject]@{ SessionId = 'sess-1'; WindowsUserId = 'alice' } }
            $body = Get-StatusBody -Action logout -WindowsUserId 'ALICE' -UserSid '$(TargetUserSid)' `
                                   -CurrentSid $AliceSid -RunningAsSystem $false
            Should -Invoke Read-SessionFile -Times 1 -ParameterFilter { $Sid -eq $AliceSid }
            $body.sessionId | Should -Be 'sess-1'
            $body.windowsUserId | Should -Be 'alice'
            @($body.Keys | Sort-Object) | Should -Be @('sessionId', 'windowsUserId')
        }

        It 'keeps its own user name when the file has none' {
            Mock Read-SessionFile { [pscustomobject]@{ SessionId = 'sess-1'; WindowsUserId = $null } }
            $body = Get-StatusBody -Action logout -WindowsUserId 'alice' -UserSid '' -CurrentSid $AliceSid -RunningAsSystem $false
            $body.windowsUserId | Should -Be 'alice'
            Should -Invoke Get-AccountName -Times 0
        }

        It 'posts without a sessionId, with a warning, when there is no file' {
            $body = Get-StatusBody -Action logout -WindowsUserId 'alice' -UserSid '' -CurrentSid $AliceSid -RunningAsSystem $false
            $body.ContainsKey('sessionId') | Should -BeFalse
            Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'WARN' -and $Message -like 'Logout has no sessionId*' }
        }
    }

    Context 'logout run as SYSTEM (the Logout task)' {
        BeforeEach {
            # SecurityIdentifier is Windows-only; its parsing is tested on Windows below.
            Mock ConvertTo-Sid { if ($Value -match '^S-1-\d+(-\d+)+$') { $Value } }
        }

        It 'reads the session file of the user the logoff event names' {
            Mock Read-SessionFile { [pscustomobject]@{ SessionId = 'sess-2'; WindowsUserId = 'alice' } }
            $body = Get-StatusBody -Action logout -WindowsUserId 'WORKSTATION$' -UserSid $AliceSid `
                                   -CurrentSid $SystemSid -RunningAsSystem $true
            Should -Invoke Read-SessionFile -Times 1 -ParameterFilter { $Sid -eq $AliceSid }
            $body.sessionId | Should -Be 'sess-2'
            $body.windowsUserId | Should -Be 'alice'
        }

        It 'never falls back to its own SID (exit code 4)' {
            $body = Get-StatusBody -Action logout -WindowsUserId 'WORKSTATION$' -UserSid '$(TargetUserSid)' `
                                   -CurrentSid $SystemSid -RunningAsSystem $true
            $body | Should -BeNullOrEmpty
            Should -Invoke Read-SessionFile -Times 0
            Should -Invoke Write-StatusLog -ParameterFilter { $Level -eq 'ERROR' -and $Message -like 'Logout needs -UserSid*' }
        }

        It 'sends the account name of the SID, not SYSTEM''s, when there is no file' {
            $body = Get-StatusBody -Action logout -WindowsUserId 'WORKSTATION$' -UserSid $AliceSid `
                                   -CurrentSid $SystemSid -RunningAsSystem $true
            Should -Invoke Get-AccountName -Times 1 -ParameterFilter { $Sid -eq $AliceSid }
            $body.windowsUserId | Should -Be 'alice-from-sid'
            $body.ContainsKey('sessionId') | Should -BeFalse
        }

        It 'sends the account name when the file has a sessionId but no name' {
            Mock Read-SessionFile { [pscustomobject]@{ SessionId = 'sess-3'; WindowsUserId = $null } }
            $body = Get-StatusBody -Action logout -WindowsUserId 'WORKSTATION$' -UserSid $AliceSid `
                                   -CurrentSid $SystemSid -RunningAsSystem $true
            $body.sessionId | Should -Be 'sess-3'
            $body.windowsUserId | Should -Be 'alice-from-sid'
        }
    }
}

Describe 'Send-StatusPost' {
    BeforeAll {
        $script:Cert = New-TestCertificate -Subject 'CN=alice'
        $script:Url  = 'https://readyroom.example.com/api/check/in'
    }
    BeforeEach {
        Mock Write-StatusLog {}
        Mock Invoke-RestMethod { [pscustomobject]@{ action = 'in'; sessionId = 'sess-9' } }
    }

    It 'posts the body as compact JSON, anonymously' {
        $null = Send-StatusPost -Url 'https://readyroom.example.com/api/check/out' -TimeoutSeconds 7 `
                                -Body @{ windowsUserId = 'alice'; sessionId = 'sess-1' }
        Should -Invoke Invoke-RestMethod -Times 1 -ParameterFilter {
            $parsed = $Body | ConvertFrom-Json
            $Uri -eq 'https://readyroom.example.com/api/check/out' -and $Method -eq 'Post' -and
            $ContentType -eq 'application/json' -and $TimeoutSec -eq 7 -and
            $Body -notmatch '\s' -and $parsed.windowsUserId -eq 'alice' -and $parsed.sessionId -eq 'sess-1' -and
            $null -eq $Certificate -and -not $UseDefaultCredentials
        }
    }

    It 'presents the client certificate' {
        $null = Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice' } -Certificate $Cert -TimeoutSeconds 30
        Should -Invoke Invoke-RestMethod -Times 1 -ParameterFilter {
            $Certificate.Thumbprint -eq $Cert.Thumbprint -and -not $UseDefaultCredentials
        }
    }

    It 'signs in with the Windows logon and does not follow redirects' {
        $response = Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice' } -WindowsSignIn -TimeoutSeconds 30
        $response.action | Should -Be 'in'
        Should -Invoke Invoke-RestMethod -Times 1 -ParameterFilter {
            $UseDefaultCredentials -and $MaximumRedirection -eq 0 -and $null -eq $Certificate
        }
    }

    It 'fails Windows sign-in when the answer is not a check-in record (a login page)' {
        Mock Invoke-RestMethod { '<html>login</html>' }
        { Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice' } -WindowsSignIn -TimeoutSeconds 30 } |
            Should -Throw '*did not accept Windows sign-in*'
    }

    It 'explains the redirect Windows PowerShell 5.1 reports as InvalidOperationException' {
        Mock Invoke-RestMethod { throw (New-Object System.InvalidOperationException 'redirect') }
        { Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice' } -WindowsSignIn -TimeoutSeconds 30 } |
            Should -Throw '*redirected instead of accepting Windows sign-in*'
    }

    It 'passes the same exception on unchanged for certificate sign-in' {
        Mock Invoke-RestMethod { throw (New-Object System.InvalidOperationException 'something else') }
        { Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice' } -Certificate $Cert -TimeoutSeconds 30 } |
            Should -Throw 'something else'
    }

    It 'logs the URL but never the body, which carries the sessionId' {
        $null = Send-StatusPost -Url $Url -Body @{ windowsUserId = 'alice'; sessionId = 'sess-secret-77' } -TimeoutSeconds 30
        Should -Invoke Write-StatusLog -ParameterFilter { $Message -eq "POST $Url" }
        Should -Invoke Write-StatusLog -Times 0 -ParameterFilter { $Message -like '*sess-secret-77*' }
    }
}

Describe 'Get-HttpStatus' {
    It 'adds the server''s error code and message' {
        $record = New-Object System.Management.Automation.ErrorRecord(
            (New-Object System.Exception 'Forbidden'), 'x', 'NotSpecified', $null)
        $record.ErrorDetails = New-Object System.Management.Automation.ErrorDetails(
            '{"error":"license_required","message":"No license is installed."}')
        Get-HttpStatus -ErrorRecord $record | Should -Be ' [license_required] No license is installed.'
    }

    It 'adds nothing for a body that is not the server''s JSON' {
        $record = New-Object System.Management.Automation.ErrorRecord(
            (New-Object System.Exception 'Bad gateway'), 'x', 'NotSpecified', $null)
        $record.ErrorDetails = New-Object System.Management.Automation.ErrorDetails('<html>502</html>')
        Get-HttpStatus -ErrorRecord $record | Should -Be ''
    }
}

Describe 'Test-NoReparsePoint' {
    # Symbolic links behave the same way on Linux; junctions are Windows-only
    # and are covered in the Windows tests below.
    BeforeEach {
        $script:Real = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path (Join-Path $Real 'sessions') -Force | Out-Null
        $script:File = Join-Path $Real 'sessions/user.json'
        Set-Content -Path $File -Value '{}'
    }

    It 'accepts a path with no link anywhere in it' {
        Test-NoReparsePoint -Path $File | Should -BeTrue
    }

    It 'refuses a file that is a symbolic link' {
        $link = Join-Path $Real 'sessions/link.json'
        New-Item -ItemType SymbolicLink -Path $link -Target $File | Out-Null
        Test-NoReparsePoint -Path $link | Should -BeFalse
    }

    It 'refuses a path that passes through a linked folder' {
        $linkedDir = Join-Path $Real 'linked'
        New-Item -ItemType SymbolicLink -Path $linkedDir -Target (Join-Path $Real 'sessions') | Out-Null
        Test-NoReparsePoint -Path (Join-Path $linkedDir 'user.json') | Should -BeFalse
    }

    It 'throws for a path that does not exist' {
        { Test-NoReparsePoint -Path (Join-Path $Real 'missing.json') } | Should -Throw
    }
}

Describe 'Test-TrustedPath (Get-Acl mocked)' {
    # The decision only: Get-Acl is replaced, so nothing here says anything
    # about real ACLs. See 'Windows' below for those.
    BeforeEach {
        $script:File = Join-Path $TestDrive 'trusted.json'
        Set-Content -Path $File -Value '{}'
    }

    It 'trusts a path owned by one of the given SIDs' {
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-32-544' }
        Test-TrustedPath -Path $File -Owners @('S-1-5-18', 'S-1-5-32-544') | Should -BeTrue
    }

    It 'does not trust a path owned by anyone else' {
        Mock Get-Acl { New-FakeAcl -OwnerSid $BobSid }
        Test-TrustedPath -Path $File -Owners @($AliceSid, 'S-1-5-18', 'S-1-5-32-544') | Should -BeFalse
    }

    It 'does not look at the owner of a link' {
        Mock Test-NoReparsePoint { $false }
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-18' }
        Test-TrustedPath -Path $File -Owners @('S-1-5-18') | Should -BeFalse
        Should -Invoke Get-Acl -Times 0
    }

    It 'does not trust what it cannot check' {
        Mock Get-Acl { throw 'access denied' }
        Test-TrustedPath -Path $File -Owners @('S-1-5-18') | Should -BeFalse
    }

    It 'does not trust a missing path' {
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-18' }
        Test-TrustedPath -Path (Join-Path $TestDrive 'missing') -Owners @('S-1-5-18') | Should -BeFalse
    }
}

Describe 'Write-StatusLog' {
    BeforeEach {
        Mock Write-Host {}
        $script:LogDirTrusted = $null
        $LogDir  = Join-Path $TestDrive ([guid]::NewGuid())
        $LogFile = Join-Path $LogDir 'inoutworker-test.log'
    }

    It 'creates the user''s log folder and appends to the log' {
        $RunningAsSystem = $false
        Write-StatusLog 'first'
        Write-StatusLog 'second' 'WARN'
        $lines = @(Get-Content -Path $LogFile)
        $lines.Count | Should -Be 2
        $lines[1] | Should -Match '^\[\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\]\[WARN\]\[login\] second$'
    }

    It 'still logs under -WhatIf' {
        $RunningAsSystem = $false
        $WhatIfPreference = $true
        Write-StatusLog 'dry run'
        Get-Content -Path $LogFile | Should -Match 'dry run'
    }

    It 'as SYSTEM, never creates the folder, and writes nothing into an untrusted one' {
        $RunningAsSystem = $true
        Mock Test-TrustedPath { $false }
        Write-StatusLog 'nothing'
        Test-Path $LogDir | Should -BeFalse
        New-Item -ItemType Directory -Path $LogDir | Out-Null
        Write-StatusLog 'still nothing'
        Test-Path $LogFile | Should -BeFalse
        Should -Invoke Test-TrustedPath -Times 1 -ParameterFilter {
            (@($Owners) -join ',') -eq "$SystemSid,$AdminsSid" }
    }

    It 'as SYSTEM, writes into a trusted folder' {
        $RunningAsSystem = $true
        Mock Test-TrustedPath { $true }
        New-Item -ItemType Directory -Path $LogDir | Out-Null
        Write-StatusLog 'written'
        Get-Content -Path $LogFile | Should -Match 'written'
    }

    It 'never fails the run' {
        $RunningAsSystem = $false
        # Not Join-Path: Windows PowerShell 5.1 refuses the NUL there already.
        $LogFile = $LogDir + [System.IO.Path]::DirectorySeparatorChar + 'bad' + [char]0 + 'name.log'
        { Write-StatusLog 'unwritable' } | Should -Not -Throw
    }
}

Describe 'Wait-ForUserProfile' {
    BeforeEach { Mock Start-Sleep {} }

    It 'does not wait without a user name' {
        Mock Get-CimInstance { throw 'not called' }
        Wait-ForUserProfile -Username '' -TimeoutSeconds 30 | Should -BeTrue
        Should -Invoke Get-CimInstance -Times 0
    }

    It 'returns once the user''s profile is loaded' {
        # A name with regex metacharacters, matched literally.
        $script:ProfileDir = Join-Path $TestDrive 'a.l(ice)'
        New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null
        Mock Get-CimInstance { @([pscustomobject]@{ LocalPath = (Join-Path $TestDrive 'other') },
                                 [pscustomobject]@{ LocalPath = $ProfileDir }) }
        Wait-ForUserProfile -Username 'a.l(ice)' -TimeoutSeconds 30 | Should -BeTrue
        Should -Invoke Get-CimInstance -Times 1 -ParameterFilter { $ClassName -eq 'Win32_UserProfile' -and $Filter -eq 'Loaded=True' }
    }

    It 'gives up once the time is up' {
        Mock Get-CimInstance { @() }
        Wait-ForUserProfile -Username 'alice' -TimeoutSeconds 0 | Should -BeFalse
    }
}

Describe 'Get-AccountName' {
    It 'falls back to the SID itself when the SID resolves to no account' {
        Get-AccountName -Sid 'S-1-5-21-1-2-3-424242' | Should -Be 'S-1-5-21-1-2-3-424242'
    }
}

Describe 'Windows' -Tag 'Windows' -Skip:(-not $OnWindows) {
    # Real SIDs, ACLs and junctions. Nothing here is mocked.

    It 'takes a well-formed SID as it is' {
        ConvertTo-Sid 'S-1-5-18' | Should -BeExactly 'S-1-5-18'
        ConvertTo-Sid $AliceSid | Should -BeExactly $AliceSid
    }

    It 'names the SYSTEM account' {
        Get-AccountName -Sid 'S-1-5-18' | Should -Be 'SYSTEM'
    }

    It 'gives the session file a DACL of its own: the user, SYSTEM and Administrators only' {
        Mock Write-StatusLog {}
        $SessionDir = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $SessionDir | Out-Null
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        Save-SessionFile -Response ([pscustomobject]@{ sessionId = 's' }) -Sid $me -WindowsUserId $env:USERNAME
        Should -Invoke Write-StatusLog -Times 0 -ParameterFilter { $Message -like 'Could not restrict ACL*' }

        $acl = Get-Acl -LiteralPath (Join-Path $SessionDir "$me.json")
        $acl.AreAccessRulesProtected | Should -BeTrue
        $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        @($rules | Where-Object { $_.IsInherited }).Count | Should -Be 0
        @($rules | Where-Object { $_.AccessControlType -ne 'Allow' }).Count | Should -Be 0
        @($rules | ForEach-Object { $_.IdentityReference.Value } | Sort-Object) |
            Should -Be @(@($me, 'S-1-5-18', 'S-1-5-32-544') | Sort-Object)
        $mine = $rules | Where-Object { $_.IdentityReference.Value -eq $me }
        ($mine.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Delete) | Should -Be 0
        ($mine.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ChangePermissions) | Should -Be 0
    }

    It 'trusts a file by its real owner, and nobody else' {
        $file = Join-Path $TestDrive 'owned.json'
        Set-Content -Path $file -Value '{}'
        $owner = (Get-Acl -LiteralPath $file).GetOwner([Security.Principal.SecurityIdentifier]).Value
        Test-TrustedPath -Path $file -Owners @($owner) | Should -BeTrue
        Test-TrustedPath -Path $file -Owners @($BobSid) | Should -BeFalse
    }

    It 'refuses a path through a junction' {
        $target = Join-Path $TestDrive 'junction-target'
        New-Item -ItemType Directory -Path $target | Out-Null
        Set-Content -Path (Join-Path $target 'user.json') -Value '{}'
        $junction = Join-Path $TestDrive 'junction'
        New-Item -ItemType Junction -Path $junction -Target $target | Out-Null
        Test-NoReparsePoint -Path (Join-Path $junction 'user.json') | Should -BeFalse
        $owner = (Get-Acl -LiteralPath $target).GetOwner([Security.Principal.SecurityIdentifier]).Value
        Test-TrustedPath -Path (Join-Path $junction 'user.json') -Owners @($owner) | Should -BeFalse
    }

    Context 'the script, run the way the tasks run it, with -WhatIf' {
        BeforeAll {
            $script:Exe = (Get-Process -Id $PID).Path
            $script:SavedLocalAppData = $env:LOCALAPPDATA
            $env:LOCALAPPDATA = Join-Path $TestDrive 'localappdata'
            function Invoke-Agent {
                $output = & $Exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $AgentPath @args 2>&1
                [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output | Out-String) }
            }
        }
        AfterAll { $env:LOCALAPPDATA = $SavedLocalAppData }

        It 'exits 0 and says what it would post' {
            $run = Invoke-Agent -Action lock -Authentication Windows -BaseUrl 'https://readyroom.example.com/api/check/' -WhatIf
            $run.ExitCode | Should -Be 0 -Because $run.Output
            $run.Output | Should -Match ([regex]::Escape('WhatIf: would POST to https://readyroom.example.com/api/check/lock'))
            $run.Output | Should -Match 'signing in with: Windows'
        }

        It 'turns server certificate checks off with -SkipCertificateCheck' {
            # Resolve-Configuration once read $PSBoundParameters itself - its own,
            # empty set - so this switch on the command line did nothing.
            $run = Invoke-Agent -Action lock -Authentication Windows -BaseUrl 'https://readyroom.example.com/api/check' -SkipCertificateCheck -WhatIf
            $run.ExitCode | Should -Be 0 -Because $run.Output
            $run.Output | Should -Match 'Server certificate validation DISABLED'
        }

        It 'exits 1 on a configuration error' {
            $run = Invoke-Agent -Action lock -BaseUrl 'ftp://readyroom.example.com' -WhatIf
            $run.ExitCode | Should -Be 1
            $run.Output | Should -Match 'Configuration error'
        }

        It 'logs out as the user without -UserSid, anonymously' {
            $run = Invoke-Agent -Action logout -BaseUrl 'https://readyroom.example.com/api/check' -UserSid '$(TargetUserSid)' -WhatIf
            $run.ExitCode | Should -Be 0 -Because $run.Output
            $run.Output | Should -Match 'signing in with: Anonymous'
        }

        It 'writes its log to the user''s profile' {
            $null = Invoke-Agent -Action lock -Authentication Windows -BaseUrl 'https://readyroom.example.com/api/check' -WhatIf
            @(Get-ChildItem (Join-Path $env:LOCALAPPDATA 'InOutWorker\logs') -Filter 'inoutworker-*.log').Count | Should -Be 1
        }
    }
}
