using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public sealed class SubscriptionStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static readonly HttpClient Http = new(new HttpClientHandler
    {
        AutomaticDecompression = System.Net.DecompressionMethods.All
    })
    {
        Timeout = TimeSpan.FromSeconds(25)
    };

    private static readonly string[] SupportedSchemes =
        ["vless://", "vmess://", "trojan://", "hysteria2://", "hy2://", "tuic://", "ss://"];

    private readonly string _filePath;

    public SubscriptionStore()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        Directory.CreateDirectory(directory);
        _filePath = Path.Combine(directory, "subscriptions.json");
    }

    public IReadOnlyList<Subscription> All()
    {
        try
        {
            if (!File.Exists(_filePath)) return Array.Empty<Subscription>();
            return JsonSerializer.Deserialize<List<Subscription>>(File.ReadAllText(_filePath), JsonOptions)
                   ?? new List<Subscription>();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return Array.Empty<Subscription>();
        }
    }

    public void Save(Subscription subscription)
    {
        var list = All().ToList();
        var index = list.FindIndex(s => s.Id == subscription.Id ||
            string.Equals(s.Url.Trim().TrimEnd('/'), subscription.Url.Trim().TrimEnd('/'), StringComparison.OrdinalIgnoreCase));

        if (index >= 0)
        {
            subscription.Id = list[index].Id;
            list[index] = subscription;
        }
        else
        {
            list.Add(subscription);
        }
        Persist(list);
    }

    public void Delete(string id, ProfileStore profileStore)
    {
        var list = All().Where(s => s.Id != id).ToList();
        Persist(list);
        profileStore.DeleteBySubscription(id);
    }

    private void Persist(IEnumerable<Subscription> subscriptions)
    {
        var temp = _filePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(subscriptions, JsonOptions));
        File.Move(temp, _filePath, overwrite: true);
    }

    public async Task<int> RefreshAsync(
        Subscription subscription,
        ProfileStore profileStore,
        RoutingProfileStore? routingProfileStore = null,
        CancellationToken cancellationToken = default)
    {
        var normalizedUrl = subscription.Url.Trim();
        if (!Uri.TryCreate(normalizedUrl, UriKind.Absolute, out var uri) ||
            (uri.Scheme != "https" && uri.Scheme != "http"))
        {
            throw new ArgumentException("Ссылка подписки должна начинаться с HTTP или HTTPS.");
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.UserAgent.ParseAdd("NeoTUN-Windows/0.4");
        request.Headers.Accept.ParseAdd("text/plain, text/*, application/json, */*");

        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();

        var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (bytes.Length == 0) throw new InvalidDataException("Ответ подписки пуст.");
        if (bytes.Length > 5 * 1024 * 1024) throw new InvalidDataException("Размер подписки превышает 5 МБ.");

        var body = Encoding.UTF8.GetString(bytes).Trim();
        var decodedBody = DecodeFeedText(body);

        // Check for INCY/Happ routing directives in headers or body
        string? routingHeader = response.Headers.TryGetValues("routing", out var rVals) ? rVals.FirstOrDefault() : null;
        string? autoRoutingHeader = response.Headers.TryGetValues("autorouting", out var arVals) ? arVals.FirstOrDefault() : null;

        var links = ExtractLinks(decodedBody);
        if (links.Count == 0)
        {
            links = ExtractLinks(body);
        }

        if (links.Count == 0)
            throw new InvalidDataException("Подписка не содержит поддерживаемых ссылок.");

        var parsedProfiles = new List<ServerProfile>();
        var unsupportedProfiles = new List<string>();
        foreach (var link in links)
        {
            string engine;
            try
            {
                var runtime = NativeCore.BuildRuntimeConfig(link);
                engine = runtime.Engine;
                if (engine is not ("sing-box" or "xray"))
                {
                    unsupportedProfiles.Add(ProfileStore.DisplayNameFromUri(link));
                    continue;
                }
            }
            catch (Exception ex) when (ex is InvalidOperationException or System.Text.Json.JsonException or
                                       ArgumentException or FormatException)
            {
                // Do not silently turn a malformed or unsupported share link into
                // a sing-box profile. Keep the subscription's existing profiles
                // intact if none of the new links can actually be parsed.
                unsupportedProfiles.Add(ProfileStore.DisplayNameFromUri(link));
                continue;
            }

            parsedProfiles.Add(new ServerProfile
            {
                Name = ProfileStore.DisplayNameFromUri(link),
                Uri = link,
                Engine = engine,
                Source = subscription.Name,
                SourceSubscriptionId = subscription.Id,
                UpdatedAt = DateTimeOffset.UtcNow
            });
        }

        if (parsedProfiles.Count == 0)
        {
            var details = unsupportedProfiles.Count == 0
                ? ""
                : $" (не удалось разобрать: {string.Join(", ", unsupportedProfiles.Take(5))})";
            throw new InvalidDataException(
                "В подписке нет профилей, которые удалось разобрать текущим ядром NeoTUN" + details +
                ". Сохранённые профили не изменены.");
        }

        var importedCount = profileStore.ReplaceFromSubscription(subscription.Id, parsedProfiles);
        if (importedCount <= 0)
            throw new InvalidDataException("В подписке нет поддерживаемых профилей; сохранённые данные не изменены.");

        // Apply routing metadata only after a valid feed has produced and saved
        // supported profiles. A failed/unsupported refresh must not change the
        // active routing configuration.
        if (routingProfileStore != null)
        {
            await ImportRoutingIfPresentAsync(body + "\n" + decodedBody, routingHeader, autoRoutingHeader, routingProfileStore, cancellationToken);
        }

        subscription.LastUpdated = DateTimeOffset.UtcNow;
        subscription.ProfileCount = importedCount;
        Save(subscription);

        return importedCount;
    }

    private static string DecodeFeedText(string raw)
    {
        var text = raw.Trim().TrimStart('\uFEFF');
        if (text.Length == 0 || Regex.IsMatch(text, @"(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://"))
            return text;

        if (TryDecodeBase64(text, out var decoded) &&
            Regex.IsMatch(decoded, @"(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://"))
            return decoded;

        return raw;
    }

    private static bool TryDecodeBase64(string value, out string decoded)
    {
        decoded = "";
        var clean = string.Concat(value.Where(c => !char.IsWhiteSpace(c)));
        if (clean.Length == 0) return false;

        // Subscription feeds commonly use URL-safe Base64 and may omit padding.
        clean = clean.Replace('-', '+').Replace('_', '/');
        clean = clean.TrimEnd('=');
        clean = clean.PadRight(clean.Length + ((4 - clean.Length % 4) % 4), '=');
        try
        {
            decoded = Encoding.UTF8.GetString(Convert.FromBase64String(clean)).TrimStart('\uFEFF');
            return decoded.Length > 0;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static List<string> ExtractLinks(string text)
    {
        // Some providers put several share links on one line. Match each URI
        // without splitting commas that are valid inside Hysteria2 port ranges.
        const string linkPattern = @"(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://.*?(?=(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://|\s|$)";
        var links = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        void AddLinks(string value)
        {
            foreach (var line in value.Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                var trimmed = line.Trim().Trim('\uFEFF');
                if (trimmed.StartsWith("#", StringComparison.Ordinal)) continue;

                foreach (Match match in Regex.Matches(trimmed, linkPattern))
                {
                    var candidate = match.Value.Trim().TrimEnd(',', ';', '"', '\'');
                    if (candidate.Length == 0 ||
                        !SupportedSchemes.Any(s => candidate.StartsWith(s, StringComparison.OrdinalIgnoreCase)))
                        continue;
                    if (seen.Add(candidate)) links.Add(candidate);
                }
            }
        }

        AddLinks(text);

        // A few feeds encode individual lines rather than the complete response.
        foreach (var line in text.Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (Regex.IsMatch(line, @"(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://")) continue;
            if (TryDecodeBase64(line, out var decoded)) AddLinks(decoded);
        }

        return links;
    }

    private static async Task ImportRoutingIfPresentAsync(
        string combinedBody,
        string? routingHeader,
        string? autoRoutingHeader,
        RoutingProfileStore routingProfileStore,
        CancellationToken cancellationToken)
    {
        try
        {
            var lines = combinedBody.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Where(l => l.Contains("://autorouting/", StringComparison.OrdinalIgnoreCase) ||
                            l.Contains("://routing/", StringComparison.OrdinalIgnoreCase))
                .ToList();

            // Match Android's precedence: autorouting header, autorouting body,
            // routing header, then routing body.
            var candidate = (!string.IsNullOrWhiteSpace(autoRoutingHeader) ? autoRoutingHeader : null)
                ?? lines.FirstOrDefault(l => l.Contains("://autorouting/", StringComparison.OrdinalIgnoreCase))
                ?? (!string.IsNullOrWhiteSpace(routingHeader) ? routingHeader : null)
                ?? lines.FirstOrDefault(l => l.Contains("://routing/", StringComparison.OrdinalIgnoreCase));

            if (string.IsNullOrWhiteSpace(candidate)) return;
            candidate = DecodeRoutingDirective(candidate.Trim());

            if (candidate.Equals("off", StringComparison.OrdinalIgnoreCase) ||
                candidate.Contains("://routing/off", StringComparison.OrdinalIgnoreCase) ||
                candidate.Contains("://autorouting/off", StringComparison.OrdinalIgnoreCase))
            {
                routingProfileStore.SetEnabled(false);
                return;
            }

            var remoteUrl = ExtractRemoteRoutingUrl(candidate);
            if (remoteUrl != null)
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, remoteUrl);
                request.Headers.UserAgent.ParseAdd("NeoTUN-Windows/0.4");
                request.Headers.Accept.ParseAdd("application/json, text/plain, */*");
                using var response = await Http.SendAsync(
                    request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                response.EnsureSuccessStatusCode();

                var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
                if (bytes.Length == 0 || bytes.Length > 1024 * 1024) return;
                var remoteBody = Encoding.UTF8.GetString(bytes).Trim().TrimStart('\uFEFF');
                if (remoteBody.StartsWith("{", StringComparison.Ordinal))
                    routingProfileStore.ImportFromDeepLink(remoteBody);
                else
                    routingProfileStore.ImportFromDeepLink(DecodeRoutingDirective(remoteBody));
                return;
            }

            routingProfileStore.ImportFromDeepLink(candidate);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            // Routing metadata is optional; a bad directive must not fail server import.
        }
    }

    private static string DecodeRoutingDirective(string candidate)
    {
        var value = candidate.Trim().TrimStart('\uFEFF');
        if (value.Contains("://", StringComparison.OrdinalIgnoreCase)) return value;

        var encoded = string.Concat(value.Where(c => !char.IsWhiteSpace(c)))
            .Replace('-', '+')
            .Replace('_', '/')
            .TrimEnd('=');
        if (encoded.Length == 0) return value;
        encoded = encoded.PadRight(encoded.Length + ((4 - encoded.Length % 4) % 4), '=');

        try
        {
            var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(encoded)).Trim().TrimStart('\uFEFF');
            return decoded.Length > 0 ? decoded : value;
        }
        catch (FormatException)
        {
            return value;
        }
    }

    private static string? ExtractRemoteRoutingUrl(string candidate)
    {
        var marker = candidate.IndexOf("://autorouting/", StringComparison.OrdinalIgnoreCase);
        var auto = marker >= 0;
        if (marker < 0) marker = candidate.IndexOf("://routing/", StringComparison.OrdinalIgnoreCase);
        if (marker < 0) return null;

        var prefixLength = auto ? "://autorouting/".Length : "://routing/".Length;
        var payload = candidate[(marker + prefixLength)..].Trim();
        if (!auto)
        {
            if (payload.StartsWith("onadd/", StringComparison.OrdinalIgnoreCase))
                payload = payload["onadd/".Length..];
            else if (payload.StartsWith("add/", StringComparison.OrdinalIgnoreCase))
                payload = payload["add/".Length..];
            else
                return null;
        }

        // Preserve the URL's own path and query; remove only an optional fragment.
        var fragment = payload.IndexOf('#');
        if (fragment >= 0) payload = payload[..fragment];
        payload = Uri.UnescapeDataString(payload);
        if (!Uri.TryCreate(payload, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp))
            return null;

        if (uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) &&
            uri.AbsolutePath.Contains("/blob/", StringComparison.OrdinalIgnoreCase))
        {
            var path = uri.AbsolutePath.Replace("/blob/", "/", StringComparison.OrdinalIgnoreCase);
            var builder = new UriBuilder(uri)
            {
                Host = "raw.githubusercontent.com",
                Path = path
            };
            return builder.Uri.ToString();
        }

        return uri.ToString();
    }

}
