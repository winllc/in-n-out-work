# PSScriptAnalyzer settings for the Windows client. CI runs:
#
#   Invoke-ScriptAnalyzer -Path powershell -Recurse -Settings powershell/PSScriptAnalyzerSettings.psd1
#
# and fails on any finding. A finding that is deliberate is suppressed where it
# occurs, with [Diagnostics.CodeAnalysis.SuppressMessageAttribute(..., Justification = '...')],
# never by switching the rule off here.
@{
    Severity            = @('Error', 'Warning', 'Information')
    IncludeDefaultRules = $true

    Rules = @{
        # The scheduled tasks run powershell.exe, which is Windows PowerShell 5.1;
        # an administrator may run the scripts with PowerShell 7. Both must parse
        # and have every command and .NET type used.
        PSUseCompatibleSyntax   = @{
            Enable         = $true
            TargetVersions = @('5.1', '7.0')
        }
        PSUseCompatibleCommands = @{
            Enable         = $true
            TargetProfiles = @(
                'win-8_x64_10.0.17763.0_5.1.17763.316_x64_4.0.30319.42000_framework',
                'win-8_x64_10.0.17763.0_7.0.0_x64_3.1.2_core'
            )
        }
        PSUseCompatibleTypes    = @{
            Enable         = $true
            TargetProfiles = @(
                'win-8_x64_10.0.17763.0_5.1.17763.316_x64_4.0.30319.42000_framework',
                'win-8_x64_10.0.17763.0_7.0.0_x64_3.1.2_core'
            )
        }
    }
}
