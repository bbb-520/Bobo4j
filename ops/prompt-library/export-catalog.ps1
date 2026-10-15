[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRepository,
    [Parameter(Mandatory = $true)][string]$OutputCatalog,
    [Parameter(Mandatory = $true)][string]$LicenseDirectory,
    [Parameter(Mandatory = $true)][string]$RagJar,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$opsRoot = $PSScriptRoot
$buildRoot = Join-Path $opsRoot 'build'
$classesRoot = Join-Path $buildRoot 'classes'
$extractRoot = Join-Path $buildRoot 'compile-dependencies'
$jarPath = (Resolve-Path -LiteralPath $RagJar).Path
$sourcePath = (Resolve-Path -LiteralPath $SourceRepository).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputCatalog)
$licensePath = [System.IO.Path]::GetFullPath($LicenseDirectory)
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
foreach ($required in @($javac, $java, (Join-Path $opsRoot 'PromptCatalogExporter.java'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing offline export prerequisite: $required" }
}
New-Item -ItemType Directory -Path $classesRoot, $extractRoot -Force | Out-Null

# Extract only existing parser bytecode and the Document compilation dependencies.
# PropertiesLauncher later resolves all runtime dependencies directly from the same jar.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $entries = @($archive.Entries | Where-Object {
        $_.FullName -match '^BOOT-INF/classes/com/bbb/exercise/agentdemo/ragservice/gallery/Gallery(MarkdownParser|ParseReport|Source)(\$[^/]+)?\.class$' -or
        $_.FullName -match '^BOOT-INF/lib/spring-ai-(commons|model)-[^/]+\.jar$'
    })
    if ($entries.Count -lt 9) { throw 'RAG jar lacks the existing gallery parser or its Document dependencies' }
    foreach ($entry in $entries) {
        $destination = Join-Path $extractRoot $entry.FullName
        New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    }
} finally { $archive.Dispose() }

$compileClasspath = (Join-Path $extractRoot 'BOOT-INF/classes') + ';' + (Join-Path $extractRoot 'BOOT-INF/lib/*')
& $javac '-encoding' 'UTF-8' '-cp' $compileClasspath '-d' $classesRoot (Join-Path $opsRoot 'PromptCatalogExporter.java')
if ($LASTEXITCODE -ne 0) { throw "Offline exporter compilation failed ($LASTEXITCODE)" }
$javaArgs = @('-Dfile.encoding=UTF-8', '-Dloader.main=PromptCatalogExporter', "-Dloader.path=$classesRoot", '-cp', $jarPath,
    'org.springframework.boot.loader.launch.PropertiesLauncher', $sourcePath, $outputPath, $licensePath,
    (Join-Path $opsRoot 'categories.zh-CN.tsv'), (Join-Path $opsRoot 'source-fingerprints.sha256'))
if ($Check) { $javaArgs += '--check' }
& $java @javaArgs
if ($LASTEXITCODE -ne 0) { throw "Offline catalog export failed ($LASTEXITCODE)" }
