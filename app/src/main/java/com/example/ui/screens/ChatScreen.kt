package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.data.remote.AppReleaseInfo
import com.example.data.remote.GitHubUpdateClient
import com.example.media.MediaHelper
import com.example.media.VoiceNotePlayer
import com.example.media.VoiceNoteRecorder
import com.example.ui.components.ChatComposer
import com.example.ui.components.MessageBubble
import com.example.ui.theme.ChatWallpaper
import com.example.ui.theme.LocalDuetChatColors
import com.example.ui.theme.WaEmerald
import com.example.ui.theme.WaGoldAccent
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.UpdateCheckState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenStarred: () -> Unit,
    onOpenSettings: () -> Unit,
    onLockNow: () -> Unit,
    onStartCall: (isVideo: Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val chatColors = LocalDuetChatColors.current

    val roomState by viewModel.roomState.collectAsStateWithLifecycle()
    val roomId by viewModel.activeRoomId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val partnerPresence by viewModel.partnerPresence.collectAsStateWithLifecycle()
    val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val disappearingSeconds by viewModel.disappearingSeconds.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val p2pProgress by viewModel.p2pProgress.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateBannerDismissed by viewModel.updateBannerDismissed.collectAsStateWithLifecycle()
    val githubRepo by viewModel.githubRepo.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var fullScreenImageB64 by remember { mutableStateOf<String?>(null) }
    var pendingCallIsVideo by remember { mutableStateOf<Boolean?>(null) }

    val voicePlayer = remember { VoiceNotePlayer() }
    val voiceRecorder = remember { VoiceNoteRecorder(context) }

    DisposableEffect(Unit) {
        onDispose {
            voicePlayer.release()
            voiceRecorder.stopAndDiscard()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            viewModel.markVisibleMessagesRead(messages)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.sendPhoto(context, it) }
    }

    val p2pFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.sendFileP2p(it) }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required for voice notes.", Toast.LENGTH_SHORT).show()
        }
    }

    val callPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val audioOk = perms[Manifest.permission.RECORD_AUDIO] == true
        val videoOk = pendingCallIsVideo != true || perms[Manifest.permission.CAMERA] == true
        val targetVideo = pendingCallIsVideo ?: false
        pendingCallIsVideo = null
        if (audioOk && videoOk) {
            onStartCall(targetVideo)
        } else {
            Toast.makeText(context, "Camera/Mic permissions are needed for calls.", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestCallPermissionsAndStart(isVideo: Boolean) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (needed.isEmpty()) {
            onStartCall(isVideo)
        } else {
            pendingCallIsVideo = isVideo
            callPermLauncher.launch(needed.toTypedArray())
        }
    }

    val partnerName = partnerPresence?.displayName?.ifBlank { "My Partner" } ?: "My Partner"
    val partnerStatusText = when {
        partnerPresence == null -> "tap to pair or share vault code"
        partnerPresence!!.recordingAudio -> "recording audio..."
        partnerPresence!!.typing -> "typing..."
        partnerPresence!!.online -> "online"
        partnerPresence!!.lastSeenAt != null -> {
            val ms = partnerPresence!!.lastSeenAt!!.toDate().time
            val rel = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            "last seen $rel"
        }
        else -> "offline"
    }

    Scaffold(
        topBar = {
            Surface(color = chatColors.topBarBackground, shadowElevation = 4.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    if (showSearch) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                showSearch = false
                                viewModel.setSearchQuery("")
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Search",
                                    tint = chatColors.topBarContent
                                )
                            }
                            TextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("Search encrypted local cache...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("search_messages_input"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = chatColors.topBarContent,
                                    unfocusedTextColor = chatColors.topBarContent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = chatColors.topBarContent)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Partner Avatar
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(WaEmerald),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = partnerName.firstOrNull()?.uppercase() ?: "D",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = partnerName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = chatColors.topBarContent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (disappearingSeconds > 0L) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = "Disappearing Messages Active",
                                            tint = WaEmerald,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = partnerStatusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (partnerPresence?.typing == true || partnerPresence?.recordingAudio == true || partnerPresence?.online == true) {
                                        WaEmerald
                                    } else {
                                        chatColors.topBarContent.copy(alpha = 0.72f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { requestCallPermissionsAndStart(isVideo = true) },
                                modifier = Modifier.testTag("start_video_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = "Video Call",
                                    tint = chatColors.topBarContent
                                )
                            }

                            IconButton(
                                onClick = { requestCallPermissionsAndStart(isVideo = false) },
                                modifier = Modifier.testTag("start_audio_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Audio Call",
                                    tint = chatColors.topBarContent
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.testTag("chat_more_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = chatColors.topBarContent
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Search Messages") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showSearch = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Disappearing Messages") },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showTimerDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Starred Messages") },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            onOpenStarred()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings & Security") },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            onOpenSettings()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Check for Updates") },
                                        leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            if (githubRepo.isBlank()) {
                                                onOpenSettings()
                                                Toast.makeText(context, "Configure your GitHub repo in Settings", Toast.LENGTH_SHORT).show()
                                            } else {
                                                viewModel.checkForUpdates(manual = true)
                                                showUpdateDialog = true
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Lock Vault Now") },
                                        onClick = {
                                            showMenu = false
                                            onLockNow()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .background(chatColors.chatBackground)
        ) {
            // Optional WhatsApp Botanical Wallpaper Texture
            if (chatColors.isDark && wallpaper == ChatWallpaper.OBSIDIAN_BOTANICAL) {
                Image(
                    painter = painterResource(id = R.drawable.img_wallpaper_dark),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.55f)
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val availableUpdate = (updateState as? UpdateCheckState.Available)?.release
                if (availableUpdate != null && !updateBannerDismissed) {
                    Surface(
                        color = WaEmerald.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = WaEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Update available: ${availableUpdate.tagName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatColors.topBarContent
                                )
                                Text(
                                    text = "Tap Update to download from GitHub Releases",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chatColors.timestampText
                                )
                            }
                            TextButton(
                                onClick = { showUpdateDialog = true },
                                modifier = Modifier.testTag("chat_update_banner_button")
                            ) {
                                Text("Update", color = WaEmerald, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.dismissUpdateBanner() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss Update Banner",
                                    tint = chatColors.timestampText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Live P2P WebRTC DataChannel Progress Banner
                p2pProgress?.let { prog ->
                    Surface(
                        color = chatColors.topBarBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(
                                text = "${if (prog.isSending) "Sending" else "Receiving"} P2P: ${prog.fileName} (${(prog.progress * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelMedium,
                                color = chatColors.accentEmerald
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { prog.progress },
                                color = chatColors.accentEmerald,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    item(key = "e2ee_banner") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = if (chatColors.isDark) Color(0xCC182229) else Color(0xFFFFF5C4),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "🔒 ${stringResource(R.string.e2ee_banner)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (chatColors.isDark) WaGoldAccent else Color(0xFF54656F),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    val room = (roomState as? UiState.Success)?.data
                    if (room != null && room.memberUids.size < 2) {
                        item(key = "pairing_card") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = chatColors.topBarBackground),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Waiting for Partner to Pair",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = chatColors.topBarContent
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Share this Vault Code and your Secret Passphrase with your partner:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = chatColors.timestampText,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = roomId,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = WaEmerald
                                        )
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(roomId))
                                            Toast.makeText(context, "Vault code copied", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Vault Code",
                                                tint = WaEmerald
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            isMine = msg.senderUid == viewModel.currentUserId,
                            voicePlayer = voicePlayer,
                            onSwipeReply = { viewModel.setReplyingTo(it) },
                            onReactionSelected = { m, emoji -> viewModel.toggleReaction(m, emoji) },
                            onToggleStar = { viewModel.toggleStarred(it) },
                            onDeleteForEveryone = { viewModel.deleteForEveryone(it) },
                            onImageClick = { b64 -> fullScreenImageB64 = b64 }
                        )
                    }
                }

                ChatComposer(
                    replyingTo = replyingTo,
                    onCancelReply = { viewModel.setReplyingTo(null) },
                    onSendText = { viewModel.sendText(it) },
                    onPickPhoto = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickP2pFile = {
                        p2pFileLauncher.launch(arrayOf("*/*"))
                    },
                    onTypingChanged = { viewModel.setTyping(it) },
                    onRecordingStateChanged = { viewModel.setRecordingAudio(it) },
                    onEnsureAudioPermission = {
                        val ok = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!ok) {
                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        ok
                    },
                    voiceRecorder = voiceRecorder,
                    onVoiceNoteReady = { b64, dur ->
                        viewModel.sendVoiceNote(b64, dur)
                    }
                )
            }
        }
    }

    // Disappearing Messages Timer Dialog
    if (showTimerDialog) {
        val options = listOf(
            0L to "Off",
            60L to "1 minute",
            3600L to "1 hour",
            86400L to "24 hours"
        )
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("Disappearing Messages") },
            text = {
                Column {
                    Text(
                        text = "New messages will automatically delete from both devices and Firestore after the timer expires.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    options.forEach { (sec, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = disappearingSeconds == sec,
                                onClick = {
                                    viewModel.setDisappearingTimer(sec)
                                    showTimerDialog = false
                                }
                            )
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimerDialog = false }) { Text("Close") }
            }
        )
    }

    // Full-Screen Zoomable Photo Viewer with Gallery Save
    fullScreenImageB64?.let { b64 ->
        FullScreenZoomablePhotoDialog(
            base64 = b64,
            onDismiss = { fullScreenImageB64 = null },
            onSaveToGallery = {
                scope.launch {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    val res = MediaHelper.saveImageToGallery(
                        context = context,
                        imageBytes = bytes,
                        fileName = "Duet_${System.currentTimeMillis()}.jpg"
                    )
                    val msg = if (res.isSuccess) "Saved to Gallery (Pictures/Duet)" else "Save failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showUpdateDialog) {
        GitHubUpdateDialog(
            updateState = updateState,
            onDismiss = { showUpdateDialog = false },
            onRetryCheck = { viewModel.checkForUpdates(manual = true) },
            onDownloadRelease = { release ->
                val targetUrl = release.apkDownloadUrl ?: release.releasePageUrl
                if (!GitHubUpdateClient.openUrlInBrowser(context, targetUrl)) {
                    Toast.makeText(context, "No browser found to open $targetUrl", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenReleasePage = { release ->
                GitHubUpdateClient.openUrlInBrowser(context, release.releasePageUrl)
            }
        )
    }
}

@Composable
private fun GitHubUpdateDialog(
    updateState: UpdateCheckState,
    onDismiss: () -> Unit,
    onRetryCheck: () -> Unit,
    onDownloadRelease: (AppReleaseInfo) -> Unit,
    onOpenReleasePage: (AppReleaseInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = WaEmerald)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Duet App Update")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Installed version: v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium
                )
                when (updateState) {
                    is UpdateCheckState.Checking -> {
                        Text("Checking GitHub Releases for a newer APK...")
                    }
                    is UpdateCheckState.Available -> {
                        val rel = updateState.release
                        val sizeText = if (rel.apkSizeBytes > 0L) {
                            " (${String.format("%.1f", rel.apkSizeBytes / (1024f * 1024f))} MB)"
                        } else ""
                        Text(
                            text = "New version ready: ${rel.tagName}$sizeText",
                            fontWeight = FontWeight.Bold,
                            color = WaEmerald
                        )
                        Text(
                            text = rel.releaseNotes.take(400),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is UpdateCheckState.UpToDate -> {
                        Text(
                            text = "You are on the latest version (${updateState.release.tagName}).",
                            color = WaEmerald
                        )
                    }
                    is UpdateCheckState.Error -> {
                        Text(
                            text = updateState.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is UpdateCheckState.Idle -> {
                        Text("Tap Check Now to query your GitHub Releases.")
                    }
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is UpdateCheckState.Available -> {
                    Button(
                        onClick = {
                            onDownloadRelease(updateState.release)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaEmerald)
                    ) {
                        Text(if (updateState.release.apkDownloadUrl != null) "Download APK" else "Open Release")
                    }
                }
                else -> {
                    TextButton(onClick = onRetryCheck) {
                        Text("Check Now")
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (updateState is UpdateCheckState.Available && updateState.release.apkDownloadUrl != null) {
                    TextButton(onClick = { onOpenReleasePage(updateState.release) }) {
                        Text("Release Page")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun FullScreenZoomablePhotoDialog(
    base64: String,
    onDismiss: () -> Unit,
    onSaveToGallery: () -> Unit
) {
    val bitmap: Bitmap? = remember(base64) { MediaHelper.decodeBase64ToBitmap(base64) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Zoomable Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                IconButton(onClick = onSaveToGallery) {
                    Icon(Icons.Default.Download, contentDescription = "Save to Gallery", tint = Color.White)
                }
            }
        }
    }
}
