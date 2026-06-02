package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.window.Dialog
import com.amsales.vpn.data.LocalRepository
import com.amsales.vpn.data.SubscriptionLoader
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вкладка «Серверы»:
 *  - список ключей (радио-выбор, копировать, share, удалить)
 *  - «Добавить» открывает меню: «Вставить ключ/подписку», «Сканировать QR»
 *  - share показывает QR + текст ключа
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersTab() {
    val repo = LocalRepository.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(repo.profiles()) }
    var currentIdx by remember { mutableIntStateOf(repo.currentIndex()) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var shareKey by remember { mutableStateOf<Pair<String, String>?>(null) } // uri to name
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun handleInput(text: String) {
        scope.launch {
            loading = true
            val res = withContext(Dispatchers.IO) { SubscriptionLoader.loadOrParse(text) }
            loading = false
            res.fold(
                onSuccess = { keys ->
                    val added = repo.addKeys(keys)
                    profiles = repo.profiles()
                    info = if (added == 1) "Добавлен 1 ключ" else "Добавлено: $added"
                    error = null
                    showPasteDialog = false
                    showScanner = false
                },
                onFailure = { e ->
                    error = e.message ?: "Ошибка"
                }
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Серверы",
                color = AmTextHi,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box {
                FilledTonalButton(
                    onClick = { addMenuOpen = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AmAccent.copy(alpha = 0.18f),
                        contentColor = AmAccent
                    )
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить", fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                    modifier = Modifier.background(AmBgTop)
                ) {
                    DropdownMenuItem(
                        text = { Text("Вставить ключ или подписку", color = AmTextHi) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = AmAccent) },
                        onClick = {
                            addMenuOpen = false
                            showPasteDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Сканировать QR-код", color = AmTextHi) },
                        leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null, tint = AmAccent) },
                        onClick = {
                            addMenuOpen = false
                            showScanner = true
                        }
                    )
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AmAccent,
                trackColor = AmBgTop
            )
        }

        if (profiles.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(profiles) { idx, p ->
                    ProfileRow(
                        name = p.name,
                        host = p.host,
                        isActive = idx == currentIdx,
                        onSelect = {
                            currentIdx = idx; repo.setCurrentIndex(idx)
                        },
                        onCopy = { clipboard.setText(AnnotatedString(p.raw)) },
                        onShare = { shareKey = p.raw to p.name },
                        onDelete = {
                            repo.removeAt(idx)
                            profiles = repo.profiles()
                            currentIdx = repo.currentIndex()
                        }
                    )
                    HorizontalDivider(color = AmBgTop, thickness = 1.dp)
                }
            }
        }
    }

    if (showPasteDialog) {
        AddKeyDialog(
            onDismiss = { showPasteDialog = false; error = null },
            onAdd = { handleInput(it) },
            error = error,
            loading = loading
        )
    }

    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            QrScannerScreen(
                onScanned = { handleInput(it) },
                onDismiss = { showScanner = false }
            )
        }
    }

    shareKey?.let { (uri, name) ->
        ShareKeyDialog(
            keyUri = uri,
            keyName = name,
            onDismiss = { shareKey = null }
        )
    }

    // info-сообщение убираю по таймауту — отображение перенесём позже на
    // SnackbarHost в Scaffold, пока без визуала.
    info?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            info = null
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.QrCode,
            null,
            tint = AmTextLo,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Ключей пока нет",
            color = AmTextHi, fontSize = 16.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажмите «Добавить» сверху, чтобы вставить vless://, подписку, happ:// или отсканировать QR-код.",
            color = AmTextLo, fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ProfileRow(
    name: String,
    host: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive)
                Icons.Outlined.RadioButtonChecked
            else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isActive) AmAccent else AmTextLo
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (isActive) AmAccent else AmTextHi,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
            )
            Text(host, color = AmTextLo, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, null, tint = AmTextLo)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, null, tint = AmTextLo)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, null, tint = AmTextLo)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    error: String?,
    loading: Boolean,
) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    // Авто-вставка vless:// если он в clipboard
    LaunchedEffect(Unit) {
        val cb = clipboard.getText()?.text ?: ""
        if (cb.startsWith("vless://", true) ||
            cb.startsWith("happ://", true) ||
            cb.startsWith("https://", true)) {
            text = cb
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmBgMid,
        titleContentColor = AmTextHi,
        textContentColor = AmTextLo,
        title = { Text("Добавить ключ или подписку") },
        text = {
            Column {
                Text(
                    "vless://… · https://… · happ://add/…",
                    color = AmTextLo, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("vless://…", color = AmTextLo) },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { text = it }
                    }) {
                        Text("Вставить из буфера", color = AmAccent, fontSize = 12.sp)
                    }
                }
                if (error != null) {
                    Text(
                        text = error,
                        color = androidx.compose.ui.graphics.Color(0xFFE8643C),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AmAccent,
                        trackColor = AmBgTop
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()) }, enabled = !loading) {
                Text("Добавить", color = AmAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = AmTextLo)
            }
        }
    )
}
