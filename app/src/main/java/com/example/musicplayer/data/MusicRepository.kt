package com.example.musicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.musicplayer.data.db.ExcludedSongEntity
import com.example.musicplayer.data.db.MusicDatabase
import com.example.musicplayer.data.db.PlaylistEntity
import com.example.musicplayer.data.db.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking

// Raw playlist data — song resolution happens in the ViewModel where
// _allSongs is always current, not here where it would be stale.
data class RawPlaylist(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
    val sortOrder: SortOrder = SortOrder.DEFAULT
)

class MusicRepository(private val context: Context) {

    private val dao = MusicDatabase.getInstance(context).playlistDao()

    private val excludedDao = MusicDatabase.getInstance(context).excludedSongDao()

    suspend fun excludeSongFromRecentlyAdded(songId: Long) {
        excludedDao.excludeSong(ExcludedSongEntity(songId))
    }

    suspend fun getExcludedSongIds(): Set<Long> =
        excludedDao.getAllExcludedIds().toSet()

    // -------------------------------------------------------------------------
    // MediaStore observer
    // -------------------------------------------------------------------------

    private var mediaObserver: ContentObserver? = null

    fun registerMediaObserver(onChange: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        mediaObserver = observer
    }

    fun unregisterObserver() {
        mediaObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
    }

    // -------------------------------------------------------------------------
    // Songs — MediaStore
    // -------------------------------------------------------------------------

    fun loadSongs(): List<Song> = querySongs("${MediaStore.Audio.Media.TITLE} ASC")

    fun loadRecentlyAdded(limit: Int = 200): List<Song> =
        querySongs("${MediaStore.Audio.Media.DATE_ADDED} DESC")
            .filter { it.id !in runBlocking { getExcludedSongIds() } }
            .take(limit)

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

        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null, sortOrder
        )?.use { cursor ->
            val idCol        = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            var count = 0
            while (cursor.moveToNext()) {
                if (limit != null && count >= limit) break
                val id = cursor.getLong(idCol)
                songs += Song(
                    id        = id,
                    title     = cursor.getString(titleCol) ?: "Unknown",
                    artist    = cursor.getString(artistCol) ?: "Unknown",
                    album     = cursor.getString(albumCol) ?: "Unknown",
                    duration  = cursor.getLong(durationCol),
                    uri       = Uri.withAppendedPath(collection, id.toString()),
                    dateAdded = cursor.getLong(dateAddedCol)    // ← add
                )
                count++
            }
        }
        return songs
    }

    // -------------------------------------------------------------------------
    // Playlists — Room
    // Returns raw (id, name, songIds) — NO song resolution here.
    // The ViewModel resolves against _allSongs.value on every emission.
    // -------------------------------------------------------------------------

    fun observePlaylists(): Flow<List<RawPlaylist>> =
        dao.observeAllPlaylists().combine(dao.observeAllPlaylistSongs()) { entities, songEntries ->
            val songsByPlaylist = songEntries
                .groupBy { it.playlistId }
                .mapValues { (_, entries) ->
                    entries.sortedBy { it.position }.map { it.songId }
                }
            entities.map { entity ->
                RawPlaylist(
                    id        = entity.id,
                    name      = entity.name,
                    sortOrder = try {
                        SortOrder.valueOf(entity.sortOrder)
                    } catch (e: IllegalArgumentException) {
                        SortOrder.DEFAULT   // safe fallback if DB has unexpected value
                    },
                    songIds   = songsByPlaylist[entity.id] ?: emptyList()
                )
            }
        }

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name))

    suspend fun updatePlaylistSortOrder(playlistId: Long, sortOrder: SortOrder) {
        dao.updateSortOrder(playlistId, sortOrder.name)
    }

    suspend fun deletePlaylist(playlistId: Long) =
        dao.deletePlaylist(playlistId)

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val nextPosition = (dao.getMaxPosition(playlistId) ?: -1) + 1
        dao.insertPlaylistSong(
            PlaylistSongEntity(playlistId = playlistId, songId = songId, position = nextPosition)
        )
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        dao.removePlaylistSong(playlistId, songId)
}