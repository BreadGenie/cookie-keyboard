package org.futo.voiceinput.shared.opencl

import android.content.Context
import java.io.File

/** Bounded content-free diagnostics for ordinary dictation. */
object OpenClDiagnostics {
    private val rows = java.util.ArrayDeque<String>()
    @Synchronized fun record(context: Context, measurements: String) {
        // Call sites pass only fixed labels, durations and boolean flags, never exception messages.
        val file = File(context.noBackupFilesDir, "opencl-timings.txt")
        runCatching {
            if (rows.isEmpty() && file.exists()) file.readLines().takeLast(99).forEach { rows.addLast(it) }
            rows.addLast("utc_ms=${System.currentTimeMillis()} $measurements")
            while (rows.size > 100) rows.removeFirst()
            file.writeText(rows.joinToString("\n", postfix = "\n"))
        }
    }
    @Synchronized fun export(context: Context): String = runCatching {
        File(context.noBackupFilesDir, "opencl-timings.txt").readText()
    }.getOrDefault("No timing measurements yet.")
}
