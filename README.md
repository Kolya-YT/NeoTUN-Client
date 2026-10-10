# NeoTUN Client

Android-клиент для подключения к серверам VLESS, VMess, Trojan, Hysteria2, TUIC и Shadowsocks. Проект использует Rust для разбора ссылок и подготовки конфигураций, sing-box для поддерживаемых им протоколов и Xray для VLESS-транспортов, которым нужен Xray.

> Проект находится в активной разработке. Наличие импорта протокола не означает, что он прошёл проверку реального трафика. Стабильность подтверждается отдельными тестами на устройстве.

## Возможности Android

- Подключение через Android `VpnService` и TUN.
- Импорт отдельных ссылок, Base64-списков и HTTP(S)-подписок.
- Хранение нескольких профилей и обновление подписок.
- Автоматический выбор сетевого движка для профиля.
- Статистика входящего и исходящего трафика.
- Настройки DNS, IPv6 и MTU.
- Диагностический журнал и проверка обновлений приложения.
- Тёмный интерфейс с адаптацией под экран устройства.

## Маршрутизация

- Импорт профилей JSON и Base64/deeplink формата INCY/Happ.
- Обработка deeplink-ссылок `incy://routing/...` и `happ://routing/...`.
- Импорт маршрутизации из HTTP-заголовков `routing` / `autorouting` и deeplink-строк в подписках.
- Правила доменов и IP/CIDR применяются через адаптеры sing-box и Xray; для `autorouting` предусмотрено обновление источника раз в 24 часа.
- Поддержка всех geosite/geoip-наборов, преобразование геофайлов для sing-box и профильные DNS-настройки ещё требуют отдельной реализации и проверки.

## Протоколы

| Протокол | Движок | Состояние |
|---|---|---|
| VLESS TCP | Xray | Проверен пользователем |
| VLESS XHTTP | Xray | Проверен пользователем |
| VLESS WS / gRPC / HTTP / HTTPUpgrade / SplitHTTP | Xray | Требуется отдельная проверка |
| VMess | sing-box | Адаптер реализован; end-to-end проверка не завершена |
| Trojan | sing-box | Адаптер реализован; end-to-end проверка не завершена |
| Hysteria2 | sing-box | Требуется проверка QUIC/UDP, включая port hopping |
| TUIC | sing-box | Требуется проверка QUIC/UDP |
| Shadowsocks | sing-box | Требуется end-to-end проверка |
| WireGuard / AmneziaWG | — | Не реализованы |

## Импорт и подписки

Поддерживаемые схемы ссылок:

```text
vless://
vmess://
trojan://
hysteria2://
hy2://
tuic://
ss://
```

Подписки могут содержать несколько серверов. Перед сохранением обновление сначала загружает и проверяет содержимое, затем заменяет профили только если найдены поддерживаемые ссылки. Ошибка загрузки или неподдерживаемая подписка не должна стирать ранее сохранённые профили. Новая подписка не сохраняется до успешной проверки и импорта профилей. Профили сопоставляются с исходной подпиской, чтобы не создавать дубликаты. Параметры порт-хоппинга Hysteria2 должны сохраняться при разборе ссылки.

## Сетевой путь

```text
Ссылка / подписка
       ↓
Rust Core: разбор и конфигурация
       ↓
Xray или sing-box
       ↓
Android VpnService / TUN
       ↓
DNS и пользовательский трафик
```

Для sing-box 1.14.x явный `dns_address` на TUN требует правила `hijack-dns` с условием `protocol: dns`. Правило без условия протокола может совпадать и с обычными соединениями, поэтому NeoTUN ограничивает перехват трафика на DNS-порт 53 и сохраняет это правило первым перед пользовательскими правилами. Для Hysteria2 не задаётся поле outbound `network: udp`: оно ограничивало proxy UDP и приводило к ошибке `TCP is not supported by outbound: proxy`, из-за которой обычный интернет-трафик не проходил.


## Интеграция Android TUN

Сетевой адаптер sing-box ориентируется на подход Hiddify: интерфейсы берутся из Android ConnectivityManager с реальными DNS, адресами, шлюзами и типом сети; VPN-интерфейс не передаётся ядру как физический outbound. Для Android 9+ используется поиск лучшей физической сети вместо слепого выбора VPN как default network. При auto_route пустой список IPv4-маршрутов не должен приводить к созданию TUN без маршрута.

## Сборка Android

### Требования

- JDK 17
- Android SDK и Platform 35
- Rust stable
- Android NDK r29
- `cargo-ndk`
- Gradle 8.10.2

Установите Android-цели Rust:

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
cargo install cargo-ndk --locked
```

Сборка Rust Core:

```bash
cd core
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release
```

Сборка release APK для поддерживаемых архитектур:

```bash
cd ../android
gradle assembleRelease --no-daemon --parallel --build-cache
```

Gradle создаёт отдельные APK для `arm64-v8a`, `armeabi-v7a` и `x86_64`. Каждый APK содержит только нативные библиотеки своей архитектуры, поэтому он заметно меньше универсального APK.

## GitHub Actions и подпись

Workflow `.github/workflows/android.yml` собирает Rust Core и нативный Xray-модуль, использует кэш Rust/Go/Gradle и публикует отдельные APK для Android ABI. Встроенная проверка обновлений выбирает APK под архитектуру устройства. Публикация требует постоянного production keystore.

Настройте следующие GitHub Actions Secrets:

```text
NEOTUN_KEYSTORE_BASE64
NEOTUN_KEYSTORE_PASSWORD
NEOTUN_KEY_ALIAS
NEOTUN_KEY_PASSWORD
```

Не добавляйте keystore и пароли в репозиторий. Для обновления установленного приложения подпись должна совпадать с предыдущей production-версией.

Сборки и артефакты: [GitHub Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions). Исходный код: [GitHub](https://github.com/Kolya-YT/NeoTUN-Client).

## Диагностика

Если подключение запускается, но сайты не открываются, проверьте по порядку:

1. Успешно ли разобрана ссылка и сформирован ли профиль.
2. Запускается ли выбранный движок без ошибки конфигурации.
3. Создан ли Android TUN.
4. Обрабатываются ли DNS-запросы.
5. Проходит ли реальный TCP- или UDP-трафик.
6. Работают ли отключение и повторное подключение.

Диагностический журнал доступен в приложении. Перед публикацией логов удаляйте адреса серверов, идентификаторы и другие чувствительные данные.

## Текущая версия

- Android: **0.7.8**
- `versionCode`: **60**
- Rust Core: **0.2.4**
- Движки Android: sing-box 1.14.1 и Xray
- Статус: активная разработка; требуется проверка реального трафика на устройствах

## Windows Desktop

Добавлен нативный WPF-интерфейс с той же палитрой из `DESIGN_SYSTEM.md`, навигацией, локальным хранением профилей, поиском, редактированием и импортом одиночных ссылок/Base64/HTTP(S)-подписок.

**Важно:** текущая Windows-сборка — это GUI milestone, а не готовый сетевой клиент. Windows TUN, service lifecycle, интеграция и запуск Xray/sing-box, реальная статистика и проверка соединения пока не реализованы. Кнопка подключения не заявляет успешный статус и прямо сообщает об этом ограничении.

- Проект: `windows/NeoTUN.Windows`
- Требуется .NET 8 SDK для сборки из исходников.
- Windows x64 GUI artifact: [GitHub Actions](https://github.com/Kolya-YT/NeoTUN-Client/actions/runs/38044323860)
- Последний Android release: [NeoTUN v0.7.6](https://github.com/Kolya-YT/NeoTUN-Client/releases/tag/v0.7.6)

## Маршрутизация\n\nПорядок групп маршрутизации читается из `RouteOrder` импортированного профиля (строка или массив); значения профиля не зашиваются в приложение. Правила `geosite:`/`geoip:` для Xray передаются движку как геоданные. Для sing-box такие токены не подменяются доменными именами: поддержка реальных sing-box rule-set будет добавлена отдельно.\n\n## Лицензия

Условия использования указаны в [LICENSE](LICENSE).


**Android DNS fallback (0.6.1):** для sing-box используется явный UDP DNS upstream (по умолчанию 1.1.1.1; можно выбрать Google или Quad9), а не локальный resolver, который на некоторых устройствах Android 16 возвращает `::1:53 connection refused`. Требуется проверить доступность выбранного DNS из сети пользователя.


**Отключение VPN (0.6.8):** команда остановки передаётся активному движку без немедленного вызова `stopService`, который мог прервать обработку команды. Оба сервиса удаляют foreground-уведомление и сбрасывают состояние при остановке.


**Единый интерфейс и маршрутизация (0.7.1):** редактор маршрутизации приведён к общим токенам `NeoTunDesign`; правила BLOCK/PROXY/DIRECT, глобальный прокси, порядок групп и DNS сохраняются в активном профиле. Пользовательские DNS endpoint-ы применяются в конфигурации sing-box; домашний DNS включается только для явно заданного списка `DomesticDNSDomains`. GeoSite/GeoIP можно принудительно обновить из редактора; загрузка идёт в фоне, а при ошибке старые файлы сохраняются.


- Android: **0.7.1** — унифицированный редактор маршрутизации, рабочее обновление GeoSite/GeoIP и применение DNS-параметров активного профиля.


### UI refresh 0.7.1

- Home screen redesigned around a compact brand header, a circular primary connect/disconnect control, compact live traffic metrics, and a smaller selected-server card.
- Bottom navigation is now a compact floating-style capsule with a clear selected state and reduced vertical footprint.
- Screen headings and spacing are scaled down to avoid oversized titles and wasted vertical space on narrow phones.
- This is a visual layout change; runtime behavior still needs verification on a real Android device.


**Routing UI (0.7.6):** исправлены системные отступы edge-to-edge для Android 15+, нижняя кнопка сохранения больше не должна уходить под навигационную панель, стрелка выбора порядка групп больше не занимает всю ширину строки; диалоги получили оформление в стиле NeoTUN.


### Hysteria2 routing fix (0.7.8)

- Custom profile rules are inserted before sing-box native fallback rules, so an early `route(proxy)` catch-all no longer masks Direct/Block/Proxy decisions.
- Xray-style `geosite:`/`geoip:` tokens are mapped to sing-box remote binary `.srs` rule-sets where supported; remote rule-set cache is enabled.
- DNS hijacking on port 53 remains first. Unsupported geodata labels are logged instead of silently being treated as domains.


**0.7.8:** sing-box GeoSite/GeoIP rule-sets now use the same RoscomVPN `.srs` data source as the user's Happ profile, including `twitch-ads`, `google-play`, `github`, `youtube`, `telegram`, and GeoIP `direct/whitelist/private`. Android Actions no longer cancels an in-progress build when a newer commit is pushed.


**0.8.0:** routing profiles that omit DNS fields now get working remote/domestic DoH defaults (Google 8.8.8.8 and Yandex 77.88.8.8), with `.ru`, `.su`, and `.рф` sent to domestic DNS. DoH TLS server names are set correctly for IP-based resolver URLs.


### Исправления маршрутизации 0.8.0

- Загрузки sing-box rule-set направляются через `direct`, чтобы не зависеть от прокси-маршрутизации при запуске туннеля.
- Исправлено соответствие `geosite:epicgames` и алиаса `geosite:epic-games` фактическому файлу `epicgames.srs`.
- Список поддерживаемых GeoSite-наборов приведён к опубликованным SRS-файлам проекта; неизвестные токены остаются явно неподдерживаемыми.


### 0.8.1
- Routing editor: constrain the order selector row to a stable height and keep the save action clear of Android system bars/keyboard.
- Windows CI: locate the built Rust core DLL from Cargo's actual output directory before staging the desktop runtime.
