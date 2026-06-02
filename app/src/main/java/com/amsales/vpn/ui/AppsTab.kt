package com.amsales.vpn.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.amsales.vpn.R
import com.amsales.vpn.data.Settings
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Вкладка «Приложения» — чёрный список split-tunneling.
 *
 *   - Загружаем список всех приложений на устройстве (фильтруем системные)
 *   - Поиск по name + packageName
 *   - Чекбокс рядом с каждым → если отмечен, приложение в blacklist
 *   - При сохранении кладём в Settings.blacklistApps
 *   - При следующем запуске VPN это передастся через addDisallowedApplication
 */
@Composable
fun AppsTab() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var query by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var blacklist by remember { mutableStateOf(settings.blacklistApps) }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) { loadApps(context.packageManager) }
        loaded = true
    }

    val filtered = remember(allApps, query) {
        if (query.isBlank()) allApps
        else allApps.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовок
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.apps_title),
                color = AmTextHi,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.apps_subtitle),
                color = AmTextLo,
                fontSize = 12.sp
            )
        }

        // Поиск
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.apps_search_hint), color = AmTextLo) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = AmTextLo) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
        Spacer(Modifier.height(8.dp))

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.apps_loading), color = AmTextLo)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in blacklist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val new = if (checked) {
                                    blacklist - app.packageName
                                } else {
                                    blacklist + app.packageName
                                }
                                blacklist = new
                                settings.blacklistApps = new
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Иконка приложения
                        app.iconBitmap?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp)
                            )
                        } ?: Box(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, color = AmTextHi, fontSize = 14.sp)
                            Text(app.packageName, color = AmTextLo, fontSize = 11.sp)
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = AmAccent,
                                uncheckedColor = AmTextLo,
                                checkmarkColor = AmBgTop
                            )
                        )
                    }
                    HorizontalDivider(color = AmBgTop, thickness = 1.dp)
                }
            }
        }
    }
}

private data class AppItem(
    val name: String,
    val packageName: String,
    val iconBitmap: ImageBitmap?
)

/**
 * Загружает все «launchable» приложения (т.е. те что есть в меню запуска).
 * Исключаем сами себя и системные без иконки — иначе список бесконечный.
 */
private fun loadApps(pm: PackageManager): List<AppItem> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }
    val resolved = pm.queryIntentActivities(intent, 0)
    val seen = HashSet<String>()
    val out = ArrayList<AppItem>(resolved.size)
    for (r in resolved) {
        val pkg = r.activityInfo.packageName ?: continue
        if (!seen.add(pkg)) continue
        if (pkg == "com.amsales.vpn") continue
        val ai = r.activityInfo.applicationInfo
        val label = pm.getApplicationLabel(ai).toString()
        val icon = try {
            pm.getApplicationIcon(ai)
        } catch (e: Exception) { null }
        out += AppItem(
            name = label,
            packageName = pkg,
            iconBitmap = icon?.toImageBitmapOrNull()
        )
    }
    return out.sortedBy { it.name.lowercase() }
}

private fun Drawable.toImageBitmapOrNull(): ImageBitmap? = try {
    toBitmap(48, 48).asImageBitmap()
} catch (e: Exception) { null }
