# NeoTUN

NeoTUN — клиент для Android и Windows для работы с серверными профилями и сетевыми движками. Проект развивается: ниже отдельно указано, что подтверждено сборками и проверками на устройстве, а что ещё требует тестирования.

## Скачать

**[Последний релиз](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest)**

### Android

Выберите APK для архитектуры устройства:

| Файл | Устройства |
| --- | --- |
| [NeoTUN-arm64-v8a.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-arm64-v8a.apk) | Большинство современных телефонов и планшетов |
| [NeoTUN-armeabi-v7a.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-armeabi-v7a.apk) | 32-битные ARM-устройства |
| [NeoTUN-x86_64.apk](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-x86_64.apk) | Совместимые устройства и эмуляторы x86-64 |

Если APK отсутствует в релизе, проверьте [сборки Android](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/android.yml). В успешном запуске файл можно найти в **Artifacts**; такие артефакты хранятся ограниченное время.

### Windows 10/11 x64

Основной установочный файл — **[NeoTUN-Setup-x64.exe](https://github.com/Kolya-YT/NeoTUN-Client/releases/latest/download/NeoTUN-Setup-x64.exe)**.

1. Скачайте и запустите EXE.
2. Подтвердите запрос Windows, если он появится.
3. Завершите установку и откройте NeoTUN из меню «Пуск».

ZIP-архив не заменяет установщик. Если EXE нет в релизе, проверьте [сборки Windows](https://github.com/Kolya-YT/NeoTUN-Client/actions/workflows/desktop.yml) и раздел **Artifacts** успешного запуска.

## Что уже проверено

Успешная сборка подтверждает компиляцию и упаковку, но не доказывает, что весь сетевой трафик проходит правильно.

### Android

- CI собирает APK для ARM64, ARMv7 и x86-64.
- В сборке есть общий Rust Core, интеграция Xray и движок sing-box.
- В приложении доступны импорт профилей, настройки маршрутизации и диагностика.
- QR-коды: импорт профиля сканированием и показ QR-кода для сохранённого профиля.
- Исключения приложений на Android: выбор приложений, которые должны выходить напрямую, с применением в TUN-конфигурациях Xray и sing-box.
- Настройки DNS, MTU и IPv6 для Android; выбранные параметры применяются при новом подключении.
- В карточке профиля есть сведения о движке и заявленных возможностях. Это описание конфигурации клиента, а не гарантия проверки каждого протокола на реальном сервере.
- VLESS TCP и VLESS XHTTP проверялись на устройстве. В профилях VLESS/VMess обычный TCP используется по умолчанию; старый TCP HTTP-header преобразуется в HTTP transport sing-box.
- В диагностике Hysteria2 наблюдались успешные DNS-ответы и исходящие HTTPS-соединения через прокси. **Применение пользовательских правил маршрутизации в Hysteria2 пока не подтверждено и остаётся в работе.**

### Windows

- CI собирает приложение x64, общий Rust Core и установщик Inno Setup.
- В установщик включаются sing-box, Xray и Wintun.
- В последнем успешном релизе опубликован установщик NeoTUN-Setup-x64.exe.

**Ещё не подтверждено на реальном ПК:** полный цикл подключения/отключения, управление маршрутами Windows, DNS и передача произвольного TCP/UDP-трафика через туннель. Наличие EXE означает, что установщик собран, но не гарантирует готовность всех сетевых функций.

## Транспорт TCP

- `type=tcp` и совместимый алиас `type=raw` обрабатываются как обычный TCP без лишнего transport-блока.
- Для `headerType=http` сохраняются `host` и `path` и создаётся HTTP transport.
- В списке серверов TCP показывается явно, даже если параметр `type` отсутствует в ссылке.

## Что ещё в разработке

- Маршрутизация Hysteria2 по пользовательским правилам Proxy, Direct и Block.
- Проверка доменных правил и GeoSite/GeoIP на каждом движке.
- Полный жизненный цикл Windows-туннеля, Wintun, управление маршрутами и восстановление сети после отключения.
- Практические тесты TCP, UDP, DNS, переподключения и восстановления после сна.
- Проверка Hysteria2, TUIC и других транспортов на реальных серверах. Импорт ссылки сам по себе не означает поддержку всех её параметров.
- Проверка обновления приложения поверх установленной версии на разных устройствах.
- Проверка исключений приложений, автоматического DNS, MTU и IPv6 на реальных Android-устройствах и сетях.

Функция считается проверенной только после соответствующего теста, а не только после успешной компиляции.

## Маршрутизация и DNS

Для совпавших правил доступны действия Proxy, Direct и Block, ручные домены и IP/CIDR, а также настройка порядка групп. Форматы геобаз зависят от движка: Xray использует свои GeoSite/GeoIP-файлы, sing-box — совместимые rule-set. Один профиль может поддерживаться разными движками неодинаково; неподдерживаемое правило нельзя считать применённым.

## Если подключение не работает

1. Откройте в приложении **Настройки → Диагностика соединения**.
2. Проверьте сообщения запуска движка и загрузки правил.
3. Проверьте несколько сайтов и приложений, затем переподключите профиль.
4. Если ошибка повторяется, создайте [Issue](https://github.com/Kolya-YT/NeoTUN-Client/issues) и укажите версию, устройство, протокол и шаги воспроизведения.

Перед отправкой журнала удалите UUID, пароли, токены и приватные ссылки подписок.

## Сборка из исходников

### Android

Нужны JDK 17, Android SDK/NDK, Rust stable, cargo-ndk, Go и Gradle. См. [android.yml](.github/workflows/android.yml).

### Windows

Нужны Windows x64, .NET 8 SDK, Rust stable с target x86_64-pc-windows-msvc, Visual Studio Build Tools с компонентами C++ и Inno Setup 6. См. [desktop.yml](.github/workflows/desktop.yml).

~~~powershell
rustup target add x86_64-pc-windows-msvc
cargo test --manifest-path core/Cargo.toml
cargo build --manifest-path core/Cargo.toml --release --target x86_64-pc-windows-msvc
dotnet restore windows/NeoTUN.Windows/NeoTUN.Windows.csproj
dotnet publish windows/NeoTUN.Windows/NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/NeoTUN-Windows
~~~

Для установщика дополнительно нужны сетевые движки, Wintun и Inno Setup.

## Структура проекта

- android/ — Android-приложение и VPN-службы.
- windows/NeoTUN.Windows/ — Windows-клиент.
- core/ — общий Rust Core.
- xraybridge/ — интеграция Xray для Android.
- installer/ — Windows-установщик.
- .github/workflows/ — CI, сборки и публикация релизов.

[Релизы](https://github.com/Kolya-YT/NeoTUN-Client/releases) · [Сборки](https://github.com/Kolya-YT/NeoTUN-Client/actions) · [Сообщить об ошибке](https://github.com/Kolya-YT/NeoTUN-Client/issues)
