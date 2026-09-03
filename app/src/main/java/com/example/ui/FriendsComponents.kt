package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.Friend
import com.example.data.FriendRequest
import com.example.data.UserProfile
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.PersonRemove

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsBottomSheet(
    friends: List<Friend>,
    pendingRequests: List<FriendRequest> = emptyList(),
    onAcceptRequest: (FriendRequest) -> Unit = {},
    onDeclineRequest: (String) -> Unit = {},
    onSearchUser: ((String, (UserProfile?) -> Unit) -> Unit)? = null,
    onSendFriendRequest: ((UserProfile, (Boolean, String) -> Unit) -> Unit)? = null,
    onAddFriend: (name: String, colorHex: String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onInviteToRoom: ((Friend) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isAddingFriend by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = {
            focusManager.clearFocus()
            keyboardController?.hide()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("friends_bottom_sheet")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = "Friends",
                        tint = AccentGold,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Friends (${friends.size})",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                IconButton(
                    onClick = { isAddingFriend = true },
                    modifier = Modifier
                        .background(DarkSurfaceVariant, CircleShape)
                        .size(36.dp)
                        .testTag("add_friend_icon_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Add Friend",
                        tint = AccentGold,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pending Friend Requests Section
            if (pendingRequests.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Friend Requests (${pendingRequests.size})",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = AccentGold
                        )
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingRequests, key = { it.requestId }) { req ->
                        FriendRequestItemRow(
                            request = req,
                            onAccept = { onAcceptRequest(req) },
                            onDecline = { onDeclineRequest(req.requestId) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = DarkBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (friends.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No friends added yet",
                            style = MaterialTheme.typography.bodyLarge.copy(color = TextSecondary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Search by @username to connect and watch together",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextTertiary)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { isAddingFriend = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGold,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("empty_add_friend_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Find Friends", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(friends, key = { it.id }) { friend ->
                        FriendItemRow(
                            friend = friend,
                            onInvite = if (onInviteToRoom != null) { { onInviteToRoom(friend) } } else null,
                            onRemove = { onRemoveFriend(friend.id) }
                        )
                    }
                }
            }
        }
    }

    if (isAddingFriend) {
        AddFriendDialog(
            onSearchUser = onSearchUser,
            onSendFriendRequest = onSendFriendRequest,
            onAdd = { name, colorHex ->
                onAddFriend(name, colorHex)
                isAddingFriend = false
            },
            onDismiss = { isAddingFriend = false }
        )
    }
}

@Composable
fun FriendRequestItemRow(
    request: FriendRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        try {
                            Color(android.graphics.Color.parseColor(request.requesterAvatarColorHex)).copy(alpha = 0.25f)
                        } catch (e: Exception) {
                            AccentCyan.copy(alpha = 0.25f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = request.requesterDisplayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.requesterDisplayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "@${request.requesterUsername}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                )
            }

            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = DarkBackground),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Accept", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onDecline,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Decline", tint = TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun FriendItemRow(
    friend: Friend,
    onInvite: (() -> Unit)?,
    onRemove: () -> Unit
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth().testTag("friend_item_${friend.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Friend Avatar
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        try {
                            Color(android.graphics.Color.parseColor(friend.avatarColorHex)).copy(alpha = 0.25f)
                        } catch (e: Exception) {
                            AccentCyan.copy(alpha = 0.25f)
                        }
                    )
                    .border(
                        1.5.dp,
                        try {
                            Color(android.graphics.Color.parseColor(friend.avatarColorHex))
                        } catch (e: Exception) {
                            AccentCyan
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!friend.avatarUri.isNullOrBlank()) {
                    AsyncImage(
                        model = friend.avatarUri,
                        contentDescription = friend.name,
                        modifier = Modifier.size(46.dp).clip(CircleShape)
                    )
                } else {
                    Text(
                        text = friend.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Name & Status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (friend.isOnline) Color(0xFF2AC28A) else TextTertiary, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (friend.isOnline) "Connected" else "Offline",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (friend.isOnline) Color(0xFF2AC28A) else TextTertiary,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // In-room Invite button or remove
            if (onInvite != null) {
                Button(
                    onClick = onInvite,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGold,
                        contentColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp).testTag("invite_friend_btn_${friend.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Invite", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp).testTag("remove_friend_btn_${friend.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove friend",
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun AddFriendDialog(
    onSearchUser: ((String, (UserProfile?) -> Unit) -> Unit)? = null,
    onSendFriendRequest: ((UserProfile, (Boolean, String) -> Unit) -> Unit)? = null,
    onAdd: (name: String, colorHex: String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchUsername by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<UserProfile?>(null) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var requestStatusMessage by remember { mutableStateOf<String?>(null) }
    var isRequestSuccess by remember { mutableStateOf(false) }
    var showManualAdd by remember { mutableStateOf(false) }

    var friendName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(AVATAR_PALETTES.random()) }
    var manualError by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Dialog(
        onDismissRequest = {
            focusManager.clearFocus()
            keyboardController?.hide()
            onDismiss()
        },
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = DarkSurface,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding()
                .testTag("add_friend_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Friend",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onDismiss()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Real username search
                Text(
                    text = "Search for a user by their unique @username",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchUsername,
                        onValueChange = {
                            searchUsername = it
                            searchMessage = null
                            requestStatusMessage = null
                        },
                        placeholder = { Text("e.g. alice", color = TextTertiary) },
                        prefix = { Text("@", color = AccentGold, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGold,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_username_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val query = searchUsername.trim().removePrefix("@")
                            if (query.isNotBlank() && onSearchUser != null) {
                                isSearching = true
                                searchResult = null
                                searchMessage = null
                                requestStatusMessage = null
                                focusManager.clearFocus()
                                keyboardController?.hide()

                                onSearchUser(query) { result ->
                                    isSearching = false
                                    if (result != null) {
                                        searchResult = result
                                    } else {
                                        searchMessage = "User not found"
                                    }
                                }
                            }
                        },
                        enabled = !isSearching && searchUsername.trim().isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGold,
                            contentColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("search_user_button")
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = DarkBackground,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (searchMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = searchMessage ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                    )
                }

                // Search Result Card
                searchResult?.let { user ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().testTag("user_search_result_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            try {
                                                Color(android.graphics.Color.parseColor(user.avatarColorHex)).copy(alpha = 0.25f)
                                            } catch (e: Exception) {
                                                AccentCyan.copy(alpha = 0.25f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = user.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )
                                    Text(
                                        text = "@${user.username}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isRequestSuccess) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2AC28A), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = requestStatusMessage ?: "Friend request sent!",
                                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF2AC28A))
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        onSendFriendRequest?.invoke(user) { success, msg ->
                                            isRequestSuccess = success
                                            requestStatusMessage = msg
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentGold,
                                        contentColor = DarkBackground
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .testTag("send_friend_request_btn")
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Send Friend Request", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }

                                if (requestStatusMessage != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = requestStatusMessage ?: "",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle for quick manual/offline addition
                TextButton(
                    onClick = { showManualAdd = !showManualAdd }
                ) {
                    Text(
                        text = if (showManualAdd) "Hide manual entry" else "Add friend locally without @username",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextTertiary, fontSize = 12.sp)
                    )
                }

                if (showManualAdd) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = friendName,
                        onValueChange = {
                            friendName = it
                            manualError = null
                        },
                        label = { Text("Friend's Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGold,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("add_friend_name_input")
                    )

                    if (manualError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = manualError ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AVATAR_PALETTES.forEach { hex ->
                            val isSelected = selectedColor == hex
                            val color = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) TextPrimary else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = hex }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (friendName.trim().isBlank()) {
                                manualError = "Please enter a name"
                            } else {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onAdd(friendName.trim(), selectedColor)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGold,
                            contentColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("confirm_add_friend_btn")
                    ) {
                        Text("Add Locally", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}
