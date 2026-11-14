package com.junkfood.seal.util

import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateLong
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** How one engine update ended. The bookkeeping follows from this, never from an exception. */
sealed interface UpdateOutcome {
    data class Succeeded(val version: String?) : UpdateOutcome

    data class Failed(val reason: String, val cause: Throwable) : UpdateOutcome

    /** Another attempt holds the lock; nothing was measured and nothing is written. */
    data object Busy : UpdateOutcome
}

object UpdateUtil {

    /** Single-flight, never queued: a service-run update can hold this for minutes. */
    private val updateMutex = Mutex()

    suspend fun updateYtDlp(): UpdateOutcome {
        if (!updateMutex.tryLock()) return UpdateOutcome.Busy
        return try {
            withContext(Dispatchers.IO) {
                val outcome =
                    runCatching {
                            val channel =
                                when (YT_DLP_UPDATE_CHANNEL.getInt()) {
                                    YT_DLP_NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                                    else -> YoutubeDL.UpdateChannel.STABLE
                                }
                            YoutubeDL.getInstance()
                                .updateYoutubeDL(appContext = context, updateChannel = channel)
                        }
                        .fold(
                            onSuccess = { status ->
                                val version =
                                    YoutubeDL.getInstance().version(context)?.takeIf {
                                        it.isNotBlank()
                                    }
                                if (status == YoutubeDL.UpdateStatus.DONE && version != null) {
                                    PreferenceUtil.encodeString(YT_DLP_VERSION, version)
                                }
                                UpdateOutcome.Succeeded(version)
                            },
                            onFailure = {
                                UpdateOutcome.Failed(it.message ?: "unknown", it)
                            },
                        )
                applyBookkeeping(
                    UpdaterPolicy.bookkeeping(
                        outcome = outcome,
                        build = BuildConfig.VERSION_CODE,
                        now = System.currentTimeMillis(),
                        failureCount = YT_DLP_UPDATE_FAILURES.getInt(),
                    )
                )
                outcome
            }
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * A manual attempt starts from zero: the failure streak and the 6 h retry
     * window both belong to the previous attempt, and the row's failure suffix
     * must disappear while this one runs.
     */
    fun beginManualAttempt() = applyBookkeeping(UpdaterPolicy.manualReset())

    private fun applyBookkeeping(keys: Map<String, Any?>) {
        keys.forEach { (key, value) ->
            when (value) {
                null -> PreferenceUtil.remove(key)
                is Int -> key.updateInt(value)
                is Long -> key.updateLong(value)
                is String -> key.updateString(value)
            }
        }
    }
}
