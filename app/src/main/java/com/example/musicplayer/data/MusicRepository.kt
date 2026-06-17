package com.example.musicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.musicplayer.data.db.MusicDatabase
import com.example.musicplayer.data.db.PlaylistEntity
import com.example.musicplayer.data.db.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MusicRepository(private val context: Context) {

    private val dao = MusicDatabase.getInstance(context).playlistDao()

    // -------------------------------------------------------------------------
    // MediaStore observer — fires onChanged() when audio files are added/removed
    // -------------------------------------------------------------------------

    private var mediaObserver: ContentObserver? = null

    /**
     * Register a ContentObserver so the ViewModel can reload songs automatically
     * when the device's audio library changes (new downloads, imports, deletions).
     * Call unregisterObserver() in ViewModel.onCleared().
     */
    fun registerMediaObserver(onChange: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        mediaObserver = observer
    }

    fun unregisterObserver() {
        mediaObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        mediaObserver = null
    }

    // -------------------------------------------------------------------------
    // Songs — MediaStore
    // -------------------------------------------------------------------------

    /**
     * Returns all music files sorted alphabetically.
     * Run on Dispatchers.IO — MediaStore queries block.
     */
    fun loadSongs(): List<Song> {
        return querySongs(
            sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        )
    }

    /**
     * Returns the [limit] most recently added songs, newest first.
     * Used to build the "Recently Added" synthetic playlist.
     */
    fun loadRecentlyAdded(limit: Int = 50): List<Song> {
        return querySongs(
            sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            limit = limit
        )
    }

    private fun querySongs(sortOrder: String, limit: Int? = null): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            var count = 0
            while (cursor.moveToNext()) {
                if (limit != null && count >= limit) break
                val id = cursor.getLong(idCol)
                songs += Song(
                    id       = id,
                    title    = cursor.getString(titleCol) ?: "Unknown",
                    artist   = cursor.getString(artistCol) ?: "Unknown",
                    album    = cursor.getString(albumCol) ?: "Unknown",
                    duration = cursor.getLong(durationCol),
                    uri      = Uri.withAppendedPath(collection, id.toString())
                )
                count++
            }
        }

        return songs
    }

    // -------------------------------------------------------------------------
    // Playlists — Room
    // -------------------------------------------------------------------------

    fun observePlaylists(allSongs: List<Song>): Flow<List<Playlist>> {
        return dao.observeAllPlaylists().map { entities ->
            entities.map { entity ->
                val songIds = dao.getSongIdsForPlaylist(entity.id)
                val songs = songIds.mapNotNull { id -> allSongs.find { it.id == id } }
                Playlist(id = entity.id, name = entity.name, songs = songs)
            }
        }
    }

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name))

    suspend fun deletePlaylist(playlistId: Long) =
        dao.deletePlaylist(playlistId)

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

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        dao.removePlaylistSong(playlistId, songId)
}