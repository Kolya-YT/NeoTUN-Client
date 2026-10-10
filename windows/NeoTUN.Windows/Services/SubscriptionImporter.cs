using System.Text;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public static class SubscriptionImporter
{
    private static readonly string[] SupportedSchemes =
        ["vless://", "vmess://", "trojan://", "hysteria2://", "hy2://", "tuic://", "ss://"];

    private static readonly string[] SupportedSchemeNames =
        ["vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss"];

    public static IReadOnlyList<ServerProfile> Parse(string input, string source = "import")
    {
        var text = input.Trim();
        if (text.Length == 0) return Array.Empty<ServerProfile>();

        // A URL is fetched explicitly by the caller; do not treat a subscription URL as Base64.\n        if (System.Uri.TryCreate(text, UriKind.Absolute, out var remote) &&\n            (remote.Scheme == "https" || remote.Scheme == "http"))\n            return Array.Empty<ServerProfile>();\n\n        var candidates = SplitLines(text);
        if (candidates.Count == 1 && !LooksLikeShareUri(candidates[0]))
        {
            try
            {
                var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(RemoveWhitespace(candidates[0])));
                if (decoded.Contains("://", StringComparison.Ordinal)) candidates = SplitLines(decoded);
            }
            catch (FormatException) { /* Plain text or URL: keep the original input. */ }
        }

        var result = new List<ServerProfile>();
        foreach (var candidate in candidates)
        {
            var line = candidate.Trim().Trim('\uFEFF');
            if (!LooksLikeShareUri(line)) continue;
            if (!System.Uri.TryCreate(line, UriKind.Absolute, out var uri)) continue;
            if (!SupportedSchemes.Any(s => line.StartsWith(s, StringComparison.OrdinalIgnoreCase))) continue;
            result.Add(new ServerProfile
            {
                Name = ReadDisplayName(uri, line),
                Uri = line,
                Source = source,
                UpdatedAt = DateTimeOffset.UtcNow
            });
        }
        return result;
    }

    private static bool LooksLikeShareUri(string value) =>
        SupportedSchemes.Any(s => value.StartsWith(s, StringComparison.OrdinalIgnoreCase));

    private static List<string> SplitLines(string value) =>
        value.Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(line => !line.StartsWith("#", StringComparison.Ordinal))
            .ToList();

    private static string RemoveWhitespace(string value) =>
        string.Concat(value.Where(c => !char.IsWhiteSpace(c)));

    private static string ReadDisplayName(System.Uri uri, string original)
    {
        var fragment = uri.Fragment.TrimStart('#');
        if (fragment.Length > 0)
        {
            try { return Uri.UnescapeDataString(fragment); }
            catch (UriFormatException) { return fragment; }
        }
        return uri.Host.Length > 0 ? uri.Host : uri.Scheme.ToUpperInvariant() + " профиль";
    }
}
