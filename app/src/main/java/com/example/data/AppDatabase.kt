package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun getUserProfile(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getUserProfileOnce(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: UserProfile)

    @Update
    suspend fun updateProfile(profile: UserProfile)

    @Query("DELETE FROM user_profile")
    suspend fun clearProfile()
}

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY addedAt DESC")
    fun getAllFriends(): Flow<List<Friend>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: Friend)

    @Query("DELETE FROM friends WHERE id = :friendId")
    suspend fun deleteFriend(friendId: String)

    @Update
    suspend fun updateFriend(friend: Friend)
}

@Dao
interface SavedRoomDao {
    @Query("SELECT * FROM saved_rooms ORDER BY lastJoinedAt DESC")
    fun getSavedRooms(): Flow<List<SavedRoom>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRoom(room: SavedRoom)

    @Query("DELETE FROM saved_rooms WHERE id = :roomId")
    suspend fun deleteRoom(roomId: String)
}

@Database(
    entities = [UserProfile::class, Friend::class, SavedRoom::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun friendDao(): FriendDao
    abstract fun savedRoomDao(): SavedRoomDao
}
