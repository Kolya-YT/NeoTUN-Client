using System;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace NeoTUN.Windows;

internal sealed class SharedUiPreviewWindow : Window
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly MainWindow _owner;
    private readonly WebView2 _webView = new();
    private readonly DispatcherTimer _stateTimer = new() { Interval = TimeSpan.FromSeconds(1) };

    public SharedUiPreviewWindow(MainWindow owner)
    {
        _owner = owner;
        Title = "NeoTUN";
        Icon = owner.Icon;
        Width = 1100;
        Height = 720;
        MinWidth = 720;
        MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = _webView;
        _stateTimer.Tick += (_, _) =>
        {
            if (_webView.CoreWebView2 is null) return;
            var message = JsonSerializer.Serialize(new
            {
                bridgeVersion = 1,
                type = "app.stateChanged",
                snapshot = _owner.BuildSharedUiSnapshot()
            }, JsonOptions);
            _webView.CoreWebView2.PostWebMessageAsString(message);
        };
        Closed += (_, _) => _stateTimer.Stop();
        Loaded += InitializeAsync;
    }

    private async void InitializeAsync(object sender, RoutedEventArgs e)
    {
        try
        {
            var uiDirectory = Path.Combine(AppContext.BaseDirectory, "shared-ui");
            if (!File.Exists(Path.Combine(uiDirectory, "index.html")))
            {
                MessageBox.Show(this,
                    "Файлы нового интерфейса не найдены. Установите актуальную сборку NeoTUN.",
                    "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
                Close();
                return;
            }

            await _webView.EnsureCoreWebView2Async();
            _webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            _webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            _webView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                "neotun.local", uiDirectory, CoreWebView2HostResourceAccessKind.DenyCors);
            _webView.CoreWebView2.WebMessageReceived += OnWebMessageReceived;
            _webView.CoreWebView2.NavigationCompleted += (_, _) => _stateTimer.Start();
            await _webView.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(BridgeScript);
            _webView.Source = new Uri("https://neotun.local/index.html");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Не удалось открыть новый интерфейс: " + ex.Message,
                "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Error);
            Close();
        }
    }

    private async void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!Uri.TryCreate(e.Source, UriKind.Absolute, out var source) ||
            source.Scheme != Uri.UriSchemeHttps ||
            !string.Equals(source.Host, "neotun.local", StringComparison.OrdinalIgnoreCase))
            return;

        string requestId = "";
        try
        {
            using var document = JsonDocument.Parse(e.TryGetWebMessageAsString());
            var request = document.RootElement;
            if (!request.TryGetProperty("bridgeVersion", out var version) || version.GetInt32() != 1 ||
                !request.TryGetProperty("requestId", out var id) ||
                !request.TryGetProperty("command", out var command))
                throw new InvalidDataException("Некорректный запрос интерфейса.");

            requestId = id.GetString() ?? "";
            var result = await _owner.HandleSharedUiCommand(command.Clone());
            var response = JsonSerializer.Serialize(new
            {
                bridgeVersion = 1,
                requestId,
                ok = true,
                result
            }, JsonOptions);
            _webView.CoreWebView2.PostWebMessageAsString(response);
        }
        catch (Exception ex)
        {
            var response = JsonSerializer.Serialize(new
            {
                bridgeVersion = 1,
                requestId,
                ok = false,
                error = new { code = "native_error", message = ex.Message, retryable = false }
            }, JsonOptions);
            _webView.CoreWebView2.PostWebMessageAsString(response);
        }
    }

    private const string BridgeScript = """
    (() => {
      const pending = new Map();
      const listeners = new Set();
      let nextId = 0;
      window.neoTunNativeBridge = {
        request(command) {
          return new Promise(resolve => {
            const requestId = String(++nextId);
            pending.set(requestId, resolve);
            window.chrome.webview.postMessage(JSON.stringify({
              bridgeVersion: 1, requestId, command
            }));
          });
        },
        subscribe(listener) {
          listeners.add(listener);
          return () => listeners.delete(listener);
        }
      };
      window.chrome.webview.addEventListener('message', event => {
        try {
          const data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
          if (data && data.requestId && pending.has(String(data.requestId))) {
            const resolve = pending.get(String(data.requestId));
            pending.delete(String(data.requestId));
            resolve(data);
            return;
          }
          for (const listener of listeners) listener(data);
        } catch (_) {}
      });
    })();
    """;
}
