package com.junkfood.seal.util

/** Extras Sleeve's own launcher shortcut attaches to QuickDownloadActivity. */
const val EXTRA_QUICK_NONCE = "quick_nonce"

/**
 * Explicit action of the opt-in "Download clipboard link" launcher shortcut.
 * The shortcut is the ONLY sender; everything else with this action lands on
 * the confirm sheet via the same nonce fence as forged share launches.
 */
const val ACTION_QUICK_CLIPBOARD = "com.sleeve.app.QUICK_CLIPBOARD"

internal const val INTERNAL_PREFS = "sleeve_internal"
internal const val QUICK_NONCE_KEY = "quick_nonce"

/*
 * Quick-download policy.
 *
 * [withoutCookies] is the SINGLE COOKIE-AND-SESSION GATE of the quick lane.
 * Every session source — the cookies flag, cookie profiles, per-task headers,
 * template config files — must be neutralized here or it is not neutralized at
 * all. Today it clears the cookies flag, and the quick lane only ever builds
 * plain URL tasks (SleeveQuickDownload), so a custom command template and its
 * `--config-locations` cannot reach it: the sole CustomCommand producer is
 * DownloadDialogViewModel.runCommand, which only the confirm sheet calls.
 * QuickDownloadPolicyTest pins both halves of that.
 *
 * Accepted residual: a malicious launcher can read the nonce out of the
 * shortcut it hosts. Worst case is a cookie-less enqueue that is announced (per
 * the preference below) and cancellable. Do not re-litigate without a wider
 * blast radius.
 *
 * Announcement contract: toast + (if enabled) the per-task progress
 * notification with Cancel. The notification preference is authoritative;
 * quick mode never forces it on.
 */

/**
 * Silent quick mode is honored only for an in-app launch that carries the
 * nonce minted for the shortcut. Share actions always land on the confirm
 * sheet: the sender is unauthenticated by design, so the extra alone must
 * never grant a queue entry.
 */
internal fun isTrustedQuickIntent(
    hasQuickExtra: Boolean,
    isShareAction: Boolean,
    providedNonce: String?,
    expectedNonce: String?,
): Boolean =
    hasQuickExtra && !isShareAction && expectedNonce != null && providedNonce == expectedNonce

/**
 * Quick mode runs without the user's cookie session. A forged launch can then
 * at worst queue public media, never fetch through a signed-in account.
 */
internal fun DownloadUtil.DownloadPreferences.withoutCookies(): DownloadUtil.DownloadPreferences =
    if (cookies) copy(cookies = false) else this
