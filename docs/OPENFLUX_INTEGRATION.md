# OpenFlux в NeoTUN — статус интеграции

## Что выяснено

- Ссылка `openflux://v1/…` — не VLESS и не конфигурация sing-box. По спецификации OpenFlux это `base64url(DEFLATE(JSON))`, содержащий параметры транспорта, контекст и потенциально секрет шифрования.
- В текущем AndroidManifest NeoTUN зарегистрированы схемы `incy://` и `happ://`; `openflux://` пока не заявлена.
- Добавление URI в список профилей само по себе не обеспечивает подключение: потребуется самостоятельный runtime OpenFlux, который не должен конфликтовать с Android VPN-службами NeoTUN.
- В upstream есть Go mobile API для чтения/записи IP-пакетов и чтения OpenFlux-ссылок. Для полноценной интеграции его потребуется подключить как отдельный движок и связать с единственным владельцем TUN-интерфейса.

## Почему нельзя просто преобразовать ссылку

OpenFlux использует отдельный стек: транспорт(ы), согласование сессии, собственный codec и выходную ноду. Если превратить ссылку в VLESS или sing-box URI, большая часть её параметров будет потеряна, а подключение не заработает.

## План интеграции

1. Реализовать и протестировать проверку `openflux://v1/` через upstream API, сохраняя исходную ссылку целиком.
2. Добавить отдельный идентификатор движка OpenFlux в модель профиля и импорт из буфера обмена/Android intent.
3. Подключить upstream mobile bridge как отдельный модуль. Не переписывать декодер ссылки самостоятельно и не копировать исходники ядра без проверки лицензии GPL-3.0-or-later.
4. Создать Android-адаптер пакетов между единственным TUN и OpenFlux runtime; корректно закрывать сокеты, потоки и TUN при отключении и переключении движка.
5. Добавить тесты импорта, сохранения профиля после перезапуска, неправильных/обрезанных ссылок, остановки и повторного подключения.
6. Проверить APK в CI и на реальном Android-устройстве: запуск, DNS, TCP/UDP, ошибки и восстановление соединения. Только после этого считать поддержку готовой.

## Источники

- Спецификация `openflux://`: https://github.com/p1neappleXpress/OpenFlux/blob/main/docs/links.md
- Upstream мобильный API: https://github.com/p1neappleXpress/OpenFlux/tree/main/mobile
- Android-приложение: https://github.com/damnurmum/OpenFlux-Android
- Лицензия upstream: GPL-3.0-or-later

## Конфигурация для ручного импорта

Используйте в приложении OpenFlux Android. Эта приватная ссылка содержит параметры доступа, поэтому не публикуйте её в открытых Issue или чатах.

```text
openflux://v1/VJFNjtowHMWvUv27jZi_TRwgO0qqUVBiBBPK2FUXjmMGB_KhYCSSEUfpHbroumfgSBVdVJr10_vQ771DrSoDIdx_3v_cf99_faJIRmw6IoyMggA8qM1b46xyBkLXXYwHZ6M74yCEYlbkOVNTiiQnOPZpkGtmlDJmNqUK9T6Y6j3ONPomGKtA4YQpn5iA5BTNhO7BA93UzlwfYQfn2vDp6TN44DpVn9umc2cIv7-D69vHQH1pz019srUBDy7dCUIw_ZLGZdMni2Wf05nTfRzs-uWRl5sqLbdjvsCeD3qcZGtf7oRb7eRRvCCRzzGVET_JLGUyO9pkEQ-r3caKIWbpAn3-HJMk21Sy3DqRzfvUklJGmqTD_CrolopBPDwosjmuouIkLTmIYXlIsvWYV6kTZVHyBVJefjly-pUIKhiP5v96RPXtJKu4Ty36aVQck2x9ldGbEzte8RdkMtr66TBHXm0OKV3b11cED9rONp11PYQTvHn_mRS2M9p90Bl6UFj1wPPhyZCyYDqB24_b3wAAAP__
```
