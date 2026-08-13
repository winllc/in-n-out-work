<#
.SYNOPSIS
  Reports a workstation session event (login / logout / lock / unlock) to the
  In-N-Out-Work server.

.DESCRIPTION
  Replaces login.ps1, logout.ps1 and updatestatus.ps1 with a single entry point
  invoked from Task Scheduler with an -Action argument.

  Transport model (mirrors SecurityConfig on the server):

    Action   Endpoint             Auth            Runs as
    ------   ------------------   -------------   ---------------------------
    login    POST /api/check/in       mTLS cert   the user (interactive)
    lock     POST /api/check/lock     mTLS cert   the user (session change)
    unlock   POST /api/check/unlock   mTLS cert   the user (session change)
    logout   POST /api/check/out      anonymous   SYSTEM / S4U, profile gone

  Only /api/check/out is permitAll on the server; every other action is
  .anyRequest().authenticated() and therefore MUST present the client
  certificate or it will be rejected with 401.

  Because the logoff task runs after the user profile has unloaded, the user's
  certificate store is not reachable at that point. To keep the record tied to
  the right identity, the login response - which contains a server-generated
  sessionId - is cached in a session file that the logout run reads back and
  echoes to the server, which resolves the DN via lookupBySessionId().

.PARAMETER Action
  Which session event to report: login, logout, lock or unlock.

.PARAMETER BaseUrl
  Base API URL, e.g. https://host:8444/api/check. Overrides the config file.

.PARAMETER CertificateThumbprint
  Thumbprint of the client certificate to use. Overrides the config file.

.PARAMETER CertificateSubjectContains
  Substring matched against certificate subjects when no thumbprint matches.

.PARAMETER SkipCertificateCheck
  Disable server TLS validation. Intended for lab use with self-signed server
  certificates only - it is off by default, unlike the scripts this replaces.

.EXAMPLE
  powershell.exe -ExecutionPolicy Bypass -File "C:\InOutWorker\Update-WorkStatus.ps1" -Action login

.EXAMPLE
  # Verify configuration and certificate selection without contacting the server.
  .\Update-WorkStatus.ps1 -Action lock -WhatIf

.NOTES
  Exit codes: 0 success, 1 configuration error, 2 no usable certificate,
              3 HTTP request failed, 4 session file unusable.
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('login', 'logout', 'lock', 'unlock')]
    [string]$Action,

    [string]$BaseUrl,
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
    CertificateThumbprint      = ''
    CertificateSubjectContains = ''
    SkipCertificateCheck       = $false
    Subfolder                  = 'InOutWorker'
    SessionFile                = 'session.json'
}

# login/lock/unlock are authenticated; only logout is anonymous.
$ActionMap = @{
    login  = @{ Endpoint = 'in';     RequiresCertificate = $true;  WaitForProfile = $true  }
    lock   = @{ Endpoint = 'lock';   RequiresCertificate = $true;  WaitForProfile = $false }
    unlock = @{ Endpoint = 'unlock'; RequiresCertificate = $true;  WaitForProfile = $true  }
    logout = @{ Endpoint = 'out';    RequiresCertificate = $false; WaitForProfile = $false }
}

$BaseDir  = Join-Path $env:ProgramData $Defaults.Subfolder
$LogDir   = Join-Path $BaseDir 'logs'
$LogFile  = Join-Path $LogDir ("inoutworker-{0}.log" -f (Get-Date -Format 'yyyyMMdd'))

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

    try {
        # -WhatIf:$false so a dry run still records what it decided; the log is
        # diagnostics, not one of the effects the caller is previewing.
        if (-not (Test-Path $LogDir)) {
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

    $candidates = @()
    if ($ConfigPath) { $candidates += $ConfigPath }
    $candidates += (Join-Path $PSScriptRoot 'inoutworker.config.json')
    $candidates += (Join-Path $BaseDir 'inoutworker.config.json')

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
# attributed to the right user via the server-issued sessionId.
# ---------------------------------------------------------------------------
function Get-SessionFilePath {
    return (Join-Path $BaseDir $Defaults.SessionFile)
}

function Save-SessionFile {
    param([object]$Response, [string]$WindowsUserId)

    $path = Get-SessionFilePath
    try {
        if (-not (Test-Path $BaseDir)) {
            New-Item -ItemType Directory -Path $BaseDir -Force | Out-Null
        }

        $sessionId = $null
        if ($Response -and $Response.PSObject.Properties.Name -contains 'sessionId') {
            $sessionId = $Response.sessionId
        }

        $payload = [ordered]@{
            sessionId     = $sessionId
            windowsUserId = $WindowsUserId
            savedAtUtc    = (Get-Date).ToUniversalTime().ToString('o')
            record        = $Response
        }

        $payload | ConvertTo-Json -Depth 10 |
            Out-File -FilePath $path -Encoding utf8 -Force

        # The logoff task may run under a different principal, so make sure it
        # is readable rather than relying on inherited ACLs.
        try {
            $acl  = Get-Acl $path
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                        'Authenticated Users', 'Read', 'Allow')
            $acl.AddAccessRule($rule)
            Set-Acl -Path $path -AclObject $acl
        } catch {
            Write-Log "Could not widen ACL on ${path}: $($_.Exception.Message)" 'WARN'
        }

        if ($sessionId) {
            Write-Log "Session cached at $path (sessionId $sessionId)."
        } else {
            Write-Log "Session cached at $path but the response carried no sessionId." 'WARN'
        }
    } catch {
        Write-Log "Failed to write session file ${path}: $($_.Exception.Message)" 'WARN'
    }
}

function Read-SessionFile {
    $path = Get-SessionFilePath

    if (-not (Test-Path $path)) {
        Write-Log "No session file at $path; posting logout without a sessionId." 'WARN'
        return $null
    }

    try {
        $raw = Get-Content -Path $path -Raw -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }

        $parsed = $raw | ConvertFrom-Json
        $names  = $parsed.PSObject.Properties.Name

        # Current format writes sessionId at the top level; older session.json
        # files were the raw server record, which also carries sessionId.
        $sessionId = $null
        if ($names -contains 'sessionId') { $sessionId = $parsed.sessionId }

        $windowsUserId = $null
        if ($names -contains 'windowsUserId') { $windowsUserId = $parsed.windowsUserId }

        if (-not $sessionId -and $names -contains 'record' -and $parsed.record) {
            $recordNames = $parsed.record.PSObject.Properties.Name
            if ($recordNames -contains 'sessionId') { $sessionId = $parsed.record.sessionId }
        }

        return [pscustomobject]@{
            SessionId     = $sessionId
            WindowsUserId = $windowsUserId
        }
    } catch {
        Write-Log "Session file unreadable: $($_.Exception.Message)" 'WARN'
        return $null
    }
}

function Remove-SessionFile {
    $path = Get-SessionFilePath
    try {
        if (Test-Path $path) {
            Remove-Item -Path $path -Force -ErrorAction Stop
            Write-Log 'Session file cleared.'
        }
    } catch {
        Write-Log "Could not remove session file: $($_.Exception.Message)" 'WARN'
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

    Write-Log "POST $Url body=$json"
    return Invoke-RestMethod @requestArgs
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

if ($plan.RequiresCertificate) {
    $certificate = Find-UserCertificate -Thumbprint $config.CertificateThumbprint `
                                        -SubjectContains $config.CertificateSubjectContains
    if (-not $certificate) {
        Write-Log "'$Action' posts to an authenticated endpoint and no client certificate is available." 'ERROR'
        exit 2
    }
}

# Build the body. CheckInOut on the server binds exactly two fields.
$body = @{ windowsUserId = $windowsUserId }

if ($Action -eq 'logout') {
    $session = Read-SessionFile
    if ($session) {
        if ($session.SessionId) { $body.sessionId = $session.SessionId }
        # At logoff the task may run as SYSTEM, where $env:USERNAME is not the
        # person who logged in - prefer the name captured at login.
        if ($session.WindowsUserId) { $body.windowsUserId = $session.WindowsUserId }
    }
    if (-not $body.ContainsKey('sessionId')) {
        Write-Log 'Logout has no sessionId; the server cannot resolve the DN from it.' 'WARN'
    }
}

if (-not $PSCmdlet.ShouldProcess($url, "POST $Action")) {
    Write-Log "WhatIf: would POST to $url as user '$($body.windowsUserId)'."
    exit 0
}

try {
    $response = Send-StatusPost -Url $url -Body $body -Certificate $certificate -TimeoutSeconds $TimeoutSec
    Write-Log "Server accepted '$Action'."
} catch {
    $status = ''
    try {
        if ($_.Exception.Response) {
            $status = " (HTTP $([int]$_.Exception.Response.StatusCode))"
        }
    } catch { }
    Write-Log "POST to $url failed${status}: $($_.Exception.Message)" 'ERROR'
    exit 3
}

if ($Action -eq 'login') {
    Save-SessionFile -Response $response -WindowsUserId $windowsUserId
} elseif ($Action -eq 'logout') {
    Remove-SessionFile
}

Write-Log "Done."
exit 0
