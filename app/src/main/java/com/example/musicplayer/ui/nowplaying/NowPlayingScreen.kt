package com.example.musicplayer.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicplayer.R
import com.example.musicplayer.data.Song
import com.example.musicplayer.ui.components.SimpleAppBar

@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {

    Column(modifier = Modifier.fillMaxSize()) {

        SimpleAppBar(title = "Now Playing")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(song?.title ?: "No Song",
                style = MaterialTheme.typography.titleLarge)

            Text(song?.artist ?: "")

            Spacer(modifier = Modifier.height(16.dp))

            Slider(value = progress, onValueChange = {})

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()) {

                IconButton(onClick = onPrevious) {
                    Image(painterResource(R.drawable.skip_previous), null)
                }

                IconButton(onClick = onPlayPause) {
                    Image(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause
                            else R.drawable.play_arrow
                        ),
                        contentDescription = null
                    )
                }

                IconButton(onClick = onNext) {
                    Image(painterResource(R.drawable.skip_next), null)
                }
            }
        }
    }
}