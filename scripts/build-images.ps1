param(
    [string]$EnvFile = '.env',
    [string]$ComposeFile = 'compose.yml'
)
$ErrorActionPreference = 'Stop'
$deploymentRoot = Split-Path -Parent $PSScriptRoot
if (-not [IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $deploymentRoot $EnvFile }
if (-not [IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile = Join-Path $deploymentRoot $ComposeFile }
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$ComposeFile = (Resolve-Path -LiteralPath $ComposeFile).Path

Push-Location -LiteralPath $deploymentRoot
try {
    Write-Host 'Validating deployment configuration...'
    & docker compose --env-file $EnvFile -f $ComposeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration validation failed.' }

    Write-Host 'Verifying Maven artifacts...'
    & (Join-Path $deploymentRoot 'mvnw.cmd') -B -ntp '-Dmaven.compiler.fork=true' verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }

    Write-Host 'Building Docker images...'
    & docker compose --env-file $EnvFile -f $ComposeFile build
    if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
} finally {
    Pop-Location
}
