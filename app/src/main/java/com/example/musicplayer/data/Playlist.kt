package com.example.musicplayer.data

data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song>
) {
    fun songCount(): Int = songs.size

    fun totalDuration(): Long =
        songs.sumOf { it.duration }
}