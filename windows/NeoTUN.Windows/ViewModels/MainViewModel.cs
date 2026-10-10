using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using NeoTUN.Windows.Models;
using NeoTUN.Windows.Services;

namespace NeoTUN.Windows.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly ProfileStore _store = new();
    private ServerProfile? _selectedProfile;
    private string _status = "Готово к подключению";
    private string _activePage = "Главная";
    private bool _isConnected;
    private string _searchText = "";
    private string _importText = "";
    private string _notice = "Добавьте ссылку или импортируйте подписку.";

    public ObservableCollection<ServerProfile> Profiles { get; } = new();
    public ObservableCollection<ServerProfile> FilteredProfiles { get; } = new();

    public ServerProfile? SelectedProfile
    {
        get => _selectedProfile;
        set { _selectedProfile = value; OnPropertyChanged(); OnPropertyChanged(nameof(SelectedEndpoint)); OnPropertyChanged(nameof(SelectedProfileName)); OnPropertyChanged(nameof(SelectedProfileUri)); OnPropertyChanged(nameof(SelectedProfileProtocol)); }
    }

    public string SelectedEndpoint => SelectedProfile?.Endpoint ?? "Сервер не выбран";
    public string SelectedProfileName => SelectedProfile?.Name ?? "Сервер не выбран";
    public string SelectedProfileUri => SelectedProfile?.Uri ?? "";
    public string SelectedProfileProtocol => SelectedProfile?.Protocol ?? "—";
    public string Status { get => _status; set { _status = value; OnPropertyChanged(); OnPropertyChanged(nameof(ConnectionLabel)); } }
    public string ActivePage { get => _activePage; set { _activePage = value; OnPropertyChanged(); OnPropertyChanged(nameof(IsHomePage)); OnPropertyChanged(nameof(IsServersPage)); } }
    public bool IsHomePage => ActivePage == "Главная";
    public bool IsServersPage => ActivePage == "Серверы";
    public bool IsConnected { get => _isConnected; set { _isConnected = value; OnPropertyChanged(); OnPropertyChanged(nameof(ConnectionLabel)); OnPropertyChanged(nameof(ConnectionColorKey)); } }
    public string ConnectionLabel => !IsConnected ? "Отключено" : Status.StartsWith("Движки запущены", StringComparison.OrdinalIgnoreCase) ? "Запуск" : "Подключено";
    public string ConnectionColorKey => IsConnected ? "SuccessBrush" : "BrandVioletBrush";
    public string SearchText { get => _searchText; set { _searchText = value; OnPropertyChanged(); RefreshFilter(); } }
    public string ImportText { get => _importText; set { _importText = value; OnPropertyChanged(); } }
    public string Notice { get => _notice; set { _notice = value; OnPropertyChanged(); } }

    public MainViewModel()
    {
        foreach (var profile in _store.Load()) Profiles.Add(profile);
        RefreshFilter();
        SelectedProfile = Profiles.FirstOrDefault();
    }

    public void AddProfiles(IEnumerable<ServerProfile> items)
    {
        var added = 0;
        foreach (var item in items)
        {
            var existing = Profiles.FirstOrDefault(x => x.Uri == item.Uri);
            if (existing is null) { Profiles.Add(item); added++; }
            else { existing.Name = item.Name; existing.Source = item.Source; }
        }
        _store.Save(Profiles);
        RefreshFilter();
        if (SelectedProfile is null) SelectedProfile = Profiles.FirstOrDefault();
        else OnPropertyChanged(nameof(SelectedProfile));
        Notice = added > 0 ? $"Импортировано серверов: {added}" : "Новых серверов не найдено.";
    }

    public int ReplaceSubscriptionProfiles(string source, IEnumerable<ServerProfile> refreshed)
    {
        var incoming = refreshed.Select(profile =>
        {
            profile.Source = source;
            return profile;
        }).ToList();
        var incomingUris = incoming.Select(profile => profile.Uri).ToHashSet(StringComparer.Ordinal);
        var oldSelectedUri = SelectedProfile?.Uri;

        foreach (var old in Profiles.Where(profile => profile.Source == source && !incomingUris.Contains(profile.Uri)).ToList())
            Profiles.Remove(old);

        foreach (var item in incoming)
        {
            var existing = Profiles.FirstOrDefault(profile => profile.Uri == item.Uri);
            if (existing is null)
                Profiles.Add(item);
            else
            {
                existing.Name = item.Name;
                existing.Source = source;
                existing.UpdatedAt = DateTimeOffset.UtcNow;
            }
        }

        _store.Save(Profiles);
        RefreshFilter();
        SelectedProfile = Profiles.FirstOrDefault(profile => profile.Uri == oldSelectedUri)
            ?? Profiles.FirstOrDefault();
        OnPropertyChanged(nameof(SelectedProfile));
        return incoming.Count;
    }

    public bool RemoveSelected()
    {
        if (SelectedProfile is null) return false;
        var selected = SelectedProfile;
        Profiles.Remove(selected);
        SelectedProfile = Profiles.FirstOrDefault();
        _store.Save(Profiles);
        RefreshFilter();
        Notice = "Профиль удалён.";
        return true;
    }

    public void SaveProfiles() => _store.Save(Profiles);

    private void RefreshFilter()
    {
        FilteredProfiles.Clear();
        foreach (var profile in Profiles.Where(p =>
                     p.Name.Contains(SearchText, StringComparison.OrdinalIgnoreCase) ||
                     p.Endpoint.Contains(SearchText, StringComparison.OrdinalIgnoreCase) ||
                     p.Protocol.Contains(SearchText, StringComparison.OrdinalIgnoreCase)))
            FilteredProfiles.Add(profile);
        OnPropertyChanged(nameof(FilteredProfiles));
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
