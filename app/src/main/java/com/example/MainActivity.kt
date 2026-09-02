package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.FriendsBottomSheet
import com.example.ui.HomeScreen
import com.example.ui.MainViewModel
import com.example.ui.ProfileSetupDialog
import com.example.ui.WatchRoomScreen
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SarnasApp()
            }
        }
    }
}

@Composable
fun SarnasApp(viewModel: MainViewModel = viewModel()) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val savedRooms by viewModel.savedRooms.collectAsStateWithLifecycle()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showFriendsSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
        ) {
            when (currentScreen) {
                AppScreen.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = AccentGold,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                AppScreen.INITIAL_PROFILE -> {
                    ProfileSetupDialog(
                        initialProfile = null,
                        isInitialSetup = true,
                        onSave = { name, avatarUri, colorHex ->
                            viewModel.saveProfile(name, avatarUri, colorHex)
                        },
                        onDismiss = {}
                    )
                }

                AppScreen.HOME -> {
                    userProfile?.let { profile ->
                        HomeScreen(
                            currentUser = profile,
                            friends = friends,
                            savedRooms = savedRooms,
                            onCreateRoom = { name, url, title ->
                                viewModel.createRoom(name, url, title)
                            },
                            onJoinRoom = { roomId ->
                                viewModel.joinRoom(roomId)
                            },
                            onOpenProfile = { showEditProfileDialog = true },
                            onOpenFriends = { showFriendsSheet = true }
                        )
                    }
                }

                AppScreen.WATCH_ROOM -> {
                    userProfile?.let { profile ->
                        WatchRoomScreen(
                            currentUser = profile,
                            friends = friends,
                            syncManager = viewModel.syncManager,
                            onLeaveRoom = { viewModel.leaveRoom() }
                        )
                    }
                }
            }

            // Edit Profile Dialog (Accessible from Home)
            if (showEditProfileDialog && userProfile != null) {
                ProfileSetupDialog(
                    initialProfile = userProfile,
                    isInitialSetup = false,
                    onSave = { name, avatarUri, colorHex ->
                        viewModel.saveProfile(name, avatarUri, colorHex)
                        showEditProfileDialog = false
                    },
                    onDismiss = { showEditProfileDialog = false }
                )
            }

            // Friends BottomSheet (Accessible from Home)
            if (showFriendsSheet) {
                FriendsBottomSheet(
                    friends = friends,
                    onAddFriend = { name, colorHex ->
                        viewModel.addFriend(name, colorHex)
                    },
                    onRemoveFriend = { id ->
                        viewModel.removeFriend(id)
                    },
                    onInviteToRoom = null,
                    onDismiss = { showFriendsSheet = false }
                )
            }
        }
    }
}
