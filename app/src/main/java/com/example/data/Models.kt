package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColorHex: String = "#E5A93C",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "friends")
data class Friend(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColorHex: String = "#4E95FF",
    val isOnline: Boolean = true,
    val status: String = "CONNECTED", // CONNECTED, PENDING
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_rooms")
data class SavedRoom(
    @PrimaryKey val id: String,
    val name: String,
    val hostName: String,
    val lastVideoUrl: String? = null,
    val lastVideoTitle: String? = null,
    val lastJoinedAt: Long = System.currentTimeMillis()
)

data class RoomParticipant(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColorHex: String = "#E5A93C",
    val isHost: Boolean = false,
    val isCameraOn: Boolean = false,
    val isMuted: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis()
)

enum class PlaybackAction {
    PLAY,
    PAUSE,
    SEEK,
    SKIP_FORWARD,
    SKIP_BACKWARD,
    CHANGE_VIDEO,
    INITIAL_SYNC
}

data class PlaybackState(
    val videoUrl: String = "",
    val videoTitle: String = "No Video Selected",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAction: PlaybackAction = PlaybackAction.INITIAL_SYNC,
    val actionSenderName: String = "",
    val subtitlesEnabled: Boolean = false,
    val playbackSpeed: Float = 1.0f
)
