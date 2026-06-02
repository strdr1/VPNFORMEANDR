package com.amsales.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.amsales.vpn.data.Repository

/**
 * Глобальная шина состояний VPN-сервиса. Сервис при любом изменении
 * (connect-progress / connected / disconnected / error) шлёт broadcast,
 * UI на него подписывается и обновляет кнопку.
 */
object VpnState {
    const val ACTION_STATE = "com.amsales.vpn.STATE"
    const val EXTRA_STATUS = "status"     // "off" / "connecting" / "on" / "error"
    const val EXTRA_TAG = "tag"            // имя активного профиля
    const val EXTRA_ERROR = "error"        // текст ошибки если status=error
    const val EXTRA_RX = "rx"               // bytes received
    const val EXTRA_TX = "tx"               // bytes sent

    fun broadcast(
        ctx: Context,
        status: String,
        tag: String = "",
        error: String = "",
        rx: Long = 0,
        tx: Long = 0,
    ) {
        ctx.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(ctx.packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_TAG, tag)
                .putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_RX, rx)
                .putExtra(EXTRA_TX, tx)
        )
    }
}

data class VpnUiState(
    val status: String = "off",       // off / connecting / on / error
    val activeTag: String = "",
    val error: String = "",
    val rx: Long = 0,
    val tx: Long = 0,
) {
    val isOn: Boolean get() = status == "on"
    val isConnecting: Boolean get() = status == "connecting"
    val isError: Boolean get() = status == "error"
}

/**
 * Composable-хук: возвращает живое VpnUiState. Подписывается на broadcast
 * при composition, отписывается при dispose.
 */
@Composable
fun rememberVpnState(): State<VpnUiState> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(VpnUiState()) }

    DisposableEffect(ctx) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                state.value = VpnUiState(
                    status = intent.getStringExtra(VpnState.EXTRA_STATUS) ?: "off",
                    activeTag = intent.getStringExtra(VpnState.EXTRA_TAG) ?: "",
                    error = intent.getStringExtra(VpnState.EXTRA_ERROR) ?: "",
                    rx = intent.getLongExtra(VpnState.EXTRA_RX, 0L),
                    tx = intent.getLongExtra(VpnState.EXTRA_TX, 0L),
                )
            }
        }
        val filter = IntentFilter(VpnState.ACTION_STATE)
        // Android 13+ требует явный флаг
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(ctx, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(rx, filter)
        }
        onDispose {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
        }
    }
    return state
}
