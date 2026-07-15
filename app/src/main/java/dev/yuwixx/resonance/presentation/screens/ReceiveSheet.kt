package dev.yuwixx.resonance.presentation.screens

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

data class ReceiveParams(
    val mode     : String,
    val ip       : String = "",
    val port     : Int = 0,
    val token    : String = "",
    val remoteUrl: String? = null,
    val title    : String,
    val artist   : String,
    val mimeType : String,
    val ext      : String,
    val ssid     : String? = null,
    val passphrase: String? = null,
) {
    val downloadUrl: String get() = if (mode == "remote") remoteUrl ?: "" else "http://$ip:$port/$token"
    val rejectUrl: String get() = "http://$ip:$port/reject?token=$token"

    companion object {
        fun parse(uri: Uri): ReceiveParams? {
            return try {
                val mode = uri.getQueryParameter("mode") ?: return null
                if (mode == "remote-multi") return null
                if (mode == "remote") {
                    ReceiveParams(
                        mode      = mode,
                        remoteUrl = uri.getQueryParameter("url") ?: return null,
                        title     = uri.getQueryParameter("title")  ?: "Unknown",
                        artist    = uri.getQueryParameter("artist") ?: "Unknown",
                        mimeType  = uri.getQueryParameter("mime")   ?: "audio/mpeg",
                        ext       = uri.getQueryParameter("ext")    ?: "mp3",
                    )
                } else {
                    ReceiveParams(
                        mode      = mode,
                        ip        = uri.getQueryParameter("ip")     ?: return null,
                        port      = uri.getQueryParameter("port")?.toInt() ?: return null,
                        token     = uri.getQueryParameter("token")  ?: return null,
                        title     = uri.getQueryParameter("title")  ?: "Unknown",
                        artist    = uri.getQueryParameter("artist") ?: "Unknown",
                        mimeType  = uri.getQueryParameter("mime")   ?: "audio/mpeg",
                        ext       = uri.getQueryParameter("ext")    ?: "mp3",
                        ssid      = uri.getQueryParameter("ssid"),
                        passphrase = uri.getQueryParameter("pass"),
                    )
                }
            } catch (_: Exception) { null }
        }
    }
}

/** Params for a `mode=remote-multi` link — a manifest token resolving to a list of songs. */
private data class RemoteMultiParams(val serverUrl: String, val manifestToken: String) {
    companion object {
        fun parse(uri: Uri): RemoteMultiParams? {
            val serverUrl = uri.getQueryParameter("url") ?: return null
            val manifestToken = uri.getQueryParameter("manifest") ?: return null
            return RemoteMultiParams(serverUrl.trimEnd('/'), manifestToken)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ReceiveSheet(
    uri          : Uri,
    onDismiss    : () -> Unit,
    onPlayNow    : (List<Song>) -> Unit,
) {
    val mode = remember(uri) { uri.getQueryParameter("mode") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = null,
        shape            = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        if (mode == "remote-multi") {
            val multiParams = remember(uri) { RemoteMultiParams.parse(uri) }
            if (multiParams == null) {
                InvalidLinkContent(onDismiss = onDismiss)
            } else {
                StoragePermissionGate(onDismiss = onDismiss) {
                    ReceiveMultiTransferContent(params = multiParams, onDismiss = onDismiss, onPlayNow = onPlayNow)
                }
            }
        } else {
            val params = remember(uri) { ReceiveParams.parse(uri) }
            if (params == null) {
                InvalidLinkContent(onDismiss = onDismiss)
            } else {
                StoragePermissionGate(onDismiss = onDismiss) {
                    ReceiveTransferContent(params = params, onDismiss = onDismiss, onPlayNow = { song -> onPlayNow(listOf(song)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun StoragePermissionGate(
    onDismiss : () -> Unit,
    content   : @Composable () -> Unit,
) {
    val needsWritePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissions = if (needsWritePermission) {
        rememberMultiplePermissionsState(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    } else {
        rememberMultiplePermissionsState(emptyList())
    }

    if (!needsWritePermission || permissions.allPermissionsGranted) {
        content()
    } else {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .navigationBarsPadding(),
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Storage permission needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Resonance needs storage access to save the incoming track.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Button(onClick = permissions::launchMultiplePermissionRequest, shape = MaterialTheme.shapes.medium) { Text("Grant permission") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

private sealed class ReceiveState {
    data class  Connecting(val detail: String) : ReceiveState()
    data class  Prompting(val network: Network?) : ReceiveState()
    data class  Downloading(val progress: Float, val network: Network?) : ReceiveState()
    data class  Done(val file: File)         : ReceiveState()
    data class  Error(val message: String)   : ReceiveState()
}

private data class ManifestSongEntry(val token: String, val title: String, val artist: String, val mime: String, val ext: String)

private sealed class SongDownloadStatus {
    data object Pending : SongDownloadStatus()
    data class  Downloading(val progress: Float) : SongDownloadStatus()
    data class  Done(val file: File) : SongDownloadStatus()
    data class  Failed(val message: String) : SongDownloadStatus()
}

private sealed class MultiReceiveState {
    data object Loading : MultiReceiveState()
    data class  Ready(val songs: List<ManifestSongEntry>, val statuses: Map<String, SongDownloadStatus>) : MultiReceiveState()
    data class  Error(val message: String) : MultiReceiveState()
}

@Composable
private fun ReceiveTransferContent(
    params    : ReceiveParams,
    onDismiss : () -> Unit,
    onPlayNow : (Song) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ReceiveState>(ReceiveState.Connecting("Preparing…")) }
    val scope   = rememberCoroutineScope()

    LaunchedEffect(params) {
        state = if (params.mode == "p2p") {
            ReceiveState.Connecting("Tap ‘Allow’ if your phone asks to switch Wi-Fi…")
        } else {
            ReceiveState.Connecting("Connecting…")
        }

        withContext(Dispatchers.IO) {
            try {
                val network: Network? = if (params.mode == "p2p") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        connectToP2pNetwork(context, params)
                    } else {
                        throw Exception("Wi-Fi Direct requires Android 10 or later")
                    }
                } else null

                if (params.mode == "p2p" && network == null) {
                    throw Exception("Could not join sender's Wi-Fi Direct network")
                }

                state = ReceiveState.Prompting(network)
            } catch (e: Exception) {
                state = ReceiveState.Error(e.message ?: "Connection failed")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )
        Spacer(Modifier.height(4.dp))

        val iconRes = when (state) {
            is ReceiveState.Done  -> Icons.Rounded.CheckCircle
            is ReceiveState.Error -> Icons.Rounded.ErrorOutline
            is ReceiveState.Prompting -> Icons.AutoMirrored.Rounded.HelpOutline
            else                  -> if (params.mode == "p2p") Icons.Rounded.Wifi else Icons.Rounded.Download
        }
        val iconTint = when (state) {
            is ReceiveState.Done  -> MaterialTheme.colorScheme.primary
            is ReceiveState.Error -> MaterialTheme.colorScheme.error
            is ReceiveState.Prompting -> MaterialTheme.colorScheme.primary
            else                  -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(iconRes, null, tint = iconTint, modifier = Modifier.size(48.dp))

        Text(text = params.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(text = params.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(4.dp))

        AnimatedContent(
            targetState = state,
            transitionSpec = { (fadeIn(tween(200)) + slideInVertically { it / 4 }).togetherWith(fadeOut(tween(150))) },
            label = "receive_state",
        ) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (s) {
                    is ReceiveState.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    is ReceiveState.Prompting -> {
                        Text("Accept this song?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        performReject(params)
                                        releaseNetwork(context, s.network)
                                        onDismiss()
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                            ) { Text("Decline") }

                            Button(
                                onClick = {
                                    state = ReceiveState.Downloading(0f, s.network)
                                    scope.launch {
                                        state = performDownloadTask(context, params, s.network) { progress ->
                                            state = ReceiveState.Downloading(progress, s.network)
                                        }
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                            ) { Text("Accept") }
                        }
                    }

                    is ReceiveState.Downloading -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    is ReceiveState.Done -> {
                        Text("Saved to Music/Resonance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Done") }
                            Button(
                                onClick = {
                                    onPlayNow(songFromReceivedFile(s.file, params.title, params.artist, params.mimeType))
                                },
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play now")
                            }
                        }
                    }

                    is ReceiveState.Error -> {
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiveMultiTransferContent(
    params    : RemoteMultiParams,
    onDismiss : () -> Unit,
    onPlayNow : (List<Song>) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<MultiReceiveState>(MultiReceiveState.Loading) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(params) {
        state = try {
            val songs = fetchManifest(params.serverUrl, params.manifestToken)
            MultiReceiveState.Ready(songs, songs.associate { it.token to SongDownloadStatus.Pending as SongDownloadStatus })
        } catch (e: Exception) {
            MultiReceiveState.Error(e.message ?: "Could not load song list")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )
        Spacer(Modifier.height(4.dp))

        when (val s = state) {
            is MultiReceiveState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                Text("Loading songs…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is MultiReceiveState.Error -> {
                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Close") }
            }

            is MultiReceiveState.Ready -> {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text(
                    "${s.songs.size} song${if (s.songs.size != 1) "s" else ""} shared with you",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.songs.forEach { song ->
                        val status = s.statuses[song.token] ?: SongDownloadStatus.Pending
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                when (status) {
                                    is SongDownloadStatus.Pending -> Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    is SongDownloadStatus.Downloading -> CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    is SongDownloadStatus.Done -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    is SongDownloadStatus.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }

                val allDone = s.songs.isNotEmpty() && s.songs.all { s.statuses[it.token] is SongDownloadStatus.Done }
                val anyDone = s.songs.any { s.statuses[it.token] is SongDownloadStatus.Done }
                val isDownloading = s.statuses.values.any { it is SongDownloadStatus.Downloading }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text(if (allDone) "Done" else "Close") }
                    Button(
                        onClick = {
                            if (allDone) {
                                val songsToPlay = s.songs.mapNotNull { entry ->
                                    (s.statuses[entry.token] as? SongDownloadStatus.Done)?.file?.let { file ->
                                        songFromReceivedFile(file, entry.title, entry.artist, entry.mime)
                                    }
                                }
                                onPlayNow(songsToPlay)
                            } else {
                                val songsToDownload = s.songs
                                scope.launch {
                                    fun updateStatus(token: String, status: SongDownloadStatus) {
                                        val latest = state
                                        if (latest is MultiReceiveState.Ready) {
                                            state = latest.copy(statuses = latest.statuses + (token to status))
                                        }
                                    }
                                    for (entry in songsToDownload) {
                                        val alreadyDone = (state as? MultiReceiveState.Ready)?.statuses?.get(entry.token) is SongDownloadStatus.Done
                                        if (alreadyDone) continue
                                        updateStatus(entry.token, SongDownloadStatus.Downloading(0f))
                                        try {
                                            val downloadUrl = "${params.serverUrl}/f/${entry.token}"
                                            val file = downloadToStorage(context, null, downloadUrl, entry.title, entry.ext, entry.mime) { progress ->
                                                updateStatus(entry.token, SongDownloadStatus.Downloading(progress))
                                            }
                                            updateStatus(entry.token, SongDownloadStatus.Done(file))
                                        } catch (e: Exception) {
                                            updateStatus(entry.token, SongDownloadStatus.Failed(e.message ?: "Failed"))
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isDownloading,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary, trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f))
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading…")
                        } else if (allDone) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play all")
                        } else {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (anyDone) "Retry failed" else "Download all")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun fetchManifest(serverUrl: String, manifestToken: String): List<ManifestSongEntry> = withContext(Dispatchers.IO) {
    val url = URL("$serverUrl/manifest/$manifestToken")
    val connection = url.openConnection() as java.net.HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    if (connection.responseCode != 200) throw Exception("Could not load song list (HTTP ${connection.responseCode})")
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    val array = JSONArray(body)
    (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        ManifestSongEntry(
            token  = obj.getString("token"),
            title  = obj.optString("title", "Unknown"),
            artist = obj.optString("artist", "Unknown"),
            mime   = obj.optString("mime", "audio/mpeg"),
            ext    = obj.optString("ext", "mp3"),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun connectToP2pNetwork(context: Context, params: ReceiveParams): Network? {
    val ssid       = params.ssid       ?: return null
    val passphrase = params.passphrase ?: return null
    val cm         = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(passphrase).build()
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()

    return suspendCancellableCoroutine { cont ->
        var resumed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!resumed) {
                    resumed = true
                    cm.bindProcessToNetwork(network)
                    cont.resume(network)
                }
            }
            override fun onUnavailable() {
                if (!resumed) { resumed = true; cont.resume(null) }
            }
        }
        cm.requestNetwork(request, callback, 60_000)
        cont.invokeOnCancellation {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }
}

/** Downloads one file to app-scoped storage via MediaStore. Shared by the single-song and
 *  manifest-based multi-song receive flows. */
private suspend fun downloadToStorage(
    context: Context, network: Network?, downloadUrl: String, title: String, ext: String, mimeType: String,
    onProgress: (Float) -> Unit,
): File = withContext(Dispatchers.IO) {
    val url = URL(downloadUrl)
    val connection = (network?.openConnection(url) ?: url.openConnection()).apply {
        connectTimeout = 15_000
        readTimeout    = 300_000
        connect()
    }

    val totalBytes = connection.contentLengthLong
    val tempFile = File.createTempFile("resonance_recv", ".tmp", context.cacheDir)
    connection.getInputStream().use { input ->
        tempFile.outputStream().use { output ->
            val buf  = ByteArray(64 * 1024)
            var read = 0L
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                read += n
                if (totalBytes > 0) onProgress((read.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
        }
    }
    val destFile = tempFile.inputStream().use { input ->
        dev.yuwixx.resonance.data.util.IncomingFileStorage.saveIncoming(
            context = context,
            input = input,
            title = title,
            ext = ext,
            mimeType = mimeType,
        )
    }
    tempFile.delete()
    destFile
}

private suspend fun performDownloadTask(
    context: Context, params: ReceiveParams, network: Network?, onProgress: (Float) -> Unit
): ReceiveState = withContext(Dispatchers.IO) {
    try {
        val destFile = downloadToStorage(context, network, params.downloadUrl, params.title, params.ext, params.mimeType, onProgress)
        releaseNetwork(context, network)
        ReceiveState.Done(destFile)
    } catch (e: Exception) {
        releaseNetwork(context, network)
        ReceiveState.Error(e.message ?: "Transfer failed")
    }
}

private fun songFromReceivedFile(file: File, title: String, artist: String, mimeType: String): Song = Song(
    id = 0L, uri = Uri.fromFile(file), title = title, artist = artist, artists = listOf(artist),
    albumArtist = artist, album = "", albumId = 0L, genre = "", duration = 0L, size = file.length(),
    bitrate = 0, sampleRate = 0, trackNumber = 0, discNumber = 0, year = 0,
    dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(),
    path = file.absolutePath, folder = file.parent ?: "", mimeType = mimeType,
    replayGainTrack = null, replayGainAlbum = null, artworkUri = null,
)

private suspend fun performReject(params: ReceiveParams) = withContext(Dispatchers.IO) {
    // The internet relay has no /reject endpoint and no live sender to notify — declining
    // just means "don't download"; the uploaded file still expires via its own TTL.
    if (params.mode == "remote") return@withContext
    try {
        val url = URL(params.rejectUrl)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.responseCode
    } catch (e: Exception) { /* ignore */ }
}

private fun releaseNetwork(context: Context, network: Network?) {
    if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
    }
}

@Composable
private fun InvalidLinkContent(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Text("Invalid share link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("This QR code doesn't contain a valid Resonance transfer link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Button(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Close") }
    }
}