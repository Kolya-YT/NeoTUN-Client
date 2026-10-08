# NeoTUN Client 🚀

**NeoTUN** — современный мультипротокольный клиент с единым ядром на **Rust**, Android TUN и нативными сетевыми движками.

Проект создаётся с нуля как быстрый и лёгкий клиент для Android с дальнейшей поддержкой Windows, iOS, macOS и Linux.

> ⚠️ Проект находится в активной разработке. Android — основная платформа на текущем этапе. Поддержка протокола считается стабильной только после сквозного тестирования реального трафика.

---

## ✨ Что умеет NeoTUN

### 📱 Android

- 🔐 Android `VpnService` + TUN
- 🦀 Rust Core
- ⚡ sing-box / libbox
- 🚀 Xray / libXray
- 🔄 автоматический выбор движка
- 📋 несколько профилей
- 📡 подписки
- 🔄 обновление подписок
- 📥 импорт ссылок и Base64
- 📦 JSON-импорт
- 📊 статистика входящего/исходящего трафика
- 📶 текущая скорость
- 🏓 проверка ping
- 🔁 переподключение
- 🧪 диагностика запуска
- 🔄 встроенная проверка обновлений приложения
- 📱 адаптивный интерфейс

---

# 🔌 Протоколы

NeoTUN использует адаптерную архитектуру: приложение не пытается реализовать каждый сетевой протокол самостоятельно.

### Уже реализован слой разбора

| Протокол | Импорт | Движок | Статус |
|---|---:|---|---|
| VLESS | ✅ | sing-box / Xray | 🟡 активно тестируется |
| VLESS + XHTTP | ✅ | Xray | 🟡 активно тестируется |
| VMess | ✅ | sing-box | 🟡 адаптер |
| Trojan | ✅ | sing-box | 🟡 адаптер |
| Hysteria2 | ✅ | sing-box | 🟡 адаптер |
| TUIC | ✅ | sing-box | 🟡 адаптер |
| Shadowsocks | ✅ | sing-box | 🟡 адаптер |
| WireGuard | 🚧 | native | следующий этап |
| AmneziaWG | 🚧 | native | следующий этап |
| OpenVPN | 🚧 | native | следующий этап |
| OpenFlux | 🚧 | adapter | следующий этап |

> 🟡 «Адаптер» означает, что формат и конфигурационный путь уже закладываются в архитектуру. Перед объявлением протокола стабильным требуется реальный end-to-end тест.

---

# 📡 Подписки

NeoTUN постепенно переходит от модели «одна ссылка → один профиль» к полноценной системе подписок.

Поддерживаемый сценарий:

```text
Subscription URL
       ↓
Download
       ↓
Decode / parse
       ↓
Detect protocol
       ↓
Rust Core
       ↓
Profiles
       ↓
Selected engine
       ↓
Android TUN
```

Подписка может содержать несколько серверов.

### Сейчас

- ✅ сохранение подписки
- ✅ URL подписки
- ✅ Base64 subscription
- ✅ обычный текстовый список ссылок
- ✅ импорт нескольких серверов
- ✅ VLESS
- ✅ VMess
- ✅ Trojan
- ✅ Hysteria2
- ✅ TUIC
- ✅ Shadowsocks
- ✅ ручное обновление
- ✅ автоматическое обновление
- 🚧 управление подписками в полноценном UI
- 🚧 QR subscription import

Автообновление сейчас выполняется примерно раз в **12 часов**.

---

# 📥 Импорт

Кнопка **+** на главном экране открывает единое меню:

```text
Импорт

├─ Добавить подписку
├─ Вставить из буфера обмена
├─ QR-код
├─ Ручной ввод
└─ Импорт JSON
```

### Поддерживаемые ссылки

```text
vless://
vmess://
trojan://
hysteria2://
hy2://
tuic://
ss://
```

QR-сканер находится в ближайшем этапе разработки.

---

# 🧠 Архитектура

Главный принцип NeoTUN:

**UI ≠ сетевой движок ≠ платформенный TUN.**

```text
┌───────────────────────────────────────┐
│               NeoTUN UI               │
│              Android / ...             │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│          NeoTUN Core — Rust            │
│                                       │
│ Parser • Profiles • Protocols • State │
│ Config • Engine selection • JNI       │
└───────────────┬───────────────┬───────┘
                │               │
        ┌───────▼──────┐  ┌────▼──────────┐
        │    Engines   │  │ Platform TUN  │
        │              │  │               │
        │ sing-box     │  │ Android VPN   │
        │ Xray         │  │ Windows       │
        │ WireGuard    │  │ iOS           │
        │ OpenVPN      │  │ Linux         │
        └──────────────┘  └───────────────┘
```

### Почему Rust?

Rust используется как общий слой приложения:

- высокая производительность;
- низкое потребление ресурсов;
- единая логика для разных платформ;
- безопасная работа с памятью;
- удобный JNI/FFI слой;
- возможность переиспользовать Core на Windows/iOS/Linux.

При этом сложные сетевые движки не переписываются без необходимости.

---

# ⚙️ Выбор движка

NeoTUN автоматически выбирает подходящий движок.

Пример:

```text
VLESS TCP
   ↓
sing-box

VLESS Reality
   ↓
sing-box / Xray

VLESS XHTTP
   ↓
Xray

Hysteria2
   ↓
sing-box

TUIC
   ↓
sing-box
```

Это позволяет не привязывать весь клиент к одному сетевому ядру.

---

# 📱 Android UI

Интерфейс развивается в сторону удобства **HAPP-подобной модели управления**, но с собственным дизайном NeoTUN.

Главный экран:

```text
        ⚙          NeoTUN          +

              ┌─────────┐
              │   ⏻    │
              └─────────┘

             Подключено
              Нидерланды

       ↓ 12.4 MB   ↑ 2.1 MB   1.4 MB/s

┌──────────────────────────────────────┐
│ NeoTUN                         6 проф.│
├──────────────────────────────────────┤
│ 🇩🇪  Ютуб без рекламы              ✓ │
│     VLESS • TCP • REALITY             │
├──────────────────────────────────────┤
│ 🇳🇱  Нидерланды                     › │
│     VLESS • TCP • REALITY             │
├──────────────────────────────────────┤
│ 🇫🇮  Финляндия                      › │
│     VLESS • XHTTP • REALITY           │
└──────────────────────────────────────┘
```

Основная цель — минимум экранов и действий для обычного подключения.

---

# 📊 Статистика

NeoTUN получает счётчики непосредственно с VPN-интерфейса.

Отображаются:

- ↓ получено;
- ↑ отправлено;
- текущая скорость;
- активный TUN;
- состояние подключения.

В будущем:

- график скорости;
- статистика за день;
- статистика за месяц;
- статистика по профилям.

---

# 🏓 Диагностика

В приложение встроен диагностический журнал.

Он позволяет проверить:

```text
JNI
 ↓
DNS
 ↓
Android VpnService
 ↓
TUN
 ↓
Profile parser
 ↓
Engine
 ↓
Configuration
 ↓
Core startup
 ↓
Traffic
```

Секретные параметры профиля не должны попадать в диагностический лог.

---

# 🗂️ Структура проекта

```text
NeoTUN-Client/
│
├── android/
│   └── app/
│       └── src/main/
│           ├── java/com/neotun/app/
│           │   ├── MainActivity.kt
│           │   ├── NeoTunCore.kt
│           │   ├── SubscriptionStore.kt
│           │   ├── ProfileStore.kt
│           │   ├── NeoTunDiagnostics.kt
│           │   └── ...
│           │
│           └── jniLibs/
│
├── core/
│   ├── src/
│   │   └── lib.rs
│   └── Cargo.toml
│
├── xraybridge/
│   ├── main.go
│   └── bridge.c
│
├── .github/
│   └── workflows/
│       └── android.yml
│
├── Cargo.toml
└── README.md
```

---

# 🛠️ Локальная сборка

## Требования

- JDK 17
- Android SDK
- Android SDK Platform 35
- Rust stable
- Android Rust targets
- cargo-ndk
- Gradle 8.10.2

### Android targets

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
```

### cargo-ndk

```bash
cargo install cargo-ndk --locked
```

### Rust Core

```bash
cd core

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release
```

### APK

```bash
cd ../android
gradle assembleDebug --no-daemon
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

---

# 🤖 GitHub Actions

GitHub Actions автоматически:

1. собирает Rust Core;
2. собирает ARM64;
3. собирает ARMv7;
4. собирает x86_64;
5. собирает Xray native bridge;
6. собирает Android APK;
7. подписывает release production-keystore;
8. публикует artifacts;
9. создаёт GitHub Release на `main`.

Для нормальных обновлений Android используется **постоянный production keystore**.

### Secrets

```text
NEOTUN_KEYSTORE_BASE64
NEOTUN_KEYSTORE_PASSWORD
NEOTUN_KEY_ALIAS
NEOTUN_KEY_PASSWORD
```

⚠️ Никогда не помещайте keystore, пароль или приватные ключи непосредственно в Git.

---

# 🔄 Обновление приложения

В NeoTUN встроен updater.

Поток:

```text
GitHub Release
      ↓
Check latest version
      ↓
Compare versionName / versionCode
      ↓
Download APK
      ↓
Verify package
      ↓
Verify signing certificate
      ↓
Android installer
```

Это позволяет устанавливать новые версии поверх существующей установки при использовании того же production signing key.

---

# 🗺️ Roadmap

## Этап 1 — Core

- [x] Rust Core
- [x] JNI
- [x] Android project
- [x] Android TUN
- [x] Multi-ABI builds
- [x] GitHub Actions

## Этап 2 — VLESS

- [x] VLESS parser
- [x] sing-box
- [x] Xray
- [x] VLESS XHTTP
- [x] Profile storage
- [x] Connect / disconnect
- [x] Reconnect
- [x] Diagnostics
- [x] Traffic statistics

## Этап 3 — Import & subscriptions

- [x] Multiple profiles
- [x] Clipboard import
- [x] Base64 import
- [x] JSON import
- [x] Subscription storage
- [x] Subscription refresh
- [x] Multi-link subscription parsing
- [x] VMess / Trojan / Hysteria2 / TUIC / Shadowsocks parser adapters
- [ ] QR scanner
- [ ] Subscription management UI
- [ ] Import/export profiles
- [ ] Subscription expiry information

## Этап 4 — Protocol engines

- [x] VLESS
- [x] VLESS XHTTP
- [🟡] VMess
- [🟡] Trojan
- [🟡] Hysteria2
- [🟡] TUIC
- [🟡] Shadowsocks
- [ ] WireGuard
- [ ] AmneziaWG
- [ ] OpenVPN
- [ ] OpenFlux

> 🟡 Конфигурационный адаптер есть, но полноценный сетевой путь должен пройти реальные тесты перед объявлением протокола стабильным.

## Этап 5 — Client features

- [x] Server list
- [x] Ping
- [x] Traffic statistics
- [x] Reconnect
- [x] In-app updater
- [ ] DNS settings
- [ ] Routing rules
- [ ] Split tunneling
- [ ] Kill switch
- [ ] Auto-connect
- [ ] Background notifications
- [ ] Per-profile statistics
- [ ] Speed graph
- [ ] QR scanner

## Этап 6 — Cross-platform

- [ ] Windows
- [ ] Wintun
- [ ] iOS
- [ ] Network Extension
- [ ] macOS
- [ ] Linux
- [ ] Shared Rust Core across platforms

---

# 🔐 Принципы проекта

### Rust-first

Общая логика находится в Rust.

### Engine reuse

NeoTUN использует зрелые сетевые движки вместо дублирования сложных криптографических реализаций.

### Platform abstraction

Android `VpnService`, Windows Wintun, iOS Network Extension и Linux TUN должны оставаться отдельными платформенными адаптерами.

### Security

- секреты не должны попадать в Git;
- приватные параметры не должны попадать в логи;
- production APK должен использовать постоянный signing key;
- конфигурации пользователей хранятся локально.

### Honest support status

Протокол не считается стабильным только потому, что приложение умеет его распарсить.

Нужен полный путь:

```text
Import
 ↓
Parse
 ↓
Generate config
 ↓
Start engine
 ↓
TUN
 ↓
Real traffic
 ↓
Reconnect
 ↓
Disconnect
```

---

# 📦 Текущая версия

```text
NeoTUN Android: 0.4.0
versionCode: 20

Core: Rust
Platform: Android
Engines: sing-box + Xray
Status: Active development
```

---

# 📄 License

Смотрите файл [LICENSE](LICENSE).

---

## ⭐ Проект

NeoTUN создаётся как единый быстрый клиент, где пользователь видит простую оболочку, а внутри приложение автоматически выбирает подходящий сетевой движок.

**Один клиент → несколько протоколов → несколько ядер → единый интерфейс.**
