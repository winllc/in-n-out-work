<#
.SYNOPSIS
  Runs PSScriptAnalyzer and the Pester tests for the Windows client, as CI does.

.DESCRIPTION
  Fetches the exact Pester and PSScriptAnalyzer versions below from the
  PowerShell Gallery, checks each package against its SHA-256, and unpacks it
  into -ModulePath (skipped when it is already there). Then:

    1. PSScriptAnalyzer over powershell\, with powershell\PSScriptAnalyzerSettings.psd1.
       Any finding fails the run.
    2. Pester over powershell\tests. Any failure fails the run. On Windows,
       so does any skipped test: the Windows-only tests (real ACLs, SIDs,
       junctions, and the script run as the tasks run it) must run there.

  Works in Windows PowerShell 5.1 and PowerShell 7, on Windows and Linux.

.PARAMETER ModulePath
  Where to keep the downloaded modules. Defaults to a folder in the temp directory.

.PARAMETER TestResultPath
  Also write the Pester results here, as NUnit XML.

.EXAMPLE
  pwsh ./powershell/tests/Invoke-ClientChecks.ps1
#>
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseCompatibleCommands', '',
    Justification = 'The profiles list the Pester 3.4 that ships with Windows; this imports the pinned Pester 5.')]
[CmdletBinding()]
param(
    [string]$ModulePath = (Join-Path ([System.IO.Path]::GetTempPath()) 'readyroom-psmodules'),
    [string]$TestResultPath
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$Packages = @(
    @{ Name = 'Pester';           Version = '5.7.1';  Sha256 = '4A27904C6814A5FBE4758F8E49861F6A1994AEE77B71165A5C43C0371BA6C580' }
    @{ Name = 'PSScriptAnalyzer'; Version = '1.23.0'; Sha256 = 'B9969345F78251112FB75BDDDC789BE8689A343472A2E09762303AA03287AFAB' }
)

$ClientDir = Split-Path $PSScriptRoot -Parent
$OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows

foreach ($package in $Packages) {
    $destination = Join-Path (Join-Path $ModulePath $package.Name) $package.Version
    if (Test-Path (Join-Path $destination "$($package.Name).psd1")) { continue }

    # .zip, because Expand-Archive in Windows PowerShell 5.1 takes nothing else.
    $archive = Join-Path ([System.IO.Path]::GetTempPath()) ('{0}.{1}.{2}.zip' -f $package.Name, $package.Version, [guid]::NewGuid())
    try {
        Write-Output "Fetching $($package.Name) $($package.Version)"
        Invoke-WebRequest -UseBasicParsing -OutFile $archive `
            -Uri "https://www.powershellgallery.com/api/v2/package/$($package.Name)/$($package.Version)"
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
        if ($hash -ne $package.Sha256) {
            throw "$($package.Name) $($package.Version): SHA-256 $hash does not match the pinned $($package.Sha256)."
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
        # The NuGet packaging around the module.
        foreach ($item in @('_rels', 'package', '[Content_Types].xml', "$($package.Name).nuspec")) {
            $path = Join-Path $destination $item
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
        }
    } finally {
        if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
    }
}

$env:PSModulePath = $ModulePath + [System.IO.Path]::PathSeparator + $env:PSModulePath
Import-Module -Name Pester -RequiredVersion '5.7.1' -Force
Import-Module -Name PSScriptAnalyzer -RequiredVersion '1.23.0' -Force

$failed = $false

Write-Output "PSScriptAnalyzer $((Get-Module PSScriptAnalyzer).Version) on $ClientDir"
$findings = @(Invoke-ScriptAnalyzer -Path $ClientDir -Recurse `
                                    -Settings (Join-Path $ClientDir 'PSScriptAnalyzerSettings.psd1'))
if ($findings.Count -gt 0) {
    $findings | Format-Table -AutoSize -Wrap RuleName, Severity, ScriptName, Line, Message | Out-String -Width 200 | Write-Output
    Write-Output "PSScriptAnalyzer: $($findings.Count) finding(s)."
    $failed = $true
} else {
    Write-Output 'PSScriptAnalyzer: no findings.'
}

Write-Output "Pester $((Get-Module Pester).Version) on $($PSVersionTable.PSEdition) $($PSVersionTable.PSVersion)"
$configuration = New-PesterConfiguration
$configuration.Run.Path        = $PSScriptRoot
$configuration.Run.PassThru    = $true
$configuration.Output.Verbosity = 'Detailed'
if ($TestResultPath) {
    $configuration.TestResult.Enabled    = $true
    $configuration.TestResult.OutputPath = $TestResultPath
}
$result = Invoke-Pester -Configuration $configuration

if ($result.FailedCount -gt 0 -or $result.FailedBlocksCount -gt 0 -or $result.FailedContainersCount -gt 0) {
    $failed = $true
}
if ($OnWindows -and $result.SkippedCount -gt 0) {
    Write-Output "$($result.SkippedCount) test(s) skipped on Windows, where every test must run."
    $failed = $true
}

if ($failed) { exit 1 }
exit 0
