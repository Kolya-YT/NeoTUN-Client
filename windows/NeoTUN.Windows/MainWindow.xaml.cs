using System.Windows;
using System.Windows.Controls;
using NeoTUN.Windows.Models;
using NeoTUN.Windows.Services;
using NeoTUN.Windows.ViewModels;

namespace NeoTUN.Windows;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        ProfileList.SelectionChanged += (_, _) => SyncSelectedProfileToEditor();
        SyncSelectedProfileToEditor();
        AddLog("NeoTUN Windows UI initialized. Network engine is not integrated yet.");
        ShowPage("Главная");
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

    private void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.IsConnected)
        {
            _vm.IsConnected = false;
            _vm.Status = "Отключено";
        }
        else
        {
            _vm.Status = _vm.SelectedProfile is null
                ? "Сначала выберите или импортируйте сервер."
                : "Профиль готов. Windows TUN / сетевой runtime ещё не подключён.";
        }
        AddLog(_vm.Status);
    }

    private void ImportProfiles_Click(object sender, RoutedEventArgs e)
    {
        var parsed = SubscriptionImporter.Parse(ImportEditor.Text);
        if (parsed.Count == 0)
        {
            _vm.Notice = "Поддерживаемые ссылки не найдены. Проверьте формат или содержимое подписки.";
            AddLog(_vm.Notice);
            return;
        }
        _vm.AddProfiles(parsed);
        ImportEditor.Clear();
        ShowPage("Серверы");
        AddLog(_vm.Notice);
    }

    private void PasteImport_Click(object sender, RoutedEventArgs e)
    {
        try { ImportEditor.Text = Clipboard.GetText(); }
        catch (Exception ex) { _vm.Notice = "Не удалось прочитать буфер обмена: " + ex.Message; }
    }

    private void ClearImport_Click(object sender, RoutedEventArgs e) => ImportEditor.Clear();

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

    private void LoadRouting_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var json = System.Text.Json.JsonDocument.Parse(RoutingEditor.Text);
            _vm.Notice = "JSON синтаксически корректен. Применение к системному трафику ещё не реализовано.";
            AddLog("Routing JSON parsed.");
        }
        catch (System.Text.Json.JsonException ex) { _vm.Notice = "Ошибка JSON: " + ex.Message; }
    }

    private void CopyRouting_Click(object sender, RoutedEventArgs e) => Clipboard.SetText(RoutingEditor.Text);

    private void AddLog(string message)
    {
        if (LogsText is null) return;
        LogsText.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
        LogsText.ScrollToEnd();
    }
}
