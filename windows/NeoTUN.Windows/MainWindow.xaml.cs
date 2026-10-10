using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Net.Http;
using System.Windows.Media.Animation;
using Microsoft.Win32;
using NeoTUN.Windows.Models;
using NeoTUN.Windows.Services;
using NeoTUN.Windows.ViewModels;

namespace NeoTUN.Windows;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();
    private readonly EngineRuntime _runtime = new();
    private bool _loadingUiSettings;
    private readonly Dictionary<DependencyObject, double> _fontBaselines = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        _loadingUiSettings = true;
        try { LoadUiSettings(); }
        finally { _loadingUiSettings = false; }
        LoadRoutingSettings();
        DataDirectoryLabel.Text = DataDirectory;
        AppVersionLabel.Text = "Установлена версия " + (System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "неизвестно");
        ProfileList.SelectionChanged += (_, _) => SyncSelectedProfileToEditor();
        SyncSelectedProfileToEditor();
        _runtime.LogLine += line => Dispatcher.BeginInvoke(() => AddLog(line));
        _runtime.TrafficUpdated += (up, down, totalUp, totalDown) => Dispatcher.BeginInvoke(() =>
        {
            HomeSpeedText.Text = $"{FormatSpeed(down)} ↓ / {FormatSpeed(up)} ↑";
            HomeReceivedText.Text = FormatBytes(totalDown);
            HomeSentText.Text = FormatBytes(totalUp);
        });
        _runtime.StateChanged += (running, status) => Dispatcher.BeginInvoke(() =>
        {
            _vm.IsConnected = running;
            _vm.Status = status;
            ConnectLabel.Text = running ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ";
        });
        AddLog("NeoTUN Windows UI initialized; runtime manager loaded.");
        ShowPage("Главная");
        Loaded += async (_, _) => await AutoRefreshSubscriptionsOnStartupAsync();
    }

    private void ShowPage(string page)
    {
        _vm.ActivePage = page;
        HomePage.Visibility = page == "Главная" ? Visibility.Visible : Visibility.Collapsed;
        ServersPage.Visibility = page == "Серверы" ? Visibility.Visible : Visibility.Collapsed;
        ImportPage.Visibility = page == "Импорт" ? Visibility.Visible : Visibility.Collapsed;
        RoutingPage.Visibility = page == "Маршрутизация" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "Настройки" ? Visibility.Visible : Visibility.Collapsed;
        LogsPage.Visibility = page == "Журнал" ? Visibility.Visible : Visibility.Collapsed;

        var active = page switch
        {
            "Главная" => HomePage,
            "Серверы" => ServersPage,
            "Импорт" => ImportPage,
            "Маршрутизация" => RoutingPage,
            "Настройки" => SettingsPage,
            "Журнал" => LogsPage,
            _ => HomePage
        };
        active.Opacity = 1;
        active.BeginAnimation(UIElement.OpacityProperty, null);
        if (IsLoaded && UiAnimationsToggle?.IsChecked == true)
        {
            active.Opacity = 0;
            active.BeginAnimation(UIElement.OpacityProperty,
                new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160))
                { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
        }
        if (IsLoaded && FontScaleSelector is not null) ApplyFontScale();
    }

    private void SyncSelectedProfileToEditor()
    {
        var profile = _vm.SelectedProfile;
        ProfileName.Text = profile?.Name ?? "";
        ProfileUri.Text = profile?.Uri ?? "";
    }

    private void HomeNav_Click(object sender, RoutedEventArgs e) => ShowPage("Главная");
    private void ServersNav_Click(object sender, RoutedEventArgs e) => ShowPage("Серверы");
    private void RoutingNav_Click(object sender, RoutedEventArgs e) => ShowPage("Маршрутизация");
    private void SettingsNav_Click(object sender, RoutedEventArgs e) => ShowPage("Настройки");
    private void LogsNav_Click(object sender, RoutedEventArgs e) => ShowPage("Журнал");
    private void ImportNav_Click(object sender, RoutedEventArgs e) => ShowPage("Импорт");

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        ConnectButton.IsEnabled = false;
        try
        {
            if (_runtime.IsRunning)
            {
                _vm.Status = "Останавливаем сетевой движок…";
                await _runtime.StopAsync();
                _vm.IsConnected = false;
                ConnectLabel.Text = "ПОДКЛЮЧИТЬ";
                return;
            }

            var profile = _vm.SelectedProfile;
            if (profile is null)
            {
                _vm.Status = "Сначала выберите или импортируйте сервер.";
                return;
            }

            _vm.Status = "Проверяем профиль и запускаем сетевой движок…";
            AddLog(_vm.Status);
            await _runtime.StartAsync(profile.Uri);
            // EngineRuntime owns connection state. Do not overwrite its status here:
            // an engine can exit immediately after startup and publish a failure state.
            AddLog("Запуск движка завершён; актуальное состояние получено от runtime.");
        }
        catch (Exception ex)
        {
            _vm.IsConnected = false;
            _vm.Status = ex.Message;
            ConnectButton.Content = "⏻  ПОДКЛЮЧИТЬ";
            AddLog("Connection failed: " + ex);
            MessageBox.Show(ex.Message, "NeoTUN — ошибка подключения", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { ConnectButton.IsEnabled = true; }
    }

    protected override void OnClosed(EventArgs e)
    {
        _runtime.Dispose();
        base.OnClosed(e);
    }

    private async void ImportProfiles_Click(object sender, RoutedEventArgs e)
    {
        ImportButtonState(false);
        try
        {
            var parsed = await SubscriptionImporter.ImportAsync(ImportEditor.Text);
            if (parsed.Count == 0)
            {
                _vm.Notice = "Поддерживаемые ссылки не найдены. Проверьте ссылку или содержимое подписки.";
                AddLog(_vm.Notice);
                return;
            }
            _vm.AddProfiles(parsed);
            ImportEditor.Clear();
            ShowPage("Серверы");
            AddLog(_vm.Notice);
        }
        catch (Exception ex)
        {
            _vm.Notice = "Ошибка импорта: " + ex.Message;
            AddLog(_vm.Notice);
        }
        finally { ImportButtonState(true); }
    }

    private void ImportButtonState(bool enabled)
    {
        foreach (var button in FindVisualChildren<Button>(this).Where(b => b.Name == "ImportProfilesButton"))
            button.IsEnabled = enabled;
    }

    private static IEnumerable<T> FindVisualChildren<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, i);
            if (child is T matched) yield return matched;
            foreach (var descendant in FindVisualChildren<T>(child)) yield return descendant;
        }
    }

    private void PasteImport_Click(object sender, RoutedEventArgs e)
    {
        try { ImportEditor.Text = Clipboard.GetText(); }
        catch (Exception ex) { _vm.Notice = "Не удалось прочитать буфер обмена: " + ex.Message; }
    }

    private void ClearImport_Click(object sender, RoutedEventArgs e) => ImportEditor.Clear();

    private string SubscriptionUpdateStatePath => Path.Combine(DataDirectory, "subscription-update-state.json");

    private async void RefreshSubscriptions_Click(object sender, RoutedEventArgs e) =>
        await RefreshSubscriptionSourcesAsync(automatic: false);

    private async Task AutoRefreshSubscriptionsOnStartupAsync()
    {
        if (AutoUpdateSubscriptionsToggle.IsChecked != true) return;
        await RefreshSubscriptionSourcesAsync(automatic: true);
    }

    private int SelectedSubscriptionIntervalHours() => SubscriptionIntervalSelector.SelectedIndex switch
    {
        0 => 1, 1 => 6, 3 => 24, 4 => 72, 5 => 168, _ => 12
    };

    private Dictionary<string, DateTimeOffset> LoadSubscriptionUpdateState()
    {
        try
        {
            if (!File.Exists(SubscriptionUpdateStatePath)) return new Dictionary<string, DateTimeOffset>(StringComparer.OrdinalIgnoreCase);
            var saved = JsonSerializer.Deserialize<Dictionary<string, DateTimeOffset>>(File.ReadAllText(SubscriptionUpdateStatePath));
            return saved is null
                ? new Dictionary<string, DateTimeOffset>(StringComparer.OrdinalIgnoreCase)
                : new Dictionary<string, DateTimeOffset>(saved, StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            AddLog("Could not read subscription update state: " + ex.Message);
            return new Dictionary<string, DateTimeOffset>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private async Task RefreshSubscriptionSourcesAsync(bool automatic)
    {
        var allSources = _vm.Profiles
            .Select(profile => profile.Source?.Trim() ?? "")
            .Where(source => Uri.TryCreate(source, UriKind.Absolute, out var uri) &&
                (uri.Scheme == Uri.UriSchemeHttps || uri.Scheme == Uri.UriSchemeHttp))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        if (allSources.Length == 0)
        {
            if (!automatic)
            {
                _vm.Notice = "Нет URL-подписок для обновления. Импортируйте HTTPS-ссылку подписки, чтобы обновлять её позже.";
                AddLog(_vm.Notice);
            }
            return;
        }

        var state = LoadSubscriptionUpdateState();
        var now = DateTimeOffset.UtcNow;
        var interval = TimeSpan.FromHours(SelectedSubscriptionIntervalHours());
        var sources = allSources.Where(source => !automatic || !state.TryGetValue(source, out var last) || now - last >= interval).ToArray();
        if (sources.Length == 0) return;

        RefreshSubscriptionsButton.IsEnabled = false;
        var refreshedProfiles = 0;
        var successfulFeeds = 0;
        var errors = new List<string>();
        try
        {
            foreach (var source in sources)
            {
                var host = new Uri(source).Host;
                try
                {
                    var imported = await SubscriptionImporter.ImportAsync(source);
                    if (imported.Count == 0)
                    {
                        errors.Add(host + ": поддерживаемые профили не найдены; прежние серверы сохранены.");
                        continue;
                    }

                    refreshedProfiles += _vm.ReplaceSubscriptionProfiles(source, imported);
                    state[source] = DateTimeOffset.UtcNow;
                    successfulFeeds++;
                    AddLog($"Subscription refreshed: {host}, profiles={imported.Count}");
                }
                catch (Exception ex)
                {
                    errors.Add(host + ": " + ex.Message);
                    AddLog("Subscription refresh failed: " + host + " — " + ex.Message);
                }
            }

            if (successfulFeeds > 0)
            {
                Directory.CreateDirectory(DataDirectory);
                File.WriteAllText(SubscriptionUpdateStatePath, JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true }));
            }

            ProfileList.Items.Refresh();
            SyncSelectedProfileToEditor();
            if (!automatic || errors.Count > 0)
            {
                _vm.Notice = errors.Count == 0
                    ? $"Обновлено подписок: {successfulFeeds}. Получено профилей: {refreshedProfiles}."
                    : $"Обновлено подписок: {successfulFeeds} из {sources.Length}. Профилей: {refreshedProfiles}. Ошибки: " + string.Join(" | ", errors);
                AddLog(_vm.Notice);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            AddLog("Could not save subscription update state: " + ex.Message);
            if (!automatic) _vm.Notice = "Подписки обновлены, но время последнего обновления не сохранилось: " + ex.Message;
        }
        finally
        {
            RefreshSubscriptionsButton.IsEnabled = true;
        }
    }

    private void SaveProfile_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedProfile is null) return;
        var uri = ProfileUri.Text.Trim();
        if (!uri.Contains("://", StringComparison.Ordinal))
        {
            _vm.Notice = "Укажите корректную ссылку подключения.";
            return;
        }
        _vm.SelectedProfile.Name = ProfileName.Text.Trim();
        _vm.SelectedProfile.Uri = uri;
        _vm.SelectedProfile.UpdatedAt = DateTimeOffset.UtcNow;
        _vm.SaveProfiles();
        _vm.Notice = "Профиль сохранён.";
        ProfileList.Items.Refresh();
        AddLog(_vm.Notice);
    }

    private void CopyProfile_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedProfile is null) return;
        Clipboard.SetText(_vm.SelectedProfile.Uri);
        _vm.Notice = "Ссылка скопирована.";
    }

    private void DeleteProfile_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedProfile is null) return;
        if (MessageBox.Show("Удалить выбранный профиль?", "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        _vm.RemoveSelected();
        SyncSelectedProfileToEditor();
    }

    private string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NeoTUN");
    private string RoutingSettingsPath => Path.Combine(DataDirectory, "routing-settings.json");
    private string UiSettingsPath => Path.Combine(DataDirectory, "ui-settings.json");
    private string RuntimeLogPath => Path.Combine(DataDirectory, "windows-runtime.log");

    private sealed record RoutingSettings(
        bool GlobalProxy, bool Ipv6Enabled, int Mtu, int RouteOrder, string RemoteDns, string DomesticDns, string DomesticDnsDomains,
        string BlockSites, string BlockIp, string ProxySites, string ProxyIp,
        string DirectSites, string DirectIp, string ProcessExclusions);

    private sealed record UiSettings(string? Theme = null, int? FontScale = null, bool? UiAnimations = null, bool? AutoUpdateSubscriptions = null, int? SubscriptionUpdateHours = null);

    private void LoadRoutingSettings()
    {
        try
        {
            if (!File.Exists(RoutingSettingsPath)) return;
            var settings = JsonSerializer.Deserialize<RoutingSettings>(File.ReadAllText(RoutingSettingsPath));
            if (settings is null) return;
            GlobalProxyToggle.IsChecked = settings.GlobalProxy;
            Ipv6Toggle.IsChecked = settings.Ipv6Enabled;
            MtuInput.Text = settings.Mtu.ToString();
            RouteOrderSelector.SelectedIndex = Math.Clamp(settings.RouteOrder, 0, 2);
            RemoteDnsInput.Text = settings.RemoteDns;
            DomesticDnsInput.Text = settings.DomesticDns;
            DomesticDnsDomainsEditor.Text = settings.DomesticDnsDomains;
            BlockSitesEditor.Text = settings.BlockSites;
            BlockIpEditor.Text = settings.BlockIp;
            ProxySitesEditor.Text = settings.ProxySites;
            ProxyIpEditor.Text = settings.ProxyIp;
            DirectSitesEditor.Text = settings.DirectSites;
            DirectIpEditor.Text = settings.DirectIp;
            ProcessExclusionsEditor.Text = settings.ProcessExclusions;
            ProcessExclusionsEditor.Text = settings.ProcessExclusions;
            RoutingSummary.Text = "Загружено из локального файла маршрутов.";
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            RoutingSummary.Text = "Не удалось прочитать файл маршрутов: " + ex.Message;
        }
    }

    private void SaveRouting_Click(object sender, RoutedEventArgs e)
    {
        if (!ValidateDns(RemoteDnsInput.Text, "Удалённый DNS") ||
            !ValidateDns(DomesticDnsInput.Text, "Домашний DNS"))
            return;

        if (!int.TryParse(MtuInput.Text.Trim(), out var mtu) || mtu is < 1280 or > 1500)
        {
            MessageBox.Show("MTU должен быть целым числом от 1280 до 1500.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var processExclusions = NormalizeLines(ProcessExclusionsEditor.Text)
            .Split(new[] { Environment.NewLine }, StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        var invalidProcessName = processExclusions.FirstOrDefault(name =>
            !name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
            name != Path.GetFileName(name) || name.Contains('/') || name.Contains('\\') || name.Contains(':'));
        if (invalidProcessName is not null)
        {
            MessageBox.Show("Имя процесса «" + invalidProcessName + "» некорректно. Укажите только имя файла .exe, без пути.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var settings = new RoutingSettings(
            GlobalProxyToggle.IsChecked == true,
            Ipv6Toggle.IsChecked == true,
            mtu,
            Math.Clamp(RouteOrderSelector.SelectedIndex, 0, 2),
            RemoteDnsInput.Text.Trim(),
            DomesticDnsInput.Text.Trim(),
            NormalizeLines(DomesticDnsDomainsEditor.Text),
            NormalizeLines(BlockSitesEditor.Text),
            NormalizeLines(BlockIpEditor.Text),
            NormalizeLines(ProxySitesEditor.Text),
            NormalizeLines(ProxyIpEditor.Text),
            NormalizeLines(DirectSitesEditor.Text),
            NormalizeLines(DirectIpEditor.Text),
            NormalizeLines(ProcessExclusionsEditor.Text));

        try
        {
            Directory.CreateDirectory(DataDirectory);
            File.WriteAllText(RoutingSettingsPath, JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
            RoutingSummary.Text = "Маршруты сохранены локально. Настройки будут учтены при следующем подключении.";
            _vm.Notice = "Настройки маршрутизации сохранены.";
            AddLog("Routing preferences saved to " + RoutingSettingsPath);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            RoutingSummary.Text = "Не удалось сохранить маршруты: " + ex.Message;
        }
    }

    private void LoadRouting_Click(object sender, RoutedEventArgs e)
    {
        if (!File.Exists(RoutingSettingsPath))
        {
            RoutingSummary.Text = "Файл маршрутов ещё не создан. Сначала сохраните настройки.";
            return;
        }
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(RoutingSettingsPath));
            LoadRoutingSettings();
            RoutingSummary.Text = "Файл маршрутов — корректный JSON; настройки загружены.";
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            RoutingSummary.Text = "Ошибка файла маршрутов: " + ex.Message;
        }
    }

    private void CopyRouting_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!int.TryParse(MtuInput.Text.Trim(), out var mtu) || mtu is < 1280 or > 1500)
                throw new InvalidDataException("MTU должен быть целым числом от 1280 до 1500.");
            var settings = new RoutingSettings(
                GlobalProxyToggle.IsChecked == true,
                Ipv6Toggle.IsChecked == true,
                mtu,
                Math.Clamp(RouteOrderSelector.SelectedIndex, 0, 2),
                RemoteDnsInput.Text.Trim(), DomesticDnsInput.Text.Trim(), NormalizeLines(DomesticDnsDomainsEditor.Text),
                NormalizeLines(BlockSitesEditor.Text), NormalizeLines(BlockIpEditor.Text),
                NormalizeLines(ProxySitesEditor.Text), NormalizeLines(ProxyIpEditor.Text),
                NormalizeLines(DirectSitesEditor.Text), NormalizeLines(DirectIpEditor.Text),
                NormalizeLines(ProcessExclusionsEditor.Text));
            Clipboard.SetText(JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
            RoutingSummary.Text = "JSON настроек маршрутизации скопирован в буфер обмена.";
        }
        catch (Exception ex) { RoutingSummary.Text = "Не удалось сформировать JSON: " + ex.Message; }
    }

    private void ResetRouting_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show("Сбросить поля маршрутизации? Сохранённый файл не будет удалён до нового сохранения.",
                "NeoTUN", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        GlobalProxyToggle.IsChecked = true;
        Ipv6Toggle.IsChecked = false;
        MtuInput.Text = "1500";
        RouteOrderSelector.SelectedIndex = 0;
        RemoteDnsInput.Text = "https://8.8.8.8/dns-query";
        DomesticDnsInput.Text = "https://77.88.8.8/dns-query";
        DomesticDnsDomainsEditor.Text = "ru" + Environment.NewLine + "su" + Environment.NewLine + "рф";
        BlockSitesEditor.Clear(); BlockIpEditor.Clear();
        ProxySitesEditor.Clear(); ProxyIpEditor.Clear();
        DirectSitesEditor.Clear(); DirectIpEditor.Clear(); ProcessExclusionsEditor.Clear();
        ProcessExclusionsEditor.Clear();
        RoutingSummary.Text = "Поля сброшены. Нажмите «Сохранить маршруты», чтобы записать новые значения.";
    }

    private bool ValidateDns(string value, string label)
    {
        value = value.Trim();
        if (value.Length == 0) return true;
        if (value.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            if (Uri.TryCreate(value, UriKind.Absolute, out var endpoint) && !string.IsNullOrWhiteSpace(endpoint.Host)) return true;
        }
        else if (System.Net.IPAddress.TryParse(value, out _)) return true;

        MessageBox.Show($"{label}: укажите корректный IP-адрес или HTTPS URL.", "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Warning);
        return false;
    }

    private static string NormalizeLines(string value) => string.Join(Environment.NewLine,
        value.Replace("\r", "").Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => !line.StartsWith("#", StringComparison.Ordinal))
            .Distinct(StringComparer.OrdinalIgnoreCase));

    private void LoadUiSettings()
    {
        try
        {
            var saved = File.Exists(UiSettingsPath)
                ? JsonSerializer.Deserialize<UiSettings>(File.ReadAllText(UiSettingsPath))
                : null;
            var theme = saved?.Theme ?? "dark";
            ThemeSelector.SelectedIndex = theme switch { "light" => 1, "oled" => 2, _ => 0 };
            FontScaleSelector.SelectedIndex = (saved?.FontScale ?? 100) switch { 85 => 0, 115 => 2, 130 => 3, _ => 1 };
            UiAnimationsToggle.IsChecked = saved?.UiAnimations ?? true;
            AutoUpdateSubscriptionsToggle.IsChecked = saved?.AutoUpdateSubscriptions ?? true;
            SubscriptionIntervalSelector.SelectedIndex = (saved?.SubscriptionUpdateHours ?? 12) switch { 1 => 0, 6 => 1, 24 => 3, 72 => 4, 168 => 5, _ => 2 };
            LaunchWithWindowsToggle.IsChecked = IsLaunchWithWindowsEnabled();
            ApplyTheme(theme);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            SettingsSummary.Text = "Не удалось загрузить настройки интерфейса: " + ex.Message;
        }
    }

    private UiSettings ReadUiSettings()
    {
        var theme = ThemeSelector.SelectedIndex switch { 1 => "light", 2 => "oled", _ => "dark" };
        var fontScale = FontScaleSelector.SelectedIndex switch { 0 => 85, 2 => 115, 3 => 130, _ => 100 };
        var hours = SubscriptionIntervalSelector.SelectedIndex switch { 0 => 1, 1 => 6, 3 => 24, 4 => 72, 5 => 168, _ => 12 };
        return new UiSettings(theme, fontScale, UiAnimationsToggle.IsChecked == true,
            AutoUpdateSubscriptionsToggle.IsChecked == true, hours);
    }

    private void PersistUiSettings(string message)
    {
        if (_loadingUiSettings || !IsInitialized) return;
        try
        {
            Directory.CreateDirectory(DataDirectory);
            File.WriteAllText(UiSettingsPath, JsonSerializer.Serialize(ReadUiSettings(), new JsonSerializerOptions { WriteIndented = true }));
            SettingsSummary.Text = message;
        }
        catch (Exception ex) { SettingsSummary.Text = "Настройка применена, но не сохранена: " + ex.Message; }
    }

    private void ThemeSelector_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingUiSettings || !IsInitialized || ThemeSelector.SelectedIndex < 0) return;
        var theme = ThemeSelector.SelectedIndex switch { 1 => "light", 2 => "oled", _ => "dark" };
        ApplyTheme(theme);
        PersistUiSettings("Тема применена и сохранена.");
    }

    private void FontScaleSelector_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingUiSettings || !IsInitialized || FontScaleSelector.SelectedIndex < 0) return;
        ApplyFontScale();
        PersistUiSettings("Размер текста применён и сохранён.");
    }

    private void UiAnimations_Changed(object sender, RoutedEventArgs e)
    {
        if (_loadingUiSettings || !IsInitialized) return;
        PersistUiSettings(UiAnimationsToggle.IsChecked == true ? "Переходы включены." : "Переходы выключены.");
    }

    private void AutoUpdateSubscriptions_Changed(object sender, RoutedEventArgs e)
    {
        if (_loadingUiSettings || !IsInitialized) return;
        PersistUiSettings(AutoUpdateSubscriptionsToggle.IsChecked == true ? "Автообновление подписок включено." : "Автообновление подписок выключено.");
    }

    private void SubscriptionInterval_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingUiSettings || !IsInitialized || SubscriptionIntervalSelector.SelectedIndex < 0) return;
        PersistUiSettings("Интервал обновления подписок сохранён.");
    }

    private void ApplyFontScale()
    {
        var factor = FontScaleSelector.SelectedIndex switch { 0 => 0.85, 2 => 1.15, 3 => 1.30, _ => 1.0 };
        ApplyFontScaleRecursive(MainRoot, factor);
    }

    private void ApplyFontScaleRecursive(DependencyObject parent, double factor)
    {
        if (parent is Control control)
        {
            if (!_fontBaselines.ContainsKey(parent)) _fontBaselines[parent] = control.FontSize;
            control.FontSize = _fontBaselines[parent] * factor;
        }
        else if (parent is TextBlock textBlock)
        {
            if (!_fontBaselines.ContainsKey(parent)) _fontBaselines[parent] = textBlock.FontSize;
            textBlock.FontSize = _fontBaselines[parent] * factor;
        }

        var count = VisualTreeHelper.GetChildrenCount(parent);
        for (var index = 0; index < count; index++)
            ApplyFontScaleRecursive(VisualTreeHelper.GetChild(parent, index), factor);
    }
    private void ApplyTheme(string theme)
    {
        var colors = theme switch
        {
            "light" => new Dictionary<string, string> {
                ["BackgroundBrush"]="#F4F5FA", ["NavigationBrush"]="#FFFFFF", ["SurfaceBrush"]="#FFFFFF",
                ["RaisedSurfaceBrush"]="#E9EBF5", ["InputSurfaceBrush"]="#F0F1F8", ["BorderBrushNeo"]="#D9DCEC",
                ["BrandVioletBrush"]="#6850E8", ["BrandBlueBrush"]="#455BE8", ["AccentSurfaceBrush"]="#EAE6FF",
                ["TextPrimaryBrush"]="#191A28", ["TextSecondaryBrush"]="#50566B", ["TextMutedBrush"]="#747B91",
                ["SuccessBrush"]="#147B55", ["SuccessSurfaceBrush"]="#DDF6EA", ["DangerBrush"]="#AD3151", ["DangerSurfaceBrush"]="#FFE8EE" },
            "oled" => new Dictionary<string, string> {
                ["BackgroundBrush"]="#000000", ["NavigationBrush"]="#050509", ["SurfaceBrush"]="#09090F",
                ["RaisedSurfaceBrush"]="#11111B", ["InputSurfaceBrush"]="#05050A", ["BorderBrushNeo"]="#242435",
                ["BrandVioletBrush"]="#8068FF", ["BrandBlueBrush"]="#5B68F2", ["AccentSurfaceBrush"]="#171327",
                ["TextPrimaryBrush"]="#F7F8FF", ["TextSecondaryBrush"]="#B1B8CD", ["TextMutedBrush"]="#858EA8",
                ["SuccessBrush"]="#65E7B0", ["SuccessSurfaceBrush"]="#09251D", ["DangerBrush"]="#FFB5C3", ["DangerSurfaceBrush"]="#26101A" },
            _ => new Dictionary<string, string> {
                ["BackgroundBrush"]="#070910", ["NavigationBrush"]="#0C0F19", ["SurfaceBrush"]="#121521",
                ["RaisedSurfaceBrush"]="#1C2033", ["InputSurfaceBrush"]="#0D111D", ["BorderBrushNeo"]="#2A2E44",
                ["BrandVioletBrush"]="#8769FF", ["BrandBlueBrush"]="#5B5BF1", ["AccentSurfaceBrush"]="#23203B",
                ["TextPrimaryBrush"]="#FFFFFF", ["TextSecondaryBrush"]="#A5ABC2", ["TextMutedBrush"]="#8F96AD",
                ["SuccessBrush"]="#5FE6A6", ["SuccessSurfaceBrush"]="#12372E", ["DangerBrush"]="#FFB1C0", ["DangerSurfaceBrush"]="#311B27" }
        };

        foreach (var item in colors)
        {
            if (ColorConverter.ConvertFromString(item.Value) is not Color color)
                continue;

            // Brushes referenced by StaticResource in the compiled XAML can be frozen
            // by WPF. Replace the resource with a new mutable brush instead of trying
            // to change the Color property on a possibly frozen instance.
            Application.Current.Resources[item.Key] = new SolidColorBrush(color);
        }
    }

    private bool IsLaunchWithWindowsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", false);
            return key?.GetValue("NeoTUN") is string;
        }
        catch { return false; }
    }

    private void LaunchWithWindows_Changed(object sender, RoutedEventArgs e)
    {
        if (!IsInitialized || LaunchWithWindowsToggle.IsChecked is null) return;
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            if (key is null) throw new InvalidOperationException("Не удалось открыть раздел автозапуска.");
            if (LaunchWithWindowsToggle.IsChecked == true)
            {
                var executable = Process.GetCurrentProcess().MainModule?.FileName;
                if (string.IsNullOrWhiteSpace(executable))
                    throw new InvalidOperationException("Не удалось определить путь к NeoTUN.exe.");
                key.SetValue("NeoTUN", $"\"{executable}\"");
            }
            else key.DeleteValue("NeoTUN", false);
            SettingsSummary.Text = LaunchWithWindowsToggle.IsChecked == true
                ? "Автозапуск включён для текущего пользователя."
                : "Автозапуск отключён.";
        }
        catch (Exception ex)
        {
            SettingsSummary.Text = "Не удалось изменить автозапуск: " + ex.Message;
            LaunchWithWindowsToggle.IsChecked = IsLaunchWithWindowsEnabled();
        }
    }

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        CheckUpdatesButton.IsEnabled = false;
        UpdateSummary.Text = "Проверяем последнюю опубликованную версию…";
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
            client.DefaultRequestHeaders.UserAgent.ParseAdd("NeoTUN-Windows/0.1");
            using var response = await client.GetAsync("https://api.github.com/repos/Kolya-YT/NeoTUN-Client/releases/latest");
            response.EnsureSuccessStatusCode();
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var tag = document.RootElement.TryGetProperty("tag_name", out var tagValue) ? tagValue.GetString() ?? "" : "";
            var releaseUrl = document.RootElement.TryGetProperty("html_url", out var urlValue) ? urlValue.GetString() ?? "https://github.com/Kolya-YT/NeoTUN-Client/releases" : "https://github.com/Kolya-YT/NeoTUN-Client/releases";
            var numericVersion = System.Text.RegularExpressions.Regex.Match(tag, @"\d+(\.\d+){1,3}").Value;
            var current = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
            if (!Version.TryParse(numericVersion, out var latest))
            {
                UpdateSummary.Text = "Последний релиз найден (" + tag + "), но его версию не удалось распознать. Откройте страницу релизов для проверки.";
                if (MessageBox.Show("Открыть страницу релизов NeoTUN?", "NeoTUN — обновления", MessageBoxButton.YesNo, MessageBoxImage.Information) == MessageBoxResult.Yes)
                    Process.Start(new ProcessStartInfo(releaseUrl) { UseShellExecute = true });
                return;
            }

            if (latest > current)
            {
                UpdateSummary.Text = "Доступно обновление: " + tag;
                if (MessageBox.Show("Установлена версия " + current + ". Доступна версия " + tag + ".\n\nОткрыть загрузку релиза?", "NeoTUN — обновления", MessageBoxButton.YesNo, MessageBoxImage.Information) == MessageBoxResult.Yes)
                    Process.Start(new ProcessStartInfo(releaseUrl) { UseShellExecute = true });
            }
            else
            {
                UpdateSummary.Text = "Установлена актуальная версия (" + current + ").";
            }
            AddLog("Update check: current=" + current + ", latest=" + tag + ".");
        }
        catch (Exception ex)
        {
            UpdateSummary.Text = "Не удалось проверить обновления: " + ex.Message;
            AddLog("Update check failed: " + ex.Message);
        }
        finally
        {
            CheckUpdatesButton.IsEnabled = true;
        }
    }
    private void OpenDataFolder_Click(object sender, RoutedEventArgs e) => OpenPath(DataDirectory);
    private void OpenLog_Click(object sender, RoutedEventArgs e) => OpenPath(RuntimeLogPath);

    private void OpenPath(string path)
    {
        try
        {
            if (File.Exists(path))
                Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            else
            {
                Directory.CreateDirectory(Directory.Exists(path) ? path : Path.GetDirectoryName(path)!);
                Process.Start(new ProcessStartInfo(Directory.Exists(path) ? path : Path.GetDirectoryName(path)!) { UseShellExecute = true });
            }
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "NeoTUN", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void CopyDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var diagnostics = new System.Text.StringBuilder()
                .AppendLine("NeoTUN Windows diagnostics")
                .AppendLine("Version: " + System.Reflection.Assembly.GetExecutingAssembly().GetName().Version)
                .AppendLine("OS: " + Environment.OSVersion)
                .AppendLine("Data directory: " + DataDirectory)
                .AppendLine("Selected profile: " + (_vm.SelectedProfile?.Name ?? "none"))
                .AppendLine("Runtime is running: " + _runtime.IsRunning);
            if (File.Exists(RuntimeLogPath))
                diagnostics.AppendLine().AppendLine("Runtime log:").AppendLine(File.ReadAllText(RuntimeLogPath));
            Clipboard.SetText(diagnostics.ToString());
            SettingsSummary.Text = "Диагностика скопирована в буфер обмена. Проверьте её перед отправкой — журнал может содержать технические адреса.";
        }
        catch (Exception ex) { SettingsSummary.Text = "Не удалось собрать диагностику: " + ex.Message; }
    }

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KiB", "MiB", "GiB", "TiB"];
        double value = Math.Max(0, bytes);
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1) { value /= 1024; unit++; }
        return $"{value:0.##} {units[unit]}";
    }

    private static string FormatSpeed(long bytesPerSecond) => FormatBytes(bytesPerSecond) + "/s";

    private void AddLog(string message)
    {
        if (LogsText is null) return;
        LogsText.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
        LogsText.ScrollToEnd();
    }
}
