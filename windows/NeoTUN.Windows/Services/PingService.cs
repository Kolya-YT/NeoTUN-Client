using System.Diagnostics;
using System.Net.Sockets;
using NeoTUN.Windows.Models;

namespace NeoTUN.Windows.Services;

public sealed class PingService
{
    public async Task<(long ElapsedMs, string Display)> PingProfileAsync(
        ServerProfile profile,
        int timeoutMs = 3500,
        CancellationToken cancellationToken = default)
    {
        var host = profile.Host;
        var port = profile.Port > 0 ? profile.Port : 443;

        if (string.IsNullOrWhiteSpace(host) || host == "Адрес не задан")
            return (-1, "Ошибка");

        profile.IsPingInProgress = true;
        profile.Ping = "…";

        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(timeoutMs);

            var sw = Stopwatch.StartNew();
            using var socket = new Socket(SocketType.Stream, ProtocolType.Tcp)
            {
                NoDelay = true
            };

            await socket.ConnectAsync(host, port, cts.Token);
            sw.Stop();

            var ms = Math.Max(1, sw.ElapsedMilliseconds);
            var display = $"{ms} мс";

            profile.PingMs = ms;
            profile.Ping = display;
            profile.IsPingInProgress = false;

            return (ms, display);
        }
        catch (OperationCanceledException)
        {
            profile.PingMs = -1;
            profile.Ping = "Таймаут";
            profile.IsPingInProgress = false;
            return (-1, "Таймаут");
        }
        catch (Exception ex)
        {
            profile.PingMs = -1;
            profile.Ping = "Ошибка";
            profile.IsPingInProgress = false;
            Debug.WriteLine($"Ping error to {host}:{port}: {ex.Message}"); // Log failure
            return (-1, "Ошибка");
        }
    }

    public async Task PingAllAsync(
        IEnumerable<ServerProfile> profiles,
        int timeoutMs = 3500,
        int maxConcurrency = 6,
        CancellationToken cancellationToken = default)
    {
        var list = profiles.ToList();
        using var throttler = new SemaphoreSlim(maxConcurrency);

        var tasks = list.Select(async profile =>
        {
            await throttler.WaitAsync(cancellationToken);
            try
            {
                await PingProfileAsync(profile, timeoutMs, cancellationToken);
            }
            finally
            {
                throttler.Release();
            }
        });

        await Task.WhenAll(tasks);
    }
}
