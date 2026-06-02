package com.amsales.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// AM.SALES палитра — глубокий чёрно-зелёный, акцент ядрёный лайм.
val AmAccent  = Color(0xFF9FE812)
val AmTextHi  = Color(0xFFF4F6F0)
val AmTextLo  = Color(0xFF7E8A72)
val AmBgTop   = Color(0xFF08110A)
val AmBgMid   = Color(0xFF060806)
val AmBgBot   = Color(0xFF0A0E08)

private val DarkColors = darkColorScheme(
    primary = AmAccent,
    onPrimary = AmBgMid,
    secondary = AmAccent,
    onSecondary = AmBgMid,
    background = AmBgMid,
    onBackground = AmTextHi,
    surface = AmBgMid,
    onSurface = AmTextHi,
    surfaceVariant = AmBgTop,
    onSurfaceVariant = AmTextLo,
)

@Composable
fun AmSalesTheme(
    darkTheme: Boolean = true,   // всегда тёмная — как в десктопе
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
