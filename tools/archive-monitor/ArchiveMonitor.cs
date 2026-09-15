using System;
using System.Collections.Generic;
using System.Drawing;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace PixivArchiveMonitor {
    public sealed class Snapshot {
        public string Status; public bool Running;
        public bool Healthy { get { return Status == "RUNNING" && Running; } }
        public string Key { get { return Status + "/" + Running; } }
        public override string ToString() { return "status: " + Status + ", running: " + Running.ToString().ToLowerInvariant(); }
        public static Snapshot Parse(string body) {
            var data = new JavaScriptSerializer().DeserializeObject(body) as Dictionary<string, object>;
            if (data == null || !data.ContainsKey("status") || !(data["status"] is string) ||
                !data.ContainsKey("running") || !(data["running"] is bool) ||
                String.IsNullOrWhiteSpace((string)data["status"]) || ((string)data["status"]).Length > 128)
                throw new FormatException("Response does not contain a valid status and running pair.");
            return new Snapshot { Status = (string)data["status"], Running = (bool)data["running"] };
        }
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
        private readonly Timer timer = new Timer();
        private readonly ChangeTracker tracker = new ChangeTracker();
        private readonly Label state = new Label();
        private readonly TextBox username = new TextBox();
        private readonly TextBox password = new TextBox();
        private readonly Button login = new Button();
        private readonly Button start = new Button();
        private readonly Button stop = new Button();
        private readonly ListBox history = new ListBox();
        private readonly NotifyIcon tray = new NotifyIcon();
        private Form alert;
        private TextBox alertText;
        private bool busy, monitoring, quitting, authNotified;
        private readonly int interval;

        public MonitorWindow(string address, int seconds) {
            endpoint = new Uri(address);
            if ((endpoint.Scheme != "http" && endpoint.Scheme != "https") || !String.IsNullOrEmpty(endpoint.UserInfo))
                throw new ArgumentException("Use an HTTP(S) endpoint without credentials in its URL.");
            if (seconds < 5 || seconds > 3600) throw new ArgumentException("Interval must be 5 to 3600 seconds.");
            interval = seconds;
            var handler = new HttpClientHandler { CookieContainer = new CookieContainer(), UseCookies = true,
                AllowAutoRedirect = false, UseProxy = false };
            client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10), MaxResponseContentBufferSize = 1024 * 1024 };
            client.DefaultRequestHeaders.Add("Cache-Control", "no-cache");
            Text = "Pixiv Archive Monitor"; ClientSize = new Size(700, 425);
            MinimumSize = new Size(716, 464); StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Segoe UI", 10); Icon = SystemIcons.Information;
            var addressLabel = new Label { Text = endpoint.ToString(), Left = 16, Top = 14, Width = 670, Height = 40, AutoEllipsis = true };
            Controls.Add(addressLabel);
            Controls.Add(new Label { Text = "Downloader admin", Left = 16, Top = 60, Width = 140 });
            username.SetBounds(158, 56, 180, 28); username.MaxLength = 128; Controls.Add(username);
            Controls.Add(new Label { Text = "Password", Left = 350, Top = 60, Width = 80 });
            password.SetBounds(430, 56, 180, 28); password.UseSystemPasswordChar = true; password.MaxLength = 1024; Controls.Add(password);
            login.Text = "Sign in"; login.SetBounds(16, 97, 100, 30); login.Click += async (s, e) => await LoginAsync(); Controls.Add(login);
            start.Text = "Start"; start.SetBounds(130, 97, 100, 30); start.Click += async (s, e) => { if (!busy) { monitoring = true; await CheckAsync(); } }; Controls.Add(start);
            stop.Text = "Pause monitor"; stop.SetBounds(244, 97, 140, 30); stop.Click += (s, e) => { monitoring = false; timer.Stop(); SetState("Monitor paused locally. The downloader is unchanged."); }; Controls.Add(stop);
            var hide = new Button { Text = "Minimize to tray", Left = 398, Top = 97, Width = 150, Height = 30 };
            hide.Click += (s, e) => Hide(); Controls.Add(hide);
            state.SetBounds(16, 140, 670, 55); Controls.Add(state);
            history.SetBounds(16, 202, 668, 180); history.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            history.HorizontalScrollbar = true; Controls.Add(history);
            Controls.Add(new Label { Text = "Checks every " + interval + "s. Password/session are held in memory only. Close = tray; Exit via tray menu.", Left = 16, Top = 392, Width = 670, Height = 25, Anchor = AnchorStyles.Bottom | AnchorStyles.Left });
            var menu = new ContextMenuStrip();
            menu.Items.Add("Open monitor", null, (s, e) => ShowMain());
            menu.Items.Add("Exit", null, (s, e) => { quitting = true; Close(); });
            tray.Icon = SystemIcons.Information; tray.Text = "Pixiv archive monitor"; tray.ContextMenuStrip = menu; tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowMain();
            timer.Interval = interval * 1000;
            timer.Tick += async (s, e) => await CheckAsync();
            Shown += async (s, e) => { monitoring = true; await CheckAsync(); };
            FormClosing += (s, e) => {
                if (!quitting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
                else { quitting = true; monitoring = false; timer.Stop(); if (alert != null) alert.Close(); tray.Visible = false; tray.Dispose(); client.Dispose(); timer.Dispose(); }
            };
            AcceptButton = login;
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
        private async Task LoginAsync() {
            if (busy || quitting) return;
            if (String.IsNullOrWhiteSpace(username.Text) || password.Text.Length == 0) { SetState("Enter the downloader administrator username and password (not your Pixiv login)."); return; }
            timer.Stop(); monitoring = false; busy = true; login.Enabled = false;
            try {
                SetState("Signing in...");
                string payload = new JavaScriptSerializer().Serialize(new { username = username.Text.Trim(), password = password.Text, rememberMe = false });
                password.Clear();
                using (var content = new StringContent(payload, Encoding.UTF8, "application/json"))
                using (var response = await client.PostAsync(new Uri(endpoint, "/api/auth/login"), content)) {
                    if (!response.IsSuccessStatusCode) { SetState("Login failed: HTTP " + (int)response.StatusCode + ". Check credentials; repeated attempts may be limited."); return; }
                }
                authNotified = false; monitoring = true; Record("Signed in; monitoring started.");
            } catch (Exception) { if (!quitting) SetState("Login connection failed. Check server/network, then try again."); }
            finally { busy = false; if (!quitting) login.Enabled = true; }
            if (monitoring && !quitting) await CheckAsync();
        }
        private async Task CheckAsync() {
            if (busy || !monitoring || quitting) return;
            busy = true; timer.Stop();
            try {
                using (var response = await client.GetAsync(endpoint)) {
                    if (quitting) return;
                    if (response.StatusCode == HttpStatusCode.Unauthorized || response.StatusCode == HttpStatusCode.Forbidden) {
                        monitoring = false;
                        SetState("Administrator login required (HTTP " + (int)response.StatusCode + "). Enter credentials and click Sign in.");
                        if (!authNotified) { Notify("Monitoring paused: administrator login is required.\r\nOpen the monitor and sign in locally."); authNotified = true; }
                        ShowMain(); return;
                    }
                    if (!response.IsSuccessStatusCode) throw new HttpRequestException("HTTP status error");
                    var snapshot = Snapshot.Parse(await response.Content.ReadAsStringAsync());
                    if (quitting) return;
                    SetState(snapshot + "\r\nLast successful check: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
                    Notify(tracker.Observe(snapshot));
                }
            } catch (Exception) {
                if (!quitting) { SetState("Status check failed at " + DateTime.Now.ToString("HH:mm:ss") + ". Retrying; popup after 3 consecutive failures."); Notify(tracker.Failure()); }
            } finally { busy = false; if (!quitting && monitoring) timer.Start(); }
        }
    }
}
