#Requires -Modules @{ ModuleName = 'Pester'; ModuleVersion = '5.5.0' }
<#
  Tests for Install-InOutWorker.ps1.

  The installer is dot-sourced, which defines its functions and stops before it
  checks for elevation or changes anything. Links and folder moves use the real
  file system (symbolic links behave the same on Linux); Get-Acl and
  Set-FolderAcl are mocked, so those tests check the installer's decisions, not
  the ACLs it writes. The "Windows" tagged tests use real ACLs and junctions,
  and only run on Windows. Registering the tasks and the ACLs Set-FolderAcl
  writes are not covered here: that needs an elevated Windows run.
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
    $script:OnWindows    = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
    $script:ClientDir    = Split-Path $PSScriptRoot -Parent
    $script:InstallerPath = Join-Path $ClientDir 'Install-InOutWorker.ps1'

    if (-not (Get-Command Get-Acl -ErrorAction SilentlyContinue)) {
        function Get-Acl { param([string]$LiteralPath) }
    }

    # The installer builds its data paths from %ProgramData%, which only Windows sets.
    $script:SavedProgramData = $env:ProgramData
    if (-not $env:ProgramData) { $env:ProgramData = Join-Path $TestDrive 'ProgramData' }

    . $InstallerPath

    function New-FakeAcl {
        param([string]$OwnerSid, [bool]$Protected = $true)
        $acl = [pscustomobject]@{ AreAccessRulesProtected = $Protected; OwnerSid = $OwnerSid }
        $acl | Add-Member -MemberType ScriptMethod -Name GetOwner -Value {
            param($Type)
            $this.OwnerSid
        }
        return $acl
    }
}

AfterAll {
    $env:ProgramData = $SavedProgramData
}

Describe 'Command line' {
    It 'accepts only Certificate, Windows or Auto as -Authentication' {
        $set = (Get-Command $InstallerPath).Parameters['Authentication'].Attributes |
            Where-Object { $_ -is [System.Management.Automation.ValidateSetAttribute] }
        @($set.ValidValues | Sort-Object) | Should -Be @('Auto', 'Certificate', 'Windows')
    }

    It 'supports -WhatIf' {
        (Get-Command $InstallerPath).Parameters.ContainsKey('WhatIf') | Should -BeTrue
    }
}

Describe 'Get-ItemAttribute and Test-ReparsePoint' {
    BeforeEach {
        $script:Dir = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $Dir | Out-Null
    }

    It 'returns nothing for a missing item' {
        Get-ItemAttribute (Join-Path $Dir 'missing') | Should -BeNullOrEmpty
        Test-ReparsePoint (Get-ItemAttribute (Join-Path $Dir 'missing')) | Should -BeFalse
    }

    It 'sees a folder as a folder, not a link' {
        $attributes = Get-ItemAttribute $Dir
        ($attributes -band [System.IO.FileAttributes]::Directory) | Should -BeTrue
        Test-ReparsePoint $attributes | Should -BeFalse
    }

    It 'sees the link itself, not what it points to' {
        $link = Join-Path $TestDrive "$([guid]::NewGuid())-link"
        New-Item -ItemType SymbolicLink -Path $link -Target $Dir | Out-Null
        Test-ReparsePoint (Get-ItemAttribute $link) | Should -BeTrue
    }

    It 'reads the ReparsePoint flag in <attributes>' -TestCases @(
        @{ Attributes = [System.IO.FileAttributes]'Directory, ReparsePoint'; Expected = $true }
        @{ Attributes = [System.IO.FileAttributes]'Archive, ReparsePoint'; Expected = $true }
        @{ Attributes = [System.IO.FileAttributes]'Directory, Hidden, System'; Expected = $false }
        @{ Attributes = [System.IO.FileAttributes]::Normal; Expected = $false }
    ) {
        Test-ReparsePoint $Attributes | Should -Be $Expected
    }
}

Describe 'Remove-Link' {
    BeforeEach {
        Mock Write-Host {}
        $script:Target = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $Target | Out-Null
        Set-Content -Path (Join-Path $Target 'precious.txt') -Value 'keep me'
    }

    It 'removes a link to a folder, and leaves the folder and its contents' {
        $link = "$Target-link"
        New-Item -ItemType SymbolicLink -Path $link -Target $Target | Out-Null
        Remove-Link $link
        Get-ItemAttribute $link | Should -BeNullOrEmpty
        Get-Content (Join-Path $Target 'precious.txt') | Should -Be 'keep me'
    }

    It 'removes a link to a file, and leaves the file' {
        $file = Join-Path $Target 'precious.txt'
        $link = "$Target-file-link"
        New-Item -ItemType SymbolicLink -Path $link -Target $file | Out-Null
        Remove-Link $link
        Get-ItemAttribute $link | Should -BeNullOrEmpty
        Get-Content $file | Should -Be 'keep me'
    }
}

Describe 'Test-InstallerFolder (Get-Acl mocked)' {
    BeforeEach {
        # Strings stand in for the SecurityIdentifier objects the installer
        # makes, which only exist on Windows.
        $SystemSid = 'S-1-5-18'
        $AdminsSid = 'S-1-5-32-544'
        $script:Dir = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $Dir | Out-Null
    }

    It 'accepts a protected folder owned by <owner>' -TestCases @(
        @{ Owner = 'S-1-5-32-544' }, @{ Owner = 'S-1-5-18' }
    ) {
        Mock Get-Acl { New-FakeAcl -OwnerSid $Owner }
        Test-InstallerFolder $Dir | Should -BeTrue
    }

    It 'refuses a folder that inherits from its parent' {
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-32-544' -Protected $false }
        Test-InstallerFolder $Dir | Should -BeFalse
    }

    It 'refuses a folder an ordinary user owns' {
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-21-1-2-3-1001' }
        Test-InstallerFolder $Dir | Should -BeFalse
    }

    It 'refuses a missing folder, a file, or a link, without reading an ACL' {
        Mock Get-Acl { New-FakeAcl -OwnerSid 'S-1-5-32-544' }
        $file = Join-Path $Dir 'file'
        Set-Content -Path $file -Value 'x'
        $link = "$Dir-link"
        New-Item -ItemType SymbolicLink -Path $link -Target $Dir | Out-Null

        Test-InstallerFolder (Join-Path $Dir 'missing') | Should -BeFalse
        Test-InstallerFolder $file | Should -BeFalse
        Test-InstallerFolder $link | Should -BeFalse
        Should -Invoke Get-Acl -Times 0
    }
}

Describe 'Initialize-ProtectedFolder' {
    BeforeEach {
        Mock Write-Host {}
        Mock Set-FolderAcl {}
        $script:Parent = Join-Path $TestDrive ([guid]::NewGuid())
        New-Item -ItemType Directory -Path $Parent | Out-Null
        $script:Folder = Join-Path $Parent 'sessions'
        $script:Grants = @(,@('S-1-5-18', 'FullControl', 'ContainerInherit, ObjectInherit'))
    }

    It 'creates a missing folder and sets its ACL' {
        Initialize-ProtectedFolder -Path $Folder -Grants $Grants
        Test-Path -LiteralPath $Folder -PathType Container | Should -BeTrue
        Should -Invoke Set-FolderAcl -Times 1 -ParameterFilter { $Path -eq $Folder -and $Grants[0][0] -eq 'S-1-5-18' }
    }

    It 'keeps a folder this installer made, with what is in it' {
        Mock Test-InstallerFolder { $true }
        New-Item -ItemType Directory -Path $Folder | Out-Null
        Set-Content -Path (Join-Path $Folder 'user.json') -Value '{}'
        Initialize-ProtectedFolder -Path $Folder -Grants $Grants
        Test-Path (Join-Path $Folder 'user.json') | Should -BeTrue
        @(Get-ChildItem $Parent).Count | Should -Be 1
        Should -Invoke Set-FolderAcl -Times 1
    }

    It 'moves a folder it did not make aside, unopened, and starts a new one' {
        Mock Test-InstallerFolder { $false }
        New-Item -ItemType Directory -Path $Folder | Out-Null
        Set-Content -Path (Join-Path $Folder 'planted.json') -Value '{}'
        Initialize-ProtectedFolder -Path $Folder -Grants $Grants

        @(Get-ChildItem $Folder).Count | Should -Be 0
        $aside = @(Get-ChildItem $Parent -Filter 'sessions.old-*')
        $aside.Count | Should -Be 1
        $aside[0].Name | Should -Match '^sessions\.old-\d{14}$'
        Test-Path (Join-Path $aside[0].FullName 'planted.json') | Should -BeTrue
        Should -Invoke Set-FolderAcl -Times 1 -ParameterFilter { $Path -eq $Folder }
    }

    It 'moves a file in the folder''s place aside too' {
        Set-Content -Path $Folder -Value 'not a folder'
        Initialize-ProtectedFolder -Path $Folder -Grants $Grants
        Test-Path -LiteralPath $Folder -PathType Container | Should -BeTrue
        $aside = @(Get-ChildItem $Parent -Filter 'sessions.old-*')
        $aside.Count | Should -Be 1
        Get-Content $aside[0].FullName | Should -Be 'not a folder'
    }

    It 'replaces a link with a real folder, and leaves its target alone' {
        $target = Join-Path $TestDrive "$([guid]::NewGuid())-elsewhere"
        New-Item -ItemType Directory -Path $target | Out-Null
        Set-Content -Path (Join-Path $target 'precious.txt') -Value 'keep me'
        New-Item -ItemType SymbolicLink -Path $Folder -Target $target | Out-Null

        Initialize-ProtectedFolder -Path $Folder -Grants $Grants

        Test-ReparsePoint (Get-ItemAttribute $Folder) | Should -BeFalse
        Test-Path -LiteralPath $Folder -PathType Container | Should -BeTrue
        @(Get-ChildItem $Folder).Count | Should -Be 0
        Get-Content (Join-Path $target 'precious.txt') | Should -Be 'keep me'
        Should -Invoke Set-FolderAcl -Times 1 -ParameterFilter { $Path -eq $Folder }
    }
}

Describe 'Scheduled task definitions' {
    BeforeDiscovery {
        $taskFiles = @(Get-ChildItem (Join-Path (Split-Path $PSScriptRoot -Parent) 'tasks') -Filter '*.xml' |
            ForEach-Object { @{ File = $_.FullName; Name = $_.BaseName } })
    }
    BeforeAll {
        $script:InstallTo = Join-Path $TestDrive 'Install Dir'
    }

    It 'has one file for each task the installer knows' {
        $names = @(Get-ChildItem (Join-Path $ClientDir 'tasks') -Filter '*.xml' |
            ForEach-Object { (Get-TaskDefinition -Path $_.FullName -InstallDir $InstallTo).Name })
        @($names | Sort-Object) | Should -Be @($TaskNames | Sort-Object)
    }

    It '<name> is named after its URI and runs the installed script' -TestCases $taskFiles {
        $task = Get-TaskDefinition -Path $File -InstallDir $InstallTo
        $task.Name | Should -Be ($Name -replace '^InOutWork-', 'InOutWork - ')
        $task.Xml | Should -Not -Match ([regex]::Escape('C:\Program Files\InOutWorker\Update-WorkStatus.ps1'))
        $task.Xml | Should -Match ([regex]::Escape("-File ""$(Join-Path $InstallTo 'Update-WorkStatus.ps1')"""))
        ([xml]$task.Xml).Task.Actions.Exec.Command | Should -Be 'powershell.exe'
    }

    It '<name> reports the right action, as the right account' -TestCases $taskFiles {
        $task = [xml](Get-TaskDefinition -Path $File -InstallDir $InstallTo).Xml
        $arguments = $task.Task.Actions.Exec.Arguments
        $principal = $task.Task.Principals.Principal
        $expected = @{
            'InOutWork-Login'      = 'login'
            'InOutWork-Logout'     = 'logout'
            'InOutWork-Disconnect' = 'logout'
            'InOutWork-Lock'       = 'lock'
            'InOutWork-Unlock'     = 'unlock'
        }[$Name]
        $arguments | Should -Match "-Action $expected(\s|$)"
        $arguments | Should -Match '-NonInteractive'
        $arguments | Should -Not -Match 'SkipCertificateCheck'
        if ($Name -eq 'InOutWork-Logout') {
            # Runs as SYSTEM, told whose session ended by the logoff event.
            $principal.UserId | Should -Be 'S-1-5-18'
            $arguments | Should -Match ([regex]::Escape('-UserSid "$(TargetUserSid)"'))
            $task.Task.Triggers.EventTrigger.ValueQueries.Value.name | Should -Be 'TargetUserSid'
        } else {
            # Runs as the user: the Users group, never SYSTEM.
            $principal.GroupId | Should -Be 'S-1-5-32-545'
            $principal.PSObject.Properties.Name | Should -Not -Contain 'UserId'
        }
    }

    It 'skips a file with no URI' {
        $file = Join-Path $TestDrive 'no-uri.xml'
        $xml = '<?xml version="1.0" encoding="UTF-16"?><Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task"><RegistrationInfo /></Task>'
        [System.IO.File]::WriteAllText($file, $xml, [System.Text.Encoding]::Unicode)
        Get-TaskDefinition -Path $file -InstallDir $InstallTo | Should -BeNullOrEmpty
    }
}

Describe 'Windows' -Tag 'Windows' -Skip:(-not $OnWindows) {
    BeforeAll {
        $SystemSid = New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'
        $AdminsSid = New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'
    }

    It 'refuses a new folder, which inherits its parent''s ACL' {
        $dir = Join-Path $TestDrive 'inherits'
        New-Item -ItemType Directory -Path $dir | Out-Null
        Test-InstallerFolder $dir | Should -BeFalse
    }

    It 'sees a junction as a link' {
        $target = Join-Path $TestDrive 'junction-target'
        New-Item -ItemType Directory -Path $target | Out-Null
        $junction = Join-Path $TestDrive 'junction'
        New-Item -ItemType Junction -Path $junction -Target $target | Out-Null
        Test-ReparsePoint (Get-ItemAttribute $junction) | Should -BeTrue
        Test-InstallerFolder $junction | Should -BeFalse
    }

    It 'removes a junction, not its target' {
        Mock Write-Host {}
        $target = Join-Path $TestDrive 'junction-target-2'
        New-Item -ItemType Directory -Path $target | Out-Null
        Set-Content -Path (Join-Path $target 'precious.txt') -Value 'keep me'
        $junction = Join-Path $TestDrive 'junction-2'
        New-Item -ItemType Junction -Path $junction -Target $target | Out-Null
        Remove-Link $junction
        Get-ItemAttribute $junction | Should -BeNullOrEmpty
        Get-Content (Join-Path $target 'precious.txt') | Should -Be 'keep me'
    }
}
