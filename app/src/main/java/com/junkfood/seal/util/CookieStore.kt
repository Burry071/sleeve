package com.junkfood.seal.util

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.ui.page.settings.network.Cookie

/** Chromium's cookie store, read once into the rows yt-dlp receives. */
internal object CookieStore {

    internal fun normalizeCookieHost(hostKey: String?): String? {
        if (hostKey.isNullOrBlank()) return null
        return if (hostKey[0] != '.') ".$hostKey" else hostKey
    }

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
            .use { db ->
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
                db.query("cookies", projection, null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val hostKey =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.HOST)
                            )
                        val host = normalizeCookieHost(hostKey) ?: continue
                        val expiry =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.EXPIRY)
                            )
                        val name =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.NAME)
                            ) ?: ""
                        val value =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.VALUE)
                            ) ?: ""
                        val path =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.PATH)
                            ) ?: "/"
                        val secure =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadUtil.CookieScheme.SECURE)
                            ) == 1L

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
                }
                cookieList
            }
    }
}
