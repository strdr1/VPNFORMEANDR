package com.amsales.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Вкладка «Диагностика»:
 *  - читает logcat по тегам AmSalesVpnService / sing-box / AmSales / VpnState
 *  - кнопка «Обновить» — перечитать
 *  - «Копировать» — в буфер
 *  - «Поделиться» — отправить через системный share-sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("Загрузка…") }

    fun refresh() {
        scope.launch {
            log = withContext(Dispatchers.IO) {
                buildString {
                    appendLine("=== CRASH.LOG (наши Java-исключения) ===")
                    appendLine(readFile(File(ctx.filesDir, "crash.log")))
                    appendLine()
                    appendLine("=== STDERR.LOG (паники Go / sing-box) ===")
                    appendLine(readFile(File(ctx.filesDir, "stderr.log")))
                    appendLine()
                    appendLine("=== LOGCAT (последние 500 строк) ===")
                    appendLine(readLogcat())
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Диагностика",
                color = AmTextHi,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Outlined.Refresh, null, tint = AmAccent)
            }
            IconButton(onClick = { copyToClipboard(ctx, log) }) {
                Icon(Icons.Outlined.ContentCopy, null, tint = AmAccent)
            }
            IconButton(onClick = { shareLog(ctx, log) }) {
                Icon(Icons.Outlined.Share, null, tint = AmAccent)
            }
        }
        Text(
            text = "Если VPN не запускается — пришлите этот лог.",
            color = AmTextLo,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        Surface(
            color = AmBgTop,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text(
                    text = log,
                    color = AmTextHi,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun readFile(f: File): String {
    if (!f.exists() || f.length() == 0L) return "(пусто)"
    return try {
        val max = 64 * 1024L
        if (f.length() <= max) f.readText()
        else f.inputStream().use { s ->
            s.skip(f.length() - max); String(s.readBytes())
        }
    } catch (e: Exception) {
        "ошибка чтения ${f.name}: ${e.message}"
    }
}

/** Читает logcat за последние ~5 минут, фильтрует по нашим тегам. */
private fun readLogcat(): String {
    return try {
        // -d = одноразовый дамп, -t 500 = последние 500 строк
        val process = ProcessBuilder(
            "logcat", "-d", "-t", "500",
            "AmSalesVpnService:V",
            "AmSalesPlatform:V",
            "AmSalesCrash:V",
            "sing-box:V",
            "libbox:V",
            "AmSales:V",
            "VpnState:V",
            "AndroidRuntime:E",
            "DEBUG:V",
            "Go:V",
            "GoLog:V",
            "*:S"
        ).redirectErrorStream(true).start()
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { sb.appendLine(it) }
        }
        if (sb.isBlank()) "(логов нет — попробуйте подключить VPN и обновить)"
        else sb.toString()
    } catch (e: Exception) {
        "Не удалось прочитать logcat: ${e.message}\n\n" +
            "На Android 6+ приложение может читать только СВОИ логи. " +
            "Если эта строка появилась — попробуйте подключить VPN, " +
            "вернуться сюда и нажать Обновить."
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AmSales VPN log", text))
}

private fun shareLog(ctx: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AmSales VPN — лог")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "Поделиться логом")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ctx.startActivity(chooser)
}
