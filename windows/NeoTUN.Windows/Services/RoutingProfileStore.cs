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
    private readonly string _enabledFilePath;

    public RoutingProfileStore()
    {
        _dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        Directory.CreateDirectory(_dataDirectory);
        _profilesFilePath = Path.Combine(_dataDirectory, "routing-profiles.json");
        _activeFilePath = Path.Combine(_dataDirectory, "active-routing.json");
        _runtimeSettingsFilePath = Path.Combine(_dataDirectory, "routing-settings.json");
        _enabledFilePath = Path.Combine(_dataDirectory, "routing-enabled.txt");

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

    public bool IsEnabled()
    {
        try
        {
            return !File.Exists(_enabledFilePath) ||
                !bool.TryParse(File.ReadAllText(_enabledFilePath).Trim(), out var enabled) || enabled;
        }
        catch { return true; }
    }

    public void SetEnabled(bool enabled)
    {
        try
        {
            var temp = _enabledFilePath + ".tmp";
            File.WriteAllText(temp, enabled ? "true" : "false");
            File.Move(temp, _enabledFilePath, overwrite: true);
        }
        catch { return; }

        SyncToRuntimeSettings(Active());
    }

    public void SyncToRuntimeSettings(RoutingProfile profile)
    {
        try
        {
            var payload = new
            {
                Enabled = IsEnabled(),
                profile.GlobalProxy,
                profile.Ipv6Enabled,
                profile.Mtu,
                profile.RouteOrder,
                profile.RouteOrderRaw,
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
                Name = ReadString(decoded["Name"]) ?? "Импортированный профиль",
                GlobalProxy = ReadBoolean(decoded["GlobalProxy"], fallback: true),
                RouteOrder = ParseOrder(decoded["RouteOrder"]),
                RouteOrderRaw = ParseOrderRaw(decoded["RouteOrder"]),
                RemoteDns = ReadString(decoded["RemoteDNS"]) ?? ReadString(decoded["RemoteDns"]) ?? "https://8.8.8.8/dns-query",
                DomesticDns = ReadString(decoded["DomesticDNS"]) ?? ReadString(decoded["DomesticDns"]) ?? "https://77.88.8.8/dns-query",
                DomesticDnsDomains = ExtractList(decoded["DomesticDNSDomains"] ?? decoded["DomesticDnsDomains"]),
                BlockSites = ExtractList(decoded["BlockSites"]),
                BlockIp = ExtractList(decoded["BlockIp"]),
                ProxySites = ExtractList(decoded["ProxySites"]),
                ProxyIp = ExtractList(decoded["ProxyIp"]),
                DirectSites = ExtractList(decoded["DirectSites"]),
                DirectIp = ExtractList(decoded["DirectIp"]),
                ProcessExclusions = ExtractList(decoded["ProcessExclusions"]),
                Ipv6Enabled = ReadBoolean(decoded["Ipv6Enabled"] ?? decoded["IPv6Enabled"], fallback: false),
                Mtu = ReadInt32(decoded["Mtu"], fallback: 1500),
                SourceUrl = raw.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? raw : null,
                UpdatedAt = DateTimeOffset.UtcNow
            };

            Save(profile);
            Select(profile.Id);
            SetEnabled(true);
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
        var autoIndex = text.IndexOf("://autorouting/", StringComparison.OrdinalIgnoreCase);
        var routingIndex = text.IndexOf("://routing/", StringComparison.OrdinalIgnoreCase);
        var linkIndex = autoIndex >= 0 ? autoIndex : routingIndex;

        if (linkIndex >= 0)
        {
            // Some Happ/INCY links carry data in the query; others put JSON/Base64
            // directly in the path. Support both forms like the Android importer.
            var queryIndex = text.IndexOf('?', linkIndex);
            if (queryIndex >= 0)
            {
                var queryEnd = text.IndexOf('#', queryIndex);
                var query = text.Substring(queryIndex + 1,
                    (queryEnd >= 0 ? queryEnd : text.Length) - queryIndex - 1);
                foreach (var param in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
                {
                    var kv = param.Split('=', 2);
                    if (kv.Length == 2 &&
                        (kv[0].Equals("data", StringComparison.OrdinalIgnoreCase) ||
                         kv[0].Equals("c", StringComparison.OrdinalIgnoreCase)))
                    {
                        var data = Uri.UnescapeDataString(kv[1]);
                        return TryParseJsonOrBase64(data);
                    }
                }
            }

            var markerLength = autoIndex >= 0 ? "://autorouting/".Length : "://routing/".Length;
            var payloadStart = linkIndex + markerLength;
            var payloadEnd = text.IndexOfAny(['?', '#'], payloadStart);
            var payload = (payloadEnd >= 0 ? text[payloadStart..payloadEnd] : text[payloadStart..]).Trim();

            if (payload.Equals("off", StringComparison.OrdinalIgnoreCase))
                return null;

            if (payload.StartsWith("onadd/", StringComparison.OrdinalIgnoreCase) ||
                payload.StartsWith("add/", StringComparison.OrdinalIgnoreCase))
                payload = payload[(payload.IndexOf('/') + 1)..];

            if (payload.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                payload.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                return null; // Remote routing feeds require the asynchronous importer.

            if (!string.IsNullOrWhiteSpace(payload))
                return TryParseJsonOrBase64(Uri.UnescapeDataString(payload));
        }

        return TryParseJsonOrBase64(text);
    }

    private static JsonObject? TryParseJsonOrBase64(string text)
    {
        var candidate = text.Trim().TrimStart('\uFEFF');
        if (candidate.StartsWith("{", StringComparison.Ordinal))
        {
            try
            {
                if (JsonNode.Parse(candidate) is JsonObject obj) return obj;
            }
            catch (JsonException) { }
        }

        // Happ/INCY exports may use URL-safe Base64 and omit padding.
        // Ignore whitespace, normalize the alphabet, and restore padding before decoding.
        var encoded = string.Concat(candidate.Where(c => !char.IsWhiteSpace(c)))
            .Replace('-', '+')
            .Replace('_', '/')
            .TrimEnd('=');
        if (encoded.Length == 0) return null;
        encoded = encoded.PadRight(encoded.Length + ((4 - encoded.Length % 4) % 4), '=');

        try
        {
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(encoded)).TrimStart('\uFEFF');
            if (!json.TrimStart().StartsWith("{", StringComparison.Ordinal)) return null;
            return JsonNode.Parse(json) as JsonObject;
        }
        catch (Exception ex) when (ex is FormatException or JsonException)
        {
            return null;
        }
    }

    private static string? ReadString(JsonNode? node)
    {
        if (node is JsonValue value && value.TryGetValue<string>(out var text))
            return text;
        return null;
    }

    private static bool ReadBoolean(JsonNode? node, bool fallback)
    {
        if (node is JsonValue value)
        {
            if (value.TryGetValue<bool>(out var boolean)) return boolean;
            if (value.TryGetValue<string>(out var text) && bool.TryParse(text, out boolean))
                return boolean;
        }
        return fallback;
    }

    private static int ReadInt32(JsonNode? node, int fallback)
    {
        if (node is JsonValue value)
        {
            if (value.TryGetValue<int>(out var number)) return number;
            if (value.TryGetValue<string>(out var text) && int.TryParse(text, out number))
                return number;
        }
        return fallback;
    }

    private static string? ParseOrderRaw(JsonNode? node)
    {
        IEnumerable<string?> values;
        if (node is JsonArray array)
            values = array.Select(ReadString);
        else if (node is JsonValue value && value.TryGetValue<string>(out var text))
            values = text.Split(new[] { ',', ';', '|', '>', '-', ' ', '\t', '\r', '\n' },
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        else
            return null; // Numeric legacy presets are handled by RouteOrder.

        var normalized = values
            .Select(value => value?.Trim().ToLowerInvariant())
            .Select(value => value switch
            {
                "block" or "reject" => "block",
                "proxy" => "proxy",
                "direct" => "direct",
                _ => null
            })
            .Where(value => value is not null)
            .Cast<string>()
            .Distinct(StringComparer.Ordinal)
            .ToList();

        if (normalized.Count == 0) return null;
        foreach (var fallback in new[] { "block", "proxy", "direct" })
            if (!normalized.Contains(fallback, StringComparer.Ordinal))
                normalized.Add(fallback);
        return string.Join(",", normalized);
    }

    private static int ParseOrder(JsonNode? node)
    {
        string? order = null;
        if (node is JsonArray array)
            order = string.Join("-", array.Select(item => ReadString(item)).Where(item => !string.IsNullOrWhiteSpace(item)));
        else
            order = ReadString(node);

        return order?.ToLowerInvariant().Replace("_", "-") switch
        {
            "block-direct-proxy" => 1,
            "proxy-block-direct" => 2,
            _ => 0
        };
    }

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
