package dev.yuwixx.resonance.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/** Brand seed used when neither wallpaper colors nor a preset are active. */
val ResonanceSeed = Color(0xFF6750A4)

val PresetColors = listOf(
    Color(0xFF6750A4),
    Color(0xFF006A6A),
    Color(0xFF984061),
    Color(0xFF3B608F),
    Color(0xFF626200),
    Color(0xFF8B4100),
    Color(0xFF006D3B),
)

/** How a seed color is spread into a full scheme — the same choices Android's wallpaper
 *  picker offers. */
enum class PaletteStyle(val label: String) {
    TONAL_SPOT("Tonal"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    FIDELITY("Faithful"),
    NEUTRAL("Neutral");

    companion object {
        fun from(name: String?): PaletteStyle = entries.firstOrNull { it.name == name } ?: TONAL_SPOT
    }
}

private val dynamicColors = MaterialDynamicColors()

/** Builds a complete Material 3 scheme (every surface-container and fixed role) from one seed
 *  using the Material Color Utilities — replaces the old hand-made schemes that tinted
 *  containers with the seed at partial alpha, which made them translucent and low-contrast. */
fun seedColorScheme(seed: Color, dark: Boolean, style: PaletteStyle = PaletteStyle.TONAL_SPOT): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    val spec = ColorSpec.SpecVersion.SPEC_2025
    val platform = DynamicScheme.Platform.PHONE
    val scheme: DynamicScheme = when (style) {
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0, spec, platform)
        PaletteStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0, spec, platform)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0, spec, platform)
        PaletteStyle.FIDELITY -> SchemeFidelity(hct, dark, 0.0, spec, platform)
        PaletteStyle.NEUTRAL -> SchemeNeutral(hct, dark, 0.0, spec, platform)
    }
    fun DynamicColor.c() = Color(getArgb(scheme))
    with(dynamicColors) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary.c(),
            onPrimary = onPrimary.c(),
            primaryContainer = primaryContainer.c(),
            onPrimaryContainer = onPrimaryContainer.c(),
            inversePrimary = inversePrimary.c(),
            secondary = secondary.c(),
            onSecondary = onSecondary.c(),
            secondaryContainer = secondaryContainer.c(),
            onSecondaryContainer = onSecondaryContainer.c(),
            tertiary = tertiary.c(),
            onTertiary = onTertiary.c(),
            tertiaryContainer = tertiaryContainer.c(),
            onTertiaryContainer = onTertiaryContainer.c(),
            background = background.c(),
            onBackground = onBackground.c(),
            surface = surface.c(),
            onSurface = onSurface.c(),
            surfaceVariant = surfaceVariant.c(),
            onSurfaceVariant = onSurfaceVariant.c(),
            surfaceTint = surfaceTint.c(),
            inverseSurface = inverseSurface.c(),
            inverseOnSurface = inverseOnSurface.c(),
            error = error.c(),
            onError = onError.c(),
            errorContainer = errorContainer.c(),
            onErrorContainer = onErrorContainer.c(),
            outline = outline.c(),
            outlineVariant = outlineVariant.c(),
            scrim = scrim.c(),
            surfaceBright = surfaceBright.c(),
            surfaceDim = surfaceDim.c(),
            surfaceContainer = surfaceContainer.c(),
            surfaceContainerHigh = surfaceContainerHigh.c(),
            surfaceContainerHighest = surfaceContainerHighest.c(),
            surfaceContainerLow = surfaceContainerLow.c(),
            surfaceContainerLowest = surfaceContainerLowest.c(),
            primaryFixed = primaryFixed.c(),
            primaryFixedDim = primaryFixedDim.c(),
            onPrimaryFixed = onPrimaryFixed.c(),
            onPrimaryFixedVariant = onPrimaryFixedVariant.c(),
            secondaryFixed = secondaryFixed.c(),
            secondaryFixedDim = secondaryFixedDim.c(),
            onSecondaryFixed = onSecondaryFixed.c(),
            onSecondaryFixedVariant = onSecondaryFixedVariant.c(),
            tertiaryFixed = tertiaryFixed.c(),
            tertiaryFixedDim = tertiaryFixedDim.c(),
            onTertiaryFixed = onTertiaryFixed.c(),
            onTertiaryFixedVariant = onTertiaryFixedVariant.c(),
        )
    }
}

/** True-black variant: background/surface become pure black (OLED pixels off) while the
 *  container ladder is pulled toward black but keeps its tint and its relative steps, so cards,
 *  sheets and nav bars stay distinguishable from the background. */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.6f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.5f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.4f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.3f),
    surfaceVariant = lerp(surfaceVariant, Color.Black, 0.4f),
    surfaceBright = lerp(surfaceBright, Color.Black, 0.3f),
)

/** The app's shape scale, driven by the Appearance → Corner radius setting (default 28 → the
 *  M3 Expressive defaults). Everything uses MaterialTheme.shapes, so one slider rounds the
 *  whole app consistently. */
fun resonanceShapes(cornerRadius: Int): Shapes {
    val f = (cornerRadius / 28f).coerceIn(0f, 2f)
    fun r(base: Int) = RoundedCornerShape((base * f).dp)
    return Shapes(
        extraSmall = r(6),
        small = r(10),
        medium = r(16),
        large = r(22),
        largeIncreased = r(26),
        extraLarge = r(30),
        extraLargeIncreased = r(34),
        extraExtraLarge = r(48),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ResonanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorSeed: Color? = null,
    systemDynamicEnabled: Boolean = true,
    cornerRadius: Int = 28,
    amoledMode: Boolean = false,
    paletteStyle: PaletteStyle = PaletteStyle.TONAL_SPOT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = remember(darkTheme, dynamicColorSeed, systemDynamicEnabled, paletteStyle) {
        when {
            dynamicColorSeed != null -> seedColorScheme(dynamicColorSeed, darkTheme, paletteStyle)
            systemDynamicEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> seedColorScheme(ResonanceSeed, darkTheme, paletteStyle)
        }
    }
    val colorScheme = remember(baseScheme, amoledMode, darkTheme) {
        if (amoledMode && darkTheme) baseScheme.toAmoled() else baseScheme
    }
    val shapes = remember(cornerRadius) { resonanceShapes(cornerRadius) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = ResonanceTypography,
        content = content,
    )
}
