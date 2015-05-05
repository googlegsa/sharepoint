# Use this powershell script to print web application policy ACLs from SharePoint
# You need to run this script from SharePoint front end server using "Farm administrator"
# privileges.
#
# Steps to execute the script.
#   1. Copy script to local folder on SharePoint front-end server. e.g. C:\GSA\scripts
#   2. Open SharePoint 2010 / 2013 management shell using "Farm Administrator" privileges
#   3. Change current directory to script location e.g. CD C:\GSA\scripts
#   4. Execute script as .\webapp_policies.ps1 > out.txt
#   5. Output of the script is redirected to out.txt file
#
# By default script will print web application policies for all web applications. If you just 
# want to process single web application you can pass identity parameter as  
# .\webapp_policies.ps1  -identity <web app url> > out.txt

param (
    [string]$identity,
    [switch]$summary = $false
)
$ver = $host | select version
if ($ver.Version.Major -gt 1) {$host.Runspace.ThreadOptions = "ReuseThread"} 
if ((Get-PSSnapin "Microsoft.SharePoint.PowerShell" -ErrorAction SilentlyContinue) -eq $null) 
{
    Add-PSSnapin "Microsoft.SharePoint.PowerShell"
}

Function OutputIdentity() {
    $FQDN = [System.Net.Dns]::GetHostByName(($env:COMPUTERNAME)).HostName
    $UserInfo = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    Write-Output "webapp_policies"
    Write-Output ""
    Write-Output "Local Time: $(Get-Date)"
    Write-Output "  UTC Time: $($(Get-Date).toUniversalTime())"
    Write-Output ""
    Write-Output "I am $($UserInfo) running on $($env:COMPUTERNAME) ($($FQDN))"
    Write-Output ""
    $wos = Get-WmiObject -class Win32_OperatingSystem
    $os = $wos.Caption.Trim()
    if ($wos.CSDVersion -ne $Null) {
        $os = "$os - $($wos.CSDVersion)"
    }
    Write-Output "OS: $os"
    Write-Output ""
}

if ([string]::IsNullOrEmpty($identity) -eq $false) {
  $virtualServers = Get-SPWebApplication -identity $identity | where {$_.IsAdministrationWebApplication -eq $false} | Select-Object Url
} else {
  $virtualServers = Get-SPWebApplication | where {$_.IsAdministrationWebApplication -eq $false} | Select-Object Url
}

OutputIdentity


foreach ($url in $virtualServers) {
    $webapp = Get-SPWebApplication -identity $url.Url
    $virtualServer = $webapp.Url
    [String]::Format("Web Application : {1} ({0})", $virtualServer, $webapp.Name);
    [String]::Format("Sharepoint Version: {0}", $webapp.Farm.BuildVersion);
    [String]::Format("Web Application Policies for: {1} ({0})", $virtualServer, $webapp.Name);
    $webapp.Policies
}	