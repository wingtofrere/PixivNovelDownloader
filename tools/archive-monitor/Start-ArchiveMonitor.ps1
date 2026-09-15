[CmdletBinding()]
param(
    [string]$Endpoint = 'http://192.168.31.221:6999/api/novel/archive/status',
    [ValidateScript({ $_ -eq 0 -or ($_ -ge 5 -and $_ -le 3600) })][int]$IntervalSeconds = 0,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, System.Net.Http, System.Web.Extensions
$references = @('System.dll', 'System.Core.dll', 'System.Windows.Forms.dll', 'System.Drawing.dll', 'System.Net.Http.dll', 'System.Web.Extensions.dll')
try {
    Add-Type -Path (Join-Path $PSScriptRoot 'ArchiveMonitor.cs') -ReferencedAssemblies $references
    if ($CheckOnly) { Write-Output 'Monitor compiled successfully.'; return }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $key = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Endpoint))).Replace('-', '') }
    finally { $hasher.Dispose() }
    $monitorMutex = [Threading.Mutex]::new($false, "Local\PixivArchiveMonitor-$key")
    $owned = $false
    try {
        try { $owned = $monitorMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
        if (-not $owned) {
            [void][Windows.Forms.MessageBox]::Show('A monitor for this endpoint is already running. Open it from the system tray.', 'Pixiv Archive Monitor')
            return
        }
        [Windows.Forms.Application]::EnableVisualStyles()
        $window = [PixivArchiveMonitor.MonitorWindow]::new($Endpoint, $IntervalSeconds)
        [Windows.Forms.Application]::Run($window)
    } finally { if ($owned) { $monitorMutex.ReleaseMutex() }; $monitorMutex.Dispose() }
} catch {
    if ($CheckOnly) { throw }
    [void][Windows.Forms.MessageBox]::Show("Could not start monitor: $($_.Exception.Message)", 'Pixiv Archive Monitor')
    throw
}
