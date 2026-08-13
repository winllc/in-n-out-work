<#
.SYNOPSIS
  Installs (or removes) the In-N-Out-Work status client and its scheduled tasks.

.DESCRIPTION
  Must be run elevated. Performs three steps:

    1. Copies Update-WorkStatus.ps1 and the config file to $InstallDir.
       This lives under Program Files on purpose: the tasks run with
       HighestAvailable / as SYSTEM, and a script that runs elevated must not
       sit somewhere a non-admin can rewrite it.

    2. Creates the data directory and grants Users Modify on it, so that a
       second user logging on to the same machine can still overwrite
       session.json. Without this the folder inherits CREATOR OWNER and the
       first user to log on effectively owns the session file.

    3. Registers the four tasks from .\tasks\*.xml under \InOutWork\.

.PARAMETER Uninstall
  Unregister the tasks and remove the install directory. The data directory and
  its logs are left in place.

.EXAMPLE
  # From an elevated PowerShell prompt, in the powershell\ folder of the repo:
  .\Install-InOutWorker.ps1 -BaseUrl "https://timeserver.example.com:8444/api/check"

.EXAMPLE
  .\Install-InOutWorker.ps1 -Uninstall
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$InstallDir = "$env:ProgramFiles\InOutWorker",
    [string]$BaseUrl,
    [string]$CertificateThumbprint,
    [switch]$SkipCertificateCheck,
    [switch]$Uninstall
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$TaskPath  = '\InOutWork\'
$TaskNames = @('InOutWork - Login', 'InOutWork - Logout', 'InOutWork - Lock', 'InOutWork - Unlock')
$DataDir   = Join-Path $env:ProgramData 'InOutWorker'

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
    Write-Host "Data directory left in place: $DataDir"
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

if (-not (Test-Path $InstallDir)) {
    if ($PSCmdlet.ShouldProcess($InstallDir, 'Create install directory')) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
}

if ($PSCmdlet.ShouldProcess($InstallDir, 'Copy Update-WorkStatus.ps1')) {
    Copy-Item $source -Destination $InstallDir -Force
    Write-Host "Installed script to $InstallDir"
}

# Write the config only when something was actually specified, so re-running the
# installer without arguments does not blank an existing configuration.
if ($BaseUrl -or $CertificateThumbprint -or $PSBoundParameters.ContainsKey('SkipCertificateCheck')) {
    $config = [ordered]@{}
    if ($BaseUrl)               { $config.BaseUrl               = $BaseUrl }
    if ($CertificateThumbprint) { $config.CertificateThumbprint = $CertificateThumbprint }
    $config.SkipCertificateCheck = [bool]$SkipCertificateCheck

    $configPath = Join-Path $InstallDir 'inoutworker.config.json'
    if ($PSCmdlet.ShouldProcess($configPath, 'Write configuration')) {
        $config | ConvertTo-Json -Depth 5 | Out-File $configPath -Encoding utf8 -Force
        Write-Host "Wrote configuration to $configPath"
    }
}

# ---------------------------------------------------------------------------
# 2. Data directory, readable and writable by every interactive user
# ---------------------------------------------------------------------------
if (-not (Test-Path $DataDir)) {
    if ($PSCmdlet.ShouldProcess($DataDir, 'Create data directory')) {
        New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
    }
}

if ($PSCmdlet.ShouldProcess($DataDir, 'Grant Users Modify')) {
    try {
        $acl  = Get-Acl $DataDir
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                    'BUILTIN\Users',
                    'Modify',
                    'ContainerInherit,ObjectInherit',
                    'None',
                    'Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -Path $DataDir -AclObject $acl
        Write-Host "Granted Users Modify on $DataDir"
    } catch {
        Write-Warning "Could not set ACL on ${DataDir}: $($_.Exception.Message)"
    }
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
