package com.example.musicplayer.ui.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Playlist

private const val RECENTLY_ADDED_ID = -1L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit      // called when the trash icon is tapped
) {
    val isDeletable = playlist.id != RECENTLY_ADDED_ID
    var showTrash by remember { mutableStateOf(false) }

    // Tapping anywhere else on the screen dismisses the trash icon
    Card(
        modifier = Modifier
            .padding(8.dp)
            .combinedClickable(
                onClick = {
                    if (showTrash) showTrash = false   // first tap dismisses
                    else onClick()
                },
                onLongClick = {
                    if (isDeletable) showTrash = true
                }
            )
    ) {
        Box {
            Column(modifier = Modifier.padding(16.dp)) {
                Image(
                    painter = painterResource(R.drawable.baseline_music_note_24),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${playlist.songCount()} songs",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = formatDuration(playlist.totalDuration()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Trash icon overlay — only visible after long-press, only for deletable playlists
            if (showTrash) {
                IconButton(
                    onClick = {
                        showTrash = false
                        onDeleteRequest()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_delete_24),
                        contentDescription = "Delete playlist",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalMs: Long): String {
    val totalSeconds = totalMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m"
    else "${minutes}m"
}