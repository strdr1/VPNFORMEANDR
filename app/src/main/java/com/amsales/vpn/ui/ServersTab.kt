package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import com.amsales.vpn.data.VlessKey
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo

/**
 * Вкладка «Серверы» — управление VLESS-ключами.
 *  + Список с радио-выбором активного
 *  + Кнопка «Добавить» открывает диалог вставки vless://-URI
 *  + Иконки: копировать, удалить
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersTab() {
    val repo = LocalRepository.current
    val clipboard = LocalClipboardManager.current
    var profiles by remember { mutableStateOf(repo.profiles()) }
    var currentIdx by remember { mutableIntStateOf(repo.currentIndex()) }
    var showAdd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Header
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
            FilledTonalButton(
                onClick = { showAdd = true },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AmAccent.copy(alpha = 0.18f),
                    contentColor = AmAccent
                )
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Добавить", fontSize = 13.sp)
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(profiles) { idx, p ->
                ProfileRow(
                    name = p.name,
                    host = p.host,
                    isActive = idx == currentIdx,
                    onSelect = {
                        currentIdx = idx; repo.setCurrentIndex(idx)
                    },
                    onCopy = {
                        clipboard.setText(AnnotatedString(p.raw))
                    },
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

    if (showAdd) {
        AddKeyDialog(
            onDismiss = { showAdd = false; error = null },
            onAdd = { uri ->
                if (repo.addKey(uri)) {
                    profiles = repo.profiles()
                    showAdd = false
                    error = null
                } else {
                    error = "Не удалось распознать vless://-ключ"
                }
            },
            error = error
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
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive)
                Icons.Outlined.RadioButtonChecked
            else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isActive) AmAccent else AmTextLo
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (isActive) AmAccent else AmTextHi,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
            )
            Text(host, color = AmTextLo, fontSize = 11.sp)
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
    error: String?
) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmBgMid,
        titleContentColor = AmTextHi,
        textContentColor = AmTextLo,
        title = { Text("Добавить ключ") },
        text = {
            Column {
                Text(
                    "Вставьте VLESS-ключ (vless://...)",
                    color = AmTextLo, fontSize = 12.sp
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()) }) {
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

