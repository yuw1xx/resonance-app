package dev.yuwixx.resonance.presentation.screens

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.service.NearbyShareManager
import dev.yuwixx.resonance.data.service.ShareTransferManager
import dev.yuwixx.resonance.presentation.components.SectionCard
import dev.yuwixx.resonance.presentation.viewmodel.ShareViewModel
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ShareSheet(
    viewModel: ShareViewModel,
    currentSongs: List<Song>,
    onDismiss: () -> Unit,
    onPlayNow: (Song) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(currentSongs) { viewModel.preselectSongs(currentSongs) }
    DisposableEffect(Unit) { onDispose { viewModel.clearSelection() } }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.disconnectNearby()
            viewModel.cancelTransfer()
            onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        ShareSheetContent(viewModel = viewModel, onPlayNow = onPlayNow, onDismiss = {
            viewModel.disconnectNearby()
            onDismiss()
        })
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ShareSheetContent(
    viewModel: ShareViewModel,
    onPlayNow: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val nearbyPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            rememberMultiplePermissionsState(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            rememberMultiplePermissionsState(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
        else -> {
            rememberMultiplePermissionsState(listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    val context = LocalContext.current
    var hasRequested by remember { mutableStateOf(false) }

    if (viewModel.isGrapheneOs || nearbyPermissions.allPermissionsGranted) {
        ShareMain(viewModel = viewModel, onPlayNow = onPlayNow, onDismiss = onDismiss)
    } else {
        val isPermanentlyDenied = hasRequested && nearbyPermissions.permissions.any { !it.status.isGranted && !it.status.shouldShowRationale }
        PermissionPrompt(
            isPermanentlyDenied = isPermanentlyDenied,
            onRequest = {
                if (isPermanentlyDenied) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", context.packageName, null) }
                    context.startActivity(intent)
                } else {
                    hasRequested = true
                    nearbyPermissions.launchMultiplePermissionRequest()
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ShareMain(
    viewModel: ShareViewModel,
    onPlayNow: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val nearbyState     by viewModel.nearbyState.collectAsState()
    val devices         by viewModel.nearbyDevices.collectAsState()
    val selectedSongs   by viewModel.selectedSongs.collectAsState()
    val selectedSong    = selectedSongs.singleOrNull()
    val transferState   by viewModel.transferState.collectAsState()
    val allSongs        by viewModel.allSongs.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()
    val incomingFile    by viewModel.incomingFile.collectAsState()
    val remoteTtlHours  by viewModel.remoteTtlHours.collectAsState()

    val haptics = LocalHapticFeedback.current
    var showSongPicker by remember { mutableStateOf(false) }

    val context  = LocalContext.current
    val activity = context as? Activity
    LaunchedEffect(Unit) { viewModel.startScanning(activity) }

    LaunchedEffect(nearbyState) {
        if (nearbyState is NearbyShareManager.NearbyState.SendSuccess) {
            kotlinx.coroutines.delay(1_600)
            onDismiss()
        }
    }

    if (incomingRequest != null) {
        val req = incomingRequest!!
        AlertDialog(
            onDismissRequest = { viewModel.rejectNearbyRequest(req.endpointId) },
            title = { Text("Accept incoming song?") },
            text = { Text("${req.senderName} wants to send you\n${req.songTitle} · ${req.songArtist}\n\nConnected via Bluetooth or Wi-Fi — tap Accept to receive.") },
            confirmButton = { Button(onClick = { viewModel.acceptNearbyRequest(req.endpointId) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { viewModel.rejectNearbyRequest(req.endpointId) }) { Text("Decline") } }
        )
    }

    if (incomingFile != null) {
        val iFile = incomingFile!!
        AlertDialog(
            onDismissRequest = { viewModel.clearIncoming() },
            icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Song received!") },
            text = {
                Text(
                    "${iFile.songTitle} · ${iFile.songArtist}\n\nSaved to Music/Resonance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ext = iFile.file.extension.ifEmpty { "mp3" }
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/mpeg"
                        onPlayNow(Song(
                            id              = 0L,
                            uri             = android.net.Uri.fromFile(iFile.file),
                            title           = iFile.songTitle,
                            artist          = iFile.songArtist,
                            artists         = listOf(iFile.songArtist),
                            albumArtist     = iFile.songArtist,
                            album           = "",
                            albumId         = 0L,
                            genre           = "",
                            duration        = 0L,
                            size            = iFile.file.length(),
                            bitrate         = 0,
                            sampleRate      = 0,
                            trackNumber     = 0,
                            discNumber      = 0,
                            year            = 0,
                            dateAdded       = System.currentTimeMillis(),
                            dateModified    = System.currentTimeMillis(),
                            path            = iFile.file.absolutePath,
                            folder          = iFile.file.parent ?: "",
                            mimeType        = mime,
                            replayGainTrack = null,
                            replayGainAlbum = null,
                            artworkUri      = null,
                        ))
                        viewModel.clearIncoming()
                        onDismiss()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play now")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearIncoming() }) { Text("Done") }
            },
        )
    }

    val readyState = transferState as? ShareTransferManager.TransferState.Ready
    val servingState = transferState as? ShareTransferManager.TransferState.Serving
    val uploadingState = transferState as? ShareTransferManager.TransferState.Uploading
    val transferErrorState = transferState as? ShareTransferManager.TransferState.Error
    // NoWifi has its own dedicated dialog (NoWifiDialog below) — don't double up on it here.
    val showQrDialog = readyState != null || servingState != null || uploadingState != null || transferErrorState != null

    if (showQrDialog) QrTransferDialog(song = selectedSong, songCount = selectedSongs.size, transferState = transferState, ttlHours = remoteTtlHours, onDismiss = { viewModel.cancelTransfer() })
    if (nearbyState is NearbyShareManager.NearbyState.LocationDisabled) LocationDisabledDialog(onDismiss = { viewModel.disconnectNearby() })
    if (transferState is ShareTransferManager.TransferState.NoWifi) NoWifiDialog(onDismiss = { viewModel.dismissNoWifi() })

    if (showSongPicker) {
        SongPickerSheet(songs = allSongs, selectedSong = selectedSong, onSongPicked = { viewModel.selectSong(it); showSongPicker = false }, onDismiss = { showSongPicker = false })
    }

    val connectedState = nearbyState as? NearbyShareManager.NearbyState.Connected
    val sendingState   = nearbyState as? NearbyShareManager.NearbyState.Sending
    val awaitingState  = nearbyState as? NearbyShareManager.NearbyState.AwaitingAcceptance
    val idleForRemote  = transferState is ShareTransferManager.TransferState.Idle ||
        transferState is ShareTransferManager.TransferState.Error ||
        transferState is ShareTransferManager.TransferState.NoWifi ||
        transferState is ShareTransferManager.TransferState.Rejected

    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sensors, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Resonance Share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, "Close")
            }
        }

        Spacer(Modifier.height(4.dp))

        // What you're sharing, up front — before how you're sharing it.
        if (selectedSongs.size > 1) {
            MultiSongSummary(songs = selectedSongs, modifier = Modifier.padding(horizontal = 20.dp))
        } else {
            SongPickerRow(song = selectedSong, onClick = { showSongPicker = true }, modifier = Modifier.padding(horizontal = 20.dp))
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(icon = Icons.Rounded.Devices, title = "Nearby", modifier = Modifier.padding(horizontal = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                if (nearbyState is NearbyShareManager.NearbyState.GpsUnavailable) {
                    GpsUnavailableBanner(isGrapheneOs = viewModel.isGrapheneOs)
                } else {
                    RadarAnimation(devices = devices, nearbyState = nearbyState, localName = viewModel.localName, onDeviceTapped = { device ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.connectTo(device.endpointId)
                    })
                }
            }

            val statusText = when (val s = nearbyState) {
                is NearbyShareManager.NearbyState.Idle -> if (transferState is ShareTransferManager.TransferState.Rejected) "The receiver declined the transfer." else "Tap QR Code or wait for a nearby device"
                is NearbyShareManager.NearbyState.LocationDisabled -> "Location services are disabled"
                is NearbyShareManager.NearbyState.GpsUnavailable -> "QR Code works without Google Play Services"
                is NearbyShareManager.NearbyState.Scanning -> if (devices.isEmpty()) "Scanning via Bluetooth & Wi-Fi…" else "${devices.size} device${if (devices.size > 1) "s" else ""} nearby — tap to connect"
                is NearbyShareManager.NearbyState.Connecting -> "Connecting…"
                is NearbyShareManager.NearbyState.Connected -> "Connected to ${s.deviceName}"
                is NearbyShareManager.NearbyState.AwaitingAcceptance -> "Waiting for receiver to accept…"
                is NearbyShareManager.NearbyState.Sending -> "Sending… ${(s.progress * 100).toInt()}%"
                is NearbyShareManager.NearbyState.SendSuccess -> "Sent! ✓"
                is NearbyShareManager.NearbyState.Rejected -> "The receiver declined the transfer."
                is NearbyShareManager.NearbyState.Receiving -> "Receiving… ${(s.progress * 100).toInt()}%"
                is NearbyShareManager.NearbyState.Error -> s.message
            }

            AnimatedContent(
                targetState = statusText,
                transitionSpec = { (fadeIn(tween(200)) + slideInVertically { -it / 3 }).togetherWith(fadeOut(tween(150))) },
                label = "status_text",
            ) { text ->
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = when {
                    nearbyState is NearbyShareManager.NearbyState.Error -> MaterialTheme.colorScheme.error
                    nearbyState is NearbyShareManager.NearbyState.Rejected || transferState is ShareTransferManager.TransferState.Rejected -> MaterialTheme.colorScheme.error
                    nearbyState is NearbyShareManager.NearbyState.SendSuccess -> MaterialTheme.colorScheme.primary
                    nearbyState is NearbyShareManager.NearbyState.Receiving -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }

            val receivingState = nearbyState as? NearbyShareManager.NearbyState.Receiving
            AnimatedVisibility(
                visible = receivingState != null,
                enter   = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit    = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                LinearProgressIndicator(
                    progress   = { receivingState?.progress ?: 0f },
                    modifier   = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            val errorMessage = (nearbyState as? NearbyShareManager.NearbyState.Error)?.message ?: ""
            val isWifiError = nearbyState is NearbyShareManager.NearbyState.Error &&
                errorMessage.lowercase().contains("wi-fi is off")
            val isPermissionError = nearbyState is NearbyShareManager.NearbyState.Error && !isWifiError &&
                (errorMessage.contains("8029") || errorMessage.lowercase().contains("permission"))

            AnimatedVisibility(
                visible = nearbyState is NearbyShareManager.NearbyState.Error
                       || (nearbyState is NearbyShareManager.NearbyState.GpsUnavailable && !viewModel.isGrapheneOs),
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit  = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            viewModel.stopScanning()
                            viewModel.startScanning(activity)
                        },
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                    if (isWifiError) {
                        TextButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        ) {
                            Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Wi-Fi Settings", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (isPermissionError) {
                        TextButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                        ) {
                            Icon(Icons.Rounded.AppSettingsAlt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open Settings", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (selectedSongs.size > 1) {
                Text(
                    "Nearby only sends one song at a time — pick a single song above to use it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick  = { selectedSong?.let { viewModel.prepareTransfer(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled  = selectedSong != null && idleForRemote,
                        shape    = MaterialTheme.shapes.medium,
                    ) {
                        if (transferState is ShareTransferManager.TransferState.Preparing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Preparing…")
                        } else {
                            Icon(Icons.Rounded.QrCode2, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("QR Code")
                        }
                    }
                    AnimatedVisibility(visible = transferState is ShareTransferManager.TransferState.NoWifi) {
                        Text("Requires Wi-Fi or Wi-Fi Direct", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp))
                    }
                }

                Button(
                    onClick  = { connectedState?.let { viewModel.sendViaNearby(it.endpointId) } },
                    modifier = Modifier.weight(1f),
                    enabled  = connectedState != null && selectedSong != null && sendingState == null && awaitingState == null,
                    shape    = MaterialTheme.shapes.medium,
                ) {
                    when {
                        sendingState != null || awaitingState != null -> {
                            CircularProgressIndicator(progress = { sendingState?.progress ?: 0f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary, trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f))
                            Spacer(Modifier.width(8.dp))
                            Text(if (awaitingState != null) "Waiting…" else "Sending…")
                        }
                        nearbyState is NearbyShareManager.NearbyState.SendSuccess -> {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sent!")
                        }
                        else -> {
                            Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(icon = Icons.Rounded.Public, title = "Internet Link", modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 24, 72).forEach { hours ->
                    FilterChip(
                        selected = remoteTtlHours == hours,
                        onClick  = { viewModel.setRemoteTtlHours(hours) },
                        label    = { Text(if (hours == 1) "1 hour" else "$hours hours") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = { viewModel.prepareRemoteTransfer() },
                modifier = Modifier.fillMaxWidth(),
                enabled  = selectedSongs.isNotEmpty() && idleForRemote,
                shape    = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.Public, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selectedSongs.size > 1) "Share ${selectedSongs.size} songs over the internet"
                    else "Share over the internet"
                )
            }
        }
    }
}

@Composable
private fun RadarAnimation(
    devices     : List<NearbyShareManager.NearbyDevice>,
    nearbyState : NearbyShareManager.NearbyState,
    localName   : String,
    onDeviceTapped: (NearbyShareManager.NearbyDevice) -> Unit,
) {
    val primaryColor   = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface      = MaterialTheme.colorScheme.onSurface

    val isScanning = nearbyState is NearbyShareManager.NearbyState.Scanning
            || nearbyState is NearbyShareManager.NearbyState.Connecting
            || nearbyState is NearbyShareManager.NearbyState.Connected

    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val ring1 by infiniteTransition.animateFloat(
        initialValue   = 0f, targetValue = 1f,
        animationSpec  = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label          = "ring1",
    )
    val ring2 by infiniteTransition.animateFloat(
        initialValue   = 0.33f, targetValue = 1.33f,
        animationSpec  = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label          = "ring2",
    )
    val ring3 by infiniteTransition.animateFloat(
        initialValue   = 0.66f, targetValue = 1.66f,
        animationSpec  = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label          = "ring3",
    )

    val centerPulse by infiniteTransition.animateFloat(
        initialValue  = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label         = "center_pulse",
    )

    val deviceAlpha by animateFloatAsState(
        targetValue   = if (devices.isNotEmpty()) 1f else 0f,
        animationSpec = tween(400),
        label         = "device_alpha",
    )

    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val cx     = size.width / 2f
            val cy     = size.height / 2f
            val maxR   = minOf(cx, cy) * 0.92f

            if (isScanning) {
                listOf(ring1, ring2, ring3).forEach { phase ->
                    val normalised = phase % 1f
                    val radius     = maxR * normalised
                    val alpha      = (1f - normalised).pow(1.6f) * 0.55f
                    drawCircle(
                        color  = primaryColor.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(cx, cy),
                        style  = androidx.compose.ui.graphics.drawscope.Stroke(
                            width     = (3f * (1f - normalised) + 1f).dp.toPx(),
                        ),
                    )
                }

                drawCircle(
                    color  = primaryColor.copy(alpha = 0.08f),
                    radius = maxR * 0.72f,
                    center = Offset(cx, cy),
                    style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
        }

        if (devices.isNotEmpty()) {
            val outerRadius = 88.dp
            devices.forEachIndexed { index, device ->
                val angle    = (2 * PI / devices.size * index - PI / 2).toFloat()
                val offsetX  = cos(angle) * outerRadius.value
                val offsetY  = sin(angle) * outerRadius.value

                val isConnected = nearbyState.let {
                    it is NearbyShareManager.NearbyState.Connected && it.endpointId == device.endpointId
                            || it is NearbyShareManager.NearbyState.Connecting && it.endpointId == device.endpointId
                }

                Box(
                    modifier = Modifier
                        .offset(x = offsetX.dp, y = offsetY.dp)
                        .alpha(deviceAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    DeviceBubble(
                        device      = device,
                        isConnected = isConnected,
                        onTap       = { onDeviceTapped(device) },
                    )
                }
            }
        }

        val isConnected = nearbyState is NearbyShareManager.NearbyState.Connected
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    scaleX = if (isConnected) centerPulse else 1f
                    scaleY = if (isConnected) centerPulse else 1f
                }
                .clip(CircleShape)
                .background(
                    if (isConnected) primaryColor
                    else surfaceVariant
                )
                .border(
                    width = 2.dp,
                    color = primaryColor.copy(alpha = if (isScanning) 0.7f else 0.3f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Rounded.PhoneAndroid,
                contentDescription = localName,
                tint               = if (isConnected) MaterialTheme.colorScheme.onPrimary else onSurface,
                modifier           = Modifier.size(28.dp),
            )
        }

        Text(
            text     = localName,
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = 48.dp),
        )
    }
}

@Composable
private fun DeviceBubble(
    device     : NearbyShareManager.NearbyDevice,
    isConnected: Boolean,
    onTap      : () -> Unit,
) {
    val primary   = MaterialTheme.colorScheme.primary
    val surface   = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    val scale by animateFloatAsState(
        targetValue   = if (isConnected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label         = "bubble_scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isConnected) primary else surface)
                .border(
                    width = if (isConnected) 2.dp else 1.dp,
                    color = primary.copy(alpha = if (isConnected) 1f else 0.4f),
                    shape = CircleShape,
                )
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Rounded.Smartphone,
                contentDescription = device.name,
                tint               = if (isConnected) MaterialTheme.colorScheme.onPrimary else onSurface,
                modifier           = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text     = device.name,
            style    = MaterialTheme.typography.labelSmall,
            color    = if (isConnected) primary else onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp),
        )
    }
}

@Composable
private fun SongPickerRow(
    song    : Song?,
    onClick : () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier      = modifier.fillMaxWidth(),
        shape         = MaterialTheme.shapes.medium,
        color         = MaterialTheme.colorScheme.surfaceVariant,
        onClick       = onClick,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier            = Modifier.padding(12.dp),
            verticalAlignment   = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (song?.artworkUri != null) {
                    AsyncImage(
                        model             = song.artworkUri,
                        contentDescription = null,
                        modifier          = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            if (song != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = song.title,
                        style    = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = song.displayArtist,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text     = "Choose a song…",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = "Change song",
                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MultiSongSummary(
    songs: List<Song>,
    modifier: Modifier = Modifier,
) {
    val maxShown = 4
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            songs.take(maxShown).forEach { song ->
                Text(
                    "${song.title} · ${song.displayArtist}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            if (songs.size > maxShown) {
                Text(
                    "+${songs.size - maxShown} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerSheet(
    songs        : List<Song>,
    selectedSong : Song?,
    onSongPicked : (Song) -> Unit,
    onDismiss    : () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Text(
            text     = "Choose a song",
            style    = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                val isSelected = song.id == selectedSong?.id
                ListItem(
                    headlineContent   = {
                        Text(
                            song.title,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    supportingContent = { Text(song.displayArtist) },
                    leadingContent    = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (song.artworkUri != null) {
                                AsyncImage(
                                    model             = song.artworkUri,
                                    contentDescription = null,
                                    modifier          = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.MusicNote, null,
                                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    trailingContent   = if (isSelected) ({
                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }) else null,
                    modifier          = Modifier.clickable { onSongPicked(song) },
                )
            }
        }
    }
}

@Composable
private fun LocationDisabledDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = {
            Icon(
                Icons.Rounded.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Location services disabled") },
        text  = {
            Text(
                "Nearby Connections requires Location Services to be enabled at the system level to scan for nearby devices.\n\n" +
                        "Your location data is not recorded or shared. Please turn on Location in your device settings to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun NoWifiDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = {
            Icon(
                Icons.Rounded.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Wi-Fi required") },
        text  = {
            Text(
                "QR sharing works over your local Wi-Fi network (LAN mode) or via Wi-Fi Direct " +
                        "when no network is available — but the Wi-Fi radio must be on.\n\n" +
                        "Turn on Wi-Fi and try again. You don't need to connect to a network; " +
                        "Wi-Fi Direct will create its own private link between the two devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Open Wi-Fi settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun QrTransferDialog(
    song          : Song?,
    songCount     : Int = 0,
    transferState : ShareTransferManager.TransferState,
    ttlHours      : Int,
    onDismiss     : () -> Unit,
) {
    val readyState     = transferState as? ShareTransferManager.TransferState.Ready
    val servingState   = transferState as? ShareTransferManager.TransferState.Serving
    val uploadingState = transferState as? ShareTransferManager.TransferState.Uploading
    val errorState     = transferState as? ShareTransferManager.TransferState.Error
    val isDone         = transferState is ShareTransferManager.TransferState.Done
    val isMulti        = readyState?.mode == "remote-multi" || (uploadingState?.totalSongs ?: 1) > 1
    val isShareableLink = readyState?.mode == "remote" || readyState?.mode == "remote-multi"
    val context = LocalContext.current

    if (errorState != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Transfer failed") },
            text = {
                Text(
                    errorState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon             = {
            when {
                isDone       -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                servingState != null || uploadingState != null -> Icon(Icons.Rounded.CloudUpload, null)
                else         -> Icon(Icons.Rounded.QrCode2, null)
            }
        },
        title = {
            Text(when {
                isDone       -> "Transfer complete"
                servingState != null -> "Sending…"
                uploadingState != null -> "Uploading…"
                readyState?.mode == "remote" || isMulti -> "Link ready"
                readyState?.mode == "p2p" -> "Scan to join & receive"
                else         -> "Scan to receive"
            })
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isMulti) {
                    Text(
                        "$songCount songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (song != null) {
                    Text(
                        "${song.title} · ${song.displayArtist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (uploadingState != null) {
                    Box(
                        modifier         = Modifier.size(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                    }
                } else {
                    AnimatedContent(
                        targetState = readyState?.qrContent,
                        transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(200))) },
                        label = "qr_content",
                    ) { content ->
                        if (content != null) {
                            MaterialQrCode(
                                content = content,
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .size(240.dp)
                            )
                        } else {
                            Box(
                                modifier         = Modifier.size(240.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }

                if (isShareableLink && readyState?.qrContent != null) {
                    val link = readyState.qrContent
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            link,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Resonance share link", link))
                            },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy link")
                        }
                        OutlinedButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share link")
                        }
                    }
                }

                if (servingState != null) {
                    LinearProgressIndicator(
                        progress   = { servingState.progress },
                        modifier   = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${(servingState.progress * 100).toInt()}% transferred",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (uploadingState != null) {
                    LinearProgressIndicator(
                        progress   = { uploadingState.progress },
                        modifier   = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        if (uploadingState.totalSongs > 1)
                            "Song ${(uploadingState.completedSongs + 1).coerceAtMost(uploadingState.totalSongs)} of ${uploadingState.totalSongs} · ${(uploadingState.progress * 100).toInt()}% uploaded"
                        else
                            "${(uploadingState.progress * 100).toInt()}% uploaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val hint = when {
                    isDone               -> "The file was received successfully."
                    servingState != null -> "Keep this screen open until the transfer finishes."
                    uploadingState != null -> "Keep this screen open until the upload finishes."
                    readyState?.mode == "remote" -> {
                        val ttlText = if (ttlHours == 1) "1 hour" else "$ttlHours hours"
                        "Share this QR code or link with anyone, anywhere. It expires after one download or $ttlText."
                    }
                    isMulti -> {
                        val ttlText = if (ttlHours == 1) "1 hour" else "$ttlHours hours"
                        "Share this QR code or link with anyone, anywhere. Each song can be downloaded once; the list stays available for $ttlText."
                    }
                    readyState?.mode == "p2p" ->
                        "The receiver will temporarily join your Wi-Fi Direct hotspot. " +
                                "No internet connection is required on either device."
                    else ->
                        "Both devices must be on the same Wi-Fi network."
                }
                Text(
                    hint,
                    style     = MaterialTheme.typography.bodySmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isDone || readyState?.mode == "remote" || isMulti) "Done" else "Cancel")
            }
        },
    )
}

@Composable

private fun PermissionPrompt(
    isPermanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .navigationBarsPadding(),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.Sensors,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(48.dp),
        )
        Text(
            "Nearby permissions needed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (isPermanentlyDenied)
                "Bluetooth and location permissions were denied. Please enable them in app settings."
            else
                "Resonance needs Bluetooth and location access to find devices nearby. Your location is never stored or uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onRequest, shape = MaterialTheme.shapes.medium) {
            Text(if (isPermanentlyDenied) "Open Settings" else "Grant permissions")
        }
        TextButton(onClick = onDismiss) { Text("Not now") }
    }
}

@Composable
private fun GpsUnavailableBanner(isGrapheneOs: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(
            Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(38.dp),
        )
        Text(
            "Nearby Share requires Google Play Services",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (isGrapheneOs) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "GrapheneOS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        "Resonance Share (Nearby Share) doesn't work on GrapheneOS, but QR Code does.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
        } else {
            Text(
                "Install Google Play Services to use Nearby Share.\nUse the QR Code button to share over Wi-Fi instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun MaterialQrCode(
    content: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    dotColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    eyeColor: Color = MaterialTheme.colorScheme.primary
) {
    val matrix = remember(content) {
        val hints = mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(40.dp))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = matrix.width
            val height = matrix.height
            val cellSize = min(size.width, size.height) / width
            val offsetX = (size.width - cellSize * width) / 2f
            val offsetY = (size.height - cellSize * height) / 2f

            val isFinderPattern = { x: Int, y: Int ->
                (x <= 6 && y <= 6) ||
                        (x >= width - 7 && y <= 6) ||
                        (x <= 6 && y >= height - 7)
            }

            for (x in 0 until width) {
                for (y in 0 until height) {
                    if (matrix.get(x, y) && !isFinderPattern(x, y)) {
                        drawCircle(
                            color = dotColor,
                            radius = cellSize * 0.45f,
                            center = Offset(
                                offsetX + x * cellSize + cellSize / 2f,
                                offsetY + y * cellSize + cellSize / 2f
                            )
                        )
                    }
                }
            }

            val drawEye = { startX: Int, startY: Int ->
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(
                        offsetX + startX * cellSize + cellSize / 2f,
                        offsetY + startY * cellSize + cellSize / 2f
                    ),
                    size = Size(6 * cellSize, 6 * cellSize),
                    cornerRadius = CornerRadius(cellSize * 1.5f, cellSize * 1.5f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = cellSize)
                )
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(
                        offsetX + (startX + 2) * cellSize,
                        offsetY + (startY + 2) * cellSize
                    ),
                    size = Size(3 * cellSize, 3 * cellSize),
                    cornerRadius = CornerRadius(cellSize, cellSize)
                )
            }

            drawEye(0, 0)
            drawEye(width - 7, 0)
            drawEye(0, height - 7)
        }
    }
}