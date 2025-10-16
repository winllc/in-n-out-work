<#
.SYNOPSIS
  Helper functions for mTLS event handling on user logon/logoff.

.DESCRIPTION
  Provides functions to:
   - Wait for a user profile to load
   - Find a certificate by thumbprint or subject
   - Send an HTTPS POST with client certificate (mTLS)
   - Create or read logout files under %ProgramData%
#>



param()

# -------------------------------
# Config
# -------------------------------
#$script:PostUrl = "https://desktop.winllc-dev.com:8444/api/check/in"
$script:CertificateThumbprint = "PUT_CERT_THUMBPRINT_HERE"
$script:CertificateSubjectContains = ""
$script:ProfileWaitTimeoutSec = 30
$script:ProfileCheckIntervalSec = 2
$script:Subfolder = "InOutWorker"

$PostUrl = "https://192.168.1.29:8444/api/check/in"

# -------------------------------
# Ignore SSL/TLS errors
# -------------------------------
Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCertsPolicy : ICertificatePolicy {
    public bool CheckValidationResult(
        ServicePoint srvPoint, X509Certificate certificate,
        WebRequest request, int certificateProblem) { return true; }
}
"@

[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCertsPolicy
[Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

# -------------------------------
# Logging helper
# -------------------------------
function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    Write-Host "[$timestamp][$Level] $Message"
}

# -------------------------------
# Wait until user profile is loaded
# -------------------------------
function Wait-ForUserProfile {
    param([string]$Username)

    $timeout = [DateTime]::Now.AddSeconds($script:ProfileWaitTimeoutSec)
    while ([DateTime]::Now -lt $timeout) {
        try {
            $profiles = Get-WmiObject -Class Win32_UserProfile -Filter "Loaded=True"
            foreach ($p in $profiles) {
                if ($p.LocalPath -match $Username -and (Test-Path $p.LocalPath)) {
                    return $true
                }
            }
        } catch {}
        Start-Sleep -Seconds $script:ProfileCheckIntervalSec
    }
    return $false
}

# -------------------------------
# Find a certificate
# -------------------------------
function Find-UserCertificate {
    param(
        [string]$Thumbprint,
        [string]$SubjectContains
    )

    try {
        $store = New-Object System.Security.Cryptography.X509Certificates.X509Store "My","CurrentUser"
        $store.Open("ReadOnly")
        $certs = $store.Certificates

        # First: search by thumbprint with private key
        if ($Thumbprint -and $Thumbprint -ne "PUT_CERT_THUMBPRINT_HERE") {
            $match = $certs | Where-Object { $_.Thumbprint -eq $Thumbprint -and $_.HasPrivateKey }
            if ($match) { return $match[0] }
        }

        # Next: search by subject with private key
        if ($SubjectContains) {
            $match = $certs | Where-Object { $_.Subject -like "*$SubjectContains*" -and $_.HasPrivateKey }
            if ($match) { return $match[0] }
        }

        # Fallback: latest valid cert with private key
        $latest = $certs | Where-Object { $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } |
                  Sort-Object NotBefore -Descending | Select-Object -First 1

        return $latest
    }
    catch {
        Write-Log "Certificate lookup failed: $_" "WARN"
        return $null
    }
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Send-PostWithClientCert {
    param(
        [string] $PostUrl,               # URL to send the POST request to
        [string] $EventReason = "Unknown", # Optional event reason (default is "Unknown")
        [hashtable] $AdditionalData = @{ }  # Additional data that can be included in the body (optional)
    )

    try {
        # Set the security protocol to TLS 1.2 for the connection (as mTLS generally uses this protocol)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        
        # Find the client certificate (this function is assumed to be implemented elsewhere in your code)
        $cert = Find-UserCertificate
        Write-Log "Cert: $cert"  # Log the certificate (for debugging or auditing purposes)

        # Prepare the body of the POST request, including eventType and username from the environment
        $Body = @{
            eventType = "SessionLogon"    # Type of event, here it's "SessionLogon"
            windowsUserId  = $env:USERNAME     # Current user's username from the environment variable
        } + $AdditionalData  # Merge any additional data passed to the function
       
        # Convert the body hashtable to a compressed JSON string
        $jsonBody = $Body | ConvertTo-Json -Compress

        # Send the POST request with the certificate for mTLS authentication
        $response = Invoke-RestMethod -Uri $PostUrl `
            -Method Post `
            -Certificate $cert `
            -ContentType "application/json" `
            -Body $jsonBody | ConvertTo-Json

        # Return the response from the server
        return $response
    }
    catch {
        # Catch any errors that occur and log them
        Write-Error "Send-PostWithClientCert exception: $($_.Exception.Message)"
        return $null  # Return $null if there was an error
    }
}


# -------------------------------
# Create or update logout file
# -------------------------------
function Create-OrUpdateLogoutFile {
    param(
        [string]$UserSid,
        [string]$Contents,
        [string]$Subfolder = $script:Subfolder
    )

    $filename = "session.json"

    $baseDir = Join-Path $env:ProgramData $Subfolder
    if (!(Test-Path $baseDir)) { New-Item -ItemType Directory -Path $baseDir | Out-Null }

    $targetPath = Join-Path $baseDir "$filename"

    $Contents | Out-File -FilePath $targetPath -Encoding utf8 -Force
    #Move-Item -Path $tempPath -Destination $targetPath -Force

    # Make readable by Authenticated Users
    try {
        $acl = Get-Acl $targetPath
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule("Authenticated Users","ReadAndExecute","Allow")
        $acl.AddAccessRule($rule)
        Set-Acl $targetPath $acl
    } catch {
        Write-Log "ACL warning on $_" "WARN"
    }
    return $targetPath
}

# -------------------------------
# Read logout file
# -------------------------------

# -------------------------------
# Public wrappers for login/logout
# -------------------------------
function Invoke-UserLogon {
    Write-Log "User logon detected."
    $username = $env:USERNAME
    if (!(Wait-ForUserProfile $username)) {
        Write-Log "Profile for $username not ready within timeout." "WARN"
        return
    }

    $content = Send-PostWithClientCert -PostUrl $PostUrl

    Create-OrUpdateLogoutFile -Contents $content
}

Invoke-UserLogon