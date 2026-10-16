// Third-party brand colors (Last.fm, Maloja) and the "loved" heart. Each is harmonized toward
// the current scheme's primary — hue nudged slightly, still recognisable — so it doesn't clash
// with whatever dynamic/seeded palette is active.
package dev.yuwixx.resonance.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend

object BrandColors {
    private val LastFmRed = Color(0xFFD51007)
    private val MalojaGreen = Color(0xFF1A8C4E)
    private val LovedRed = Color(0xFFE8384D)

    val lastFm: Color @Composable @ReadOnlyComposable get() = harmonize(LastFmRed)
    val maloja: Color @Composable @ReadOnlyComposable get() = harmonize(MalojaGreen)
    val loved: Color @Composable @ReadOnlyComposable get() = harmonize(LovedRed)

    @Composable
    @ReadOnlyComposable
    private fun harmonize(color: Color): Color =
        Color(Blend.harmonize(color.toArgb(), MaterialTheme.colorScheme.primary.toArgb()))
}
