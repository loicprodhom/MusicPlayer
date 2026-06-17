package com.example.musicplayer.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.RepeatMode
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    progress: Float,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(title = "Now Playing")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Album art placeholder
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = song?.title ?: "No Song",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1
            )
            Text(
                text = song?.artist ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = progress,
                onValueChange = onSeek
            )

            // Time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs((progress * (song?.duration ?: 0)).toLong()),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = song?.formattedDuration() ?: "0:00",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main transport controls: prev / play-pause / next
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onPrevious) {
                    Image(painterResource(R.drawable.baseline_skip_previous_24), contentDescription = "Previous")
                }

                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp)
                ) {
                    Image(
                        painter = painterResource(
                            if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }

                IconButton(onClick = onNext) {
                    Image(painterResource(R.drawable.baseline_skip_next_24), contentDescription = "Next")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secondary controls: shuffle / repeat
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shuffle — tinted when active
                IconButton(onClick = onToggleShuffle) {
                    Image(
                        painter = painterResource(R.drawable.baseline_shuffle_24),
                        contentDescription = "Shuffle",
                        colorFilter = if (shuffleEnabled)
                            androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        else
                            androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                // Repeat — cycles OFF → REPEAT_ALL → REPEAT_ONE
                IconButton(onClick = onCycleRepeat) {
                    val iconRes = when (repeatMode) {
                        RepeatMode.REPEAT_ONE -> R.drawable.baseline_repeat_one_24
                        else                  -> R.drawable.baseline_repeat_24
                    }
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = "Repeat: $repeatMode",
                        colorFilter = if (repeatMode != RepeatMode.OFF)
                            androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        else
                            androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        }
    }
}

/** Converts milliseconds to a m:ss string for the elapsed-time label. */
private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}