package com.amsales.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.R
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgBot
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo

/**
 * Главный экран с 4 вкладками снизу: VPN, Приложения, Опции, О программе.
 * Стиль — повторяет десктопный AmSalesVPN (тёмный, акцент-лайм).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onConnectRequest: () -> Unit,
    onDisconnectRequest: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var connected by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AmBgMid,
        bottomBar = {
            NavigationBar(
                containerColor = AmBgTop,
                contentColor = AmTextLo,
            ) {
                val items = listOf(
                    Triple(0, Icons.Outlined.VpnLock, R.string.tab_vpn),
                    Triple(1, Icons.Outlined.Apps, R.string.tab_apps),
                    Triple(2, Icons.Outlined.Settings, R.string.tab_options),
                    Triple(3, Icons.Outlined.Info, R.string.tab_about),
                )
                items.forEach { (idx, icon, lbl) ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(text = androidx.compose.ui.res.stringResource(lbl), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AmAccent,
                            selectedTextColor = AmAccent,
                            indicatorColor = AmBgMid,
                            unselectedIconColor = AmTextLo,
                            unselectedTextColor = AmTextLo,
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(listOf(AmBgTop, AmBgMid, AmBgBot))
                )
        ) {
            when (tab) {
                0 -> VpnTab(
                    connected = connected,
                    onToggle = {
                        if (connected) {
                            onDisconnectRequest()
                            connected = false
                        } else {
                            onConnectRequest()
                            // ⚠ Connected = true ставим оптимистично. На этапе 2
                            // тут будет реактивная подписка на состояние сервиса.
                            connected = true
                        }
                    }
                )
                1 -> AppsTab()
                2 -> OptionsTab()
                3 -> AboutTab()
            }
        }
    }
}

// ── VPN tab ────────────────────────────────────────────────────────────

@Composable
private fun VpnTab(connected: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AM.SALES VPN",
            color = AmTextHi,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.height(48.dp))

        // Большая power-кнопка (как в десктопе)
        Surface(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape),
            color = if (connected) AmAccent.copy(alpha = 0.12f) else AmBgTop,
            border = androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = if (connected) AmAccent else AmTextLo.copy(alpha = 0.5f)
            ),
            shape = CircleShape,
            onClick = onToggle,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (connected) AmAccent else AmTextLo,
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        Spacer(Modifier.height(36.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(
                if (connected) R.string.status_on else R.string.status_off
            ),
            color = if (connected) AmAccent else AmTextHi,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.tap_to_connect),
            color = AmTextLo,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ── Apps / Options / About — заглушки этапа 1 ─────────────────────────

@Composable
private fun OptionsTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Опции — этап 2", color = AmTextLo)
    }
}

@Composable
private fun AboutTab() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.about_title),
            color = AmTextHi, fontSize = 18.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            androidx.compose.ui.res.stringResource(R.string.about_version, "1.0.0"),
            color = AmTextLo, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            androidx.compose.ui.res.stringResource(R.string.about_desc),
            color = AmTextLo, fontSize = 12.sp, textAlign = TextAlign.Center
        )
    }
}
