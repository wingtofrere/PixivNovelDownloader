[CmdletBinding()]
param([string]$JavaHome, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path }
if (-not $env:JAVA_HOME) {
    $portable = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portable) { $env:JAVA_HOME = $portable.FullName }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 or pass -JavaHome.' }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required by the upstream resource build.' }
$env:MAVEN_USER_HOME = Join-Path $ProjectRoot '.tools/maven-home'
$repository = Join-Path $ProjectRoot '.tools/m2'
$arguments = @('-B', "-Dmaven.repo.local=$repository",
    '-pl', 'pixivdownload-app,pixivdownload-plugin-novel,pixivdownload-plugin-download-workbench', '-am',
    '-Dfile.encoding=UTF-8')
if ($SkipTests) { $arguments += '-DskipTests' }
else {
    $arguments += '-Dtest=Archive*Test,NovelDownload*Test,NovelEpubWriterTest,PixivScheduledNovel*Test,NovelPluginContributionTest,PixivAjaxProxyClientTest,DatabaseSchemaRegistryTest,DatabaseInitializerTest,NovelPluginModuleDependencyGuardTest,NovelPersistenceOwnershipGuardTest'
    $arguments += '-Dsurefire.failIfNoSpecifiedTests=false'
}
Push-Location $ProjectRoot
try {
    & (Join-Path $ProjectRoot 'mvnw.cmd') @arguments package
    if ($LASTEXITCODE -ne 0) { throw "Archive build failed ($LASTEXITCODE). Runtime data is unchanged." }
} finally { Pop-Location }
