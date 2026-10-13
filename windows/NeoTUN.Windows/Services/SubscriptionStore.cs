using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
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
        foreach (var link in links)
        {
            var engine = NativeCore.DetectEngine(link);
            var name = ProfileStore.DisplayNameFromUri(link);
            parsedProfiles.Add(new ServerProfile
            {
                Name = name,
                Uri = link,
                Engine = engine,
                Source = subscription.Name,
                SourceSubscriptionId = subscription.Id,
                UpdatedAt = DateTimeOffset.UtcNow
            });
        }

        var importedCount = profileStore.ReplaceFromSubscription(subscription.Id, parsedProfiles);
        if (importedCount <= 0)
            throw new InvalidDataException("В подписке нет поддерживаемых профилей; сохранённые данные не изменены.");

        // Apply routing metadata only after a valid feed has produced and saved
        // supported profiles. A failed/unsupported refresh must not change the
        // active routing configuration.
        if (routingProfileStore != null)
        {
            ImportRoutingIfPresent(body + "\n" + decodedBody, routingHeader, autoRoutingHeader, routingProfileStore);
        }

        subscription.LastUpdated = DateTimeOffset.UtcNow;
        subscription.ProfileCount = importedCount;
        Save(subscription);

        return importedCount;
    }

    private static string DecodeFeedText(string raw)
    {
        var clean = string.Concat(raw.Where(c => !char.IsWhiteSpace(c)));
        try
        {
            var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(clean));
            if (decoded.Contains("://", StringComparison.Ordinal)) return decoded;
        }
        catch (FormatException) { }
        return raw;
    }

    private static List<string> ExtractLinks(string text)
    {
        var list = new List<string>();
        foreach (var line in text.Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var trimmed = line.Trim().Trim('\uFEFF');
            if (trimmed.StartsWith("#")) continue;
            if (SupportedSchemes.Any(s => trimmed.StartsWith(s, StringComparison.OrdinalIgnoreCase)))
            {
                list.Add(trimmed);
            }
        }
        return list;
    }

    private static void ImportRoutingIfPresent(
        string combinedBody,
        string? routingHeader,
        string? autoRoutingHeader,
        RoutingProfileStore routingProfileStore)
    {
        try
        {
            var lines = combinedBody.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Where(l => l.Contains("://autorouting/", StringComparison.OrdinalIgnoreCase) ||
                            l.Contains("://routing/", StringComparison.OrdinalIgnoreCase))
                .ToList();

            var candidate = (!string.IsNullOrWhiteSpace(autoRoutingHeader) ? autoRoutingHeader : null)
                ?? lines.FirstOrDefault(l => l.Contains("://autorouting/", StringComparison.OrdinalIgnoreCase))
                ?? (!string.IsNullOrWhiteSpace(routingHeader) ? routingHeader : null)
                ?? lines.FirstOrDefault(l => l.Contains("://routing/", StringComparison.OrdinalIgnoreCase));

            if (string.IsNullOrWhiteSpace(candidate)) return;

            // Android-like decoding for routing payload
            var decodedCandidate = candidate;
            try {
                if (!candidate.Contains("://", StringComparison.OrdinalIgnoreCase)) {
                    decodedCandidate = Encoding.UTF8.GetString(Convert.FromBase64String(candidate.Replace(" ", "")));
                }
            } catch { }

            routingProfileStore.ImportFromDeepLink(decodedCandidate);
        }
        catch
        {
            // Routing directive in subscription is optional; do not fail server import
        }
    }
}
