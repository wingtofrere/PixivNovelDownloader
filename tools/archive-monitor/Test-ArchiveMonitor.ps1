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

# Active-mode totals must not mix dry-run, real work, series, or control records.
$body = @'
{"status":"RUNNING","running":true,"dryRun":false,"paused":false,"counts":{"work:COMPLETED":12,"work:SKIPPED_EXCLUDED_TAG":3,"work:FAILED":1,"work:PENDING":4,"work:RETRY_WAIT":2,"dry-work:DRY_RUN":80,"series:COMPLETED":9,"control:PENDING":1},"network":{"state":"WAITING_NETWORK","due":1789400000000},"jobs":{"tagA":{"state":"PENDING","page":438,"from":"2020-01-01","to":"2020-02-01","scanned":2300,"remaining":[{"from":"2019-01-01"}]},"tagB":null}}
'@
$snapshot = [PixivArchiveMonitor.Snapshot]::Parse($body)
$p = $snapshot.Progress
Assert-True ($p.Discovered -eq 22) 'Real-work total must exclude dry run, series and control'
Assert-True ($p.Completed -eq 12 -and $p.Processed -eq 16) 'Processed must include completed, skipped and failed work'
Assert-True ($p.Pending -eq 4 -and $p.RetryWaiting -eq 2) 'Pending and retry counts must be distinct'
Assert-True ($p.Jobs['tagA']['page'] -eq 438) 'Persisted search page must be displayed without conversion'
Assert-True ($p.Jobs.Count -eq 2 -and $p.NetworkState.Contains('WAITING_NETWORK')) 'Jobs and network state must be retained'
$dry = [PixivArchiveMonitor.Snapshot]::Parse($body.Replace('"dryRun":false', '"dryRun":true')).Progress
Assert-True ($dry.Discovered -eq 80 -and $dry.Previewed -eq 80 -and $dry.Completed -eq 0) 'Dry-run summary must use its own namespace'
Assert-True (-not (Snapshot 'RUNNING').Progress.CanSummarize) 'Missing progress data must not be reported as zero downloads'
$badCounts = $false
try { [void][PixivArchiveMonitor.Snapshot]::Parse('{"status":"RUNNING","running":true,"counts":{"work:COMPLETED":-1}}') } catch { $badCounts = $true }
Assert-True $badCounts 'Invalid negative counts must be rejected'
$steady = [PixivArchiveMonitor.ChangeTracker]::new()
[void]$steady.Observe($snapshot)
Assert-True ($null -eq $steady.Observe([PixivArchiveMonitor.Snapshot]::Parse($body.Replace('"work:COMPLETED":12', '"work:COMPLETED":13')))) 'Progress-only updates must not raise status-change alarms'

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('pixiv-monitor-tests-' + [guid]::NewGuid().ToString('N'))
$window = $null
try {
    $file = [PixivArchiveMonitor.MonitorPreferences]::FilePath('http://test.invalid/status', $testRoot)
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 300) 'Missing preference uses default'
    [PixivArchiveMonitor.MonitorPreferences]::Save($file, 10)
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 10) 'Saved interval must survive reload'
    [PixivArchiveMonitor.MonitorPreferences]::Save($file, 600)
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 600) 'Existing preference must be replaced atomically'
    $stored = Get-Content -Raw $file | ConvertFrom-Json
    Assert-True (@($stored.PSObject.Properties.Name).Count -eq 1 -and $stored.intervalSeconds -eq 600) 'Preferences contain only interval, no authentication'
    [IO.File]::WriteAllText($file, '{"intervalSeconds":1}')
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 300) 'Out-of-range preference must fall back'
    [IO.File]::WriteAllText($file, 'broken json')
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 300) 'Corrupt preference must fall back'
    Assert-True ($file -ne [PixivArchiveMonitor.MonitorPreferences]::FilePath('http://another.invalid/status', $testRoot)) 'Endpoints must have separate preferences'

    # Construct the real Windows UI without showing it: no Shown event, login, or HTTP requests.
    $window = [PixivArchiveMonitor.MonitorWindow]::new('http://test.invalid/status', 300)
    $flags = [Reflection.BindingFlags]'Instance,NonPublic'
    $type = $window.GetType()
    $type.GetField('preferencesPath', $flags).SetValue($window, $file)
    $control = $window.Controls.Find('IntervalSeconds', $true)[0]
    $control.Value = 5
    [void]$type.GetMethod('ApplyInterval', $flags).Invoke($window, @())
    Assert-True ($type.GetField('timer', $flags).GetValue($window).Interval -eq 5000) 'UI Apply must update the live timer'
    Assert-True ([PixivArchiveMonitor.MonitorPreferences]::Load($file, 300) -eq 5) 'UI Apply must persist the selection'
    [void]$type.GetMethod('DisplayProgress', $flags).Invoke($window, @($p))
    Assert-True ($window.Controls.Find('ProgressSummary', $true)[0].Text.Contains('Completed: 12')) 'Real progress UI must show completed count'
    Assert-True ($type.GetField('jobs', $flags).GetValue($window).Rows.Count -eq 2) 'Real progress grid must contain tag rows'
    Assert-True ($window.Controls.Find('RefreshNow', $true).Count -eq 1) 'Manual refresh must be available'
} finally {
    if ($null -ne $window) {
        $type.GetField('tray', $flags).GetValue($window).Dispose()
        $type.GetField('clock', $flags).GetValue($window).Dispose()
        $type.GetField('timer', $flags).GetValue($window).Dispose()
        $type.GetField('client', $flags).GetValue($window).Dispose()
        $window.Dispose()
    }
    if (Test-Path $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
Write-Output 'PASS: 21 additional progress, preference, and Windows UI checks (37 total).'
