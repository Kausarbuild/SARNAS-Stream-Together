package com.example.sync

import com.example.data.PlaybackAction
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderAvatarColorHex: String = "#E5A93C",
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class FloatingReaction(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val senderName: String,
    val startXFraction: Float = (20..80).random() / 100f,
    val timestamp: Long = System.currentTimeMillis()
)

data class RealtimeMessage(
    val type: String, // JOIN, HEARTBEAT, LEAVE, SYNC_REQUEST, SYNC_STATE, PLAY, PAUSE, SEEK, SKIP_FORWARD, SKIP_BACKWARD, CHANGE_VIDEO, MEDIA_STATUS, CHAT, REACTION
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val avatarUri: String? = null,
    val avatarColorHex: String? = null,
    val isHost: Boolean = false,
    val isCameraOn: Boolean = false,
    val isMuted: Boolean = false,
    val videoUrl: String? = null,
    val videoTitle: String? = null,
    val isPlaying: Boolean? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val subtitlesEnabled: Boolean? = null,
    val chatText: String? = null,
    val emoji: String? = null,
    val videoFrameBase64: String? = null,
    val audioPacketBase64: String? = null,
    val targetUserId: String? = null,
    val sdpType: String? = null,
    val sdpDescription: String? = null,
    val iceCandidateSdp: String? = null,
    val iceCandidateSdpMid: String? = null,
    val iceCandidateSdpMLineIndex: Int? = null,
    val creatorId: String? = null,
    val createdAt: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)
