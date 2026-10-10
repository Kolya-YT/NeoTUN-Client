using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace NeoTUN.Windows.Services;

internal sealed class TrafficMonitor : IAsyncDisposable
{
    private readonly int _port;
    private readonly string _secret;
    private readonly CancellationTokenSource _stop = new();
    private Task? _worker;

    public event Action<long, long>? SampleReceived;
    public event Action<string>? LogLine;

    public TrafficMonitor(int port, string secret)
    {
        _port = port;
        _secret = secret;
    }

    public void Start()
    {
        _worker ??= Task.Run(() => RunAsync(_stop.Token));
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        var endpoint = new Uri($"ws://127.0.0.1:{_port}/traffic");
        while (!cancellationToken.IsCancellationRequested)
        {
            using var socket = new ClientWebSocket();
            socket.Options.SetRequestHeader("Authorization", "Bearer " + _secret);
            try
            {
                await socket.ConnectAsync(endpoint, cancellationToken);
                LogLine?.Invoke("Traffic monitor connected to local sing-box API");
                var buffer = new byte[8192];
                while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
                {
                    using var message = new MemoryStream();
                    WebSocketReceiveResult result;
                    do
                    {
                        result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken);
                        if (result.MessageType == WebSocketMessageType.Close) break;
                        message.Write(buffer, 0, result.Count);
                        if (message.Length > 64 * 1024) throw new InvalidDataException("Traffic API message exceeded limit.");
                    } while (!result.EndOfMessage);

                    if (result.MessageType == WebSocketMessageType.Close) break;
                    if (result.MessageType != WebSocketMessageType.Text) continue;
                    using var json = JsonDocument.Parse(message.ToArray());
                    var root = json.RootElement;
                    var up = root.TryGetProperty("up", out var upValue) && upValue.TryGetInt64(out var upRate)
                        ? Math.Max(0, upRate) : 0;
                    var down = root.TryGetProperty("down", out var downValue) && downValue.TryGetInt64(out var downRate)
                        ? Math.Max(0, downRate) : 0;
                    SampleReceived?.Invoke(up, down);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
            catch (Exception ex)
            {
                LogLine?.Invoke("Traffic monitor reconnect: " + ex.Message);
            }

            try { await Task.Delay(1200, cancellationToken); }
            catch (OperationCanceledException) { break; }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        if (_worker is not null)
        {
            try { await _worker; } catch (OperationCanceledException) { }
        }
        _stop.Dispose();
    }
}
