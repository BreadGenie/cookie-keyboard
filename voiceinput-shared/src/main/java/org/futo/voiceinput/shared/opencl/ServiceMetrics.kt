package org.futo.voiceinput.shared.opencl

/**
 * v18 DEBUG-ONLY parsed service METRICS row. Pure Kotlin (no Android
 * imports) so JVM unit tests pin the telemetry contract: verification
 * enum values, actual verification_ms/bytes_hashed counters, resident
 * generation/hit/token. A resident_verified row is only meaningful with
 * bytes_hashed=0 AND resident_hit=1; the replay asserts both (never a
 * bare label).
 */
internal data class ServiceMetrics(
    val verification: String,
    val verificationMs: Long,
    val bytesHashed: Long,
    val residentGeneration: Long,
    val residentHit: Int,
    val residentToken: Long,
    val contextHit: Boolean,
) {
    companion object {
        fun parse(row: String): ServiceMetrics? = runCatching {
            val fields = row.split(" ").mapNotNull { token ->
                val edge = token.indexOf('=')
                if (edge <= 0) null else token.substring(0, edge) to token.substring(edge + 1)
            }.toMap()
            ServiceMetrics(
                verification = fields.getValue("verification"),
                verificationMs = fields.getValue("verification_ms").toLong(),
                bytesHashed = fields.getValue("bytes_hashed").toLong(),
                residentGeneration = fields.getValue("resident_generation").toLong(),
                residentHit = fields.getValue("resident_hit").toInt(),
                residentToken = fields.getValue("resident_token").toLong(),
                contextHit = fields.getValue("context_hit").toBooleanStrict(),
            )
        }.getOrNull()
    }
}
