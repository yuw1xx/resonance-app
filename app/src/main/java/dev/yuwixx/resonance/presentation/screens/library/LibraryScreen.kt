// The Library tab: Songs, Albums, Artists, Liked and (local source) Folders behind one
// navigation destination. Each section keeps its own screen and top-bar actions; the host
// provides a row of connected toggle buttons that those screens draw under their top bar.
package dev.yuwixx.resonance.presentation.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class LibrarySection(val label: String, val icon: ImageVector) {
    Songs("Songs", Icons.Rounded.MusicNote),
    Albums("Albums", Icons.Rounded.Album),
    Artists("Artists", Icons.Rounded.Person),
    Liked("Liked", Icons.Rounded.Favorite),
    Folders("Folders", Icons.Rounded.FolderOpen),
}

/** Non-null while a screen is shown as a Library section: draws the section switcher.
 *  Screens use it to put the switcher under their top bar, show "Library" as the title and
 *  drop their own back button / big title. */
val LocalLibrarySections = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** Top-bar title for a screen that may be hosted as a Library section. */
@Composable
fun LibraryTitle() {
    if (LocalLibrarySections.current != null) Text("Library")
}

@Composable
fun LibraryScreen(
    showFolders: Boolean,
    content: @Composable (LibrarySection) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.Songs) }
    val sections = remember(showFolders) {
        LibrarySection.entries.filter { showFolders || it != LibrarySection.Folders }
    }
    // Folders disappears when switching to Navidrome; fall back without writing state here.
    val shown = if (section in sections) section else LibrarySection.Songs

    val switcher: @Composable () -> Unit = {
        LibrarySectionSwitcher(sections = sections, selected = shown, onSelect = { section = it })
    }
    val motion = MaterialTheme.motionScheme
    CompositionLocalProvider(LocalLibrarySections provides switcher) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "library_section",
        ) { content(it) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibrarySectionSwitcher(
    sections: List<LibrarySection>,
    selected: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        sections.forEachIndexed { index, item ->
            val checked = item == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(item) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    sections.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                if (checked) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                    Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                }
                Text(item.label)
            }
        }
    }
}
