using System.Runtime.InteropServices;
using System.Text.Json;

namespace NeoTUN.Windows.Services;

internal static class NativeCore
{
    [DllImport("neotun_core.dll", EntryPoint = "neotun_windows_runtime_json", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr RuntimeJson([MarshalAs(UnmanagedType.LPUTF8Str)] string uri);

    [DllImport("neotun_core.dll", EntryPoint = "neotun_free_string", CallingConvention = CallingConvention.Cdecl)]
    private static extern void FreeString(IntPtr value);

    public static RuntimeConfig BuildRuntimeConfig(string shareUri)
    {
        IntPtr ptr = IntPtr.Zero;
        try
        {
            ptr = RuntimeJson(shareUri);
            if (ptr == IntPtr.Zero) throw new InvalidOperationException("Rust core не вернул конфигурацию.");
            var json = Marshal.PtrToStringUTF8(ptr);
            if (string.IsNullOrWhiteSpace(json)) throw new InvalidOperationException("Rust core вернул пустую конфигурацию.");
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.TryGetProperty("error", out var error))
                throw new InvalidOperationException(error.GetString() ?? "Не удалось разобрать профиль.");
            if (!root.TryGetProperty("engine", out var engine) ||
                !root.TryGetProperty("sing_box_config", out var singBox))
                throw new InvalidOperationException("Rust core вернул неполную конфигурацию.");
            var xray = root.TryGetProperty("xray_config", out var xrayConfig) ? xrayConfig.GetRawText() : null;
            return new RuntimeConfig(engine.GetString() ?? "sing-box", singBox.GetRawText(), xray);
        }
        catch (JsonException ex)
        {
            throw new InvalidOperationException("Некорректный ответ native core: " + ex.Message, ex);
        }
        finally
        {
            if (ptr != IntPtr.Zero) FreeString(ptr);
        }
    }
}

internal sealed record RuntimeConfig(string Engine, string SingBoxJson, string? XrayJson);
