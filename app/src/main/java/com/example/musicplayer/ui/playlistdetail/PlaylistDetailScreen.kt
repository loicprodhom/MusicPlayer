package com.example.musicplayer.ui.playlistdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar
import com.example.musicplayer.ui.library.SongRow

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    onPlayAll: () -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit
) {
    // Tracks which song's dropdown is open; null means none
    var menuOpenForSong by remember { mutableStateOf<Song?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(title = playlist.name)

        Button(
            onClick = onPlayAll,
            enabled = playlist.songs.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Play All")
        }

        if (playlist.songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "No songs yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(playlist.songs) { _, song ->
                    Box {
                        SongRow(
                            song = song,
                            onClick = { onSongClick(song) },
                            onLongClick = { menuOpenForSong = song }
                        )

                        // Dropdown anchored to the row that was long-pressed
                        DropdownMenu(
                            expanded = menuOpenForSong == song,
                            onDismissRequest = { menuOpenForSong = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Remove from playlist") },
                                onClick = {
                                    onRemoveSong(song)
                                    menuOpenForSong = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}