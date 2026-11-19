package org.futo.inputmethod.latin.whistle

/** Lease survives cancellation, timeout and unbind; only observed death permits replacement. */
internal class ReclamationGate {
    private var owner: Long? = null
    @Synchronized fun acquire(generation: Long): Boolean {
        if(owner != null) return false
        owner = generation; return true
    }
    @Synchronized fun confirmedDeath(generation: Long) {
        check(owner == generation); owner = null
    }
    /** Only valid when startService returned null (no matching service started). */
    @Synchronized fun notStarted(generation: Long) { check(owner == generation); owner = null }
}
