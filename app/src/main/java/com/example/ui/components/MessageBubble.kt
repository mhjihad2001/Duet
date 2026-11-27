package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.MessageEntity
import com.example.data.remote.FirebaseRepository
import com.example.media.MediaHelper
import com.example.media.VoiceNotePlayer
import com.example.ui.theme.LocalDuetChatColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    isMine: Boolean,
    voicePlayer: VoiceNotePlayer,
    onSwipeReply: (MessageEntity) -> Unit,
    onReactionSelected: (MessageEntity, String) -> Unit,
    onToggleStar: (MessageEntity) -> Unit,
    onDeleteForEveryone: (MessageEntity) -> Unit,
    onImageClick: (String) -> Unit
) {
    val chatColors = LocalDuetChatColors.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = dragOffsetX, label = "swipeReply")
    var showMenu by remember { mutableStateOf(false) }

    val timeStr = remember(message.createdAtMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.createdAtMillis))
    }

    val bubbleColor = if (isMine) chatColors.outgoingBubble else chatColors.incomingBubble
    val textColor = if (isMine) chatColors.outgoingText else chatColors.incomingText
    val reactionsMap = remember(message.reactionJson) {
        FirebaseRepository.parseReactions(message.reactionJson)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffsetX > 85f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSwipeReply(message)
                        }
                        dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        val next = (dragOffsetX + dragAmount).coerceIn(0f, 120f)
                        dragOffsetX = next
                    }
                )
            }
    ) {
        if (animatedOffset > 24f) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Swipe to Reply",
                tint = chatColors.accentEmerald,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(22.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) },
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            Box {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = if (isMine) 16.dp else 4.dp,
                        topEnd = if (isMine) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    ),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(min = 84.dp, max = 300.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            }
                        )
                        .testTag("message_bubble_${message.id}")
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        // Quoted Reply Preview
                        if (!message.replyPreviewText.isNullOrBlank() && !message.deletedForEveryone) {
                            Surface(
                                color = chatColors.replyBarBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(28.dp)
                                            .background(chatColors.accentEmerald, RoundedCornerShape(2.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = message.replyPreviewText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = chatColors.timestampText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (message.deletedForEveryone) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Block,
                                    contentDescription = null,
                                    tint = chatColors.timestampText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isMine) "You deleted this message" else "This message was deleted",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = chatColors.timestampText
                                )
                            }
                        } else {
                            when (message.messageType) {
                                "IMAGE" -> {
                                    val bitmap = remember(message.mediaBase64) {
                                        message.mediaBase64?.let { MediaHelper.decodeBase64ToBitmap(it) }
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Encrypted Photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(190.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    message.mediaBase64?.let { onImageClick(it) }
                                                }
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }

                                "VOICE" -> {
                                    VoiceNoteBubblePlayer(
                                        message = message,
                                        voicePlayer = voicePlayer,
                                        textColor = textColor,
                                        accentColor = chatColors.accentEmerald
                                    )
                                }

                                "FILE_P2P" -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = "P2P File",
                                            tint = chatColors.accentEmerald,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = message.decryptedText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor
                                        )
                                    }
                                }

                                else -> {
                                    Text(
                                        text = message.decryptedText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = textColor
                                    )
                                }
                            }
                        }

                        // Metadata Footer: Timer, Star, Timestamp, Read Ticks
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (message.expiresAtMillis > 0L) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = "Disappearing",
                                    tint = chatColors.timestampText,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            if (message.starred) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Starred",
                                    tint = chatColors.timestampText,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = timeStr,
                                fontSize = 11.sp,
                                color = chatColors.timestampText
                            )
                            if (isMine && !message.deletedForEveryone) {
                                val (tickIcon, tickTint) = when (message.status) {
                                    "READ" -> Icons.Default.DoneAll to chatColors.readTickBlue
                                    "DELIVERED" -> Icons.Default.DoneAll to chatColors.unreadTickGrey
                                    else -> Icons.Default.Done to chatColors.unreadTickGrey
                                }
                                Icon(
                                    imageVector = tickIcon,
                                    contentDescription = message.status,
                                    tint = tickTint,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                // Long-Press Reaction & Action Menu
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QUICK_REACTIONS.forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        showMenu = false
                                        onReactionSelected(message, emoji)
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = {
                            showMenu = false
                            onSwipeReply(message)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (message.starred) "Unstar" else "Star Message") },
                        onClick = {
                            showMenu = false
                            onToggleStar(message)
                        }
                    )
                    if (message.messageType == "TEXT" && !message.deletedForEveryone) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                showMenu = false
                                clipboard.setText(AnnotatedString(message.decryptedText))
                            }
                        )
                    }
                    if (isMine && !message.deletedForEveryone) {
                        DropdownMenuItem(
                            text = { Text("Delete for Everyone", color = Color(0xFFEF4444)) },
                            onClick = {
                                showMenu = false
                                onDeleteForEveryone(message)
                            }
                        )
                    }
                }
            }

            // Emoji Reaction Pill
            if (reactionsMap.isNotEmpty() && !message.deletedForEveryone) {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        reactionsMap.values.forEach { emoji ->
                            Text(text = emoji, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceNoteBubblePlayer(
    message: MessageEntity,
    voicePlayer: VoiceNotePlayer,
    textColor: Color,
    accentColor: Color
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            isPlaying = voicePlayer.isPlaying(message.id)
            progress = voicePlayer.progressFraction()
            delay(100L)
        }
    }

    val sec = (message.voiceDurationMs / 1000L).coerceAtLeast(1L)
    val durationText = String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(228.dp)
            .padding(vertical = 2.dp)
    ) {
        IconButton(
            onClick = {
                val b64 = message.mediaBase64 ?: return@IconButton
                val file = MediaHelper.saveVoiceCacheFile(context, message.id, b64) ?: return@IconButton
                isPlaying = voicePlayer.playOrToggle(
                    messageId = message.id,
                    audioFile = file,
                    speed = speed,
                    onProgress = { progress = it },
                    onComplete = {
                        isPlaying = false
                        progress = 0f
                    }
                )
            },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause Voice Note" else "Play Voice Note",
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress },
                color = accentColor,
                trackColor = Color(0x448696A0),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = durationText,
                fontSize = 11.sp,
                color = textColor.copy(alpha = 0.75f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = Color(0x33000000),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable {
                speed = when (speed) {
                    1f -> 1.5f
                    1.5f -> 2f
                    else -> 1f
                }
                if (isPlaying) {
                    voicePlayer.updateSpeed(speed)
                }
            }
        ) {
            val label = when (speed) {
                1.5f -> "1.5x"
                2f -> "2x"
                else -> "1x"
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}
