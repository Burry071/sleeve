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
 * Both hosts (the downloads page and the quick-download sheet) used to keep
 * their own parked action plus their own copy of the dialog callbacks, so the
 * two gates could drift. They now share this holder, and [PendingMeteredDialog]
 * is the only renderer of the question.
 */
@Stable
class PendingMetered {
    private var parked by mutableStateOf<Pair<Action, () -> Unit>?>(null)

    /** The action waiting for an answer, if any. */
    val action: Action?
        get() = parked?.first

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
    if (pending.action == null) return
    MeteredNetworkDialog(
        onDismissRequest = pending::dismiss,
        onAllowOnceConfirm = pending::allowOnce,
        onAllowAlwaysConfirm = pending::allowAlways,
    )
}
