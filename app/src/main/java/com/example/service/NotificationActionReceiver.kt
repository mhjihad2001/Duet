package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.R
import com.example.data.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.remote.FirebaseRepository
import com.example.data.remote.PushRelayClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val roomId = intent.getStringExtra(NotificationHelper.EXTRA_ROOM_ID).orEmpty()
        val messageId = intent.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID).orEmpty()
        val callId = intent.getStringExtra(NotificationHelper.EXTRA_CALL_ID).orEmpty()
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, -1)

        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        if (uid == null || roomId.isBlank()) {
            pendingResult.finish()
            return
        }

        val cryptoManager = CryptoManager(context)
        val dao = AppDatabase.getInstance(context).messageDao()
        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
        val repo = FirebaseRepository(db, auth, cryptoManager, dao, PushRelayClient(cryptoManager))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    NotificationHelper.ACTION_DIRECT_REPLY -> {
                        val replyText = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (replyText.isNotEmpty()) {
                            val partnerUid = cryptoManager.getPartnerUid()
                            val members = listOfNotNull(uid, partnerUid).distinct()
                            repo.sendEncryptedMessage(
                                roomId = roomId,
                                memberUids = members,
                                messageType = "TEXT",
                                plaintext = replyText,
                                disappearingSeconds = cryptoManager.getDisappearingSeconds(),
                                senderName = auth.currentUser?.displayName ?: "Partner"
                            )
                            if (messageId.isNotBlank()) {
                                repo.updateMessageStatus(roomId, messageId, "READ")
                            }
                        }
                    }

                    NotificationHelper.ACTION_MARK_READ -> {
                        if (messageId.isNotBlank()) {
                            repo.updateMessageStatus(roomId, messageId, "READ")
                        }
                    }

                    NotificationHelper.ACTION_DECLINE_CALL -> {
                        if (callId.isNotBlank()) {
                            repo.updateCallStatus(roomId, callId, "DECLINED")
                        }
                    }
                }
                if (notificationId > 0) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
