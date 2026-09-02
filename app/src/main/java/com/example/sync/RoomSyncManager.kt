package com.example.sync

import com.example.data.PlaybackAction
import com.example.data.PlaybackState
import com.example.data.RoomParticipant
import com.example.data.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SyncNotification(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val senderName: String,
    val action: PlaybackAction,
    val timestamp: Long = System.currentTimeMillis()
)

class RoomSyncManager private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _roomTitle = MutableStateFlow("Watch Room")
    val roomTitle: StateFlow<String> = _roomTitle.asStateFlow()

    private val _participants = MutableStateFlow<List<RoomParticipant>>(emptyList())
    val participants: StateFlow<List<RoomParticipant>> = _participants.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _syncEvents = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 64)
    val syncEvents: SharedFlow<SyncNotification> = _syncEvents.asSharedFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _activeReactions = MutableSharedFlow<FloatingReaction>(extraBufferCapacity = 64)
    val activeReactions: SharedFlow<FloatingReaction> = _activeReactions.asSharedFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isMicrophoneEnabled = MutableStateFlow(true)
    val isMicrophoneEnabled: StateFlow<Boolean> = _isMicrophoneEnabled.asStateFlow()

    private var currentUserProfile: UserProfile? = null
    private var isHostUser = false
    private var heartbeatJob: Job? = null
    private val participantLastSeen = ConcurrentHashMap<String, Long>()

    // Realtime network client connected to shared topic
    private val networkClient = RealtimeRoomClient { incoming ->
        handleIncomingMessage(incoming)
    }

    val connectionState: StateFlow<ConnectionState> = networkClient.connectionState

    fun joinRoom(
        roomId: String,
        roomName: String,
        currentUser: UserProfile,
        isHost: Boolean = false,
        initialVideoUrl: String? = null,
        initialVideoTitle: String? = null
    ) {
        val cleanRoomId = roomId.trim().uppercase()
        _roomId.value = cleanRoomId
        _roomTitle.value = roomName
        currentUserProfile = currentUser
        isHostUser = isHost
        participantLastSeen.clear()
        _chatMessages.value = emptyList()

        val localParticipant = RoomParticipant(
            id = currentUser.id,
            name = currentUser.name,
            avatarUri = currentUser.avatarUri,
            avatarColorHex = currentUser.avatarColorHex,
            isHost = isHost,
            isCameraOn = _isCameraEnabled.value,
            isMuted = !_isMicrophoneEnabled.value
        )
        _participants.value = listOf(localParticipant)
        participantLastSeen[currentUser.id] = System.currentTimeMillis()

        if (initialVideoUrl != null && initialVideoUrl.isNotBlank()) {
            _playbackState.value = PlaybackState(
                videoUrl = initialVideoUrl,
                videoTitle = initialVideoTitle ?: "Video",
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                updatedAt = System.currentTimeMillis(),
                lastAction = PlaybackAction.CHANGE_VIDEO,
                actionSenderName = currentUser.name
            )
        } else {
            _playbackState.value = PlaybackState()
        }

        // Connect to Realtime Network Bus
        networkClient.connect(cleanRoomId)

        // Broadcast JOIN message over network
        networkClient.broadcast(
            RealtimeMessage(
                type = "JOIN",
                roomId = cleanRoomId,
                senderId = currentUser.id,
                senderName = currentUser.name,
                avatarUri = currentUser.avatarUri,
                avatarColorHex = currentUser.avatarColorHex,
                isHost = isHost,
                isCameraOn = _isCameraEnabled.value,
                isMuted = !_isMicrophoneEnabled.value
            )
        )

        // Start Periodic Heartbeat and Presence Pruning loop
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // If joining existing room, request state sync from host
            if (!isHost) {
                delay(400)
                networkClient.broadcast(
                    RealtimeMessage(
                        type = "SYNC_REQUEST",
                        roomId = cleanRoomId,
                        senderId = currentUser.id,
                        senderName = currentUser.name
                    )
                )
            }

            while (isActive) {
                delay(3000)
                val user = currentUserProfile ?: break

                // 1. Send our presence heartbeat
                networkClient.broadcast(
                    RealtimeMessage(
                        type = "HEARTBEAT",
                        roomId = cleanRoomId,
                        senderId = user.id,
                        senderName = user.name,
                        avatarUri = user.avatarUri,
                        avatarColorHex = user.avatarColorHex,
                        isHost = isHostUser,
                        isCameraOn = _isCameraEnabled.value,
                        isMuted = !_isMicrophoneEnabled.value
                    )
                )

                // 2. Prune disconnected participants (if no heartbeat for > 12 seconds)
                val now = System.currentTimeMillis()
                val activeList = _participants.value.filter { p ->
                    if (p.id == user.id) true
                    else {
                        val lastSeen = participantLastSeen[p.id] ?: 0L
                        (now - lastSeen) < 12000L
                    }
                }
                if (activeList.size != _participants.value.size) {
                    _participants.value = activeList
                }
            }
        }
    }

    private fun handleIncomingMessage(msg: RealtimeMessage) {
        val myId = currentUserProfile?.id ?: return
        if (msg.senderId == myId) return // Skip self echo

        participantLastSeen[msg.senderId] = System.currentTimeMillis()

        scope.launch {
            when (msg.type) {
                "JOIN" -> {
                    val newParticipant = RoomParticipant(
                        id = msg.senderId,
                        name = msg.senderName,
                        avatarUri = msg.avatarUri,
                        avatarColorHex = msg.avatarColorHex ?: "#E5A93C",
                        isHost = msg.isHost,
                        isCameraOn = msg.isCameraOn,
                        isMuted = msg.isMuted
                    )
                    addOrUpdateParticipant(newParticipant)
                    notifySync("${msg.senderName} joined the room", msg.senderName, PlaybackAction.INITIAL_SYNC)

                    // If we have an active video stream, reply with SYNC_STATE so the new user immediately sees it
                    val curPlay = _playbackState.value
                    if (curPlay.videoUrl.isNotBlank()) {
                        networkClient.broadcast(
                            RealtimeMessage(
                                type = "SYNC_STATE",
                                roomId = _roomId.value,
                                senderId = myId,
                                senderName = currentUserProfile?.name ?: "Host",
                                videoUrl = curPlay.videoUrl,
                                videoTitle = curPlay.videoTitle,
                                isPlaying = curPlay.isPlaying,
                                positionMs = curPlay.positionMs,
                                durationMs = curPlay.durationMs,
                                subtitlesEnabled = curPlay.subtitlesEnabled
                            )
                        )
                    }
                }

                "HEARTBEAT" -> {
                    val peer = RoomParticipant(
                        id = msg.senderId,
                        name = msg.senderName,
                        avatarUri = msg.avatarUri,
                        avatarColorHex = msg.avatarColorHex ?: "#E5A93C",
                        isHost = msg.isHost,
                        isCameraOn = msg.isCameraOn,
                        isMuted = msg.isMuted
                    )
                    addOrUpdateParticipant(peer)
                }

                "SYNC_REQUEST" -> {
                    val curPlay = _playbackState.value
                    if (curPlay.videoUrl.isNotBlank()) {
                        networkClient.broadcast(
                            RealtimeMessage(
                                type = "SYNC_STATE",
                                roomId = _roomId.value,
                                senderId = myId,
                                senderName = currentUserProfile?.name ?: "Host",
                                videoUrl = curPlay.videoUrl,
                                videoTitle = curPlay.videoTitle,
                                isPlaying = curPlay.isPlaying,
                                positionMs = curPlay.positionMs,
                                durationMs = curPlay.durationMs,
                                subtitlesEnabled = curPlay.subtitlesEnabled
                            )
                        )
                    }
                }

                "SYNC_STATE" -> {
                    if (msg.videoUrl != null && msg.videoUrl.isNotBlank()) {
                        val current = _playbackState.value
                        _playbackState.value = current.copy(
                            videoUrl = msg.videoUrl,
                            videoTitle = msg.videoTitle ?: "Video",
                            isPlaying = msg.isPlaying ?: false,
                            positionMs = msg.positionMs ?: 0L,
                            durationMs = msg.durationMs ?: current.durationMs,
                            subtitlesEnabled = msg.subtitlesEnabled ?: current.subtitlesEnabled,
                            updatedAt = System.currentTimeMillis(),
                            lastAction = PlaybackAction.INITIAL_SYNC,
                            actionSenderName = msg.senderName
                        )
                        notifySync("Synced video with ${msg.senderName}", msg.senderName, PlaybackAction.INITIAL_SYNC)
                    }
                }

                "PLAY" -> {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = true,
                        positionMs = msg.positionMs ?: _playbackState.value.positionMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.PLAY,
                        actionSenderName = msg.senderName
                    )
                    notifySync("${msg.senderName} pressed Play", msg.senderName, PlaybackAction.PLAY)
                }

                "PAUSE" -> {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        positionMs = msg.positionMs ?: _playbackState.value.positionMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.PAUSE,
                        actionSenderName = msg.senderName
                    )
                    notifySync("${msg.senderName} paused video", msg.senderName, PlaybackAction.PAUSE)
                }

                "SEEK" -> {
                    val targetMs = msg.positionMs ?: 0L
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = targetMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SEEK,
                        actionSenderName = msg.senderName
                    )
                    notifySync("${msg.senderName} jumped to ${formatTimestamp(targetMs)}", msg.senderName, PlaybackAction.SEEK)
                }

                "SKIP_FORWARD" -> {
                    val targetMs = msg.positionMs ?: (_playbackState.value.positionMs + 15000L)
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = targetMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SKIP_FORWARD,
                        actionSenderName = msg.senderName
                    )
                    notifySync("${msg.senderName} skipped +15s", msg.senderName, PlaybackAction.SKIP_FORWARD)
                }

                "SKIP_BACKWARD" -> {
                    val targetMs = msg.positionMs ?: (_playbackState.value.positionMs - 15000L).coerceAtLeast(0L)
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = targetMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SKIP_BACKWARD,
                        actionSenderName = msg.senderName
                    )
                    notifySync("${msg.senderName} rewound -15s", msg.senderName, PlaybackAction.SKIP_BACKWARD)
                }

                "CHANGE_VIDEO" -> {
                    if (!msg.videoUrl.isNullOrBlank()) {
                        _playbackState.value = _playbackState.value.copy(
                            videoUrl = msg.videoUrl,
                            videoTitle = msg.videoTitle ?: "Video",
                            isPlaying = true,
                            positionMs = 0L,
                            durationMs = 0L,
                            updatedAt = System.currentTimeMillis(),
                            lastAction = PlaybackAction.CHANGE_VIDEO,
                            actionSenderName = msg.senderName
                        )
                        notifySync("${msg.senderName} loaded \"${msg.videoTitle ?: "Video"}\"", msg.senderName, PlaybackAction.CHANGE_VIDEO)
                    }
                }

                "MEDIA_STATUS" -> {
                    updateParticipant(msg.senderId) {
                        it.copy(isCameraOn = msg.isCameraOn, isMuted = msg.isMuted)
                    }
                }

                "CHAT" -> {
                    if (!msg.chatText.isNullOrBlank()) {
                        val newMsg = ChatMessage(
                            senderId = msg.senderId,
                            senderName = msg.senderName,
                            senderAvatarColorHex = msg.avatarColorHex ?: "#E5A93C",
                            text = msg.chatText,
                            timestamp = msg.timestamp
                        )
                        _chatMessages.value = _chatMessages.value + newMsg
                    }
                }

                "REACTION" -> {
                    if (!msg.emoji.isNullOrBlank()) {
                        val reaction = FloatingReaction(
                            emoji = msg.emoji,
                            senderName = msg.senderName,
                            timestamp = msg.timestamp
                        )
                        _activeReactions.emit(reaction)
                    }
                }

                "LEAVE" -> {
                    removeParticipant(msg.senderId)
                    notifySync("${msg.senderName} left the room", msg.senderName, PlaybackAction.INITIAL_SYNC)
                }
            }
        }
    }

    private fun addOrUpdateParticipant(participant: RoomParticipant) {
        val current = _participants.value.toMutableList()
        val index = current.indexOfFirst { it.id == participant.id }
        if (index >= 0) {
            current[index] = participant
        } else {
            current.add(participant)
        }
        _participants.value = current
    }

    fun addParticipant(participant: RoomParticipant) {
        addOrUpdateParticipant(participant)
        participantLastSeen[participant.id] = System.currentTimeMillis()
    }

    fun removeParticipant(participantId: String) {
        _participants.value = _participants.value.filter { it.id != participantId }
        participantLastSeen.remove(participantId)
    }

    fun setCameraEnabled(enabled: Boolean, userId: String) {
        _isCameraEnabled.value = enabled
        updateParticipant(userId) { it.copy(isCameraOn = enabled) }
        broadcastMediaStatus()
    }

    fun setMicrophoneEnabled(enabled: Boolean, userId: String) {
        _isMicrophoneEnabled.value = enabled
        updateParticipant(userId) { it.copy(isMuted = !enabled) }
        broadcastMediaStatus()
    }

    private fun broadcastMediaStatus() {
        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "MEDIA_STATUS",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = user.name,
                isCameraOn = _isCameraEnabled.value,
                isMuted = !_isMicrophoneEnabled.value
            )
        )
    }

    private fun updateParticipant(userId: String, transform: (RoomParticipant) -> RoomParticipant) {
        _participants.value = _participants.value.map {
            if (it.id == userId) transform(it) else it
        }
    }

    // Playback control actions that broadcast live sync to all devices
    fun sendPlay(currentPosMs: Long, senderName: String) {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = true,
            positionMs = currentPosMs,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.PLAY,
            actionSenderName = senderName
        )
        notifySync("$senderName started playing", senderName, PlaybackAction.PLAY)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "PLAY",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                positionMs = currentPosMs
            )
        )
    }

    fun sendPause(currentPosMs: Long, senderName: String) {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            positionMs = currentPosMs,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.PAUSE,
            actionSenderName = senderName
        )
        notifySync("$senderName paused the video", senderName, PlaybackAction.PAUSE)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "PAUSE",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                positionMs = currentPosMs
            )
        )
    }

    fun sendSeek(targetPosMs: Long, senderName: String) {
        _playbackState.value = _playbackState.value.copy(
            positionMs = targetPosMs,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.SEEK,
            actionSenderName = senderName
        )
        val formattedTime = formatTimestamp(targetPosMs)
        notifySync("$senderName jumped to $formattedTime", senderName, PlaybackAction.SEEK)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "SEEK",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                positionMs = targetPosMs
            )
        )
    }

    fun sendSkipForward(currentPosMs: Long, durationMs: Long, senderName: String) {
        val newPos = (currentPosMs + 15000L).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
        _playbackState.value = _playbackState.value.copy(
            positionMs = newPos,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.SKIP_FORWARD,
            actionSenderName = senderName
        )
        notifySync("$senderName skipped +15s forward", senderName, PlaybackAction.SKIP_FORWARD)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "SKIP_FORWARD",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                positionMs = newPos
            )
        )
    }

    fun sendSkipBackward(currentPosMs: Long, senderName: String) {
        val newPos = (currentPosMs - 15000L).coerceAtLeast(0L)
        _playbackState.value = _playbackState.value.copy(
            positionMs = newPos,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.SKIP_BACKWARD,
            actionSenderName = senderName
        )
        notifySync("$senderName rewound -15s backward", senderName, PlaybackAction.SKIP_BACKWARD)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "SKIP_BACKWARD",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                positionMs = newPos
            )
        )
    }

    fun changeVideo(newUrl: String, newTitle: String, senderName: String) {
        _playbackState.value = _playbackState.value.copy(
            videoUrl = newUrl,
            videoTitle = newTitle,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 0L,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.CHANGE_VIDEO,
            actionSenderName = senderName
        )
        notifySync("$senderName loaded \"$newTitle\"", senderName, PlaybackAction.CHANGE_VIDEO)

        val user = currentUserProfile ?: return
        networkClient.broadcast(
            RealtimeMessage(
                type = "CHANGE_VIDEO",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                videoUrl = newUrl,
                videoTitle = newTitle
            )
        )
    }

    fun sendChatMessage(text: String, senderName: String) {
        val user = currentUserProfile ?: return
        val msg = ChatMessage(
            senderId = user.id,
            senderName = senderName,
            senderAvatarColorHex = user.avatarColorHex,
            text = text
        )
        _chatMessages.value = _chatMessages.value + msg

        networkClient.broadcast(
            RealtimeMessage(
                type = "CHAT",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                avatarColorHex = user.avatarColorHex,
                chatText = text
            )
        )
    }

    fun sendReaction(emoji: String, senderName: String) {
        val user = currentUserProfile ?: return
        val reaction = FloatingReaction(
            emoji = emoji,
            senderName = senderName
        )
        scope.launch {
            _activeReactions.emit(reaction)
        }

        networkClient.broadcast(
            RealtimeMessage(
                type = "REACTION",
                roomId = _roomId.value,
                senderId = user.id,
                senderName = senderName,
                emoji = emoji
            )
        )
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        _playbackState.value = _playbackState.value.copy(
            subtitlesEnabled = enabled
        )
    }

    fun updateDuration(durationMs: Long) {
        if (durationMs > 0 && _playbackState.value.durationMs != durationMs) {
            _playbackState.value = _playbackState.value.copy(durationMs = durationMs)
        }
    }

    fun updateLocalPosition(posMs: Long) {
        if (_playbackState.value.isPlaying) {
            _playbackState.value = _playbackState.value.copy(
                positionMs = posMs,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun notifySync(message: String, senderName: String, action: PlaybackAction) {
        scope.launch {
            _syncEvents.emit(
                SyncNotification(
                    message = message,
                    senderName = senderName,
                    action = action
                )
            )
        }
    }

    fun leaveRoom() {
        val user = currentUserProfile
        if (user != null && _roomId.value.isNotBlank()) {
            networkClient.broadcast(
                RealtimeMessage(
                    type = "LEAVE",
                    roomId = _roomId.value,
                    senderId = user.id,
                    senderName = user.name
                )
            )
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        networkClient.disconnect()
        participantLastSeen.clear()
        _roomId.value = ""
        _roomTitle.value = "Watch Room"
        _participants.value = emptyList()
        _chatMessages.value = emptyList()
        _playbackState.value = PlaybackState()
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    companion object {
        val instance = RoomSyncManager()
    }
}
