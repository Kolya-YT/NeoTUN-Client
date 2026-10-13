using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using NeoTUN.Windows.Models;
using NeoTUN.Windows.Services;
using NeoTUN.Windows.ViewModels;
using Application = System.Windows.Application;
using Button = System.Windows.Controls.Button;
using Clipboard = System.Windows.Clipboard;
using FontFamily = System.Windows.Media.FontFamily;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using MessageBox = System.Windows.MessageBox;
using Orientation = System.Windows.Controls.Orientation;
using TextBox = System.Windows.Controls.TextBox;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;

namespace NeoTUN.Windows;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();
    private readonly ProfileStore _profileStore = new();
    private readonly SubscriptionStore _subscriptionStore = new();
    private readonly RoutingProfileStore _routingProfileStore = new();
    private readonly PingService _pingService = new();
    private readonly EngineRuntime _runtime;

    private System.Windows.Forms.NotifyIcon? _notifyIcon;
    private readonly string _dataDirectory;
    private readonly string _appSettingsPath;
    private AppSettings _settings = new();

    private bool _isExplicitExit;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;

        _dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
        Directory.CreateDirectory(_dataDirectory);
        _appSettingsPath = Path.Combine(_dataDirectory, "app-settings.json");

        _runtime = new EngineRuntime();
        _runtime.LogLine += msg => Dispatcher.Invoke(() => AddLog(msg));
        _runtime.TrafficUpdated += (upload, download, totalUpload, totalDownload) =>
            Dispatcher.BeginInvoke(() => _vm.UpdateTraffic(download, upload, totalDownload, totalUpload));
        _runtime.StateChanged += (connected, message) => Dispatcher.BeginInvoke(() =>
        {
            // The click handler owns connect/disconnect transitions. This callback
            // handles asynchronous engine exits without racing an active operation.
            if (connected || _vm.IsConnecting) return;
            // StopAsync emits a generic final state after an unexpected-exit event;
            // don't let that follow-up overwrite the more useful crash reason.
            if (message == "Отключено" && !_vm.IsConnected) return;
            _vm.IsConnected = false;
            _vm.Status = message;
            _vm.Notice = message;
            UpdateHeroConnectionVisuals(false);
            UpdateTrayStatus(false);
        });

        LoadAppSettings();
        InitializeSystemTray();
        ApplyThemeAndScale();

        Loaded += MainWindow_Loaded;
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        AddLog("NeoTUN Windows Client initialized. Core: " + _dataDirectory);

        if (_settings.AutoUpdateSubscriptions)
        {
            await AutoRefreshSubscriptionsAsync();
        }

        if (_settings.MinimizeToTrayOnStart)
        {
            Hide();
        }
    }

    private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_isExplicitExit && _settings.MinimizeToTrayOnClose)
        {
            e.Cancel = true;
            Hide();
            if (_notifyIcon != null)
            {
                _notifyIcon.ShowBalloonTip(1500, "NeoTUN", "Приложение свёрнуто в системный трей.", System.Windows.Forms.ToolTipIcon.Info);
            }
        }
        else
        {
            CleanupSystemTray();
        }
    }

    #region Navigation

    private void HomeNav_Click(object sender, RoutedEventArgs e) => ShowPage("Главная");
    private void ServersNav_Click(object sender, RoutedEventArgs e) => ShowPage("Серверы");
    private void RoutingNav_Click(object sender, RoutedEventArgs e) => ShowPage("Маршрутизация");
    private void SettingsNav_Click(object sender, RoutedEventArgs e) => ShowPage("Настройки");
    private void LogsNav_Click(object sender, RoutedEventArgs e) => ShowPage("Журнал");

    private void ShowPage(string page)
    {
        _vm.ActivePage = page;

        HomePage.Visibility = page == "Главная" ? Visibility.Visible : Visibility.Collapsed;
        ServersPage.Visibility = page == "Серверы" ? Visibility.Visible : Visibility.Collapsed;
        RoutingPage.Visibility = page == "Маршрутизация" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "Настройки" ? Visibility.Visible : Visibility.Collapsed;
        LogsPage.Visibility = page == "Журнал" ? Visibility.Visible : Visibility.Collapsed;

        UpdateButtonNav(TabHome, page == "Главная");
        UpdateButtonNav(TabServers, page == "Серверы");
        UpdateButtonNav(TabRouting, page == "Маршрутизация");
        UpdateButtonNav(TabSettings, page == "Настройки");
        UpdateButtonNav(TabLogs, page == "Журнал");
    }

    private static void UpdateButtonNav(Button btn, bool isActive)
    {
        btn.Background = isActive ? (Brush)Application.Current.Resources["BrandSoftBrush"] : Brushes.Transparent;
        btn.BorderBrush = isActive ? (Brush)Application.Current.Resources["BorderAccentBrush"] : Brushes.Transparent;
    }

    #endregion

    #region Connection Lifecycle

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        await ConnectSelectedProfileAsync();
    }

    private async Task ConnectSelectedProfileAsync()
    {
        if (_vm.IsConnecting) return;

        if (_vm.IsConnected)
        {
            await DisconnectAsync();
            return;
        }

        var profile = _vm.SelectedProfile;
        if (profile == null || string.IsNullOrWhiteSpace(profile.Uri))
        {
            MessageBox.Show("Сначала выберите или добавьте профиль подключения.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
            ShowPage("Серверы");
            return;
        }

        _vm.IsConnecting = true;
        _vm.Status = "Запуск движков и конфигурация Wintun…";
        AddLog($"Connecting to profile: {profile.Name} ({profile.Protocol})");

        try
        {
            // Sync active routing profile before launching
            if (_vm.ActiveRoutingProfile != null)
            {
                _routingProfileStore.SyncToRuntimeSettings(_vm.ActiveRoutingProfile);
            }

            await _runtime.StartAsync(profile.Uri);

            _vm.IsConnected = true;
            _vm.IsConnecting = false;
            _vm.Status = $"Подключено к {profile.Name} ({profile.Endpoint})";
            _vm.Notice = "Соединение установлено. Трафик защищён через Wintun.";
            UpdateHeroConnectionVisuals(true);
            UpdateTrayStatus(true, profile.Name);

        }
        catch (Exception ex)
        {
            _vm.IsConnected = false;
            _vm.IsConnecting = false;
            _vm.Status = "Ошибка подключения: " + ex.Message;
            _vm.Notice = "Не удалось установить соединение. Проверьте параметры профиля или журнал.";
            UpdateHeroConnectionVisuals(false);
            UpdateTrayStatus(false);
            AddLog("Connection error: " + ex.Message);
            MessageBox.Show("Ошибка подключения:\n" + ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async Task DisconnectAsync()
    {
        _vm.IsConnecting = true;
        _vm.Status = "Остановка туннеля…";
        AddLog("Disconnecting tunnel...");

        try
        {
            await _runtime.StopAsync();
            _vm.IsConnected = false;
            _vm.IsConnecting = false;
            _vm.Status = "Отключено";
            _vm.Notice = "Соединение разорвано.";
            _vm.ResetTraffic();
            UpdateHeroConnectionVisuals(false);
            UpdateTrayStatus(false);
            AddLog("Tunnel stopped.");
        }
        catch (Exception ex)
        {
            _vm.IsConnected = false;
            _vm.IsConnecting = false;
            _vm.Status = "Ошибка при остановке: " + ex.Message;
            UpdateHeroConnectionVisuals(false);
            UpdateTrayStatus(false);
            AddLog("Stop error: " + ex.Message);
        }
    }

    private async void QuickReconnect_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.IsConnected)
        {
            await DisconnectAsync();
            await Task.Delay(500);
        }
        Connect_Click(sender, e);
    }

    private void UpdateHeroConnectionVisuals(bool isRunning)
    {
        var successBrush = (Brush)Application.Current.Resources["SuccessBrush"];
        var successSurface = (Brush)Application.Current.Resources["SuccessSurfaceBrush"];
        var borderAccent = (Brush)Application.Current.Resources["BorderAccentBrush"];
        var textMuted = (Brush)Application.Current.Resources["TextMutedBrush"];

        HeroCard.BorderBrush = isRunning ? successBrush : borderAccent;
        HeroCard.Background = isRunning
            ? (Brush)Application.Current.Resources["HeroConnectedGradient"]
            : (Brush)Application.Current.Resources["HeroIdleGradient"];

        HeroStatusDot.Fill = isRunning ? successBrush : textMuted;
        HeaderStatusDot.Fill = isRunning ? successBrush : textMuted;
        HeroBadgeText.Foreground = isRunning ? successBrush : (Brush)Application.Current.Resources["TextSecondaryBrush"];
    }

    #endregion

    #region Servers & Subscriptions Management

    private async void SelectProfile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ServerProfile profile }) return;
        if (_vm.SelectedProfile?.Id == profile.Id) return;

        // Do not overlap a second start with an in-flight startup. The user can
        // retry the selection once the current attempt has reached a stable state.
        if (_vm.IsConnecting)
        {
            _vm.Notice = "Дождитесь завершения подключения перед переключением сервера.";
            return;
        }

        var reconnect = _vm.IsConnected;
        if (reconnect)
        {
            _vm.Notice = $"Переключение на {profile.Name}: останавливаем текущее соединение…";
            await DisconnectAsync();
            if (_vm.IsConnecting || _vm.IsConnected || _runtime.IsRunning)
            {
                _vm.Notice = "Не удалось подтвердить остановку предыдущего соединения. Новый сервер не запущен.";
                return;
            }
        }

        _vm.SelectedProfile = profile;
        _settings.SelectedProfileUri = profile.Uri;
        SaveAppSettings();
        _vm.Notice = reconnect
            ? $"Переподключаемся к {profile.Name}…"
            : $"Выбран сервер: {profile.Name}";

        if (reconnect)
            await ConnectSelectedProfileAsync();
    }

    private async void PingProfile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ServerProfile profile })
        {
            _vm.Notice = $"Проверка задержки для {profile.Name}…";
            var (ms, display) = await _pingService.PingProfileAsync(profile, _settings.PingTimeoutMs);
            _vm.Notice = ms > 0 ? $"Задержка {profile.Name}: {display}" : $"Сервер {profile.Name} недоступен ({display})";
        }
    }

    private async void QuickPing_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedProfile != null)
        {
            _vm.Notice = $"Проверка задержки для {_vm.SelectedProfile.Name}…";
            var (ms, display) = await _pingService.PingProfileAsync(_vm.SelectedProfile, _settings.PingTimeoutMs);
            _vm.Notice = ms > 0 ? $"Задержка: {display}" : $"Не удалось связаться с сервером ({display})";
        }
        else
        {
            MessageBox.Show("Выберите сервер для проверки задержки.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private async void PingAll_Click(object sender, RoutedEventArgs e)
    {
        _vm.Notice = "Выполняется проверка задержки для всех отображаемых серверов…";
        await _pingService.PingAllAsync(_vm.FilteredProfiles, _settings.PingTimeoutMs);
        _vm.Notice = "Проверка задержки завершена.";
    }

    private async void RefreshSubscriptions_Click(object sender, RoutedEventArgs e)
    {
        _vm.Notice = "Обновление всех подписок…";
        var total = 0;
        foreach (var sub in _vm.Subscriptions.ToList())
        {
            try
            {
                sub.IsUpdating = true;
                var count = await _subscriptionStore.RefreshAsync(sub, _profileStore, _routingProfileStore);
                total += count;
            }
            catch (Exception ex)
            {
                AddLog($"Ошибка обновления {sub.Name}: {ex.Message}");
            }
            finally
            {
                sub.IsUpdating = false;
            }
        }
        _vm.ReloadAll();
        _vm.Notice = $"Подписки обновлены. Загружено серверов: {total}.";
    }

    private async void RefreshSingleSubscription_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Subscription sub })
        {
            try
            {
                sub.IsUpdating = true;
                _vm.Notice = $"Обновление подписки «{sub.Name}»…";
                var count = await _subscriptionStore.RefreshAsync(sub, _profileStore, _routingProfileStore);
                _vm.ReloadAll();
                _vm.Notice = $"Подписка «{sub.Name}» обновлена ({count} серверов).";
            }
            catch (Exception ex)
            {
                _vm.Notice = $"Ошибка обновления: {ex.Message}";
                MessageBox.Show("Не удалось обновить подписку:\n" + ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            finally
            {
                sub.IsUpdating = false;
            }
        }
    }

    private async Task AutoRefreshSubscriptionsAsync()
    {
        try
        {
            foreach (var sub in _subscriptionStore.All())
            {
                var hoursSince = sub.LastUpdated.HasValue ? (DateTimeOffset.UtcNow - sub.LastUpdated.Value).TotalHours : 999;
                if (hoursSince >= _settings.SubscriptionIntervalHours)
                {
                    await _subscriptionStore.RefreshAsync(sub, _profileStore, _routingProfileStore);
                }
            }
            _vm.ReloadAll();
        }
        catch (Exception ex)
        {
            AddLog("Auto refresh error: " + ex.Message);
        }
    }

    private void EditSubscription_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Subscription sub })
        {
            ShowEditSubscriptionModal(sub);
        }
    }

    private void DeleteSubscription_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: Subscription sub })
        {
            if (MessageBox.Show($"Удалить подписку «{sub.Name}» и все связанные с ней серверы?",
                    "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
            {
                _subscriptionStore.Delete(sub.Id, _profileStore);
                _vm.ReloadAll();
                _vm.Notice = $"Подписка «{sub.Name}» удалена.";
            }
        }
    }

    private void DeleteProfile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ServerProfile profile })
        {
            if (MessageBox.Show($"Удалить сервер «{profile.Name}»?", "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
            {
                _profileStore.Delete(profile.Id);
                _vm.ReloadAll();
                _vm.Notice = "Профиль удалён.";
            }
        }
    }

    #endregion

    #region Modals (Profile Editor, Add Dialog, Config JSON Inspector, Capabilities)

    private void AddServerDialog_Click(object sender, RoutedEventArgs e)
    {
        var panel = new StackPanel { Margin = new Thickness(10) };
        panel.Children.Add(new TextBlock
        {
            Text = "Добавление серверов и подписок",
            FontSize = 18,
            FontWeight = FontWeights.Bold,
            Margin = new Thickness(0, 0, 0, 16)
        });

        var input = new TextBox
        {
            Height = 120,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            ToolTip = "Вставьте ссылку на подписку (http/https), share-ссылку (vless://, hysteria2://, trojan://...) или Base64-список"
        };
        panel.Children.Add(input);

        var hint = new TextBlock
        {
            Text = "Поддерживаются: HTTP(S) подписки, vless://, trojan://, hysteria2://, vmess://, tuic://, ss://, а также JSON конфигурации.",
            FontSize = 11,
            Foreground = (Brush)Application.Current.Resources["TextMutedBrush"],
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 8, 0, 16)
        };
        panel.Children.Add(hint);

        var btnBar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var btnPaste = new Button { Content = "📋 Вставить из буфера", Margin = new Thickness(0, 0, 8, 0) };
        btnPaste.Click += (s, ev) => { input.Text = Clipboard.GetText(); };

        var btnImport = new Button { Content = "Импортировать", Style = (Style)Application.Current.Resources["BrandButton"], Margin = new Thickness(0, 0, 8, 0) };
        btnImport.Click += async (s, ev) =>
        {
            var text = input.Text.Trim();
            if (string.IsNullOrWhiteSpace(text)) return;

            HideModal();
            _vm.Notice = "Выполняется импорт…";

            try
            {
                if (text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                    text.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                {
                    var sub = new Subscription { Name = "Подписка", Url = text };
                    _subscriptionStore.Save(sub);
                    var count = await _subscriptionStore.RefreshAsync(sub, _profileStore, _routingProfileStore);
                    _vm.ReloadAll();
                    _vm.Notice = $"Подписка добавлена ({count} серверов).";
                }
                else
                {
                    var profiles = SubscriptionImporter.Parse(text);
                    if (profiles.Count == 0) throw new InvalidDataException("Не найдено поддерживаемых ссылок.");
                    foreach (var p in profiles)
                    {
                        p.Engine = NativeCore.DetectEngine(p.Uri);
                        _profileStore.Upsert(p);
                    }
                    _vm.ReloadAll();
                    _vm.Notice = $"Импортировано серверов: {profiles.Count}.";
                }
            }
            catch (Exception ex)
            {
                _vm.Notice = "Ошибка импорта: " + ex.Message;
                MessageBox.Show("Ошибка импорта:\n" + ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        };

        var btnCancel = new Button { Content = "Отмена" };
        btnCancel.Click += (s, ev) => HideModal();

        btnBar.Children.Add(btnPaste);
        btnBar.Children.Add(btnImport);
        btnBar.Children.Add(btnCancel);
        panel.Children.Add(btnBar);

        ShowModal(panel);
    }

    private void EditProfile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ServerProfile profile })
        {
            ShowEditProfileModal(profile);
        }
    }

    private void ShowEditProfileModal(ServerProfile profile)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 520 };
        var panel = new StackPanel { Margin = new Thickness(10) };

        panel.Children.Add(new TextBlock
        {
            Text = "Редактирование параметров сервера",
            FontSize = 18,
            FontWeight = FontWeights.Bold,
            Margin = new Thickness(0, 0, 0, 14)
        });

        TextBox AddField(string label, string value)
        {
            panel.Children.Add(new TextBlock { Text = label, FontSize = 11, Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"], Margin = new Thickness(0, 4, 0, 2) });
            var tb = new TextBox { Text = value, Margin = new Thickness(0, 0, 0, 8) };
            panel.Children.Add(tb);
            return tb;
        }

        var tbName = AddField("Название сервера", profile.Name);
        var tbHost = AddField("Адрес сервера (Host)", profile.Host);
        var tbPort = AddField("Порт", profile.Port.ToString());
        var tbEngine = AddField("Движок (sing-box / xray)", profile.Engine);
        var tbUri = AddField("Исходная ссылка конфигурации", profile.Uri);

        var btnBar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
        var btnSave = new Button { Content = "Сохранить", Style = (Style)Application.Current.Resources["BrandButton"], Margin = new Thickness(0, 0, 8, 0) };
        btnSave.Click += (s, ev) =>
        {
            profile.Name = tbName.Text.Trim();
            profile.Engine = tbEngine.Text.Trim().ToLowerInvariant().Contains("xray") ? "xray" : "sing-box";

            var newUri = tbUri.Text.Trim();
            if (!string.IsNullOrWhiteSpace(newUri) && newUri != profile.Uri)
            {
                profile.Uri = newUri;
                profile.Engine = NativeCore.DetectEngine(newUri);
            }

            _profileStore.Upsert(profile);
            _vm.ReloadAll();
            HideModal();
            _vm.Notice = $"Сервер «{profile.Name}» сохранён.";
        };

        var btnCancel = new Button { Content = "Отмена" };
        btnCancel.Click += (s, ev) => HideModal();

        btnBar.Children.Add(btnSave);
        btnBar.Children.Add(btnCancel);
        panel.Children.Add(btnBar);

        scroll.Content = panel;
        ShowModal(scroll);
    }

    private void ShowEditSubscriptionModal(Subscription sub)
    {
        var panel = new StackPanel { Margin = new Thickness(10) };
        panel.Children.Add(new TextBlock { Text = "Редактирование подписки", FontSize = 18, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 14) });

        panel.Children.Add(new TextBlock { Text = "Название подписки:", FontSize = 12, Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"], Margin = new Thickness(0, 0, 0, 4) });
        var tbName = new TextBox { Text = sub.Name, Margin = new Thickness(0, 0, 0, 12) };
        panel.Children.Add(tbName);

        panel.Children.Add(new TextBlock { Text = "URL подписки (HTTP / HTTPS):", FontSize = 12, Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"], Margin = new Thickness(0, 0, 0, 4) });
        var tbUrl = new TextBox { Text = sub.Url, Margin = new Thickness(0, 0, 0, 16) };
        panel.Children.Add(tbUrl);

        var btnBar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var btnSave = new Button { Content = "Сохранить", Style = (Style)Application.Current.Resources["BrandButton"], Margin = new Thickness(0, 0, 8, 0) };
        btnSave.Click += (s, ev) =>
        {
            sub.Name = tbName.Text.Trim();
            sub.Url = tbUrl.Text.Trim();
            _subscriptionStore.Save(sub);
            _vm.ReloadAll();
            HideModal();
            _vm.Notice = "Параметры подписки сохранены.";
        };

        var btnCancel = new Button { Content = "Отмена" };
        btnCancel.Click += (s, ev) => HideModal();

        btnBar.Children.Add(btnSave);
        btnBar.Children.Add(btnCancel);
        panel.Children.Add(btnBar);

        ShowModal(panel);
    }

    private void ShowProfileConfig_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ServerProfile profile })
        {
            ShowConfigModal(profile);
        }
    }

    private void InspectConfig_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedProfile != null)
        {
            ShowConfigModal(_vm.SelectedProfile);
        }
        else
        {
            MessageBox.Show("Сначала выберите профиль.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void ShowConfigModal(ServerProfile profile)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 520 };
        var panel = new StackPanel { Margin = new Thickness(10) };

        panel.Children.Add(new TextBlock
        {
            Text = $"Инспектор конфигурации · {profile.Name}",
            FontSize = 18,
            FontWeight = FontWeights.Bold,
            Margin = new Thickness(0, 0, 0, 10)
        });

        var caps = EngineCapabilities.ForProfile(profile);
        var capsPanel = new Border
        {
            Background = (Brush)Application.Current.Resources["RaisedSurfaceBrush"],
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(12),
            Margin = new Thickness(0, 0, 0, 12)
        };
        var capsStack = new StackPanel();
        capsStack.Children.Add(new TextBlock { Text = $"Движок: {caps.EngineName}  •  Протокол: {caps.Protocol}", FontWeight = FontWeights.SemiBold });
        foreach (var note in caps.Notes)
        {
            capsStack.Children.Add(new TextBlock { Text = "• " + note, FontSize = 11, Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"], Margin = new Thickness(0, 2, 0, 0) });
        }
        capsPanel.Child = capsStack;
        panel.Children.Add(capsPanel);

        panel.Children.Add(new TextBlock { Text = "Синтезированный JSON (sing-box / xray):", FontSize = 12, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 4) });

        string configPreview;
        try
        {
            var runtime = NativeCore.BuildRuntimeConfig(profile.Uri);
            configPreview = runtime.SingBoxJson;
            if (!string.IsNullOrEmpty(runtime.XrayJson))
            {
                configPreview = "// === SING-BOX TUN BRIDGE ===\n" + configPreview + "\n\n// === XRAY OUTBOUND CONFIG ===\n" + runtime.XrayJson;
            }
        }
        catch (Exception ex)
        {
            configPreview = "Ошибка разбора профиля native core: " + ex.Message;
        }

        var tbJson = new TextBox
        {
            Text = configPreview,
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            FontFamily = new FontFamily("Consolas, monospace"),
            FontSize = 11,
            Height = 220,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto
        };
        panel.Children.Add(tbJson);

        var btnBar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
        var btnCopyJson = new Button { Content = "Копировать JSON", Style = (Style)Application.Current.Resources["BrandButton"], Margin = new Thickness(0, 0, 8, 0) };
        btnCopyJson.Click += (s, ev) =>
        {
            Clipboard.SetText(configPreview);
            MessageBox.Show("Конфигурация скопирована в буфер обмена.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        };

        var btnCopyUri = new Button { Content = "Копировать ссылку профиля", Margin = new Thickness(0, 0, 8, 0) };
        btnCopyUri.Click += (s, ev) =>
        {
            Clipboard.SetText(profile.Uri);
            MessageBox.Show("Ссылка профиля скопирована в буфер обмена.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        };

        var btnClose = new Button { Content = "Закрыть" };
        btnClose.Click += (s, ev) => HideModal();

        btnBar.Children.Add(btnCopyJson);
        btnBar.Children.Add(btnCopyUri);
        btnBar.Children.Add(btnClose);
        panel.Children.Add(btnBar);

        scroll.Content = panel;
        ShowModal(scroll);
    }

    private void ShowModal(UIElement content)
    {
        ModalContentRoot.Children.Clear();
        ModalContentRoot.Children.Add(content);
        ModalOverlay.Visibility = Visibility.Visible;
    }

    private void HideModal()
    {
        ModalOverlay.Visibility = Visibility.Collapsed;
        ModalContentRoot.Children.Clear();
    }

    #endregion

    #region Routing

    private void RoutingProfileSelector_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoutingProfileSelector.SelectedItem is RoutingProfile rp)
        {
            _routingProfileStore.Select(rp.Id);
            _settings.ActiveRoutingProfileId = rp.Id;
            SaveAppSettings();
        }
    }

    private void NewRoutingProfile_Click(object sender, RoutedEventArgs e)
    {
        var newProfile = new RoutingProfile
        {
            Name = "Новый профиль маршрутизации",
            GlobalProxy = true,
            RouteOrder = 0
        };
        _routingProfileStore.Save(newProfile);
        _vm.ReloadAll();
        _vm.ActiveRoutingProfile = newProfile;
        _vm.Notice = "Создан новый профиль маршрутизации.";
    }

    private void ImportRoutingProfile_Click(object sender, RoutedEventArgs e)
    {
        var panel = new StackPanel { Margin = new Thickness(10) };
        panel.Children.Add(new TextBlock { Text = "Импорт профиля маршрутизации", FontSize = 18, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 14) });

        var input = new TextBox { Height = 100, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, ToolTip = "Вставьте ссылку happ://routing/..., Base64 или JSON" };
        panel.Children.Add(input);

        var btnBar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        var btnImport = new Button { Content = "Импортировать", Style = (Style)Application.Current.Resources["BrandButton"], Margin = new Thickness(0, 0, 8, 0) };
        btnImport.Click += (s, ev) =>
        {
            var text = input.Text.Trim();
            if (string.IsNullOrWhiteSpace(text)) return;

            var imported = _routingProfileStore.ImportFromDeepLink(text);
            if (imported != null)
            {
                _vm.ReloadAll();
                _vm.ActiveRoutingProfile = imported;
                HideModal();
                _vm.Notice = $"Импортирован профиль маршрутизации «{imported.Name}».";
            }
            else
            {
                MessageBox.Show("Не удалось распознать формат маршрутов (требуется JSON, Base64 или deeplink).", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        };

        var btnCancel = new Button { Content = "Отмена" };
        btnCancel.Click += (s, ev) => HideModal();

        btnBar.Children.Add(btnImport);
        btnBar.Children.Add(btnCancel);
        panel.Children.Add(btnBar);

        ShowModal(panel);
    }

    private void DeleteRoutingProfile_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.ActiveRoutingProfile != null)
        {
            if (MessageBox.Show($"Удалить профиль маршрутизации «{_vm.ActiveRoutingProfile.Name}»?", "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
            {
                _routingProfileStore.Delete(_vm.ActiveRoutingProfile.Id);
                _vm.ReloadAll();
                _vm.Notice = "Профиль маршрутизации удалён.";
            }
        }
    }

    private void SaveRouting_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.ActiveRoutingProfile != null)
        {
            _routingProfileStore.Save(_vm.ActiveRoutingProfile);
            _routingProfileStore.SyncToRuntimeSettings(_vm.ActiveRoutingProfile);
            _vm.Notice = "Настройки маршрутизации сохранены.";
            AddLog("Routing profile saved: " + _vm.ActiveRoutingProfile.Name);
            MessageBox.Show("Настройки маршрутизации успешно сохранены.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void CopyRoutingJson_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.ActiveRoutingProfile != null)
        {
            try
            {
                var json = JsonSerializer.Serialize(_vm.ActiveRoutingProfile, new JsonSerializerOptions { WriteIndented = true });
                Clipboard.SetText(json);
                MessageBox.Show("Конфигурация маршрутизации скопирована в буфер обмена.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Ошибка: " + ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }

    #endregion

    #region Settings

    private void SettingsTheme_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SettingsThemeSelector.SelectedIndex >= 0)
        {
            var theme = SettingsThemeSelector.SelectedIndex switch
            {
                1 => "light",
                2 => "oled",
                _ => "dark"
            };
            _settings.Theme = theme;
            ThemeManager.ApplyTheme(theme);
            SaveAppSettings();
        }
    }

    private void SettingsFontScale_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SettingsFontScaleSelector.SelectedIndex >= 0)
        {
            var scale = SettingsFontScaleSelector.SelectedIndex switch
            {
                0 => 0.85,
                2 => 1.15,
                3 => 1.30,
                _ => 1.0
            };
            _settings.FontScale = scale;
            SaveAppSettings();
        }
    }

    private void SettingsSubInterval_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SettingsSubInterval.SelectedIndex >= 0)
        {
            _settings.SubscriptionIntervalHours = SettingsSubInterval.SelectedIndex switch
            {
                0 => 1,
                1 => 6,
                2 => 12,
                3 => 24,
                4 => 72,
                _ => 168
            };
            SaveAppSettings();
        }
    }

    private void LaunchWithWindows_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var runKey = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            var isChecked = SettingsLaunchWithWindows.IsChecked == true;
            _settings.LaunchWithWindows = isChecked;

            if (runKey != null)
            {
                if (isChecked)
                {
                    var exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
                    if (!string.IsNullOrEmpty(exePath))
                    {
                        runKey.SetValue("NeoTUN", $"\"{exePath}\"");
                    }
                }
                else
                {
                    runKey.DeleteValue("NeoTUN", false);
                }
            }
            SaveAppSettings();
        }
        catch (Exception ex)
        {
            MessageBox.Show("Ошибка реестра автозапуска: " + ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        SettingsUpdateSummary.Text = "Проверка наличия обновлений на GitHub…";
        try
        {
            using var client = new HttpClient();
            client.DefaultRequestHeaders.UserAgent.ParseAdd("NeoTUN-Windows/0.4");
            var json = await client.GetStringAsync("https://api.github.com/repos/Kolya-YT/NeoTUN-Client/releases/latest");
            using var doc = JsonDocument.Parse(json);
            var tag = doc.RootElement.GetProperty("tag_name").GetString();
            SettingsUpdateSummary.Text = $"Последняя версия на GitHub: {tag}. Текущая: v0.1.1.";
        }
        catch (Exception ex)
        {
            SettingsUpdateSummary.Text = "Не удалось проверить обновления: " + ex.Message;
        }
    }

    private void OpenGitHubReleases_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("https://github.com/Kolya-YT/NeoTUN-Client/releases") { UseShellExecute = true });
        }
        catch { }
    }

    private void ResetSettings_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show("Сбросить все настройки к значениям по умолчанию?", "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
        {
            _settings = new AppSettings();
            SaveAppSettings();
            ApplyThemeAndScale();
            MessageBox.Show("Настройки сброшены.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void LoadAppSettings()
    {
        try
        {
            if (File.Exists(_appSettingsPath))
            {
                _settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(_appSettingsPath)) ?? new AppSettings();
            }
        }
        catch { _settings = new AppSettings(); }

        SettingsThemeSelector.SelectedIndex = _settings.Theme switch { "light" => 1, "oled" => 2, _ => 0 };
        SettingsFontScaleSelector.SelectedIndex = _settings.FontScale switch { 0.85 => 0, 1.15 => 2, 1.30 => 3, _ => 1 };
        SettingsAutoUpdateSubscriptions.IsChecked = _settings.AutoUpdateSubscriptions;
        SettingsLaunchWithWindows.IsChecked = _settings.LaunchWithWindows;
        SettingsMinimizeToTrayOnClose.IsChecked = _settings.MinimizeToTrayOnClose;
        SettingsMinimizeToTrayOnStart.IsChecked = _settings.MinimizeToTrayOnStart;
    }

    private void SaveAppSettings()
    {
        try
        {
            File.WriteAllText(_appSettingsPath, JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }

    private void ApplyThemeAndScale()
    {
        ThemeManager.ApplyTheme(_settings.Theme);
    }

    #endregion

    #region System Tray

    private void InitializeSystemTray()
    {
        try
        {
            _notifyIcon = new System.Windows.Forms.NotifyIcon
            {
                Text = "NeoTUN Client",
                Icon = System.Drawing.SystemIcons.Shield,
                Visible = true
            };

            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Открыть NeoTUN", null, (s, e) => ShowAndRestore());
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Подключить / Отключить", null, (s, e) => Dispatcher.Invoke(() => Connect_Click(s, new RoutedEventArgs())));
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("Выход", null, (s, e) => Dispatcher.Invoke(ExitApplication));

            _notifyIcon.ContextMenuStrip = menu;
            _notifyIcon.DoubleClick += (s, e) => Dispatcher.Invoke(ShowAndRestore);
        }
        catch (Exception ex)
        {
            AddLog("System tray init: " + ex.Message);
        }
    }

    private void UpdateTrayStatus(bool connected, string? serverName = null)
    {
        if (_notifyIcon == null) return;
        _notifyIcon.Text = connected
            ? $"NeoTUN: Подключено ({serverName ?? "VPN"})"
            : "NeoTUN: Отключено";
    }

    private void MinimizeToTray_Click(object sender, RoutedEventArgs e)
    {
        Hide();
    }

    private void ShowAndRestore()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitApplication()
    {
        _isExplicitExit = true;
        CleanupSystemTray();
        Application.Current.Shutdown();
    }

    private void CleanupSystemTray()
    {
        if (_notifyIcon != null)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _notifyIcon = null;
        }
    }

    #endregion

    #region Logs & Diagnostics

    private void AddLog(string message)
    {
        var line = $"[{DateTime.Now:HH:mm:ss.fff}] {message}\n";
        LogsTextBox.AppendText(line);
        LogsTextBox.ScrollToEnd();
    }

    private void CopyLogs_Click(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(LogsTextBox.Text);
        MessageBox.Show("Журнал скопирован в буфер обмена.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void ClearLogs_Click(object sender, RoutedEventArgs e)
    {
        LogsTextBox.Clear();
    }

    private void OpenDataFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", _dataDirectory) { UseShellExecute = true });
        }
        catch { }
    }

    #endregion
}
