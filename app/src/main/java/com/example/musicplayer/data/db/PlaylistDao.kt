package com.example.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // -------------------------------------------------------------------------
    // Playlists
    // -------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    // -------------------------------------------------------------------------
    // Songs in playlists
    // -------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removePlaylistSong(playlistId: Long, songId: Long)

    @Query("""
        SELECT songId FROM playlist_songs
        WHERE playlistId = :playlistId
        ORDER BY position ASC
    """)
    suspend fun getSongIdsForPlaylist(playlistId: Long): List<Long>

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Query("""
    SELECT ps.playlistId, ps.songId, ps.position
    FROM playlist_songs ps
    ORDER BY ps.playlistId, ps.position ASC
""")
    fun observeAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>
}

