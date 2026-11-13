package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgBot
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import kotlinx.coroutines.launch

private data class WelcomePage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val PAGES = listOf(
    WelcomePage(
        Icons.Outlined.Shield,
        "AM.SALES VPN",
        "Разблокирует YouTube, Discord, Instagram, ChatGPT и другие сайты " +
            "заблокированные в России. Работает на VLESS+REALITY.",
    ),
    WelcomePage(
        Icons.Outlined.Speed,
        "Три режима под любую задачу",
        "● VPN — весь трафик через сервер, максимальная защита.\n\n" +
            "● Запрет — без VPN, обход DPI прямо в провайдерскую сеть. " +
            "YouTube идёт с РФ-IP, без рекламы, без замедления скорости.\n\n" +
            "● VPN + Запрет — гибрид. YouTube/Google через обход DPI " +
            "(нет рекламы), остальные блокировки через VPN.",
    ),
    WelcomePage(
        Icons.Outlined.QrCodeScanner,
        "Импорт ключа",
        "Открой вкладку «Ключи», вставь VLESS-ссылку или отсканируй QR. " +
            "Можно добавить несколько серверов и переключаться между ними.",
    ),
    WelcomePage(
        Icons.Outlined.PowerSettingsNew,
        "Готово",
        "Большая кнопка на главном экране — вкл/выкл. " +
            "При первом запуске Android попросит разрешение на VPN — это нормально.",
    ),
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == PAGES.size - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AmBgTop, AmBgMid, AmBgBot)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) { page ->
                WelcomePageView(PAGES[page])
            }

            // Индикатор страниц
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGES.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (active) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (active) AmAccent else Color.White.copy(alpha = 0.3f)),
                    )
                }
            }

            // Кнопки
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onFinish) {
                    Text("Пропустить", color = Color.White.copy(alpha = 0.7f))
                }
                Button(
                    onClick = {
                        if (isLast) onFinish()
                        else scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmAccent),
                ) {
                    Text(if (isLast) "Готово" else "Далее")
                }
            }
        }
    }
}

@Composable
private fun WelcomePageView(page: WelcomePage) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            page.icon,
            contentDescription = null,
            tint = AmAccent,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(32.dp))
        Text(
            page.title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            page.body,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}
