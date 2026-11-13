package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgBot
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop

private data class HelpSection(val title: String, val body: String)

private val SECTIONS = listOf(
    HelpSection(
        "Что такое VPN-режим",
        "Все приложения телефона ходят в интернет через наш сервер. " +
            "Скрывает твой IP, обходит блокировки сайтов, но видео может работать чуть медленнее.",
    ),
    HelpSection(
        "Что такое режим Запрет (Zapret)",
        "VPN не включается. Работает только обход DPI — это техника фрагментации TLS, " +
            "которая ломает анализатор провайдера. Открывается YouTube, Discord, ChatGPT " +
            "без VPN-задержек. Не скрывает IP.",
    ),
    HelpSection(
        "Что такое VPN+Запрет",
        "Гибридный режим. Весь трафик идёт через VPN, КРОМЕ выбранных Zapret-сервисов " +
            "(YouTube/Discord) — они идут direct через TLS-фрагментацию. " +
            "Видео грузится быстро (без двойного хопа), остальное защищено VPN.",
    ),
    HelpSection(
        "Как добавить ключ",
        "Вкладка Серверы → '+' → вставь VLESS-ссылку или отсканируй QR-код. " +
            "Ключ выглядит как vless://uuid@host:port?... — формат XRay/Sing-box. " +
            "Можно добавить несколько и переключаться.",
    ),
    HelpSection(
        "Что такое VLESS-ключ",
        "Это адрес и пароль к VPN-серверу в одной строке. " +
            "Безопасный, маскируется под обычный HTTPS — провайдер не отличит от " +
            "захода на сайт. Используется протокол REALITY от XTLS-Reality (последнее " +
            "поколение обхода DPI).",
    ),
    HelpSection(
        "Стратегии обхода",
        "Опции → Стратегия DPI. Авто-перебор тестирует 12 техник и пинит ту что прошла. " +
            "Если YouTube не открывается — попробуй стратегии 2 (Split @ SNI) или 6 " +
            "(TTL=3 disorder). Подбирается под твоего провайдера.",
    ),
    HelpSection(
        "Split tunneling",
        "Вкладка Приложения. Можно отключить конкретные приложения от VPN — " +
            "например, банковские (чтобы они видели твою реальную страну). " +
            "Эти приложения идут мимо туннеля.",
    ),
    HelpSection(
        "Я в России — как пользоваться",
        ".ru-домены автоматически идут direct (быстрее, без задержек). " +
            "YouTube/Discord — через Запрет (TLS-фрагментация). " +
            "Всё остальное — через VPN, если включён.",
    ),
    HelpSection(
        "Что-то не работает",
        "Вкладка Диагностика → 'Лог sing-box'. Там видно что происходит. " +
            "Если YouTube не грузится — выключи и включи Запрет (пересоберёт конфиг). " +
            "Если падает приложение — переустанови.",
    ),
    HelpSection(
        "Кому это надо",
        "Бесплатный VPN без подписок, рекламы и логов. Сделан в РФ для своих. " +
            "Сервер живёт пока я плачу за хостинг — донат на буст всегда приветствуется.",
    ),
)

@Composable
fun HelpScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AmBgTop, AmBgMid, AmBgBot)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Text(
                "Помощь",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SECTIONS.forEach { sec ->
                HelpCard(sec)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpCard(sec: HelpSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.06f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                sec.title,
                color = AmAccent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                sec.body,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}
