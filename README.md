<div align="center">

# NeoTUN

**Один клиент. Ваши серверы. Гибкая маршрутизация.**

Кроссплатформенный проект клиента для Android и Windows с общим Rust Core и сетевыми движками Xray и sing-box.

[Скачать релиз](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest) · [Все сборки](https://github.com/Kolya-YT/NeoTUN-Client/actions) · [Сообщить об ошибке](https://github.com/Kolya-YT/NeoTUN-Client/issues)

![Android CI](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml/badge.svg)
![Windows CI](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml/badge.svg)

</div>

---

## 🚀 Скачать NeoTUN

### Windows 10/11 · x64

**Рекомендуемый файл — NeoTUN-Setup-x64.exe.** Это установщик Windows, а не архив: он устанавливает приложение и необходимые сетевые компоненты, создаёт ярлык и добавляет NeoTUN в список установленных программ.

1. Откройте страницу [последнего релиза](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest).
2. В разделе **Assets** скачайте **NeoTUN-Setup-x64.exe**.
3. Запустите установщик. Для настройки сетевого туннеля могут потребоваться права администратора.
4. Откройте NeoTUN из меню «Пуск» или с рабочего стола.

Если в последнем релизе установщика ещё нет, откройте [последние сборки Windows](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml), выберите успешный запуск и скачайте артефакт **NeoTUN-Setup-x64** внизу страницы. Для скачивания CI-артефактов может потребоваться вход в GitHub.

> **Важно:** Windows-клиент активно разрабатывается. Успешная сборка и наличие установщика не означают, что на каждом ПК уже проверена работа всего сетевого трафика. Перед повседневным использованием проверьте подключение, DNS, TCP/UDP и восстановление сети после отключения.

### Android

Откройте [последний релиз](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest) и скачайте APK, подходящий архитектуре устройства.

| APK | Для каких устройств |
|---|---|
| NeoTUN-arm64-v8a.apk | Большинство современных Android-смартфонов и планшетов |
| NeoTUN-armeabi-v7a.apk | Старые 32-битные ARM-устройства |
| NeoTUN-x86_64.apk | Совместимые устройства и эмуляторы x86-64 |

Если APK отсутствуют в Releases, проверьте [последнюю сборку Android](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml) и её артефакты. Не устанавливайте APK другой архитектуры наугад.

---

## ✨ Возможности

- **Серверы и подписки:** импорт ссылок и списков, несколько профилей, выбор сервера и обновление подписок.
- **Сетевые движки:** интеграция Xray и sing-box там, где это поддержано соответствующей сборкой.
- **Маршрутизация:** правила для доменов и IP/CIDR с действиями **Proxy**, **Direct** и **Block**.
- **DNS:** настройки резолвера, DNS over HTTPS и отдельный список доменов для домашнего DNS.
- **Настройки соединения:** DNS, IPv6 и MTU на Android; Windows-функции развиваются отдельно.
- **Диагностика:** журнал работы и сообщения об ошибках, помогающие проверять запуск и сетевой путь.
- **Общий код:** Rust Core используется для общей логики разбора профилей.

Доступность конкретной функции зависит от платформы, версии приложения, сетевого движка и параметров импортированной конфигурации.

## 🔌 Импортируемые протоколы

Клиент содержит обработчики следующих форматов ссылок:

- vless://
- vmess://
- trojan://
- hysteria2:// и hy2://
- tuic://
- ss://

**Обработчик ссылки не является гарантией совместимости со всеми транспортами и параметрами.** Например, XHTTP, WebSocket, gRPC, QUIC, специальные расширения серверов и дополнительные поля требуют проверки конкретной конфигурации. WireGuard и AmneziaWG не следует считать поддерживаемыми, если соответствующий движок и полноценный путь подключения не реализованы в используемой сборке.

## 🧭 Маршрутизация

Редактор профиля позволяет задавать списки доменов и IP/CIDR для трёх действий:

| Действие | Что делает |
|---|---|
| **Block** | Блокирует совпавший трафик |
| **Proxy** | Направляет совпавший трафик через прокси |
| **Direct** | Отправляет совпавший трафик напрямую |

Дополнительно доступны глобальный прокси, порядок групп правил, профили маршрутизации и настройки DNS. Поддержка GeoSite/GeoIP зависит от формата геоданных и движка: Xray использует совместимые .dat-файлы, а sing-box — поддерживаемые им rule-set. Нельзя считать, что любой токен geosite автоматически работает как обычный домен.

После изменения DNS или правил переподключите клиент, чтобы настройки применились к новой конфигурации.

## 🖥️ Windows: что входит в установщик

NeoTUN-Setup-x64.exe создаётся в CI из проверенной папки приложения. Установщик включает:

- **NeoTUN.exe** — графический клиент;
- **neotun_core.dll** — общий Rust Core;
- **runtime/sing-box.exe** и необходимые файлы движка;
- **runtime/xray.exe** и необходимые файлы движка;
- **runtime/wintun.dll** — компонент TUN для Windows.

Установщик нужен потому, что одного GUI-файла недостаточно: приложению требуются нативная библиотека и сетевые компоненты. После установки запускать отдельные DLL или файлы движков вручную не нужно.

## 📱 Android: что входит в сборку

Android-приложение использует системный VpnService и TUN-интерфейс, а нативные библиотеки собираются под несколько ABI. Для выпуска обновлений поверх установленной production-версии важна совместимая подпись APK. Сборка CI может завершиться успешно, даже если публикация релиза отдельно не прошла — в таком случае APK ищите в артефактах запуска.

## 🧱 Структура репозитория

    NeoTUN-Client/
    ├── android/                  Android UI, VPN-сервис и интеграция движков
    ├── core/                     Общий Rust Core
    ├── xraybridge/               Интеграция Xray для Android
    ├── windows/NeoTUN.Windows/   Windows WPF-клиент
    ├── installer/                Сценарий сборки Windows Setup EXE
    ├── .github/workflows/        CI для Android и Windows
    └── DESIGN_SYSTEM.md          Общие дизайн-токены

## 🛠️ Сборка Windows из исходников

### Требования

- Windows x64
- .NET 8 SDK
- Rust stable и target x86_64-pc-windows-msvc
- Visual Studio Build Tools с C++ workload
- Inno Setup 6 для создания установщика

### Сборка приложения

Запустите в PowerShell из корня репозитория:

    rustup target add x86_64-pc-windows-msvc
    cargo test --manifest-path core/Cargo.toml
    cargo build --manifest-path core/Cargo.toml --release --target x86_64-pc-windows-msvc
    dotnet restore windows/NeoTUN.Windows/NeoTUN.Windows.csproj
    dotnet publish windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/NeoTUN-Windows

Для полноценного дистрибутива также нужно поместить neotun_core.dll, sing-box.exe, xray.exe, wintun.dll и сопровождающие файлы в папку приложения. Затем установите Inno Setup 6 и выполните в PowerShell:

    & "$env:ProgramFiles(x86)\Inno Setup 6\ISCC.exe" "installer/NeoTUN-Windows.iss"

Готовый установщик появится по пути dist/NeoTUN-Setup-x64.exe.

## 🤖 Сборка Android из исходников

Основные инструменты CI:

- JDK 17
- Android SDK и NDK
- Rust stable и cargo-ndk
- Go для Xray bridge
- Gradle

CI workflow автоматизирует подготовку нативных компонентов, тестирование Rust Core, сборку APK и загрузку артефактов. Для production-подписи используются секреты GitHub Actions; приватный keystore и пароли нельзя коммитить в репозиторий.

## 🧪 Проверка и диагностика

Если приложение показывает состояние «Подключено», но сайты не открываются:

1. Проверьте, что конфигурация принята сетевым движком без ошибок.
2. Убедитесь, что DNS-запросы получают ответы.
3. Проверьте реальные TCP- и UDP-соединения, а не только статус подключения.
4. Проверьте несколько сайтов и приложений.
5. Отключите соединение и убедитесь, что системный VPN-индикатор исчез, а обычная сеть восстановилась.
6. Повторите тест после сна/пробуждения устройства и переподключения.

Перед отправкой журнала диагностики удалите UUID, пароли, токены подписок и другие секретные данные.

## 📦 Версии и релизы

- **Последний опубликованный релиз:** [GitHub Releases](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)
- **Android CI:** [сборки и логи](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml)
- **Windows CI:** [сборки и логи](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml)
- **Исходный код:** [ветка main](https://github.com/Kolya-YT/NeoTUN-Client/tree/main)
- **Дизайн-система:** [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- **Обсуждение проблем:** [GitHub Issues](https://github.com/Kolya-YT/NeoTUN-Client/issues)

Версия в main может быть новее последнего опубликованного релиза. Отдельно проверяйте результат CI: **зелёная сборка подтверждает успешную компиляцию и проверки workflow, но не заменяет тест реального сетевого трафика на устройстве.**

---

<div align="center">

**NeoTUN — активная разработка.**  
Спасибо, что тестируете клиент и сообщаете об ошибках.

</div>
