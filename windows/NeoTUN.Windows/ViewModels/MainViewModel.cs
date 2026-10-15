using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using NeoTUN.Windows.Models;
using NeoTUN.Windows.Services;

namespace NeoTUN.Windows.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly ProfileStore _profileStore = new();
    private readonly SubscriptionStore _subscriptionStore = new();
    private readonly RoutingProfileStore _routingProfileStore = new();

    private ServerProfile? _selectedProfile;
    private RoutingProfile? _activeRoutingProfile;
    private bool _routingEnabled = true;
    private string _status = "Готово к подключению";
    private string _activePage = "Главная";
    private bool _isConnected;
    private bool _isConnecting;
    private string _searchText = "";
    private string _notice = "Добавьте ссылку на сервер или импортируйте подписку.";

    private string _speed = "0 B/s";
    private string _downloadSpeed = "0 B/s";
    private string _uploadSpeed = "0 B/s";
    private string _received = "0 B";
    private string _sent = "0 B";

    public ObservableCollection<ServerProfile> Profiles { get; } = new();
    public ObservableCollection<ServerProfile> FilteredProfiles { get; } = new();
    public ObservableCollection<Subscription> Subscriptions { get; } = new();
    public ObservableCollection<RoutingProfile> RoutingProfiles { get; } = new();
    public ObservableCollection<ProfileGroupViewModel> GroupedProfiles { get; } = new();

    public ServerProfile? SelectedProfile
    {
        get => _selectedProfile;
        set
        {
            if (_selectedProfile != value)
            {
                _selectedProfile = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(SelectedEndpoint));
                OnPropertyChanged(nameof(SelectedProfileName));
                OnPropertyChanged(nameof(SelectedProfileUri));
                OnPropertyChanged(nameof(SelectedProfileProtocol));
                OnPropertyChanged(nameof(SelectedProfileCountryFlag));
                OnPropertyChanged(nameof(SelectedProfileEngine));
                OnPropertyChanged(nameof(SelectedProfilePing));
                OnPropertyChanged(nameof(SelectedProfilePingBrush));
            }
        }
    }

    public RoutingProfile? ActiveRoutingProfile
    {
        get => _activeRoutingProfile;
        set
        {
            if (_activeRoutingProfile != value)
            {
                _activeRoutingProfile = value;
                OnPropertyChanged();
            }
        }
    }

    public bool RoutingEnabled
    {
        get => _routingEnabled;
        set
        {
            if (_routingEnabled == value) return;
            _routingEnabled = value;
            _routingProfileStore.SetEnabled(value);
            OnPropertyChanged();
            Notice = value
                ? "Пользовательские правила маршрутизации включены. Переподключитесь, чтобы применить изменения."
                : "Пользовательские правила маршрутизации выключены. Переподключитесь, чтобы применить изменения.";
        }
    }

    public string SelectedEndpoint => SelectedProfile?.Endpoint ?? "Сервер не выбран";
    public string SelectedProfileName => SelectedProfile?.Name ?? "Сервер не выбран";
    public string SelectedProfileUri => SelectedProfile?.Uri ?? "";
    public string SelectedProfileProtocol => SelectedProfile?.ProtocolLabel ?? "—";
    public string SelectedProfileCountryFlag => SelectedProfile?.CountryFlag ?? "🌐";
    public string SelectedProfileEngine => SelectedProfile?.EngineLabel ?? "sing-box";
    public string SelectedProfilePing => SelectedProfile?.DisplayPing ?? "—";
    public string SelectedProfilePingBrush => SelectedProfile?.PingColorBrushKey ?? "TextMutedBrush";

    public string Status
    {
        get => _status;
        set
        {
            if (_status != value)
            {
                _status = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(ConnectionLabel));
            }
        }
    }

    public string ActivePage
    {
        get => _activePage;
        set
        {
            if (_activePage != value)
            {
                _activePage = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(IsHomePage));
                OnPropertyChanged(nameof(IsServersPage));
                OnPropertyChanged(nameof(IsRoutingPage));
                OnPropertyChanged(nameof(IsSettingsPage));
                OnPropertyChanged(nameof(IsLogsPage));
            }
        }
    }

    public bool IsHomePage => ActivePage == "Главная";
    public bool IsServersPage => ActivePage == "Серверы";
    public bool IsRoutingPage => ActivePage == "Маршрутизация";
    public bool IsSettingsPage => ActivePage == "Настройки";
    public bool IsLogsPage => ActivePage == "Журнал";

    public bool IsConnected
    {
        get => _isConnected;
        set
        {
            if (_isConnected != value)
            {
                _isConnected = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(ConnectionLabel));
                OnPropertyChanged(nameof(ConnectionBadgeText));
                OnPropertyChanged(nameof(HeroGradientBrushKey));
                OnPropertyChanged(nameof(ConnectionButtonText));
            }
        }
    }

    public bool IsConnecting
    {
        get => _isConnecting;
        set
        {
            if (_isConnecting != value)
            {
                _isConnecting = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(ConnectionLabel));
                OnPropertyChanged(nameof(ConnectionBadgeText));
                OnPropertyChanged(nameof(ConnectionButtonText));
            }
        }
    }

    public string ConnectionLabel =>
        IsConnecting ? "Подключение…" : (IsConnected ? "Подключено" : "Отключено");

    public string ConnectionBadgeText =>
        IsConnecting ? "УСТАНОВКА СОЕДИНЕНИЯ…" : (IsConnected ? "СОЕДИНЕНИЕ АКТИВНО" : "СОЕДИНЕНИЕ НЕ АКТИВНО");

    public string ConnectionButtonText =>
        IsConnecting ? "ПОДКЛЮЧЕНИЕ…" : (IsConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ");

    public string HeroGradientBrushKey =>
        IsConnected ? "HeroConnectedGradient" : "HeroIdleGradient";

    public string SearchText
    {
        get => _searchText;
        set
        {
            if (_searchText != value)
            {
                _searchText = value;
                OnPropertyChanged();
                RefreshFilter();
            }
        }
    }

    public string Notice
    {
        get => _notice;
        set { if (_notice != value) { _notice = value; OnPropertyChanged(); } }
    }

    public string Speed { get => _speed; set { if (_speed != value) { _speed = value; OnPropertyChanged(); } } }
    public string DownloadSpeed { get => _downloadSpeed; set { if (_downloadSpeed != value) { _downloadSpeed = value; OnPropertyChanged(); } } }
    public string UploadSpeed { get => _uploadSpeed; set { if (_uploadSpeed != value) { _uploadSpeed = value; OnPropertyChanged(); } } }
    public string Received { get => _received; set { if (_received != value) { _received = value; OnPropertyChanged(); } } }
    public string Sent { get => _sent; set { if (_sent != value) { _sent = value; OnPropertyChanged(); } } }

    public MainViewModel()
    {
        ReloadAll();
    }

    public void ReloadAll()
    {
        // 1. Load Subscriptions
        Subscriptions.Clear();
        foreach (var sub in _subscriptionStore.All())
            Subscriptions.Add(sub);

        // 2. Load Profiles
        Profiles.Clear();
        foreach (var profile in _profileStore.Load())
            Profiles.Add(profile);

        // Update profile counts in subscriptions
        foreach (var sub in Subscriptions)
        {
            sub.ProfileCount = Profiles.Count(p => p.SourceSubscriptionId == sub.Id);
        }

        RefreshFilter();

        if (SelectedProfile == null || !Profiles.Any(p => p.Id == SelectedProfile.Id))
        {
            SelectedProfile = Profiles.FirstOrDefault();
        }

        // 3. Load Routing Profiles
        RoutingProfiles.Clear();
        foreach (var rp in _routingProfileStore.All())
            RoutingProfiles.Add(rp);

        ActiveRoutingProfile = _routingProfileStore.Active();
        _routingEnabled = _routingProfileStore.IsEnabled();
        OnPropertyChanged(nameof(RoutingEnabled));
    }

    public void RefreshFilter()
    {
        FilteredProfiles.Clear();
        var search = SearchText.Trim();

        var query = Profiles.AsEnumerable();
        if (!string.IsNullOrEmpty(search))
        {
            query = query.Where(p =>
                p.Name.Contains(search, StringComparison.OrdinalIgnoreCase) ||
                p.Endpoint.Contains(search, StringComparison.OrdinalIgnoreCase) ||
                p.Protocol.Contains(search, StringComparison.OrdinalIgnoreCase));
        }

        foreach (var profile in query)
            FilteredProfiles.Add(profile);

        // Rebuild GroupedProfiles
        GroupedProfiles.Clear();

        // 1. Subscription groups
        foreach (var sub in Subscriptions)
        {
            var items = FilteredProfiles.Where(p => p.SourceSubscriptionId == sub.Id).ToList();
            if (items.Count > 0 || string.IsNullOrEmpty(search))
            {
                GroupedProfiles.Add(new ProfileGroupViewModel(sub.Name, sub.LastUpdatedFormatted, items, sub));
            }
        }

        // 2. Local / unassigned profiles
        var localItems = FilteredProfiles.Where(p => string.IsNullOrEmpty(p.SourceSubscriptionId)).ToList();
        if (localItems.Count > 0)
        {
            GroupedProfiles.Add(new ProfileGroupViewModel("Локальные профили", "Без подписки", localItems, null));
        }

        OnPropertyChanged(nameof(FilteredProfiles));
        OnPropertyChanged(nameof(GroupedProfiles));
    }

    public void UpdateTraffic(long rxRate, long txRate, long totalRx, long totalTx)
    {
        DownloadSpeed = $"{FormatBytes(rxRate)}/s";
        UploadSpeed = $"{FormatBytes(txRate)}/s";
        Speed = $"{FormatBytes(rxRate + txRate)}/s";
        Received = FormatBytes(totalRx);
        Sent = FormatBytes(totalTx);
    }

    public void ResetTraffic()
    {
        Speed = "0 B/s";
        DownloadSpeed = "0 B/s";
        UploadSpeed = "0 B/s";
        Received = "0 B";
        Sent = "0 B";
    }

    public static string FormatBytes(long bytes)
    {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{(bytes / 1024.0):F1} KB";
        if (bytes < 1024 * 1024 * 1024) return $"{(bytes / (1024.0 * 1024)):F1} MB";
        return $"{(bytes / (1024.0 * 1024 * 1024)):F2} GB";
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class ProfileGroupViewModel
{
    public string Title { get; }
    public string Subtitle { get; }
    public ObservableCollection<ServerProfile> Items { get; }
    public Subscription? Subscription { get; }
    public bool HasSubscription => Subscription != null;
    public int Count => Items.Count;

    public ProfileGroupViewModel(string title, string subtitle, IEnumerable<ServerProfile> items, Subscription? subscription)
    {
        Title = title;
        Subtitle = subtitle;
        Items = new ObservableCollection<ServerProfile>(items);
        Subscription = subscription;
    }
}
