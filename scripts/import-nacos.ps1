$ErrorActionPreference = 'Stop'

$server = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { 'localhost:8848' }
$username = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { 'nacos' }
$password = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { 'nacos' }
$group = 'AGENT_GROUP'

foreach ($file in Get-ChildItem 'infra/nacos/import' -Filter '*.yml') {
    $dataId = $file.Name
    $content = Get-Content -Raw $file.FullName
    $body = @{
        dataId = $dataId
        group = $group
        content = $content
        type = 'yaml'
    }
    $uri = "http://$server/nacos/v1/cs/configs?username=$username&password=$password"
    Invoke-RestMethod -Method Post -Uri $uri -Body $body -ContentType 'application/x-www-form-urlencoded'
    Write-Host "Imported $dataId"
}
