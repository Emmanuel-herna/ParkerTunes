package com.parker.tunes

import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the most recent uncaught app exception so it can be copied from the
 * next launch on devices where Android Studio/ADB is not available.
 */
object CrashDiagnostics {
    private const val FILE_NAME = "plyra-crash-report.txt"

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                File(application.filesDir, FILE_NAME).writeText(
                    "Plyra crash report\nThread: ${thread.name}\n\n${writer}"
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun read(context: Context): String? =
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.isFile && it.length() > 0L }
                ?.readText()
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }
}
