package com.example.musicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import com.example.musicplayer.data.db.MusicDatabase
import com.example.musicplayer.data.db.PlaylistEntity
import com.example.musicplayer.data.db.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MusicRepository(private val context: Context) {

    private val dao = MusicDatabase.getInstance(context).playlistDao()

    // -------------------------------------------------------------------------
    // Songs — MediaStore
    // -------------------------------------------------------------------------

    fun loadSongs(): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        val resolver: ContentResolver = context.contentResolver

        resolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                songs += Song(
                    id       = id,
                    title    = cursor.getString(titleColumn) ?: "Unknown",
                    artist   = cursor.getString(artistColumn) ?: "Unknown",
                    album    = cursor.getString(albumColumn) ?: "Unknown",
                    duration = cursor.getLong(durationColumn),
                    uri      = Uri.withAppendedPath(collection, id.toString())
                )
            }
        }

        return songs
    }

    // -------------------------------------------------------------------------
    // Playlists — Room
    // -------------------------------------------------------------------------

    /**
     * Emits the full playlist list whenever the DB changes.
     * Songs are resolved by joining with the in-memory song library passed in.
     */
    fun observePlaylists(allSongs: List<Song>): Flow<List<Playlist>> {
        return dao.observeAllPlaylists().map { entities ->
            entities.map { entity ->
                val songIds = dao.getSongIdsForPlaylist(entity.id)
                val songs = songIds.mapNotNull { id -> allSongs.find { it.id == id } }
                Playlist(id = entity.id, name = entity.name, songs = songs)
            }
        }
    }

    suspend fun createPlaylist(name: String): Long {
        return dao.insertPlaylist(PlaylistEntity(name = name))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        dao.deletePlaylist(playlistId)
        // Cascade delete removes playlist_songs rows automatically
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val nextPosition = (dao.getMaxPosition(playlistId) ?: -1) + 1
        dao.insertPlaylistSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId     = songId,
                position   = nextPosition
            )
        )
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        dao.removePlaylistSong(playlistId, songId)
    }
}