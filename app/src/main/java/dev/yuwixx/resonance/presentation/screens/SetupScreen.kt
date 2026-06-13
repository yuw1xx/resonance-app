package dev.yuwixx.resonance.presentation.screens

import android.Manifest
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.*
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.repository.LastFmAuthState
import dev.yuwixx.resonance.data.repository.NavidromeConnectionState
import dev.yuwixx.resonance.presentation.viewmodel.SettingsViewModel

private const val TOTAL_STEPS = 5
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SetupScreen(onComplete: () -> Unit) {
    var currentStep by remember { mutableIntStateOf(0) }
    var chosenSource by remember { mutableStateOf(MusicSource.LOCAL) }

    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val lastFmAuthState by settingsViewModel.lastFmAuthState.collectAsState()
    val navidromeConnectionState by settingsViewModel.navidromeConnectionState.collectAsState()

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionState = rememberPermissionState(permission)

    fun advance() {
        val next = currentStep + 1
        if (next == 2 && chosenSource == MusicSource.NAVIDROME) {
            currentStep = 3
        } else {
            currentStep = next
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (slideInHorizontally(tween(420, easing = EmphasizedDecelerate)) { it } +
                        fadeIn(tween(350, easing = EmphasizedDecelerate)))
                        .togetherWith(
                            slideOutHorizontally(tween(340, easing = EmphasizedAccelerate)) { -it } +
                                fadeOut(tween(180, easing = EmphasizedAccelerate))
                        )
                },
                label = "setup_steps"
            ) { step ->
                when (step) {
                    0 -> WelcomeStep()
                    1 -> SourceStep(
                        selectedSource = chosenSource,
                        onSourceSelected = { chosenSource = it },
                    )
                    2 -> PermissionStep(permissionState)
                    3 -> NavidromeSetupStep(
                        visible = chosenSource == MusicSource.NAVIDROME,
                        connectionState = navidromeConnectionState,
                        onTest = { url, user, pass ->
                            settingsViewModel.testNavidromeConnection(url, user, pass)
                        },
                        onSave = { url, user, pass ->
                            settingsViewModel.saveNavidromeAndSwitch(url, user, pass)
                        },
                        lastFmAuthState = lastFmAuthState,
                        onLastFmLogin = { u, p -> settingsViewModel.lastFmLogin(u, p) },
                    )
                    4 -> ReadyStep()
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val visibleSteps = if (chosenSource == MusicSource.NAVIDROME) TOTAL_STEPS - 1 else TOTAL_STEPS
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(visibleSteps) { index ->
                    val dotStep = if (chosenSource == MusicSource.NAVIDROME && index >= 2) index + 1 else index
                    Box(
                        modifier = Modifier
                            .size(if (dotStep == currentStep) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (dotStep == currentStep) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            when (currentStep) {
                3 -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { advance() }) { Text("Skip") }
                        val canContinue = if (chosenSource == MusicSource.NAVIDROME) {
                            navidromeConnectionState is NavidromeConnectionState.Connected
                        } else {
                            lastFmAuthState is LastFmAuthState.Authenticated
                        }
                        Button(
                            onClick = { advance() },
                            shape = MaterialTheme.shapes.medium,
                            enabled = canContinue
                        ) {
                            Text("Continue")
                            Icon(
                                Icons.Rounded.ArrowForward, null,
                                modifier = Modifier.padding(start = 8.dp).size(18.dp)
                            )
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            when {
                                currentStep < TOTAL_STEPS - 1 -> {
                                    if (currentStep == 2 && !permissionState.status.isGranted) {
                                        permissionState.launchPermissionRequest()
                                    } else {
                                        advance()
                                    }
                                }
                                else -> onComplete()
                            }
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(if (currentStep == TOTAL_STEPS - 1) "Get Started" else "Continue")
                        if (currentStep < TOTAL_STEPS - 1) {
                            Icon(
                                Icons.Rounded.ArrowForward, null,
                                modifier = Modifier.padding(start = 8.dp).size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(permissionState.status.isGranted) {
        if (currentStep == 2 && permissionState.status.isGranted) {
            advance()
        }
    }
}

@Composable
private fun WelcomeStep() {
    SetupContent(
        icon = Icons.Rounded.MusicNote,
        title = "Welcome to Resonance",
        description = "Your personal music experience, refined with Material You 3. Experience your library like never before."
    )
}

@Composable
private fun SourceStep(
    selectedSource: MusicSource,
    onSourceSelected: (MusicSource) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                Icons.Rounded.LibraryMusic, null,
                modifier = Modifier.padding(32.dp).size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Where's your music?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Choose where Resonance should load your library from. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(32.dp))

        SourceCard(
            selected = selectedSource == MusicSource.LOCAL,
            icon = Icons.Rounded.PhoneAndroid,
            title = "Local Library",
            description = "Play music stored on this device.",
            onClick = { onSourceSelected(MusicSource.LOCAL) }
        )

        Spacer(Modifier.height(12.dp))

        SourceCard(
            selected = selectedSource == MusicSource.NAVIDROME,
            icon = Icons.Rounded.Cloud,
            title = "Navidrome",
            description = "Stream from your self-hosted Navidrome server.",
            onClick = { onSourceSelected(MusicSource.NAVIDROME) }
        )
    }
}

@Composable
private fun SourceCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.padding(12.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionStep(permissionState: PermissionState) {
    SetupContent(
        icon = Icons.Rounded.Storage,
        title = "Library Access",
        description = "To find and play your music, Resonance needs permission to read audio files on your device."
    )
}

@Composable
private fun NavidromeSetupStep(
    visible: Boolean,
    connectionState: NavidromeConnectionState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
    lastFmAuthState: LastFmAuthState,
    onLastFmLogin: (String, String) -> Unit,
) {
    if (visible) {
        NavidromeStep(connectionState = connectionState, onTest = onTest, onSave = onSave)
    } else {
        LastFmStep(authState = lastFmAuthState, onLogin = onLastFmLogin)
    }
}

@Composable
private fun NavidromeStep(
    connectionState: NavidromeConnectionState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState is NavidromeConnectionState.Connected) {
            onSave(serverUrl, username, password)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                Icons.Rounded.Cloud, null,
                modifier = Modifier.padding(32.dp).size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Connect to Navidrome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Enter your server address and credentials. You can update these later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://music.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Language, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        when (connectionState) {
            is NavidromeConnectionState.Connecting -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is NavidromeConnectionState.Connected -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Connected! Tap Continue below.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is NavidromeConnectionState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            connectionState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            else -> Unit
        }

        if (connectionState !is NavidromeConnectionState.Connected) {
            Button(
                onClick = { onTest(serverUrl, username, password) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                        && connectionState !is NavidromeConnectionState.Connecting
            ) {
                Icon(Icons.Rounded.Wifi, null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text("Test Connection")
            }
        }
    }
}

@Composable
private fun LastFmStep(
    authState: LastFmAuthState,
    onLogin: (String, String) -> Unit,
) {
    var showLoginDialog by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                Icons.Rounded.Radio, null,
                modifier = Modifier.padding(32.dp).size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Scrobble Your Music",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Connect your Last.fm account to automatically track every song you listen to and build a rich listening history.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(28.dp))

        when (authState) {
            is LastFmAuthState.Authenticated -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Linked as ${authState.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Tap Continue below to proceed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            is LastFmAuthState.Loading -> CircularProgressIndicator()
            is LastFmAuthState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
                        Text(authState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showLoginDialog = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    LastFmBadge()
                    Text("Try Again")
                }
            }
            LastFmAuthState.Idle -> {
                Button(onClick = { showLoginDialog = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    LastFmBadge()
                    Text("Link Account")
                }
            }
        }
    }

    if (showLoginDialog) {
        LastFmLoginDialog(onDismiss = { showLoginDialog = false }, onLogin = { u, p -> onLogin(u, p); showLoginDialog = false })
    }
}

@Composable
private fun LastFmBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFD51007), modifier = Modifier.padding(end = 8.dp)) {
        Text("Last.fm", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun LastFmLoginDialog(onDismiss: () -> Unit, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFD51007)) {
                Text("Last.fm", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        },
        title = { Text("Sign in to Last.fm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onLogin(username, password) }, enabled = username.isNotBlank() && password.isNotBlank()) { Text("Sign In") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReadyStep() {
    SetupContent(
        icon = Icons.Rounded.AutoAwesome,
        title = "All Set!",
        description = "We're ready to build your library. Enjoy the smooth transitions and expressive design of Resonance."
    )
}

@Composable
private fun SetupContent(icon: ImageVector, title: String, description: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, modifier = Modifier.padding(32.dp).size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
