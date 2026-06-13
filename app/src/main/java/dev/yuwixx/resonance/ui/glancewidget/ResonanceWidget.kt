// Glance (Jetpack Compose) home-screen widget showing now-playing info, a progress bar,
// and playback controls. Receives state pushes from MusicService via updateState().
package dev.yuwixx.resonance.ui.glancewidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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

const val ACTION_WIDGET_PLAY_PAUSE = "dev.yuwixx.resonance.WIDGET_PLAY_PAUSE"
const val ACTION_WIDGET_SKIP_NEXT  = "dev.yuwixx.resonance.WIDGET_SKIP_NEXT"
const val ACTION_WIDGET_SKIP_PREV  = "dev.yuwixx.resonance.WIDGET_SKIP_PREV"

val KEY_TITLE       = stringPreferencesKey("widget_title")
val KEY_ARTIST      = stringPreferencesKey("widget_artist")
val KEY_ARTWORK_URI = stringPreferencesKey("widget_artwork_uri")
val KEY_IS_PLAYING  = booleanPreferencesKey("widget_is_playing")
val KEY_HAS_SONG    = booleanPreferencesKey("widget_has_song")
val KEY_HAS_PREV    = booleanPreferencesKey("widget_has_prev")
val KEY_HAS_NEXT    = booleanPreferencesKey("widget_has_next")
val KEY_PROGRESS    = floatPreferencesKey("widget_progress")

class ResonanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 84.dp),
            DpSize(280.dp, 84.dp),
            DpSize(360.dp, 84.dp),
            DpSize(360.dp, 140.dp),
        )
    )

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Load artwork before entering the reactive content block.
        // We use our own app's ContentResolver (not the launcher's), so content:// URIs work.
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
                val state      = currentState<Preferences>()
                val hasSong    = state[KEY_HAS_SONG]   ?: false
                val title      = state[KEY_TITLE]       ?: ""
                val artist     = state[KEY_ARTIST]      ?: ""
                val isPlaying  = state[KEY_IS_PLAYING]  ?: false
                val hasPrev    = state[KEY_HAS_PREV]    ?: false
                val hasNext    = state[KEY_HAS_NEXT]    ?: false
                val progress   = state[KEY_PROGRESS]    ?: 0f
                val size       = LocalSize.current

                WidgetContent(
                    hasSong        = hasSong,
                    title          = title,
                    artist         = artist,
                    artworkBitmap  = artworkBitmap,
                    isPlaying      = isPlaying,
                    hasPrev        = hasPrev,
                    hasNext        = hasNext,
                    progress       = progress,
                    widgetWidth    = size.width,
                    widgetHeight   = size.height,
                )
            }
        }
    }

    companion object {
        private fun loadArtwork(context: Context, uriStr: String): Bitmap? {
            if (uriStr.isEmpty()) return null
            return try {
                val uri = Uri.parse(uriStr)
                val scheme = uri.scheme ?: return null
                if (scheme != "content" && scheme != "file") return null
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) { null }
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
        ) {
            try {
                val ids = GlanceAppWidgetManager(context).getGlanceIds(ResonanceWidget::class.java)
                for (id in ids) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[KEY_TITLE]       = title
                            this[KEY_ARTIST]      = artist
                            this[KEY_ARTWORK_URI] = artworkUri
                            this[KEY_IS_PLAYING]  = isPlaying
                            this[KEY_HAS_SONG]    = hasSong
                            this[KEY_HAS_PREV]    = hasPrev
                            this[KEY_HAS_NEXT]    = hasNext
                            this[KEY_PROGRESS]    = progress
                        }
                    }
                    ResonanceWidget().update(context, id)
                }
            } catch (_: Exception) {}
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
    widgetWidth: androidx.compose.ui.unit.Dp,
    widgetHeight: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(28.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (!hasSong) {
            IdleContent()
        } else {
            val showArt  = widgetWidth >= 240.dp
            val expanded = widgetWidth >= 320.dp
            val tall     = widgetHeight >= 130.dp

            NowPlayingContent(
                title         = title,
                artist        = artist,
                artworkBitmap = artworkBitmap,
                isPlaying     = isPlaying,
                hasPrev       = hasPrev,
                hasNext       = hasNext,
                progress      = progress,
                showArt       = showArt,
                expanded      = expanded,
                tall          = tall,
                widgetWidth   = widgetWidth,
            )
        }
    }
}

@Composable
private fun IdleContent() {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(52.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_ic_music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(26.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
        Spacer(GlanceModifier.width(16.dp))
        Column(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Resonance",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
            )
            Spacer(GlanceModifier.height(3.dp))
            Text(
                "Nothing playing",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}

@Composable
private fun NowPlayingContent(
    title: String,
    artist: String,
    artworkBitmap: Bitmap?,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    progress: Float,
    showArt: Boolean,
    expanded: Boolean,
    tall: Boolean,
    widgetWidth: androidx.compose.ui.unit.Dp,
) {
    val artSize = when {
        tall     -> 110.dp
        expanded -> 80.dp
        else     -> 60.dp
    }
    val padding = if (expanded) 14.dp else 10.dp

    Row(
        modifier = GlanceModifier.fillMaxSize().padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            if (artworkBitmap != null) {
                Image(
                    provider = ImageProvider(artworkBitmap),
                    contentDescription = null,
                    modifier = GlanceModifier
                        .size(artSize)
                        .cornerRadius(12.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .size(artSize)
                        .background(GlanceTheme.colors.primaryContainer)
                        .cornerRadius(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.widget_ic_music_note),
                        contentDescription = null,
                        modifier = GlanceModifier.size(26.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    )
                }
            }
            Spacer(GlanceModifier.width(12.dp))
        }

        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(GlanceModifier.defaultWeight())

            Text(
                text = title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (expanded) 15.sp else 13.sp,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = artist,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = if (expanded) 13.sp else 11.sp,
                ),
                maxLines = 1,
            )

            Spacer(GlanceModifier.defaultWeight())

            // Progress bar — track with filled overlay (Glance has no Slider)
            val artOffset  = if (showArt) artSize + 12.dp else 0.dp
            val trackWidth = (widgetWidth - padding * 2 - artOffset).coerceAtLeast(0.dp)
            val fillWidth  = (trackWidth * progress.coerceIn(0f, 1f)).coerceAtLeast(0.dp)

            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
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

            Spacer(GlanceModifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expanded) {
                    ControlButton(
                        iconRes     = R.drawable.widget_ic_skip_prev,
                        description = "Previous",
                        active      = hasPrev,
                        action      = actionSendBroadcast(
                            Intent(ACTION_WIDGET_SKIP_PREV).setPackage("dev.yuwixx.resonance")
                        ),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                }
                ControlButton(
                    iconRes     = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                    description = if (isPlaying) "Pause" else "Play",
                    active      = true,
                    primary     = true,
                    action      = actionSendBroadcast(
                        Intent(ACTION_WIDGET_PLAY_PAUSE).setPackage("dev.yuwixx.resonance")
                    ),
                )
                if (expanded) {
                    Spacer(GlanceModifier.width(6.dp))
                    ControlButton(
                        iconRes     = R.drawable.widget_ic_skip_next,
                        description = "Next",
                        active      = hasNext,
                        action      = actionSendBroadcast(
                            Intent(ACTION_WIDGET_SKIP_NEXT).setPackage("dev.yuwixx.resonance")
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.defaultWeight())
        }
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
            .clickable(action),
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
