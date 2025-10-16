<#
.SYNOPSIS
  Posts a JSON file's contents to a remote URL during user logoff.
  Designed to run under SYSTEM or a service account without user profile access.
#>

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

$PostUrl = "https://192.168.1.29:8444/api/check/out"
$UserName = "test"
$Subfolder = "InOutWorker"
$SessionFile = "session.json"

# -------------------------------
# Config
# -------------------------------
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# -------------------------------
# Logging helper
# -------------------------------
function Write-Log {
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    Write-Host "[$timestamp][$Level] $Message"
}

# -------------------------------
# Locate logout file
# -------------------------------
$baseDir = Join-Path $env:ProgramData $Subfolder
$filePath = Join-Path $baseDir "$SessionFile"
#$filePath = "C:\ProgramData\$SessionFile"

#if (!(Test-Path $filePath)) {
#     Write-Host "No logout file found for $UserName at $filePath"
   # exit 0
#}

# -------------------------------
# Read file contents
# -------------------------------
try {
    $json = Get-Content -Path $filePath -Raw -ErrorAction Stop
    Write-Host "Read logout file for $UserName ($($json.Length) chars)."
}
catch {
    Write-Host "Failed to read logout file: $($_.Exception.Message)"
    #exit 1
    $json = "{}"
}

# -------------------------------
# Send POST request
# -------------------------------
try {
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)

    $request = [System.Net.HttpWebRequest]::Create($PostUrl)
    $request.Method = "POST"
    $request.ContentType = "application/json"
    $request.ContentLength = $body.Length
    $request.Timeout = 15000  # 15 seconds

    $stream = $request.GetRequestStream()
    $stream.Write($body, 0, $body.Length)
    $stream.Close()

    $response = $request.GetResponse()
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $respText = $reader.ReadToEnd()
    $reader.Close()
    $response.Close()

     Write-Host "POST succeeded for $UserName. Response: $respText"
}
catch {
     Write-Host "POST failed: $($_.Exception)" 
    exit 2
}

exit 0