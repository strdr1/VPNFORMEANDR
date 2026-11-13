package com.amsales.vpn.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверяет наличие обновлений через GitHub Releases API.
 *
 * Логика:
 *  1. GET https://api.github.com/repos/strdr1/VPNFORMEANDR/releases/latest
 *  2. Сравниваем tag_name (e.g. "v1.0.1") с BuildConfig.VERSION_NAME
 *  3. Если в релизе новее — возвращаем download URL APK-ассета
 *
 * Версия должна быть в формате semver: "1.0.0" или "v1.0.0".
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_API = "https://api.github.com/repos/strdr1/VPNFORMEANDR/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val releaseNotes: String,
    )

    /**
     * Проверка наличия обновлений. Возвращает UpdateInfo если есть свежее,
     * иначе null. Вызывать с IO-диспатчера.
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AmSalesVPN-Updater")
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "GitHub API HTTP ${conn.responseCode}")
                    return@withContext null
                }
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trimStart('v', 'V')
                val notes = json.optString("body").take(2000)
                if (tag.isEmpty()) return@withContext null
                if (!isNewer(tag, currentVersion)) {
                    Log.i(TAG, "current=$currentVersion latest=$tag — up to date")
                    return@withContext null
                }
                val assets = json.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val url = a.optString("browser_download_url")
                    val size = a.optLong("size")
                    if (url.isEmpty()) continue
                    Log.i(TAG, "update available: $tag size=${size / 1024}KB")
                    return@withContext UpdateInfo(tag, url, size, notes)
                }
                Log.w(TAG, "release $tag has no APK asset")
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            null
        }
    }

    /**
     * Сравнение semver-строк: 1.2.3 vs 1.2.10 -> 1.2.10 newer.
     * Поддерживает любое число компонент, отсутствующие = 0.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val c = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrNull(i) ?: 0
            val cv = c.getOrNull(i) ?: 0
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    /**
     * Скачивает APK во внутренний кэш. Прогресс [0..100] репортится
     * через onProgress. Возвращает File или null если что-то пошло не так.
     */
    suspend fun download(
        ctx: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            // Чистим старые APK перед новой загрузкой
            updatesDir.listFiles()?.forEach { it.delete() }
            val outFile = File(updatesDir, "update.apk")

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AmSalesVPN-Updater")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "download HTTP ${conn.responseCode}")
                    return@withContext null
                }
                val total = conn.contentLengthLong.coerceAtLeast(1L)
                var written = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                Log.i(TAG, "downloaded ${written / 1024}KB → ${outFile.absolutePath}")
                outFile
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }
}
