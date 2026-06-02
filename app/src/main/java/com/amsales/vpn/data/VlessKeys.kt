package com.amsales.vpn.data

/**
 * Дефолтные VLESS-ключи AM.SALES, которые автоматически добавляются при
 * первом запуске приложения. Меняются только когда мигрируем на новый
 * сервер — у клиента на устройстве остаются те, что были, плюс свежие
 * через подписку или ручной ввод (последнее — в следующих версиях).
 *
 * 1/2 — прямые (REALITY на 78.17.103.241:443, TCP). Быстрые, но могут
 * блокироваться DPI у некоторых провайдеров (InterZet и т.п.).
 *
 * CF/CF-2 — через Cloudflare Worker. URL зашит навсегда — Worker сам
 * проксирует к нашему серверу. Обход DPI почти везде.
 */
object VlessKeys {

    val defaults = listOf(
        "vless://74553d23-3ff1-4e25-b06f-ff24e22f97ba@78.17.103.241:443" +
            "?type=tcp&security=reality&sni=ya.ru&fp=firefox" +
            "&pbk=TiUgA_8KxozBVo35PWZczwodV5aDoypTbQDRyEuwpSU" +
            "&sid=23f62cc284c8fbb4#AM.SALES-1",

        "vless://048f5a49-476b-4b72-8f3c-034103caf7bc@78.17.103.241:443" +
            "?type=tcp&security=reality&sni=ya.ru&fp=firefox" +
            "&pbk=TiUgA_8KxozBVo35PWZczwodV5aDoypTbQDRyEuwpSU" +
            "&sid=23f62cc284c8fbb4#AM.SALES-2",

        "vless://74553d23-3ff1-4e25-b06f-ff24e22f97ba" +
            "@amsales-vpn.danecc5678.workers.dev:443" +
            "?type=ws&security=tls" +
            "&sni=amsales-vpn.danecc5678.workers.dev" +
            "&host=amsales-vpn.danecc5678.workers.dev" +
            "&path=%2Famsales&fp=chrome&encryption=none#AM.SALES-CF",

        "vless://048f5a49-476b-4b72-8f3c-034103caf7bc" +
            "@amsales-vpn.danecc5678.workers.dev:443" +
            "?type=ws&security=tls" +
            "&sni=amsales-vpn.danecc5678.workers.dev" +
            "&host=amsales-vpn.danecc5678.workers.dev" +
            "&path=%2Famsales&fp=chrome&encryption=none#AM.SALES-CF-2",
    )
}
