using System.Collections.ObjectModel;
using System.IO;
using System.Text;
using System.Text.Json;

namespace NeoTUN.Windows;

public sealed class ProfileStore
{
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
    private static readonly string FilePath = Path.Combine(DirectoryPath, "profiles.json");
    private static readonly HashSet<string> SupportedSchemes =
        new(StringComparer.OrdinalIgnoreCase) { "vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss" };

    public ObservableCollection<Profile> Profiles { get; } = [];

    public ProfileStore()
    {
        try
        {
            if (!File.Exists(FilePath)) return;
            var loaded = JsonSerializer.Deserialize<List<Profile>>(File.ReadAllText(FilePath)) ?? [];
            foreach (var profile in loaded.Where(p => !string.IsNullOrWhiteSpace(p.Uri)))
                Profiles.Add(profile);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { }
    }

    public int Import(string text)
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
                    if (!Profiles.Any(p => p.Uri == canonical) && !imported.Any(p => p.Uri == canonical))
                        imported.Add(new Profile { Name = name, Scheme = "vmess", Uri = canonical, Server = host, Port = port });
                }
                catch (Exception ex) when (ex is FormatException or JsonException or InvalidOperationException) { }
                continue;
            }

            if (!System.Uri.TryCreate(candidate, UriKind.Absolute, out var uri) ||
                !SupportedSchemes.Contains(uri.Scheme)) continue;
            if (Profiles.Any(p => p.Uri == candidate) || imported.Any(p => p.Uri == candidate)) continue;

            var displayName = System.Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));
            if (string.IsNullOrWhiteSpace(displayName))
                displayName = uri.UserInfo.Split(':').FirstOrDefault() is { Length: > 0 } user ? user : uri.Host;
            var portValue = uri.IsDefaultPort ? (int?)null : uri.Port;
            imported.Add(new Profile
            {
                Name = displayName,
                Scheme = uri.Scheme.Equals("hy2", StringComparison.OrdinalIgnoreCase) ? "hysteria2" : uri.Scheme,
                Uri = candidate,
                Server = uri.Host,
                Port = portValue
            });
        }

        foreach (var profile in imported) Profiles.Add(profile);
        if (imported.Count > 0) Save();
        return imported.Count;
    }

    public void Remove(Profile profile)
    {
        if (Profiles.Remove(profile)) Save();
    }

    private void Save()
    {
        Directory.CreateDirectory(DirectoryPath);
        var temp = FilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(Profiles, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, FilePath, true);
    }

    private static string DecodeBase64(string value)
    {
        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized = normalized.PadRight(normalized.Length + (4 - normalized.Length % 4) % 4, '=');
        return Encoding.UTF8.GetString(Convert.FromBase64String(normalized));
    }
}
