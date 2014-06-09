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
    Write-Output "diagnose_sp 1.0"
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

$allmembershipcount = 0
$allgroupcount = 0
$allitems = 0
foreach ($url in $virtualServers) {
    $webapp = Get-SPWebApplication -identity $url.Url
    $virtualServer = $webapp.Url
    [String]::Format("Web Application : {1} ({0})", $virtualServer, $webapp.Name);
    [String]::Format("Sharepoint Version: {0}", $webapp.Farm.BuildVersion);
    
    try {
        $contentdbs = Get-SPContentDatabase -webapplication $webapp.Url | Measure-Object;
    } catch {
        $contentdbs = Measure-Object
    }
    try {
        $sitecolids = Get-SPSite -WebApplication $webapp.Url -limit all -ErrorAction Stop | Select-Object ID
        $sitecols = $sitecolids | Measure-Object
    } catch {
        $sitecolids = $null
        $sitecols = Measure-Object
    }
    
    if ($summary -ne $true) {
        "Number of Content DBs = " + $contentdbs.Count
        "Number of Site Collections = " + $sitecols.Count
    }
    
    if ($summary -ne $true) {
        $mappings = $webapp.AlternateUrls
        if ($mappings -eq $null -or $mappings.Count -eq 0) {
            "Alternate Mappings: 0"
        } else {
            "Alternate Mappings: " + $mappings.Count
            foreach($mapping in $mappings) {
                $zone = $mapping.Zone
                [String]::Format("  {0,8} {1}", $zone, $mapping.IncomingUrl);
                $iis = $webapp.IisSettings[$zone]
                if ($iis -ne $null) {
                    $authmode = $iis.AuthenticationMode
                    $anonymous = $iis.AllowAnonymous
                    $wia = $iis.UseWindowsIntegratedAuthentication
                    $kerberos = ($iis.DisableKerberos -eq $false)
                    $claims = $iis.UseClaimsAuthentication
                    $claimsforms = $iis.UseFormsClaimsAuthenticationProvider
                    $claimstrusted = $iis.UseTrustedClaimsAuthenticationProvider
                    $claimswindows = $iis.UseWindowsClaimsAuthenticationProvider
                    "             Auth Mode: " + $authmode
                    "             Use WIA: " + $wia
                    "             Use Claims: " + $claims
                    if ($claims) {
                        "             Use Claims Forms: " + $claimsforms
                        "             Use Claims Windows: " + $claimswindows
                        "             Use Claims Trusted: " + $claimstrusted
                    }
                    "             Allow Kerberos: " + $kerberos
                }
            }
        }
    }


    ""
    if ($sitecolids -eq $null) {
        continue
    }
    
    $groupcount = 0
    $usercount = 0;
    $membershipcount = 0;
    foreach ($siteid in $sitecolids) {
        if ($siteid -eq $null -or $siteid.Id -eq $null) { 
            continue
        }
        $site = Get-SPSite -identity $siteid.Id
        $rootweb = [Microsoft.SharePoint.SPWeb]$site.RootWeb
        [string]::Format("Site Name = {4} Site Url = {0} Users = {1} Groups = {2} RoleAssignments = {3}", $site.Url, $rootweb.SiteUsers.Count, $rootweb.SiteGroups.Count, $rootweb.RoleAssignments.Count, $rootweb.Title)
        $groupcount = $rootweb.SiteGroups.Count;

        $membershipcount = 0
        foreach ($grp in $rootweb.SiteGroups) {
          $membershipcount += $grp.Users.Count
        }

        $allgroupcount += $groupcount
        $allmembershipcount += $membershipcount

        if ($summary -ne $true) {
            foreach($web in $site.AllWebs) {
                $totallists = $web.Lists | Measure-Object
                $versionedlists = $web.Lists | where { $_.EnableVersioning -eq $true} | Measure-Object
                $itemcount = $web.Lists | Measure-Object -Property ItemCount -Sum

                if ($totallists.Count -gt 0) {
                    [string]::Format("Web --> {0}", $web.Url) 
                    [string]::Format("Versioned Lists: {0} of {1}", $versionedlists.Count, $totallists.Count)
                    [string]::Format("Items: {0}", $itemcount.Sum)
                    $allitems += $itemcount.Sum
                    ""
                }                
                $web.dispose()
            }

            [string]::Format("# SP Groups = {0}  # SP Memberships = {1}", $groupcount, $membershipcount)
                ""            
        }
        
        $rootweb.dispose()
        $site.dispose()
    }
    ""
}
[string]::Format("Global # SP Groups = {0}  Global # SP Memberships = {1}  Global # Items = {2}", $allgroupcount, $allmembershipcount, $allitems)
