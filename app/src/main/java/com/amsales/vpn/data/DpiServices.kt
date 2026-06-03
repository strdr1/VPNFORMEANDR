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
            // Список доменов взят из zapret-discord-youtube 1.9.9a / list-google.txt
            domains = listOf(
                "youtube.com", "youtu.be", "googlevideo.com",
                "ggpht.com", "ytimg.com", "youtube-nocookie.com",
                "youtubekids.com", "youtubeembeddedplayer.googleapis.com",
                "youtubei.googleapis.com", "googleusercontent.com",
                "jnn-pa.googleapis.com",
                "wide-youtube.l.google.com",
                "youtube-ui.l.google.com",
                "yt-video-upload.l.google.com",
                "ytimg.l.google.com",
            ),
        ),
        DpiService(
            id = "discord",
            title = "Discord",
            description = "discord.com, discord.gg, голос и видео",
            // Список из zapret-discord-youtube 1.9.9a / list-general.txt
            domains = listOf(
                "discord.com", "discord.gg", "discord.media",
                "discordapp.com", "discordapp.net", "discordcdn.com",
                "discord.app", "discord.dev", "discord.gift",
                "discord-activities.com", "discordactivities.com",
                "discordstatus.com", "discordsays.com",
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
