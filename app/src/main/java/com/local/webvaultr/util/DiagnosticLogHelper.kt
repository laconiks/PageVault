package com.local.webvaultr.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DiagnosticLogHelper {
    private val logs = CopyOnWriteArrayList<String>()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            synchronized(this) {
                if (logFile == null) {
                    logFile = File(context.filesDir, "webvaultr_rolling_diag.log")
                }
            }
        }
    }

    fun log(tag: String, message: String) {
        val stamp = timeFormatter.format(Date())
        val entry = "[$stamp] [$tag] $message"
        logs.add(entry)
        if (logs.size > 500) {
            logs.removeAt(0)
        }
        logFile?.let { file ->
            runCatching {
                synchronized(this) {
                    file.appendText("$entry\n")
                    if (file.length() > 250 * 1024) {
                        val bytes = file.readBytes()
                        val keepBytes = bytes.takeLast(150 * 1024).toByteArray()
                        file.writeBytes(keepBytes)
                    }
                }
            }
        }
    }

    fun exportTroubleshootingLog(context: Context): File? {
        return try {
            init(context)
            val timestamp = fileDateFormatter.format(Date())
            val safeMake = Build.MANUFACTURER.replace(Regex("[^a-zA-Z0-9]"), "")
            val safeModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "")
            val fileName = "WebVaultr_Log_${safeMake}_${safeModel}_$timestamp.txt"

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, fileName)

            val linesToExport = if (logs.isNotEmpty()) {
                logs.toList()
            } else {
                runCatching {
                    logFile?.takeIf { it.exists() }?.readLines()
                }.getOrNull().orEmpty()
            }

            val content = buildString {
                appendLine("=== WebVaultr 0.9 Troubleshooting Log ===")
                appendLine("Device Make: ${Build.MANUFACTURER}")
                appendLine("Device Model: ${Build.MODEL}")
                appendLine("Android API Level: ${Build.VERSION.SDK_INT}")
                appendLine("Exported At: ${timeFormatter.format(Date())}")
                appendLine("=========================================")
                appendLine()
                if (linesToExport.isEmpty()) {
                    appendLine("No diagnostic events recorded in this session yet.")
                } else {
                    linesToExport.forEach { appendLine(it) }
                }
            }
            outFile.writeText(content)
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
