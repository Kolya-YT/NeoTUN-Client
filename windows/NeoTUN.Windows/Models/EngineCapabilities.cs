namespace NeoTUN.Windows.Models;

public sealed record EngineCapabilities(
    string EngineId,
    string EngineName,
    string Protocol,
    bool SupportsTun,
    bool SupportsDnsSettings,
    bool SupportsRoutingProfiles,
    bool SupportsMtu,
    bool SupportsAppExclusions,
    IReadOnlyList<string> Notes)
{
    public static EngineCapabilities ForProfile(ServerProfile profile)
    {
        var protocol = profile.Protocol;
        var isXray = profile.Engine.Contains("xray", StringComparison.OrdinalIgnoreCase);
        var isSingBox = profile.Engine.Contains("sing", StringComparison.OrdinalIgnoreCase) ||
                        profile.Engine.Contains("box", StringComparison.OrdinalIgnoreCase);

        var engineName = isXray ? "Xray" : (isSingBox ? "sing-box" : profile.Engine);

        var notes = new List<string>
        {
            $"Протокол определяется из исходной ссылки: {protocol}.",
            "DNS, MTU и профиль маршрутизации задаются в настройках NeoTUN и применяются при следующем подключении."
        };

        if (isXray)
        {
            notes.Add("Для этого профиля используется Xray (ядро с поддержкой REALITY X25519MLKEM768 и XHTTP). Маршрутизация передаётся в sing-box TUN.");
        }
        else if (isSingBox)
        {
            notes.Add("Для этого профиля используется sing-box в качестве основного outbound-движка и TUN.");
        }
        else
        {
            notes.Add("Движок не распознан как sing-box или Xray. Перед подключением проверьте поддержку протокола.");
        }

        return new EngineCapabilities(
            profile.Engine,
            engineName,
            protocol,
            SupportsTun: isXray || isSingBox,
            SupportsDnsSettings: isXray || isSingBox,
            SupportsRoutingProfiles: isXray || isSingBox,
            SupportsMtu: isXray || isSingBox,
            SupportsAppExclusions: isXray || isSingBox,
            notes);
    }
}
