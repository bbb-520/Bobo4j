$ErrorActionPreference = 'Stop'

function Assert-Healthy([string]$Url) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 10
    if ($response.StatusCode -ne 200) { throw "$Url returned $($response.StatusCode)" }
    Write-Host "OK $Url"
}

Assert-Healthy 'http://localhost:8848/nacos/v1/ns/operator/metrics'
Assert-Healthy 'http://localhost:18000/actuator/health'
Assert-Healthy 'http://localhost:18081/actuator/health'
Assert-Healthy 'http://localhost:18085/actuator/health'
Assert-Healthy 'http://localhost:18082/actuator/health'
Assert-Healthy 'http://localhost:18083/actuator/health'
Assert-Healthy 'http://localhost:18084/actuator/health'
Assert-Healthy 'http://localhost:18086/actuator/health'
Assert-Healthy 'http://localhost:9091/healthz'
Assert-Healthy 'http://localhost:19090/-/ready'
