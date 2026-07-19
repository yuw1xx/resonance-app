// Glance (Jetpack Compose) home-screen widget showing now-playing info, a seekable progress
// bar, and playback controls. Receives state pushes from MusicService via updateState().
package dev.yuwixx.resonance.ui.glancewidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.glance.material3.ColorProviders
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import dev.yuwixx.resonance.MainActivity
import dev.yuwixx.resonance.R
import dev.yuwixx.resonance.ui.theme.DarkColorScheme
import dev.yuwixx.resonance.ui.theme.LightColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ResonanceWidget"
private const val APP_PACKAGE = "dev.yuwixx.resonance"
private const val SEEK_ZONES = 10

const val ACTION_WIDGET_PLAY_PAUSE  = "dev.yuwixx.resonance.WIDGET_PLAY_PAUSE"
const val ACTION_WIDGET_SKIP_NEXT   = "dev.yuwixx.resonance.WIDGET_SKIP_NEXT"
const val ACTION_WIDGET_SKIP_PREV   = "dev.yuwixx.resonance.WIDGET_SKIP_PREV"
const val ACTION_WIDGET_SEEK        = "dev.yuwixx.resonance.WIDGET_SEEK"
const val ACTION_WIDGET_TOGGLE_LIKE = "dev.yuwixx.resonance.WIDGET_TOGGLE_LIKE"
const val EXTRA_SEEK_FRACTION_PCT   = "seek_fraction_pct"

// Signature-protected: only apps signed with the same key as this one (i.e. only this app)
// can broadcast these widget control actions. Without it, any installed app could send an
// unprotected implicit broadcast with a matching action string to hijack playback controls.
const val PERMISSION_WIDGET_CONTROL = "dev.yuwixx.resonance.permission.WIDGET_CONTROL"

val KEY_TITLE         = stringPreferencesKey("widget_title")
val KEY_ARTIST        = stringPreferencesKey("widget_artist")
val KEY_ARTWORK_URI   = stringPreferencesKey("widget_artwork_uri")
val KEY_IS_PLAYING    = booleanPreferencesKey("widget_is_playing")
val KEY_HAS_SONG      = booleanPreferencesKey("widget_has_song")
val KEY_HAS_PREV      = booleanPreferencesKey("widget_has_prev")
val KEY_HAS_NEXT      = booleanPreferencesKey("widget_has_next")
val KEY_PROGRESS      = floatPreferencesKey("widget_progress")
val KEY_IS_LIKED      = booleanPreferencesKey("widget_is_liked")
val KEY_CORNER_RADIUS = intPreferencesKey("widget_corner_radius")

class ResonanceWidget : GlanceAppWidget() {

    // Two extra large/square breakpoints beyond the original short banners, so bigger
    // launcher grid placements (e.g. a 4x2 or 2x2 slot) get a real stacked layout instead of
    // just a wider version of the same thin banner.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 84.dp),
            DpSize(280.dp, 84.dp),
            DpSize(360.dp, 84.dp),
            DpSize(360.dp, 140.dp),
            DpSize(250.dp, 250.dp),
            DpSize(360.dp, 250.dp),
        )
    )

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Load artwork before entering the reactive content block.
        // We use our own app's ContentResolver (not the launcher's), so content:// URIs work,
        // and fetch http(s) URIs ourselves too — Navidrome album art is always a network URL,
        // which this widget silently failed to show before.
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        val artworkUriStr = prefs[KEY_ARTWORK_URI] ?: ""
        val artworkBitmap: Bitmap? = withContext(Dispatchers.IO) {
            loadArtwork(context, artworkUriStr)
        }

        val glanceColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ColorProviders(
                light = dynamicLightColorScheme(context),
                dark = dynamicDarkColorScheme(context),
            )
        else
            ColorProviders(light = LightColorScheme, dark = DarkColorScheme)

        provideContent {
            GlanceTheme(colors = glanceColors) {
                val state         = currentState<Preferences>()
                val hasSong       = state[KEY_HAS_SONG]   ?: false
                val title         = state[KEY_TITLE]       ?: ""
                val artist        = state[KEY_ARTIST]      ?: ""
                val isPlaying     = state[KEY_IS_PLAYING]  ?: false
                val hasPrev       = state[KEY_HAS_PREV]    ?: false
                val hasNext       = state[KEY_HAS_NEXT]    ?: false
                val progress      = state[KEY_PROGRESS]    ?: 0f
                val isLiked       = state[KEY_IS_LIKED]    ?: false
                val cornerRadius  = (state[KEY_CORNER_RADIUS] ?: 28).dp
                val size          = LocalSize.current

                WidgetContent(
                    hasSong        = hasSong,
                    title          = title,
                    artist         = artist,
                    artworkBitmap  = artworkBitmap,
                    isPlaying      = isPlaying,
                    hasPrev        = hasPrev,
                    hasNext        = hasNext,
                    progress       = progress,
                    isLiked        = isLiked,
                    cornerRadius   = cornerRadius,
                    widgetWidth    = size.width,
                    widgetHeight   = size.height,
                )
            }
        }
    }

    companion object {
        // Downsamples against a single generous target rather than a fixed inSampleSize=2 —
        // Glance composes this same bitmap into every declared size breakpoint at once (not
        // just whatever's on-screen right now), so there's no single "target widget size" to
        // sample against; this keeps memory bounded regardless of the source image's actual
        // resolution instead of scaling everything by a flat, source-blind factor of 2.
        private const val ARTWORK_TARGET_PX = 400

        private fun loadArtwork(context: Context, uriStr: String): Bitmap? {
            if (uriStr.isEmpty()) return null
            return try {
                val uri = Uri.parse(uriStr)
                val bytes = when (uri.scheme) {
                    "content", "file" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    "http", "https" -> {
                        val connection = (URL(uriStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5_000
                            readTimeout    = 8_000
                        }
                        try {
                            connection.inputStream.use { it.readBytes() }
                        } finally {
                            connection.disconnect()
                        }
                    }
                    else -> null
                } ?: return null
                decodeSampledBitmap(bytes, ARTWORK_TARGET_PX)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load widget artwork from $uriStr", e)
                null
            }
        }

        private fun decodeSampledBitmap(bytes: ByteArray, targetPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetPx && bounds.outHeight / (sampleSize * 2) >= targetPx) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        suspend fun updateState(
            context: Context,
            title: String,
            artist: String,
            artworkUri: String,
            isPlaying: Boolean,
            hasSong: Boolean,
            hasPrev: Boolean,
            hasNext: Boolean,
            progress: Float = 0f,
            isLiked: Boolean = false,
            cornerRadiusDp: Int = 28,
        ) {
            try {
                val ids = GlanceAppWidgetManager(context).getGlanceIds(ResonanceWidget::class.java)
                for (id in ids) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[KEY_TITLE]         = title
                            this[KEY_ARTIST]        = artist
                            this[KEY_ARTWORK_URI]   = artworkUri
                            this[KEY_IS_PLAYING]    = isPlaying
                            this[KEY_HAS_SONG]      = hasSong
                            this[KEY_HAS_PREV]      = hasPrev
                            this[KEY_HAS_NEXT]      = hasNext
                            this[KEY_PROGRESS]      = progress
                            this[KEY_IS_LIKED]      = isLiked
                            this[KEY_CORNER_RADIUS] = cornerRadiusDp
                        }
                    }
                    ResonanceWidget().update(context, id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push widget state", e)
            }
        }
    }
}

@Composable
private fun WidgetContent(
    hasSong: Boolean,
    title: String,
    artist: String,
    artworkBitmap: Bitmap?,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    progress: Float,
    isLiked: Boolean,
    cornerRadius: Dp,
    widgetWidth: Dp,
    widgetHeight: Dp,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(cornerRadius)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // Height >=200dp is only reached by the two new large/square breakpoints — a short
        // banner never qualifies, so this cleanly picks the stacked layout only for those.
        val stacked = widgetHeight >= 200.dp

        if (!hasSong) {
            IdleContent(stacked = stacked, expanded = widgetWidth >= 320.dp)
        } else if (stacked) {
            StackedNowPlayingContent(
                title         = title,
                artist        = artist,
                artworkBitmap = artworkBitmap,
                isPlaying     = isPlaying,
                hasPrev       = hasPrev,
                hasNext       = hasNext,
                progress      = progress,
                isLiked       = isLiked,
                cornerRadius  = cornerRadius,
                widgetWidth   = widgetWidth,
            )
        } else {
            val showArt  = widgetWidth >= 240.dp
            val expanded = widgetWidth >= 320.dp
            val tall     = widgetHeight >= 130.dp

            BannerNowPlayingContent(
                title         = title,
                artist        = artist,
                artworkBitmap = artworkBitmap,
                isPlaying     = isPlaying,
                hasPrev       = hasPrev,
                hasNext       = hasNext,
                progress      = progress,
                isLiked       = isLiked,
                cornerRadius  = cornerRadius,
                showArt       = showArt,
                expanded      = expanded,
                tall          = tall,
                widgetWidth   = widgetWidth,
            )
        }
    }
}

@Composable
private fun IdleContent(stacked: Boolean, expanded: Boolean) {
    val iconBoxSize = if (stacked) 72.dp else 52.dp
    val iconSize    = if (stacked) 34.dp else 26.dp
    val titleSize   = if (stacked || expanded) 17.sp else 16.sp
    val subtitleSize = if (stacked || expanded) 14.sp else 13.sp

    val icon: @Composable () -> Unit = {
        Box(
            modifier = GlanceModifier
                .size(iconBoxSize)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(iconBoxSize / 2),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_ic_music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
    val text: @Composable () -> Unit = {
        Text("Resonance", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = titleSize))
        Spacer(GlanceModifier.height(3.dp))
        Text("Nothing playing", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = subtitleSize))
    }

    if (stacked) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(GlanceModifier.height(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { text() }
        }
    } else {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(GlanceModifier.width(16.dp))
            Column(verticalAlignment = Alignment.CenterVertically) { text() }
        }
    }
}

@Composable
private fun BannerNowPlayingContent(
    title: String,
    artist: String,
    artworkBitmap: Bitmap?,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    progress: Float,
    isLiked: Boolean,
    cornerRadius: Dp,
    showArt: Boolean,
    expanded: Boolean,
    tall: Boolean,
    widgetWidth: Dp,
) {
    val artSize = when {
        tall     -> 110.dp
        expanded -> 80.dp
        else     -> 60.dp
    }
    val padding = if (expanded) 14.dp else 10.dp
    val artCornerRadius = (cornerRadius * 0.4f).coerceAtMost(20.dp)

    Row(
        modifier = GlanceModifier.fillMaxSize().padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            ArtworkOrPlaceholder(artworkBitmap, artSize, artCornerRadius)
            Spacer(GlanceModifier.width(12.dp))
        }

        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(GlanceModifier.defaultWeight())

            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = if (expanded) 15.sp else 13.sp),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = artist,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = if (expanded) 13.sp else 11.sp),
                        maxLines = 1,
                    )
                }
                if (expanded) {
                    Spacer(GlanceModifier.width(8.dp))
                    LikeButton(isLiked = isLiked, size = 28.dp)
                }
            }

            Spacer(GlanceModifier.defaultWeight())

            val artOffset  = if (showArt) artSize + 12.dp else 0.dp
            val trackWidth = (widgetWidth - padding * 2 - artOffset).coerceAtLeast(0.dp)
            SeekableProgressBar(trackWidth = trackWidth, progress = progress)

            Spacer(GlanceModifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expanded) {
                    ControlButton(
                        iconRes     = R.drawable.widget_ic_skip_prev,
                        description = "Previous",
                        active      = hasPrev,
                        action      = actionSendBroadcast(Intent(ACTION_WIDGET_SKIP_PREV).setPackage(APP_PACKAGE)),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                }
                ControlButton(
                    iconRes     = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                    description = if (isPlaying) "Pause" else "Play",
                    active      = true,
                    primary     = true,
                    action      = actionSendBroadcast(Intent(ACTION_WIDGET_PLAY_PAUSE).setPackage(APP_PACKAGE)),
                )
                if (expanded) {
                    Spacer(GlanceModifier.width(6.dp))
                    ControlButton(
                        iconRes     = R.drawable.widget_ic_skip_next,
                        description = "Next",
                        active      = hasNext,
                        action      = actionSendBroadcast(Intent(ACTION_WIDGET_SKIP_NEXT).setPackage(APP_PACKAGE)),
                    )
                }
            }

            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun StackedNowPlayingContent(
    title: String,
    artist: String,
    artworkBitmap: Bitmap?,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    progress: Float,
    isLiked: Boolean,
    cornerRadius: Dp,
    widgetWidth: Dp,
) {
    val padding = 18.dp
    val artSize = 140.dp
    val artCornerRadius = (cornerRadius * 0.5f).coerceAtMost(28.dp)
    val trackWidth = (widgetWidth - padding * 2).coerceAtLeast(0.dp)

    Column(
        modifier = GlanceModifier.fillMaxSize().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.defaultWeight())

        ArtworkOrPlaceholder(artworkBitmap, artSize, artCornerRadius)
        Spacer(GlanceModifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(8.dp))
            LikeButton(isLiked = isLiked, size = 26.dp)
        }
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = artist,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            maxLines = 1,
        )

        Spacer(GlanceModifier.height(14.dp))
        SeekableProgressBar(trackWidth = trackWidth, progress = progress)
        Spacer(GlanceModifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(
                iconRes     = R.drawable.widget_ic_skip_prev,
                description = "Previous",
                active      = hasPrev,
                action      = actionSendBroadcast(Intent(ACTION_WIDGET_SKIP_PREV).setPackage(APP_PACKAGE)),
            )
            Spacer(GlanceModifier.width(10.dp))
            ControlButton(
                iconRes     = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                description = if (isPlaying) "Pause" else "Play",
                active      = true,
                primary     = true,
                action      = actionSendBroadcast(Intent(ACTION_WIDGET_PLAY_PAUSE).setPackage(APP_PACKAGE)),
            )
            Spacer(GlanceModifier.width(10.dp))
            ControlButton(
                iconRes     = R.drawable.widget_ic_skip_next,
                description = "Next",
                active      = hasNext,
                action      = actionSendBroadcast(Intent(ACTION_WIDGET_SKIP_NEXT).setPackage(APP_PACKAGE)),
            )
        }

        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun ArtworkOrPlaceholder(bitmap: Bitmap?, size: Dp, cornerRadius: Dp) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.size(size).cornerRadius(cornerRadius),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = GlanceModifier
                .size(size)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(cornerRadius),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_ic_music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(size * 0.4f),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
}

// Track with filled overlay (Glance has no Slider), plus a row of invisible equal-width
// clickable zones layered on top for coarse tap-to-seek. Android's home-screen-widget
// RemoteViews view whitelist has never included a real draggable slider/seekbar — this
// ~10%-granularity segmented-tap approach is the practical ceiling for a widget; full precise
// scrubbing is still available from the lock-screen/notification media controls.
@Composable
private fun SeekableProgressBar(trackWidth: Dp, progress: Float) {
    val fillWidth = (trackWidth * progress.coerceIn(0f, 1f)).coerceAtLeast(0.dp)
    val zoneWidth = (trackWidth / SEEK_ZONES).coerceAtLeast(1.dp)

    Box(
        modifier = GlanceModifier.width(trackWidth).height(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .width(trackWidth)
                .height(4.dp)
                .background(GlanceTheme.colors.outline)
                .cornerRadius(2.dp),
        ) {
            if (fillWidth > 0.dp) {
                Box(
                    modifier = GlanceModifier
                        .width(fillWidth)
                        .height(4.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(2.dp),
                ) {}
            }
        }

        Row(modifier = GlanceModifier.width(trackWidth).height(22.dp)) {
            repeat(SEEK_ZONES) { i ->
                val fractionPct = (i + 1) * 100 / SEEK_ZONES
                Box(
                    modifier = GlanceModifier
                        .width(zoneWidth)
                        .fillMaxHeight()
                        .clickable(
                            actionSendBroadcast(
                                Intent(ACTION_WIDGET_SEEK)
                                    .putExtra(EXTRA_SEEK_FRACTION_PCT, fractionPct)
                                    .setPackage(APP_PACKAGE)
                            )
                        ),
                ) {}
            }
        }
    }
}

@Composable
private fun LikeButton(isLiked: Boolean, size: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(if (isLiked) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant)
            .cornerRadius(size / 2)
            .clickable(actionSendBroadcast(Intent(ACTION_WIDGET_TOGGLE_LIKE).setPackage(APP_PACKAGE))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isLiked) R.drawable.widget_ic_like_filled else R.drawable.widget_ic_like),
            contentDescription = if (isLiked) "Unlike" else "Like",
            modifier = GlanceModifier.size(size * 0.55f),
            colorFilter = ColorFilter.tint(
                if (isLiked) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
            ),
        )
    }
}

@Composable
private fun ControlButton(
    iconRes: Int,
    description: String,
    active: Boolean,
    primary: Boolean = false,
    action: androidx.glance.action.Action,
) {
    val bgColor   = if (primary) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer
    val iconColor = when {
        primary -> GlanceTheme.colors.onPrimaryContainer
        active  -> GlanceTheme.colors.onSecondaryContainer
        else    -> GlanceTheme.colors.outline
    }
    val size = if (primary) 48.dp else 40.dp

    Box(
        modifier = GlanceModifier
            .size(size)
            .background(bgColor)
            .cornerRadius(size / 2)
            .let { if (active) it.clickable(action) else it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            modifier = GlanceModifier.size(if (primary) 22.dp else 18.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

class ResonanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ResonanceWidget()
}
