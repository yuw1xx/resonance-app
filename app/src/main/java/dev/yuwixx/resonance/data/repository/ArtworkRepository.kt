package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.ArtworkDao
import dev.yuwixx.resonance.data.database.entity.ArtistArtworkEntity
import dev.yuwixx.resonance.data.network.DeezerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deezerApi: DeezerApi,
    private val artworkDao: ArtworkDao,
    private val imageLoader: ImageLoader,
) {
    private val paletteCache = java.util.concurrent.ConcurrentHashMap<Long, ArtworkColors>(64)

        suspend fun getArtistArtworkUrl(artistName: String): String? = withContext(Dispatchers.IO) {
        val cached = artworkDao.getArtistArtwork(artistName)
        val cacheAgeMs = 7L * 24 * 60 * 60 * 1000
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < cacheAgeMs) {
            return@withContext cached.artworkUrl
        }

        try {
            val response = deezerApi.searchArtist(artistName, limit = 1)
            val url = response.data.firstOrNull()?.pictureXl
                ?: response.data.firstOrNull()?.pictureMedium
            artworkDao.upsertArtistArtwork(
                ArtistArtworkEntity(artistName = artistName, artworkUrl = url)
            )
            url
        } catch (e: Exception) {
            null
        }
    }

        suspend fun extractArtworkColors(albumId: Long, artworkUri: Any): ArtworkColors =
        withContext(Dispatchers.IO) {
            paletteCache[albumId]?.let { return@withContext it }

            val bitmap = loadBitmap(artworkUri) ?: return@withContext ArtworkColors.Default
            val palette = Palette.from(bitmap).generate()

            val colors = ArtworkColors(
                dominant = palette.getDominantColor(Color.Gray.toArgb()),
                vibrant = palette.getVibrantColor(palette.getDominantColor(Color.Gray.toArgb())),
                darkVibrant = palette.getDarkVibrantColor(Color.Black.toArgb()),
                lightVibrant = palette.getLightVibrantColor(Color.White.toArgb()),
                muted = palette.getMutedColor(Color.DarkGray.toArgb()),
                darkMuted = palette.getDarkMutedColor(Color.Black.toArgb()),
                onVibrant = palette.vibrantSwatch?.titleTextColor ?: Color.White.toArgb(),
            )

            paletteCache[albumId] = colors
            colors
        }

    private suspend fun loadBitmap(uri: Any): Bitmap? {
        val req = ImageRequest.Builder(context)
            .data(uri)
            .size(200, 200)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(req)
        return (result as? SuccessResult)?.drawable?.let {
            (it as? BitmapDrawable)?.bitmap
        }
    }
}

data class ArtworkColors(
    val dominant: Int,
    val vibrant: Int,
    val darkVibrant: Int,
    val lightVibrant: Int,
    val muted: Int,
    val darkMuted: Int,
    val onVibrant: Int,
) {
    companion object {
        val Default = ArtworkColors(
            dominant = Color.DarkGray.toArgb(),
            vibrant = Color(0xFF7C4DFF).toArgb(),
            darkVibrant = Color(0xFF311B92).toArgb(),
            lightVibrant = Color(0xFFB39DDB).toArgb(),
            muted = Color(0xFF546E7A).toArgb(),
            darkMuted = Color(0xFF263238).toArgb(),
            onVibrant = Color.White.toArgb(),
        )
    }

    fun toComposeColors() = ArtworkComposeColors(
        dominant = Color(dominant),
        vibrant = Color(vibrant),
        darkVibrant = Color(darkVibrant),
        lightVibrant = Color(lightVibrant),
        muted = Color(muted),
        onVibrant = Color(onVibrant),
    )
}

data class ArtworkComposeColors(
    val dominant: Color,
    val vibrant: Color,
    val darkVibrant: Color,
    val lightVibrant: Color,
    val muted: Color,
    val onVibrant: Color,
)
