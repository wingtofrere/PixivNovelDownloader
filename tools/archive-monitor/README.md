# Pixiv archive status monitor for Windows

A standalone Windows tray monitor for `http://192.168.31.221:6999/api/novel/archive/status`.
It does not start, stop, or modify the downloader. No Java, Python, or Maven build is required for the monitor; it uses Windows PowerShell 5.1 and Windows .NET Framework.

## Start and authenticate

1. Keep this folder's files together. Double-click **Start-ArchiveMonitor.cmd** on the computer where you want the notification window.
2. Enter the **downloader administrator** username and password (the same account used at `http://192.168.31.221:6999/`), then click **Sign in**. Do not enter a Pixiv Cookie or your Pixiv account password.
3. The session is kept in memory. No password, cookie, or authentication file is saved. Restarting the monitor or an expired session requires signing in again. Authentication uses the target server's `/api/auth/login` endpoint. The supplied endpoint is HTTP, so use it on your trusted LAN; this monitor does not add TLS to that connection.
4. **Minimize to tray** hides the main window. Closing it with X also hides it; right-click the tray icon and select **Exit** to quit.

## Notifications

- One status check immediately after startup/login, then **300 seconds after each completed check**. Each request has a 10-second timeout; requests never overlap.
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

This compiles the actual program and checks status changes, initial abnormal state, connection failure thresholds/recovery, and strict JSON validation. It does not contact your downloader or read credentials. Actual authenticated monitoring requires signing in locally.
