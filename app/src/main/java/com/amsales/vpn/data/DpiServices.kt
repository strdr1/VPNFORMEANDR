package com.amsales.vpn.data

/**
 * Каталог сервисов для DPI-обхода (zapret-аналог).
 *
 * Каждый сервис — id + название + список domain_suffix-доменов
 * которые должны идти через локальный TLS-фрагментирующий прокси
 * (а не через VPN). DPI-обход маскирует TLS-handshake, провайдер
 * не видит SNI и не блокирует соединение.
 */
data class DpiService(
    val id: String,
    val title: String,
    val description: String,
    val domains: List<String>,
)

object DpiServices {
    val ALL: List<DpiService> = listOf(
        DpiService(
            id = "youtube",
            title = "YouTube",
            description = "youtube.com, googlevideo.com — без рекламы и без VPN",
            domains = listOf(
                "youtube.com", "youtu.be", "googlevideo.com",
                "ggpht.com", "ytimg.com", "youtube-nocookie.com",
                "yt3.ggpht.com", "i.ytimg.com",
            ),
        ),
        DpiService(
            id = "discord",
            title = "Discord",
            description = "discord.com, discord.gg",
            domains = listOf(
                "discord.com", "discord.gg", "discordapp.com",
                "discordapp.net", "discord.media",
            ),
        ),
        DpiService(
            id = "chatgpt",
            title = "ChatGPT",
            description = "chat.openai.com, chatgpt.com",
            domains = listOf(
                "openai.com", "chatgpt.com", "oaistatic.com",
                "oaiusercontent.com",
            ),
        ),
        DpiService(
            id = "spotify",
            title = "Spotify",
            description = "spotify.com, scdn.co",
            domains = listOf(
                "spotify.com", "scdn.co", "spoti.fi", "spotifycdn.com",
            ),
        ),
        DpiService(
            id = "twitter",
            title = "X (Twitter)",
            description = "twitter.com, x.com",
            domains = listOf(
                "twitter.com", "x.com", "twimg.com", "t.co",
            ),
        ),
        DpiService(
            id = "instagram",
            title = "Instagram",
            description = "instagram.com, cdninstagram.com",
            domains = listOf(
                "instagram.com", "cdninstagram.com", "fbcdn.net",
            ),
        ),
        DpiService(
            id = "facebook",
            title = "Facebook",
            description = "facebook.com",
            domains = listOf(
                "facebook.com", "fb.com", "fbsbx.com",
            ),
        ),
        DpiService(
            id = "linkedin",
            title = "LinkedIn",
            description = "linkedin.com",
            domains = listOf(
                "linkedin.com", "licdn.com",
            ),
        ),
    )

    fun byId(id: String): DpiService? = ALL.find { it.id == id }
}
