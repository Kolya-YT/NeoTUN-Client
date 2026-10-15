using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace NeoTUN.Windows.Models;

public sealed class ServerProfile : INotifyPropertyChanged
{
    private string _name = "Новый сервер";
    private string _uri = "";
    private string? _ping;
    private long _pingMs;
    private bool _isPingInProgress;
    private string _engine = "sing-box";

    public Guid Id { get; set; } = Guid.NewGuid();

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); OnPropertyChanged(nameof(CountryFlag)); } }
    }

    public string Uri
    {
        get => _uri;
        set
        {
            if (_uri != value)
            {
                _uri = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(Protocol));
                OnPropertyChanged(nameof(Endpoint));
                OnPropertyChanged(nameof(Host));
                OnPropertyChanged(nameof(Port));
                OnPropertyChanged(nameof(CountryFlag));
                OnPropertyChanged(nameof(ProtocolLabel));
            }
        }
    }

    public string Source { get; set; } = "local";

    public string? SourceSubscriptionId { get; set; }

    public string Engine
    {
        get => _engine;
        set { if (_engine != value) { _engine = value; OnPropertyChanged(); OnPropertyChanged(nameof(EngineLabel)); OnPropertyChanged(nameof(ProtocolLabel)); } }
    }

    public DateTimeOffset? UpdatedAt { get; set; }

    public string? Ping
    {
        get => _ping;
        set
        {
            if (_ping != value)
            {
                _ping = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(DisplayPing));
                OnPropertyChanged(nameof(PingColorBrushKey));
            }
        }
    }

    public long PingMs
    {
        get => _pingMs;
        set
        {
            if (_pingMs != value)
            {
                _pingMs = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(PingColorBrushKey));
            }
        }
    }

    public bool IsPingInProgress
    {
        get => _isPingInProgress;
        set { if (_isPingInProgress != value) { _isPingInProgress = value; OnPropertyChanged(); } }
    }

    public string Protocol
    {
        get
        {
            var separator = Uri.IndexOf("://", StringComparison.Ordinal);
            return separator > 0 ? Uri[..separator].ToUpperInvariant() : "UNKNOWN";
        }
    }

    public string Endpoint
    {
        get
        {
            var host = Host;
            var port = Port;
            if (string.IsNullOrEmpty(host)) return "Адрес не задан";
            return port > 0 ? $"{host}:{port}" : host;
        }
    }

    public string Host
    {
        get
        {
            if (System.Uri.TryCreate(Uri, UriKind.Absolute, out var parsed) && !string.IsNullOrEmpty(parsed.Host))
                return parsed.Host;

            var authority = Uri.Contains("://", StringComparison.Ordinal)
                ? Uri.Substring(Uri.IndexOf("://", StringComparison.Ordinal) + 3).Split('?')[0].Split('#')[0]
                : Uri.Split('?')[0].Split('#')[0];

            if (authority.Contains('@'))
                authority = authority.Substring(authority.LastIndexOf('@') + 1);

            return authority.Contains(':') ? authority.Split(':')[0] : authority;
        }
    }

    public int Port
    {
        get
        {
            if (System.Uri.TryCreate(Uri, UriKind.Absolute, out var parsed) && parsed.Port > 0)
                return parsed.Port;

            var authority = Uri.Contains("://", StringComparison.Ordinal)
                ? Uri.Substring(Uri.IndexOf("://", StringComparison.Ordinal) + 3).Split('?')[0].Split('#')[0]
                : Uri.Split('?')[0].Split('#')[0];

            if (authority.Contains('@'))
                authority = authority.Substring(authority.LastIndexOf('@') + 1);

            if (authority.Contains(':'))
            {
                var portStr = authority.Substring(authority.LastIndexOf(':') + 1);
                if (int.TryParse(portStr, out var port)) return port;
            }
            return 443;
        }
    }

    public string EngineLabel =>
        Engine.Contains("xray", StringComparison.OrdinalIgnoreCase) ? "Xray" : "sing-box";

    public string CountryFlag
    {
        get
        {
            // Use readable country names from the profile label, plus exact country-code
            // tokens. Short codes such as "fi", "de" and "us" must not match arbitrary
            // substrings in hostnames or names (for example, "office" contains "fi").
            var label = Name.ToLowerInvariant();
            var tokens = System.Text.RegularExpressions.Regex
                .Split(label, @"[^a-z0-9]+")
                .Where(token => token.Length > 0)
                .ToHashSet(StringComparer.Ordinal);

            if (ContainsAny(label, "нидерланд", "netherland") || HasToken(tokens, "nl")) return "🇳🇱";
            if (ContainsAny(label, "финлянд", "finland") || HasToken(tokens, "fi")) return "🇫🇮";
            if (ContainsAny(label, "герман", "german", "ютуб") || HasToken(tokens, "de")) return "🇩🇪";
            if (ContainsAny(label, "швед", "sweden") || HasToken(tokens, "se")) return "🇸🇪";
            if (ContainsAny(label, "франц", "france") || HasToken(tokens, "fr")) return "🇫🇷";
            if (ContainsAny(label, "сша", "usa", "united states", "america", "америк") || HasToken(tokens, "us")) return "🇺🇸";
            if (ContainsAny(label, "британ", "united kingdom", "great britain") || HasToken(tokens, "uk")) return "🇬🇧";
            if (ContainsAny(label, "турц", "turkey") || HasToken(tokens, "tr")) return "🇹🇷";
            if (ContainsAny(label, "росс", "russia") || HasToken(tokens, "ru")) return "🇷🇺";
            if (ContainsAny(label, "япон", "japan") || HasToken(tokens, "jp")) return "🇯🇵";
            if (ContainsAny(label, "сингапур", "singapore") || HasToken(tokens, "sg")) return "🇸🇬";
            if (ContainsAny(label, "польш", "poland") || HasToken(tokens, "pl")) return "🇵🇱";
            if (ContainsAny(label, "казах", "kazakhstan") || HasToken(tokens, "kz")) return "🇰🇿";
            return "🌐";
        }
    }

    private static bool ContainsAny(string value, params string[] candidates) =>
        candidates.Any(candidate => value.Contains(candidate, StringComparison.Ordinal));

    private static bool HasToken(HashSet<string> tokens, string token) => tokens.Contains(token);

    public string DisplayPing =>
        IsPingInProgress ? "…" : (string.IsNullOrWhiteSpace(Ping) ? "—" : Ping);

    public string PingColorBrushKey
    {
        get
        {
            if (IsPingInProgress || string.IsNullOrWhiteSpace(Ping) || Ping == "—")
                return "TextMutedBrush";
            if (PingMs > 0)
            {
                if (PingMs < 80) return "SuccessBrush";
                if (PingMs < 150) return "BrandVioletLightBrush";
                if (PingMs < 300) return "WarningBrush";
                return "DangerBrush";
            }
            if (Ping.EndsWith("мс", StringComparison.OrdinalIgnoreCase))
            {
                var numStr = new string(Ping.Where(char.IsDigit).ToArray());
                if (int.TryParse(numStr, out var ms))
                {
                    if (ms < 80) return "SuccessBrush";
                    if (ms < 150) return "BrandVioletLightBrush";
                    if (ms < 300) return "WarningBrush";
                    return "DangerBrush";
                }
            }
            return Ping.Contains("Ошибка", StringComparison.OrdinalIgnoreCase) ? "DangerBrush" : "TextMutedBrush";
        }
    }

    public string ProtocolLabel
    {
        get
        {
            var scheme = Protocol;
            var uri = Uri;
            string? transport = GetQueryParam(uri, "type") ?? (scheme is "VLESS" or "VMESS" ? "TCP" : null);
            if (string.Equals(transport, "raw", StringComparison.OrdinalIgnoreCase)) transport = "TCP";
            string? security = GetQueryParam(uri, "security") ?? (scheme is "TROJAN" or "HTTPS" ? "TLS" : null);
            var core = EngineLabel;

            var parts = new List<string> { scheme };
            if (!string.IsNullOrEmpty(transport)) parts.Add(transport.ToUpperInvariant());
            if (!string.IsNullOrEmpty(security)) parts.Add(security.ToUpperInvariant());
            parts.Add(core);

            return string.Join("  •  ", parts.Distinct());
        }
    }

    private static string? GetQueryParam(string uri, string paramName)
    {
        var queryIndex = uri.IndexOf('?');
        if (queryIndex < 0) return null;
        var query = uri.Substring(queryIndex + 1).Split('#')[0];
        foreach (var pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = pair.Split('=', 2);
            if (parts.Length > 0 && string.Equals(parts[0], paramName, StringComparison.OrdinalIgnoreCase))
                return parts.Length > 1 ? System.Uri.UnescapeDataString(parts[1]) : "";
        }
        return null;
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
