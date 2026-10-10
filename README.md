# NeoTUN

NeoTUN — клиент для подключения к прокси-серверам на Android и Windows. Проект находится в активной разработке: интерфейс и сборки уже есть, но полноценная работа сетевого туннеля ещё проверяется, особенно на Windows.

## Скачать

**Последняя версия:** [открыть релиз NeoTUN](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)

### Android

Выберите APK под архитектуру устройства:

| Файл | Устройства |
| --- | --- |
| [NeoTUN-arm64-v8a.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-arm64-v8a.apk) | Большинство современных смартфонов и планшетов |
| [NeoTUN-armeabi-v7a.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-armeabi-v7a.apk) | Старые устройства с 32-битным ARM |
| [NeoTUN-x86_64.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-x86_64.apk) | Устройства и эмуляторы x86-64 |

Если APK не скачивается по прямой ссылке, откройте страницу [последнего релиза](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest) и выберите файл в разделе **Assets**.

### Windows 10/11, 64-разрядная версия

[**Скачать установщик NeoTUN-Setup-x64.exe**](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-Setup-x64.exe)

1. Скачайте EXE-файл и запустите его.
2. Подтвердите запуск, если Windows запросит разрешение.
3. После установки откройте NeoTUN из меню «Пуск».

Для обычной установки нужен именно **EXE-установщик**. ZIP из раздела сборок — не основной способ установки. Если Windows показывает предупреждение SmartScreen, перед запуском проверьте, что файл скачан со страницы официального релиза проекта.

## Что уже проверено

Здесь перечислены только результаты, которые подтверждаются сборками или проведёнными проверками. Успешная компиляция сама по себе не означает, что весь сетевой трафик работает без ошибок.

### Android

- Успешно собираются APK для ARM64, ARMv7 и x86-64.
- Сборка включает общий Rust Core и нативную интеграцию Xray.
- **VLESS TCP** и **VLESS XHTTP** проверялись на устройстве.
- В диагностике подтверждены ответы DNS и передача HTTPS-соединений в исходящее подключение Hysteria2. Полная проверка всего трафика Hysteria2 ещё продолжается.
- В приложении доступны импорт профилей, настройки маршрутизации и журнал диагностики.

### Windows

- Windows-клиент собирается для x64.
- Собирается установщик **NeoTUN-Setup-x64.exe** с приложением и необходимыми сетевыми компонентами.
- Установщик публикуется в разделе Releases вместе с Android APK.
- В приложении есть интерфейс серверов, профилей, маршрутизации, настроек и диагностики.

**Важно:** полноценная работа Windows-туннеля ещё не подтверждена. Успешная сборка установщика не означает, что подключение, DNS и весь TCP/UDP-трафик уже проверены на реальном компьютере.

## Что ещё в разработке

- **Windows-подключение:** запуск и остановка движка, TUN/Wintun, управление маршрутами и восстановление сети после отключения.
- **Сетевые тесты Windows:** проверка TCP и UDP, DNS, переподключения и поведения после сна или перезапуска приложения.
- **Маршрутизация:** проверка применения пользовательских правил на каждом поддерживаемом движке.
- **GeoSite и GeoIP:** загрузка, обновление и корректное применение геобаз в форматах, которые поддерживает конкретное ядро.
- **Протоколы:** отдельная проверка Hysteria2, TUIC и остальных вариантов транспорта на реальных серверах. Возможность импортировать ссылку не означает, что все её параметры уже поддерживаются.
- **Обновление приложения:** проверка обновления поверх установленной версии на разных устройствах и обработка ошибок установки.

Статусы будут меняться по мере прохождения практических проверок, а не только после успешной сборки.

## Если не работает подключение

1. Откройте в приложении раздел диагностики.
2. Проверьте, запускается ли выбранное ядро и появляются ли ответы DNS.
3. Проверьте несколько сайтов и приложений, а затем попробуйте отключить и снова включить подключение.
4. Если проблема остаётся, создайте [Issue](https://github.com/Kolya-YT/NeoTUN-Client/issues) и укажите версию приложения, устройство, протокол и шаги для повторения ошибки.

Перед отправкой журнала удалите UUID, пароли, токены, приватные ссылки подписок и другие данные доступа.

## Сборка из исходников

### Android

Используются JDK 17, Android SDK/NDK, Rust stable, `cargo-ndk`, Go и Gradle. Workflow сборки: [android.yml](.github/workflows/android.yml).

### Windows

Используются Windows x64, .NET 8 SDK, Rust stable с target `x86_64-pc-windows-msvc`, Visual Studio Build Tools с компонентами C++ и Inno Setup 6. Workflow сборки и установщика: [desktop.yml](.github/workflows/desktop.yml).

Базовые команды для Windows:

```powershell
rustup target add x86_64-pc-windows-msvc
cargo test --manifest-path core/Cargo.toml
cargo build --manifest-path core/Cargo.toml --release --target x86_64-pc-windows-msvc
dotnet restore windows/NeoTUN.Windows/NeoTUN.Windows.csproj
dotnet publish windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/NeoTUN-Windows
```

Эти команды собирают приложение и общий Rust Core. Для готового установщика дополнительно нужны сетевые движки, Wintun и упаковка через Inno Setup.

## Репозиторий

- `android/` — Android-приложение и VPN-сервис.
- `windows/NeoTUN.Windows/` — Windows-клиент.
- `core/` — общий Rust Core.
- `xraybridge/` — интеграция Xray для Android.
- `installer/` — сценарий установщика Windows.
- `.github/workflows/` — сборка, проверки и публикация релизов.

[Релизы](https://github.com/Kolya-YT/NeoTUN-Client/releases) · [Сборки](https://github.com/Kolya-YT/NeoTUN-Client/actions) · [Сообщить об ошибке](https://github.com/Kolya-YT/NeoTUN-Client/issues)
