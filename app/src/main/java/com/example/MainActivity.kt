package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.RoomInvite
import kotlinx.coroutines.launch
import com.example.ui.AppScreen
import com.example.ui.FriendsBottomSheet
import com.example.ui.HomeScreen
import com.example.ui.MainViewModel
import com.example.ui.ProfileSetupDialog
import com.example.ui.WatchRoomScreen
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

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
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val pendingFriendRequests by viewModel.pendingFriendRequests.collectAsStateWithLifecycle()
    val savedRooms by viewModel.savedRooms.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showFriendsSheet by remember { mutableStateOf(false) }
    var activeInvite by remember { mutableStateOf<RoomInvite?>(null) }

    // Listen for incoming room invites
    LaunchedEffect(Unit) {
        viewModel.incomingInvites.collect { invite ->
            activeInvite = invite
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
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
                            onJoinRoom = { roomId, onSuccess, onError ->
                                coroutineScope.launch {
                                    viewModel.verifyAndJoinRoom(roomId, onSuccess, onError)
                                }
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
                            onSendRoomInvite = { friend, roomId, roomTitle ->
                                viewModel.sendRoomInvite(friend, roomId, roomTitle)
                            },
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
                    pendingRequests = pendingFriendRequests,
                    onAcceptRequest = { req ->
                        viewModel.acceptFriendRequest(req)
                        Toast.makeText(context, "Accepted friend request from @${req.requesterUsername}", Toast.LENGTH_SHORT).show()
                    },
                    onDeclineRequest = { id ->
                        viewModel.declineFriendRequest(id)
                    },
                    onSearchUser = { query, callback ->
                        viewModel.searchUser(query, callback)
                    },
                    onSendFriendRequest = { target, callback ->
                        viewModel.sendFriendRequest(target, callback)
                    },
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

            // Incoming Room Invite Alert Dialog
            activeInvite?.let { invite ->
                AlertDialog(
                    onDismissRequest = { activeInvite = null },
                    title = {
                        Text(
                            text = "Room Invitation",
                            color = TextPrimary
                        )
                    },
                    text = {
                        Text(
                            text = "${invite.senderName} invited you to join \"${invite.roomTitle}\" (${invite.roomId})",
                            color = TextSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val rId = invite.roomId
                                activeInvite = null
                                coroutineScope.launch {
                                    viewModel.verifyAndJoinRoom(
                                        roomId = rId,
                                        onSuccess = {},
                                        onError = { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGold,
                                contentColor = DarkBackground
                            )
                        ) {
                            Text("Join Room")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeInvite = null }) {
                            Text("Decline", color = TextSecondary)
                        }
                    },
                    containerColor = DarkSurface
                )
            }
        }
    }
}
