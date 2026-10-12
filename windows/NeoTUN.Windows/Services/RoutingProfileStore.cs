using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public sealed class RoutingProfileStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _dataDirectory;
    private readonly string _profilesFilePath;
    private readonly string _activeFilePath;
    private readonly string _runtimeSettingsFilePath;

    public RoutingProfileStore()
    {
        _dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        Directory.CreateDirectory(_dataDirectory);
        _profilesFilePath = Path.Combine(_dataDirectory, "routing-profiles.json");
        _activeFilePath = Path.Combine(_dataDirectory, "active-routing.json");
        _runtimeSettingsFilePath = Path.Combine(_dataDirectory, "routing-settings.json");

        EnsureDefaultProfile();
    }

    public IReadOnlyList<RoutingProfile> All()
    {
        try
        {
            if (!File.Exists(_profilesFilePath)) return Array.Empty<RoutingProfile>();
            return JsonSerializer.Deserialize<List<RoutingProfile>>(File.ReadAllText(_profilesFilePath), JsonOptions)
                   ?? new List<RoutingProfile>();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return Array.Empty<RoutingProfile>();
        }
    }

    public RoutingProfile Active()
    {
        var all = All();
        if (all.Count == 0)
        {
            var def = CreateDefault();
            Save(def);
            return def;
        }

        var activeId = GetActiveId();
        if (!string.IsNullOrEmpty(activeId))
        {
            var found = all.FirstOrDefault(p => p.Id == activeId);
            if (found != null) return found;
        }

        return all[0];
    }

    public void Select(string id)
    {
        File.WriteAllText(_activeFilePath, id);
        var active = All().FirstOrDefault(p => p.Id == id) ?? All().FirstOrDefault();
        if (active != null)
        {
            SyncToRuntimeSettings(active);
        }
    }

    public void Save(RoutingProfile profile)
    {
        var list = All().ToList();
        var index = list.FindIndex(p => p.Id == profile.Id);
        profile.UpdatedAt = DateTimeOffset.UtcNow;

        if (index >= 0)
            list[index] = profile;
        else
            list.Add(profile);

        Persist(list);

        if (GetActiveId() == profile.Id || list.Count == 1)
        {
            Select(profile.Id);
        }
    }

    public void Delete(string id)
    {
        var list = All().Where(p => p.Id != id).ToList();
        if (list.Count == 0)
        {
            list.Add(CreateDefault());
        }
        Persist(list);

        if (GetActiveId() == id)
        {
            Select(list[0].Id);
        }
    }

    public void SyncToRuntimeSettings(RoutingProfile profile)
    {
        try
        {
            var payload = new
            {
                profile.GlobalProxy,
                profile.Ipv6Enabled,
                profile.Mtu,
                profile.RouteOrder,
                profile.RemoteDns,
                profile.DomesticDns,
                profile.DomesticDnsDomains,
                profile.BlockSites,
                profile.BlockIp,
                profile.ProxySites,
                profile.ProxyIp,
                profile.DirectSites,
                profile.DirectIp,
                profile.ProcessExclusions
            };

            var json = JsonSerializer.Serialize(payload, JsonOptions);
            var temp = _runtimeSettingsFilePath + ".tmp";
            File.WriteAllText(temp, json);
            File.Move(temp, _runtimeSettingsFilePath, overwrite: true);
        }
        catch
        {
            // Logging or non-fatal
        }
    }

    public RoutingProfile? ImportFromDeepLink(string raw)
    {
        try
        {
            var decoded = DecodeRoutingPayload(raw);
            if (decoded == null) return null;

            var profile = new RoutingProfile
            {
                Name = decoded["Name"]?.GetValue<string>() ?? "Импортированный профиль",
                GlobalProxy = decoded["GlobalProxy"]?.GetValue<string>()?.Equals("false", StringComparison.OrdinalIgnoreCase) != true,
                RouteOrder = ParseOrder(decoded["RouteOrder"]?.GetValue<string>()),
                RemoteDns = decoded["RemoteDNS"]?.GetValue<string>() ?? "https://8.8.8.8/dns-query",
                DomesticDns = decoded["DomesticDNS"]?.GetValue<string>() ?? "https://77.88.8.8/dns-query",
                DomesticDnsDomains = ExtractList(decoded["DomesticDNSDomains"]),
                BlockSites = ExtractList(decoded["BlockSites"]),
                BlockIp = ExtractList(decoded["BlockIp"]),
                ProxySites = ExtractList(decoded["ProxySites"]),
                ProxyIp = ExtractList(decoded["ProxyIp"]),
                DirectSites = ExtractList(decoded["DirectSites"]),
                DirectIp = ExtractList(decoded["DirectIp"]),
                SourceUrl = raw.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? raw : null,
                UpdatedAt = DateTimeOffset.UtcNow
            };

            Save(profile);
            Select(profile.Id);
            return profile;
        }
        catch
        {
            return null;
        }
    }

    private static JsonObject? DecodeRoutingPayload(string raw)
    {
        var text = raw.Trim();
        if (text.Contains("://routing/", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("://autorouting/", StringComparison.OrdinalIgnoreCase))
        {
            var query = text.Split('?');
            if (query.Length > 1)
            {
                foreach (var param in query[1].Split('&'))
                {
                    var kv = param.Split('=', 2);
                    if (kv.Length == 2 && (kv[0] == "data" || kv[0] == "c"))
                    {
                        var data = Uri.UnescapeDataString(kv[1]);
                        return TryParseJsonOrBase64(data);
                    }
                }
            }
        }
        return TryParseJsonOrBase64(text);
    }

    private static JsonObject? TryParseJsonOrBase64(string text)
    {
        try
        {
            if (JsonNode.Parse(text) is JsonObject obj) return obj;
        }
        catch { }

        try
        {
            var bytes = Convert.FromBase64String(text.Trim());
            var json = Encoding.UTF8.GetString(bytes);
            if (JsonNode.Parse(json) is JsonObject obj) return obj;
        }
        catch { }

        return null;
    }

    private static int ParseOrder(string? order) => order?.ToLowerInvariant() switch
    {
        "block-direct-proxy" => 1,
        "proxy-block-direct" => 2,
        _ => 0
    };

    private static string ExtractList(JsonNode? node)
    {
        if (node is JsonArray arr)
        {
            var items = arr.Select(x => x?.GetValue<string>()).Where(x => !string.IsNullOrWhiteSpace(x));
            return string.Join(Environment.NewLine, items);
        }
        if (node is JsonValue val && val.TryGetValue<string>(out var str))
        {
            return str;
        }
        return "";
    }

    private string? GetActiveId()
    {
        try
        {
            return File.Exists(_activeFilePath) ? File.ReadAllText(_activeFilePath).Trim() : null;
        }
        catch { return null; }
    }

    private void Persist(IEnumerable<RoutingProfile> profiles)
    {
        var temp = _profilesFilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(profiles, JsonOptions));
        File.Move(temp, _profilesFilePath, overwrite: true);
    }

    private void EnsureDefaultProfile()
    {
        if (!File.Exists(_profilesFilePath) || All().Count == 0)
        {
            var def = CreateDefault();
            Save(def);
            SyncToRuntimeSettings(def);
        }
    }

    private static RoutingProfile CreateDefault() => new()
    {
        Name = "Основная маршрутизация",
        GlobalProxy = true,
        RouteOrder = 0,
        RemoteDns = "https://8.8.8.8/dns-query",
        DomesticDns = "https://77.88.8.8/dns-query",
        DomesticDnsDomains = "ru\nsu\nрф",
        BlockSites = "geosite:category-ads",
        BlockIp = "",
        ProxySites = "",
        ProxyIp = "",
        DirectSites = "geosite:category-ru",
        DirectIp = "geoip:direct",
        ProcessExclusions = "",
        Ipv6Enabled = false,
        Mtu = 1500
    };
}
