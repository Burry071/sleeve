package com.junkfood.seal.util

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.ui.page.settings.network.Cookie

/** Chromium's cookie store, read once into the rows yt-dlp receives. */
internal object CookieStore {

    @CheckResult
    fun listFromDatabase(): Result<List<Cookie>> = runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        SQLiteDatabase.openDatabase(
                context.dataDir.resolve("app_webview/Default/Cookies").absolutePath,
                null,
                OPEN_READONLY,
            )
            .run {
                val projection =
                    arrayOf(
                        DownloadUtil.CookieScheme.HOST,
                        DownloadUtil.CookieScheme.EXPIRY,
                        DownloadUtil.CookieScheme.PATH,
                        DownloadUtil.CookieScheme.NAME,
                        DownloadUtil.CookieScheme.VALUE,
                        DownloadUtil.CookieScheme.SECURE,
                    )
                val cookieList = mutableListOf<Cookie>()
                query("cookies", projection, null, null, null, null, null).run {
                    while (moveToNext()) {
                        val expiry = getLong(getColumnIndexOrThrow(DownloadUtil.CookieScheme.EXPIRY))
                        val name = getString(getColumnIndexOrThrow(DownloadUtil.CookieScheme.NAME))
                        val value = getString(getColumnIndexOrThrow(DownloadUtil.CookieScheme.VALUE))
                        val path = getString(getColumnIndexOrThrow(DownloadUtil.CookieScheme.PATH))
                        val secure = getLong(getColumnIndexOrThrow(DownloadUtil.CookieScheme.SECURE)) == 1L
                        val hostKey = getString(getColumnIndexOrThrow(DownloadUtil.CookieScheme.HOST))

                        val host = if (hostKey[0] != '.') ".$hostKey" else hostKey
                        cookieList.add(
                            Cookie(
                                domain = host,
                                name = name,
                                value = value,
                                path = path,
                                secure = secure,
                                expiry = expiry,
                            )
                        )
                    }
                    close()
                }
                close()
                cookieList
            }
    }
}
