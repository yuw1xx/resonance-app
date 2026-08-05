// FOSS-flavor stub: Chromecast requires Google Play Services, unavailable in this build.
// Renders nothing so call sites (HomeScreen, PlayerScreen) don't need flavor-specific branching.
package dev.yuwixx.resonance.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    // No Cast SDK in this build.
}
