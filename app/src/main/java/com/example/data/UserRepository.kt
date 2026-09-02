package com.example.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class UserRepository private constructor(context: Context) {

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "sarnas_database.db"
    ).fallbackToDestructiveMigration().build()

    private val userDao = database.userDao()
    private val friendDao = database.friendDao()
    private val savedRoomDao = database.savedRoomDao()

    val userProfileFlow: Flow<UserProfile?> = userDao.getUserProfile()
    val friendsFlow: Flow<List<Friend>> = friendDao.getAllFriends()
    val savedRoomsFlow: Flow<List<SavedRoom>> = savedRoomDao.getSavedRooms()

    suspend fun getCurrentUser(): UserProfile? = userDao.getUserProfileOnce()

    suspend fun saveProfile(name: String, avatarUri: String?, colorHex: String = "#E5A93C"): UserProfile {
        val existing = userDao.getUserProfileOnce()
        val userId = existing?.id ?: ("usr_" + UUID.randomUUID().toString().take(8))
        val profile = UserProfile(
            id = userId,
            name = name.trim(),
            avatarUri = avatarUri,
            avatarColorHex = colorHex,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        userDao.insertOrUpdateProfile(profile)
        return profile
    }

    suspend fun addFriend(name: String, avatarUri: String? = null, colorHex: String = "#4E95FF"): Friend {
        val friend = Friend(
            id = "frd_" + UUID.randomUUID().toString().take(8),
            name = name.trim(),
            avatarUri = avatarUri,
            avatarColorHex = colorHex,
            isOnline = true,
            status = "CONNECTED",
            addedAt = System.currentTimeMillis()
        )
        friendDao.insertFriend(friend)
        return friend
    }

    suspend fun removeFriend(friendId: String) {
        friendDao.deleteFriend(friendId)
    }

    suspend fun saveRecentRoom(id: String, name: String, hostName: String, videoUrl: String? = null, videoTitle: String? = null) {
        val room = SavedRoom(
            id = id,
            name = name,
            hostName = hostName,
            lastVideoUrl = videoUrl,
            lastVideoTitle = videoTitle,
            lastJoinedAt = System.currentTimeMillis()
        )
        savedRoomDao.insertOrUpdateRoom(room)
    }

    suspend fun deleteSavedRoom(roomId: String) {
        savedRoomDao.deleteRoom(roomId)
    }

    companion object {
        @Volatile
        private var INSTANCE: UserRepository? = null

        fun getInstance(context: Context): UserRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserRepository(context).also { INSTANCE = it }
            }
        }
    }
}
