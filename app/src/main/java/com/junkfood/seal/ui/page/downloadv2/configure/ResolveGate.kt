package com.junkfood.seal.ui.page.downloadv2.configure

/**
 * Admits one resolve per source and link inside a cooldown, so a double share
 * cannot launch two metadata fetches for the same link while a different link
 * is still allowed through.
 */
internal class ResolveGate(
    private val cooldownMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastAcceptedAt = mutableMapOf<String, Long>()

    /** Returns true when the request may proceed and false when it duplicates
     * a request accepted less than [cooldownMs] ago. */
    fun accept(kind: String, url: String): Boolean {
        val now = clock()
        val key = "$kind:$url"
        val last = lastAcceptedAt[key]
        if (last != null && now - last < cooldownMs) return false
        lastAcceptedAt[key] = now
        lastAcceptedAt.entries.removeAll { now - it.value >= cooldownMs }
        return true
    }
}
