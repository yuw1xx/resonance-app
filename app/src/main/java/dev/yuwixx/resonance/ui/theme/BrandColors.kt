package dev.yuwixx.resonance.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend

/** Brand colors are harmonized toward the scheme's primary so they don't clash with dynamic palettes. */
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
