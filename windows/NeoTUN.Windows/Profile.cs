namespace NeoTUN.Windows;
public sealed class Profile
{
 public string Id { get; set; } = Guid.NewGuid().ToString("N");
 public string Name { get; set; } = "Server";
 public string Scheme { get; set; } = "";
 public string Uri { get; set; } = "";
 public string Server { get; set; } = "";
 public int? Port { get; set; }
 public string? SubscriptionUrl { get; set; }
 public string DisplayProtocol => Scheme.ToUpperInvariant();
 public string DisplayEndpoint => Port is > 0 ? $"{Server}:{Port}" : Server;
}
