package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Friend
import com.example.data.SavedRoom
import com.example.data.UserProfile
import com.example.data.UserRepository
import com.example.sync.RoomSyncManager
import com.example.sync.RoomVerificationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppScreen {
    LOADING,
    INITIAL_PROFILE,
    HOME,
    WATCH_ROOM
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UserRepository.getInstance(application)
    val syncManager = RoomSyncManager.instance

    val userProfile: StateFlow<UserProfile?> = repository.userProfileFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val friends: StateFlow<List<Friend>> = repository.friendsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedRooms: StateFlow<List<SavedRoom>> = repository.savedRoomsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentScreen = MutableStateFlow(AppScreen.LOADING)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    init {
        viewModelScope.launch {
            repository.userProfileFlow.collect { profile ->
                if (_currentScreen.value == AppScreen.LOADING) {
                    if (profile == null) {
                        _currentScreen.value = AppScreen.INITIAL_PROFILE
                    } else {
                        _currentScreen.value = AppScreen.HOME
                    }
                }
            }
        }
    }

    fun saveProfile(name: String, avatarUri: String?, colorHex: String) {
        viewModelScope.launch {
            val profile = repository.saveProfile(name, avatarUri, colorHex)
            if (_currentScreen.value == AppScreen.INITIAL_PROFILE) {
                _currentScreen.value = AppScreen.HOME
            }
        }
    }

    fun addFriend(name: String, colorHex: String) {
        viewModelScope.launch {
            repository.addFriend(name = name, colorHex = colorHex)
        }
    }

    fun removeFriend(friendId: String) {
        viewModelScope.launch {
            repository.removeFriend(friendId)
        }
    }

    fun createRoom(roomName: String, initialVideoUrl: String?, initialVideoTitle: String?) {
        val user = userProfile.value ?: return
        val roomId = "SARN-" + (1000..9999).random()

        syncManager.joinRoom(
            roomId = roomId,
            roomName = roomName,
            currentUser = user,
            isHost = true,
            initialVideoUrl = initialVideoUrl,
            initialVideoTitle = initialVideoTitle
        )

        viewModelScope.launch {
            repository.saveRecentRoom(
                id = roomId,
                name = roomName,
                hostName = user.name,
                videoUrl = initialVideoUrl,
                videoTitle = initialVideoTitle
            )
        }

        _currentScreen.value = AppScreen.WATCH_ROOM
    }

    suspend fun verifyAndJoinRoom(
        roomId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = userProfile.value
        if (user == null) {
            onError("Profile not loaded. Please set up profile first.")
            return
        }

        val cleanRoomId = roomId.trim().uppercase()
        if (cleanRoomId.length < 3) {
            onError("Please enter a valid room code (e.g. SARN-1234).")
            return
        }

        when (val result = syncManager.verifyRoomExists(cleanRoomId)) {
            is RoomVerificationResult.Found -> {
                val roomName = result.roomName ?: "Watch Room ($cleanRoomId)"
                syncManager.joinRoom(
                    roomId = cleanRoomId,
                    roomName = roomName,
                    currentUser = user,
                    isHost = false
                )

                repository.saveRecentRoom(
                    id = cleanRoomId,
                    name = roomName,
                    hostName = result.hostName ?: "Host",
                    videoUrl = null,
                    videoTitle = null
                )

                _currentScreen.value = AppScreen.WATCH_ROOM
                onSuccess()
            }
            is RoomVerificationResult.NotFound -> {
                onError(result.reason)
            }
            is RoomVerificationResult.Error -> {
                onError(result.message)
            }
        }
    }

    fun joinRoom(roomId: String) {
        val user = userProfile.value ?: return
        val cleanRoomId = roomId.trim().uppercase()

        syncManager.joinRoom(
            roomId = cleanRoomId,
            roomName = "Watch Room ($cleanRoomId)",
            currentUser = user,
            isHost = false
        )

        viewModelScope.launch {
            repository.saveRecentRoom(
                id = cleanRoomId,
                name = "Watch Room ($cleanRoomId)",
                hostName = "Friend",
                videoUrl = null,
                videoTitle = null
            )
        }

        _currentScreen.value = AppScreen.WATCH_ROOM
    }

    fun leaveRoom() {
        syncManager.leaveRoom()
        _currentScreen.value = AppScreen.HOME
    }
}
