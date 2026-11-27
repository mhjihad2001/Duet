package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageEntity
import com.example.media.VoiceNoteRecorder
import com.example.ui.theme.LocalDuetChatColors
import com.example.ui.theme.WaCallRed
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ChatComposer(
    replyingTo: MessageEntity?,
    onCancelReply: () -> Unit,
    onSendText: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickP2pFile: () -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onRecordingStateChanged: (Boolean) -> Unit,
    onEnsureAudioPermission: () -> Boolean,
    voiceRecorder: VoiceNoteRecorder,
    onVoiceNoteReady: (base64: String, durationMs: Long) -> Unit
) {
    val chatColors = LocalDuetChatColors.current
    val haptic = LocalHapticFeedback.current
    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var amplitude by remember { mutableFloatStateOf(0.2f) }

    LaunchedEffect(text) {
        onTypingChanged(text.isNotBlank())
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (isRecording) {
                recordingSeconds = (System.currentTimeMillis() - start) / 1000L
                amplitude = voiceRecorder.currentAmplitudeNormalized()
                delay(120L)
            }
        } else {
            recordingSeconds = 0L
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        AnimatedVisibility(visible = replyingTo != null) {
            replyingTo?.let { reply ->
                Surface(
                    color = chatColors.composerSurface,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(32.dp)
                                .background(chatColors.accentEmerald, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to message",
                                style = MaterialTheme.typography.labelMedium,
                                color = chatColors.accentEmerald
                            )
                            Text(
                                text = reply.decryptedText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = chatColors.timestampText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onCancelReply, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Reply",
                                tint = chatColors.timestampText
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                color = chatColors.composerSurface,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.weight(1f)
            ) {
                if (isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(WaCallRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "Recording %d:%02d",
                                    recordingSeconds / 60,
                                    recordingSeconds % 60
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // Animated waveform bars
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0 until 8) {
                                val barH = ((amplitude * 22f) * (0.5f + ((i % 3) * 0.25f))).dp.coerceIn(4.dp, 22.dp)
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barH)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(chatColors.accentEmerald)
                                )
                            }
                        }
                        Text(
                            text = "Cancel",
                            color = WaCallRed,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        voiceRecorder.stopAndDiscard()
                                        isRecording = false
                                        onRecordingStateChanged(false)
                                    }
                                }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "Encrypted message",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = chatColors.timestampText
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(chatColors.accentEmerald),
                                maxLines = 5,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message_input")
                            )
                        }

                        IconButton(
                            onClick = onPickP2pFile,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("attach_p2p_file_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Send P2P File",
                                tint = chatColors.timestampText
                            )
                        }

                        IconButton(
                            onClick = onPickPhoto,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("attach_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Send Encrypted Photo",
                                tint = chatColors.timestampText
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            if (text.isNotBlank() && !isRecording) {
                Surface(
                    color = chatColors.accentEmerald,
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_message_button"),
                    onClick = {
                        val outgoing = text
                        text = ""
                        onSendText(outgoing)
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                // Tap or Hold Mic Button for Voice Notes
                Surface(
                    color = if (isRecording) WaCallRed else chatColors.accentEmerald,
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("voice_record_button"),
                    onClick = {
                        if (!isRecording) {
                            if (onEnsureAudioPermission()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (voiceRecorder.startRecording()) {
                                    isRecording = true
                                    onRecordingStateChanged(true)
                                }
                            }
                        } else {
                            isRecording = false
                            onRecordingStateChanged(false)
                            voiceRecorder.stopAndEncodeBase64()?.let { (b64, dur) ->
                                onVoiceNoteReady(b64, dur)
                            }
                        }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop and Send Voice Note" else "Record Voice Note",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
