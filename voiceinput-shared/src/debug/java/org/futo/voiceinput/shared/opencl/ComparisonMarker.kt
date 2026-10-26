package org.futo.voiceinput.shared.opencl

import java.io.File

/**
 * Crash/reinstall recovery marker for the debug comparison harness.
 *
 * A marker exists while a comparison process may own native work and is
 * deleted only after the production cleanup fence returned success. If the
 * app process died earlier, a new Activity may start a run only when the
 * previous OpenCL process is also gone; otherwise the user is told to
 * force-stop, which kills both processes. This never guesses that native work
 * stopped just because an Activity or process ended.
 */
class ComparisonMarker(
    private val file: File,
    private val currentPid: () -> Long,
    private val currentBootCount: () -> Int,
    private val otherProcessAlive: () -> Boolean,
) {
    enum class Recovery { NONE, CLEARED, SAME_PROCESS, OTHER_PROCESS_ALIVE }

    fun exists(): Boolean = file.exists()

    fun write() {
        val body = "pid=${currentPid()}\nboot=${currentBootCount()}\n"
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(body, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun clear(): Boolean = !file.exists() || file.delete()

    fun recover(): Recovery {
        if (!file.exists()) return Recovery.NONE
        val fields = try {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
        } catch (_: Exception) {
            return Recovery.OTHER_PROCESS_ALIVE
        }
        val pid = fields["pid"]?.toLongOrNull() ?: return Recovery.OTHER_PROCESS_ALIVE
        val boot = fields["boot"]?.toIntOrNull() ?: return Recovery.OTHER_PROCESS_ALIVE
        if (pid == currentPid() && boot == currentBootCount()) return Recovery.SAME_PROCESS
        if (!otherProcessAlive()) {
            return if (file.delete()) Recovery.CLEARED else Recovery.OTHER_PROCESS_ALIVE
        }
        return Recovery.OTHER_PROCESS_ALIVE
    }
}
