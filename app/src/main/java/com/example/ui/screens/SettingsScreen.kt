package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.crypto.CryptoManager
import com.example.data.remote.GitHubUpdateClient
import com.example.ui.theme.ChatWallpaper
import com.example.ui.theme.LocalDuetChatColors
import com.example.ui.theme.WaCallRed
import com.example.ui.theme.WaEmerald
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.UpdateCheckState

@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    cryptoManager: CryptoManager,
    onBack: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val chatColors = LocalDuetChatColors.current

    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val roomId by viewModel.activeRoomId.collectAsStateWithLifecycle()
    val savedGithubRepo by viewModel.githubRepo.collectAsStateWithLifecycle()
    val autoCheckUpdates by viewModel.autoCheckUpdates.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    var githubRepoInput by remember(savedGithubRepo) { mutableStateOf(savedGithubRepo) }
    var biometricEnabled by remember { mutableStateOf(cryptoManager.isBiometricEnabled()) }
    var screenPrivacyEnabled by remember {
        mutableStateOf(
            cryptoManager.isScreenPrivacyEnabled(
                defaultValue = !com.example.MainActivity.isEmulatorDevice()
            )
        )
    }
    var webhookUrl by remember {
        val defaultUrl = BuildConfig.PUSH_WEBHOOK_URL.takeUnless { it.contains("example.invalid") }.orEmpty()
        mutableStateOf(cryptoManager.getPushWebhookUrl().ifBlank { defaultUrl })
    }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var newPassphrase by remember { mutableStateOf("") }
    val safetyNumber = remember(roomId) { cryptoManager.computeSafetyNumber() }

    Scaffold(
        topBar = {
            Surface(color = chatColors.topBarBackground, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = chatColors.topBarContent
                        )
                    }
                    Text(
                        text = "Vault Security & Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = chatColors.topBarContent
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Identity & Whitelist UIDs
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Your Identity & Couple Vault",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Firebase UID (for 2-user whitelist)", style = MaterialTheme.typography.labelMedium, color = chatColors.timestampText)
                            Text(viewModel.currentUserId, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(viewModel.currentUserId))
                            Toast.makeText(context, "UID copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy UID", tint = WaEmerald)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Vault Room Code", style = MaterialTheme.typography.labelMedium, color = chatColors.timestampText)
                            Text(roomId.ifBlank { "Not paired" }, style = MaterialTheme.typography.titleMedium, color = WaEmerald)
                        }
                        if (roomId.isNotBlank()) {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(roomId))
                                Toast.makeText(context, "Vault code copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Room Code", tint = WaEmerald)
                            }
                        }
                    }
                }
            }

            // Signal-Style Safety Number
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = WaEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Zero-Knowledge Safety Number", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Compare these 60 digits with your partner to verify both phones derived the identical 256-bit AES key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.timestampText
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = safetyNumber,
                            style = MaterialTheme.typography.labelSmall,
                            color = WaEmerald,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Appearance: WhatsApp Dark/Light Toggle & Wallpaper
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DarkMode, contentDescription = null, tint = WaEmerald)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("WhatsApp Dark Mode", style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(
                            checked = darkTheme,
                            onCheckedChange = { viewModel.toggleTheme() },
                            modifier = Modifier.testTag("theme_toggle_switch"),
                            colors = SwitchDefaults.colors(checkedTrackColor = WaEmerald)
                        )
                    }

                    Text("Chat Wallpaper", style = MaterialTheme.typography.labelMedium, color = chatColors.timestampText)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ChatWallpaper.entries) { wp ->
                            val selected = wallpaper == wp
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setWallpaper(wp) }
                                    .padding(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(if (darkTheme) wp.darkTint else wp.lightTint)
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(WaEmerald.copy(alpha = 0.35f))
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = wp.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) WaEmerald else chatColors.timestampText
                                )
                            }
                        }
                    }
                }
            }

            // Privacy & Passphrase
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = WaEmerald)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Biometric App Lock", style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = {
                                biometricEnabled = it
                                viewModel.updateBiometricEnabled(it)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = WaEmerald)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ScreenshotMonitor, contentDescription = null, tint = WaEmerald)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Block Screenshots (FLAG_SECURE)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Hides recents preview & blocks screen capture",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = chatColors.timestampText
                                )
                            }
                        }
                        Switch(
                            checked = screenPrivacyEnabled,
                            onCheckedChange = { enabled ->
                                screenPrivacyEnabled = enabled
                                cryptoManager.setScreenPrivacyEnabled(enabled)
                                val act = context.findSettingsActivity()
                                if (enabled) {
                                    act?.window?.setFlags(
                                        WindowManager.LayoutParams.FLAG_SECURE,
                                        WindowManager.LayoutParams.FLAG_SECURE
                                    )
                                } else {
                                    act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = WaEmerald)
                        )
                    }

                    OutlinedButton(
                        onClick = { showPassphraseDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = WaEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Shared Secret Passphrase")
                    }
                }
            }

            // Spark-Plan Push Webhook Relay
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = WaEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Spark-Plan FCM Push Webhook", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Optional Cloudflare Worker or Google Apps Script URL to relay encrypted FCM pushes without Blaze billing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.timestampText
                    )
                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { webhookUrl = it },
                        label = { Text("https://your-worker.workers.dev") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            viewModel.updateWebhookUrl(webhookUrl)
                            Toast.makeText(context, "Webhook URL saved", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaEmerald)
                    ) {
                        Text("Save Webhook URL")
                    }
                }
            }

            // GitHub Releases In-App Updater
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = WaEmerald)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("App Updates (GitHub Releases)", style = MaterialTheme.typography.titleMedium)
                        }
                        Surface(
                            color = WaEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = WaEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = "Sync this project to GitHub and tag a release (e.g. v1.0.1) to build APKs via GitHub Actions and check/download updates inside the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.timestampText
                    )
                    OutlinedTextField(
                        value = githubRepoInput,
                        onValueChange = { githubRepoInput = it },
                        label = { Text("GitHub Repo (owner/repo or URL)") },
                        placeholder = { Text("username/duet-messenger") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("github_repo_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-check on launch", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = autoCheckUpdates,
                            onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = WaEmerald)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (viewModel.saveGitHubRepo(githubRepoInput)) {
                                    Toast.makeText(context, "GitHub repository saved", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Enter valid owner/repo format", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Repo")
                        }
                        Button(
                            onClick = {
                                if (viewModel.saveGitHubRepo(githubRepoInput)) {
                                    viewModel.checkForUpdates(manual = true)
                                } else {
                                    Toast.makeText(context, "Enter valid owner/repo format", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = updateState !is UpdateCheckState.Checking,
                            colors = ButtonDefaults.buttonColors(containerColor = WaEmerald),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("check_updates_button")
                        ) {
                            if (updateState is UpdateCheckState.Checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Check Updates")
                            }
                        }
                    }

                    when (val st = updateState) {
                        is UpdateCheckState.Available -> {
                            val rel = st.release
                            Surface(
                                color = WaEmerald.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "New Version Available: ${rel.tagName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = WaEmerald
                                    )
                                    Text(
                                        text = rel.releaseNotes.take(260),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                val url = rel.apkDownloadUrl ?: rel.releasePageUrl
                                                GitHubUpdateClient.openUrlInBrowser(context, url)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaEmerald)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(if (rel.apkDownloadUrl != null) "Download APK" else "Open Release")
                                        }
                                        if (rel.apkDownloadUrl != null) {
                                            OutlinedButton(
                                                onClick = { GitHubUpdateClient.openUrlInBrowser(context, rel.releasePageUrl) }
                                            ) {
                                                Text("Release Notes")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is UpdateCheckState.UpToDate -> {
                            Text(
                                text = "Up to date (latest on GitHub: ${st.release.tagName}).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WaEmerald
                            )
                        }
                        is UpdateCheckState.Error -> {
                            Text(
                                text = st.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = WaCallRed
                            )
                        }
                        else -> Unit
                    }
                }
            }

            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(containerColor = WaCallRed, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showPassphraseDialog = false },
            title = { Text("Update Secret Passphrase") },
            text = {
                OutlinedTextField(
                    value = newPassphrase,
                    onValueChange = { newPassphrase = it },
                    label = { Text("New Passphrase (min 8 chars)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPassphrase.length >= 8) {
                            viewModel.updatePassphrase(newPassphrase)
                            newPassphrase = ""
                            showPassphraseDialog = false
                            Toast.makeText(context, "256-bit AES key re-derived", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPassphraseDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun Context.findSettingsActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
