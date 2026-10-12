using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace NeoTUN.Windows.Models;

public sealed class Subscription : INotifyPropertyChanged
{
    private string _name = "Подписка";
    private string _url = "";
    private DateTimeOffset? _lastUpdated;
    private int _profileCount;
    private bool _isUpdating;

    public string Id { get; set; } = Guid.NewGuid().ToString();

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); } }
    }

    public string Url
    {
        get => _url;
        set { if (_url != value) { _url = value; OnPropertyChanged(); } }
    }

    public DateTimeOffset? LastUpdated
    {
        get => _lastUpdated;
        set
        {
            if (_lastUpdated != value)
            {
                _lastUpdated = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(LastUpdatedFormatted));
            }
        }
    }

    public int ProfileCount
    {
        get => _profileCount;
        set { if (_profileCount != value) { _profileCount = value; OnPropertyChanged(); OnPropertyChanged(nameof(CountFormatted)); } }
    }

    public bool IsUpdating
    {
        get => _isUpdating;
        set { if (_isUpdating != value) { _isUpdating = value; OnPropertyChanged(); } }
    }

    public string LastUpdatedFormatted =>
        LastUpdated.HasValue
            ? $"Обновлено: {LastUpdated.Value.ToLocalTime():dd.MM.yyyy HH:mm}"
            : "Ещё не обновлялось";

    public string CountFormatted =>
        $"{ProfileCount} {(ProfileCount == 1 ? "сервер" : (ProfileCount is >= 2 and <= 4 ? "сервера" : "серверов"))}";

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
