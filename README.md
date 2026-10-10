# NeoTUN Client

**NeoTUN** — клиент для Android и Windows, который находится в активной разработке. Репозиторий содержит Android-приложение, общий Rust Core и раннюю Windows Desktop-версию.

> **Важно:** успешная сборка не равна подтверждённой работе сетевого туннеля. Протоколы и маршрутизацию необходимо проверять на реальном устройстве. Windows-версия особенно экспериментальная: наличие UI, движков в пакете или успешной CI-сборки само по себе не подтверждает полноценную работу TUN, DNS, TCP/UDP и очистку маршрутов.

## Скачать

- **[Последний опубликованный релиз](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)** — только версии, успешно опубликованные в Releases.
- [Все сборки GitHub Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions) — статусы, логи и CI-артефакты.
- [Последняя успешная сборка Windows (#93)](https://github.com/Kolya-YT/NeoTUN-Client/actions/runs/38046053861) — ZIP доступен в разделе **Artifacts** запуска.
- [Сборки Android APK](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml) — APK по архитектурам.
- [Исходный код](https://github.com/Kolya-YT/NeoTUN-Client) — текущая версия в `main` может быть новее опубликованного релиза.

### Android APK

CI собирает отдельные APK для следующих ABI:

| Файл | Архитектура |
|---|---|
| `NeoTUN-arm64-v8a.apk` | Большинство современных Android-смартфонов |
| `NeoTUN-armeabi-v7a.apk` | 32-битные ARM-устройства |
| `NeoTUN-x86_64.apk` | Android-устройства и эмуляторы x86_64 |

Выбирайте APK по архитектуре устройства. Не устанавливайте APK другой архитектуры, если не уверены в совместимости.

Для production-сборки требуются GitHub Actions Secrets с постоянным ключом подписи. Без них workflow намеренно не публикует APK, который нельзя гарантированно установить поверх существующей production-версии.

**Статус сборки Android #403:** APK были собраны и загружены как CI-артефакты, но шаг публикации GitHub Release завершился HTTP 403 (`Resource not accessible by integration`). Поэтому собранный APK не обязательно появился на странице Releases. Проверяйте артефакты конкретного запуска и номер версии внутри него.

### Windows Desktop

Windows workflow собирает WPF-приложение для **Windows x64** и подготавливает архив `NeoTUN-Windows-x64.zip` с приложением и сетевыми компонентами, если все проверки прошли. Артефакт появляется только после успешного завершения workflow.

Последняя успешная сборка Windows Desktop — [workflow #93](https://github.com/Kolya-YT/NeoTUN-Client/actions/runs/38046053861): Rust Core, WPF-приложение и проверка состава архива прошли успешно. Скачайте `NeoTUN-Windows-x64` в разделе **Artifacts** этого запуска. Это CI-артефакт, а не автоматически опубликованный GitHub Release.

Если загрузка сетевых компонентов или проверка состава архива завершается ошибкой, Windows ZIP не публикуется. История запусков и логи доступны в [Windows Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml).

## Возможности Android

- Подключение через Android `VpnService` и TUN.
- Импорт одиночных ссылок, Base64-списков и HTTP(S)-подписок.
- Несколько профилей серверов и обновление подписок.
- Выбор сетевого движка для поддерживаемого профиля.
- Отображение статистики трафика.
- Настройки DNS, IPv6 и MTU.
- Диагностический журнал, проверка обновлений и тёмный интерфейс.
- Редактор маршрутизации с правилами доменов и IP/CIDR.
- Порядок групп BLOCK / PROXY / DIRECT.
- DNS over HTTPS и список доменов для домашнего DNS.
- GeoSite/GeoIP rule-set для поддерживаемых правил sing-box и геоданные Xray.

## Протоколы

Импорт ссылки и наличие адаптера не означают, что каждый транспорт проверен end-to-end. Текущее состояние:

| Протокол | Основной движок | Статус |
|---|---|---|
| VLESS TCP | Xray | Работоспособность проверялась; требуется регрессионная проверка после изменений |
| VLESS XHTTP | Xray | Реализована поддержка; проверяйте конкретную конфигурацию |
| VLESS WS / gRPC / HTTP / HTTPUpgrade | Xray | Требуется проверка конкретного транспорта |
| VMess | sing-box | Адаптер реализован; требуется end-to-end проверка |
| Trojan | sing-box | Адаптер реализован; требуется end-to-end проверка |
| Hysteria2 | sing-box | Проверяйте QUIC/UDP, DNS и port hopping |
| TUIC | sing-box | Требуется проверка QUIC/UDP |
| Shadowsocks | sing-box | Требуется end-to-end проверка |
| WireGuard / AmneziaWG | — | Не заявлены как реализованные |

Поддерживаемые схемы импорта:

```text
vless://
vmess://
trojan://
hysteria2://
hy2://
tuic://
ss://
```

Подписка может содержать несколько серверов. Обновление должно проверять загруженное содержимое до замены существующих профилей, не удалять рабочие данные при сетевой ошибке и избегать повторного добавления уже известных серверов.

## Маршрутизация и DNS

Редактор профиля поддерживает:

- **BLOCK** — блокировка доменов и IP/CIDR.
- **PROXY** — отправка совпавшего трафика через прокси.
- **DIRECT** — прямое соединение.
- Глобальный прокси и настраиваемый порядок групп.
- Ручные списки доменов и IP/CIDR.
- Импорт JSON и поддерживаемых routing deeplink-профилей INCY/Happ.
- URL GeoSite/GeoIP и обновление файлов для соответствующего движка.
- Удалённый DoH и домашний DoH с отдельным списком доменов.

DNS-перехват на порту 53 должен оставаться перед пользовательскими правилами. Для sing-box геоправила используют поддерживаемые бинарные rule-set; неизвестные токены нельзя считать обычными доменными именами. Для Xray используются совместимые GeoSite/GeoIP-данные.

Настройки маршрутизации и DNS применяются при запуске/переподключении туннеля. После изменения профиля отключите и снова подключите клиент.

## Как устроен проект

```text
NeoTUN-Client/
├── android/          Android-приложение, UI, TUN и сервисы
├── core/             Общий Rust Core и разбор профилей
├── xraybridge/       Сборка/интеграция Xray для Android
├── windows/          Windows WPF Desktop-клиент
├── .github/workflows CI-сборки Android и Windows
└── DESIGN_SYSTEM.md  Общие токены интерфейса
```

Общий поток Android-подключения:

```text
Ссылка / подписка
       ↓
Rust Core: разбор профиля
       ↓
Xray или sing-box
       ↓
Android VpnService / TUN
       ↓
DNS и пользовательский TCP/UDP-трафик
```

Windows использует WPF UI и общий Rust Core. Windows TUN, жизненный цикл движков, маршруты и DNS всё ещё требуют полноценной проверки на реальном ПК, включая отключение, повторное подключение, сон/пробуждение и восстановление сетевого состояния.

## Сборка Android из исходников

### Требования

- JDK 17
- Android SDK Platform 35
- Android NDK r29
- Rust stable и `cargo-ndk`
- Go stable для сборки Xray bridge
- Gradle 8.10.2

Установите Android-цели Rust:

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
cargo install cargo-ndk --locked
```

Соберите Rust Core:

```bash
cd core
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release
```

Сборка APK также требует подготовленных нативных библиотек Xray bridge. CI выполняет этот шаг автоматически. После подготовки библиотек выполните:

```bash
cd android
gradle assembleRelease --no-daemon --parallel --build-cache
```

## Сборка Windows из исходников

### Требования

- Windows x64
- .NET 8 SDK
- Rust stable с target `x86_64-pc-windows-msvc`
- Visual Studio Build Tools с C++ workload

```powershell
rustup target add x86_64-pc-windows-msvc
cargo test --manifest-path core/Cargo.toml
cargo build --manifest-path core/Cargo.toml --release --target x86_64-pc-windows-msvc
dotnet restore windows/NeoTUN.Windows/NeoTUN.Windows.csproj
dotnet publish windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/NeoTUN-Windows
```

Этот набор команд собирает UI и Rust Core. Для готового дистрибутива дополнительно требуются сетевые runtime-компоненты, которые подготавливает Windows workflow.

## GitHub Actions и подпись Android

Для production-сборки Android workflow ожидает следующие Secrets:

```text
NEOTUN_KEYSTORE_BASE64
NEOTUN_KEYSTORE_PASSWORD
NEOTUN_KEY_ALIAS
NEOTUN_KEY_PASSWORD
```

Не коммитьте keystore, пароли или приватные ключи в репозиторий. При выпуске обновления Android package должен иметь тот же application ID и совместимую подпись, что и установленная версия.

Windows workflow публикует архив только после успешной сборки приложения, размещения сетевых компонентов и проверки состава архива. Если шаг падает, скачиваемый дистрибутив не публикуется.

## Диагностика

Если клиент показывает подключение, но интернет не работает:

1. Проверьте, что профиль импортирован и конфигурация движка принята без ошибок.
2. Убедитесь, что TUN создан и системный значок VPN появился.
3. Проверьте в журнале DNS: должны быть видны запросы и ответы, а не только попытки подключения.
4. Убедитесь, что реальные TCP- и UDP-соединения доходят до нужного outbound.
5. Проверьте сайты и приложения, а не только статус «Подключено».
6. Нажмите «Отключить» и проверьте, что VPN исчез и трафик восстановился через обычную сеть.
7. После изменения DNS или правил маршрутизации переподключитесь и повторите проверку.

Перед отправкой диагностического журнала удаляйте адреса серверов, UUID, пароли, токены подписок и другие секреты.

## Текущая версия

- **Android в исходниках `main`:** 0.8.1
- **Android versionCode:** 61
- **Последний опубликованный релиз:** сверяйте с [GitHub Releases](https://github.com/Kolya-YT/NeoTUN-Client/releases); версия исходников может опережать публикацию.
- **Android SDK:** minSdk 26, targetSdk 35
- **sing-box Android dependency:** 1.14.1
- **Windows:** экспериментальная WPF Desktop-версия; архив #93 успешно собран, но работу TUN и реальный TCP/UDP-трафик ещё нужно проверить на ПК
- **Статус:** активная разработка; готовность сетевого трафика необходимо подтверждать на реальных устройствах

## Участие в разработке

Перед отправкой изменений запускайте доступные тесты Rust и проверяйте соответствующий GitHub Actions workflow. Изменения маршрутизации должны сохранять приоритет DNS hijack, не ломать пользовательский порядок правил и не подменять неподдерживаемые геотокены обычными доменами.

- [Исходный код](https://github.com/Kolya-YT/NeoTUN-Client)
- [Последние релизы](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)
- [Android Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml)
- [Windows Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml)
- [Система дизайна](DESIGN_SYSTEM.md)
- [Лицензия](LICENSE)
