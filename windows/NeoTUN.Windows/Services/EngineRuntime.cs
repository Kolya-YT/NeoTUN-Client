using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Net.Http;
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
    private int _stopRequested;
    private readonly string _dataDirectory;
    private readonly string _runtimeDirectory;
    private readonly string _logPath;

    public event Action<string>? LogLine;
    public event Action<bool, string>? StateChanged;
    public event Action<long, long, long, long>? TrafficUpdated;
    public bool IsRunning
    {
        get
        {
            lock (_sync)
            {
                if (_singBox is not { HasExited: false }) return false;
                return _xray is null || _xray is { HasExited: false };
            }
        }
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
        Interlocked.Exchange(ref _stopRequested, 0);
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
        ApplyRoutingPreferences(singBoxRoot);
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
        await WaitForClashApiAsync(apiPort, apiSecret, TimeSpan.FromSeconds(6), cancellationToken);
        WriteLog("sing-box control API is responsive; TUN process startup confirmed (internet reachability is not yet proven).");
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
        Interlocked.Exchange(ref _stopRequested, 1);
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

    private void ApplyRoutingPreferences(JsonObject singBoxRoot)
    {
        var settingsPath = Path.Combine(_dataDirectory, "routing-settings.json");
        if (!File.Exists(settingsPath)) return;

        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(settingsPath));
            var settings = document.RootElement;
            bool ReadBool(string name, bool fallback) =>
                settings.TryGetProperty(name, out var value) &&
                (value.ValueKind == JsonValueKind.True || value.ValueKind == JsonValueKind.False)
                    ? value.GetBoolean() : fallback;
            int ReadInt(string name, int fallback) =>
                settings.TryGetProperty(name, out var value) && value.TryGetInt32(out var number)
                    ? number : fallback;
            string ReadString(string name, string fallback) =>
                settings.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
                    ? value.GetString() ?? fallback : fallback;
            string[] ReadLines(string name) => ReadString(name, "")
                .Replace("\r", "")
                .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
                .Where(line => !line.StartsWith("#", StringComparison.Ordinal))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray();

            var ipv6Enabled = ReadBool("Ipv6Enabled", false);
            var mtu = Math.Clamp(ReadInt("Mtu", 1500), 1280, 1500);
            if (singBoxRoot["inbounds"] is JsonArray inbounds)
            {
                foreach (var inbound in inbounds.OfType<JsonObject>())
                {
                    if (!string.Equals(inbound["type"]?.GetValue<string>(), "tun", StringComparison.OrdinalIgnoreCase))
                        continue;

                    inbound["mtu"] = mtu;
                    var oldAddresses = inbound["address"] as JsonArray;
                    var newAddresses = new JsonArray();
                    if (oldAddresses is not null)
                    {
                        foreach (var addressNode in oldAddresses)
                        {
                            var address = addressNode?.GetValue<string>();
                            if (!string.IsNullOrWhiteSpace(address) &&
                                (ipv6Enabled || !address.Contains(':', StringComparison.Ordinal)))
                                newAddresses.Add(address);
                        }
                    }
                    if (ipv6Enabled && !newAddresses.Any(a => (a?.GetValue<string>() ?? "").Contains(':', StringComparison.Ordinal)))
                        newAddresses.Add("fdfe:dcba:9876::1/126");
                    inbound["address"] = newAddresses;
                }
            }

            var route = singBoxRoot["route"] as JsonObject;
            if (route is null)
            {
                route = new JsonObject();
                singBoxRoot["route"] = route;
            }
            route["final"] = ReadBool("GlobalProxy", true) ? "proxy" : "direct";

            var previousRules = route["rules"] as JsonArray ?? new JsonArray();
            var priorityRules = new List<JsonNode>();
            var remainingRules = new List<JsonNode>();
            foreach (var rule in previousRules)
            {
                if (rule is not JsonObject obj) continue;
                if (string.Equals(obj["action"]?.GetValue<string>(), "hijack-dns", StringComparison.OrdinalIgnoreCase))
                    priorityRules.Add(rule.DeepClone());
                else
                    remainingRules.Add(rule.DeepClone());
            }

            JsonObject? MakeRule(string field, string[] values, string outbound)
            {
                if (values.Length == 0) return null;
                var rule = new JsonObject { ["outbound"] = outbound };
                rule[field] = new JsonArray(values.Select(v => (JsonNode?)JsonValue.Create(v)).ToArray());
                return rule;
            }

            var blockDomains = ReadLines("BlockSites")
                .Select(NormalizeDomainSuffix).Where(v => v is not null).Cast<string>().ToArray();
            var proxyDomains = ReadLines("ProxySites")
                .Select(NormalizeDomainSuffix).Where(v => v is not null).Cast<string>().ToArray();
            var directDomains = ReadLines("DirectSites")
                .Select(NormalizeDomainSuffix).Where(v => v is not null).Cast<string>().ToArray();
            var blockIps = ReadLines("BlockIp").Where(IsIpOrCidr).ToArray();
            var proxyIps = ReadLines("ProxyIp").Where(IsIpOrCidr).ToArray();
            var directIps = ReadLines("DirectIp").Where(IsIpOrCidr).ToArray();

            var groups = new Dictionary<string, (string[] Domains, string[] Ips)>
            {
                ["block"] = (blockDomains, blockIps),
                ["proxy"] = (proxyDomains, proxyIps),
                ["direct"] = (directDomains, directIps)
            };
            var order = ReadInt("RouteOrder", 0) switch
            {
                1 => new[] { "block", "direct", "proxy" },
                2 => new[] { "proxy", "block", "direct" },
                _ => new[] { "block", "proxy", "direct" }
            };
            var mergedRules = new JsonArray();
            foreach (var rule in priorityRules) mergedRules.Add(rule);
            foreach (var tag in order)
            {
                var group = groups[tag];
                var domains = MakeRule("domain_suffix", group.Domains, tag);
                var ips = MakeRule("ip_cidr", group.Ips, tag);
                if (domains is not null) mergedRules.Add(domains);
                if (ips is not null) mergedRules.Add(ips);
            }
            foreach (var rule in remainingRules) mergedRules.Add(rule);
            route["rules"] = mergedRules;

            ApplyDnsPreferences(singBoxRoot, ReadString("RemoteDns", ""), ReadString("DomesticDns", ""),
                ReadLines("DomesticDnsDomains"));

            WriteLog($"Applied saved Windows routing preferences: globalProxy={ReadBool("GlobalProxy", true)}, ipv6={ipv6Enabled}, mtu={mtu}, order={ReadInt("RouteOrder", 0)}, domainRules={blockDomains.Length + proxyDomains.Length + directDomains.Length}, ipRules={blockIps.Length + proxyIps.Length + directIps.Length}.");
        }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException or FormatException)
        {
            throw new InvalidOperationException("Не удалось применить настройки маршрутизации Windows: " + ex.Message, ex);
        }
    }

    private static string? NormalizeDomainSuffix(string value)
    {
        var domain = value.Trim().TrimStart('*').TrimStart('.');
        if (domain.Length == 0 || domain.Any(char.IsWhiteSpace) || domain.Contains('/') ||
            domain.Contains(':') || domain.StartsWith("geosite", StringComparison.OrdinalIgnoreCase))
            return null;
        return "." + domain;
    }

    private static bool IsIpOrCidr(string value)
    {
        var parts = value.Split('/', 2, StringSplitOptions.TrimEntries);
        if (!IPAddress.TryParse(parts[0], out var address)) return false;
        if (parts.Length == 1) return true;
        if (!int.TryParse(parts[1], out var prefix)) return false;
        return prefix >= 0 && prefix <= (address.AddressFamily == AddressFamily.InterNetwork ? 32 : 128);
    }

    private static void ApplyDnsPreferences(JsonObject root, string remote, string domestic, string[] domesticDomains)
    {
        var dns = root["dns"] as JsonObject;
        if (dns is null)
        {
            dns = new JsonObject();
            root["dns"] = dns;
        }

        var servers = new JsonArray();
        var existing = dns["servers"] as JsonArray;
        if (existing is not null)
        {
            foreach (var item in existing)
            {
                if (item is JsonObject obj && string.Equals(obj["tag"]?.GetValue<string>(), "system", StringComparison.Ordinal))
                    servers.Add(item.DeepClone());
            }
        }
        if (servers.Count == 0)
            servers.Add(new JsonObject { ["type"] = "local", ["tag"] = "system" });

        var remoteTag = AddDnsServer(servers, remote, "neotun-remote");
        var domesticTag = AddDnsServer(servers, domestic, "neotun-domestic");
        dns["servers"] = servers;

        if (remoteTag is not null)
            dns["final"] = remoteTag;
        else if (dns["final"] is null)
            dns["final"] = "system";

        if (domesticTag is not null && domesticDomains.Length > 0)
        {
            var rules = dns["rules"] as JsonArray ?? new JsonArray();
            var newRules = new JsonArray();
            newRules.Add(new JsonObject
            {
                ["domain_suffix"] = new JsonArray(domesticDomains
                    .Select(NormalizeDomainSuffix)
                    .Where(v => v is not null)
                    .Select(v => (JsonNode?)JsonValue.Create(v))
                    .ToArray()),
                ["action"] = "route",
                ["server"] = domesticTag
            });
            foreach (var rule in rules) newRules.Add(rule?.DeepClone());
            dns["rules"] = newRules;
        }
    }

    private static string? AddDnsServer(JsonArray servers, string endpoint, string tag)
    {
        endpoint = endpoint.Trim();
        if (endpoint.Length == 0) return null;

        if (endpoint.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) || string.IsNullOrWhiteSpace(uri.Host))
                throw new InvalidDataException("Некорректный HTTPS DNS endpoint: " + endpoint);
            var server = new JsonObject
            {
                ["type"] = "https",
                ["tag"] = tag,
                ["server"] = uri.Host,
                ["server_port"] = uri.IsDefaultPort ? 443 : uri.Port,
                ["path"] = string.IsNullOrWhiteSpace(uri.AbsolutePath) ? "/dns-query" : uri.AbsolutePath
            };
            if (!IPAddress.TryParse(uri.Host, out _))
                server["domain_resolver"] = "system";
            servers.Add(server);
            return tag;
        }

        if (IPAddress.TryParse(endpoint, out var ip))
        {
            servers.Add(new JsonObject
            {
                ["type"] = "udp",
                ["tag"] = tag,
                ["server"] = ip.ToString(),
                ["server_port"] = 53
            });
            return tag;
        }

        throw new InvalidDataException("DNS должен быть IP-адресом или HTTPS URL: " + endpoint);
    }

    private async Task WaitForClashApiAsync(
        int port,
        string secret,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromMilliseconds(900) };
        client.DefaultRequestHeaders.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
        var endpoint = new Uri($"http://127.0.0.1:{port}/version");
        var deadline = DateTime.UtcNow + timeout;
        Exception? lastError = null;

        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_singBox is null || _singBox.HasExited)
            {
                var exitCode = _singBox is null ? -1 : SafeExitCode(_singBox);
                throw new InvalidOperationException($"sing-box завершился до готовности API (код {exitCode}).");
            }

            try
            {
                using var response = await client.GetAsync(endpoint, cancellationToken);
                if (response.IsSuccessStatusCode)
                {
                    WriteLog("sing-box control API readiness check passed.");
                    return;
                }
                lastError = new InvalidOperationException($"Control API returned HTTP {(int)response.StatusCode}.");
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
            {
                lastError = ex;
            }

            await Task.Delay(150, cancellationToken);
        }

        throw new TimeoutException(
            $"sing-box control API не ответил за {timeout.TotalSeconds:0} с. Последняя ошибка: {lastError?.Message ?? "нет ответа"}");
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
            if (name != "sing-box" && name != "xray") return;

            // Kill() during a normal disconnect also raises Exited (usually with code -1).
            // Do not report an intentional stop as a runtime crash.
            if (Volatile.Read(ref _stopRequested) != 0) return;

            StateChanged?.Invoke(false, $"{name} завершился с кодом {exitCode}. Останавливаем оставшийся движок…");
            _ = StopAfterUnexpectedExitAsync(name, exitCode);
        };
        if (!process.Start()) throw new InvalidOperationException("Не удалось запустить " + name);
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        WriteLog(name + " process started");
        return process;
    }

    private async Task StopAfterUnexpectedExitAsync(string name, int exitCode)
    {
        // Prevent duplicate cleanup if both processes exit nearly simultaneously.
        if (Interlocked.Exchange(ref _stopRequested, 1) != 0) return;
        WriteLog($"Unexpected {name} exit ({exitCode}); stopping the remaining runtime processes.");
        try
        {
            if (_trafficMonitor is not null)
            {
                await _trafficMonitor.DisposeAsync();
                _trafficMonitor = null;
            }
            await StopSingBoxAsync();
            await StopXrayAsync();
        }
        catch (Exception ex)
        {
            WriteLog("Runtime cleanup after unexpected exit failed: " + ex.Message);
        }
        finally
        {
            StateChanged?.Invoke(false, $"Отключено: {name} завершился с кодом {exitCode}. Проверь журнал.");
            WriteLog("Runtime cleanup after unexpected exit completed.");
        }
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
