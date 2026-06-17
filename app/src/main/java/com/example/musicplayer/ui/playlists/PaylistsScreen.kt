package com.example.musicplayer.ui.playlists

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.ui.components.CreatePlaylistDialog
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    onPlaylistClick: (Playlist) -> Unit,
    onCreateClick: (name: String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    // Which playlist is pending deletion confirmation
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    // Delete confirmation dialog
    pendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"${playlist.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlist)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                onCreateClick(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(
            title = "Playlists",
            actions = {
                IconButton(onClick = { showCreateDialog = true }) {
                    Image(
                        painterResource(R.drawable.baseline_add_24),
                        contentDescription = "Create playlist"
                    )
                }
            }
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(playlists) { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist) },
                    onDeleteRequest = { pendingDelete = playlist }
                )
            }
        }
    }
}