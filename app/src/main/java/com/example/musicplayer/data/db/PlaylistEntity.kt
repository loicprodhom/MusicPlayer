package com.example.musicplayer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.musicplayer.data.SortOrder

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sortOrder: String = SortOrder.DEFAULT.name
)