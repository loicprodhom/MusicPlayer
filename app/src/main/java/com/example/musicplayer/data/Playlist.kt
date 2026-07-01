package com.example.musicplayer.data

data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song>,
    val sortOrder: SortOrder = SortOrder.DEFAULT
) {
    fun songCount(): Int = songs.size

    fun totalDuration(): Long =
        songs.sumOf { it.duration }
}