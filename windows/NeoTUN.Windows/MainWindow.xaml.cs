using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
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
        // Never claim a real connection until the Windows tunnel/runtime lifecycle
        // has been integrated and can confirm a successful start.
        _vm.IsConnected = false;
        _vm.Status = _vm.SelectedProfile is null
            ? "Сначала выберите или импортируйте сервер."
            : "Сервер выбран, но Windows TUN и запуск сетевого движка ещё не интегрированы. Трафик не перенаправляется.";
        AddLog(_vm.Status);
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
