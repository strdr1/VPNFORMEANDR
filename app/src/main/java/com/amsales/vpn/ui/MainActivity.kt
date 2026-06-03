package com.amsales.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    // Уведомления (Android 13+ требует runtime-permission)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* без разницы — без уведа сервис всё равно работает */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Запрашиваем разрешение на показ уведомлений (Android 13+).
        // Без него foreground-service notification не появится и юзер
        // не увидит кнопки переключения режима/отключения.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(perm)
            }
        }

        setContent {
            AmSalesTheme {
                MainScreen(
                    onConnectRequest = {
                        val intent = VpnService.prepare(this)
                        if (intent != null) {
                            vpnPrepareLauncher.launch(intent)
                        } else {
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
