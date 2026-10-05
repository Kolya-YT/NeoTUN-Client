# NeoTUN Client

Кроссплатформенный клиент с общим **ядром на Rust** и нативными сетевыми адаптерами для каждой платформы.

> 🚧 **Текущий статус:** Android уже перешёл к первой реальной интеграции сетевого движка. Подключён **sing-box libbox**, реализован Android TUN через `VpnService`, а Rust разбирает VLESS и генерирует конфигурацию sing-box. Проект всё ещё в активной разработке и пока не считается production-клиентом.

## 🎯 Цели проекта

NeoTUN создаётся как единый клиент с общим ядром и отдельными сетевыми адаптерами для разных платформ.

### 📱 Платформы

| Platform | Status | Planned networking layer |
|---|---|---|
| Android | 🟡 Active development | `VpnService` / TUN + sing-box libbox |
| Windows | ⚪ Planned | Wintun |
| iOS | ⚪ Planned | Network Extension |
| macOS | ⚪ Planned | Network Extension |
| Linux | ⚪ Planned | TUN |

Android — первая целевая платформа. Сначала тестируем ядро и модель профилей, после чего добавляем Windows, iOS и другие платформы.

## 🏗️ Архитектура

```text
┌──────────────────────────────────────────────┐
│                  NeoTUN UI                   │
│        Android / Windows / iOS / ...         │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│               NeoTUN Core — Rust             │
│                                              │
│  Profiles • Parser • Routing • State • API  │
└───────────────┬──────────────┬───────────────┘
                │              │
        ┌───────▼──────┐ ┌────▼─────────────┐
        │ Engine layer │ │ Platform adapter │
        │              │ │                  │
        │ sing-box     │ │ Android VpnService│
        │ Xray         │ │ Windows/Wintun   │
        │ WireGuard    │ │ iOS NetworkExt.  │
        │ AmneziaWG    │ │ Linux TUN        │
        │ OpenVPN      │ │                  │
        └──────────────┘ └──────────────────┘
```

Главное разделение ответственности: **Rust Core управляет профилями, конфигурацией, состоянием и ядрами**, а зрелые протокольные движки и системные API выполняют низкоуровневую сетевую работу.

## 🔌 План поддержки протоколов

Цель — широкая совместимость с протоколами, но протокол **не считается работающим**, пока его движок и полный путь передачи трафика не реализованы и не протестированы.

### Планируемая поддержка

- VLESS
- VMess
- Trojan
- Hysteria2
- TUIC
- Shadowsocks
- WireGuard
- AmneziaWG
- OpenVPN
- OpenFlux
- Additional formats supported by the selected engines

### Стратегия использования ядер

Проект намеренно не пытается реализовать каждый протокол с нуля на Rust.

Основной подход — интегрировать проверенные нативные движки там, где это целесообразно:

- **sing-box** — primary engine for a large set of modern protocols.
- **Xray-core** — compatibility path for Xray-based configurations.
- **WireGuard / AmneziaWG** — native/platform integration where appropriate.
- **OpenVPN** — dedicated native integration.
- **OpenFlux** — separate adapter/engine integration once its runtime requirements are finalized.

Так приложение остаётся быстрым и поддерживаемым, а сложная реализация протоколов не дублируется.

## 📥 Форматы конфигураций

Слой профилей будет использовать единую внутреннюю модель и парсеры распространённых форматов:

```text
vless://
vmess://
trojan://
ss://
hysteria2://
tuic://
WireGuard configuration
Amnezia configuration
Subscription URLs
QR codes
```

Предполагаемый поток:

```text
Link / QR / File / Subscription
              ↓
        Profile parser
              ↓
     Normalized Rust profile
              ↓
       Selected engine
              ↓
          TUN adapter
```

## 📱 Android — текущий фундамент

Текущее Android-приложение уже содержит:

- [x] Kotlin Android application
- [x] Rust native library
- [x] Rust ↔ Kotlin JNI bridge
- [x] Rust VLESS URI parser
- [x] Rust → sing-box JSON compiler для VLESS
- [x] Android `VpnService`
- [x] Android TUN adapter для libbox
- [x] sing-box `libbox` integration
- [x] ARM64 / ARMv7 / x86_64 builds
- [x] GitHub Actions APK build
- [x] Release APK artifact
- [x] In-app update checker
- [x] APK download/install flow
- [x] Profile persistence

### ⚠️ Важно

Это первая интеграция реального сетевого движка. Перед объявлением VLESS production-ready нужно проверить реальные подключения, DNS, TLS/Reality, WS/gRPC, IPv6, reconnect и корректное отключение TUN.

## 📂 Структура репозитория

```text
NeoTUN-Client/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/neotun/app/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── NeoTunCore.kt
│   │   │   │   └── NeoTunVpnService.kt
│   │   │   └── res/
│   │   ├── build.gradle.kts
│   │   └── ...
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── core/
│   ├── src/lib.rs
│   └── Cargo.toml
├── .github/workflows/
│   └── android.yml
├── Cargo.toml
└── README.md
```

## 🛠️ Локальная сборка

### Требования

- JDK 17
- Android SDK
- Android SDK Platform 35
- Rust stable
- Android Rust targets
- `cargo-ndk`
- Gradle 8.10.2

### Rust core

Установите Android targets:

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
```

Установите cargo-ndk:

```bash
cargo install cargo-ndk --locked
```

Соберите нативные библиотеки:

```bash
cd core

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release
```

Соберите APK:

```bash
cd ../android
gradle assembleDebug --no-daemon
```

Готовый APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 🤖 GitHub Actions

Каждый push запускает workflow сборки Android.

Workflow выполняет:

1. Checks out the repository.
2. Installs JDK 17.
3. Installs Gradle 8.10.2.
4. Installs the Rust Android targets.
5. Installs `cargo-ndk`.
6. Builds the Rust library for ARM64, ARMv7 and x86_64.
7. Builds the Android debug APK.
8. Publishes `NeoTUN-debug` as a GitHub Actions artifact.

Workflow использует Android SDK, уже установленный на GitHub Runner, и не устанавливает удалённый устаревший пакет `tools`.

## 🗺️ План разработки

### Этап 1 — Фундамент
- [x] Rust workspace
- [x] JNI bridge
- [x] Android project
- [x] Android VpnService/TUN foundation
- [x] Multi-ABI Rust build
- [x] GitHub Actions APK build

### Этап 2 — Реальное подключение
- [x] `vless://` parser
- [x] First real engine integration — sing-box libbox
- [x] Android TUN adapter
- [x] Rust → sing-box configuration
- [x] Profile persistence
- [ ] Сквозное VLESS-тестирование
- [ ] Connect/disconnect state machine
- [ ] Connection logs
- [ ] Traffic statistics
- [ ] Reconnection

### Этап 3 — Импорт профилей
- [ ] Unified profile model for all formats
- [ ] VMess parser
- [ ] Trojan parser
- [ ] Shadowsocks parser
- [ ] Hysteria2 parser
- [ ] TUIC parser
- [ ] Subscription parser
- [ ] QR import
- [ ] Import/export
- [ ] Profile/server list

### Этап 4 — Поддержка протоколов
- [ ] sing-box integration
- [ ] Xray integration
- [ ] Hysteria2
- [ ] TUIC
- [ ] Shadowsocks
- [ ] VMess
- [ ] Trojan
- [ ] WireGuard
- [ ] AmneziaWG
- [ ] OpenVPN
- [ ] OpenFlux

### Этап 5 — Возможности клиента
- [ ] Server/profile list
- [ ] Subscription auto-update
- [ ] Ping/latency
- [ ] Upload/download statistics
- [ ] DNS configuration
- [ ] Routing rules
- [ ] Split tunneling
- [ ] Kill switch
- [ ] Background connection
- [ ] Notifications
- [ ] QR scanner
- [ ] Import/export

### Этап 6 — Кроссплатформенность
- [ ] Windows + Wintun
- [ ] iOS + Network Extension
- [ ] macOS + Network Extension
- [ ] Linux + TUN

## 🔐 Принципы разработки

- **Rust-first:** общая логика и управление ядрами находятся в Rust.
- **Нативная производительность:** избегаем лишних уровней абстракции в сетевом пути.
- **Переиспользование ядер:** используем зрелые реализации вместо переписывания сложных криптографических и сетевых стеков.
- **Абстракция платформы:** Android-специфичная сеть остаётся за пределами общего ядра.
- **Единая модель профиля:** разные форматы конфигураций преобразуются в общую внутреннюю структуру.
- **Честный статус поддержки:** протокол считается поддержанным только после реального сквозного тестирования.
- **Безопасность прежде всего:** ключи, UUID, пароли и приватные конфигурации нельзя случайно записывать в логи.

## 🔄 Обновление приложения

В приложение встроен updater: он проверяет GitHub Releases, находит новый APK, загружает его и запускает стандартный Android installer.

После успешной сборки `main` GitHub Actions автоматически публикует Release с тегом из `versionName`: например `v0.1.0`. APK становится доступен в разделе Releases, откуда его также использует встроенный updater.

## 🏷️ Выпуск новой версии

1. Увеличить `versionCode` и `versionName` в `android/app/build.gradle.kts`.
2. Push в `main`.
3. GitHub Actions соберёт Release APK и автоматически обновит/создаст соответствующий GitHub Release.

Production signing подключается через Secrets `NEOTUN_KEYSTORE_BASE64`, `NEOTUN_KEYSTORE_PASSWORD`, `NEOTUN_KEY_ALIAS`, `NEOTUN_KEY_PASSWORD`. Если они не настроены, Release APK временно подписывается debug-ключом.

## 📊 Текущая версия

```text
NeoTUN Core 0.1.0
Android app 0.1.0
Статус: фундамент в разработке
```

## 📄 Лицензия

Смотрите файл [LICENSE](LICENSE).
