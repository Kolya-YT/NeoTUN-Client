using System.Windows;
using System.Windows.Controls;
namespace NeoTUN.Windows;
public partial class MainWindow : Window
{
 private readonly ProfileStore _store = new();
 public MainWindow() { InitializeComponent(); ProfilesList.ItemsSource = _store.Profiles; UpdateCount(); if (_store.Profiles.Count > 0) ProfilesList.SelectedIndex = 0; }
 private async void Import_Click(object sender, RoutedEventArgs e)
 {
  var input = ImportText.Text.Trim();
  if (input.StartsWith("https://", StringComparison.OrdinalIgnoreCase) || input.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
  {
   ImportButton.IsEnabled = false; Feedback.Text = "Загружаю и проверяю подписку…";
   try { var count = await _store.ImportSubscriptionAsync(input); Feedback.Text = $"Подписка обновлена: {count} профилей."; ImportText.Clear(); UpdateCount(); }
   catch (Exception ex) { Feedback.Text = $"Не удалось обновить подписку: {ex.Message}"; }
   finally { ImportButton.IsEnabled = true; }
   return;
  }
  var added = _store.Import(input); Feedback.Text = added > 0 ? $"Импортировано профилей: {added}. Они сохранены локально." : "Поддерживаемые ссылки не найдены или все профили уже добавлены."; ImportText.Clear(); UpdateCount();
 }
 private void Paste_Click(object sender, RoutedEventArgs e) { try { if (Clipboard.ContainsText()) ImportText.Text = Clipboard.GetText(); else Feedback.Text = "В буфере обмена нет текста."; } catch (Exception ex) { Feedback.Text = $"Не удалось прочитать буфер обмена: {ex.Message}"; } }
 private void Delete_Click(object sender, RoutedEventArgs e) { if (ProfilesList.SelectedItem is not Profile selected) { Feedback.Text = "Сначала выбери профиль."; return; } _store.Remove(selected); UpdateCount(); Feedback.Text = "Профиль удалён."; }
 private void ProfilesList_SelectionChanged(object sender, SelectionChangedEventArgs e) { if (ProfilesList.SelectedItem is Profile selected) ConnectionStatus.Text = $"Выбран профиль: {selected.Name} · {selected.DisplayProtocol}"; else ConnectionStatus.Text = "Движок Windows ещё не подключён"; }
 private void UpdateCount() => ProfileCount.Text = $"{_store.Profiles.Count} серверов";
}
