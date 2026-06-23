package com.example.musicplayer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "excluded_songs")
data class ExcludedSongEntity(
    @PrimaryKey val songId: Long
)