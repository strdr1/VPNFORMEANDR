package com.amsales.vpn.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import com.amsales.vpn.tgproxy.TgProxyService
import com.amsales.vpn.tgproxy.rememberTgProxyState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
    val ctx = LocalContext.current
    var routeRu by remember { mutableStateOf(repo.routeRuDirect) }
    var autoBoot by remember { mutableStateOf(repo.autoConnectOnBoot) }
    var bypassSites by remember { mutableStateOf(repo.bypassSites) }
    var newSite by remember { mutableStateOf("") }
    val tgState by rememberTgProxyState()
    var tgLink by remember { mutableStateOf(repo.tgProxyLink) }
    // если сервис прислал свежую ссылку — обновим локальный
    if (tgState.link.isNotEmpty() && tgState.link != tgLink) {
        tgLink = tgState.link
    }

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
            subtitle = ".ru-сайты идут мимо VPN. РЕКОМЕНДУЕТСЯ — иначе " +
                "российские сайты будут грузиться очень медленно или совсем не работать.",
            checked = routeRu
        ) {
            routeRu = it; repo.routeRuDirect = it
        }
        Divider()

        // Обход DPI — мастер-toggle + список сервисов
        var zapretOn by remember { mutableStateOf(repo.useZapret) }
        var dpiSet by remember { mutableStateOf(repo.dpiServices) }
        ToggleRow(
            title = "Обход DPI (zapret)",
            subtitle = if (zapretOn && dpiSet.isEmpty())
                "ВКЛЮЧЁН, но сервисы НЕ выбраны — отметь YouTube/Discord ниже"
            else if (zapretOn)
                "ВКЛЮЧЁН для ${dpiSet.size} сервис(ов). Переподключи VPN после изменений."
            else
                "ВЫКЛЮЧЕН. Сервисы ниже игнорируются.",
            checked = zapretOn
        ) {
            zapretOn = it
            repo.useZapret = it
            // Auto-select YouTube при первом включении если список пуст
            if (it && dpiSet.isEmpty()) {
                val auto = setOf("youtube")
                dpiSet = auto
                repo.dpiServices = auto
            }
        }
        Divider()

        Spacer(Modifier.height(8.dp))
        Text(
            "Сервисы для обхода DPI",
            color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Выбранные сервисы идут НАПРЯМУЮ с фрагментацией TLS — провайдер " +
                "не видит SNI и не блокирует. Реклама региональная (российский IP).\n" +
                "ВАЖНО: после изменений нужно переподключить VPN.",
            color = AmTextLo, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        com.amsales.vpn.data.DpiServices.ALL.forEach { svc ->
            val checked = svc.id in dpiSet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = if (checked) dpiSet - svc.id else dpiSet + svc.id
                        dpiSet = next
                        repo.dpiServices = next
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(svc.title, color = AmTextHi, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    Text(svc.description, color = AmTextLo, fontSize = 10.sp)
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AmAccent,
                        uncheckedColor = AmTextLo,
                        checkmarkColor = AmBgTop,
                    )
                )
            }
        }
        Divider()

        // Авто-подключение при старте Android
        ToggleRow(
            title = "Авто-подключение при загрузке",
            subtitle = "Запускать VPN сразу после старта телефона",
            checked = autoBoot
        ) {
            autoBoot = it; repo.autoConnectOnBoot = it
        }
        Divider()

        // Telegram-прокси — локальный MTProto WS-bridge движок
        Spacer(Modifier.height(12.dp))
        Text(
            "Telegram-прокси",
            color = AmTextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Поднимает локальный MTProto-прокси на 127.0.0.1:1443 — гонит " +
                "Telegram через Cloudflare. Аналог TG-движка из десктопа.",
            color = AmTextLo, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        // Главная кнопка — Включить / Выключить
        Button(
            onClick = {
                val intent = Intent(ctx, TgProxyService::class.java)
                    .setAction(
                        if (tgState.running) TgProxyService.ACTION_STOP
                        else TgProxyService.ACTION_START
                    )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tgState.running) AmTextLo.copy(alpha = 0.3f) else AmAccent,
                contentColor = if (tgState.running) AmTextHi else AmBgTop
            )
        ) {
            Icon(
                if (tgState.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (tgState.running) "Выключить TG-прокси" else "Включить TG-прокси",
                fontWeight = FontWeight.Medium
            )
        }

        if (tgState.error.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Ошибка: ${tgState.error}",
                color = androidx.compose.ui.graphics.Color(0xFFFF7373), fontSize = 11.sp)
        }

        if (tgState.running && tgLink.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = AmBgTop, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ссылка для Telegram:",
                        color = AmTextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(tgLink, color = AmTextHi, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { openInTelegram(ctx, tgLink) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AmAccent.copy(alpha = 0.18f),
                        contentColor = AmAccent
                    )
                ) {
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть в TG", fontSize = 12.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyTextToSystemClipboard(ctx, "tg-proxy", tgLink)
                        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AmAccent.copy(alpha = 0.18f),
                        contentColor = AmAccent
                    )
                ) { Text("Копировать", fontSize = 12.sp) }
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

/**
 * Открывает TG-прокси в приложении Telegram. Принимает как tg://, так и
 * https://t.me/proxy?... — пробуем строго в этом порядке, fallback на
 * системный browser/chooser, если Telegram не установлен.
 */
private fun openInTelegram(ctx: android.content.Context, link: String) {
    val cleaned = link.trim()
    val uri = try { Uri.parse(cleaned) } catch (_: Exception) { null }
    if (uri == null) {
        Toast.makeText(ctx, "Невалидная ссылка", Toast.LENGTH_SHORT).show()
        return
    }
    // Попытка №1 — прямой intent с Telegram-пакетом
    val tgPackages = listOf("org.telegram.messenger", "org.telegram.plus", "nekox.messenger")
    for (pkg in tgPackages) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return
        } catch (_: Exception) {}
    }
    // Попытка №2 — открываем без явного пакета (любое приложение/браузер)
    try {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(intent, "Открыть прокси")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не могу открыть: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Копирует в системный буфер через ClipboardManager.
 * Compose-овский LocalClipboardManager на ряде прошивок MIUI глючит
 * (молча проглатывает setText), поэтому используем платформенный API.
 */
private fun copyTextToSystemClipboard(ctx: android.content.Context, label: String, text: String) {
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось скопировать: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
