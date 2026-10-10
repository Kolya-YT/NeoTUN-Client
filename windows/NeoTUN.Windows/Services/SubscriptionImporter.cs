using System.Text;
using System.Net.Http;
using System.IO;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public static class SubscriptionImporter
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(25)
    };

    public static async Task<IReadOnlyList<ServerProfile>> ImportAsync(
        string input,
        CancellationToken cancellationToken = default)
    {
        var text = input.Trim();
        if (text.Length == 0) return Array.Empty<ServerProfile>();

        if (System.Uri.TryCreate(text, UriKind.Absolute, out var remote) &&
            (remote.Scheme == "https" || remote.Scheme == "http"))
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, remote);
            request.Headers.UserAgent.ParseAdd("NeoTUN-Windows/0.1");
            using var response = await Http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            response.EnsureSuccessStatusCode();

            var mediaType = response.Content.Headers.ContentType?.MediaType ?? "";
            var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
            if (bytes.Length == 0) return Array.Empty<ServerProfile>();
            if (bytes.Length > 5 * 1024 * 1024)
                throw new InvalidDataException("Подписка превышает лимит 5 МБ.");

            var body = Encoding.UTF8.GetString(bytes).Trim();
            if (body.Length == 0) return Array.Empty<ServerProfile>();
            var imported = Parse(body, source: remote.ToString());
            if (imported.Count == 0)
                throw new InvalidDataException($"Подписка загружена ({mediaType}), но поддерживаемых ссылок не найдено.");
            return imported;
        }

        return Parse(text);
    }

    private static readonly string[] SupportedSchemes =
        ["vless://", "vmess://", "trojan://", "hysteria2://", "hy2://", "tuic://", "ss://"];

    public static IReadOnlyList<ServerProfile> Parse(string input, string source = "import")
    {
        var text = input.Trim();
        if (text.Length == 0) return Array.Empty<ServerProfile>();

        // HTTP subscription fetching is handled by a dedicated importer; never decode its URL as Base64.
        if (System.Uri.TryCreate(text, UriKind.Absolute, out var remote) &&
            (remote.Scheme == "https" || remote.Scheme == "http"))
            return Array.Empty<ServerProfile>();

        var candidates = SplitLines(text);
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
