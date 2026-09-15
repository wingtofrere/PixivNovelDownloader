$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Start-ArchiveMonitor.ps1') -CheckOnly
function Assert-True([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Snapshot([string]$Status, [bool]$Running = $true) {
    [PixivArchiveMonitor.Snapshot]::Parse((@{status=$Status; running=$Running} | ConvertTo-Json -Compress))
}
$t = [PixivArchiveMonitor.ChangeTracker]::new()
Assert-True ($null -eq $t.Observe((Snapshot 'RUNNING'))) 'First healthy sample must be quiet'
Assert-True ($null -eq $t.Observe((Snapshot 'RUNNING'))) 'Stable healthy state must be quiet'
Assert-True ($null -ne $t.Observe((Snapshot 'RUNNING' $false))) 'running flag change must alert'
Assert-True ($null -eq $t.Observe((Snapshot 'RUNNING' $false))) 'Stable abnormal state must be quiet'
Assert-True ($null -ne $t.Observe((Snapshot 'PAUSED' $false))) 'Different status must alert'
Assert-True ($null -ne $t.Observe((Snapshot 'RUNNING'))) 'Recovery must alert'
Assert-True ($null -eq $t.Failure()) 'First connection failure must be quiet'
Assert-True ($null -eq $t.Failure()) 'Second connection failure must be quiet'
Assert-True ($null -ne $t.Failure()) 'Third connection failure must alert'
Assert-True ($null -eq $t.Failure()) 'Repeated offline checks must be quiet'
Assert-True ($null -ne $t.Observe((Snapshot 'RUNNING'))) 'Network recovery must alert'
Assert-True ($null -eq $t.Failure()) 'Network recovery must reset failure counter'
$t2 = [PixivArchiveMonitor.ChangeTracker]::new()
Assert-True ($null -ne $t2.Observe((Snapshot 'DISABLED' $false))) 'Initial abnormal state must alert'
foreach ($body in @('{}', '{"status":"RUNNING","running":"true"}', '{"status":"","running":true}')) {
    $rejected = $false
    try { [void][PixivArchiveMonitor.Snapshot]::Parse($body) } catch { $rejected = $true }
    Assert-True $rejected 'Malformed status must not be treated as a real transition'
}
Write-Output 'PASS: 16 monitor state/parser checks.'
