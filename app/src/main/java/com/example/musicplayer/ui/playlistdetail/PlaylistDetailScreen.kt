package com.example.musicplayer.ui.playlistdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar
import com.example.musicplayer.ui.library.SongRow

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    onSongClick: (Song) -> Unit
) {

    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(title = playlist.name)

        Button(
            onClick = { },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Play All")
        }

        LazyColumn {
            items(playlist.songs) { song ->
                SongRow(song = song,
                    onClick = { onSongClick(song) })
            }
        }
    }
}