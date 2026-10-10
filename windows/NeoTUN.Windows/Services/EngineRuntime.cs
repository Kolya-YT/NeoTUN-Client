using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text;
using System.Text.Json;

namespace NeoTUN.Windows.Services;

internal sealed class EngineRuntime : IDisposable
{
    private readonly object _sync = new();
    private Process? _singBox;
    private Process? _xray;
    private TrafficMonitor? _trafficMonitor;
    private long _totalUpload;
    private long _totalDownload;
    private readonly string _dataDirectory;
    private readonly string _runtimeDirectory;
    private readonly string _logPath;

    public event Action<string>? LogLine;
    public event Action<bool, string>? StateChanged;
    public event Action<long, long, long, long>? TrafficUpdated;
    public bool IsRunning
    {
        get { lock (_sync) return _singBox is { HasExited: false }; }
    }

    public EngineRuntime()
    {
        _dataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        _runtimeDirectory = Path.Combine(AppContext.BaseDirectory, "runtime");
        Directory.CreateDirectory(_dataDirectory);
        _logPath = Path.Combine(_dataDirectory, "windows-runtime.log");
    }

    public async Task StartAsync(string shareUri, CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Windows runtime доступен только в Windows.");
        if (!IsAdministrator())
            throw new InvalidOperationException("Для создания системного TUN нужны права администратора. Запусти NeoTUN от имени администратора.");
        if (IsRunning) return;
        if (_singBox is not null || _xray is not null) await StopAsync();

        var singBoxPath = Path.Combine(_runtimeDirectory, "sing-box.exe");
        var xrayPath = Path.Combine(_runtimeDirectory, "xray.exe");
        if (!File.Exists(singBoxPath)) throw new FileNotFoundException("Не найден sing-box.exe в папке runtime.", singBoxPath);
        var wintunPath = Path.Combine(_runtimeDirectory, "wintun.dll");
        if (!File.Exists(wintunPath))
            throw new FileNotFoundException("Не найден runtime/wintun.dll. Переустанови NeoTUN через актуальный установщик Windows.", wintunPath);
        if (new FileInfo(wintunPath).Length < 64 * 1024)
            throw new InvalidDataException($"runtime/wintun.dll выглядит неполным ({new FileInfo(wintunPath).Length} bytes). Переустанови приложение.");

        WriteLog($"Runtime paths: app={AppContext.BaseDirectory}; runtime={_runtimeDirectory}; wintun={wintunPath}; wintunBytes={new FileInfo(wintunPath).Length}");
        WriteLog($"Runtime files: sing-box={new FileInfo(singBoxPath).Length} bytes; xray={(File.Exists(xrayPath) ? new FileInfo(xrayPath).Length : 0)} bytes");
        var runtime = NativeCore.BuildRuntimeConfig(shareUri);
        var singBoxConfigPath = Path.Combine(_dataDirectory, "sing-box-runtime.json");
        var apiPort = GetAvailableLoopbackPort();
        var apiSecret = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
        var xrayStarted = false;
        var singBoxStarted = false;
        var singBoxRoot = JsonNode.Parse(runtime.SingBoxJson)?.AsObject()
            ?? throw new InvalidOperationException("Rust core вернул некорректный sing-box JSON.");
        var experimental = singBoxRoot["experimental"] as JsonObject;
        if (experimental is null)
        {
            experimental = new JsonObject();
            singBoxRoot["experimental"] = experimental;
        }
        experimental["clash_api"] = new JsonObject
        {
            ["external_controller"] = $"127.0.0.1:{apiPort}",
            ["secret"] = apiSecret
        };
        await File.WriteAllTextAsync(singBoxConfigPath,
            singBoxRoot.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }),
            new UTF8Encoding(false), cancellationToken);

        try
        {
            if (runtime.Engine == "xray")
            {
                if (runtime.XrayJson is null) throw new InvalidOperationException("Для этого профиля не сформирована конфигурация Xray.");
                WriteLog("Selected Xray + sing-box TUN bridge; validating Xray before starting TUN.");
                if (!File.Exists(xrayPath)) throw new FileNotFoundException("Не найден xray.exe в папке runtime.", xrayPath);
                var xrayConfigPath = Path.Combine(_dataDirectory, "xray-runtime.json");
                await File.WriteAllTextAsync(xrayConfigPath, runtime.XrayJson, new UTF8Encoding(false), cancellationToken);
                await ValidateAsync(xrayPath, cancellationToken, "run", "-test", "-config", xrayConfigPath);
                _xray = StartProcess(xrayPath, "run -config " + Quote(xrayConfigPath), "xray");
                xrayStarted = true;
                await WaitForSocks5Async(IPAddress.Loopback, 10808, TimeSpan.FromSeconds(8), cancellationToken);
                WriteLog("Xray SOCKS5 listener is accepting no-auth handshakes on 127.0.0.1:10808.");
            }

            await ValidateAsync(singBoxPath, cancellationToken, "check", "-c", singBoxConfigPath);
            _singBox = StartProcess(singBoxPath, "run -c " + Quote(singBoxConfigPath), "sing-box");
            singBoxStarted = true;
            await Task.Delay(900, cancellationToken);
            if (_singBox.HasExited)
            {
                var exit = _singBox.ExitCode;
                throw new InvalidOperationException($"sing-box завершился сразу после запуска (код {exit}). Проверь журнал NeoTUN.");
            }
        }
        catch
        {
            if (singBoxStarted) await StopSingBoxAsync();
            if (xrayStarted) await StopXrayAsync();
            throw;
        }
        _totalUpload = 0;
        _totalDownload = 0;
        _trafficMonitor = new TrafficMonitor(apiPort, apiSecret);
        _trafficMonitor.LogLine += WriteLog;
        _trafficMonitor.SampleReceived += (upload, download) =>
        {
            long totalUpload;
            long totalDownload;
            lock (_sync)
            {
                _totalUpload += upload;
                _totalDownload += download;
                totalUpload = _totalUpload;
                totalDownload = _totalDownload;
            }
            TrafficUpdated?.Invoke(upload, download, totalUpload, totalDownload);
        };
        _trafficMonitor.Start();
        StateChanged?.Invoke(true, "Движки запущены; проверяем стабильность TUN и доступность трафика…");
        WriteLog("Runtime processes started; engine=" + runtime.Engine + "; TUN startup is provisional until the process remains alive.");
    }

    public async Task StopAsync()
    {
        if (_trafficMonitor is not null)
        {
            await _trafficMonitor.DisposeAsync();
            _trafficMonitor = null;
        }
        await StopSingBoxAsync();
        await StopXrayAsync();
        StateChanged?.Invoke(false, "Отключено");
        WriteLog("Runtime stopped");
    }

    private async Task WaitForSocks5Async(
        IPAddress address,
        int port,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var deadline = DateTime.UtcNow + timeout;
        Exception? lastError = null;

        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_xray is null || _xray.HasExited)
            {
                var exitCode = _xray is null ? -1 : SafeExitCode(_xray);
                throw new InvalidOperationException($"Xray завершился до готовности SOCKS5 (код {exitCode}). Проверь журнал NeoTUN.");
            }

            using var attempt = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            attempt.CancelAfter(TimeSpan.FromMilliseconds(600));
            try
            {
                using var client = new TcpClient();
                await client.ConnectAsync(address, port, attempt.Token);
                await using var stream = client.GetStream();
                var greeting = new byte[] { 0x05, 0x01, 0x00 };
                await stream.WriteAsync(greeting, attempt.Token);

                var response = new byte[2];
                await stream.ReadExactlyAsync(response, attempt.Token);
                if (response[0] == 0x05 && response[1] == 0x00)
                    return;

                lastError = new InvalidDataException(
                    $"SOCKS5 returned unexpected greeting: {response[0]:X2} {response[1]:X2}.");
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is SocketException or IOException or OperationCanceledException)
            {
                lastError = ex;
            }

            await Task.Delay(150, cancellationToken);
        }

        throw new TimeoutException(
            $"Xray не открыл рабочий SOCKS5 на {address}:{port} за {timeout.TotalSeconds:0} с. Последняя ошибка: {lastError?.Message ?? "нет ответа"}");
    }

    private async Task ValidateAsync(string executable, CancellationToken cancellationToken, params string[] values)
    {
        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(executable)!
        };
        foreach (var arg in values) start.ArgumentList.Add(arg);
        using var process = new Process { StartInfo = start };
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) stdout.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) stderr.AppendLine(e.Data); };
        if (!process.Start()) throw new InvalidOperationException("Не удалось запустить проверку конфигурации.");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        await process.WaitForExitAsync(cancellationToken);
        if (process.ExitCode != 0)
        {
            var details = stderr.ToString().Trim();
            if (details.Length == 0) details = stdout.ToString().Trim();
            WriteLog("Config validation failed: " + details);
            throw new InvalidOperationException("Проверка конфигурации не пройдена: " + (details.Length > 0 ? details : $"код {process.ExitCode}"));
        }
        WriteLog("Config validation passed: " + Path.GetFileName(executable));
    }

    private Process StartProcess(string executable, string arguments, string name)
    {
        var start = new ProcessStartInfo(executable)
        {
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(executable)!
        };
        var runtimePath = Path.GetDirectoryName(executable)!;
        var existingPath = start.Environment.TryGetValue("PATH", out var pathValue) ? pathValue : Environment.GetEnvironmentVariable("PATH");
        start.Environment["PATH"] = string.IsNullOrWhiteSpace(existingPath)
            ? runtimePath
            : runtimePath + Path.PathSeparator + existingPath;
        var process = new Process { StartInfo = start, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) WriteLog(name + ": " + e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) WriteLog(name + ": " + e.Data); };
        process.Exited += (_, _) =>
        {
            var exitCode = SafeExitCode(process);
            WriteLog($"{name} exited with code {exitCode}");
            if (name == "sing-box") StateChanged?.Invoke(false, $"sing-box завершился с кодом {exitCode}. См. журнал.");
        };
        if (!process.Start()) throw new InvalidOperationException("Не удалось запустить " + name);
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        WriteLog(name + " process started");
        return process;
    }

    private async Task StopSingBoxAsync()
    {
        Process? current;
        lock (_sync) { current = _singBox; _singBox = null; }
        await StopProcessAsync(current, "sing-box");
    }

    private async Task StopXrayAsync()
    {
        Process? current;
        lock (_sync) { current = _xray; _xray = null; }
        await StopProcessAsync(current, "xray");
    }

    private async Task StopProcessAsync(Process? current, string name)
    {
        if (current is null) return;
        try
        {
            if (!current.HasExited)
            {
                current.Kill(entireProcessTree: true);
                await current.WaitForExitAsync();
            }
        }
        catch (InvalidOperationException) { }
        catch (Exception ex) { WriteLog(name + " stop warning: " + ex.Message); }
        finally { current.Dispose(); }
        WriteLog(name + " process stopped");
    }

    private void WriteLog(string line)
    {
        var entry = $"[{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz}] {line}";
        try { File.AppendAllText(_logPath, entry + Environment.NewLine, Encoding.UTF8); } catch { }
        LogLine?.Invoke(entry);
    }

    private static int GetAvailableLoopbackPort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static bool IsAdministrator()
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(identity)
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }

    private static int SafeExitCode(Process process)
    {
        try { return process.ExitCode; } catch { return -1; }
    }

    private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";

    public void Dispose()
    {
        try { StopAsync().GetAwaiter().GetResult(); } catch { }
    }
}
