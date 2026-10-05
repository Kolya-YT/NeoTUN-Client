# NeoTUN Client

Кроссплатформенный клиент с общим **ядром на Rust** и нативными сетевыми адаптерами для каждой платформы.

> 🚧 **Текущий статус:** разработка начинается с Android. В репозитории сейчас находится фундамент Rust/JNI и Android TUN/VpnService. Это **ещё не готовый клиент**, реальные подключения через протоколы пока не реализованы.

## 🎯 Цели проекта

NeoTUN создаётся как единый клиент с общим ядром и отдельными сетевыми адаптерами для разных платформ.

### 📱 Платформы

| Platform | Status | Planned networking layer |
|---|---|---|
| Android | 🟡 In development | `VpnService` / TUN |
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

- Kotlin Android application shell
- Rust native library
- Rust ↔ Kotlin JNI bridge
- Android `VpnService`
- TUN interface creation
- ARM64 Android build target
- ARMv7 Android build target
- x86_64 Android build target
- GitHub Actions APK build

### ⚠️ Важно

Текущий `VpnService` создаёт TUN-интерфейс, но **ещё не передаёт трафик через VLESS, sing-box, Xray или другое протокольное ядро**.

Поэтому текущую сборку нужно воспринимать как фундамент архитектуры, а не как готовый рабочий клиент.

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
- [ ] Unified profile model
- [ ] `vless://` parser
- [ ] Subscription parser
- [ ] QR import
- [ ] First real engine integration
- [ ] TUN → engine traffic forwarding
- [ ] Connect/disconnect state
- [ ] Connection logs
- [ ] Traffic statistics

### Этап 3 — Поддержка протоколов
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

### Этап 4 — Возможности клиента
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

### Этап 5 — Кроссплатформенность
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

## 📊 Текущая версия

```text
NeoTUN Core 0.1.0
Android app 0.1.0
Статус: фундамент в разработке
```

## 📄 Лицензия

Смотрите файл [LICENSE](LICENSE).
