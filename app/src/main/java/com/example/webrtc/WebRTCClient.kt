package com.example.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class P2pTransferProgress(
    val fileName: String,
    val progress: Float,
    val isSending: Boolean,
    val completedFilePath: String? = null
)

class WebRTCClient(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eglBase: EglBase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EglBase.create()
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var ringtone: Ringtone? = null
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _connectionState = MutableStateFlow("IDLE")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _p2pProgress = MutableStateFlow<P2pTransferProgress?>(null)
    val p2pProgress: StateFlow<P2pTransferProgress?> = _p2pProgress.asStateFlow()

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false

    // P2P File Receive Buffers
    private var incomingFileName: String? = null
    private var incomingFileExpectedBytes: Long = 0L
    private var incomingFileBuffer: ByteArrayOutputStream? = null

    private var onLocalIceCandidateCallback: ((String) -> Unit)? = null
    private var onP2pFileReceivedCallback: ((String, File) -> Unit)? = null

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initSurfaceView(renderer: SurfaceViewRenderer, mirror: Boolean) {
        try {
            renderer.init(eglBase.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(mirror)
        } catch (_: Exception) {
        }
    }

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        local?.let { r -> localVideoTrack?.addSink(r) }
        remote?.let { r -> remoteVideoTrack?.addSink(r) }
    }

    fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(appContext, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (_: Exception) {
        }
    }

    fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    fun startSession(
        isVideo: Boolean,
        isCaller: Boolean,
        onIceCandidate: (String) -> Unit,
        onP2pFileReceived: (String, File) -> Unit = { _, _ -> }
    ) {
        cleanupSession()
        ensureFactory()

        onLocalIceCandidateCallback = onIceCandidate
        onP2pFileReceivedCallback = onP2pFileReceived
        _isCameraEnabled.value = isVideo
        _isMuted.value = false
        setSpeakerphone(isVideo)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                _connectionState.value = state?.name ?: "UNKNOWN"
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val json = JSONObject().apply {
                    put("sdpMid", candidate.sdpMid ?: "")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("sdp", candidate.sdp)
                }.toString()
                onLocalIceCandidateCallback?.invoke(json)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                channel?.let { setupDataChannel(it) }
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { r -> track.addSink(r) }
                }
            }
        })

        peerConnection = pc

        // Audio Track
        val audioConstraints = MediaConstraints()
        localAudioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.let { pc?.addTrack(it, listOf("ARDAMS")) }

        // Video Track (if Video Call)
        if (isVideo) {
            setupLocalVideo(pc)
        }

        // DataChannel for Direct P2P File Sharing
        if (isCaller) {
            val dcInit = DataChannel.Init().apply { ordered = true }
            pc?.createDataChannel("duet-p2p-files", dcInit)?.let { setupDataChannel(it) }
        }
    }

    private fun setupLocalVideo(pc: PeerConnection?) {
        val factory = peerConnectionFactory ?: return
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return

        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        localVideoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, appContext, localVideoSource!!.capturerObserver)
        try {
            capturer.startCapture(1280, 720, 30)
        } catch (e: Exception) {
            Log.w(TAG, "Camera capture start failed: ${e.message}")
        }
        localVideoTrack = factory.createVideoTrack("ARDAMSv0", localVideoSource)
        localRenderer?.let { r -> localVideoTrack?.addSink(r) }
        localVideoTrack?.let { pc?.addTrack(it, listOf("ARDAMS")) }
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                if (!buffer.binary) {
                    val headerText = String(data, Charsets.UTF_8)
                    handleP2pControlMessage(headerText)
                } else {
                    handleP2pBinaryChunk(data)
                }
            }
        })
    }

    private fun handleP2pControlMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("cmd")) {
                "START" -> {
                    incomingFileName = json.optString("fileName", "duet_file_${System.currentTimeMillis()}")
                    incomingFileExpectedBytes = json.optLong("size", 0L)
                    incomingFileBuffer = ByteArrayOutputStream()
                    _p2pProgress.value = P2pTransferProgress(incomingFileName!!, 0f, isSending = false)
                }
                "END" -> {
                    val name = incomingFileName ?: "duet_file"
                    val bytes = incomingFileBuffer?.toByteArray() ?: ByteArray(0)
                    val outDir = File(appContext.cacheDir, "p2p_received").apply { mkdirs() }
                    val outFile = File(outDir, name)
                    FileOutputStream(outFile).use { it.write(bytes) }
                    _p2pProgress.value = P2pTransferProgress(name, 1f, isSending = false, completedFilePath = outFile.absolutePath)
                    onP2pFileReceivedCallback?.invoke(name, outFile)
                    incomingFileBuffer = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P control parse error: ${e.message}")
        }
    }

    private fun handleP2pBinaryChunk(chunk: ByteArray) {
        val buf = incomingFileBuffer ?: return
        buf.write(chunk)
        val total = incomingFileExpectedBytes.coerceAtLeast(1L)
        val frac = (buf.size().toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
        _p2pProgress.value = P2pTransferProgress(incomingFileName ?: "File", frac, isSending = false)
    }

    fun sendFileOverDataChannel(uri: Uri) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "DataChannel not open for P2P transfer")
            return
        }
        scope.launch {
            try {
                val resolver = appContext.contentResolver
                var fileName = "duet_file_${System.currentTimeMillis()}"
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(idx) ?: fileName
                    }
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val startJson = JSONObject().apply {
                    put("cmd", "START")
                    put("fileName", fileName)
                    put("size", bytes.size.toLong())
                }.toString()
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(startJson.toByteArray(Charsets.UTF_8)), false))

                val chunkSize = 16 * 1024
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + chunkSize, bytes.size)
                    val slice = bytes.copyOfRange(offset, end)
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(slice), true))
                    offset = end
                    _p2pProgress.value = P2pTransferProgress(
                        fileName = fileName,
                        progress = (offset.toFloat() / bytes.size.coerceAtLeast(1)).coerceIn(0f, 1f),
                        isSending = true
                    )
                }

                val endJson = JSONObject().apply { put("cmd", "END") }.toString()
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(endJson.toByteArray(Charsets.UTF_8)), false))
                _p2pProgress.value = P2pTransferProgress(fileName, 1f, isSending = true)
            } catch (e: Exception) {
                Log.w(TAG, "P2P file send failed: ${e.message}")
            }
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                onOfferCreated(desc.description)
            }
        }, constraints)
    }

    fun acceptOfferAndCreateAnswer(offerSdp: String, onAnswerCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIceCandidates()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc ?: return
                        pc.setLocalDescription(SimpleSdpObserver(), desc)
                        onAnswerCreated(desc.description)
                    }
                }, constraints)
            }
        }, remoteDesc)
    }

    fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIceCandidates()
            }
        }, remoteDesc)
    }

    fun addRemoteIceCandidate(candidateJson: String) {
        try {
            val obj = JSONObject(candidateJson)
            val candidate = IceCandidate(
                obj.optString("sdpMid"),
                obj.optInt("sdpMLineIndex"),
                obj.optString("sdp")
            )
            if (hasRemoteDescription) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                pendingIceCandidates.add(candidate)
            }
        } catch (_: Exception) {
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    fun toggleMute(): Boolean {
        val next = !_isMuted.value
        _isMuted.value = next
        localAudioTrack?.setEnabled(!next)
        return next
    }

    fun toggleCamera(): Boolean {
        val next = !_isCameraEnabled.value
        _isCameraEnabled.value = next
        localVideoTrack?.setEnabled(next)
        return next
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun setSpeakerphone(enabled: Boolean) {
        _isSpeakerOn.value = enabled
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) audioManager.setCommunicationDevice(speaker)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
        } catch (_: Exception) {
        }
    }

    fun cleanupSession() {
        stopRingtone()
        hasRemoteDescription = false
        pendingIceCandidates.clear()
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (_: Exception) {
        }
        videoCapturer = null
        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack = null
        _connectionState.value = "IDLE"
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.w(TAG, "SDP set failure: $error")
        }
    }

    companion object {
        private const val TAG = "WebRTCClient"
    }
}
