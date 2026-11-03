package com.junkfood.seal.util

import android.util.Log
import androidx.room.Room
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.AppDatabase
import com.junkfood.seal.database.backup.Backup
import com.junkfood.seal.database.backup.BackupUtil.BackupType
import com.junkfood.seal.database.backup.BackupUtil.decodeToBackup
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.CookieProfile
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.database.objects.OptionShortcut
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DatabaseUtil {
    private const val DATABASE_NAME = "app_database"
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME).build()
    private val dao = db.videoInfoDao()

    fun insertInfo(vararg infoList: DownloadedVideoInfo) {
        applicationScope.launch(Dispatchers.IO) {
            infoList.forEach { dao.insertInfoDistinctByPath(it) }
        }
    }

    init {
        applicationScope.launch {
            getTemplateFlow().collect {
                if (it.isEmpty()) PreferenceUtil.initializeTemplateSample()
            }
        }
    }

    fun getDownloadHistoryFlow() = dao.getDownloadHistoryFlow()

    private suspend fun getDownloadHistory() = dao.getDownloadHistory()

    fun getTemplateFlow() = dao.getTemplateFlow()

    fun getCookiesFlow() = dao.getCookieProfileFlow()

    fun getShortcuts() = dao.getOptionShortcuts()

    suspend fun deleteShortcut(shortcut: OptionShortcut) = dao.deleteShortcut(shortcut)

    suspend fun insertShortcut(shortcut: OptionShortcut) = dao.insertShortcut(shortcut)

    suspend fun getCookieById(id: Int) = dao.getCookieById(id)

    suspend fun deleteCookieProfile(profile: CookieProfile) = dao.deleteCookieProfile(profile)

    suspend fun insertCookieProfile(profile: CookieProfile) = dao.insertCookieProfile(profile)

    suspend fun updateCookieProfile(profile: CookieProfile) = dao.updateCookieProfile(profile)

    suspend fun getTemplateList() = dao.getTemplateList()

    suspend fun getShortcutList() = dao.getShortcutList()

    suspend fun deleteInfoList(infoList: List<DownloadedVideoInfo>, deleteFile: Boolean = false) {
        dao.deleteInfoList(infoList)
        infoList.forEach { info -> if (deleteFile) FileUtil.deleteFile(info.videoPath) }
    }

    suspend fun getInfoById(id: Int): DownloadedVideoInfo = dao.getInfoById(id)

    suspend fun deleteInfoById(id: Int) = dao.deleteInfoById(id)

    suspend fun insertTemplate(commandTemplate: CommandTemplate) =
        dao.insertTemplate(commandTemplate)

    /**
     * Recover completed downloads whose history rows were lost: media files
     * sitting in the download directories with no matching row. Earlier
     * builds lost rows when the post-download scan matched by title only and
     * `--trim-filenames`/sanitized titles broke the match, so the insert ran
     * on an empty path list. Path-distinct insert keeps this idempotent;
     * private-mode files are intentionally not listed and stay excluded.
     */
    fun backfillDownloadHistoryFromDisk() {
        applicationScope.launch(Dispatchers.IO) {
            val knownPaths =
                getDownloadHistory().mapTo(mutableSetOf()) { it.videoPath }
            val dirs =
                listOf(
                        runCatching { App.videoDownloadDir }.getOrNull(),
                        runCatching { App.audioDownloadDir }.getOrNull(),
                    )
                    .filterNotNull()
                    .map(::File)
                    .filter { it.exists() }
            val recovered =
                dirs
                    .flatMap { dir ->
                        dir.walkTopDown()
                            .filter { it.isFile }
                            .filter {
                                it.extension.lowercase() in DirectMediaRules.MEDIA_EXTS &&
                                    it.path.split(File.separatorChar).none { seg -> seg == "tmp" }
                            }
                    }
                    .filter { it.absolutePath !in knownPaths }
                    .map { file ->
                        DownloadedVideoInfo(
                            id = 0,
                            videoTitle = file.nameWithoutExtension,
                            videoAuthor = "",
                            videoUrl = "",
                            thumbnailUrl = "",
                            videoPath = file.absolutePath,
                            extractor = "file",
                        )
                    }
            recovered.forEach { dao.insertInfoDistinctByPath(it) }
            if (recovered.isNotEmpty()) {
                Log.i(TAG, "Backfilled ${recovered.size} download(s) from disk into history")
            }
        }
    }

    suspend fun updateTemplate(commandTemplate: CommandTemplate) {
        dao.updateTemplate(commandTemplate)
    }

    suspend fun importBackup(backup: Backup, types: Set<BackupType>): Int {
        var cnt = 0
        backup.run {
            if (types.contains(BackupType.DownloadHistory)) {
                val itemList = getDownloadHistory()

                if (!downloadHistory.isNullOrEmpty()) {
                    dao.insertAll(
                        downloadHistory
                            .filterNot { itemList.contains(it) }
                            .map { it.copy(id = 0) }
                            .also { cnt += it.size }
                    )
                }
            }
            if (types.contains(BackupType.CommandTemplate)) {
                if (templates != null) {
                    val templateList = getTemplateList()
                    dao.importTemplates(
                        templateList
                            .filterNot { templateList.contains(it) }
                            .map { it.copy(id = 0) }
                            .also { cnt += it.size }
                    )
                }
            }
            if (types.contains(BackupType.CommandShortcut)) {
                val shortcutList = getShortcutList()
                if (shortcuts != null) {
                    dao.insertAllShortcuts(
                        shortcuts
                            .filterNot { shortcutList.contains(it) }
                            .map { it.copy(id = 0) }
                            .also { cnt += it.size }
                    )
                }
            }
        }
        return cnt
    }

    suspend fun importTemplatesFromJson(json: String): Int {
        json
            .decodeToBackup()
            .onSuccess { backup ->
                return importBackup(
                    backup = backup,
                    types = setOf(BackupType.CommandTemplate, BackupType.CommandShortcut),
                )
            }
            .onFailure { it.printStackTrace() }
        return 0
    }

    suspend fun deleteTemplateById(id: Int) = dao.deleteTemplateById(id)

    suspend fun deleteTemplates(templates: List<CommandTemplate>) = dao.deleteTemplates(templates)

    private const val TAG = "DatabaseUtil"
}
