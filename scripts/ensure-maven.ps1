param(
    [string]$MavenHome = $env:MAVEN_HOME
)

$ErrorActionPreference = 'Stop'

$candidates = @()
if ($MavenHome) {
    $candidates += Join-Path $MavenHome 'bin\mvn.cmd'
}
$command = Get-Command mvn -ErrorAction SilentlyContinue
if ($command) {
    Write-Output $command.Source
    exit 0
}
$candidates += @(
    'D:\java\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd',
    'C:\apache-maven\bin\mvn.cmd'
)

foreach ($candidate in $candidates) {
    if (Test-Path -LiteralPath $candidate) {
        Write-Output (Resolve-Path -LiteralPath $candidate).Path
        exit 0
    }
}

Write-Error '未找到 Maven。请安装 Maven 3.9+，或设置 MAVEN_HOME 后重新运行。'
exit 1
