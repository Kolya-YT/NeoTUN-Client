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

    public IReadOnlyList<ServerProfile> Load()
    {
        try
        {
            if (!File.Exists(_filePath)) return Array.Empty<ServerProfile>();
            return JsonSerializer.Deserialize<List<ServerProfile>>(File.ReadAllText(_filePath), JsonOptions)
                   ?? new List<ServerProfile>();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // Preserve a malformed file for diagnostics instead of silently overwriting it.
            return Array.Empty<ServerProfile>();
        }
    }

    public void Save(IEnumerable<ServerProfile> profiles)
    {
        var temp = _filePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(profiles, JsonOptions));
        File.Move(temp, _filePath, overwrite: true);
    }
}
