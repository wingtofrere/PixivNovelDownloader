[CmdletBinding()]
param([string]$RuntimeDirectory, [string]$JavaHome, [switch]$Foreground)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path (Split-Path -Parent $ProjectRoot) 'PixivNovelArchiveData' }
$RuntimeDirectory = [IO.Path]::GetFullPath($RuntimeDirectory)
if ($JavaHome) { $java = Join-Path $JavaHome 'bin/java.exe' }
elseif ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
else {
    $portable = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portable) { $java = Join-Path $portable.FullName 'bin/java.exe' }
    else { $java = (Get-Command java -ErrorAction Stop).Source }
}
$jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'pixivdownload-app/target') -Filter '*-boot.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw 'Run scripts/build-novel-archive.ps1 first.' }
foreach ($module in @('pixivdownload-plugin-novel', 'pixivdownload-plugin-download-workbench')) {
    if (-not (Test-Path (Join-Path $ProjectRoot "$module/target/classes/plugin.properties"))) { throw "Missing compiled $module; rebuild first." }
}
foreach ($directory in @('config/plugins', 'state', 'data', 'instance', 'log')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $RuntimeDirectory $directory) | Out-Null
}
$config = Join-Path $RuntimeDirectory 'config/plugins/novel-archive.json'
if (-not (Test-Path -LiteralPath $config)) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'docs/novel-archive/novel-archive.example.json') -Destination $config
    Write-Host "Created disabled example: $config"
    Write-Host 'Edit tags and excludedTags, then set enabled=true. Keep dryRun=true for the first check.'
    return
}
$properties = @(
    '-Dfile.encoding=UTF-8',
    '-Dpixivdownload.plugin-dev.enabled=true',
    "-Dpixivdownload.plugin-dev.root=$ProjectRoot",
    "-Dpixivdownload.config-dir=$(Join-Path $RuntimeDirectory 'config')",
    "-Dpixivdownload.state-dir=$(Join-Path $RuntimeDirectory 'state')",
    "-Dpixivdownload.data-dir=$(Join-Path $RuntimeDirectory 'data')",
    "-Dpixivdownload.instance-dir=$(Join-Path $RuntimeDirectory 'instance')",
    "-Dpixivdownload.plugins-dir=$(Join-Path $RuntimeDirectory 'plugins')",
    "-Dlogback.configurationFile=$(Join-Path $ProjectRoot 'docs/novel-archive/logback-archive.xml')",
    "-Dlogging.config=$(Join-Path $ProjectRoot 'docs/novel-archive/logback-archive.xml')",
    '-jar', $jar.FullName, '--no-gui')
$setupFile = Join-Path $RuntimeDirectory 'state/setup_config.json'
$setupComplete = $false
if (Test-Path -LiteralPath $setupFile) { $setupComplete = (Get-Content -Raw -LiteralPath $setupFile | ConvertFrom-Json).setupComplete }
if (-not $setupComplete) {
    Write-Host 'The upstream application requires one-time administrator setup before headless startup.'
    $setupArguments = @($properties | Where-Object { $_ -ne '--no-gui' }) + @('--setup')
    Push-Location $RuntimeDirectory
    try {
        & $java @setupArguments
        if ($LASTEXITCODE -ne 0) { throw 'Initial setup did not complete. No background worker was started.' }
    } finally { Pop-Location }
}
if ($Foreground) {
    Push-Location $RuntimeDirectory
    try { & $java @properties } finally { Pop-Location }
} else {
    # Start-Process joins arguments; quote every token to preserve Windows paths containing spaces.
    $quoted = $properties | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $RuntimeDirectory -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -LiteralPath (Join-Path $RuntimeDirectory 'state/archive-launcher.pid')
    Write-Host "Started PID $($process.Id). Runtime: $RuntimeDirectory"
    Write-Host 'Open http://localhost:6999 and sign in with the administrator account you configured.'
    Write-Host "Progress log: $(Join-Path $RuntimeDirectory 'log/archive.log')"
}
