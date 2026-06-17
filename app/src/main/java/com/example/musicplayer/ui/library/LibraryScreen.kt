package com.example.musicplayer.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun LibraryScreen(
    songs: List<Song>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    playlists: List<Playlist>,
    onAddToPlaylist: (songs: Set<Song>, playlistId: Long) -> Unit
) {
    // --- Selection state (lives here — purely UI concern) ---
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedSongs = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        if (selectionMode) {
            SelectionAppBar(
                selectedCount = selectedSongs.size,
                totalCount = songs.size,
                onSelectAll = { selectedSongs = songs.toSet() },
                onCancel = { exitSelectionMode() },
                onAddToPlaylist = { showPlaylistPicker = true }
            )
        } else {
            SimpleAppBar(title = "Library")
        }

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
                SongRow(
                    song = song,
                    selectionMode = selectionMode,
                    isSelected = song in selectedSongs,
                    onClick = {
                        if (selectionMode) {
                            // In selection mode, tap toggles the checkbox
                            selectedSongs = if (song in selectedSongs)
                                selectedSongs - song
                            else
                                selectedSongs + song
                        } else {
                            onSongClick(song)
                        }
                    },
                    onLongClick = {
                        if (!selectionMode) {
                            selectionMode = true
                            selectedSongs = setOf(song)   // pre-select the long-pressed song
                        }
                    },
                    onCheckedChange = { checked ->
                        selectedSongs = if (checked)
                            selectedSongs + song
                        else
                            selectedSongs - song
                    }
                )
            }
        }
    }

    // Playlist picker dialog
    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                onAddToPlaylist(selectedSongs, playlistId)
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }
}

// -----------------------------------------------------------------------------
// Selection app bar — replaces SimpleAppBar while in selection mode
// -----------------------------------------------------------------------------

@Composable
private fun SelectionAppBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Cancel
        IconButton(onClick = onCancel) {
            Icon(
                painter = painterResource(R.drawable.baseline_close_24),
                contentDescription = "Cancel selection"
            )
        }

        // Count label
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .alignByBaseline()
                .wrapContentHeight()
        )

        // Select all
        TextButton(onClick = onSelectAll) {
            Text("All ($totalCount)")
        }

        // Add to playlist
        IconButton(
            onClick = onAddToPlaylist,
            enabled = selectedCount > 0
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_playlist_add_24),
                contentDescription = "Add to playlist"
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Playlist picker dialog
// -----------------------------------------------------------------------------

@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onPlaylistSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // Filter out the synthetic Recently Added playlist (id = -1)
    val userPlaylists = playlists.filter { it.id != -1L }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            if (userPlaylists.isEmpty()) {
                Text(
                    "No playlists yet. Create one from the Playlists tab first.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    userPlaylists.forEach { playlist ->
                        TextButton(
                            onClick = { onPlaylistSelected(playlist.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${playlist.songCount()} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}