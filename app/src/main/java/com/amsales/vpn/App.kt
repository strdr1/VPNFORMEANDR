package com.amsales.vpn

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальный UncaughtExceptionHandler — пишет фатальные ошибки в
 * filesDir/crash.log. Вкладка «Диагностика» потом читает оттуда.
 *
 * Без этого, если процесс падает (например, libbox не загрузилась),
 * пользователь видит только «приложение закрылось» и ничего больше.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, "crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                crashFile.appendText(
                    "\n\n=== CRASH $ts (thread: ${thread.name}) ===\n$sw\n"
                )
                Log.e("AmSalesCrash", "Uncaught in ${thread.name}", throwable)
            } catch (_: Throwable) {
                // не должны валиться сами
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
