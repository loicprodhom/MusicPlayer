package com.example.musicplayer.data

import android.net.Uri
import java.util.concurrent.TimeUnit

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri
) {

    fun formattedDuration(): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun matchesQuery(query: String): Boolean {
        val q = query.lowercase()
        return title.lowercase().contains(q) ||
                artist.lowercase().contains(q) ||
                album.lowercase().contains(q)
    }
}