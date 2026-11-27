package com.example

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.data.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.remote.FirebaseRepository
import com.example.data.remote.PushRelayClient
import com.example.service.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DuetApplication : Application(), DefaultLifecycleObserver {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var cryptoManager: CryptoManager
        private set
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        cryptoManager = CryptoManager(this)
        database = AppDatabase.getInstance(this)
        NotificationHelper.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        updateForegroundPresence(online = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        updateForegroundPresence(online = false)
    }

    private fun updateForegroundPresence(online: Boolean) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return
        val roomId = cryptoManager.getRoomId() ?: return
        val partnerUid = cryptoManager.getPartnerUid()
        val members = listOfNotNull(user.uid, partnerUid).distinct()

        appScope.launch {
            val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
            val repo = FirebaseRepository(
                db = db,
                auth = auth,
                cryptoManager = cryptoManager,
                messageDao = database.messageDao(),
                pushRelayClient = PushRelayClient(cryptoManager)
            )
            repo.updatePresence(
                roomId = roomId,
                memberUids = members,
                displayName = user.displayName ?: "Partner",
                online = online,
                typing = false,
                recordingAudio = false
            )
        }
    }
}
