package com.amsales.vpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.R
import com.amsales.vpn.data.LocalRepository
import com.amsales.vpn.data.Repository
import com.amsales.vpn.ui.theme.*
import com.amsales.vpn.vpn.rememberVpnState

/**
 * Главный экран — 5 табов снизу (как в десктопе плюс «Серверы»):
 *   VPN | Серверы | Приложения | Опции | О программе
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onConnectRequest: () -> Unit,
    onDisconnectRequest: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { Repository(context).also { it.ensureDefaults() } }

    CompositionLocalProvider(LocalRepository provides repository) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val vpnState by rememberVpnState()

        Scaffold(
            containerColor = AmBgMid,
            bottomBar = { BottomBar(tab) { tab = it } }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Brush.verticalGradient(listOf(AmBgTop, AmBgMid, AmBgBot)))
            ) {
                when (tab) {
                    0 -> VpnTab(
                        state = vpnState,
                        onToggle = {
                            if (vpnState.isOn || vpnState.isConnecting) onDisconnectRequest()
                            else onConnectRequest()
                        }
                    )
                    1 -> ServersTab()
                    2 -> AppsTab()
                    3 -> OptionsTab()
                    4 -> DiagnosticsTab()
                    5 -> AboutTab()
                }
            }
        }
    }
}

@Composable
private fun BottomBar(active: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = AmBgTop, contentColor = AmTextLo) {
        val items = listOf(
            Triple(0, Icons.Outlined.VpnLock,         R.string.tab_vpn),
            Triple(1, Icons.Outlined.Dns,             R.string.tab_servers),
            Triple(2, Icons.Outlined.Apps,            R.string.tab_apps),
            Triple(3, Icons.Outlined.Settings,        R.string.tab_options),
            Triple(4, Icons.Outlined.BugReport,       R.string.tab_diag),
            Triple(5, Icons.Outlined.Info,            R.string.tab_about),
        )
        items.forEach { (idx, icon, lbl) ->
            NavigationBarItem(
                selected = active == idx,
                onClick = { onSelect(idx) },
                icon = { Icon(icon, null) },
                label = {
                    Text(
                        text = stringResource(lbl),
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                    )
                },
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

// ── VPN tab — главный экран с большой кнопкой ─────────────────────────

@Composable
private fun VpnTab(state: com.amsales.vpn.vpn.VpnUiState, onToggle: () -> Unit) {
    val repo = LocalRepository.current
    var currentIdx by remember { mutableIntStateOf(repo.currentIndex()) }
    val profiles = remember(currentIdx) { repo.profiles() }
    val activeName = profiles.getOrNull(currentIdx)?.name ?: "—"
    val connected = state.isOn
    val connecting = state.isConnecting
    var mode by remember { mutableStateOf(repo.vpnMode) }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.amsales_logo),
            contentDescription = null,
            modifier = Modifier.height(32.dp)
        )
        Spacer(Modifier.height(20.dp))

        // Селектор профиля прямо на главной — стрелки + название.
        // Тап по названию = диалог-выбор из всего списка.
        ProfileSelector(
            current = activeName,
            host = profiles.getOrNull(currentIdx)?.host ?: "",
            canPrev = currentIdx > 0,
            canNext = currentIdx < profiles.size - 1,
            allProfiles = profiles.map { it.name to it.host },
            onPrev = {
                if (currentIdx > 0) {
                    currentIdx--; repo.setCurrentIndex(currentIdx)
                }
            },
            onNext = {
                if (currentIdx < profiles.size - 1) {
                    currentIdx++; repo.setCurrentIndex(currentIdx)
                }
            },
            onPick = { idx ->
                currentIdx = idx; repo.setCurrentIndex(idx)
            }
        )

        Spacer(Modifier.height(16.dp))

        // Переключатель режима: VPN (полный туннель) / DPI (только обход без VPN)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(AmBgTop)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeChip(
                label = "VPN",
                selected = mode == "vpn",
                enabled = !connected && !connecting,
                onClick = { mode = "vpn"; repo.vpnMode = "vpn" }
            )
            ModeChip(
                label = "Zapret",
                selected = mode == "dpi",
                enabled = !connected && !connecting,
                onClick = { mode = "dpi"; repo.vpnMode = "dpi" }
            )
        }

        Spacer(Modifier.height(20.dp))

        PowerButton(connected = connected, connecting = connecting, onClick = onToggle)

        Spacer(Modifier.height(36.dp))
        Crossfade(
            targetState = state.status,
            animationSpec = tween(600),
            label = "status"
        ) { st ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val (label, color) = when (st) {
                    "on"         -> stringResource(R.string.status_on) to AmAccent
                    "connecting" -> stringResource(R.string.status_connecting) to AmTextHi
                    "error"      -> "Ошибка" to androidx.compose.ui.graphics.Color(0xFFE8643C)
                    else         -> stringResource(R.string.status_off) to AmTextHi
                }
                Text(
                    text = label,
                    color = color,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (st) {
                        "on"         -> "· $activeName ·"
                        "error"      -> state.error.ifEmpty { "Подключение не удалось" }
                        else         -> stringResource(R.string.tap_to_connect)
                    },
                    color = AmTextLo,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                if (state.isOn && (state.rx > 0 || state.tx > 0)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "↓ ${formatBytes(state.rx)}   ↑ ${formatBytes(state.tx)}",
                        color = AmTextLo,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    if (b < 1024 * 1024) return "${b / 1024} KB"
    if (b < 1024L * 1024 * 1024) return "%.1f MB".format(b / 1048576.0)
    return "%.2f GB".format(b / 1073741824.0)
}

/**
 * Селектор активного профиля прямо на главном экране.
 *   ◀  STAROSTIN-CF · 78.17.103.241  ▶
 * Тап по названию → bottom-sheet со списком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelector(
    current: String,
    host: String,
    canPrev: Boolean,
    canNext: Boolean,
    allProfiles: List<Pair<String, String>>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.padding(horizontal = 24.dp),
        shape = RoundedCornerShape(14.dp),
        color = AmBgTop,
        border = androidx.compose.foundation.BorderStroke(1.dp, AmTextLo.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = canPrev) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = null,
                    tint = if (canPrev) AmAccent else AmTextLo.copy(alpha = 0.3f)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.Transparent)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    onClick = { showSheet = true },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = current,
                            color = AmTextHi,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = host,
                            color = AmTextLo,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            IconButton(onClick = onNext, enabled = canNext) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = if (canNext) AmAccent else AmTextLo.copy(alpha = 0.3f)
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = AmBgTop,
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = "Выбор профиля",
                    color = AmTextHi,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                allProfiles.forEachIndexed { idx, (name, host) ->
                    val selected = name == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(idx); showSheet = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (selected) AmAccent else AmTextLo.copy(alpha = 0.3f))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = AmTextHi, fontSize = 14.sp)
                            Text(host, color = AmTextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Большая power-кнопка с анимациями:
 *  - постоянный плавный «pulse»: масштаб 1.0 ↔ 1.02 + opacity-свечение
 *  - снаружи радиальное свечение (более яркое когда connected)
 *  - press effect: scale 0.95
 */
@Composable
private fun PowerButton(connected: Boolean, connecting: Boolean = false, onClick: () -> Unit) {
    // Постоянная пульсация
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (connected || connecting) 0.45f else 0.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (connecting) 700 else 1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (connected || connecting) 1.04f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (connecting) 700 else 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    // Цветовой переход
    val ringColor by animateColorAsState(
        targetValue = if (connected) AmAccent else AmTextLo.copy(alpha = 0.45f),
        animationSpec = tween(600),
        label = "ring"
    )
    val iconTint by animateColorAsState(
        targetValue = if (connected) AmAccent else AmTextLo,
        animationSpec = tween(600),
        label = "icon"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(260.dp)
    ) {
        // Радиальное свечение (большое мягкое пятно)
        Box(
            modifier = Modifier
                .size(260.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        0.0f to AmAccent.copy(alpha = pulseAlpha),
                        1.0f to AmAccent.copy(alpha = 0.0f)
                    )
                )
        )
        // Основной круг-кнопка
        Surface(
            modifier = Modifier.size(180.dp),
            shape = CircleShape,
            color = if (connected)
                AmAccent.copy(alpha = 0.10f) else AmBgTop,
            border = androidx.compose.foundation.BorderStroke(2.dp, ringColor),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(80.dp)
                )
            }
        }
    }
}


// ── About — отдельный экран ────────────────────────────────────────────

@Composable
fun AboutTab() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.amsales_emblem),
            contentDescription = null,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_title),
            color = AmTextHi, fontSize = 18.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_version, "1.0.0"),
            color = AmTextLo, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_desc),
            color = AmTextLo, fontSize = 12.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) AmAccent else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) AmBgTop else AmTextLo
    Surface(
        color = bg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .let { if (enabled) it.clickable { onClick() } else it }
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
