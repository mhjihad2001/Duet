package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CallSignalDto
import com.example.ui.theme.WaCallRed
import com.example.ui.theme.WaDarkBackground
import com.example.ui.theme.WaDarkSurface
import com.example.ui.theme.WaDarkTextPrimary
import com.example.ui.theme.WaDarkTextSecondary
import com.example.ui.theme.WaEmerald
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

@Composable
fun CallScreen(
    call: CallSignalDto,
    viewModel: ChatViewModel,
    onCallClosed: () -> Unit
) {
    val webRtc = viewModel.webRTCClient
    val isMuted by webRtc.isMuted.collectAsStateWithLifecycle()
    val isCameraOn by webRtc.isCameraEnabled.collectAsStateWithLifecycle()
    val isSpeakerOn by webRtc.isSpeakerOn.collectAsStateWithLifecycle()
    val iceState by webRtc.connectionState.collectAsStateWithLifecycle()
    val partnerPresence by viewModel.partnerPresence.collectAsStateWithLifecycle()

    val isIncomingRinging = call.status == "RINGING" && call.callerUid != viewModel.currentUserId
    val isVideo = call.callType == "VIDEO"
    val partnerName = partnerPresence?.displayName?.ifBlank { "Partner" } ?: "Partner"

    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(call.status, call.encryptedAnswer) {
        if (call.status == "ACTIVE" && !call.encryptedAnswer.isNullOrBlank()) {
            viewModel.applyRemoteAnswerIfNeeded(call)
        }
        if (call.status == "ENDED" || call.status == "DECLINED") {
            webRtc.cleanupSession()
            onCallClosed()
        }
    }

    LaunchedEffect(isIncomingRinging) {
        if (isIncomingRinging) {
            webRtc.startRingtone()
        } else {
            webRtc.stopRingtone()
        }
    }

    LaunchedEffect(call.status) {
        if (call.status == "ACTIVE") {
            while (true) {
                delay(1000L)
                elapsedSeconds += 1
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webRtc.stopRingtone()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1D24), WaDarkBackground, Color(0xFF071412))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Video Renderers for HD Video Calls
        if (isVideo && !isIncomingRinging) {
            var localViewRef: SurfaceViewRenderer? = remember { null }
            var remoteViewRef: SurfaceViewRenderer? = remember { null }

            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        webRtc.initSurfaceView(r, mirror = false)
                        remoteViewRef = r
                        webRtc.attachRenderers(localViewRef, remoteViewRef)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { r ->
                            webRtc.initSurfaceView(r, mirror = true)
                            localViewRef = r
                            webRtc.attachRenderers(localViewRef, remoteViewRef)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Top E2EE Call Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0x55000000),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = WaEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End-to-end encrypted",
                        style = MaterialTheme.typography.labelMedium,
                        color = WaDarkTextPrimary
                    )
                }
            }

            if (!isVideo || isIncomingRinging) {
                Spacer(modifier = Modifier.height(48.dp))
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(WaEmerald),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = partnerName.firstOrNull()?.uppercase() ?: "D",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = partnerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = WaDarkTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val statusLabel = when {
                    isIncomingRinging -> if (isVideo) "Incoming Duet Video Call..." else "Incoming Duet Audio Call..."
                    call.status == "RINGING" -> "Ringing..."
                    call.status == "ACTIVE" -> String.format(
                        Locale.getDefault(),
                        "%02d:%02d • %s",
                        elapsedSeconds / 60,
                        elapsedSeconds % 60,
                        iceState
                    )
                    else -> call.status
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = WaDarkTextSecondary
                )
            }
        }

        // Bottom Call Controls
        if (isIncomingRinging) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { viewModel.endOrDeclineCall(call) },
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(WaCallRed)
                            .testTag("decline_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Decline", color = WaDarkTextPrimary)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { viewModel.answerIncomingCall(call) },
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(WaEmerald)
                            .testTag("answer_call_button")
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Answer Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Answer", color = WaDarkTextPrimary)
                }
            }
        } else {
            Surface(
                color = WaDarkSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 28.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webRtc.setSpeakerphone(!isSpeakerOn) },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) WaEmerald else Color(0x33FFFFFF))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speaker", tint = Color.White)
                    }

                    if (isVideo) {
                        IconButton(
                            onClick = { webRtc.toggleCamera() },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(if (!isCameraOn) Color.White else Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle Camera",
                                tint = if (!isCameraOn) WaDarkBackground else Color.White
                            )
                        }
                        IconButton(
                            onClick = { webRtc.switchCamera() },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                        }
                    }

                    IconButton(
                        onClick = { webRtc.toggleMute() },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) Color.White else Color(0x33FFFFFF))
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute Mic",
                            tint = if (isMuted) WaDarkBackground else Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.endOrDeclineCall(call) },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(WaCallRed)
                            .testTag("end_call_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White)
                    }
                }
            }
        }
    }
}
