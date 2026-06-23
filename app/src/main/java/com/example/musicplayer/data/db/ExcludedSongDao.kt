package com.example.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExcludedSongDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun excludeSong(entity: ExcludedSongEntity)

    @Query("SELECT songId FROM excluded_songs")
    suspend fun getAllExcludedIds(): List<Long>
}