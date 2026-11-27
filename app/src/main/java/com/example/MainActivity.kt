package com.example

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.model.SecurityWhitelist
import com.example.data.remote.FirebaseRepository
import com.example.data.remote.PushRelayClient
import com.example.service.NotificationHelper
import com.example.ui.auth.GoogleAuthHelper
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.LockScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SetupVaultScreen
import com.example.ui.screens.StarredMessagesScreen
import com.example.ui.theme.ChatWallpaper
import com.example.ui.theme.DuetTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.webrtc.WebRTCClient
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

object Routes {
    const val CHAT = "chat"
    const val CALL = "call"
    const val STARRED = "starred"
    const val SETTINGS = "settings"
}

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply FLAG_SECURE on physical devices to block screenshots & recents preview,
        // while keeping streaming emulators visible unless explicitly enabled in Settings.
        val app = applicationContext as DuetApplication
        val shouldSecure = app.cryptoManager.isScreenPrivacyEnabled(defaultValue = !isEmulatorDevice())
        if (shouldSecure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        enableEdgeToEdge()
        NotificationHelper.cancelCallNotification(this)

        setContent {
            DuetRootContent()
        }
    }

    companion object {
        fun isEmulatorDevice(): Boolean {
            return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("cuttlefish")
                || Build.HARDWARE.contains("vsoc")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
        }
    }
}

@Composable
private fun DuetRootContent() {
    val context = LocalContext.current
    val app = context.applicationContext as DuetApplication
    val cryptoManager = app.cryptoManager
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    var authError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val u = auth.currentUser
            if (u != null && !SecurityWhitelist.isAllowed(u.uid)) {
                auth.signOut()
                authError = "Access denied: UID is not authorized for this private 2-user messenger."
                currentUser = null
            } else {
                currentUser = u
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        DuetTheme(darkTheme = true, wallpaper = ChatWallpaper.OBSIDIAN_BOTANICAL) {
            AuthScreen(
                onAuthSuccess = {
                    authError = null
                    currentUser = Firebase.auth.currentUser
                },
                initialError = authError
            )
        }
    } else {
        val uid = user.uid
        key(uid) {
            val viewModel: ChatViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val application = checkNotNull(this[APPLICATION_KEY]) as DuetApplication
                        val databaseId = application.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        val dao = AppDatabase.getInstance(application).messageDao()
                        val repo = FirebaseRepository(
                            db = db,
                            auth = Firebase.auth,
                            cryptoManager = application.cryptoManager,
                            messageDao = dao,
                            pushRelayClient = PushRelayClient(application.cryptoManager)
                        )
                        val webRtc = WebRTCClient(application)
                        ChatViewModel(
                            repository = repo,
                            cryptoManager = application.cryptoManager,
                            webRTCClient = webRtc,
                            currentUserId = uid,
                            currentUserName = user.displayName ?: "Partner"
                        )
                    }
                }
            )

            AuthenticatedDuetShell(
                viewModel = viewModel,
                cryptoManager = cryptoManager,
                onSignOut = {
                    GoogleAuthHelper.signOut(credentialManager, scope) {
                        currentUser = null
                    }
                }
            )
        }
    }
}

@Composable
private fun AuthenticatedDuetShell(
    viewModel: ChatViewModel,
    cryptoManager: CryptoManager,
    onSignOut: () -> Unit
) {
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val vaultConfigured by viewModel.vaultConfigured.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBanner.collectAsStateWithLifecycle()

    var isUnlocked by remember { mutableStateOf(!cryptoManager.isVaultConfigured()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Automatically re-lock when app moves to background (unless in an active call)
    DisposableEffect(lifecycleOwner, vaultConfigured, activeCall) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && vaultConfigured && activeCall == null) {
                isUnlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DuetTheme(darkTheme = darkTheme, wallpaper = wallpaper) {
        when {
            !vaultConfigured -> {
                SetupVaultScreen(
                    isBusy = isBusy,
                    errorBanner = errorBanner,
                    onCreateVault = { passphrase, pin, biometric ->
                        viewModel.setupNewVault(passphrase, pin, biometric) {
                            isUnlocked = true
                        }
                    },
                    onJoinVault = { roomCode, passphrase, pin, biometric ->
                        viewModel.joinExistingVault(roomCode, passphrase, pin, biometric) {
                            isUnlocked = true
                        }
                    }
                )
            }

            !isUnlocked -> {
                LockScreen(
                    cryptoManager = cryptoManager,
                    onUnlocked = { isUnlocked = true }
                )
            }

            else -> {
                val navController = rememberNavController()

                LaunchedEffect(activeCall?.id, activeCall?.status) {
                    val call = activeCall
                    if (call != null && (call.status == "RINGING" || call.status == "ACTIVE")) {
                        if (navController.currentDestination?.route != Routes.CALL) {
                            navController.navigate(Routes.CALL) {
                                launchSingleTop = true
                            }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.CHAT
                ) {
                    composable(Routes.CHAT) {
                        ChatScreen(
                            viewModel = viewModel,
                            onOpenStarred = { navController.navigate(Routes.STARRED) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onLockNow = { isUnlocked = false },
                            onStartCall = { isVideo ->
                                viewModel.startOutgoingCall(isVideo) {
                                    navController.navigate(Routes.CALL) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        )
                    }

                    composable(Routes.CALL) {
                        val call = activeCall
                        if (call != null) {
                            CallScreen(
                                call = call,
                                viewModel = viewModel,
                                onCallClosed = {
                                    navController.popBackStack(Routes.CHAT, inclusive = false)
                                }
                            )
                        } else {
                            LaunchedEffect(Unit) {
                                navController.popBackStack(Routes.CHAT, inclusive = false)
                            }
                        }
                    }

                    composable(Routes.STARRED) {
                        StarredMessagesScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            cryptoManager = cryptoManager,
                            onBack = { navController.popBackStack() },
                            onSignOut = onSignOut
                        )
                    }
                }
            }
        }
    }
}
