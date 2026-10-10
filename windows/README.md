# NeoTUN for Windows

Первый этап Windows-клиента на C# / WPF (.NET 8). Ветка изолирована от Android-разработки.

## Уже реализовано

- Тёмное desktop-окно в стиле NeoTUN.
- Локальное хранение профилей в `%LOCALAPPDATA%\\NeoTUN\\profiles.json`.
- Импорт ссылок VLESS, VMess, Trojan, Hysteria2/Hy2, TUIC и Shadowsocks из текста или буфера обмена.
- Удаление профиля и защита от повторного импорта одинаковых ссылок.
- Импорт HTTP(S)-подписок с Base64-декодированием, ограничением размера и проверкой содержимого до замены старых серверов.
- Windows CI собирает self-contained Windows x64 и публикует ZIP-артефакт.

## Важно

Это начальный интерфейс и менеджер профилей, **не готовый клиент для передачи системного трафика**. Кнопка подключения намеренно отключена, пока не интегрирован и не проверен реальный Windows networking engine. Следующие этапы: подписки и QR, общий Rust parser/config API, затем реальный системный туннель/маршрутизация через Windows-совместимый движок. Статус «подключено» не имитируется.

## Сборка локально

Нужен Visual Studio 2022 с workload **.NET desktop development** или .NET 8 SDK на Windows:

```powershell
dotnet build windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release
dotnet run --project windows/NeoTUN.Windows/NeoTUN.Windows.csproj
```

Проверка сборки выполняется GitHub Actions на Windows runner; подключение и передача трафика пока не заявляются как реализованные.
