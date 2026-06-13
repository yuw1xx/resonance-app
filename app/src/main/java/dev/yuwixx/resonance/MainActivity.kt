// Single-activity host: sets up the theme, handles incoming audio URIs (share-to-play),
// drives the in-app update flow, and renders ResonanceNavGraph inside ResonanceTheme.
package dev.yuwixx.resonance

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import dev.yuwixx.resonance.cast.CastManager
import dev.yuwixx.resonance.presentation.components.ConfettiOverlay
import dev.yuwixx.resonance.presentation.navigation.ResonanceNavGraph
import dev.yuwixx.resonance.presentation.viewmodel.PlayerViewModel
import dev.yuwixx.resonance.presentation.viewmodel.SettingsViewModel
import dev.yuwixx.resonance.ui.theme.ResonanceTheme
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var castManager: CastManager

    private val receiveUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        castManager.initialize()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val layoutParams = window.attributes
            layoutParams.preferredRefreshRate = 120f
            window.attributes = layoutParams
        }

        receiveUri.value = intent?.data

        setContent {
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val settingsViewModel: SettingsViewModel = hiltViewModel()

            val dynamicColor by playerViewModel.dynamicColor.collectAsState()
            val darkThemePref by settingsViewModel.darkTheme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val uri by receiveUri.collectAsState()
            val amoledMode by playerViewModel.prefs.amoledBlackTheme.collectAsState(initial = false)
            val systemDynamicEnabled by playerViewModel.prefs.dynamicColorEnabled.collectAsState(initial = true)
            val partyMode by playerViewModel.partyMode.collectAsState(initial = false)

            val useDark = when (darkThemePref) {
                "LIGHT" -> false
                "DARK"  -> true
                else    -> systemDark
            }

            LaunchedEffect(Unit) {
                settingsViewModel.checkForUpdates(APP_VERSION, isManual = false)
            }

            val updateState by settingsViewModel.updateState.collectAsState()

            when (val state = updateState) {
                is SettingsViewModel.UpdateState.Available -> {
                    AlertDialog(
                        onDismissRequest = { settingsViewModel.dismissUpdate() },
                        icon = { Icon(Icons.Rounded.NewReleases, null, tint = MaterialTheme.colorScheme.primary) },
                        title = { Text("Update Available") },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    "Version ${state.release.tagName} is available!",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val body = state.release.body
                                if (!body.isNullOrBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = parseMarkdown(body),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { settingsViewModel.downloadUpdate(this@MainActivity, state.assetUrl) }) {
                                Text("Download")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { settingsViewModel.dismissUpdate() }) { Text("Later") }
                        }
                    )
                }
                is SettingsViewModel.UpdateState.Downloading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Downloading Update") },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("${(state.progress * 100).toInt()}%")
                            }
                        },
                        confirmButton = {}
                    )
                }
                is SettingsViewModel.UpdateState.ReadyToInstall -> {
                    AlertDialog(
                        onDismissRequest = { settingsViewModel.dismissUpdate() },
                        title = { Text("Download Complete") },
                        text = { Text("The update is ready to be installed.") },
                        confirmButton = {
                            Button(onClick = {
                                settingsViewModel.dismissUpdate()
                                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "${packageName}.provider",
                                    state.apkFile
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            }) { Text("Install") }
                        },
                        dismissButton = {
                            TextButton(onClick = { settingsViewModel.dismissUpdate() }) { Text("Cancel") }
                        }
                    )
                }
                is SettingsViewModel.UpdateState.Checking -> {
                    if (state.isManual) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Checking for updates...") },
                            text = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            },
                            confirmButton = {}
                        )
                    }
                }
                is SettingsViewModel.UpdateState.UpToDate -> {
                    AlertDialog(
                        onDismissRequest = { settingsViewModel.dismissUpdate() },
                        title = { Text("Up to date") },
                        text = { Text("You are on the latest version of Resonance.") },
                        confirmButton = { TextButton(onClick = { settingsViewModel.dismissUpdate() }) { Text("OK") } }
                    )
                }
                is SettingsViewModel.UpdateState.Error -> {
                    AlertDialog(
                        onDismissRequest = { settingsViewModel.dismissUpdate() },
                        title = { Text("Update Error") },
                        text = { Text(state.message) },
                        confirmButton = { TextButton(onClick = { settingsViewModel.dismissUpdate() }) { Text("OK") } }
                    )
                }
                else -> {}
            }

            ResonanceTheme(
                darkTheme = useDark,
                dynamicColorSeed = dynamicColor,
                systemDynamicEnabled = systemDynamicEnabled,
                amoledMode = amoledMode,
            ) {
                Box {
                    ResonanceNavGraph(
                        playerViewModel = playerViewModel,
                        receiveUri = uri,
                        onReceiveDismiss = { receiveUri.value = null }
                    )
                    if (partyMode) {
                        ConfettiOverlay()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { receiveUri.value = it }
    }
}

// Minimal Markdown renderer used to display GitHub release notes in the update dialog.
private fun parseMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> {
                append("\n")
            }
            trimmed.startsWith("### ") -> {
                appendInlineMarkdown(trimmed.removePrefix("### "), bold = true)
                append("\n")
            }
            trimmed.startsWith("## ") -> {
                appendInlineMarkdown(trimmed.removePrefix("## "), bold = true)
                append("\n")
            }
            trimmed.startsWith("# ") -> {
                appendInlineMarkdown(trimmed.removePrefix("# "), bold = true)
                append("\n")
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("• ")
                appendInlineMarkdown(trimmed.drop(2))
                append("\n")
            }
            trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                val dotIdx = trimmed.indexOf('.')
                append("${trimmed.substring(0, dotIdx + 1)} ")
                appendInlineMarkdown(trimmed.substring(dotIdx + 2).trim())
                append("\n")
            }
            trimmed.matches(Regex("^[-*]{3,}$")) -> {
                append("──────────────\n")
            }
            else -> {
                appendInlineMarkdown(trimmed)
                append("\n")
            }
        }
        i++
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String, bold: Boolean = false) {
    val pattern = Regex(
        """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|\*(.+?)\*|_(.+?)_|`(.+?)`"""
    )

    var last = 0
    for (match in pattern.findAll(text)) {
        val plain = text.substring(last, match.range.first)
        if (plain.isNotEmpty()) {
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(plain) }
            else append(plain)
        }

        val (boldAst, boldUnd, strike, italicAst, italicUnd, code) = match.destructured
        when {
            boldAst.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldAst) }
            boldUnd.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldUnd) }
            strike.isNotEmpty() ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike) }
            italicAst.isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italicAst) }
            italicUnd.isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italicUnd) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(code) }
        }
        last = match.range.last + 1
    }

    val tail = text.substring(last)
    if (tail.isNotEmpty()) {
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tail) }
        else append(tail)
    }
}
