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

## Протоколы

| Протокол | Движок | Состояние |
|---|---|---|
| VLESS TCP / WS / gRPC / HTTP / HTTPUpgrade / XHTTP / SplitHTTP | Xray | Требуется проверка на реальном сервере |
| VLESS XHTTP / SplitHTTP | Xray | Требуется проверка на реальном сервере |
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

Подписки могут содержать несколько серверов. При обновлении профили должны сопоставляться с исходной подпиской, чтобы не создавать дубликаты. Параметры порт-хоппинга Hysteria2 должны сохраняться при разборе ссылки.

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

Для sing-box 1.14.x явный `dns_address` на TUN требует правила маршрутизации `hijack-dns`. Это правило должно присутствовать в создаваемой конфигурации вместе с DNS-сервером, иначе DNS-запросы могут не попадать в DNS-модуль sing-box.

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

Сборка APK:

```bash
cd ../android
gradle assembleDebug --no-daemon
```

Debug APK создаётся по пути:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions и подпись

Workflow `.github/workflows/android.yml` собирает Rust Core, нативный Xray-модуль и APK для Android ABI. Публикация release APK требует постоянного production keystore.

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

- Android: **0.5.0**
- `versionCode`: **30**
- Rust Core: **0.2.4**
- Движки: sing-box 1.14.1 и Xray
- Статус: активная разработка

## Лицензия

Условия использования указаны в [LICENSE](LICENSE).
