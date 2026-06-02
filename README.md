# AM.SALES VPN — Android

Мобильный клиент к нашему серверу `78.17.103.241` + Cloudflare Worker.
Стек: Kotlin + Jetpack Compose + Android VpnService + sing-box.

## Этапы

- ✅ **Этап 1** (текущий): Каркас + Compose UI + VpnService с TUN-интерфейсом +
  список приложений для split tunneling. Подключение «на ходу» работает,
  но сам sing-box ещё не запускается — это этап 2.
- ⏳ **Этап 2**: Подкладываем sing-box-binary (ARMv7 + ARM64) в `assets/`,
  поднимаем процесс из VpnService, отдаём ему TUN-fd. VPN работает.
- ⏳ **Этап 3**: zapret-Android (port в `.so` через NDK). Обход DPI для
  сайтов, идущих мимо VPN.

## Сборка

### Через GitHub Actions (рекомендую)

Каждый push в `main` запускает CI и собирает debug-APK. Скачиваешь из
вкладки **Actions** → последний прогон → артефакт `AmSalesVPN-debug`.

Релиз (`vX.Y.Z` tag) автоматически создаёт GitHub Release с APK.

### Локально

Нужно JDK 17 и Android SDK (без Android Studio достаточно cmdline-tools):

```bash
./gradlew assembleDebug
# APK будет в app/build/outputs/apk/debug/
```

## Установка на телефон

1. Скачай APK на телефон
2. В Настройки → Безопасность → Разреши установку из неизвестных источников
3. Открой APK → установить
4. При первом подключении Android спросит «Разрешить AM.SALES VPN?» — соглашайся
5. На вкладке **Приложения** выбери что **НЕ должно** идти через VPN
   (Ozon, WB, Госуслуги, банковские) — они будут ходить напрямую

## Архитектура

```
[Apps на телефоне]
       ↓ через VpnService TUN-interface (или мимо если в blacklist)
[AmSalesVpnService]
       ↓ tun-fd
[sing-box (нативный бинарь в assets, этап 2)]
       ↓ VLESS+REALITY / VLESS+WS
[Cloudflare Worker amsales-vpn.danecc5678.workers.dev]  (для AM.SALES-CF)
       или прямо
[78.17.103.241:443]                                     (для AM.SALES-1/2)
       ↓
[Интернет]
```

## Связанные проекты

- [AmSalesVPN](https://github.com/strdr1/AM.Sales-VPN) — десктоп клиент (Windows)
- [StarostinVPN](https://github.com/strdr1/StarostinVPN) — форк для бати с режимом шлюза
