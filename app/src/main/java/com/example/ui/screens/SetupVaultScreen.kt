package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.WaDarkBackground
import com.example.ui.theme.WaDarkSurface
import com.example.ui.theme.WaDarkTextPrimary
import com.example.ui.theme.WaDarkTextSecondary
import com.example.ui.theme.WaEmerald

@SuppressLint("BatteryLife")
@Composable
fun SetupVaultScreen(
    isBusy: Boolean,
    errorBanner: String?,
    onCreateVault: (passphrase: String, pin: String, biometricEnabled: Boolean) -> Unit,
    onJoinVault: (roomCode: String, passphrase: String, pin: String, biometricEnabled: Boolean) -> Unit
) {
    val context = LocalContext.current
    var isJoinMode by remember { mutableStateOf(false) }
    var roomCode by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var biometricEnabled by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }

    var notificationsGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryUnrestricted by remember {
        mutableStateOf(
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        )
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WaDarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.img_vault_shield),
                    contentDescription = "Vault Setup",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Pair & Encrypt Your Vault",
                        style = MaterialTheme.typography.titleLarge,
                        color = WaDarkTextPrimary
                    )
                    Text(
                        text = "Keys stay on-device (PBKDF2 100k + AES-256-GCM)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaDarkTextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = !isJoinMode,
                    onClick = { isJoinMode = false },
                    label = { Text("Create New Vault") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mode_create_vault"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaEmerald,
                        selectedLabelColor = Color.White,
                        labelColor = WaDarkTextSecondary
                    )
                )
                FilterChip(
                    selected = isJoinMode,
                    onClick = { isJoinMode = true },
                    label = { Text("Join Partner Vault") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mode_join_vault"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaEmerald,
                        selectedLabelColor = Color.White,
                        labelColor = WaDarkTextSecondary
                    )
                )
            }

            if (isJoinMode) {
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it.uppercase() },
                    label = { Text("Partner Vault Code (e.g. DUET-7X9K-2M4P)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_code_input"),
                    colors = vaultFieldColors()
                )
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Shared Secret Passphrase (min 8 chars)") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = WaEmerald) },
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            imageVector = if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Passphrase Visibility",
                            tint = WaDarkTextSecondary
                        )
                    }
                },
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("secret_passphrase_input"),
                colors = vaultFieldColors()
            )

            Text(
                text = "Both partners must enter the exact same Secret Passphrase. It is never sent to Firebase.",
                style = MaterialTheme.typography.labelMedium,
                color = WaDarkTextSecondary
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { input ->
                    if (input.length <= 4 && input.all { it.isDigit() }) {
                        pin = input
                    }
                },
                label = { Text("4-Digit App Lock PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_pin_input"),
                colors = vaultFieldColors()
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = WaDarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biometric Unlock",
                            style = MaterialTheme.typography.titleMedium,
                            color = WaDarkTextPrimary
                        )
                        Text(
                            text = "Use Fingerprint or Face Unlock on launch",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WaDarkTextSecondary
                        )
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = { biometricEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = WaEmerald)
                    )
                }
            }

            // Notification & Battery Optimization Reliability Onboarding
            Card(
                colors = CardDefaults.cardColors(containerColor = WaDarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Zero-Miss Push Reliability",
                        style = MaterialTheme.typography.titleMedium,
                        color = WaDarkTextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (notificationsGranted) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = if (notificationsGranted) WaEmerald else Color(0xFFF5C451)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (notificationsGranted) "Push notifications enabled" else "Allow notifications (Android 13+)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WaDarkTextPrimary
                            )
                        }
                        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(
                                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            ) {
                                Text("Enable")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (batteryUnrestricted) Icons.Default.CheckCircle else Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (batteryUnrestricted) WaEmerald else Color(0xFFF5C451)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (batteryUnrestricted) "Battery optimization unrestricted" else "Disable battery optimization for calls",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WaDarkTextPrimary
                            )
                        }
                        if (!batteryUnrestricted) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    try {
                                        batteryLauncher.launch(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        localError = "Open Android Settings > Apps > Duet > Battery to set Unrestricted."
                                    }
                                }
                            ) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }

            val displayedError = localError ?: errorBanner
            if (!displayedError.isNullOrBlank()) {
                Surface(
                    color = Color(0x33EF4444),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = displayedError,
                        color = Color(0xFFFCA5A5),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = {
                    localError = null
                    when {
                        isJoinMode && roomCode.trim().length < 4 -> {
                            localError = "Please enter your partner's Vault Code."
                        }
                        passphrase.length < 8 -> {
                            localError = "Secret Passphrase must be at least 8 characters."
                        }
                        pin.length != 4 -> {
                            localError = "Enter a 4-digit App Lock PIN."
                        }
                        isJoinMode -> onJoinVault(roomCode.trim(), passphrase, pin, biometricEnabled)
                        else -> onCreateVault(passphrase, pin, biometricEnabled)
                    }
                },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("complete_vault_setup_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WaEmerald, contentColor = Color.White)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (isJoinMode) "Join Encrypted Vault" else "Create Private Vault",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun vaultFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = WaEmerald,
    unfocusedBorderColor = Color(0xFF2D3E48),
    focusedLabelColor = WaEmerald,
    unfocusedLabelColor = WaDarkTextSecondary,
    focusedTextColor = WaDarkTextPrimary,
    unfocusedTextColor = WaDarkTextPrimary,
    cursorColor = WaEmerald
)
