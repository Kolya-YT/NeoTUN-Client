namespace NeoTUN.Windows.Models;

public sealed class ServerProfile
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "Новый сервер";
    public string Uri { get; set; } = "";
    public string Source { get; set; } = "local";
    public DateTimeOffset? UpdatedAt { get; set; }

    public string Protocol
    {
        get
        {
            var separator = Uri.IndexOf("://", StringComparison.Ordinal);
            return separator > 0 ? Uri[..separator].ToUpperInvariant() : "UNKNOWN";
        }
    }

    public string Endpoint
    {
        get
        {
            if (!System.Uri.TryCreate(Uri, UriKind.Absolute, out var parsed))
                return "Адрес не задан";
            return parsed.IsDefaultPort ? parsed.Host : $"{parsed.Host}:{parsed.Port}";
        }
    }
}
