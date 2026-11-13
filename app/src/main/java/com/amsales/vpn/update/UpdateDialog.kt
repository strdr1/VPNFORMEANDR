package com.amsales.vpn.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf(-1) }    // -1 = ещё не начали, 0..100 = качаем, 101 = готово
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (progress < 0 || progress >= 101) onDismiss() },
        title = { Text("Доступно обновление") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Версия ${info.versionName} (у вас $currentVersion)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "Размер: ${(info.sizeBytes / 1024 / 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (progress in 0..100) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("$progress%", style = MaterialTheme.typography.bodySmall)
                }
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (progress < 0) {
                    TextButton(onClick = {
                        if (!ApkInstaller.canInstall(ctx)) {
                            ApkInstaller.openInstallPermissionSettings(ctx)
                            error = "Разрешите установку и нажмите Обновить снова"
                            return@TextButton
                        }
                        progress = 0
                        error = null
                        scope.launch {
                            val apk = UpdateChecker.download(ctx, info.apkUrl) { p -> progress = p }
                            if (apk == null) {
                                error = "Не удалось скачать обновление"
                                progress = -1
                            } else {
                                progress = 101
                                ApkInstaller.install(ctx, apk)
                            }
                        }
                    }) {
                        Text("Обновить")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = progress < 0 || progress >= 101,
            ) {
                Text(if (progress in 0..100) "..." else "Позже")
            }
        },
    )
}

