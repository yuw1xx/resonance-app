package dev.yuwixx.resonance.data.playlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.PlaylistSourceDao
import dev.yuwixx.resonance.data.database.entity.PlaylistSourceEntity
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.PlaylistRepository
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps playlists in step with .m3u/.m3u8 files in a folder picked through SAF, so SD cards and
 * Syncthing folders work too. Each linked file is stored in playlist_sources with its
 * last-modified time, so only new or changed files are read.
 *
 * - New file: imported as a playlist, or linked to an existing one with the same name.
 * - Changed file: re-imported into its playlist.
 * - With write-back on, a playlist edited in the app is written to its file, unless the file
 *   changed too; then the file wins.
 * - Deleted file: the link is dropped and the playlist stays.
 */
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
            val ignored = prefs.playlistSyncIgnored.first()
            val allFiles = dir.listFiles().filter { it.isPlaylistFile() }
            val present = allFiles.map { it.uri.toString() }.toHashSet()
            val files = allFiles.filter { it.uri.toString() !in ignored }
            var imported = 0; var updated = 0; var written = 0; var failed = 0
            // Loaded on first need only (write-back), since building it maps the whole library.
            var playlists: List<Playlist>? = null

            for (file in files) {
                val uri = file.uri
                val modified = versionOf(file)
                val source = sourceDao.getByUri(uri.toString())
                val linked = source?.let { playlistDao.getPlaylistById(it.playlistId) }
                try {
                    when {
                        source == null || linked == null -> {
                            // A same-named playlist is taken over, unless another file already
                            // feeds it: then this file becomes a playlist of its own.
                            val existingId = importer.preview(listOf(uri)).firstOrNull()?.existingPlaylistId
                            val takenByOtherFile = existingId != null &&
                                sourceDao.getByPlaylist(existingId)?.let { it.documentUri != uri.toString() } == true
                            val mode = if (takenByOtherFile) M3uImporter.ConflictMode.KEEP_BOTH else M3uImporter.ConflictMode.REPLACE
                            val result = importer.import(uri, mode)
                            sourceDao.upsert(
                                PlaylistSourceEntity(
                                    result.playlistId,
                                    uri.toString(),
                                    file.name ?: result.name,
                                    modified,
                                    System.currentTimeMillis(),
                                )
                            )
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
                            val newModified = DocumentFile.fromSingleUri(context, uri)?.let(::versionOf) ?: modified
                            sourceDao.upsert(source.copy(lastModified = newModified, lastSyncedAt = System.currentTimeMillis()))
                            written++
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Folder sync failed for ${file.name}", e)
                    failed++
                }
            }
            // Files that disappeared: forget the link, keep the playlist.
            sourceDao.getAll().filter { it.documentUri !in present }.forEach { sourceDao.delete(it.playlistId) }
            if (ignored.any { it !in present }) prefs.setPlaylistSyncIgnored(ignored.filterTo(HashSet()) { it in present })
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
                    val fileName = uniqueFileName(dir, playlist.name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                    val file = dir.createFile("audio/x-mpegurl", fileName) ?: return@forEach
                    runCatchingCancellable {
                        importer.exportToUri(playlist, file.uri, if (relative) importer.baseDirFor(file.uri) else null)
                        val modified = DocumentFile.fromSingleUri(context, file.uri)?.let(::versionOf) ?: 0L
                        sourceDao.upsert(PlaylistSourceEntity(playlist.id, file.uri.toString(), fileName, modified, System.currentTimeMillis()))
                        count++
                    }.onFailure { Log.w(TAG, "Export failed for ${playlist.name}", it) }
                }
            count
        }
    }

    /** Never overwrites an existing file: "Mix.m3u8", then "Mix (2).m3u8", … */
    private fun uniqueFileName(dir: DocumentFile, base: String): String {
        var name = "$base.m3u8"
        var n = 2
        while (dir.findFile(name) != null) name = "$base (${n++}).m3u8"
        return name
    }

    /** Change marker for a file. Some providers report no modification time; use a checksum then. */
    private fun versionOf(file: DocumentFile): Long {
        file.lastModified().takeIf { it > 0 }?.let { return it }
        return runCatchingCancellable {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val crc = CRC32()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
                crc.value
            }
        }.getOrNull() ?: 0L
    }

    /**
     * Sets (or clears) the sync folder, keeping read/write access across reboots.
     * Returns false if the folder's access couldn't be kept.
     */
    suspend fun setFolder(uri: Uri?): Boolean {
        val old = prefs.playlistSyncFolder.first()?.let(Uri::parse)
        if (old != null && old != uri) {
            runCatchingCancellable {
                context.contentResolver.releasePersistableUriPermission(old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            sourceDao.getAll().forEach { sourceDao.delete(it.playlistId) }
        }
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't keep access to $uri", e)
                return false
            }
        }
        if (old != uri) prefs.setPlaylistSyncIgnored(emptySet())
        prefs.setPlaylistSyncFolder(uri?.toString())
        schedule(uri != null)
        return true
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
