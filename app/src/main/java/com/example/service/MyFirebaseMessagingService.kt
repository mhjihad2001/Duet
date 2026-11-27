package com.example.service

import android.util.Log
import com.example.R
import com.example.data.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.local.MessageEntity
import com.example.data.remote.FirebaseRepository
import com.example.data.remote.PushRelayClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Handles high-priority Data-Only FCM payloads even when the app is swiped away or killed.
 * Decrypts incoming payloads locally using CryptoManager, persists to Room DB immediately,
 * marks messages as DELIVERED in Firestore, and raises rich native notifications.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val cryptoManager = CryptoManager(applicationContext)
        cryptoManager.setLocalFcmToken(token)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val roomId = cryptoManager.getRoomId()
        val db = FirebaseFirestore.getInstance(
            applicationContext.getString(R.string.firestore_database_id)
        )
        serviceScope.launch {
            try {
                db.collection("users").document(uid)
                    .update(
                        mapOf(
                            "fcmToken" to token,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                if (!roomId.isNullOrBlank()) {
                    db.collection("rooms").document(roomId)
                        .collection("presence").document(uid)
                        .update(
                            mapOf(
                                "fcmToken" to token,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FCM token sync deferred: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        if (data.isEmpty()) return

        NotificationHelper.createChannels(applicationContext)
        val cryptoManager = CryptoManager(applicationContext)
        if (!cryptoManager.hasAesKey()) return

        val type = data["type"] ?: "MESSAGE"
        val encryptedPayload = data["encryptedPayload"] ?: return

        try {
            val decrypted = JSONObject(cryptoManager.decryptString(encryptedPayload))
            when (type) {
                "MESSAGE" -> handleIncomingMessage(cryptoManager, decrypted)
                "CALL" -> handleIncomingCall(decrypted)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decrypt FCM payload: ${e.message}")
        }
    }

    private fun handleIncomingMessage(cryptoManager: CryptoManager, json: JSONObject) {
        val roomId = json.optString("roomId")
        val messageId = json.optString("messageId")
        val senderUid = json.optString("senderUid")
        val senderName = json.optString("senderName", cryptoManager.getPartnerName())
        val messageType = json.optString("messageType", "TEXT")
        val text = json.optString("text", "New encrypted message")
        val expiresAtMillis = json.optLong("expiresAtMillis", 0L)
        val createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis())

        if (roomId.isBlank() || messageId.isBlank()) return

        serviceScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).messageDao()
            val entity = MessageEntity(
                id = messageId,
                roomId = roomId,
                senderUid = senderUid,
                messageType = messageType,
                decryptedText = text,
                status = "DELIVERED",
                expiresAtMillis = expiresAtMillis,
                createdAtMillis = createdAtMillis
            )
            dao.upsertMessage(entity)

            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser != null) {
                val db = FirebaseFirestore.getInstance(
                    applicationContext.getString(R.string.firestore_database_id)
                )
                val repo = FirebaseRepository(
                    db = db,
                    auth = auth,
                    cryptoManager = cryptoManager,
                    messageDao = dao,
                    pushRelayClient = PushRelayClient(cryptoManager)
                )
                repo.updateMessageStatus(roomId, messageId, "DELIVERED")
            }

            NotificationHelper.showDecryptedMessageNotification(
                context = applicationContext,
                roomId = roomId,
                messageId = messageId,
                senderName = senderName,
                decryptedText = text
            )
        }
    }

    private fun handleIncomingCall(json: JSONObject) {
        val roomId = json.optString("roomId")
        val callId = json.optString("callId")
        val callerName = json.optString("callerName", "Partner")
        val callType = json.optString("callType", "AUDIO")
        if (roomId.isBlank() || callId.isBlank()) return

        NotificationHelper.showIncomingCallNotification(
            context = applicationContext,
            roomId = roomId,
            callId = callId,
            callerName = callerName,
            callType = callType
        )
    }

    companion object {
        private const val TAG = "MyFCMService"
    }
}
