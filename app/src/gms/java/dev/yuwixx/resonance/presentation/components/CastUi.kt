// Extracted from Components.kt so this — the only piece of the shared UI that touches
// androidx.mediarouter / com.google.android.gms.cast — lives in the gms flavor's source set.
// The foss flavor provides its own no-op CastButton() with the same signature.
package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val router = remember { MediaRouter.getInstance(context) }
    val castSelector = remember {
        try { CastContext.getSharedInstance(context)?.mergedSelector } catch (_: Exception) { null }
    }
    var isCasting by remember { mutableStateOf(!router.selectedRoute.isDefault) }

    DisposableEffect(Unit) {
        fun sync() { isCasting = !router.selectedRoute.isDefault }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
        }
        castSelector?.let { router.addCallback(it, cb, 0) }
        onDispose { router.removeCallback(cb) }
    }

    val castTint by animateColorAsState(
        targetValue = if (isCasting) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "cast_tint",
    )

    IconButton(onClick = { showSheet = true }, modifier = modifier) {
        Icon(Icons.Rounded.Cast, contentDescription = "Cast", tint = castTint)
    }

    if (showSheet) {
        CastSheet(
            router = router,
            castSelector = castSelector,
            onDisconnect = {
                try { CastContext.getSharedInstance(context)?.sessionManager?.endCurrentSession(true) }
                catch (_: Exception) {}
            },
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastSheet(
    router: MediaRouter,
    castSelector: MediaRouteSelector?,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var routes by remember { mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList()) }
    val connectedRoute by remember { derivedStateOf { routes.firstOrNull { it.isSelected } } }

    DisposableEffect(Unit) {
        fun sync() {
            routes = router.routes.filter { r ->
                !r.isDefault && (castSelector == null || r.matchesSelector(castSelector))
            }
        }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo) = sync()
        }
        castSelector?.let { router.addCallback(it, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY) }
        sync()
        onDispose { router.removeCallback(cb) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.Cast, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    "Cast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (connectedRoute != null) {
                ListItem(
                    headlineContent = { Text(connectedRoute!!.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    leadingContent = {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(castDeviceIcon(connectedRoute!!), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                )

                ListItem(
                    modifier = Modifier.clickable { onDisconnect(); onDismiss() },
                    headlineContent = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                )

                val otherRoutes = routes.filter { !it.isSelected }
                if (otherRoutes.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    Text(
                        "Other devices",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    otherRoutes.forEach { CastRouteItem(it) { it.select(); onDismiss() } }
                }
            } else if (routes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text(
                            "Searching for devices…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                routes.forEach { CastRouteItem(it) { it.select(); onDismiss() } }
            }
        }
    }
}

@Composable
private fun CastRouteItem(route: MediaRouter.RouteInfo, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(route.name) },
        supportingContent = route.description?.takeIf { it.isNotBlank() }?.let { desc ->
            { Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(castDeviceIcon(route), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
            }
        },
    )
}

private fun castDeviceIcon(route: MediaRouter.RouteInfo) = when (route.deviceType) {
    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> Icons.Rounded.Tv
    MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER -> Icons.Rounded.Speaker
    else -> Icons.Rounded.Cast
}
