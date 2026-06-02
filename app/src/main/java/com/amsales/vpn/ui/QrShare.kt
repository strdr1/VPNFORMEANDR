package com.amsales.vpn.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amsales.vpn.ui.theme.AmAccent
import com.amsales.vpn.ui.theme.AmBgMid
import com.amsales.vpn.ui.theme.AmBgTop
import com.amsales.vpn.ui.theme.AmTextHi
import com.amsales.vpn.ui.theme.AmTextLo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Диалог «Поделиться ключом»:
 *  - Большой QR-код с URI ключа
 *  - Сам URI текстом (с маленькой кнопкой Копировать)
 */
@Composable
fun ShareKeyDialog(
    keyUri: String,
    keyName: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val qrBitmap = remember(keyUri) { generateQrBitmap(keyUri, 600) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmBgMid,
        titleContentColor = AmTextHi,
        title = { Text("Поделиться ключом") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(keyName, color = AmAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(12.dp)
                    )
                } else {
                    Box(
                        Modifier.size(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("QR не сгенерирован", color = AmTextLo)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = AmBgTop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = keyUri,
                        color = AmTextLo,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(keyUri))
                onDismiss()
            }) { Text("Копировать", color = AmAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = AmTextLo)
            }
        }
    )
}

private fun generateQrBitmap(text: String, size: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
