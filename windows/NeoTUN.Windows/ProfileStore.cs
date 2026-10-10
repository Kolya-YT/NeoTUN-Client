using System.Collections.ObjectModel;
using System.IO;
using System.Text;
using System.Text.Json;
namespace NeoTUN.Windows;
public sealed class ProfileStore
{
 private static readonly string DirectoryPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
 private static readonly string FilePath = Path.Combine(DirectoryPath, "profiles.json");
 private static readonly HashSet<string> SupportedSchemes = new(StringComparer.OrdinalIgnoreCase) { "vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss" };
 public ObservableCollection<Profile> Profiles { get; } = [];
 public ProfileStore()
 {
  try { if (!File.Exists(FilePath)) return; var loaded = JsonSerializer.Deserialize<List<Profile>>(File.ReadAllText(FilePath)) ?? []; foreach (var p in loaded.Where(p => !string.IsNullOrWhiteSpace(p.Uri))) Profiles.Add(p); }
  catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { }
 }
 public int Import(string text)
 {
  var imported = new List<Profile>();
  foreach (var raw in text.Split(new[] { '\\r', '\\n', ' ', '\\t' }, StringSplitOptions.RemoveEmptyEntries))
  {
   var candidate = raw.Trim();
   if (candidate.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase)) candidate = NormalizeVmess(candidate);
   if (!System.Uri.TryCreate(candidate, UriKind.Absolute, out var uri) || !SupportedSchemes.Contains(uri.Scheme)) continue;
   if (Profiles.Any(p => p.Uri == candidate) || imported.Any(p => p.Uri == candidate)) continue;
   var name = System.Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));
   if (string.IsNullOrWhiteSpace(name)) name = uri.UserInfo.Split(':').FirstOrDefault() is { Length: > 0 } user ? user : uri.Host;
   var port = uri.IsDefaultPort ? (int?)null : uri.Port;
   if (uri.Scheme.Equals("vmess", StringComparison.OrdinalIgnoreCase))
   {
    try { using var doc = JsonDocument.Parse(DecodeBase64(uri.Host + uri.Path)); var root = doc.RootElement; name = root.TryGetProperty("ps", out var ps) ? ps.GetString() ?? name : name; var host = root.TryGetProperty("add", out var add) ? add.GetString() ?? "" : ""; port = root.TryGetProperty("port", out var p) && int.TryParse(p.ToString(), out var parsed) ? parsed : null; imported.Add(new Profile { Name = name, Scheme = "vmess", Uri = candidate, Server = host, Port = port }); continue; }
    catch (Exception ex) when (ex is FormatException or JsonException or InvalidOperationException) { continue; }
   }
   imported.Add(new Profile { Name = name, Scheme = uri.Scheme.Equals("hy2", StringComparison.OrdinalIgnoreCase) ? "hysteria2" : uri.Scheme, Uri = candidate, Server = uri.Host, Port = port });
  }
  foreach (var p in imported) Profiles.Add(p); if (imported.Count > 0) Save(); return imported.Count;
 }
 public void Remove(Profile profile) { if (Profiles.Remove(profile)) Save(); }
 private void Save() { Directory.CreateDirectory(DirectoryPath); var temp = FilePath + ".tmp"; File.WriteAllText(temp, JsonSerializer.Serialize(Profiles, new JsonSerializerOptions { WriteIndented = true })); File.Move(temp, FilePath, true); }
 private static string NormalizeVmess(string value) { var payload = value["vmess://".Length..].Trim(); if (payload.StartsWith('{')) return value; var decoded = DecodeBase64(payload); return "vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(decoded)); }
 private static string DecodeBase64(string value) { var s = value.Replace('-', '+').Replace('_', '/'); s = s.PadRight(s.Length + (4 - s.Length % 4) % 4, '='); return Encoding.UTF8.GetString(Convert.FromBase64String(s)); }
}
