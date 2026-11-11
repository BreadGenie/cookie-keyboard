package org.futo.inputmethod.latin.whistle

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutionException

/** Only enum tokens and JVM symbols enter reports. Never Throwable messages or file/line data. */
internal enum class WhistleStage { MODEL_VERIFY, IMPORT, LEASE, START, BIND, CONNECT, LOAD, FEED, FINISH, REQUEST, TRANSACT, ACK, SCHEMA, DEATH, CONTROL }
internal enum class WhistleCode { EXCEPTION, ABSENT, UNVERIFIED, NATIVE_REJECTED, NATIVE_EMPTY, TIMEOUT, REJECTED, USER_CANCEL, WATCHDOG, WORKER_LOST }
internal class WhistleFailure(val report: String) : IllegalStateException()
internal object WhistleDiagnostics {
    // Both inputs are existing content-free diagnostic exports; never pass the ephemeral transcript.
    fun comparisonReport(versionCode: Int, versionName: String, whistleReport: String, timings: String) =
        "Cookie debug v$versionCode ($versionName)\n$whistleReport\nVoice timing diagnostics (before editor insertion):\n$timings"

    private fun symbol(s: String) = s.take(120).replace(Regex("[^A-Za-z0-9_.$<>]"), "_")
    fun report(stage: WhistleStage, code: WhistleCode, error: Throwable? = null): String {
        if(error is WhistleFailure) return error.report
        val e = if(error is ExecutionException) error.cause ?: error else error
        if(e is WhistleFailure) return e.report
        val schema = if(e is WhistleSchemaException) " invariant=${e.invariant} key=${e.key} type=${e.type}" else ""
        return "stage=$stage code=$code" + schema + if(e == null) "" else
            " exception=${symbol(e.javaClass.name)} stack=" + e.stackTrace.take(6).joinToString(",") { symbol(it.className) + "#" + symbol(it.methodName) }
    }
    fun failure(stage: WhistleStage, code: WhistleCode, error: Throwable? = null) = WhistleFailure(report(stage, code, error))
    fun checkedRemote(value: String): WhistleFailure {
        check(value.length <= 2048 && value.matches(Regex("[A-Za-z0-9_.$<>#= ,]+")))
        return WhistleFailure(value)
    }
    inline fun <T> at(stage: WhistleStage, action: () -> T): T = try { action() }
        catch(e: Exception) { throw failure(stage, WhistleCode.EXCEPTION, e) }
        catch(e: LinkageError) { throw failure(stage, WhistleCode.EXCEPTION, e) }
}
internal object WhistleModel {
    const val SIZE = 16919407L
    const val HASH = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
    fun verify(file: File): Boolean {
        if(!file.isFile || file.length() != SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536); var count = 0L
            while(true) { val n = input.read(buffer); if(n < 0) break; count += n; if(count > SIZE) return false; digest.update(buffer,0,n) }
            if(count != SIZE) return false
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == HASH
    }
}
