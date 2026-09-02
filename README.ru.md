# l2tp-sstp-client

> Форк [TunnelForge](https://github.com/evokelektrique/tunnel-forge) (GPL-3.0)
> с SSTP-движком на основе [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) (MIT).
> Независимый проект; не связан с авторами обоих upstream-проектов и не одобрен ими.

[English version](README.md)

## Что это

VPN-клиент для Android, поддерживающий два протокола в одном приложении:

- **L2TP/IPsec (IKEv1)** — из TunnelForge, нативный C-движок;
- **SSTP** — движок, перенесённый из Open SSTP Client.

Протокол выбирается в профиле. Слой туннеля (построение TUN, маршруты, DNS,
per-app routing, kill switch) общий для обоих движков.

Android 12 убрал встроенную поддержку L2TP и PPTP, при этом L2TP/IPsec-серверы
никуда не делись — они стоят в офисах, вузах и домашних сетях. SSTP добавлен
как запасной вариант для сетей, где закрыты UDP/500 и ESP: он идёт по TCP/443 и
умеет работать через HTTP CONNECT-прокси.

## Отличия от upstream

| | TunnelForge | Этот форк |
|---|---|---|
| Протоколы | L2TP/IPsec | L2TP/IPsec + SSTP |
| Слой туннеля | внутри движка | общий модуль `core-tunnel` |
| Доверие к сертификатам | — | хранилище сертификатов и политики доверия (`core-trust`) |
| HTTP-прокси для VPN-транспорта | — | есть для SSTP, с авторизацией на прокси |
| Failover | — | группа профилей с перебором по порядку |
| Локализация | — | добавлен русский |

## Состояние

Проект собирается по фазам, описанным в [`SPEC`](SPEC). Текущее состояние:

- [x] Фаза 1 — структура модулей, каталог версий, ktlint, CI
- [x] Фаза 2 — контракт `engine-api`
- [x] Фаза 3 — `core-tunnel`
- [x] Фаза 4 — `engine-l2tp`
- [x] Фаза 5 — `core-trust`
- [x] Фаза 6 — `engine-sstp`
- [x] Фаза 7 — единый `VpnService` и диспетчер протокола
- [x] Фаза 8 — профили, хранилище, миграция
- [x] Фаза 9 — UI
- [x] Фаза 10 — failover ([автовыбор по сети снят](docs/PHASE10.md))
- [x] Фаза 11 — тесты, документация, релиз

Что сделано и что сделано не было — в [`CHANGELOG.md`](CHANGELOG.md) и, по
фазам, в приложении В [`SPEC`](SPEC). Ручная матрица, без которой релиз не
выходит, — [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md).

## Структура репозитория

| Путь | Назначение |
|---|---|
| `lib/` | Flutter UI, профили, настройки, логи |
| `android/app/` | Android-хост: `VpnService`, платформенные каналы, Netty-прокси |
| `android/app/src/main/cpp/` | Нативный C-движок L2TP/IPsec — не трогаем |
| `android/gvisor/` | Go/gVisor, userspace-стек для proxy-режима |
| `engine-api/` | Контракт движков: `VpnEngine`, `EngineProfile`, `EngineError` |
| `engine-l2tp/` | Обёртка нативного L2TP-движка |
| `engine-sstp/` | SSTP-движок из Open SSTP Client |
| `core-tunnel/` | Общий слой TUN, маршрутов и DNS |
| `core-trust/` | Хранилище серверных сертификатов и политики доверия |
| `third_party/open-sstp-client/` | Лицензия MIT и происхождение перенесённых файлов |
| `docs/` | Архитектура, лицензирование, зависимости |

## Сборка

Требуется: Flutter с Dart 3.11+, Android SDK, NDK и CMake, Go 1.25.9+.

```sh
flutter pub get
cd android/gvisor && go mod download && cd ../..
make build-debug
```

Проверки:

```sh
make check    # анализ Dart, Android lint, ktlint, проверки нативного C
make test     # тесты Flutter, Android и нативные
```

## Безопасность и приватность

- Пароли VPN, PSK и пароль прокси хранятся в зашифрованном хранилище и не
  попадают в логи.
- Сертификаты серверов копируются во внутреннее хранилище приложения; внешние
  `Uri` не сохраняются — они недоступны при старте по always-on VPN до
  разблокировки устройства.
- Никакой аналитики, телеметрии и crash-репортинга. Приложение не обращается
  никуда, кроме VPN-сервера пользователя.
- Режим доверия `INSECURE` доступен только в debug-сборке.

Полный чек-лист — приложение А в [`SPEC`](SPEC); что из него проверяется на
устройстве перед каждым релизом — раздел 3
[`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md).

**Об уязвимостях не сообщают в публичных issue** — см. [`SECURITY.md`](SECURITY.md).

## Как участвовать

[`CONTRIBUTING.md`](CONTRIBUTING.md) — сборка, проверки, которые гоняет CI, и
что должно приходить вместе с изменением. Правила общения —
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Лицензия

GPL-3.0-or-later. См. [`LICENSE`](LICENSE), [`NOTICE`](NOTICE) и
[`docs/LICENSING.md`](docs/LICENSING.md).
