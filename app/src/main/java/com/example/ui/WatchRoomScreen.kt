package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.graphicsLayer
import com.example.camera.ParticipantBubblesStack
import com.example.data.Friend
import com.example.data.PlaybackAction
import com.example.data.RoomParticipant
import com.example.data.UserProfile
import com.example.player.SarnasVideoPlayer
import com.example.player.VideoUrlResolver
import com.example.sync.ChatMessage
import com.example.sync.ConnectionState
import com.example.sync.FloatingReaction
import com.example.sync.RoomSyncManager
import com.example.sync.SyncNotification
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import kotlinx.coroutines.delay

@Composable
fun WatchRoomScreen(
    currentUser: UserProfile,
    friends: List<Friend>,
    syncManager: RoomSyncManager,
    onLeaveRoom: () -> Unit
) {
    val context = LocalContext.current

    val roomId by syncManager.roomId.collectAsStateWithLifecycle()
    val roomTitle by syncManager.roomTitle.collectAsStateWithLifecycle()
    val participants by syncManager.participants.collectAsStateWithLifecycle()
    val playbackState by syncManager.playbackState.collectAsStateWithLifecycle()
    val isCameraEnabled by syncManager.isCameraEnabled.collectAsStateWithLifecycle()
    val isMicrophoneEnabled by syncManager.isMicrophoneEnabled.collectAsStateWithLifecycle()
    val connectionState by syncManager.connectionState.collectAsStateWithLifecycle()
    val chatMessages by syncManager.chatMessages.collectAsStateWithLifecycle()
    val peerVideoFrames by syncManager.peerVideoFrames.collectAsStateWithLifecycle()

    var showChangeVideoDialog by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }
    var showParticipantsSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var activeSyncBanner by remember { mutableStateOf<SyncNotification?>(null) }
    var isFullscreenActive by remember { mutableStateOf(false) }
    var reactionsList by remember { mutableStateOf<List<FloatingReaction>>(emptyList()) }

    // Collect Real-time reactions
    LaunchedEffect(Unit) {
        syncManager.activeReactions.collect { reaction ->
            reactionsList = reactionsList + reaction
        }
    }

    // Listen for real-time synchronization notification toasts
    LaunchedEffect(Unit) {
        syncManager.syncEvents.collect { notification ->
            activeSyncBanner = notification
            delay(3000)
            if (activeSyncBanner?.id == notification.id) {
                activeSyncBanner = null
            }
        }
    }

    // Camera & Microphone Permission Handlers
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted) {
            syncManager.setCameraEnabled(true, currentUser.id)
        } else if (permissions.containsKey(Manifest.permission.CAMERA)) {
            Toast.makeText(context, "Camera permission needed for video bubble", Toast.LENGTH_SHORT).show()
        }

        if (audioGranted) {
            syncManager.setMicrophoneEnabled(true, currentUser.id)
        }
    }

    fun requestCameraToggle() {
        if (!isCameraEnabled) {
            val hasCameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (hasCameraPerm) {
                syncManager.setCameraEnabled(true, currentUser.id)
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        } else {
            syncManager.setCameraEnabled(false, currentUser.id)
        }
    }

    fun requestMicToggle() {
        if (!isMicrophoneEnabled) {
            val hasAudioPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasAudioPerm) {
                syncManager.setMicrophoneEnabled(true, currentUser.id)
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            syncManager.setMicrophoneEnabled(false, currentUser.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("watch_room_screen")
    ) {
        // Main Dominant Video Player
        if (playbackState.videoUrl.isNotBlank()) {
            SarnasVideoPlayer(
                playbackState = playbackState,
                syncManager = syncManager,
                currentUserName = currentUser.name,
                modifier = Modifier.fillMaxSize(),
                onFullscreenToggle = { fs -> isFullscreenActive = fs }
            )
        } else {
            // Empty Video State (Invites user to paste URL or pick stream)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Load Video",
                        tint = AccentGold,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Video Loaded",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Paste a Google Drive link or video URL to start streaming together in real-time",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { showChangeVideoDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGold,
                            contentColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("load_initial_video_btn")
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Video Link", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Floating Animated Emojis / Reactions Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 90.dp)
        ) {
            reactionsList.takeLast(10).forEach { reaction ->
                FloatingReactionItem(
                    reaction = reaction,
                    onFinished = {
                        reactionsList = reactionsList.filter { it.id != reaction.id }
                    }
                )
            }
        }

        // Top Minimalist Room Bar (Overlay)
        if (!isFullscreenActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back & Room Info Pill
                Surface(
                    color = DarkSurface.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                syncManager.leaveRoom()
                                onLeaveRoom()
                            },
                            modifier = Modifier.size(32.dp).testTag("leave_room_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Leave Room",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = roomTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // Connection status indicator
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            color = when (connectionState) {
                                                ConnectionState.CONNECTED -> Color(0xFF2AC28A)
                                                ConnectionState.CONNECTING -> Color(0xFFFFB300)
                                                else -> Color(0xFFE53935)
                                            },
                                            shape = CircleShape
                                        )
                                )
                            }
                            Text(
                                text = "Code: $roomId • ${if (connectionState == ConnectionState.CONNECTED) "Live Synced" else "Connecting..."}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (connectionState == ConnectionState.CONNECTED) AccentGold else TextTertiary,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                // Top Actions: Link Video, Invite, Chat, Participants
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Live Chat Button
                    IconButton(
                        onClick = { showChatSheet = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(DarkSurface.copy(alpha = 0.88f), CircleShape)
                            .border(1.dp, DarkBorder, CircleShape)
                            .testTag("open_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Live Chat",
                            tint = AccentGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Change / Add Video Link
                    IconButton(
                        onClick = { showChangeVideoDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(DarkSurface.copy(alpha = 0.88f), CircleShape)
                            .border(1.dp, DarkBorder, CircleShape)
                            .testTag("change_video_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLink,
                            contentDescription = "Change Video",
                            tint = AccentGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Invite Friends
                    IconButton(
                        onClick = { showInviteSheet = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(DarkSurface.copy(alpha = 0.88f), CircleShape)
                            .border(1.dp, DarkBorder, CircleShape)
                            .testTag("invite_to_room_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Invite Friends",
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Participants Pill
                    Surface(
                        color = DarkSurface.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.clickable { showParticipantsSheet = true }.testTag("participants_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = "Participants",
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${participants.size}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // Live Real-Time Sync Action Notification Pill
        AnimatedVisibility(
            visible = activeSyncBanner != null,
            enter = fadeIn() + slideInVertically { -20 },
            exit = fadeOut() + slideOutVertically { -20 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 92.dp)
        ) {
            activeSyncBanner?.let { notification ->
                Surface(
                    color = DarkSurfaceElevated.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync",
                            tint = AccentGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = notification.message,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }
        }

        // Floating Camera Video Bubbles (Positioned gracefully at bottom-right corner)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (isFullscreenActive) 16.dp else 76.dp, end = 16.dp)
                .testTag("camera_bubbles_area")
        ) {
            ParticipantBubblesStack(
                participants = participants,
                currentUserId = currentUser.id,
                isCameraEnabled = isCameraEnabled,
                isMicrophoneEnabled = isMicrophoneEnabled,
                peerVideoFrames = peerVideoFrames,
                onFrameCaptured = { jpegBytes ->
                    syncManager.broadcastCameraFrame(jpegBytes)
                }
            )
        }

        // Floating Controls: Mic, Camera & Quick Emoji Reactions Bar at bottom-left corner
        if (!isFullscreenActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 76.dp, start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic toggle
                IconButton(
                    onClick = { requestMicToggle() },
                    modifier = Modifier
                        .size(42.dp)
                        .background(if (isMicrophoneEnabled) DarkSurface.copy(alpha = 0.85f) else Color(0xFFE53935).copy(alpha = 0.85f), CircleShape)
                        .border(1.dp, DarkBorder, CircleShape)
                        .testTag("room_mic_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isMicrophoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Toggle Mic",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Camera toggle
                IconButton(
                    onClick = { requestCameraToggle() },
                    modifier = Modifier
                        .size(42.dp)
                        .background(if (isCameraEnabled) AccentGold else DarkSurface.copy(alpha = 0.85f), CircleShape)
                        .border(1.dp, DarkBorder, CircleShape)
                        .testTag("room_camera_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = "Toggle Camera",
                        tint = if (isCameraEnabled) DarkBackground else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Quick Reaction Bar
                Surface(
                    color = DarkSurface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("❤️", "🔥", "😂", "👏", "🎉", "🍿").forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable {
                                        syncManager.sendReaction(emoji, currentUser.name)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Video Source Picker / Paste Dialog
    if (showChangeVideoDialog) {
        ChangeVideoSourceDialog(
            currentUrl = playbackState.videoUrl,
            onLoadVideo = { url, title ->
                syncManager.changeVideo(url, title, currentUser.name)
                showChangeVideoDialog = false
            },
            onDismiss = { showChangeVideoDialog = false }
        )
    }

    // In-Room Live Chat Bottom Sheet
    if (showChatSheet) {
        InRoomChatSheet(
            messages = chatMessages,
            currentUserId = currentUser.id,
            onSendMessage = { text ->
                syncManager.sendChatMessage(text, currentUser.name)
            },
            onDismiss = { showChatSheet = false }
        )
    }

    // In-Room Invite Bottom Sheet
    if (showInviteSheet) {
        InRoomInviteSheet(
            roomId = roomId,
            friends = friends,
            currentParticipants = participants,
            onInviteFriend = { friend ->
                // Share/Invite
                Toast.makeText(context, "Inviting ${friend.name} with room code $roomId", Toast.LENGTH_SHORT).show()
                showInviteSheet = false
            },
            onDismiss = { showInviteSheet = false }
        )
    }

    // Room Participants & Sync Test Sheet
    if (showParticipantsSheet) {
        RoomParticipantsSheet(
            participants = participants,
            currentUserId = currentUser.id,
            onSimulatePeerAction = { peerName, action ->
                val curPos = playbackState.positionMs
                when (action) {
                    PlaybackAction.PLAY -> syncManager.sendPlay(curPos, peerName)
                    PlaybackAction.PAUSE -> syncManager.sendPause(curPos, peerName)
                    PlaybackAction.SKIP_FORWARD -> syncManager.sendSkipForward(curPos, playbackState.durationMs, peerName)
                    else -> Unit
                }
            },
            onDismiss = { showParticipantsSheet = false }
        )
    }
}

@Composable
fun FloatingReactionItem(
    reaction: FloatingReaction,
    onFinished: () -> Unit
) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(reaction.id) {
        launch {
            offsetY.animateTo(
                targetValue = -350f,
                animationSpec = tween(durationMillis = 2400, easing = LinearEasing)
            )
        }
        launch {
            delay(1600)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 800)
            )
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = reaction.emoji,
            fontSize = 32.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = offsetY.value
                    this.alpha = alpha.value
                }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InRoomChatSheet(
    messages: List<ChatMessage>,
    currentUserId: String,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .testTag("in_room_chat_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Room Live Chat",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Messages List
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Say hi to the room! 👋",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        val isSelf = msg.senderId == currentUserId
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (isSelf) AccentGold else DarkSurfaceVariant,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    if (!isSelf) {
                                        Text(
                                            text = msg.senderName,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = AccentCyan,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = if (isSelf) DarkBackground else TextPrimary,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type a message...", color = TextTertiary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGold,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("chat_input_field")
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val text = textInput.trim()
                        if (text.isNotBlank()) {
                            onSendMessage(text)
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(AccentGold, CircleShape)
                        .testTag("send_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = DarkBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChangeVideoSourceDialog(
    currentUrl: String,
    onLoadVideo: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(currentUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var isResolving by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DarkSurface,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth().testTag("change_video_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Play Video",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Paste a YouTube URL (e.g. youtube.com or youtu.be), Google Drive link, Dropbox, or MP4/HLS stream:",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        error = null
                    },
                    placeholder = { Text("https://youtube.com/watch?v=... or Drive link") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGold,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("video_url_input_field")
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Fast test presets
                Text(
                    text = "Or choose a stream preset:",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VideoUrlResolver.CURATED_STREAMS) { preset ->
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.clickable {
                                urlInput = preset.directPlayableUrl
                            }
                        ) {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = AccentGold,
                                    fontSize = 11.sp
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val trimmed = urlInput.trim()
                        if (trimmed.isBlank()) {
                            error = "Please enter a video URL or Drive link"
                            return@Button
                        }
                        isResolving = true
                        error = null
                        coroutineScope.launch {
                            try {
                                val resolved = VideoUrlResolver.resolveAsync(trimmed)
                                onLoadVideo(resolved.directPlayableUrl, resolved.title)
                            } catch (e: Exception) {
                                // Fallback to synchronous resolve
                                val syncRes = VideoUrlResolver.resolve(trimmed)
                                if (syncRes.isSuccess) {
                                    val resolved = syncRes.getOrThrow()
                                    onLoadVideo(resolved.directPlayableUrl, resolved.title)
                                } else {
                                    error = syncRes.exceptionOrNull()?.message ?: e.localizedMessage ?: "Invalid video link"
                                }
                            } finally {
                                isResolving = false
                            }
                        }
                    },
                    enabled = !isResolving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGold,
                        contentColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_load_video_btn")
                ) {
                    if (isResolving) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = DarkBackground,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Resolving Stream...", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    } else {
                        Text("Load & Play in Room", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InRoomInviteSheet(
    roomId: String,
    friends: List<Friend>,
    currentParticipants: List<RoomParticipant>,
    onInviteFriend: (Friend) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val inviteLink = "https://sarnas.stream/watch?room=$roomId"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("in_room_invite_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Invite to Room",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Room code & Copy Share Box
            Surface(
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Room Invite Code",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = roomId,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = AccentGold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }

                    Row {
                        // Copy Button
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("SARNAS Room Code", roomId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Room code copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .background(DarkBorder, CircleShape)
                                .size(36.dp)
                                .testTag("copy_room_code_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextPrimary, modifier = Modifier.size(16.dp))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Share Intent Button
                        IconButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Join my SARNAS Watch Room")
                                    putExtra(Intent.EXTRA_TEXT, "Watch videos together on SARNAS! Join room with code: $roomId or link: $inviteLink")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Watch Room"))
                            },
                            modifier = Modifier
                                .background(AccentGold, CircleShape)
                                .size(36.dp)
                                .testTag("share_room_code_btn")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = DarkBackground, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Friends Quick Invite
            Text(
                text = "Direct Invite Friends",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            val nonParticipantFriends = friends.filter { f -> currentParticipants.none { it.id == f.id } }

            if (nonParticipantFriends.isEmpty()) {
                Text(
                    text = if (friends.isEmpty()) "No friends added yet. Share room code above!" else "All your connected friends are in the room!",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    nonParticipantFriends.forEach { friend ->
                        FriendItemRow(
                            friend = friend,
                            onInvite = { onInviteFriend(friend) },
                            onRemove = {}
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomParticipantsSheet(
    participants: List<RoomParticipant>,
    currentUserId: String,
    onSimulatePeerAction: (peerName: String, action: PlaybackAction) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("room_participants_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Room Participants (${participants.size})",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                participants.forEach { p ->
                    val isSelf = p.id == currentUserId
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(AccentGold.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = p.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSelf) "${p.name} (You)" else p.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = if (p.isHost) "Host • In sync" else "Participant • In sync",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = if (p.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (p.isMuted) Color(0xFFE53935) else Color(0xFF2AC28A),
                                    modifier = Modifier.size(16.dp)
                                )
                                Icon(
                                    imageVector = if (p.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = null,
                                    tint = if (p.isCameraOn) AccentGold else TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Sync Verification Controls for connected peers
            val otherParticipants = participants.filter { it.id != currentUserId }
            if (otherParticipants.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Test Remote Synchronization:",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val peer = otherParticipants.first()
                    Button(
                        onClick = { onSimulatePeerAction(peer.name, PlaybackAction.PAUSE) },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("Peer Pause", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onSimulatePeerAction(peer.name, PlaybackAction.PLAY) },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("Peer Play", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onSimulatePeerAction(peer.name, PlaybackAction.SKIP_FORWARD) },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("Peer +15s", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
