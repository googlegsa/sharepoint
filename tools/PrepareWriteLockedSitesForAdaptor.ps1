# Run this script to prepare read only and write locked site collection for Adaptor crawling.
param (
    [Parameter(Mandatory=$true,
               HelpMessage="Please provide a URL pointing to your SharePoint Web
Application root.")]
    [ValidateNotNullOrEmpty()]
    [string]$VirtualServer,
    [Parameter(Mandatory=$true,
               HelpMessage="Please provide the username the connector will use
to crawl SharePoint. Do not include the domain name here.")]
    [ValidateNotNullOrEmpty()]
    [string]$Username,
    [Parameter(Mandatory=$true,
               HelpMessage="Please provide the password the connector will use
to crawl SharePoint.")]
    [ValidateNotNullOrEmpty()]
    [string]$Password,
    [Parameter(Mandatory=$true,
               HelpMessage="Please provide the domain of the user the connector
will use to crawl SharePoint.")]
    [ValidateNotNullOrEmpty()]
    [string]$Domain
)

Add-pssnapin Microsoft.SharePoint.Powershell -ErrorAction silentlycontinue

function Make-SiteDataRequest {
    Param ([String]$siteUrl)
    $url = $siteUrl + "/_vti_bin/sitedata.asmx"     
    $siteDataClient = [System.Net.WebRequest]::Create($URL)
    $siteDataClient.Headers.Add("SOAPAction", "http://schemas.microsoft.com/sharepoint/soap/GetContent");
    $siteDataClient.ContentType = "text/xml"
    $siteDataClient.Accept = "text/xml" 
    $siteDataClient.Method = "POST"
    $siteDataClient.Credentials = new-object System.Net.NetworkCredential($Username, $Password, $Domain)
    $requestBody = "<?xml version=""1.0"" encoding=""utf-8""?><soap:Envelope xmlns:xsi=""http://www.w3.org/2001/XMLSchema-instance"" 
    xmlns:xsd=""http://www.w3.org/2001/XMLSchema"" xmlns:soap=""http://schemas.xmlsoap.org/soap/envelope/""><soap:Body><GetContent xmlns=""http://schemas.microsoft.com/sharepoint/soap/""><objectType>SiteCollection</objectType><objectId>null</objectId><folderUrl></folderUrl><itemId>null</itemId><retrieveChildItems>true</retrieveChildItems><securityOnly>false</securityOnly><lastItemIdOnPage>null</lastItemIdOnPage></GetContent></soap:Body></soap:Envelope>"

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($requestBody)
    $siteDataClient.ContentLength = $bytes.Length         
    [System.IO.Stream] $outputStream = [System.IO.Stream]$siteDataClient.GetRequestStream()
    $outputStream.Write($bytes,0,$bytes.Length)  
    $outputStream.Close() 
    try
    {
        [System.Net.HttpWebResponse] $response = [System.Net.HttpWebResponse] $siteDataClient.GetResponse()     
        $sr = New-Object System.IO.StreamReader($response.GetResponseStream())       
        $responseText = $sr.ReadToEnd()
        Write-Host "CONTENT-TYPE: " $response.ContentType
        Write-Host "RESPONSE: " $responseText
        return $true       
        
    } catch [Net.WebException] { 
        [System.Net.HttpWebResponse] $resp = [System.Net.HttpWebResponse] $_.Exception.Response
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())       
        $responseText = $sr.ReadToEnd()
        Write-Host "ERROR - RESPONSE: " $responseText
               
        Write-Host $resp.StatusCode -ForegroundColor Red -BackgroundColor Yellow       
        Write-Host $resp.StatusDescription -ForegroundColor Red -BackgroundColor Yellow       
        return $false
    }
}

function Enable-AdaptorAccess {
  Param([String]$siteCollectionURL, [String]$lockMode)
  $canMakeRequest = Make-SiteDataRequest $siteCollectionURL
  if ($canMakeRequest) {
    "No need to process [$siteCollectionURL]. Adaptor user can make site data call."     
     return;    
  }
  #Adaptor user can not make request to Site Collection
  $siteCollectionURL + " is ReadOnly. Unlocking it for Adaptor user."
  Set-SPSite -Identity $siteCollectionURL -LockState "Unlock"
  $unlockedSite = Get-SPSite -Identity $siteCollectionURL
  if ($unlockedSite.WriteLocked) {
    Write-Host "[$siteCollectionURL] is still WriteLocked. Need to handle manually." -ForegroundColor Red -BackgroundColor Yellow    
    $unlockedSite.Dispose()
    return;
  }
  $unlockedSite.Dispose()
  $canMakeRequest = Make-SiteDataRequest $siteCollectionURL
  if ($canMakeRequest) {
    "Success. Adaptor user can make Site Data call for [$siteCollectionURL]."
  } else {
    "Failure. Adaptor user still can't make Site Data call for [$siteCollectionURL]."
  }
  #Revert back LockState
  Set-SPSite -Identity $siteCollectionURL -LockState $lockMode

}

function ProcessLockStateSites([string]$virtualServer,[string]$lockState) {
    $sites = @(Get-SPWebApplication $virtualServer | `
               Get-SPSite -Filter { $_.LockState -eq $lockState} | `
               select Url, Name, ReadLocked, ReadOnly, WriteLocked)

    if ($sites -eq $null -or $sites.Count -eq 0) {
        "No $lockState Site Collections were found"
        return
    }

    foreach($site in $sites) {
        $siteCollectionURL = $site.Url
        if (-not $siteCollectionURL) {
            "Unable to get URL for Site Collection $($site.Name)"
            continue
        }
        Write-Host ("Site URL[{0}] ReadOnly [{1}] ReadLocked [{2}] WriteLocked [{3}]" -f $site.Url,
            $site.ReadOnly, $site.ReadLocked, $site.WriteLocked)
        Enable-AdaptorAccess $siteCollectionURL $lockState
    }
}

ProcessLockStateSites $VirtualServer "ReadOnly"
ProcessLockStateSites $VirtualServer "NoAdditions"