// M3U folder sync: keeps playlists in step with .m3u/.m3u8 files in a folder the user picked
// (Storage Access Framework tree, so it works on any storage, including SD cards and synced
// folders like Syncthing). Each linked file is remembered in playlist_sources with its
// last-modified time, so only new or changed files are read.
//
//  - New file      → imported as a playlist (or linked to a same-named one, whose songs it replaces)
//  - Changed file  → re-imported into its playlist
//  - Write-back on → a playlist edited in the app since the last sync is written to its file,
//                    unless the file itself also changed (then the file wins)
//  - Deleted file  → the link is dropped; the playlist stays
package dev.yuwixx.resonance.data.playlist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.PlaylistSourceDao
import dev.yuwixx.resonance.data.database.entity.PlaylistSourceEntity
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistFolderSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ResonancePreferences,
    private val importer: M3uImporter,
    private val sourceDao: PlaylistSourceDao,
    private val playlistDao: PlaylistDao,
    private val playlistRepository: PlaylistRepository,
) {
    data class Report(val imported: Int, val updated: Int, val written: Int, val failed: Int)

    private val mutex = Mutex()

    private suspend fun folder(): DocumentFile? {
        val uri = prefs.playlistSyncFolder.first()?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() }
    }

    private fun DocumentFile.isPlaylistFile(): Boolean {
        val n = name?.lowercase() ?: return false
        return isFile && (n.endsWith(".m3u") || n.endsWith(".m3u8"))
    }

    /** Imports new/changed files and (if enabled) writes back playlists edited in the app. */
    suspend fun syncNow(): Report = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = folder() ?: return@withContext Report(0, 0, 0, 0)
            val writeBack = prefs.playlistSyncExport.first()
            val relative = prefs.playlistSyncRelativePaths.first()
            val files = dir.listFiles().filter { it.isPlaylistFile() }
            val present = files.map { it.uri.toString() }.toHashSet()
            var imported = 0; var updated = 0; var written = 0; var failed = 0
            // Loaded on first need only (write-back), since building it maps the whole library.
            var playlists: List<dev.yuwixx.resonance.data.model.Playlist>? = null

            for (file in files) {
                val uri = file.uri
                val modified = file.lastModified()
                val source = sourceDao.getByUri(uri.toString())
                val linked = source?.let { playlistDao.getPlaylistById(it.playlistId) }
                try {
                    when {
                        source == null || linked == null -> {
                            val result = importer.import(uri, M3uImporter.ConflictMode.REPLACE)
                            sourceDao.upsert(PlaylistSourceEntity(result.playlistId, uri.toString(), file.name ?: result.name, modified, System.currentTimeMillis()))
                            imported++
                        }
                        modified != source.lastModified -> {
                            importer.reimportInto(source.playlistId, uri)
                            sourceDao.upsert(source.copy(lastModified = modified, lastSyncedAt = System.currentTimeMillis()))
                            updated++
                        }
                        writeBack && linked.dateModified > source.lastSyncedAt -> {
                            val all = playlists ?: playlistRepository.allPlaylists.first().also { playlists = it }
                            val playlist = all.firstOrNull { it.id == linked.id } ?: continue
                            importer.exportToUri(playlist, uri, if (relative) importer.baseDirFor(uri) else null)
                            val newModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: modified
                            sourceDao.upsert(source.copy(lastModified = newModified, lastSyncedAt = System.currentTimeMillis()))
                            written++
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Folder sync failed for ${file.name}", e)
                    failed++
                }
            }
            // Files that disappeared: forget the link, keep the playlist.
            sourceDao.getAll().filter { it.documentUri !in present }.forEach { sourceDao.delete(it.playlistId) }
            Report(imported, updated, written, failed)
        }
    }

    /** Writes every playlist that isn't linked to a file yet into the folder, and links it. */
    suspend fun exportAll(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = folder() ?: return@withContext 0
            val relative = prefs.playlistSyncRelativePaths.first()
            val linkedIds = sourceDao.getAll().map { it.playlistId }.toHashSet()
            var count = 0
            playlistRepository.allPlaylists.first()
                .filter { !it.isMix && it.id !in linkedIds && it.songs.isNotEmpty() }
                .forEach { playlist ->
                    val fileName = playlist.name.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".m3u8"
                    val file = dir.findFile(fileName) ?: dir.createFile("audio/x-mpegurl", fileName) ?: return@forEach
                    runCatching {
                        importer.exportToUri(playlist, file.uri, if (relative) importer.baseDirFor(file.uri) else null)
                        val modified = DocumentFile.fromSingleUri(context, file.uri)?.lastModified() ?: 0L
                        sourceDao.upsert(PlaylistSourceEntity(playlist.id, file.uri.toString(), fileName, modified, System.currentTimeMillis()))
                        count++
                    }.onFailure { Log.w(TAG, "Export failed for ${playlist.name}", it) }
                }
            count
        }
    }

    /** Sets (or clears) the sync folder, keeping read/write access across reboots. */
    suspend fun setFolder(uri: Uri?) {
        val old = prefs.playlistSyncFolder.first()?.let(Uri::parse)
        if (old != null && old != uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            sourceDao.getAll().forEach { sourceDao.delete(it.playlistId) }
        }
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.setPlaylistSyncFolder(uri?.toString())
        schedule(uri != null)
    }

    /** Periodic background sync while a folder is set; battery-friendly constraints. */
    fun schedule(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<PlaylistFolderSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        wm.enqueue(OneTimeWorkRequestBuilder<PlaylistFolderSyncWorker>().build())
    }

    private companion object {
        const val TAG = "PlaylistFolderSync"
        const val WORK_NAME = "playlist_folder_sync"
    }
}

@HiltWorker
class PlaylistFolderSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderSync: PlaylistFolderSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        folderSync.syncNow()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
