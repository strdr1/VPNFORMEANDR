package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.data.LocalRepository
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo

/**
 * Вкладка «Опции» — аналог десктопной панели «Настройки»:
 *  - Российский трафик напрямую (.ru мимо VPN)
 *  - Обход DPI (zapret)  — пока заглушка (этап 3)
 *  - Telegram-прокси (поле-ссылка + копирование)
 *  - Сайты-исключения
 *  - Обновить Cloudflare-ключ
 *  - Проверка обновлений (заглушка)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsTab() {
    val repo = LocalRepository.current
    val clipboard = LocalClipboardManager.current
    var routeRu by remember { mutableStateOf(repo.routeRuDirect) }
    var useZapret by remember { mutableStateOf(repo.useZapret) }
    var tgLink by remember { mutableStateOf(repo.tgProxyLink) }
    var bypassSites by remember { mutableStateOf(repo.bypassSites) }
    var newSite by remember { mutableStateOf("") }
    var cfStatus by remember { mutableStateOf("Не проверялось") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Настройки",
            color = AmTextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(14.dp))

        // .ru-трафик напрямую
        ToggleRow(
            title = "Российский трафик напрямую",
            subtitle = ".ru-сайты идут мимо VPN",
            checked = routeRu
        ) {
            routeRu = it; repo.routeRuDirect = it
        }
        Divider()

        // Обход DPI (zapret) — заглушка
        ToggleRow(
            title = "Обход DPI (zapret)",
            subtitle = "Будет добавлено в одной из следующих версий",
            checked = useZapret
        ) {
            useZapret = it; repo.useZapret = it
        }
        Divider()

        // Telegram-прокси
        Spacer(Modifier.height(12.dp))
        Text(
            "Telegram-прокси",
            color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "MTProto через наш сервер",
            color = AmTextLo, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            color = AmBgTop,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tgLink,
                    color = AmTextLo,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(tgLink))
                }) {
                    Text("Копировать", color = AmAccent, fontSize = 12.sp)
                }
            }
        }
        Divider()

        // Сайты-исключения
        Spacer(Modifier.height(12.dp))
        Text(
            "Сайты-исключения",
            color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Идут напрямую — мимо VPN. Например: gosuslugi.ru",
            color = AmTextLo, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newSite,
                onValueChange = { newSite = it },
                placeholder = { Text("например: gosuslugi.ru", color = AmTextLo) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AmTextHi,
                    unfocusedTextColor = AmTextHi,
                    focusedBorderColor = AmAccent,
                    unfocusedBorderColor = AmTextLo.copy(alpha = 0.3f),
                    cursorColor = AmAccent,
                    focusedContainerColor = AmBgTop,
                    unfocusedContainerColor = AmBgTop,
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    val s = newSite.trim()
                    if (s.isNotEmpty() && s !in bypassSites) {
                        bypassSites = bypassSites + s
                        repo.bypassSites = bypassSites
                        newSite = ""
                    }
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AmAccent.copy(alpha = 0.18f),
                    contentColor = AmAccent
                )
            ) { Text("+") }
        }
        if (bypassSites.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            bypassSites.forEach { site ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(site, color = AmTextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        bypassSites = bypassSites - site
                        repo.bypassSites = bypassSites
                    }) {
                        Icon(Icons.Outlined.Close, null, tint = AmTextLo)
                    }
                }
            }
        }
        Divider()

        // Обновления
        Spacer(Modifier.height(16.dp))
        Text(
            "Обновления",
            color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Версия 1.0.0 · Проверка обновлений будет в следующих версиях",
            color = AmTextLo, fontSize = 11.sp
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AmTextLo, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmAccent,
                checkedTrackColor = AmAccent.copy(alpha = 0.35f),
                checkedBorderColor = AmAccent,
                uncheckedThumbColor = AmTextLo,
                uncheckedTrackColor = AmBgTop,
                uncheckedBorderColor = AmTextLo.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = 1.dp,
        color = AmTextLo.copy(alpha = 0.1f)
    )
}
