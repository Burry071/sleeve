package com.junkfood.seal.util

/**
 * The rules behind the launch check and the three update call sites, as pure
 * functions: no Android types, so the bookkeeping map and the due-ness rule are
 * testable off-device. `UpdateUtil` owns the MMKV writes; this file only decides
 * what they should be.
 */
internal object UpdaterPolicy {

    /** A failure is retried on the next launch only after this long. */
    const val FAILURE_RETRY_MS = 6L * 60 * 60 * 1000

    /** After this many consecutive failures the automatic check stands down. */
    const val MAX_AUTO_FAILURES = 5

    /** An engine swap under a running transfer replaces files yt-dlp is using. */
    fun canAttempt(activeTransfers: Int): Boolean = activeTransfers == 0

    /**
     * The keys an attempt writes. A null value means "remove this key": the
     * stored properties are MMKV extensions and have no null write, so a clear
     * has to be an explicit delete.
     */
    fun bookkeeping(
        outcome: UpdateOutcome,
        build: Int,
        now: Long,
        failureCount: Int = 0,
    ): Map<String, Any?> =
        when (outcome) {
            is UpdateOutcome.Succeeded ->
                mapOf(
                    YT_DLP_UPDATE_TIME to now,
                    // The stamp records "this build was checked", not "it worked".
                    YT_DLP_UPDATE_BUILD to build,
                    YT_DLP_UPDATE_FAILURES to 0,
                    YT_DLP_LAST_FAILURE to null,
                    YT_DLP_LAST_FAILURE_REASON to null,
                )
            is UpdateOutcome.Failed ->
                mapOf(
                    YT_DLP_LAST_FAILURE to now,
                    YT_DLP_LAST_FAILURE_REASON to outcome.reason,
                    YT_DLP_UPDATE_BUILD to build,
                    YT_DLP_UPDATE_FAILURES to failureCount + 1,
                )
            UpdateOutcome.Busy -> emptyMap()
        }

    /** What a manual attempt clears before it starts. */
    fun manualReset(): Map<String, Any?> =
        mapOf(
            YT_DLP_LAST_FAILURE to null,
            YT_DLP_LAST_FAILURE_REASON to null,
            YT_DLP_UPDATE_FAILURES to 0,
        )

    /**
     * Whether the launch check should run now.
     *
     * `sameBuild = false` means the app was upgraded and the bundled engine was
     * replaced: the stored timestamp and failure streak describe a different
     * engine, so the check runs at once — still gated on auto-update and network
     * by the caller. The caller passes `failureCount = 0` in that case.
     *
     * The version string is deliberately not an input: it is written only on
     * `DONE`, so an `ALREADY_UP_TO_DATE` success would never fill it.
     */
    fun isDue(
        now: Long,
        lastSuccess: Long,
        lastFailure: Long,
        intervalMs: Long,
        failureCount: Int,
        autoUpdate: Boolean,
        sameBuild: Boolean,
    ): Boolean {
        if (!autoUpdate && lastSuccess > 0) return false
        if (failureCount >= MAX_AUTO_FAILURES) return false
        if (!sameBuild) return true
        return if (lastFailure > lastSuccess) {
            now >= lastFailure + FAILURE_RETRY_MS
        } else {
            now >= lastSuccess + intervalMs
        }
    }
}
