package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junkfood.seal.ui.page.download.MeteredNetworkDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.util.CELLULAR_DOWNLOAD
import com.junkfood.seal.util.NetworkPolicy
import com.junkfood.seal.util.PreferenceUtil.updateBoolean

/**
 * The one place a download action waits for the user's metered-network answer.
 *
 * The hosts (the downloads page, the quick-download sheet, and the command
 * lane) share this holder, and [PendingMeteredDialog] is the only renderer of
 * the question, so no lane can start a download past a parked gate silently.
 */
@Stable
class PendingMetered {
    private var parked by mutableStateOf<Pair<Action?, () -> Unit>?>(null)

    /** True while a launch is waiting for the user's answer. */
    val blocked: Boolean
        get() = parked != null

    /**
     * Runs [proceed] now, unless [action] would start a download on a metered
     * network — then it parks the action and [proceed] until the user answers.
     */
    fun post(action: Action, proceed: () -> Unit) {
        if (action.startsDownload() && !NetworkPolicy.canStartDownloads()) {
            parked = action to proceed
            return
        }
        proceed()
    }

    /**
     * Parks [proceed] when the metered gate would block it. For hosts whose
     * launch is unconditionally a download (the command lane's Start button),
     * so no sheet action has to be invented just to ask the question.
     */
    fun postDownload(proceed: () -> Unit) {
        if (!NetworkPolicy.canStartDownloads()) {
            parked = null to proceed
            return
        }
        proceed()
    }

    fun dismiss() {
        parked = null
    }

    fun allowOnce() {
        NetworkPolicy.allowMeteredOnce()
        resumeParked()
    }

    fun allowAlways() {
        CELLULAR_DOWNLOAD.updateBoolean(true)
        resumeParked()
    }

    private fun resumeParked() {
        val (_, proceed) = parked ?: return
        parked = null
        proceed()
    }
}

@Composable
fun PendingMeteredDialog(pending: PendingMetered) {
    if (!pending.blocked) return
    MeteredNetworkDialog(
        onDismissRequest = pending::dismiss,
        onAllowOnceConfirm = pending::allowOnce,
        onAllowAlwaysConfirm = pending::allowAlways,
    )
}
