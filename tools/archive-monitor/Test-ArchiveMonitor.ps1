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
    $sessionEndpoint = [Uri]'http://test.invalid/status'
    $sessionFile = [PixivArchiveMonitor.SessionVault]::FilePath($sessionEndpoint, $testRoot)
    $type.GetField('sessionPath', $flags).SetValue($window, $sessionFile)
    $rememberControl = $window.Controls.Find('RememberSession', $true)[0]
    Assert-True $rememberControl.Checked 'Remember session is enabled by default'
    $sessionCookie = [Net.Cookie]::new('pixiv_session', 'monitor-fixture-not-a-real-session', '/')
    $sessionCookie.Expires = [DateTime]::UtcNow.AddDays(30)
    $jar = $type.GetField('cookies', $flags).GetValue($window)
    $jar.Add($sessionEndpoint, $sessionCookie)
    [void]$type.GetMethod('SaveSession', $flags).Invoke($window, @())
    Assert-True (Test-Path $sessionFile) 'Remembered UI login must be saved'
    $rememberControl.Checked = $false
    Assert-True (-not (Test-Path $sessionFile) -and $null -ne $jar.GetCookies($sessionEndpoint)['pixiv_session']) 'Unchecking remember removes disk copy but keeps current login'
    $rememberControl.Checked = $true
    [void]$type.GetMethod('SaveSession', $flags).Invoke($window, @())
    [void]$type.GetMethod('ForgetSession', $flags).Invoke($window, @())
    Assert-True (-not (Test-Path $sessionFile) -and $null -eq $jar.GetCookies($sessionEndpoint)['pixiv_session']) 'Forget login removes both saved and in-memory session'

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
Write-Output 'PASS: 25 progress, preference, and Windows UI checks (41 total so far).'

$vaultRoot = Join-Path ([IO.Path]::GetTempPath()) ('pixiv-monitor-vault-' + [guid]::NewGuid().ToString('N'))
$origin = [Uri]'http://fixture.invalid/api/novel/archive/status'
$vault = [PixivArchiveMonitor.SessionVault]::FilePath($origin, $vaultRoot)
try {
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)) 'Missing session requires login'
    $cookie = [Net.Cookie]::new('pixiv_session', 'fake-test-cookie-no-real-credentials', '/')
    $cookie.Expires = [DateTime]::UtcNow.AddDays(30)
    [PixivArchiveMonitor.SessionVault]::Save($vault, $origin, $cookie)
    $bytes = [IO.File]::ReadAllBytes($vault)
    Assert-True (-not [Text.Encoding]::UTF8.GetString($bytes).Contains($cookie.Value)) 'Saved cookie must not be plaintext'
    $loaded = [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)
    Assert-True ($loaded.Value -eq $cookie.Value -and $loaded.Name -eq 'pixiv_session') 'DPAPI round trip must restore session'
    Assert-True ([Math]::Abs(($loaded.Expires.ToUniversalTime() - $cookie.Expires.ToUniversalTime()).TotalSeconds) -lt 1) 'Server expiry must not be extended'
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, [Uri]'http://other.invalid/api/novel/archive/status')) 'Cookie cannot be decrypted for another endpoint'
    $bytes[[int]($bytes.Length / 2)] = $bytes[[int]($bytes.Length / 2)] -bxor 1
    [IO.File]::WriteAllBytes($vault, $bytes)
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)) 'Tampered ciphertext must be rejected without crashing'
    [IO.File]::WriteAllBytes($vault, [byte[]](1,2,3))
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)) 'Unreadable ciphertext must require login'
    $expired = @{version=1;endpoint=$origin.AbsoluteUri;value='expired-fixture';expiresUtcTicks=[DateTime]::UtcNow.AddDays(-1).Ticks} | ConvertTo-Json -Compress
    $encrypted = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($expired), [Text.Encoding]::UTF8.GetBytes($origin.AbsoluteUri), [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($vault, $encrypted)
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)) 'Expired session must not be restored'
    $cookie.Value = 'new-fake-session'
    [PixivArchiveMonitor.SessionVault]::Save($vault, $origin, $cookie)
    Assert-True ([PixivArchiveMonitor.SessionVault]::Load($vault, $origin).Value -eq $cookie.Value) 'New session replaces the old saved session'
    $rejected = $false
    try { [PixivArchiveMonitor.SessionVault]::Save($vault, $origin, [Net.Cookie]::new('pixiv_session', 'temporary-fixture', '/')) } catch { $rejected = $true }
    Assert-True $rejected 'Temporary cookie must not be given a fabricated long expiry'
    [PixivArchiveMonitor.SessionVault]::Delete($vault)
    Assert-True (-not (Test-Path $vault)) 'Deleting remembered login removes encrypted file'
    [PixivArchiveMonitor.SessionVault]::Delete($vault)
    Assert-True ($null -eq [PixivArchiveMonitor.SessionVault]::Load($vault, $origin)) 'Deleting absent login is harmless'
} finally { if (Test-Path $vaultRoot) { Remove-Item -LiteralPath $vaultRoot -Recurse -Force } }
Write-Output 'PASS: 12 DPAPI session-vault checks (53 total).'
