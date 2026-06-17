package com.example.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Song

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,       // tapping the bar opens NowPlayingScreen
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Song info — takes all remaining space
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Controls
            IconButton(onClick = onPrevious) {
                Icon(
                    painter = painterResource(R.drawable.baseline_skip_previous_24),
                    contentDescription = "Previous"
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.baseline_pause_24
                        else R.drawable.baseline_play_arrow_24
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    painter = painterResource(R.drawable.baseline_skip_next_24),
                    contentDescription = "Next"
                )
            }
        }
    }
}