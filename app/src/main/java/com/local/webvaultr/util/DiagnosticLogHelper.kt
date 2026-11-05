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

    fun log(tag: String, message: String) {
        val stamp = timeFormatter.format(Date())
        val entry = "[$stamp] [$tag] $message"
        logs.add(entry)
        if (logs.size > 500) {
            logs.removeAt(0)
        }
    }

    fun exportTroubleshootingLog(context: Context): File? {
        return try {
            val timestamp = fileDateFormatter.format(Date())
            val safeMake = Build.MANUFACTURER.replace(Regex("[^a-zA-Z0-9]"), "")
            val safeModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "")
            val fileName = "WebVaultr_Log_${safeMake}_${safeModel}_$timestamp.txt"

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, fileName)

            val content = buildString {
                appendLine("=== WebVaultr 0.8.4 Troubleshooting Log ===")
                appendLine("Device Make: ${Build.MANUFACTURER}")
                appendLine("Device Model: ${Build.MODEL}")
                appendLine("Android API Level: ${Build.VERSION.SDK_INT}")
                appendLine("Exported At: ${timeFormatter.format(Date())}")
                appendLine("=========================================")
                appendLine()
                if (logs.isEmpty()) {
                    appendLine("No diagnostic events recorded in this session yet.")
                } else {
                    logs.forEach { appendLine(it) }
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
