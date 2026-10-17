<#
.SYNOPSIS
  Reports a workstation session event (login / logout / lock / unlock) to the
  In-N-Out-Work server.

.DESCRIPTION
  Replaces login.ps1, logout.ps1 and updatestatus.ps1 with a single entry point
  invoked from Task Scheduler with an -Action argument.

  Transport model (mirrors SecurityConfig on the server):

    Action   Endpoint                 Auth                    Runs as
    ------   ----------------------   ---------------------   ---------------------------
    login    POST /api/check/in       certificate or Windows  the user (interactive)
    lock     POST /api/check/lock     certificate or Windows  the user (session change)
    unlock   POST /api/check/unlock   certificate or Windows  the user (session change)
    logout   POST /api/check/out      anonymous               SYSTEM at logoff, profile gone;
                                                              the user on a remote disconnect

  Only /api/check/out is permitAll on the server; every other action must sign
  in. How is set by Authentication:

    Certificate  the user's client certificate (the default, as before)
    Windows      the user's Windows logon (Kerberos). Needs a domain-joined PC,
                 a domain account, BaseUrl using the server's registered host
                 name (not an IP address), and application.windows-auth
                 enabled on the server.
    Auto         Windows first; the certificate if that fails, e.g. on a
                 laptop that cannot reach a domain controller.

  Because the logoff task runs after the user profile has unloaded, the user's
  certificate store is not reachable at that point. To keep the record tied to
  the right identity, the login response - which contains a server-generated
  sessionId - is cached in a session file that the logout run reads back and
  echoes to the server, which resolves the DN via lookupBySessionId().

  Each user has their own session file, %ProgramData%\InOutWorker\sessions\<SID>.json,
  readable only by that user, SYSTEM and administrators. The SYSTEM run is told
  whose session ended by the logoff event (-UserSid), reads that file only
  after checking that no part of its path is a junction or link and that the
  user (or an administrator) owns it, and never writes or deletes anything in
  a folder an ordinary user can add to. The next login overwrites the file.

  Logs: SYSTEM writes to %ProgramData%\InOutWorker\logs, which only SYSTEM and
  administrators can write; a user's own runs log to
  %LOCALAPPDATA%\InOutWorker\logs.

.PARAMETER Action
  Which session event to report: login, logout, lock or unlock.

.PARAMETER UserSid
  For logout run as SYSTEM: the SID of the user whose session ended. The Logout
  task passes it from the logoff event (TargetUserSid). Run as a user, the
  user's own SID is used.

.PARAMETER BaseUrl
  Base API URL, e.g. https://host:8444/api/check. Overrides the config file.

.PARAMETER Authentication
  How login, lock and unlock sign in: Certificate (default), Windows or Auto.
  Overrides the config file.

.PARAMETER CertificateThumbprint
  Thumbprint of the client certificate to use. Overrides the config file.

.PARAMETER CertificateSubjectContains
  Substring matched against certificate subjects when no thumbprint matches.

.PARAMETER SkipCertificateCheck
  Disable server TLS validation. Intended for lab use with self-signed server
  certificates only - it is off by default, unlike the scripts this replaces.

.EXAMPLE
  powershell.exe -ExecutionPolicy Bypass -File "C:\Program Files\InOutWorker\Update-WorkStatus.ps1" -Action login

.EXAMPLE
  # Verify configuration and certificate selection without contacting the server.
  .\Update-WorkStatus.ps1 -Action lock -WhatIf

.NOTES
  Exit codes: 0 success, 1 configuration error, 2 no usable certificate,
              3 HTTP request failed, 4 logout run as SYSTEM without a valid -UserSid.
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('login', 'logout', 'lock', 'unlock')]
    [string]$Action,

    [string]$UserSid,
    [string]$BaseUrl,
    [ValidateSet('Certificate', 'Windows', 'Auto')]
    [string]$Authentication,
    [string]$CertificateThumbprint,
    [string]$CertificateSubjectContains,
    [string]$ConfigPath,
    [switch]$SkipCertificateCheck,
    [int]$TimeoutSec = 30,
    [int]$ProfileWaitTimeoutSec = 30
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Defaults. Anything here can be overridden by the config file, which in turn
# is overridden by an explicit parameter, so the script itself never needs to
# be edited per deployment.
# ---------------------------------------------------------------------------
$Defaults = @{
    BaseUrl                    = 'https://192.168.1.29:8444/api/check'
    Authentication             = 'Certificate'
    CertificateThumbprint      = ''
    CertificateSubjectContains = ''
    SkipCertificateCheck       = $false
}

# login/lock/unlock are authenticated; only logout is anonymous.
$ActionMap = @{
    login  = @{ Endpoint = 'in';     Authenticated = $true;  WaitForProfile = $true  }
    lock   = @{ Endpoint = 'lock';   Authenticated = $true;  WaitForProfile = $false }
    unlock = @{ Endpoint = 'unlock'; Authenticated = $true;  WaitForProfile = $true  }
    logout = @{ Endpoint = 'out';    Authenticated = $false; WaitForProfile = $false }
}

$SystemSid = 'S-1-5-18'
$AdminsSid = 'S-1-5-32-544'

$CurrentSid      = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$RunningAsSystem = $CurrentSid -eq $SystemSid

# The installer creates both folders and sets their permissions: sessions\ lets
# a user add a file but not list, change or delete anyone else's, and logs\ is
# writable by SYSTEM and administrators only.
$DataDir    = Join-Path $env:ProgramData 'InOutWorker'
$SessionDir = Join-Path $DataDir 'sessions'

if ($RunningAsSystem) {
    $LogDir = Join-Path $DataDir 'logs'
} elseif ($env:LOCALAPPDATA) {
    # A user cannot write to the SYSTEM log folder, so their runs log to their profile.
    $LogDir = Join-Path $env:LOCALAPPDATA 'InOutWorker\logs'
} else {
    $LogDir = $null
}
$LogFile       = $null
$LogDirTrusted = $null
if ($LogDir) {
    $LogFile = Join-Path $LogDir ("inoutworker-{0}.log" -f (Get-Date -Format 'yyyyMMdd'))
}

# ---------------------------------------------------------------------------
# Paths SYSTEM touches. It reads or writes under %ProgramData%\InOutWorker only
# after checking that no part of the path is a junction or symbolic link and
# that the item is owned by an account an ordinary user cannot act as, so a
# user cannot redirect it to a file of their choosing.
# ---------------------------------------------------------------------------
function Test-NoReparsePoint {
    param([string]$Path)

    # GetAttributes reports a link itself rather than its target. Walk every
    # parent up to the root; a missing item throws, which the caller treats as
    # untrusted.
    $current = $Path
    while ($current) {
        $attributes = [System.IO.File]::GetAttributes($current)
        if ($attributes -band [System.IO.FileAttributes]::ReparsePoint) { return $false }
        $current = [System.IO.Path]::GetDirectoryName($current)
    }
    return $true
}

function Test-TrustedPath {
    param([string]$Path, [string[]]$Owners)

    # No logging here: Write-Log calls this.
    try {
        if (-not (Test-NoReparsePoint -Path $Path)) { return $false }
        $owner = (Get-Acl -LiteralPath $Path).GetOwner([Security.Principal.SecurityIdentifier]).Value
        return ($Owners -contains $owner)
    } catch {
        return $false
    }
}

# ---------------------------------------------------------------------------
# Logging - Write-Host alone is invisible under Task Scheduler, so everything
# is also appended to a dated log file.
# ---------------------------------------------------------------------------
function Write-Log {
    param(
        [string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
    )

    $line = "[{0}][{1}][{2}] {3}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'),
                                     $Level, $Action, $Message

    switch ($Level) {
        'ERROR' { Write-Host $line -ForegroundColor Red }
        'WARN'  { Write-Host $line -ForegroundColor Yellow }
        default { Write-Host $line }
    }

    if (-not $LogFile) { return }

    try {
        if ($RunningAsSystem) {
            # SYSTEM never creates the folder, and writes only into the one the
            # installer locked down.
            if ($null -eq $script:LogDirTrusted) {
                $script:LogDirTrusted = Test-TrustedPath -Path $LogDir -Owners @($SystemSid, $AdminsSid)
            }
            if (-not $script:LogDirTrusted) { return }
        } elseif (-not (Test-Path $LogDir)) {
            # -WhatIf:$false so a dry run still records what it decided; the log is
            # diagnostics, not one of the effects the caller is previewing.
            New-Item -ItemType Directory -Path $LogDir -Force -WhatIf:$false | Out-Null
        }
        Add-Content -Path $LogFile -Value $line -Encoding UTF8 -WhatIf:$false
    } catch {
        # Logging must never be the reason the run fails.
    }
}

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
function Resolve-Configuration {
    $config = $Defaults.Clone()

    # Only from beside the script, in the install folder administrators own -
    # never from %ProgramData%, which earlier versions let any user write to.
    $candidates = @()
    if ($ConfigPath) { $candidates += $ConfigPath }
    $candidates += (Join-Path $PSScriptRoot 'inoutworker.config.json')

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            try {
                $fromFile = Get-Content -Path $candidate -Raw | ConvertFrom-Json
                foreach ($key in @($config.Keys)) {
                    if ($fromFile.PSObject.Properties.Name -contains $key) {
                        $config[$key] = $fromFile.$key
                    }
                }
                Write-Log "Loaded configuration from $candidate"
            } catch {
                Write-Log "Ignoring unreadable config ${candidate}: $($_.Exception.Message)" 'WARN'
            }
            break
        }
    }

    # Explicit parameters win over the file.
    if ($BaseUrl)                    { $config.BaseUrl                    = $BaseUrl }
    if ($Authentication)             { $config.Authentication             = $Authentication }
    if ($CertificateThumbprint)      { $config.CertificateThumbprint      = $CertificateThumbprint }
    if ($CertificateSubjectContains) { $config.CertificateSubjectContains = $CertificateSubjectContains }
    if ($PSBoundParameters.ContainsKey('SkipCertificateCheck')) {
        $config.SkipCertificateCheck = [bool]$SkipCertificateCheck
    }

    $config.BaseUrl = $config.BaseUrl.TrimEnd('/')

    if ([string]::IsNullOrWhiteSpace($config.BaseUrl)) {
        throw 'BaseUrl is not configured.'
    }
    if ($config.BaseUrl -notmatch '^https?://') {
        throw "BaseUrl '$($config.BaseUrl)' is not a valid URL."
    }

    $valid = @('Certificate', 'Windows', 'Auto')
    $match = @($valid | Where-Object { $_ -eq [string]$config.Authentication })
    if ($match.Count -eq 0) {
        throw "Authentication '$($config.Authentication)' is not one of: $($valid -join ', ')."
    }
    $config.Authentication = $match[0]

    if ($config.Authentication -ne 'Certificate' -and ([uri]$config.BaseUrl).HostNameType -ne 'Dns') {
        # Kerberos tickets are issued for host names. With an IP address Windows
        # falls back to NTLM, which the server does not accept.
        Write-Log "BaseUrl uses an IP address; Windows sign-in needs the server's host name." 'WARN'
    }

    return $config
}

# ---------------------------------------------------------------------------
# TLS. Server validation stays ON unless explicitly disabled, which is the
# opposite of the scripts this replaces - they trusted every certificate
# unconditionally, silently removing the protection mTLS is there to provide.
# ---------------------------------------------------------------------------
function Initialize-Tls {
    param([bool]$SkipValidation)

    try {
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls11
    } catch {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    }

    if (-not $SkipValidation) { return }

    Write-Log 'Server certificate validation DISABLED by configuration.' 'WARN'
    [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

# ---------------------------------------------------------------------------
# Wait for the user profile to finish loading. A logon task can fire before the
# certificate store is reachable, which is why login/unlock wait here.
# ---------------------------------------------------------------------------
function Wait-ForUserProfile {
    param([string]$Username, [int]$TimeoutSeconds)

    if ([string]::IsNullOrWhiteSpace($Username)) { return $true }

    # This probe is read-only; without this, -WhatIf leaks the CimCmdlets module
    # auto-load into the output as a wall of "Set Alias" messages.
    $WhatIfPreference = $false

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $profiles = Get-CimInstance -ClassName Win32_UserProfile -Filter 'Loaded=True' -ErrorAction Stop
            foreach ($p in $profiles) {
                # -like, not -match: a username may contain regex metacharacters.
                if ($p.LocalPath -and $p.LocalPath -like "*$Username*" -and (Test-Path $p.LocalPath)) {
                    return $true
                }
            }
        } catch {
            Write-Log "Profile probe failed: $($_.Exception.Message)" 'WARN'
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

# ---------------------------------------------------------------------------
# Certificate selection
# ---------------------------------------------------------------------------
function Find-UserCertificate {
    param([string]$Thumbprint, [string]$SubjectContains)

    $store = $null
    try {
        $store = New-Object System.Security.Cryptography.X509Certificates.X509Store 'My', 'CurrentUser'
        $store.Open('ReadOnly')

        $usable = @($store.Certificates | Where-Object {
            $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) -and $_.NotBefore -le (Get-Date)
        })

        if ($usable.Count -eq 0) {
            Write-Log 'No valid certificate with a private key in CurrentUser\My.' 'WARN'
            return $null
        }

        if ($Thumbprint) {
            $clean = ($Thumbprint -replace '[^0-9A-Fa-f]', '')
            $match = @($usable | Where-Object { $_.Thumbprint -eq $clean })
            if ($match.Count -gt 0) {
                Write-Log "Selected certificate by thumbprint: $($match[0].Subject)"
                return $match[0]
            }
            Write-Log "No certificate matched thumbprint $clean; falling back." 'WARN'
        }

        if ($SubjectContains) {
            $match = @($usable | Where-Object { $_.Subject -like "*$SubjectContains*" })
            if ($match.Count -gt 0) {
                Write-Log "Selected certificate by subject: $($match[0].Subject)"
                return $match[0]
            }
            Write-Log "No certificate subject contained '$SubjectContains'; falling back." 'WARN'
        }

        $latest = $usable | Sort-Object NotBefore -Descending | Select-Object -First 1
        Write-Log "Selected most recent valid certificate: $($latest.Subject)"
        return $latest
    } catch {
        Write-Log "Certificate lookup failed: $($_.Exception.Message)" 'ERROR'
        return $null
    } finally {
        if ($store) { $store.Close() }
    }
}

# ---------------------------------------------------------------------------
# Session file. Written at login so the anonymous logout call can still be
# attributed to the right user via the server-issued sessionId. One file per
# user, named by SID: users may add files to sessions\, so a file named after
# someone else proves nothing until its owner has been checked.
# ---------------------------------------------------------------------------
function ConvertTo-Sid {
    param([string]$Value)

    # Task Scheduler passes "$(TargetUserSid)" through as literal text when the
    # task is started by hand rather than by the event.
    if ($Value -notmatch '^S-1-\d+(-\d+)+$') { return $null }
    try {
        return (New-Object Security.Principal.SecurityIdentifier $Value).Value
    } catch {
        return $null
    }
}

function Get-SessionFilePath {
    param([string]$Sid)
    return (Join-Path $SessionDir "$Sid.json")
}

function Save-SessionFile {
    param([object]$Response, [string]$Sid, [string]$WindowsUserId)

    $path = Get-SessionFilePath -Sid $Sid
    try {
        if (-not (Test-Path $SessionDir)) {
            # Only the installer creates it, with the permissions that make it
            # safe for SYSTEM to read from.
            Write-Log "$SessionDir does not exist; re-run Install-InOutWorker.ps1. The next logoff cannot be attributed." 'WARN'
            return
        }

        $sessionId = $null
        if ($Response -and $Response.PSObject.Properties.Name -contains 'sessionId') {
            $sessionId = $Response.sessionId
        }

        # Only what the logout run needs; not the server's whole record.
        $payload = [ordered]@{
            sessionId     = $sessionId
            windowsUserId = $WindowsUserId
            userSid       = $Sid
            savedAtUtc    = (Get-Date).ToUniversalTime().ToString('o')
        }

        $payload | ConvertTo-Json |
            Out-File -LiteralPath $path -Encoding utf8 -Force

        # Readable by this user, SYSTEM and administrators only: the sessionId
        # is all the anonymous logoff call needs to check this user out.
        try {
            $acl = New-Object System.Security.AccessControl.FileSecurity
            $acl.SetAccessRuleProtection($true, $false)
            foreach ($grant in @(@($Sid, 'Read, Write'), @($SystemSid, 'FullControl'), @($AdminsSid, 'FullControl'))) {
                $identity = New-Object Security.Principal.SecurityIdentifier $grant[0]
                $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
                            $identity, $grant[1], 'Allow')))
            }
            # Writes the DACL alone. Set-Acl would also try the audit section,
            # which a user may not set, once the file's DACL is protected.
            $file = Get-Item -LiteralPath $path -Force
            if ($PSVersionTable.PSEdition -eq 'Core') {
                [System.IO.FileSystemAclExtensions]::SetAccessControl($file, $acl)
            } else {
                $file.SetAccessControl($acl)
            }
        } catch {
            Write-Log "Could not restrict ACL on ${path}: $($_.Exception.Message)" 'WARN'
        }

        # Never log the sessionId itself: it is all it takes to check this
        # user out.
        if ($sessionId) {
            Write-Log "Session cached at $path."
        } else {
            Write-Log "Session cached at $path but the response carried no sessionId." 'WARN'
        }
    } catch {
        # Access denied here usually means another account created this file
        # first; an administrator can delete it.
        Write-Log "Failed to write session file ${path}: $($_.Exception.Message)" 'WARN'
    }
}

function Read-SessionFile {
    param([string]$Sid)

    $path = Get-SessionFilePath -Sid $Sid

    # The SYSTEM run also checks the folder: the installer made it, so anything
    # else means it has been tampered with or the installer was not re-run.
    if ($RunningAsSystem -and -not (Test-TrustedPath -Path $SessionDir -Owners @($SystemSid, $AdminsSid))) {
        Write-Log "$SessionDir is missing, a link, or not owned by SYSTEM or Administrators; not reading it. Re-run Install-InOutWorker.ps1." 'WARN'
        return $null
    }

    if (-not (Test-Path -LiteralPath $path)) {
        Write-Log "No session file at $path; posting logout without a sessionId." 'WARN'
        return $null
    }

    # Read only a file the user themselves (or an administrator) wrote, reached
    # without passing through a junction or link. It is never deleted: the next
    # login overwrites it, and the server refuses a sessionId used before.
    if (-not (Test-TrustedPath -Path $path -Owners @($Sid, $SystemSid, $AdminsSid))) {
        Write-Log "Session file $path is a link or not owned by $Sid; ignoring it." 'WARN'
        return $null
    }

    try {
        if ((Get-Item -LiteralPath $path -Force).Length -gt 64KB) {
            Write-Log "Session file $path is too large to be one this script wrote; ignoring it." 'WARN'
            return $null
        }

        $raw = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }

        $parsed = $raw | ConvertFrom-Json
        $names  = $parsed.PSObject.Properties.Name

        if (-not ($names -contains 'userSid' -and [string]$parsed.userSid -eq $Sid)) {
            Write-Log "Session file $path was not written for $Sid; ignoring it." 'WARN'
            return $null
        }

        $sessionId = $null
        if ($names -contains 'sessionId' -and $parsed.sessionId -is [string]) { $sessionId = $parsed.sessionId }

        $windowsUserId = $null
        if ($names -contains 'windowsUserId' -and $parsed.windowsUserId -is [string]) { $windowsUserId = $parsed.windowsUserId }

        return [pscustomobject]@{
            SessionId     = $sessionId
            WindowsUserId = $windowsUserId
        }
    } catch {
        Write-Log "Session file unreadable: $($_.Exception.Message)" 'WARN'
        return $null
    }
}

function Get-AccountName {
    param([string]$Sid)

    # The name the login run would have sent ($env:USERNAME), for a logoff
    # with no session file; the SID itself if it cannot be resolved.
    try {
        $account = (New-Object Security.Principal.SecurityIdentifier $Sid).Translate([Security.Principal.NTAccount]).Value
        return $account.Split('\')[-1]
    } catch {
        return $Sid
    }
}

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
function Send-StatusPost {
    param(
        [string]$Url,
        [hashtable]$Body,
        [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
        [switch]$WindowsSignIn,
        [int]$TimeoutSeconds
    )

    $json = $Body | ConvertTo-Json -Compress

    # Not $args - that is an automatic variable.
    $requestArgs = @{
        Uri         = $Url
        Method      = 'Post'
        ContentType = 'application/json'
        Body        = $json
        TimeoutSec  = $TimeoutSeconds
    }
    if ($Certificate) { $requestArgs.Certificate = $Certificate }
    if ($WindowsSignIn) {
        # Answers the server's "WWW-Authenticate: Negotiate" with the logged-on
        # user's Kerberos ticket.
        $requestArgs.UseDefaultCredentials = $true
        # A server without Windows sign-in redirects to its login page; following
        # that would look like success.
        $requestArgs.MaximumRedirection = 0
    }

    # Not the body: at logout it carries the sessionId.
    Write-Log "POST $Url"
    try {
        $response = Invoke-RestMethod @requestArgs
    } catch [System.InvalidOperationException] {
        # Windows PowerShell 5.1's way of reporting a redirect it was told not to follow.
        if ($WindowsSignIn) {
            throw 'The server redirected instead of accepting Windows sign-in (is application.windows-auth enabled on the server?).'
        }
        throw
    }

    if ($WindowsSignIn -and -not ($response -and $response.PSObject.Properties.Name -contains 'action')) {
        throw 'The server did not accept Windows sign-in (is application.windows-auth enabled on the server?).'
    }
    return $response
}

function Get-HttpStatus {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)
    $text = ''
    try {
        if ($ErrorRecord.Exception.Response) {
            $text = " (HTTP $([int]$ErrorRecord.Exception.Response.StatusCode))"
        }
    } catch { }
    # The server explains refusals in JSON: {"error":"license_required"|"license_read_only"|"license_limit",
    # "message":"..."}. Log that, since Windows PowerShell's own message only says "(403) Forbidden". The
    # event is not retried: the next logon, lock or unlock simply tries again.
    try {
        if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
            $body = $ErrorRecord.ErrorDetails.Message | ConvertFrom-Json -ErrorAction Stop
            if ($body.error) {
                $text += " [$($body.error)] $($body.message)"
            }
        }
    } catch { }
    return $text
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
try {
    $config = Resolve-Configuration
} catch {
    Write-Log "Configuration error: $($_.Exception.Message)" 'ERROR'
    exit 1
}

$plan = $ActionMap[$Action]
$url  = "$($config.BaseUrl)/$($plan.Endpoint)"

Initialize-Tls -SkipValidation ([bool]$config.SkipCertificateCheck)

$windowsUserId = $env:USERNAME
$certificate   = $null

if ($plan.WaitForProfile) {
    if (-not (Wait-ForUserProfile -Username $windowsUserId -TimeoutSeconds $ProfileWaitTimeoutSec)) {
        Write-Log "Profile for $windowsUserId not ready after ${ProfileWaitTimeoutSec}s; continuing anyway." 'WARN'
    }
}

# How to sign in, in order of preference. Logout is anonymous.
$attempts = @()
if ($plan.Authenticated) {
    if ($config.Authentication -ne 'Certificate') {
        $attempts += 'Windows'
    }
    if ($config.Authentication -ne 'Windows') {
        $certificate = Find-UserCertificate -Thumbprint $config.CertificateThumbprint `
                                            -SubjectContains $config.CertificateSubjectContains
        if ($certificate) {
            $attempts += 'Certificate'
        } elseif ($config.Authentication -eq 'Certificate') {
            Write-Log "'$Action' posts to an authenticated endpoint and no client certificate is available." 'ERROR'
            exit 2
        } else {
            Write-Log 'No client certificate to fall back on if Windows sign-in fails.' 'WARN'
        }
    }
} else {
    $attempts += 'Anonymous'
}

# Build the body. CheckInOut on the server binds exactly two fields.
$body = @{ windowsUserId = $windowsUserId }

if ($Action -eq 'logout') {
    # Whose session ended: run as SYSTEM, the logoff event says (-UserSid); run
    # as the user (a remote disconnect), it is their own.
    $sessionSid = ConvertTo-Sid $UserSid
    if (-not $sessionSid -and -not $RunningAsSystem) { $sessionSid = $CurrentSid }
    if (-not $sessionSid) {
        Write-Log "Logout needs -UserSid when run as SYSTEM (the Logout task passes it from event 4647); got '$UserSid'." 'ERROR'
        exit 4
    }

    $session = Read-SessionFile -Sid $sessionSid
    if ($session) {
        if ($session.SessionId) { $body.sessionId = $session.SessionId }
        # At logoff the task runs as SYSTEM, where $env:USERNAME is not the
        # person who logged in - prefer the name captured at login.
        if ($session.WindowsUserId) { $body.windowsUserId = $session.WindowsUserId }
    }
    if ($RunningAsSystem -and -not ($session -and $session.WindowsUserId)) {
        $body.windowsUserId = Get-AccountName -Sid $sessionSid
    }
    if (-not $body.ContainsKey('sessionId')) {
        Write-Log 'Logout has no sessionId; the server cannot resolve the DN from it.' 'WARN'
    }
}

if (-not $PSCmdlet.ShouldProcess($url, "POST $Action")) {
    Write-Log "WhatIf: would POST to $url as user '$($body.windowsUserId)' signing in with: $($attempts -join ', then ')."
    exit 0
}

$response = $null
$accepted = $false
foreach ($attempt in $attempts) {
    try {
        switch ($attempt) {
            'Windows'     { $response = Send-StatusPost -Url $url -Body $body -WindowsSignIn -TimeoutSeconds $TimeoutSec }
            'Certificate' { $response = Send-StatusPost -Url $url -Body $body -Certificate $certificate -TimeoutSeconds $TimeoutSec }
            default       { $response = Send-StatusPost -Url $url -Body $body -TimeoutSeconds $TimeoutSec }
        }
        Write-Log "Server accepted '$Action' ($attempt sign-in)."
        $accepted = $true
        break
    } catch {
        $status = Get-HttpStatus -ErrorRecord $_
        $level = if ($attempt -eq $attempts[-1]) { 'ERROR' } else { 'WARN' }
        Write-Log "POST to $url with $attempt sign-in failed${status}: $($_.Exception.Message)" $level
    }
}
if (-not $accepted) {
    exit 3
}

if ($Action -eq 'login') {
    Save-SessionFile -Response $response -Sid $CurrentSid -WindowsUserId $windowsUserId
}

Write-Log "Done."
exit 0
