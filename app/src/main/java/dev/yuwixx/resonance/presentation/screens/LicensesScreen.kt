package dev.yuwixx.resonance.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LicenseItem(
    val moduleName: String,
    val moduleUrl: String,
    val moduleVersion: String,
    val moduleLicense: String,
    val moduleLicenseUrl: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var licenses by remember { mutableStateOf<List<LicenseItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val jsonString = context.assets.open("license-report.json")
                    .bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonString)
                val array = jsonObject.getJSONArray("dependencies")

                val list = mutableListOf<LicenseItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        LicenseItem(
                            moduleName = obj.optString("moduleName", "Unknown Library"),
                            moduleUrl = obj.optString("moduleUrl", ""),
                            moduleVersion = obj.optString("moduleVersion", ""),
                            moduleLicense = obj.optString("moduleLicense", "Unknown License"),
                            moduleLicenseUrl = obj.optString("moduleLicenseUrl", "")
                        )
                    )
                }
                licenses = list.sortedBy { it.moduleName }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Third-Party Licenses", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 32.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                items(licenses) { item ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (item.moduleUrl.isNotBlank()) uriHandler.openUri(item.moduleUrl)
                        },
                        headlineContent = {
                            Text(
                                text = item.moduleName,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        supportingContent = {
                            Column {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Version: ${item.moduleVersion}", style = MaterialTheme.typography.bodySmall)
                                Text(item.moduleLicense, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        trailingContent = {
                            if (item.moduleLicenseUrl.isNotBlank() && item.moduleLicenseUrl != "null") {
                                IconButton(onClick = { uriHandler.openUri(item.moduleLicenseUrl) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = "View License",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}
