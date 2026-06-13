package dev.yuwixx.resonance.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val Violet80 = Color(0xFFCBB8FF)
val Violet40 = Color(0xFF6650A4)
val VioletContainer = Color(0xFF3D1F6B)
val VioletContainerLight = Color(0xFFEADDFF)

val Pink80 = Color(0xFFFFB3C1)
val Pink40 = Color(0xFF984061)

val Surface0 = Color(0xFF0E0A17)
val Surface1 = Color(0xFF1A1426)
val Surface2 = Color(0xFF251D36)
val Surface3 = Color(0xFF312848)

val OnSurface = Color(0xFFE9E0F3)
val OnSurfaceVariant = Color(0xFFCBC2DB)

val DarkColorScheme = darkColorScheme(
    primary = Violet80,
    onPrimary = Color(0xFF21005D),
    primaryContainer = VioletContainer,
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Pink80,
    onSecondary = Color(0xFF550027),
    secondaryContainer = Color(0xFF3E0020),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF80DEEA),
    onTertiary = Color(0xFF00363C),
    background = Surface0,
    onBackground = OnSurface,
    surface = Surface1,
    onSurface = OnSurface,
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Color(0xFF958DA5),
    outlineVariant = Color(0xFF4A4458),
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Color(0xFF3D305A),
)

val LightColorScheme = lightColorScheme(
    primary = Violet40,
    onPrimary = Color.White,
    primaryContainer = VioletContainerLight,
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Pink40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3E0020),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0EA),
)

val PresetColors = listOf(
    Color(0xFF6750A4),
    Color(0xFF006A6A),
    Color(0xFF984061),
    Color(0xFF3B608F),
    Color(0xFF626200),
    Color(0xFF8B4100),
    Color(0xFF006D3B),
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

val LocalDynamicColorSeed = compositionLocalOf<Color?> { null }

@Composable
fun ResonanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorSeed: Color? = null,
    systemDynamicEnabled: Boolean = true,
    cornerRadius: Int = 28,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColorSeed != null -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = dynamicColorSeed,
                    onPrimary = Color.Black,
                    primaryContainer = dynamicColorSeed.copy(alpha = 0.3f),
                    onPrimaryContainer = Color.White,
                    secondary = dynamicColorSeed,
                    onSecondary = Color.Black,
                    secondaryContainer = dynamicColorSeed.copy(alpha = 0.2f),
                    onSecondaryContainer = Color.White,
                    tertiary = dynamicColorSeed,
                    background = Color(0xFF121212),
                    onBackground = Color.White,
                    surface = Color(0xFF121212),
                    onSurface = Color.White,
                    surfaceVariant = Color(0xFF222222),
                    onSurfaceVariant = Color.LightGray,
                    outline = dynamicColorSeed.copy(alpha = 0.5f)
                )
            } else {
                lightColorScheme(
                    primary = dynamicColorSeed,
                    onPrimary = Color.White,
                    primaryContainer = dynamicColorSeed.copy(alpha = 0.2f),
                    onPrimaryContainer = dynamicColorSeed,
                    secondary = dynamicColorSeed,
                    onSecondary = Color.White,
                    secondaryContainer = dynamicColorSeed.copy(alpha = 0.1f),
                    onSecondaryContainer = dynamicColorSeed,
                    tertiary = dynamicColorSeed,
                    background = Color.White,
                    onBackground = Color.Black,
                    surface = Color.White,
                    onSurface = Color.Black,
                    surfaceVariant = Color(0xFFF5F5F5),
                    onSurfaceVariant = Color.DarkGray,
                    outline = dynamicColorSeed.copy(alpha = 0.5f)
                )
            }
        }
        systemDynamicEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val finalColorScheme = if (amoledMode && darkTheme) {
        colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF0D0D0D),
            surfaceContainer = Color(0xFF111111),
            surfaceContainerHigh = Color(0xFF181818),
            surfaceContainerHighest = Color(0xFF1F1F1F),
        )
    } else colorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val dynamicShapes = Shapes(
        extraSmall = RoundedCornerShape((cornerRadius * 0.4f).dp),
        small = RoundedCornerShape((cornerRadius * 0.6f).dp),
        medium = RoundedCornerShape(cornerRadius.dp),
        large = RoundedCornerShape((cornerRadius * 1.2f).dp),
        extraLarge = RoundedCornerShape((cornerRadius * 1.5f).dp)
    )

    CompositionLocalProvider(
        LocalDynamicColorSeed provides dynamicColorSeed,
    ) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            typography = ResonanceTypography,
            shapes = dynamicShapes,
            content = content,
        )
    }
}
