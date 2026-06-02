package com.amsales.vpn.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.amsales.vpn.ui.theme.AmSalesTheme
import com.amsales.vpn.vpn.AmSalesVpnService

/**
 * Главная активность. Compose UI запускается из onCreate().
 *
 * VPN на Android требует разрешения у пользователя через системный диалог
 * VpnService.prepare(). Делаем это перед стартом сервиса.
 */
class MainActivity : ComponentActivity() {

    // Регистрируем колбэк на результат VPN-разрешения системой
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            // Пользователь отказался — ничего не делаем
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AmSalesTheme {
                MainScreen(
                    onConnectRequest = {
                        val intent = VpnService.prepare(this)
                        if (intent != null) {
                            // Сначала нужно разрешение системы
                            vpnPrepareLauncher.launch(intent)
                        } else {
                            // Разрешение уже есть — стартуем сразу
                            startVpnService()
                        }
                    },
                    onDisconnectRequest = {
                        val intent = Intent(this, AmSalesVpnService::class.java)
                            .setAction(AmSalesVpnService.ACTION_DISCONNECT)
                        startService(intent)
                    }
                )
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AmSalesVpnService::class.java)
            .setAction(AmSalesVpnService.ACTION_CONNECT)
        startService(intent)
    }
}
