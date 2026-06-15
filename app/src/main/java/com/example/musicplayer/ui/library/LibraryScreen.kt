package com.example.musicplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun LibraryScreen(
    songs: List<Song>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSongClick: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(title = "Library")

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            placeholder = { Text("Search songs") },
            singleLine = true
        )

        LazyColumn {
            items(songs) { song ->
                SongRow(song = song, onClick = { onSongClick(song) })
            }
        }
    }
}