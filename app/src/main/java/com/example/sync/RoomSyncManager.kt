package com.example.sync

import com.example.data.PlaybackAction
import com.example.data.PlaybackState
import com.example.data.RoomParticipant
import com.example.data.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class SyncNotification(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val senderName: String,
    val action: PlaybackAction,
    val timestamp: Long = System.currentTimeMillis()
)

class RoomSyncManager private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _roomId = MutableStateFlow<String>("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _roomTitle = MutableStateFlow<String>("Watch Room")
    val roomTitle: StateFlow<String> = _roomTitle.asStateFlow()

    private val _participants = MutableStateFlow<List<RoomParticipant>>(emptyList())
    val participants: StateFlow<List<RoomParticipant>> = _participants.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _syncEvents = MutableSharedFlow<SyncNotification>(extraBufferCapacity = 64)
    val syncEvents: SharedFlow<SyncNotification> = _syncEvents.asSharedFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isMicrophoneEnabled = MutableStateFlow(true)
    val isMicrophoneEnabled: StateFlow<Boolean> = _isMicrophoneEnabled.asStateFlow()

    fun joinRoom(
        roomId: String,
        roomName: String,
        currentUser: UserProfile,
        isHost: Boolean = false,
        initialVideoUrl: String? = null,
        initialVideoTitle: String? = null
    ) {
        _roomId.value = roomId
        _roomTitle.value = roomName

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
        }
    }

    fun addParticipant(participant: RoomParticipant) {
        val current = _participants.value.toMutableList()
        if (current.none { it.id == participant.id }) {
            current.add(participant)
            _participants.value = current
            notifySync("${participant.name} joined the room", participant.name, PlaybackAction.INITIAL_SYNC)
        }
    }

    fun removeParticipant(participantId: String) {
        val current = _participants.value.toMutableList()
        val removed = current.firstOrNull { it.id == participantId }
        current.removeAll { it.id == participantId }
        _participants.value = current
        if (removed != null) {
            notifySync("${removed.name} left the room", removed.name, PlaybackAction.INITIAL_SYNC)
        }
    }

    fun setCameraEnabled(enabled: Boolean, userId: String) {
        _isCameraEnabled.value = enabled
        updateParticipant(userId) { it.copy(isCameraOn = enabled) }
    }

    fun setMicrophoneEnabled(enabled: Boolean, userId: String) {
        _isMicrophoneEnabled.value = enabled
        updateParticipant(userId) { it.copy(isMuted = !enabled) }
    }

    private fun updateParticipant(userId: String, transform: (RoomParticipant) -> RoomParticipant) {
        _participants.value = _participants.value.map {
            if (it.id == userId) transform(it) else it
        }
    }

    // Playback control actions that broadcast sync
    fun sendPlay(currentPosMs: Long, senderName: String) {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = true,
            positionMs = currentPosMs,
            updatedAt = System.currentTimeMillis(),
            lastAction = PlaybackAction.PLAY,
            actionSenderName = senderName
        )
        notifySync("$senderName started playing", senderName, PlaybackAction.PLAY)
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
        // Internal periodic position tracking
        if (_playbackState.value.isPlaying) {
            _playbackState.value = _playbackState.value.copy(
                positionMs = posMs,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    // Trigger an incoming action from a simulated peer (e.g. friend) to demonstrate bidirectional sync
    fun triggerPeerSyncAction(peerName: String, action: PlaybackAction, currentPosMs: Long, durationMs: Long) {
        scope.launch {
            when (action) {
                PlaybackAction.PLAY -> {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = true,
                        positionMs = currentPosMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.PLAY,
                        actionSenderName = peerName
                    )
                    notifySync("$peerName pressed play", peerName, PlaybackAction.PLAY)
                }
                PlaybackAction.PAUSE -> {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        positionMs = currentPosMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.PAUSE,
                        actionSenderName = peerName
                    )
                    notifySync("$peerName paused the video", peerName, PlaybackAction.PAUSE)
                }
                PlaybackAction.SKIP_FORWARD -> {
                    val newPos = (currentPosMs + 15000L).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = newPos,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SKIP_FORWARD,
                        actionSenderName = peerName
                    )
                    notifySync("$peerName skipped +15s forward", peerName, PlaybackAction.SKIP_FORWARD)
                }
                PlaybackAction.SKIP_BACKWARD -> {
                    val newPos = (currentPosMs - 15000L).coerceAtLeast(0L)
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = newPos,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SKIP_BACKWARD,
                        actionSenderName = peerName
                    )
                    notifySync("$peerName rewound -15s backward", peerName, PlaybackAction.SKIP_BACKWARD)
                }
                PlaybackAction.SEEK -> {
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = currentPosMs,
                        updatedAt = System.currentTimeMillis(),
                        lastAction = PlaybackAction.SEEK,
                        actionSenderName = peerName
                    )
                    notifySync("$peerName seeked to ${formatTimestamp(currentPosMs)}", peerName, PlaybackAction.SEEK)
                }
                else -> Unit
            }
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
        _roomId.value = ""
        _roomTitle.value = "Watch Room"
        _participants.value = emptyList()
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
