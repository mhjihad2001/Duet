package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.LocalDuetChatColors
import com.example.ui.theme.WaGoldAccent
import com.example.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StarredMessagesScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val starred by viewModel.starredMessages.collectAsStateWithLifecycle()
    val chatColors = LocalDuetChatColors.current

    Scaffold(
        topBar = {
            Surface(color = chatColors.topBarBackground, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = chatColors.topBarContent
                        )
                    }
                    Text(
                        text = "Starred Messages",
                        style = MaterialTheme.typography.titleLarge,
                        color = chatColors.topBarContent
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(chatColors.chatBackground)
        ) {
            if (starred.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.StarOutline,
                        contentDescription = null,
                        tint = chatColors.timestampText,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No starred messages yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = chatColors.timestampText
                    )
                    Text(
                        text = "Long-press any message in chat to star it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.timestampText
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(starred, key = { it.id }) { item ->
                        val dateText = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                            .format(Date(item.createdAtMillis))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = chatColors.incomingBubble),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (item.senderUid == viewModel.currentUserId) "You" else "Partner",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = chatColors.accentEmerald
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.decryptedText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = chatColors.incomingText
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = chatColors.timestampText
                                    )
                                }
                                IconButton(onClick = { viewModel.toggleStarred(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Unstar",
                                        tint = WaGoldAccent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
