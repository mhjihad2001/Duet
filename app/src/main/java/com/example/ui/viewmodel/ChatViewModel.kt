package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.crypto.CryptoManager
import com.example.data.local.MessageEntity
import com.example.data.model.CallSignalDto
import com.example.data.model.CoupleRoom
import com.example.data.model.PresenceStatus
import com.example.data.remote.AppReleaseInfo
import com.example.data.remote.FirebaseRepository
import com.example.data.remote.GitHubUpdateClient
import com.example.media.MediaHelper
import com.example.ui.theme.ChatWallpaper
import com.example.webrtc.P2pTransferProgress
import com.example.webrtc.WebRTCClient
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val release: AppReleaseInfo) : UpdateCheckState
    data class UpToDate(val release: AppReleaseInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

class ChatViewModel(
    private val repository: FirebaseRepository,
    private val cryptoManager: CryptoManager,
    val webRTCClient: WebRTCClient,
    val currentUserId: String,
    val currentUserName: String
) : ViewModel() {

    private val _vaultConfigured = MutableStateFlow(cryptoManager.isVaultConfigured())
    val vaultConfigured: StateFlow<Boolean> = _vaultConfigured.asStateFlow()

    private val _activeRoomId = MutableStateFlow(cryptoManager.getRoomId().orEmpty())
    val activeRoomId: StateFlow<String> = _activeRoomId.asStateFlow()

    private val _darkTheme = MutableStateFlow(cryptoManager.isDarkTheme())
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _wallpaper = MutableStateFlow(ChatWallpaper.fromId(cryptoManager.getWallpaperId()))
    val wallpaper: StateFlow<ChatWallpaper> = _wallpaper.asStateFlow()

    private val _disappearingSeconds = MutableStateFlow(cryptoManager.getDisappearingSeconds())
    val disappearingSeconds: StateFlow<Long> = _disappearingSeconds.asStateFlow()

    private val _replyingTo = MutableStateFlow<MessageEntity?>(null)
    val replyingTo: StateFlow<MessageEntity?> = _replyingTo.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val updateClient = GitHubUpdateClient()
    private val _githubRepo = MutableStateFlow(
        cryptoManager.getGitHubRepo().ifBlank { GitHubUpdateClient.defaultConfiguredRepo() }
    )
    val githubRepo: StateFlow<String> = _githubRepo.asStateFlow()

    private val _autoCheckUpdates = MutableStateFlow(cryptoManager.isAutoCheckUpdatesEnabled())
    val autoCheckUpdates: StateFlow<Boolean> = _autoCheckUpdates.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    private val _updateBannerDismissed = MutableStateFlow(false)
    val updateBannerDismissed: StateFlow<Boolean> = _updateBannerDismissed.asStateFlow()

    val p2pProgress: StateFlow<P2pTransferProgress?> = webRTCClient.p2pProgress

    val roomState: StateFlow<UiState<CoupleRoom?>> = repository.observeMyRoom(currentUserId)
        .map<CoupleRoom?, UiState<CoupleRoom?>> { room ->
            if (room != null) {
                if (cryptoManager.getRoomId() != room.id) {
                    cryptoManager.setRoomId(room.id)
                    _activeRoomId.value = room.id
                }
                val partner = room.memberUids.firstOrNull { it != currentUserId }
                if (!partner.isNullOrBlank()) {
                    cryptoManager.setPartnerUid(partner)
                }
                _disappearingSeconds.value = room.disappearingSeconds
                cryptoManager.setDisappearingSeconds(room.disappearingSeconds)
            }
            UiState.Success(room)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing room", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load room"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    @Suppress("OPT_IN_USAGE")
    val messages: StateFlow<List<MessageEntity>> = combine(_activeRoomId, _searchQuery) { roomId, query ->
        roomId to query
    }.flatMapLatest { (roomId, query) ->
        if (roomId.isBlank()) flowOf(emptyList())
        else if (query.isBlank()) repository.observeLocalMessages(roomId)
        else repository.searchLocalMessages(roomId, query)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    @Suppress("OPT_IN_USAGE")
    val starredMessages: StateFlow<List<MessageEntity>> = _activeRoomId.flatMapLatest { roomId ->
        if (roomId.isBlank()) flowOf(emptyList())
        else repository.observeStarredMessages(roomId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    @Suppress("OPT_IN_USAGE")
    val partnerPresence: StateFlow<PresenceStatus?> = _activeRoomId.flatMapLatest { roomId ->
        if (roomId.isBlank()) flowOf(null)
        else repository.observePartnerPresence(roomId, currentUserId)
            .catch { error ->
                if (error is CancellationException) throw error
                emit(null)
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null
    )

    @Suppress("OPT_IN_USAGE")
    val activeCall: StateFlow<CallSignalDto?> = _activeRoomId.flatMapLatest { roomId ->
        if (roomId.isBlank()) flowOf(null)
        else repository.observeCalls(roomId, currentUserId)
            .map { calls ->
                calls.filter { it.status == "RINGING" || it.status == "ACTIVE" }
                    .maxByOrNull { it.createdAt?.toDate()?.time ?: 0L }
            }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(null)
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null
    )

    private var syncJob: Job? = null
    private var iceObserverJob: Job? = null
    private var answeredCallIds = mutableSetOf<String>()
    private var appliedAnswerCallIds = mutableSetOf<String>()
    private var processedCandidateIds = mutableSetOf<String>()

    init {
        syncFcmToken()
        startRoomSyncLoop()
        startExpiryCleanupLoop()
        if (_autoCheckUpdates.value && _githubRepo.value.isNotBlank()) {
            checkForUpdates(manual = false)
        }
    }

    private fun syncFcmToken() {
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                cryptoManager.setLocalFcmToken(token)
                repository.upsertUserProfile(
                    displayName = currentUserName,
                    fcmToken = token,
                    roomId = cryptoManager.getRoomId(),
                    partnerUid = cryptoManager.getPartnerUid()
                )
            } catch (e: Exception) {
                Log.w(TAG, "FCM token registration skipped: ${e.message}")
            }
        }
    }

    private fun startRoomSyncLoop() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _activeRoomId.collectLatest { roomId ->
                if (roomId.isNotBlank() && cryptoManager.hasAesKey()) {
                    try {
                        repository.syncRemoteMessages(roomId, currentUserId).collect { }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote sync interrupted: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startExpiryCleanupLoop() {
        viewModelScope.launch {
            while (true) {
                val roomId = _activeRoomId.value
                if (roomId.isNotBlank()) {
                    repository.purgeExpiredMessages(roomId)
                }
                delay(10_000L)
            }
        }
    }

    fun setupNewVault(passphrase: String, pin: String, biometricEnabled: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            val res = repository.createCoupleRoom(currentUserName)
            res.onSuccess { room ->
                cryptoManager.deriveAndSavePassphrase(passphrase, room.id)
                cryptoManager.setPin(pin)
                cryptoManager.setBiometricEnabled(biometricEnabled)
                cryptoManager.setRoomId(room.id)
                _activeRoomId.value = room.id
                _vaultConfigured.value = true
                startRoomSyncLoop()
                onDone()
            }.onFailure { err ->
                _errorBanner.value = err.message ?: "Failed to create couple vault"
            }
            _isBusy.value = false
        }
    }

    fun joinExistingVault(roomCode: String, passphrase: String, pin: String, biometricEnabled: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            val res = repository.joinCoupleRoom(roomCode, currentUserName)
            res.onSuccess { room ->
                cryptoManager.deriveAndSavePassphrase(passphrase, room.id)
                cryptoManager.setPin(pin)
                cryptoManager.setBiometricEnabled(biometricEnabled)
                cryptoManager.setRoomId(room.id)
                _activeRoomId.value = room.id
                _vaultConfigured.value = true
                startRoomSyncLoop()
                onDone()
            }.onFailure { err ->
                _errorBanner.value = err.message ?: "Failed to join vault"
            }
            _isBusy.value = false
        }
    }

    private fun currentMemberUids(): List<String> {
        val fromState = (roomState.value as? UiState.Success)?.data?.memberUids
        if (!fromState.isNullOrEmpty()) return fromState
        return listOfNotNull(currentUserId, cryptoManager.getPartnerUid()).distinct()
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val roomId = _activeRoomId.value.ifBlank { return }
        val reply = _replyingTo.value
        _replyingTo.value = null

        viewModelScope.launch {
            repository.sendEncryptedMessage(
                roomId = roomId,
                memberUids = currentMemberUids(),
                messageType = "TEXT",
                plaintext = clean,
                replyToMessageId = reply?.id,
                replyPreviewText = reply?.decryptedText,
                disappearingSeconds = _disappearingSeconds.value,
                senderName = currentUserName
            ).onFailure {
                _errorBanner.value = it.message ?: "Failed to send message"
            }
            setTyping(false)
        }
    }

    fun sendPhoto(context: Context, uri: Uri) {
        val roomId = _activeRoomId.value.ifBlank { return }
        val reply = _replyingTo.value
        _replyingTo.value = null

        viewModelScope.launch {
            _isBusy.value = true
            val compressed = MediaHelper.compressImageToBase64(context, uri)
            compressed.onSuccess { b64 ->
                repository.sendEncryptedMessage(
                    roomId = roomId,
                    memberUids = currentMemberUids(),
                    messageType = "IMAGE",
                    plaintext = "Photo",
                    mediaBase64 = b64,
                    replyToMessageId = reply?.id,
                    replyPreviewText = reply?.decryptedText,
                    disappearingSeconds = _disappearingSeconds.value,
                    senderName = currentUserName
                ).onFailure {
                    _errorBanner.value = it.message ?: "Photo upload failed"
                }
            }.onFailure {
                _errorBanner.value = it.message ?: "Image compression failed"
            }
            _isBusy.value = false
        }
    }

    fun sendVoiceNote(base64Audio: String, durationMs: Long) {
        val roomId = _activeRoomId.value.ifBlank { return }
        val reply = _replyingTo.value
        _replyingTo.value = null

        viewModelScope.launch {
            repository.sendEncryptedMessage(
                roomId = roomId,
                memberUids = currentMemberUids(),
                messageType = "VOICE",
                plaintext = "Voice message (${(durationMs / 1000).coerceAtLeast(1)}s)",
                mediaBase64 = base64Audio,
                voiceDurationMs = durationMs,
                replyToMessageId = reply?.id,
                replyPreviewText = reply?.decryptedText,
                disappearingSeconds = _disappearingSeconds.value,
                senderName = currentUserName
            )
        }
    }

    fun sendFileP2p(uri: Uri) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            webRTCClient.sendFileOverDataChannel(uri)
            repository.sendEncryptedMessage(
                roomId = roomId,
                memberUids = currentMemberUids(),
                messageType = "FILE_P2P",
                plaintext = "Direct P2P file transfer initiated",
                disappearingSeconds = _disappearingSeconds.value,
                senderName = currentUserName
            )
        }
    }

    fun markVisibleMessagesRead(list: List<MessageEntity>) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            repository.markMessagesAsRead(roomId, currentUserId, list)
        }
    }

    fun setTyping(typing: Boolean) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            repository.updatePresence(
                roomId = roomId,
                memberUids = currentMemberUids(),
                displayName = currentUserName,
                online = true,
                typing = typing,
                recordingAudio = false
            )
        }
    }

    fun setRecordingAudio(recording: Boolean) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            repository.updatePresence(
                roomId = roomId,
                memberUids = currentMemberUids(),
                displayName = currentUserName,
                online = true,
                typing = false,
                recordingAudio = recording
            )
        }
    }

    fun setReplyingTo(message: MessageEntity?) {
        _replyingTo.value = message
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleReaction(message: MessageEntity, emoji: String) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            repository.toggleReaction(roomId, message, currentUserId, emoji)
        }
    }

    fun toggleStarred(message: MessageEntity) {
        viewModelScope.launch {
            repository.toggleStarred(message.id, !message.starred)
        }
    }

    fun deleteForEveryone(message: MessageEntity) {
        val roomId = _activeRoomId.value.ifBlank { return }
        viewModelScope.launch {
            repository.deleteForEveryone(roomId, message)
        }
    }

    fun setDisappearingTimer(seconds: Long) {
        val roomId = _activeRoomId.value.ifBlank { return }
        _disappearingSeconds.value = seconds
        viewModelScope.launch {
            repository.updateDisappearingTimer(roomId, seconds)
        }
    }

    // --- WebRTC Audio / Video Calls ---

    fun startOutgoingCall(isVideo: Boolean, onCallStarted: (String) -> Unit) {
        val roomId = _activeRoomId.value.ifBlank { return }
        val members = currentMemberUids()
        val callType = if (isVideo) "VIDEO" else "AUDIO"

        val pendingIce = mutableListOf<String>()
        var createdCallId: String? = null

        webRTCClient.startSession(
            isVideo = isVideo,
            isCaller = true,
            onIceCandidate = { candJson ->
                val cid = createdCallId
                if (cid != null) {
                    viewModelScope.launch {
                        repository.sendIceCandidate(roomId, cid, members, candJson)
                    }
                } else {
                    pendingIce.add(candJson)
                }
            },
            onP2pFileReceived = { name, file ->
                onP2pFileSaved(roomId, members, name, file)
            }
        )

        webRTCClient.createOffer { offerSdp ->
            viewModelScope.launch {
                val res = repository.createCallOffer(roomId, members, callType, offerSdp, currentUserName)
                res.onSuccess { callId ->
                    createdCallId = callId
                    pendingIce.forEach { cand ->
                        repository.sendIceCandidate(roomId, callId, members, cand)
                    }
                    pendingIce.clear()
                    observeRemoteIce(roomId, callId)
                    onCallStarted(callId)
                }.onFailure {
                    webRTCClient.cleanupSession()
                    _errorBanner.value = it.message ?: "Call failed"
                }
            }
        }
    }

    fun answerIncomingCall(call: CallSignalDto) {
        val roomId = _activeRoomId.value.ifBlank { return }
        if (call.id in answeredCallIds) return
        answeredCallIds.add(call.id)
        webRTCClient.stopRingtone()

        val members = currentMemberUids()
        val isVideo = call.callType == "VIDEO"
        val offerSdp = call.encryptedOffer?.let {
            try {
                cryptoManager.decryptString(it)
            } catch (_: Exception) {
                null
            }
        } ?: return

        webRTCClient.startSession(
            isVideo = isVideo,
            isCaller = false,
            onIceCandidate = { candJson ->
                viewModelScope.launch {
                    repository.sendIceCandidate(roomId, call.id, members, candJson)
                }
            },
            onP2pFileReceived = { name, file ->
                onP2pFileSaved(roomId, members, name, file)
            }
        )

        observeRemoteIce(roomId, call.id)

        webRTCClient.acceptOfferAndCreateAnswer(offerSdp) { answerSdp ->
            viewModelScope.launch {
                repository.answerCall(roomId, call.id, answerSdp)
            }
        }
    }

    fun applyRemoteAnswerIfNeeded(call: CallSignalDto) {
        if (call.callerUid != currentUserId) return
        if (call.id in appliedAnswerCallIds) return
        val encAnswer = call.encryptedAnswer ?: return
        appliedAnswerCallIds.add(call.id)
        try {
            val sdp = cryptoManager.decryptString(encAnswer)
            webRTCClient.setRemoteAnswer(sdp)
        } catch (_: Exception) {
        }
    }

    fun endOrDeclineCall(call: CallSignalDto) {
        val roomId = _activeRoomId.value.ifBlank { return }
        val newStatus = if (call.status == "RINGING" && call.callerUid != currentUserId) "DECLINED" else "ENDED"
        webRTCClient.cleanupSession()
        iceObserverJob?.cancel()
        viewModelScope.launch {
            repository.updateCallStatus(roomId, call.id, newStatus)
        }
    }

    private fun observeRemoteIce(roomId: String, callId: String) {
        iceObserverJob?.cancel()
        processedCandidateIds.clear()
        iceObserverJob = viewModelScope.launch {
            repository.observeIceCandidates(roomId, callId, currentUserId).collect { list ->
                for (item in list) {
                    if (item.senderUid != currentUserId && item.id !in processedCandidateIds) {
                        processedCandidateIds.add(item.id)
                        try {
                            val candJson = cryptoManager.decryptString(item.encryptedCandidate)
                            webRTCClient.addRemoteIceCandidate(candJson)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun onP2pFileSaved(roomId: String, members: List<String>, name: String, file: File) {
        viewModelScope.launch {
            repository.sendEncryptedMessage(
                roomId = roomId,
                memberUids = members,
                messageType = "FILE_P2P",
                plaintext = "Received P2P file: $name (${file.length() / 1024} KB)",
                disappearingSeconds = _disappearingSeconds.value,
                senderName = currentUserName
            )
        }
    }

    // --- Settings & Theme ---

    fun toggleTheme() {
        val next = !_darkTheme.value
        _darkTheme.value = next
        cryptoManager.setDarkTheme(next)
    }

    fun setWallpaper(wp: ChatWallpaper) {
        _wallpaper.value = wp
        cryptoManager.setWallpaperId(wp.id)
    }

    fun updateWebhookUrl(url: String) {
        cryptoManager.setPushWebhookUrl(url)
    }

    fun updatePin(newPin: String) {
        cryptoManager.setPin(newPin)
    }

    fun updateBiometricEnabled(enabled: Boolean) {
        cryptoManager.setBiometricEnabled(enabled)
    }

    fun updatePassphrase(newPassphrase: String) {
        val roomId = _activeRoomId.value.ifBlank { return }
        cryptoManager.deriveAndSavePassphrase(newPassphrase, roomId)
    }

    fun dismissError() {
        _errorBanner.value = null
    }

    fun saveGitHubRepo(rawInput: String): Boolean {
        val normalized = GitHubUpdateClient.normalizeRepoSlug(rawInput)
        return if (rawInput.isBlank()) {
            _githubRepo.value = ""
            cryptoManager.setGitHubRepo("")
            _updateState.value = UpdateCheckState.Idle
            true
        } else if (normalized != null) {
            _githubRepo.value = normalized
            cryptoManager.setGitHubRepo(normalized)
            true
        } else {
            false
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        _autoCheckUpdates.value = enabled
        cryptoManager.setAutoCheckUpdatesEnabled(enabled)
    }

    fun checkForUpdates(manual: Boolean = true) {
        val repo = _githubRepo.value.trim()
        if (repo.isBlank()) {
            if (manual) {
                _updateState.value = UpdateCheckState.Error(
                    "Set your GitHub repository (owner/repo) in Settings -> App Updates first."
                )
            }
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateCheckState.Checking
            val result = updateClient.fetchLatestRelease(repo)
            result.onSuccess { release ->
                if (release.isNewerThanCurrent) {
                    _updateBannerDismissed.value = false
                    _updateState.value = UpdateCheckState.Available(release)
                } else {
                    _updateState.value = UpdateCheckState.UpToDate(release)
                }
            }.onFailure { err ->
                if (manual) {
                    _updateState.value = UpdateCheckState.Error(
                        err.message ?: "Unable to check GitHub Releases."
                    )
                } else {
                    _updateState.value = UpdateCheckState.Idle
                }
            }
        }
    }

    fun dismissUpdateBanner() {
        _updateBannerDismissed.value = true
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
