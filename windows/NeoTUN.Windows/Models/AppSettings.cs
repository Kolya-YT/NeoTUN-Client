namespace NeoTUN.Windows.Models;

public sealed class AppSettings
{
    public string Theme { get; set; } = "dark"; // dark, light, oled
    public double FontScale { get; set; } = 1.0; // 0.85, 1.0, 1.15, 1.30
    public bool UiAnimations { get; set; } = true;
    public bool AutoUpdateSubscriptions { get; set; } = true;
    public int SubscriptionIntervalHours { get; set; } = 12;
    public string PingMode { get; set; } = "tcp"; // tcp, http
    public int PingTimeoutMs { get; set; } = 3500;
    public bool AutoReconnect { get; set; } = false;
    public bool LaunchWithWindows { get; set; } = false;
    public bool MinimizeToTrayOnClose { get; set; } = true;
    public bool MinimizeToTrayOnStart { get; set; } = false;
    public string? SelectedProfileUri { get; set; }
    public string? ActiveRoutingProfileId { get; set; }
}
