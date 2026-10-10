# NeoTUN

Клиент для подключения к собственным прокси-серверам на Android и Windows. Проект находится в активной разработке.

- [Последний релиз](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)
- [Все сборки и логи CI](https://github.com/Kolya-YT/NeoTUN-Client/actions)
- [Сообщить об ошибке](https://github.com/Kolya-YT/NeoTUN-Client/issues)

## Скачать

### Android

Откройте [Releases](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest) и выберите APK под архитектуру устройства:

| Файл | Архитектура |
|---|---|
| `NeoTUN-arm64-v8a.apk` | Большинство современных телефонов и планшетов |
| `NeoTUN-armeabi-v7a.apk` | Устройства с 32-битным ARM |
| `NeoTUN-x86_64.apk` | Совместимые x86-64 устройства и эмуляторы |

Если APK не опубликованы, откройте [сборки Android](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml), выберите успешный запуск и скачайте файлы из **Artifacts**. Для скачивания артефактов может потребоваться вход в GitHub.

### Windows 10/11, x64

Основной установочный файл — **`NeoTUN-Setup-x64.exe`**. ZIP не является заменой установщику.

1. Скачайте EXE из [последнего релиза](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest).
2. Запустите установщик и подтвердите запросы Windows. Для установки сетевых компонентов могут потребоваться права администратора.
3. Запустите NeoTUN из меню «Пуск».

Если EXE пока не появился в релизе, откройте [сборки Windows](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml), выберите успешный запуск и скачайте артефакт `NeoTUN-Setup-x64`. Артефакты CI хранятся ограниченное время.

## Что проверено

В автоматических сборках проверяются отдельные этапы, а не все сценарии использования приложения.

- **Android, сборка #404:** Rust Core и нативный движок Xray собраны, APK для поддерживаемых ABI подготовлены и загружены в артефакты.
- **Публикация #404:** завершилась ошибкой GitHub API `403 Resource not accessible by integration`. APK собраны, но этот запуск не опубликовал их в Releases.
- **Windows:** workflow собирает приложение, общий Rust Core, сетевые движки и установщик. Успешный запуск CI подтверждает только те этапы, которые отмечены зелёными в конкретном запуске.

Компиляция не доказывает, что весь трафик работает на реальном телефоне или ПК. Не считайте функции проверенными, если для них нет отдельного теста.

## Возможности

Набор функций зависит от платформы, версии приложения и выбранного движка.

- Импорт серверных профилей и подписок.
- Разбор ссылок VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC.
- Интеграция Xray и sing-box в соответствующих сборках.
- Маршрутизация по доменам и IP/CIDR с действиями Proxy, Direct и Block.
- Настройки DNS, включая DoH, там, где они поддерживаются активным движком.
- Журналы диагностики запуска и сетевых ошибок.

Поддержка формата ссылки не гарантирует совместимость со всеми транспортами и параметрами сервера. Каждую конфигурацию необходимо проверять отдельно.

## В разработке и на проверке

- Полный жизненный цикл Windows-подключения: запуск и остановка движка, TUN/Wintun, DNS, обработка ошибок и восстановление сети.
- Проверка реального TCP- и UDP-трафика на Windows, включая переподключение и выход из приложения.
- Надёжная публикация единого релиза с APK и Windows-установщиком.
- Проверка обновления поверх предыдущей версии и целостности загружаемых файлов.
- Согласованная маршрутизация GeoSite/GeoIP между Xray и sing-box: форматы геоданных у движков различаются.
- Проверка протоколов и нестандартных параметров подписок на реальных серверах.

Эти пункты нельзя считать завершёнными до прохождения соответствующих тестов.

## Диагностика

Если приложение показывает «Подключено», но сайты не открываются:

1. Проверьте журнал запуска сетевого движка.
2. Убедитесь, что DNS-запросы получают ответы.
3. Проверьте несколько сайтов и приложений.
4. Проверьте TCP и UDP отдельно.
5. Отключите соединение и убедитесь, что индикатор VPN исчез, а обычная сеть восстановилась.

Перед отправкой журнала удалите ссылки подписок, UUID, пароли и токены.

## Сборка из исходников

### Android

Для сборки используются JDK 17, Android SDK/NDK, Rust stable, `cargo-ndk`, Go и Gradle. Автоматизация находится в [android.yml](.github/workflows/android.yml). Для production APK в GitHub Actions должны быть настроены секреты подписи.

### Windows

Нужны Windows x64, .NET 8 SDK, Rust stable с target `x86_64-pc-windows-msvc`, Visual Studio Build Tools с C++ workload и Inno Setup 6.

Из корня репозитория в PowerShell:

```powershell
rustup target add x86_64-pc-windows-msvc
cargo test --manifest-path core/Cargo.toml
cargo build --manifest-path core/Cargo.toml --release --target x86_64-pc-windows-msvc
dotnet restore windows/NeoTUN.Windows/NeoTUN.Windows.csproj
dotnet publish windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/NeoTUN-Windows
```

Для установщика нужны также `neotun_core.dll`, `sing-box.exe`, `xray.exe` и `wintun.dll`. Windows workflow подготавливает эти файлы и собирает `NeoTUN-Setup-x64.exe`.

## Структура проекта

- `android/` — Android-приложение и VPN-сервис.
- `core/` — общий Rust Core.
- `xraybridge/` — интеграция Xray для Android.
- `windows/NeoTUN.Windows/` — Windows-клиент.
- `installer/` — сценарий установщика Windows.
- `.github/workflows/` — автоматические сборки и публикация.

NeoTUN распространяется в процессе разработки. Перед установкой проверяйте статус сборки и список файлов в релизе.
