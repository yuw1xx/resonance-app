package dev.yuwixx.resonance.presentation.screens

import android.Manifest
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

data class ReceiveParams(
    val mode     : String,
    val ip       : String,
    val port     : Int,
    val token    : String,
    val title    : String,
    val artist   : String,
    val mimeType : String,
    val ext      : String,
    val ssid     : String? = null,
    val passphrase: String? = null,
) {
    val downloadUrl: String get() = "http://$ip:$port/$token"
    val rejectUrl: String get() = "http://$ip:$port/reject?token=$token"

    companion object {
        fun parse(uri: Uri): ReceiveParams? {
            return try {
                ReceiveParams(
                    mode      = uri.getQueryParameter("mode")   ?: return null,
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
            } catch (_: Exception) { null }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ReceiveSheet(
    uri          : Uri,
    onDismiss    : () -> Unit,
    onPlayNow    : (Song) -> Unit,
) {
    val params = remember(uri) { ReceiveParams.parse(uri) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = null,
        shape            = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        if (params == null) {
            InvalidLinkContent(onDismiss = onDismiss)
        } else {
            ReceiveContent(params = params, onDismiss = onDismiss, onPlayNow = onPlayNow)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ReceiveContent(
    params    : ReceiveParams,
    onDismiss : () -> Unit,
    onPlayNow : (Song) -> Unit,
) {
    val needsWritePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissions = if (needsWritePermission) {
        rememberMultiplePermissionsState(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    } else {
        rememberMultiplePermissionsState(emptyList())
    }

    if (!needsWritePermission || permissions.allPermissionsGranted) {
        ReceiveTransferContent(params = params, onDismiss = onDismiss, onPlayNow = onPlayNow)
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
            is ReceiveState.Prompting -> Icons.Rounded.HelpOutline
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
                                    val uri = Uri.fromFile(s.file)
                                    onPlayNow(Song(id=0L,uri=uri,title=params.title,artist=params.artist,artists=listOf(params.artist),albumArtist=params.artist,album="",albumId=0L,genre="",duration=0L,size=s.file.length(),bitrate=0,sampleRate=0,trackNumber=0,discNumber=0,year=0,dateAdded=System.currentTimeMillis(),dateModified=System.currentTimeMillis(),path=s.file.absolutePath,folder=s.file.parent?:"",mimeType=params.mimeType,replayGainTrack=null,replayGainAlbum=null,artworkUri=null))
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

private suspend fun performDownloadTask(
    context: Context, params: ReceiveParams, network: Network?, onProgress: (Float) -> Unit
): ReceiveState = withContext(Dispatchers.IO) {
    try {
        val destDir  = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Resonance").also { it.mkdirs() }
        val destFile = File(destDir, "${params.title}.${params.ext}")

        val url = URL(params.downloadUrl)
        val connection = (network?.openConnection(url) ?: url.openConnection()).apply {
            connectTimeout = 15_000
            readTimeout    = 300_000
            connect()
        }

        val totalBytes = connection.contentLengthLong
        connection.getInputStream().use { input ->
            destFile.outputStream().use { output ->
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
        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
        releaseNetwork(context, network)
        ReceiveState.Done(destFile)
    } catch (e: Exception) {
        releaseNetwork(context, network)
        ReceiveState.Error(e.message ?: "Transfer failed")
    }
}

private suspend fun performReject(params: ReceiveParams) = withContext(Dispatchers.IO) {
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