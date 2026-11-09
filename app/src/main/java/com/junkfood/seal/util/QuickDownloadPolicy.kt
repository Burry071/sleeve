package com.junkfood.seal.util

/** Extras Sleeve's own launcher shortcut attaches to QuickDownloadActivity. */
const val EXTRA_QUICK_NONCE = "quick_nonce"

internal const val INTERNAL_PREFS = "sleeve_internal"
internal const val QUICK_NONCE_KEY = "quick_nonce"

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
