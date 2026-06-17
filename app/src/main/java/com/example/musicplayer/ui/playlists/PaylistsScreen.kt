package com.example.musicplayer.ui.playlists

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
    onCreateClick: (name: String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(
            title = "Playlists",
            actions = {
                IconButton(onClick = { showDialog = true }) {
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
                    onClick = { onPlaylistClick(playlist) }
                )
            }
        }
    }

    if (showDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                onCreateClick(name)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}