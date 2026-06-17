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
import com.example.musicplayer.ui.components.CreatePlaylistDialog
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun LibraryScreen(
    songs: List<Song>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    playlists: List<Playlist>,
    onAddToPlaylist: (songs: Set<Song>, playlistId: Long) -> Unit,
    onCreatePlaylist: (name: String) -> Unit
) {
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
                            selectedSongs = setOf(song)
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

    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                onAddToPlaylist(selectedSongs, playlistId)
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onCreateNewPlaylist = { name ->
                // Create the playlist then add songs to it.
                // ViewModel returns the new ID via callback so we can add immediately.
                onCreatePlaylist(name)
                // onCreatePlaylist triggers a DB insert whose Flow emission updates
                // playlists — we close and let the user re-open to pick it, OR the
                // caller can provide the new ID directly (see AppNavHost note below).
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }
}

// -----------------------------------------------------------------------------
// Selection app bar
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
        IconButton(onClick = onCancel) {
            Icon(
                painter = painterResource(R.drawable.baseline_close_24),
                contentDescription = "Cancel selection"
            )
        }

        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .wrapContentHeight()
        )

        TextButton(onClick = onSelectAll) {
            Text("All ($totalCount)")
        }

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
// Playlist picker dialog — with inline "New playlist" entry
// -----------------------------------------------------------------------------

@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onPlaylistSelected: (Long) -> Unit,
    onCreateNewPlaylist: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val userPlaylists = playlists.filter { it.id != -1L }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                onCreateNewPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add to playlist") },
            text = {
                Column {
                    // "New playlist" always at the top
                    TextButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_add_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "New playlist",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.weight(1f))
                    }

                    if (userPlaylists.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        userPlaylists.forEach { playlist ->
                            TextButton(
                                onClick = { onPlaylistSelected(playlist.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
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
}