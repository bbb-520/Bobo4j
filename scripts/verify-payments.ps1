[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '../.env'),
    [string[]]$ComposeFiles = @(),
    [string]$AuthContainer = 'agentdemo-agent-auth-service-1',
    [string]$ExpectedAppId = '',
    [switch]$ConfigurationOnly
)
$ErrorActionPreference = 'Stop'
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
if ($ComposeFiles.Count -eq 0) {
    $label = & docker inspect $AuthContainer --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($label)) {
        throw 'Cannot locate the deployment entry point. Supply -ComposeFiles explicitly.'
    }
    $ComposeFiles = $label.Trim().Split(',')
}
$composeArgs = @('compose', '--env-file', $EnvFile)
foreach ($file in $ComposeFiles) { $composeArgs += @('-f', (Resolve-Path -LiteralPath $file).Path) }
# Config contains secrets: keep it in memory and never print the raw JSON.
$raw = & docker @composeArgs config --format json
if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }
$config = ($raw -join "`n") | ConvertFrom-Json
$auth = $config.services.'agent-auth-service'
if ($null -eq $auth) { throw 'Compose has no agent-auth-service.' }
$settings = $auth.environment
if ([string]$settings.ALIPAY_ENABLED -ne 'true') { throw 'ALIPAY_ENABLED must be true in the selected environment file.' }
foreach ($name in @('ALIPAY_APP_ID','ALIPAY_SELLER_ID','ALIPAY_PRIVATE_KEY_PATH','ALIPAY_PUBLIC_KEY_PATH','PAYMENT_NOTIFY_BASE_URL')) {
    if ([string]::IsNullOrWhiteSpace([string]$settings.$name)) { throw "$name is missing." }
}
if ($ExpectedAppId -and [string]$settings.ALIPAY_APP_ID -ne $ExpectedAppId) { throw 'Configured Alipay application does not match -ExpectedAppId.' }
$callback = [uri]$settings.PAYMENT_NOTIFY_BASE_URL
if (-not $callback.IsAbsoluteUri -or $callback.Scheme -ne 'https' -or $callback.IsLoopback -or $callback.UserInfo -or $callback.Query -or $callback.Fragment) {
    throw 'PAYMENT_NOTIFY_BASE_URL must be a public HTTPS base URL without credentials, query or fragment.'
}
foreach ($name in @('ALIPAY_PRIVATE_KEY_PATH','ALIPAY_PUBLIC_KEY_PATH')) {
    $containerPath = [string]$settings.$name
    $mount = @($auth.volumes | Where-Object { $containerPath.StartsWith($_.target.TrimEnd('/') + '/') })
    if ($mount.Count -ne 1 -or $mount[0].type -ne 'bind' -or -not $mount[0].read_only) { throw "$name needs one read-only bind mount." }
    $relative = $containerPath.Substring($mount[0].target.TrimEnd('/').Length).TrimStart('/')
    $hostFile = Join-Path $mount[0].source $relative
    if (-not (Test-Path -LiteralPath $hostFile -PathType Leaf) -or (Get-Item -LiteralPath $hostFile).Length -eq 0) { throw "$name points to a missing or empty mounted file." }
}
Write-Output 'OK: explicit environment file, enabled Alipay, application/merchant fields, HTTPS callback and read-only key mounts.'
if (-not $ConfigurationOnly) {
    $runtimeRaw = & docker inspect $AuthContainer --format '{{json .Config.Env}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the running Auth service.' }
    $runtime = @{}
    foreach ($line in ($runtimeRaw | ConvertFrom-Json)) { $parts = $line.Split('=',2); $runtime[$parts[0]]=$parts[1] }
    foreach ($name in @('ALIPAY_ENABLED','ALIPAY_APP_ID','ALIPAY_SELLER_ID','ALIPAY_GATEWAY','ALIPAY_PRIVATE_KEY_PATH','ALIPAY_PUBLIC_KEY_PATH','PAYMENT_NOTIFY_BASE_URL')) {
        if ([string]$runtime[$name] -ne [string]$settings.$name) { throw "Runtime $name differs from selected configuration. Recreate Auth with the same Compose entry point and explicit --env-file; restart alone is insufficient." }
    }
    & docker exec $AuthContainer sh -c 'test -s "$ALIPAY_PRIVATE_KEY_PATH" && test -s "$ALIPAY_PUBLIC_KEY_PATH"'
    if ($LASTEXITCODE -ne 0) { throw 'Payment keys are not readable inside Auth.' }
    Write-Output 'OK: running Auth payment settings match Compose; both payment keys are mounted and readable.'
}
Write-Output 'Preflight passed. Merchant product permissions and actual payment still require your signed-in 0.01 CNY end-to-end test.'
