package com.example.musicplayer.ui.playlistdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.CreatePlaylistDialog
import com.example.musicplayer.ui.library.SongRow

private const val RECENTLY_ADDED_ID = -1L

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    currentSong: Song?,
    allPlaylists: List<Playlist>,
    onPlayAll: () -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSongs: (Set<Song>) -> Unit,
    onAddSongsToPlaylist: (songs: Set<Song>, playlistId: Long) -> Unit,
    onCreatePlaylistAndAdd: (name: String, songs: Set<Song>) -> Unit,
    onDeletePlaylist: () -> Unit,
    onBack: () -> Unit
) {
    val isRecentlyAdded = playlist.id == RECENTLY_ADDED_ID

    var selectionMode by remember { mutableStateOf(false) }
    var selectedSongs by remember { mutableStateOf(setOf<Song>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedSongs = emptySet()
    }

    // Delete playlist confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"${playlist.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist()
                    showDeleteConfirm = false
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Playlist picker for adding selected songs to another playlist
    if (showPlaylistPicker) {
        val songsToAdd = selectedSongs
        PlaylistPickerDialog(
            playlists = allPlaylists,
            currentPlaylistId = playlist.id,
            onPlaylistSelected = { playlistId ->
                onAddSongsToPlaylist(songsToAdd, playlistId)
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onCreateNewPlaylist = { name ->
                onCreatePlaylistAndAdd(name, songsToAdd)
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── App bar ──────────────────────────────────────────────────────────
        if (selectionMode) {
            // Selection mode bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { exitSelectionMode() }) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_close_24),
                        contentDescription = "Cancel selection"
                    )
                }
                Text(
                    text = "${selectedSongs.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                // Select all
                TextButton(onClick = { selectedSongs = playlist.songs.toSet() }) {
                    Text("All (${playlist.songs.size})")
                }
                // Add to playlist
                IconButton(
                    onClick = { showPlaylistPicker = true },
                    enabled = selectedSongs.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_playlist_add_24),
                        contentDescription = "Add to playlist"
                    )
                }
                // Remove from this playlist
                IconButton(
                    onClick = {
                        onRemoveSongs(selectedSongs)
                        exitSelectionMode()
                    },
                    enabled = selectedSongs.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_delete_24),
                        contentDescription = "Remove from playlist",
                        tint = if (selectedSongs.isNotEmpty())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Normal app bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_arrow_back_24),
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (!isRecentlyAdded) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_delete_24),
                            contentDescription = "Delete playlist",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ── Play All button ──────────────────────────────────────────────────
        if (!selectionMode) {
            Button(
                onClick = onPlayAll,
                enabled = playlist.songs.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Play All")
            }
        }

        // ── Song list ────────────────────────────────────────────────────────
        if (playlist.songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No songs yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                items(playlist.songs) { song ->
                    SongRow(
                        song = song,
                        isCurrentlyPlaying = song == currentSong,
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
    }
}

// ── Playlist picker dialog ────────────────────────────────────────────────────

@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    currentPlaylistId: Long,
    onPlaylistSelected: (Long) -> Unit,
    onCreateNewPlaylist: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Exclude Recently Added and the current playlist itself
    val targetPlaylists = playlists.filter {
        it.id != RECENTLY_ADDED_ID && it.id != currentPlaylistId
    }
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
                        Text("New playlist", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                    }

                    if (targetPlaylists.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        targetPlaylists.forEach { playlist ->
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