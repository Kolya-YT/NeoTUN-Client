using System.Collections.ObjectModel;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace NeoTUN.Windows;

public sealed class ProfileStore
{
    private static readonly string DirectoryPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
    private static readonly string FilePath = Path.Combine(DirectoryPath, "profiles.json");
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = TimeSpan.FromSeconds(25) };
    private static readonly HashSet<string> SupportedSchemes = new(StringComparer.OrdinalIgnoreCase) { "vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss" };
    public ObservableCollection<Profile> Profiles { get; } = [];

    public ProfileStore()
    {
        try { if (!File.Exists(FilePath)) return; var loaded = JsonSerializer.Deserialize<List<Profile>>(File.ReadAllText(FilePath)) ?? []; foreach (var p in loaded.Where(p => !string.IsNullOrWhiteSpace(p.Uri))) Profiles.Add(p); }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { }
    }

    public async Task<int> ImportSubscriptionAsync(string sourceUrl, CancellationToken cancellationToken = default)
    {
        if (!System.Uri.TryCreate(sourceUrl, UriKind.Absolute, out var url) || (url.Scheme != "https" && url.Scheme != "http"))
            throw new InvalidOperationException("Укажи корректную HTTP(S)-ссылку подписки.");

        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength is > 2_000_000)
            throw new InvalidOperationException("Подписка слишком большая (лимит 2 МБ).");
        var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (bytes.Length > 2_000_000) throw new InvalidOperationException("Подписка слишком большая (лимит 2 МБ).");
        var text = Encoding.UTF8.GetString(bytes).Trim();
        if (!text.Contains("://", StringComparison.Ordinal))
        {
            try
            {
                var compact = string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
                text = Encoding.UTF8.GetString(Convert.FromBase64String(compact.PadRight(compact.Length + (4 - compact.Length % 4) % 4, '=')));
            }
            catch (FormatException) { }
        }

        var candidates = Parse(text, sourceUrl);
        if (candidates.Count == 0) throw new InvalidOperationException("В подписке нет поддерживаемых профилей. Старые серверы сохранены.");

        foreach (var old in Profiles.Where(p => string.Equals(p.SubscriptionUrl, sourceUrl, StringComparison.Ordinal)).ToList())
            Profiles.Remove(old);
        foreach (var item in candidates)
        {
            var duplicate = Profiles.FirstOrDefault(p => string.Equals(p.Uri, item.Uri, StringComparison.Ordinal));
            if (duplicate is null) Profiles.Add(item);
        }
        Save();
        return candidates.Count;
    }

    public int Import(string text)
    {
        var candidates = Parse(text, null);
        var added = candidates.Where(c => !Profiles.Any(p => p.Uri == c.Uri)).ToList();
        foreach (var profile in added) Profiles.Add(profile);
        if (added.Count > 0) Save();
        return added.Count;
    }

    private static List<Profile> Parse(string text, string? subscriptionUrl)
    {
        var imported = new List<Profile>();
        foreach (var raw in text.Split(new[] { '\r', '\n', ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = raw.Trim();
            if (candidate.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    var payload = candidate["vmess://".Length..];
                    var json = payload.StartsWith('{') ? payload : DecodeBase64(payload);
                    using var doc = JsonDocument.Parse(json);
                    var root = doc.RootElement;
                    var host = root.TryGetProperty("add", out var add) ? add.GetString() ?? "" : "";
                    var name = root.TryGetProperty("ps", out var ps) ? ps.GetString() ?? host : host;
                    var port = root.TryGetProperty("port", out var p) && int.TryParse(p.ToString(), out var parsed) ? parsed : (int?)null;
                    var canonical = "vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(json));
                    if (!imported.Any(p => p.Uri == canonical))
                        imported.Add(new Profile { Name = name, Scheme = "vmess", Uri = canonical, Server = host, Port = port, SubscriptionUrl = subscriptionUrl });
                }
                catch (Exception ex) when (ex is FormatException or JsonException or InvalidOperationException) { }
                continue;
            }
            if (!System.Uri.TryCreate(candidate, UriKind.Absolute, out var uri) || !SupportedSchemes.Contains(uri.Scheme)) continue;
            if (imported.Any(p => p.Uri == candidate)) continue;
            var nameValue = System.Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));
            if (string.IsNullOrWhiteSpace(nameValue)) nameValue = uri.UserInfo.Split(':').FirstOrDefault() is { Length: > 0 } user ? user : uri.Host;
            imported.Add(new Profile {
                Name = nameValue,
                Scheme = uri.Scheme.Equals("hy2", StringComparison.OrdinalIgnoreCase) ? "hysteria2" : uri.Scheme,
                Uri = candidate, Server = uri.Host, Port = uri.IsDefaultPort ? null : uri.Port,
                SubscriptionUrl = subscriptionUrl
            });
        }
        return imported;
    }

    public void Remove(Profile profile) { if (Profiles.Remove(profile)) Save(); }
    private void Save() { Directory.CreateDirectory(DirectoryPath); var temp = FilePath + ".tmp"; File.WriteAllText(temp, System.Text.Json.JsonSerializer.Serialize(Profiles, new JsonSerializerOptions { WriteIndented = true })); File.Move(temp, FilePath, true); }
    private static string DecodeBase64(string value) { var s = value.Replace('-', '+').Replace('_', '/'); s = s.PadRight(s.Length + (4 - s.Length % 4) % 4, '='); return Encoding.UTF8.GetString(Convert.FromBase64String(s)); }
}
