<#
.SYNOPSIS
  Installs (or removes) the In-N-Out-Work status client and its scheduled tasks.

.DESCRIPTION
  Must be run elevated. Performs three steps:

    1. Copies Update-WorkStatus.ps1 and the config file to $InstallDir, and
       makes that folder writable by SYSTEM and Administrators only. It lives
       under Program Files on purpose: the Logout task runs it as SYSTEM, and a
       script (or config) that runs as SYSTEM must not sit somewhere a non-admin
       can rewrite it.

    2. Prepares %ProgramData%\InOutWorker, where the SYSTEM task writes logs
       and reads each user's session file, so that SYSTEM never writes to,
       deletes from or follows a link in a folder an ordinary user can modify:

         InOutWorker\           SYSTEM, Administrators; Users may only list it
         InOutWorker\logs\      SYSTEM, Administrators
         InOutWorker\sessions\  SYSTEM, Administrators; Users may only add a
                                file (their own <SID>.json) and read and
                                rewrite that file, not list, change or delete
                                anyone else's

       Inheritance from %ProgramData% is cut on all three. Earlier versions
       granted Users Modify on the whole folder; on an upgrade that grant is
       removed, links and the old shared session.json are deleted, and old
       logs\ or sessions\ folders are renamed aside (*.old-<timestamp>)
       rather than reused.

    3. Registers the tasks from .\tasks\*.xml under \InOutWork\.

.PARAMETER Uninstall
  Unregister the tasks, remove the install directory and the session files.
  The logs are left in place.

.EXAMPLE
  # From an elevated PowerShell prompt, in the powershell\ folder of the repo:
  .\Install-InOutWorker.ps1 -BaseUrl "https://timeserver.example.com:8444/api/check"

.EXAMPLE
  # Sign in with the Windows logon, falling back to the certificate.
  .\Install-InOutWorker.ps1 -BaseUrl "https://timeserver.example.com:8444/api/check" -Authentication Auto

.EXAMPLE
  .\Install-InOutWorker.ps1 -Uninstall
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$InstallDir = "$env:ProgramFiles\InOutWorker",
    [string]$BaseUrl,
    [ValidateSet('Certificate', 'Windows', 'Auto')]
    [string]$Authentication,
    [string]$CertificateThumbprint,
    [switch]$SkipCertificateCheck,
    [switch]$Uninstall
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$TaskPath  = '\InOutWork\'
$TaskNames = @('InOutWork - Login', 'InOutWork - Logout', 'InOutWork - Disconnect',
               'InOutWork - Lock', 'InOutWork - Unlock')
$DataDir    = Join-Path $env:ProgramData 'InOutWorker'
$LogDir     = Join-Path $DataDir 'logs'
$SessionDir = Join-Path $DataDir 'sessions'

$SystemSid = New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'
$AdminsSid = New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'
$UsersSid  = New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-545'
$OwnerSid  = New-Object System.Security.Principal.SecurityIdentifier 'S-1-3-0'

# ACE inheritance: the folder, its subfolders and files - or the folder alone.
$Inherited  = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
$FolderOnly = [System.Security.AccessControl.InheritanceFlags]::None
$FilesOnly  = [System.Security.AccessControl.InheritanceFlags]::ObjectInherit

# Only SYSTEM and Administrators are ever granted anything that is inherited,
# so whatever these folders hold, an ordinary user gets no access from them.
$AdminGrants = @(
    @($SystemSid, 'FullControl', $Inherited),
    @($AdminsSid, 'FullControl', $Inherited)
)
$InstallGrants = $AdminGrants + @(,@($UsersSid, 'ReadAndExecute', $Inherited))
$DataGrants    = $AdminGrants + @(,@($UsersSid, 'ReadAndExecute', $FolderOnly))
# In sessions\ a user may create a file and then read and rewrite that one file (CREATOR OWNER),
# nothing more. Update-WorkStatus.ps1 also gives each file an explicit DACL of its own.
$SessionGrants = $AdminGrants + @(
    @($UsersSid, 'CreateFiles, Traverse, ReadAttributes, ReadPermissions, Synchronize', $FolderOnly),
    @($OwnerSid, 'Read, Write', $FilesOnly, [System.Security.AccessControl.PropagationFlags]::InheritOnly)
)

function Get-ItemAttributes {
    # The item's own attributes - a link's, not its target's - or $null when
    # nothing is there.
    param([string]$Path)
    try {
        return [System.IO.File]::GetAttributes($Path)
    } catch {
        return $null
    }
}

function Test-ReparsePoint {
    param($Attributes)
    return ($null -ne $Attributes -and ($Attributes -band [System.IO.FileAttributes]::ReparsePoint))
}

function Remove-Link {
    # Deletes a junction or symbolic link itself, never what it points to.
    param([string]$Path)
    if ((Get-ItemAttributes $Path) -band [System.IO.FileAttributes]::Directory) {
        [System.IO.Directory]::Delete($Path)
    } else {
        [System.IO.File]::Delete($Path)
    }
    Write-Host "Removed link $Path"
}

function Test-InstallerFolder {
    # A real folder, owned by Administrators or SYSTEM, that does not inherit
    # from its parent: what this installer leaves behind, and nothing an
    # ordinary user can make.
    param([string]$Path)
    $attributes = Get-ItemAttributes $Path
    if ($null -eq $attributes -or (Test-ReparsePoint $attributes) -or
        -not ($attributes -band [System.IO.FileAttributes]::Directory)) {
        return $false
    }
    $acl   = Get-Acl -LiteralPath $Path
    $owner = $acl.GetOwner([System.Security.Principal.SecurityIdentifier])
    return ($acl.AreAccessRulesProtected -and ($owner -eq $AdminsSid -or $owner -eq $SystemSid))
}

function Set-FolderAcl {
    # Replaces the folder's owner and DACL outright: Administrators own it,
    # nothing is inherited from the parent, and only $Grants apply. Each grant
    # is @(SID, rights, inheritance[, propagation]).
    param([string]$Path, [object[]]$Grants)

    # A folder an ordinary user created is theirs, and its owner can always
    # rewrite its DACL; take it over first.
    & takeown.exe /F $Path /A | Out-Null

    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetOwner($AdminsSid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($grant in $Grants) {
        $propagation = [System.Security.AccessControl.PropagationFlags]::None
        if ($grant.Count -gt 3) { $propagation = $grant[3] }
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
                    $grant[0], [System.Security.AccessControl.FileSystemRights]$grant[1], $grant[2],
                    $propagation, 'Allow')))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Initialize-ProtectedFolder {
    param([string]$Path, [object[]]$Grants)

    $attributes = Get-ItemAttributes $Path
    if (Test-ReparsePoint $attributes) {
        Remove-Link $Path
        $attributes = $null
    } elseif ($null -ne $attributes -and -not (Test-InstallerFolder $Path)) {
        # Left from a version that let any user write here, so it may hold
        # anything. Rename it aside unopened rather than walk into it.
        $aside = '{0}.old-{1}' -f $Path, (Get-Date -Format 'yyyyMMddHHmmss')
        if ($attributes -band [System.IO.FileAttributes]::Directory) {
            [System.IO.Directory]::Move($Path, $aside)
        } else {
            [System.IO.File]::Move($Path, $aside)
        }
        Write-Host "Moved the old $Path aside to $aside"
        $attributes = $null
    }
    if ($null -eq $attributes) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
    Set-FolderAcl -Path $Path -Grants $Grants
}

function Test-Elevated {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal $id).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Elevated)) {
    Write-Error 'This script must be run from an elevated PowerShell session.'
    exit 1
}

# ---------------------------------------------------------------------------
# Uninstall
# ---------------------------------------------------------------------------
if ($Uninstall) {
    foreach ($name in $TaskNames) {
        $existing = Get-ScheduledTask -TaskName $name -TaskPath $TaskPath -ErrorAction SilentlyContinue
        if ($existing) {
            if ($PSCmdlet.ShouldProcess("$TaskPath$name", 'Unregister scheduled task')) {
                Unregister-ScheduledTask -TaskName $name -TaskPath $TaskPath -Confirm:$false
                Write-Host "Removed task $TaskPath$name"
            }
        }
    }
    if (Test-Path $InstallDir) {
        if ($PSCmdlet.ShouldProcess($InstallDir, 'Remove install directory')) {
            Remove-Item $InstallDir -Recurse -Force
            Write-Host "Removed $InstallDir"
        }
    }

    # Each user's last sessionId. Users can only have added files here, so
    # delete entry by entry - a link as a link - and never recurse.
    $attributes = Get-ItemAttributes $SessionDir
    if ($null -ne $attributes -and $PSCmdlet.ShouldProcess($SessionDir, 'Remove session files')) {
        if (Test-ReparsePoint $attributes) {
            Remove-Link $SessionDir
        } else {
            foreach ($item in @(Get-ChildItem -LiteralPath $SessionDir -Force)) {
                try {
                    if ($item.Attributes -band [System.IO.FileAttributes]::Directory) {
                        [System.IO.Directory]::Delete($item.FullName)
                    } else {
                        [System.IO.File]::Delete($item.FullName)
                    }
                } catch {
                    Write-Warning "Could not remove $($item.FullName): $($_.Exception.Message)"
                }
            }
            [System.IO.Directory]::Delete($SessionDir)
            Write-Host "Removed $SessionDir"
        }
    }
    Write-Host "Logs left in place: $LogDir, and %LOCALAPPDATA%\InOutWorker\logs in each user's profile"
    exit 0
}

# ---------------------------------------------------------------------------
# 1. Install files
# ---------------------------------------------------------------------------
$source = Join-Path $PSScriptRoot 'Update-WorkStatus.ps1'
if (-not (Test-Path $source)) {
    Write-Error "Cannot find Update-WorkStatus.ps1 next to this installer ($PSScriptRoot)."
    exit 1
}

if (Test-ReparsePoint (Get-ItemAttributes $InstallDir)) {
    Write-Error "$InstallDir is a junction or symbolic link; choose a real folder."
    exit 1
}

if (-not (Test-Path $InstallDir)) {
    if ($PSCmdlet.ShouldProcess($InstallDir, 'Create install directory')) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
}

# Program Files is admin-only already; this also covers an -InstallDir that is
# not, such as a new folder under C:\.
if ($PSCmdlet.ShouldProcess($InstallDir, 'Restrict to SYSTEM and Administrators, Users read')) {
    Set-FolderAcl -Path $InstallDir -Grants $InstallGrants
}

if ($PSCmdlet.ShouldProcess($InstallDir, 'Copy Update-WorkStatus.ps1')) {
    # Replace rather than overwrite, so the file is created - and owned - by an
    # administrator even if an earlier one was not.
    $target = Join-Path $InstallDir 'Update-WorkStatus.ps1'
    if (Test-Path $target) { Remove-Item $target -Force }
    Copy-Item $source -Destination $InstallDir -Force
    Write-Host "Installed script to $InstallDir"
}

# Write the config only when something was actually specified, so re-running the
# installer without arguments does not blank an existing configuration.
if ($BaseUrl -or $Authentication -or $CertificateThumbprint -or $PSBoundParameters.ContainsKey('SkipCertificateCheck')) {
    $config = [ordered]@{}
    if ($BaseUrl)               { $config.BaseUrl               = $BaseUrl }
    if ($Authentication)        { $config.Authentication        = $Authentication }
    if ($CertificateThumbprint) { $config.CertificateThumbprint = $CertificateThumbprint }
    $config.SkipCertificateCheck = [bool]$SkipCertificateCheck

    $configPath = Join-Path $InstallDir 'inoutworker.config.json'
    if ($PSCmdlet.ShouldProcess($configPath, 'Write configuration')) {
        if (Test-Path $configPath) { Remove-Item $configPath -Force }
        $config | ConvertTo-Json -Depth 5 | Out-File $configPath -Encoding utf8 -Force
        Write-Host "Wrote configuration to $configPath"
    }
}

$configPath = Join-Path $InstallDir 'inoutworker.config.json'
if (Test-Path $configPath) {
    $owner = (Get-Acl -LiteralPath $configPath).GetOwner([System.Security.Principal.SecurityIdentifier])
    if ($owner -ne $AdminsSid -and $owner -ne $SystemSid) {
        Write-Warning "$configPath is owned by $owner, not an administrator; review it, or re-run with -BaseUrl to rewrite it."
    }
}

# ---------------------------------------------------------------------------
# 2. Data folders. The SYSTEM task writes logs\ and reads sessions\, so no
#    ordinary user may write anywhere here except to add their own session
#    file. See the description at the top for the layout.
# ---------------------------------------------------------------------------
if ($PSCmdlet.ShouldProcess($DataDir, 'Restrict to SYSTEM and Administrators; set up logs and sessions')) {
    $attributes = Get-ItemAttributes $DataDir
    if (Test-ReparsePoint $attributes) {
        Remove-Link $DataDir
        $attributes = $null
    }
    if ($null -eq $attributes) {
        New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
    } else {
        # Upgrade from a version that granted Users Modify here. Links first,
        # so the new DACL is not applied through one.
        foreach ($item in @(Get-ChildItem -LiteralPath $DataDir -Force)) {
            if (Test-ReparsePoint $item.Attributes) { Remove-Link $item.FullName }
        }
    }

    # Removes the Users Modify grant along with everything else inherited or
    # added before.
    Set-FolderAcl -Path $DataDir -Grants $DataGrants
    Write-Host "Restricted $DataDir to SYSTEM and Administrators"

    # The shared session file of earlier versions, readable by every user. Its
    # sessionId is stale: each user's next login writes sessions\<SID>.json.
    $oldSession = Join-Path $DataDir 'session.json'
    if ($null -ne (Get-ItemAttributes $oldSession)) {
        [System.IO.File]::Delete($oldSession)
        Write-Host "Removed the old shared $oldSession"
    }

    $oldConfig = Join-Path $DataDir 'inoutworker.config.json'
    if ($null -ne (Get-ItemAttributes $oldConfig)) {
        Write-Warning "$oldConfig is no longer read: any user could change it. Review it and pass its settings to this installer, which writes them to $InstallDir."
    }

    Initialize-ProtectedFolder -Path $LogDir -Grants $AdminGrants
    Initialize-ProtectedFolder -Path $SessionDir -Grants $SessionGrants
    Write-Host "Prepared $LogDir and $SessionDir"
}

# ---------------------------------------------------------------------------
# 3. Register the tasks
# ---------------------------------------------------------------------------
$taskDir = Join-Path $PSScriptRoot 'tasks'
if (-not (Test-Path $taskDir)) {
    Write-Error "Cannot find the tasks folder at $taskDir."
    exit 1
}

foreach ($file in Get-ChildItem $taskDir -Filter '*.xml' | Sort-Object Name) {
    # The XMLs are UTF-16; read them as such so the declaration matches the bytes.
    $xml = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::Unicode)

    # Task name comes from the URI in the XML so the two cannot drift apart.
    $doc = New-Object System.Xml.XmlDocument
    $doc.LoadXml($xml)
    $ns = New-Object System.Xml.XmlNamespaceManager($doc.NameTable)
    $ns.AddNamespace('t', 'http://schemas.microsoft.com/windows/2004/02/mit/task')
    $uriNode = $doc.SelectSingleNode('//t:URI', $ns)
    if (-not $uriNode) {
        Write-Warning "$($file.Name) has no <URI>; skipping."
        continue
    }
    $taskName = Split-Path $uriNode.InnerText -Leaf

    # Point the action at wherever we actually installed the script.
    $xml = $xml.Replace('C:\Program Files\InOutWorker\Update-WorkStatus.ps1',
                        (Join-Path $InstallDir 'Update-WorkStatus.ps1'))

    if ($PSCmdlet.ShouldProcess("$TaskPath$taskName", 'Register scheduled task')) {
        $existing = Get-ScheduledTask -TaskName $taskName -TaskPath $TaskPath -ErrorAction SilentlyContinue
        if ($existing) {
            Unregister-ScheduledTask -TaskName $taskName -TaskPath $TaskPath -Confirm:$false
        }
        Register-ScheduledTask -Xml $xml -TaskName $taskName -TaskPath $TaskPath -Force | Out-Null
        Write-Host "Registered $TaskPath$taskName"
    }
}

Write-Host ''
Write-Host 'Done. Verify with:'
Write-Host '  Get-ScheduledTask -TaskPath "\InOutWork\"'
Write-Host 'Test a single action without waiting for a real event:'
Write-Host ("  & '{0}' -Action login -WhatIf" -f (Join-Path $InstallDir 'Update-WorkStatus.ps1'))
