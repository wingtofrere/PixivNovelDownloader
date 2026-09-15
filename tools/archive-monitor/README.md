# Pixiv archive status monitor for Windows

A standalone Windows tray monitor for `http://192.168.31.221:6999/api/novel/archive/status`.
It does not start, stop, or modify the downloader. No Java, Python, or Maven build is required for the monitor; it uses Windows PowerShell 5.1 and Windows .NET Framework.

## Start and authenticate

1. Keep this folder's files together. Double-click **Start-ArchiveMonitor.cmd** on the computer where you want the notification window.
2. Enter the **downloader administrator** username and password (the same account used at `http://192.168.31.221:6999/`), then click **Sign in**. Do not enter a Pixiv Cookie or your Pixiv account password.
3. Leave **Remember login on this PC** checked when signing in to request the server's persistent administrator session (currently up to 30 days). The monitor saves only `pixiv_session`, encrypted with Windows DPAPI for the current Windows user. It restores that session on the next launch; your password is never saved. Authentication uses the target server's `/api/auth/login` endpoint. The supplied endpoint is HTTP, so use it on your trusted LAN; this monitor does not add TLS to that connection.
4. Set **Check interval (seconds)** and click **Apply**. The allowed range is **5–3600 seconds**. The first launch defaults to 300 seconds; choose 5 or 10 seconds for frequent progress updates. Changes take effect without restarting.
5. Watch the progress panel and **Tag checkpoints** tab. **Refresh now** requests an immediate sample, even when automatic monitoring is paused.
6. **Minimize to tray** hides the main window. Closing it with X also hides it; right-click the tray icon and select **Exit** to quit.

## Progress and saved preferences

The panel refreshes from the status endpoint on each check. It shows:

- Active mode (dry run or real download), archive pause state, and discovered/completed/previewed/skipped/failed/pending/retry work counts.
- A progress bar for **processed works among currently discovered works**. Processed includes completed, previewed, skipped and failed works. This is not a success percentage, a complete-search percentage, or file-byte progress; the discovered total can grow while search continues.
- Per-tag saved page, state, date range, scanned entries and remaining date ranges. Scanned entries can overlap between tags and are not a unique download total.
- All raw kind/state counts, including series and both dry/real namespaces, in a separate tab. The summary uses only the currently active work namespace.
- Network state and a reported retry time, the last successful sample time/age, and a next-check countdown updated every second.

**Refresh now** does not resume paused automatic checks. Requests never overlap. A failed check leaves the last successful progress visible with a **DATA STALE / UNAVAILABLE** indicator. Missing progress fields are shown as unavailable rather than fabricated zero totals.

This endpoint is polled, not a server push feed. Progress becomes visible at the selected interval plus request time; selecting 300 seconds still means updates approximately every five minutes. The endpoint does not expose the currently transferring filename, downloaded bytes or an overall completion estimate.

The interval is saved, per endpoint, in `%LOCALAPPDATA%\PixivArchiveMonitor\interval-<endpoint hash>.json`. That JSON contains no authentication data; remembered sessions use a separate encrypted file described below. A damaged preference file falls back to 300 seconds. A command-line interval overrides the saved value for that launch; clicking Apply saves the selected value.

## Remembered administrator login

- After updating an older monitor, sign in once with **Remember login on this PC** enabled. Old in-memory sessions cannot simply be assigned a longer expiry by this program.
- Encrypted file: `%LOCALAPPDATA%\PixivArchiveMonitor\session-<endpoint hash>.bin`. It contains the administrator session, endpoint binding and server-issued expiry, protected with Windows DPAPI `CurrentUser`. No plaintext Cookie or password is written to disk, and no credential is placed in the repository.
- On launch, the monitor restores a decryptable, unexpired session automatically and checks it against the server. It never extends the server's expiry; logout/revocation, expiry, server changes or loss of the Windows encryption context can require signing in again. A damaged file also falls back to the login form.
- **Forget login** pauses this monitor and removes both its saved Cookie and its in-memory Cookie. It does not log other browsers out or revoke the session on the server. If the saved file cannot be deleted, the monitor reports that failure and its location.
- Uncheck **Remember login on this PC** to remove the saved copy and continue using the current in-memory login until exit. Signing in with the box unchecked does not save the session.
- HTTP 401/403 discards the local rejected session and asks for a new login. Network outages do not erase a saved session.
- Other software running as the same Windows user may be able to use that user's DPAPI protection; this is local credential protection, not a separate password vault.

## Notifications

- One status check immediately after startup/login, then **the selected interval after each completed check**. Each request has a 10-second timeout; requests never overlap.
- Expected state: exactly `status: "RUNNING"` and boolean `running: true`.
- The first healthy result is quiet. An initial abnormal result opens a window immediately.
- A change to either field opens a topmost notification window and plays a sound. Recovery also notifies. An unchanged state does not repeatedly alert.
- Three consecutive failed checks notify once (with a 300-second interval, roughly 10–15 minutes from the beginning of an outage). A subsequent successful response notifies recovery.
- HTTP 401/403 immediately pauses monitoring and asks you to sign in again. It is reported as monitor authentication failure, not as a downloader status.
- Only sampled states are observed: a change that happens and recovers between checks may be missed.
- `IDLE_OR_WAITING` also counts as a change. The downloader can legitimately report that state between work items; it does not necessarily mean failure.
- Only one popup is kept open; further changes update it. The main window retains the latest 200 events for this session.
- **Pause monitor** affects only this local monitor. It does not pause downloads. Windows sleep suspends checks; monitoring resumes after this computer wakes. No Windows startup task is installed.

## Custom endpoint or interval

Run in Windows PowerShell (the `.cmd` launcher selects it automatically):

```powershell
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File .\Start-ArchiveMonitor.ps1 -Endpoint 'http://192.168.31.221:6999/api/novel/archive/status' -IntervalSeconds 300
```

The monitor connects directly to the LAN server without using the computer's HTTP proxy. It does not follow redirects or send credentials to redirected hosts. One instance per endpoint is allowed in the current Windows session.

## Verification

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-ArchiveMonitor.ps1
```

This compiles the actual program and checks status changes, initial abnormal state, connection failure thresholds/recovery, strict JSON validation, active-mode progress totals, preference persistence, and the actual Windows interval/progress controls. It does not contact your downloader or read credentials. The tests use fictitious credentials and include DPAPI round-trip, corruption/expiry rejection, encrypted-file replacement/deletion, and remember/forget UI behavior. Actual authenticated monitoring requires signing in locally once.
