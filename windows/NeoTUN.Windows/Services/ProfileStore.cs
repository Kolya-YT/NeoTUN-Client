using System.IO;
using System.Text.Json;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public sealed class ProfileStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _filePath;

    public ProfileStore()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        Directory.CreateDirectory(directory);
        _filePath = Path.Combine(directory, "profiles.json");
    }

    public static string CanonicalKey(string uri)
    {
        var withoutFragment = uri.Trim().Split('#')[0];
        var schemeIndex = withoutFragment.IndexOf("://", StringComparison.Ordinal);
        var scheme = schemeIndex >= 0 ? withoutFragment[..schemeIndex].ToLowerInvariant() : "";
        var rest = schemeIndex >= 0 ? withoutFragment[(schemeIndex + 3)..] : withoutFragment;
        var queryIndex = rest.IndexOf('?');
        if (queryIndex < 0) return $"{scheme}://{rest}";

        var authority = rest[..queryIndex];
        var queryParts = rest[(queryIndex + 1)..]
            .Split('&', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .OrderBy(p => p, StringComparer.Ordinal)
            .ToList();

        return queryParts.Count == 0 ? $"{scheme}://{authority}" : $"{scheme}://{authority}?{string.Join("&", queryParts)}";
    }

    public static string DisplayNameFromUri(string uri)
    {
        var hashIndex = uri.IndexOf('#');
        if (hashIndex >= 0 && hashIndex < uri.Length - 1)
        {
            var fragment = uri[(hashIndex + 1)..].Trim();
            try { return Uri.UnescapeDataString(fragment); }
            catch { return fragment; }
        }

        if (Uri.TryCreate(uri, UriKind.Absolute, out var parsed))
        {
            if (!string.IsNullOrEmpty(parsed.Host)) return parsed.Host;
            return parsed.Scheme.ToUpperInvariant() + " профиль";
        }
        return "VLESS профиль";
    }

    public IReadOnlyList<ServerProfile> Load()
    {
        try
        {
            if (!File.Exists(_filePath)) return Array.Empty<ServerProfile>();
            var list = JsonSerializer.Deserialize<List<ServerProfile>>(File.ReadAllText(_filePath), JsonOptions)
                   ?? new List<ServerProfile>();
            return Deduplicate(list);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return Array.Empty<ServerProfile>();
        }
    }

    public void Save(IEnumerable<ServerProfile> profiles)
    {
        var list = Deduplicate(profiles.ToList());
        var temp = _filePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(list, JsonOptions));
        File.Move(temp, _filePath, overwrite: true);
    }

    public void Upsert(ServerProfile profile)
    {
        var list = Load().ToList();
        var key = CanonicalKey(profile.Uri);
        var index = list.FindIndex(p => p.Id == profile.Id ||
            (CanonicalKey(p.Uri) == key && p.SourceSubscriptionId == profile.SourceSubscriptionId));

        if (index >= 0)
        {
            profile.Id = list[index].Id;
            profile.SourceSubscriptionId ??= list[index].SourceSubscriptionId;
            list[index] = profile;
        }
        else
        {
            list.Add(profile);
        }
        Save(list);
    }

    public int ReplaceFromSubscription(string subscriptionId, IEnumerable<ServerProfile> incoming)
    {
        var incomingList = incoming.ToList();
        var uniqueIncoming = new Dictionary<string, ServerProfile>(StringComparer.Ordinal);
        foreach (var profile in incomingList)
        {
            var key = CanonicalKey(profile.Uri);
            if (!uniqueIncoming.ContainsKey(key))
            {
                profile.SourceSubscriptionId = subscriptionId;
                uniqueIncoming[key] = profile;
            }
        }

        var existing = Load().ToList();
        var incomingKeys = uniqueIncoming.Keys.ToHashSet(StringComparer.Ordinal);

        // Remove stale profiles belonging to this subscription and unowned duplicates of incoming URIs
        var result = existing.Where(p =>
            !(p.SourceSubscriptionId == subscriptionId && !incomingKeys.Contains(CanonicalKey(p.Uri))) &&
            !(string.IsNullOrEmpty(p.SourceSubscriptionId) && incomingKeys.Contains(CanonicalKey(p.Uri)))
        ).ToList();

        foreach (var (key, fresh) in uniqueIncoming)
        {
            var oldIndex = result.FindIndex(p => p.SourceSubscriptionId == subscriptionId && CanonicalKey(p.Uri) == key);
            if (oldIndex >= 0)
            {
                fresh.Id = result[oldIndex].Id;
                fresh.SourceSubscriptionId = subscriptionId;
                fresh.UpdatedAt = DateTimeOffset.UtcNow;
                result[oldIndex] = fresh;
            }
            else
            {
                fresh.SourceSubscriptionId = subscriptionId;
                fresh.UpdatedAt = DateTimeOffset.UtcNow;
                result.Add(fresh);
            }
        }

        Save(result);
        return uniqueIncoming.Count;
    }

    public void Delete(Guid id)
    {
        var remaining = Load().Where(p => p.Id != id).ToList();
        Save(remaining);
    }

    public void DeleteBySubscription(string subscriptionId)
    {
        var remaining = Load().Where(p => p.SourceSubscriptionId != subscriptionId).ToList();
        Save(remaining);
    }

    public void Rename(Guid id, string newName)
    {
        var list = Load().ToList();
        var item = list.FirstOrDefault(p => p.Id == id);
        if (item != null)
        {
            item.Name = string.IsNullOrWhiteSpace(newName) ? item.Name : newName.Trim();
            Save(list);
        }
    }

    private static List<ServerProfile> Deduplicate(List<ServerProfile> profiles)
    {
        var unique = new Dictionary<string, ServerProfile>(StringComparer.Ordinal);
        foreach (var profile in profiles)
        {
            var key = CanonicalKey(profile.Uri);
            if (!unique.TryGetValue(key, out var previous) ||
                (string.IsNullOrEmpty(previous.SourceSubscriptionId) && !string.IsNullOrEmpty(profile.SourceSubscriptionId)))
            {
                unique[key] = profile;
            }
        }
        return unique.Values.ToList();
    }
}
