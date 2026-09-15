using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Globalization;
using System.Security.Cryptography;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace PixivArchiveMonitor {
    public sealed class Snapshot {
        public string Status; public bool Running;
        public ProgressSnapshot Progress;
        public bool Healthy { get { return Status == "RUNNING" && Running; } }
        public string Key { get { return Status + "/" + Running; } }
        public override string ToString() { return "status: " + Status + ", running: " + Running.ToString().ToLowerInvariant(); }
        public static Snapshot Parse(string body) {
            var data = new JavaScriptSerializer().DeserializeObject(body) as Dictionary<string, object>;
            if (data == null || !data.ContainsKey("status") || !(data["status"] is string) ||
                !data.ContainsKey("running") || !(data["running"] is bool) ||
                String.IsNullOrWhiteSpace((string)data["status"]) || ((string)data["status"]).Length > 128)
                throw new FormatException("Response does not contain a valid status and running pair.");
            return new Snapshot { Status = (string)data["status"], Running = (bool)data["running"], Progress = ProgressSnapshot.Parse(data) };
        }
    }

    public sealed class ProgressSnapshot {
        public bool? DryRun, Paused;
        public bool HasCounts;
        public readonly SortedDictionary<string, long> Counts = new SortedDictionary<string, long>(StringComparer.Ordinal);
        public readonly SortedDictionary<string, Dictionary<string, object>> Jobs = new SortedDictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
        public string NetworkState = "No network state reported";
        public long Discovered, Completed, Previewed, Skipped, Failed, Pending, RetryWaiting;
        public long Processed { get { return Completed + Previewed + Skipped + Failed; } }
        public bool CanSummarize { get { return HasCounts && DryRun.HasValue; } }
        public static string Text(Dictionary<string, object> data, string key) {
            object value; return data != null && data.TryGetValue(key, out value) && value != null ? Convert.ToString(value, CultureInfo.InvariantCulture) : "-";
        }
        private static long Count(object value) {
            if (!(value is int) && !(value is long)) throw new FormatException("Count must be a nonnegative integer.");
            long n = Convert.ToInt64(value); if (n < 0) throw new FormatException("Count cannot be negative."); return n;
        }
        public static ProgressSnapshot Parse(Dictionary<string, object> data) {
            var result = new ProgressSnapshot(); object value;
            if (data.TryGetValue("dryRun", out value) && value is bool) result.DryRun = (bool)value;
            if (data.TryGetValue("paused", out value) && value is bool) result.Paused = (bool)value;
            if (data.TryGetValue("counts", out value) && value is Dictionary<string, object>) {
                result.HasCounts = true;
                foreach (var pair in (Dictionary<string, object>)value) result.Counts.Add(pair.Key, Count(pair.Value));
            }
            if (result.CanSummarize) {
                string prefix = result.DryRun.Value ? "dry-work:" : "work:";
                checked {
                    foreach (var pair in result.Counts) {
                        if (!pair.Key.StartsWith(prefix, StringComparison.Ordinal)) continue;
                        result.Discovered += pair.Value;
                        string kind = pair.Key.Substring(prefix.Length);
                        if (kind == "COMPLETED") result.Completed += pair.Value;
                        else if (kind == "DRY_RUN") result.Previewed += pair.Value;
                        else if (kind.StartsWith("SKIPPED_", StringComparison.Ordinal)) result.Skipped += pair.Value;
                        else if (kind == "FAILED") result.Failed += pair.Value;
                        else if (kind == "RETRY_WAIT") result.RetryWaiting += pair.Value;
                        else result.Pending += pair.Value;
                    }
                }
            }
            if (data.TryGetValue("jobs", out value) && value is Dictionary<string, object>) {
                foreach (var pair in (Dictionary<string, object>)value) {
                    var job = pair.Value as Dictionary<string, object>;
                    result.Jobs.Add(pair.Key, job ?? new Dictionary<string, object>());
                }
            }
            if (data.TryGetValue("network", out value) && value is Dictionary<string, object>) {
                var network = (Dictionary<string, object>)value;
                result.NetworkState = Text(network, "state");
                object due;
                if (network.TryGetValue("due", out due) && (due is int || due is long) && Convert.ToInt64(due) > 0) {
                    try { result.NetworkState += " | retry: " + new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(Convert.ToInt64(due)).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"); }
                    catch (ArgumentOutOfRangeException) { result.NetworkState += " | retry time unavailable"; }
                }
            }
            return result;
        }
    }

    public static class MonitorPreferences {
        public static string FilePath(string endpoint, string root) {
            using (var hash = SHA256.Create()) {
                return Path.Combine(root, "interval-" + BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(endpoint))).Replace("-", "") + ".json");
            }
        }
        public static int Load(string path, int fallback) {
            try {
                if (!File.Exists(path)) return fallback;
                var data = new JavaScriptSerializer().DeserializeObject(File.ReadAllText(path)) as Dictionary<string, object>;
                object value;
                if (data != null && data.TryGetValue("intervalSeconds", out value) && value is int && (int)value >= 5 && (int)value <= 3600) return (int)value;
            } catch (Exception) { }
            return fallback;
        }
        public static void Save(string path, int seconds) {
            if (seconds < 5 || seconds > 3600) throw new ArgumentOutOfRangeException("seconds");
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try {
                File.WriteAllText(temp, new JavaScriptSerializer().Serialize(new { intervalSeconds = seconds }), Encoding.UTF8);
                if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
            } finally { if (File.Exists(temp)) File.Delete(temp); }
        }
    }

    public static class SessionVault {
        public static string FilePath(Uri endpoint, string root) {
            return Path.Combine(root, Path.GetFileName(MonitorPreferences.FilePath(endpoint.AbsoluteUri, root)).Replace("interval-", "session-").Replace(".json", ".bin"));
        }
        public static void Save(string path, Uri endpoint, Cookie cookie) {
            if (cookie == null || cookie.Name != "pixiv_session" || cookie.Expired || String.IsNullOrEmpty(cookie.Value) ||
                cookie.Expires == DateTime.MinValue || cookie.Expires.ToUniversalTime() <= DateTime.UtcNow)
                throw new ArgumentException("A valid persistent administrator session is required.");
            byte[] plain = Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(new {
                version = 1, endpoint = endpoint.AbsoluteUri, value = cookie.Value, expiresUtcTicks = cookie.Expires.ToUniversalTime().Ticks
            }));
            byte[] encrypted;
            try { encrypted = ProtectedData.Protect(plain, Encoding.UTF8.GetBytes(endpoint.AbsoluteUri), DataProtectionScope.CurrentUser); }
            finally { Array.Clear(plain, 0, plain.Length); }
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try {
                File.WriteAllBytes(temp, encrypted);
                if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
            } finally { if (File.Exists(temp)) File.Delete(temp); }
        }
        public static Cookie Load(string path, Uri endpoint) {
            if (!File.Exists(path)) return null;
            byte[] plain = null;
            try {
                if (new FileInfo(path).Length > 65536) return null;
                plain = ProtectedData.Unprotect(File.ReadAllBytes(path), Encoding.UTF8.GetBytes(endpoint.AbsoluteUri), DataProtectionScope.CurrentUser);
                var data = new JavaScriptSerializer().DeserializeObject(Encoding.UTF8.GetString(plain)) as Dictionary<string, object>;
                if (data == null || !data.ContainsKey("version") || !(data["version"] is int) || (int)data["version"] != 1 ||
                    !data.ContainsKey("endpoint") || !String.Equals(data["endpoint"] as string, endpoint.AbsoluteUri, StringComparison.Ordinal) ||
                    !data.ContainsKey("value") || !(data["value"] is string) || String.IsNullOrEmpty((string)data["value"]) ||
                    !data.ContainsKey("expiresUtcTicks") || !(data["expiresUtcTicks"] is long)) return null;
                var expiry = new DateTime((long)data["expiresUtcTicks"], DateTimeKind.Utc);
                if (expiry <= DateTime.UtcNow) return null;
                return new Cookie("pixiv_session", (string)data["value"], "/") { Expires = expiry, HttpOnly = true, Secure = endpoint.Scheme == "https" };
            } catch (Exception) { return null; }
            finally { if (plain != null) Array.Clear(plain, 0, plain.Length); }
        }
        public static void Delete(string path) { if (File.Exists(path)) File.Delete(path); }
    }

    // Network faults must be consecutive. No repeated notification for an unchanged pair.
    public sealed class ChangeTracker {
        private Snapshot previous;
        private int failures;
        private bool offline;
        public string Observe(Snapshot current) {
            string message = null;
            if (offline) message = "Connection restored.\r\n" + current;
            else if (previous == null && !current.Healthy) message = "Archive is not in the expected RUNNING / true state.\r\n" + current;
            else if (previous != null && previous.Key != current.Key) message = "Archive state changed.\r\nBefore: " + previous + "\r\nNow: " + current;
            previous = current; failures = 0; offline = false;
            return message;
        }
        public string Failure() {
            failures++;
            if (failures < 3 || offline) return null;
            offline = true;
            return "Cannot read archive status after 3 consecutive checks.\r\nCheck the server, network, and monitor login.";
        }
    }

    public sealed class MonitorWindow : Form {
        private readonly Uri endpoint;
        private readonly HttpClient client;
        private readonly CookieContainer cookies = new CookieContainer();
        private readonly CheckBox remember = new CheckBox();
        private readonly Button forget = new Button();
        private readonly string sessionPath;
        private string savedSessionSignature;
        private readonly Timer timer = new Timer();
        private readonly Timer clock = new Timer();
        private readonly ChangeTracker tracker = new ChangeTracker();
        private readonly Label state = new Label();
        private readonly TextBox username = new TextBox();
        private readonly TextBox password = new TextBox();
        private readonly Button login = new Button();
        private readonly Button start = new Button();
        private readonly Button stop = new Button();
        private readonly ListBox history = new ListBox();
        private readonly NotifyIcon tray = new NotifyIcon();
        private readonly NumericUpDown intervalInput = new NumericUpDown();
        private readonly Button refresh = new Button();
        private readonly Label schedule = new Label();
        private readonly Label summary = new Label();
        private readonly Label progressNote = new Label();
        private readonly Label preferenceNotice = new Label();
        private readonly ProgressBar progress = new ProgressBar();
        private readonly DataGridView jobs = new DataGridView();
        private readonly DataGridView counts = new DataGridView();
        private readonly string preferencesPath;
        private DateTime nextCheckUtc = DateTime.MinValue, lastSuccessUtc = DateTime.MinValue;
        private bool stale = true;
        private Form alert;
        private TextBox alertText;
        private bool busy, monitoring, quitting, authNotified;
        private int interval;

        public MonitorWindow(string address, int seconds) {
            endpoint = new Uri(address);
            if ((endpoint.Scheme != "http" && endpoint.Scheme != "https") || !String.IsNullOrEmpty(endpoint.UserInfo))
                throw new ArgumentException("Use an HTTP(S) endpoint without credentials in its URL.");
            if (seconds != 0 && (seconds < 5 || seconds > 3600)) throw new ArgumentException("Interval must be 5 to 3600 seconds.");
            preferencesPath = MonitorPreferences.FilePath(endpoint.ToString(), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PixivArchiveMonitor"));
            interval = seconds == 0 ? MonitorPreferences.Load(preferencesPath, 300) : seconds;
            sessionPath = SessionVault.FilePath(endpoint, Path.GetDirectoryName(preferencesPath));
            var restored = SessionVault.Load(sessionPath, endpoint);
            if (restored != null) { cookies.Add(endpoint, restored); savedSessionSignature = restored.Value + "/" + restored.Expires.ToUniversalTime().Ticks; }
            var handler = new HttpClientHandler { CookieContainer = cookies, UseCookies = true,
                AllowAutoRedirect = false, UseProxy = false };
            client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10), MaxResponseContentBufferSize = 1024 * 1024 };
            client.DefaultRequestHeaders.Add("Cache-Control", "no-cache");
            Text = "Pixiv Archive Monitor"; ClientSize = new Size(880, 720);
            MinimumSize = new Size(896, 759); StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Segoe UI", 10); Icon = SystemIcons.Information;
            Controls.Add(new Label { Text = endpoint.ToString(), Left = 16, Top = 12, Width = 848, Height = 30, AutoEllipsis = true });
            Controls.Add(new Label { Text = "Downloader admin", Left = 16, Top = 53, Width = 140 });
            username.SetBounds(158, 49, 180, 28); username.MaxLength = 128; Controls.Add(username);
            Controls.Add(new Label { Text = "Password", Left = 350, Top = 53, Width = 80 });
            password.SetBounds(430, 49, 180, 28); password.UseSystemPasswordChar = true; password.MaxLength = 1024; Controls.Add(password);
            login.Text = "Sign in"; login.SetBounds(624, 47, 100, 32); login.Click += async (s, e) => await LoginAsync(); Controls.Add(login);
            forget.Name = "ForgetSession"; forget.Text = "Forget login"; forget.SetBounds(736, 47, 128, 32);
            forget.Click += (s, e) => { if (!busy) { monitoring = false; timer.Stop(); bool removed = ForgetSession(); remember.Checked = false; SetState(removed ? "Saved and in-memory login removed. Sign in to resume monitoring." : "In-memory login removed, but encrypted file could not be deleted. See Notifications."); UpdateClock(); } }; Controls.Add(forget);
            remember.Name = "RememberSession"; remember.Text = "Remember login on this PC"; remember.Checked = true; remember.SetBounds(592, 93, 272, 30);
            remember.CheckedChanged += (s, e) => { if (!remember.Checked) { if (DeleteSavedSession()) Record("Session will not be kept after exit."); else SetState("Could not delete saved login. See Notifications for the file location."); } else { Record("Sign in again to request and remember a long-lived session."); } }; Controls.Add(remember);
            start.Text = "Start"; start.SetBounds(16, 92, 100, 32); start.Click += async (s, e) => { if (!busy) { monitoring = true; await CheckAsync(); } }; Controls.Add(start);
            stop.Text = "Pause monitor"; stop.SetBounds(130, 92, 140, 32); stop.Click += (s, e) => { monitoring = false; timer.Stop(); nextCheckUtc = DateTime.MinValue; SetState("Monitor paused locally. The downloader is unchanged."); UpdateClock(); }; Controls.Add(stop);
            refresh.Name = "RefreshNow"; refresh.Text = "Refresh now"; refresh.SetBounds(284, 92, 130, 32);
            refresh.Click += async (s, e) => await CheckAsync(true); Controls.Add(refresh);
            var hide = new Button { Text = "Minimize to tray", Left = 428, Top = 92, Width = 150, Height = 32 };
            hide.Click += (s, e) => Hide(); Controls.Add(hide);
            Controls.Add(new Label { Text = "Check interval (seconds)", Left = 16, Top = 145, Width = 185 });
            intervalInput.Name = "IntervalSeconds"; intervalInput.SetBounds(205, 140, 100, 30); intervalInput.Minimum = 5; intervalInput.Maximum = 3600; intervalInput.Value = interval; Controls.Add(intervalInput);
            var apply = new Button { Name = "ApplyInterval", Text = "Apply", Left = 319, Top = 138, Width = 90, Height = 32 };
            apply.Click += (s, e) => ApplyInterval(); Controls.Add(apply);
            preferenceNotice.SetBounds(425, 142, 430, 30); preferenceNotice.Text = "5-3600 seconds. Apply to save for the next launch."; Controls.Add(preferenceNotice);
            state.SetBounds(16, 182, 848, 47); Controls.Add(state);
            schedule.SetBounds(16, 231, 848, 26); Controls.Add(schedule);
            summary.Name = "ProgressSummary"; summary.SetBounds(16, 265, 848, 53); summary.Text = "Waiting for the first successful status response."; Controls.Add(summary);
            progress.Name = "DiscoveredProgress"; progress.SetBounds(16, 326, 848, 18); progress.Maximum = 1000; progress.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; Controls.Add(progress);
            progressNote.SetBounds(16, 350, 848, 45); progressNote.Text = "Progress is based on discovered works, not all matching novels or download bytes."; Controls.Add(progressNote);
            var tabs = new TabControl { Left = 16, Top = 402, Width = 848, Height = 265, Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right };
            var jobsPage = new TabPage("Tag checkpoints"); var countsPage = new TabPage("All state counts"); var eventsPage = new TabPage("Notifications");
            ConfigureGrid(jobs, new[] { "Tag", "State", "Page", "From", "To", "Scanned entries", "Remaining ranges" });
            ConfigureGrid(counts, new[] { "Kind / state", "Count" });
            jobsPage.Controls.Add(jobs); countsPage.Controls.Add(counts); history.Dock = DockStyle.Fill; history.HorizontalScrollbar = true; eventsPage.Controls.Add(history);
            tabs.TabPages.Add(jobsPage); tabs.TabPages.Add(countsPage); tabs.TabPages.Add(eventsPage); Controls.Add(tabs);
            Controls.Add(new Label { Text = "Remembered login is encrypted for your Windows account. Password is never saved. Close = tray; Exit via tray.", Left = 16, Top = 684, Width = 848, Height = 25, Anchor = AnchorStyles.Bottom | AnchorStyles.Left });
            var menu = new ContextMenuStrip();
            menu.Items.Add("Open monitor", null, (s, e) => ShowMain());
            menu.Items.Add("Exit", null, (s, e) => { quitting = true; Close(); });
            tray.Icon = SystemIcons.Information; tray.Text = "Pixiv archive monitor"; tray.ContextMenuStrip = menu; tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowMain();
            timer.Interval = interval * 1000;
            timer.Tick += async (s, e) => await CheckAsync();
            clock.Interval = 1000; clock.Tick += (s, e) => UpdateClock(); clock.Start();
            Shown += async (s, e) => { monitoring = true; await CheckAsync(); };
            FormClosing += (s, e) => {
                if (!quitting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
                else { quitting = true; monitoring = false; timer.Stop(); if (alert != null) alert.Close(); tray.Visible = false; tray.Dispose(); client.Dispose(); timer.Dispose(); clock.Stop(); clock.Dispose(); }
            };
            AcceptButton = login;
            if (restored != null) Record("Restored encrypted administrator session; checking server validity.");
            else if (File.Exists(sessionPath)) Record("Saved session expired or could not be decrypted. Sign in again.");
        }
        private static void ConfigureGrid(DataGridView grid, string[] columns) {
            grid.Dock = DockStyle.Fill; grid.ReadOnly = true; grid.AllowUserToAddRows = false; grid.AllowUserToDeleteRows = false;
            grid.RowHeadersVisible = false; grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
            grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            foreach (string column in columns) grid.Columns.Add(column, column);
        }
        private void ApplyInterval() {
            interval = (int)intervalInput.Value; timer.Stop(); timer.Interval = interval * 1000;
            if (monitoring && !busy) ScheduleNext();
            try { MonitorPreferences.Save(preferencesPath, interval); preferenceNotice.Text = "Saved: " + interval + " seconds (takes effect now)."; }
            catch (Exception) { preferenceNotice.Text = "Applied for this session; could not save preference."; }
            UpdateClock();
        }
        private void ScheduleNext() { nextCheckUtc = DateTime.UtcNow.AddSeconds(interval); timer.Start(); }
        private void UpdateClock() {
            if (quitting) return;
            string age = lastSuccessUtc == DateTime.MinValue ? "No successful sample yet" : "Last success: " + lastSuccessUtc.ToLocalTime().ToString("HH:mm:ss") + " (" + (long)(DateTime.UtcNow - lastSuccessUtc).TotalSeconds + "s ago)";
            string next = busy ? "Checking..." : !monitoring ? "Automatic checks paused" : "Next check in " + Math.Max(0, (int)Math.Ceiling((nextCheckUtc - DateTime.UtcNow).TotalSeconds)) + "s";
            schedule.Text = next + " | " + age + (stale ? " | DATA STALE / UNAVAILABLE" : "");
            schedule.ForeColor = stale ? Color.DarkOrange : SystemColors.ControlText;
        }
        private void DisplayProgress(ProgressSnapshot data) {
            string mode = !data.DryRun.HasValue ? "Mode unknown" : data.DryRun.Value ? "DRY RUN" : "DOWNLOAD";
            if (data.CanSummarize) {
                summary.Text = mode + " | Discovered: " + data.Discovered + " | Completed: " + data.Completed + " | Previewed: " + data.Previewed +
                    "\r\nSkipped: " + data.Skipped + " | Failed: " + data.Failed + " | Pending/other: " + data.Pending + " | Retry waiting: " + data.RetryWaiting + " | Archive paused: " + (data.Paused.HasValue ? data.Paused.Value.ToString() : "unknown");
                progress.Value = data.Discovered == 0 ? 0 : Math.Min(1000, (int)(1000m * data.Processed / data.Discovered));
                progressNote.Text = "Processed " + data.Processed + " / " + data.Discovered + " discovered works (includes skips/failures; search may discover more).\r\nNetwork: " + data.NetworkState;
            } else {
                summary.Text = mode + " | This response does not provide enough information to summarize work progress.";
                progress.Value = 0; progressNote.Text = "Overall total / byte progress unavailable. Network: " + data.NetworkState;
            }
            jobs.Rows.Clear();
            foreach (var pair in data.Jobs) {
                object remaining; var job = pair.Value;
                string ranges = job.TryGetValue("remaining", out remaining) && remaining is object[] ? ((object[])remaining).Length.ToString() : "-";
                jobs.Rows.Add(pair.Key, ProgressSnapshot.Text(job, "state"), ProgressSnapshot.Text(job, "page"), ProgressSnapshot.Text(job, "from"), ProgressSnapshot.Text(job, "to"), ProgressSnapshot.Text(job, "scanned"), ranges);
            }
            counts.Rows.Clear(); foreach (var pair in data.Counts) counts.Rows.Add(pair.Key, pair.Value);
        }
        private void ShowMain() { Show(); WindowState = FormWindowState.Normal; Activate(); }
        private void SetState(string message) { if (!quitting) state.Text = message; }
        private void Record(string message) {
            if (quitting) return;
            history.Items.Insert(0, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + message.Replace("\r\n", "  "));
            while (history.Items.Count > 200) history.Items.RemoveAt(history.Items.Count - 1);
        }
        private void Notify(string message) {
            if (String.IsNullOrEmpty(message) || quitting) return;
            Record(message);
            if (alert == null || alert.IsDisposed) {
                alert = new Form { Text = "Pixiv archive status changed", ClientSize = new Size(570, 240), TopMost = true,
                    StartPosition = FormStartPosition.CenterScreen, Icon = SystemIcons.Warning, Font = Font };
                alertText = new TextBox { Multiline = true, ReadOnly = true, Dock = DockStyle.Fill, ScrollBars = ScrollBars.Vertical };
                var dismiss = new Button { Text = "Dismiss", Dock = DockStyle.Bottom, Height = 36 };
                dismiss.Click += (s, e) => alert.Close();
                alert.Controls.Add(alertText); alert.Controls.Add(dismiss);
            }
            alertText.Text = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "\r\n" + endpoint + "\r\n\r\n" + message;
            alert.Show(); alert.BringToFront();
            System.Media.SystemSounds.Exclamation.Play();
        }
        private bool DeleteSavedSession() {
            savedSessionSignature = null;
            try { SessionVault.Delete(sessionPath); return true; }
            catch (Exception) { Record("Could not remove encrypted session file. Check access to " + sessionPath); return false; }
        }
        private bool ForgetSession() {
            bool removed = DeleteSavedSession();
            cookies.Add(endpoint, new Cookie("pixiv_session", "", "/") { Expires = DateTime.UtcNow.AddDays(-1), Expired = true });
            stale = true; return removed;
        }
        private void SaveSession() {
            if (!remember.Checked) return;
            Cookie cookie = cookies.GetCookies(endpoint)["pixiv_session"];
            if (cookie == null) return;
            string signature = cookie.Value + "/" + cookie.Expires.ToUniversalTime().Ticks;
            if (signature == savedSessionSignature) return;
            try { SessionVault.Save(sessionPath, endpoint, cookie); savedSessionSignature = signature; Record("Administrator session saved with Windows account encryption."); }
            catch (Exception) { Record("Session could not be saved. Monitoring continues in memory; sign in with Remember login enabled to retry."); }
        }
        private async Task LoginAsync() {
            if (busy || quitting) return;
            if (String.IsNullOrWhiteSpace(username.Text) || password.Text.Length == 0) { SetState("Enter the downloader administrator username and password (not your Pixiv login)."); return; }
            timer.Stop(); monitoring = false; busy = true; login.Enabled = false; refresh.Enabled = false; forget.Enabled = false; remember.Enabled = false; UpdateClock();
            try {
                SetState("Signing in...");
                string payload = new JavaScriptSerializer().Serialize(new { username = username.Text.Trim(), password = password.Text, rememberMe = remember.Checked });
                password.Clear();
                using (var content = new StringContent(payload, Encoding.UTF8, "application/json"))
                using (var response = await client.PostAsync(new Uri(endpoint, "/api/auth/login"), content)) {
                    if (!response.IsSuccessStatusCode) { SetState("Login failed: HTTP " + (int)response.StatusCode + ". Check credentials; repeated attempts may be limited."); return; }
                }
                if (quitting) return;
                if (remember.Checked) SaveSession(); else DeleteSavedSession();
                authNotified = false; monitoring = true; Record("Signed in; monitoring started.");
            } catch (Exception) { if (!quitting) SetState("Login connection failed. Check server/network, then try again."); }
            finally { busy = false; if (!quitting) { login.Enabled = true; refresh.Enabled = true; forget.Enabled = true; remember.Enabled = true; UpdateClock(); } }
            if (monitoring && !quitting) await CheckAsync();
        }
        private async Task CheckAsync(bool manual = false) {
            if (busy || (!monitoring && !manual) || quitting) return;
            busy = true; timer.Stop(); refresh.Enabled = false; forget.Enabled = false; remember.Enabled = false; UpdateClock();
            try {
                using (var response = await client.GetAsync(endpoint)) {
                    if (quitting) return;
                    if (response.StatusCode == HttpStatusCode.Unauthorized || response.StatusCode == HttpStatusCode.Forbidden) {
                        monitoring = false; stale = true; ForgetSession();
                        SetState("Administrator login required (HTTP " + (int)response.StatusCode + "). Enter credentials and click Sign in.");
                        if (!authNotified) { Notify("Monitoring paused: administrator login is required.\r\nOpen the monitor and sign in locally."); authNotified = true; }
                        ShowMain(); return;
                    }
                    if (!response.IsSuccessStatusCode) throw new HttpRequestException("HTTP status error");
                    var snapshot = Snapshot.Parse(await response.Content.ReadAsStringAsync());
                    if (quitting) return;
                    lastSuccessUtc = DateTime.UtcNow; stale = false; SaveSession();
                    SetState(snapshot.ToString()); DisplayProgress(snapshot.Progress);
                    Notify(tracker.Observe(snapshot));
                }
            } catch (Exception) {
                if (!quitting) { stale = true; SetState("Status check failed at " + DateTime.Now.ToString("HH:mm:ss") + ". Retrying; popup after 3 consecutive failures."); Notify(tracker.Failure()); }
            } finally { busy = false; if (!quitting) { refresh.Enabled = true; forget.Enabled = true; remember.Enabled = true; if (monitoring) ScheduleNext(); UpdateClock(); } }
        }
    }
}
