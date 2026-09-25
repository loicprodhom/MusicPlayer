package com.example.musicplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "queue")

class QueuePersistence(private val context: Context) {

    companion object {
        private val KEY_QUEUE            = stringPreferencesKey("queue_song_ids")
        private val KEY_SHUFFLED_QUEUE   = stringPreferencesKey("shuffled_queue_song_ids")
        private val KEY_INDEX            = intPreferencesKey("queue_index")
        private val KEY_POSITION_MS      = longPreferencesKey("queue_position_ms")
        private val KEY_SHUFFLE          = stringPreferencesKey("shuffle_enabled")
        private val KEY_REPEAT           = stringPreferencesKey("repeat_mode")
        private val KEY_ACTIVE_PLAYLIST  = longPreferencesKey("active_playlist_id")
        private val KEY_IS_PLAYING = stringPreferencesKey("is_playing")
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    suspend fun saveQueue(
        queue: List<Song>,
        shuffledQueue: List<Song>,
        index: Int,
        positionMs: Int,
        isPlaying: Boolean,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
        activePlaylistId: Long?
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_QUEUE]          = queue.joinToString(",") { it.id.toString() }
            prefs[KEY_SHUFFLED_QUEUE] = shuffledQueue.joinToString(",") { it.id.toString() }
            prefs[KEY_INDEX]          = index
            prefs[KEY_POSITION_MS]    = positionMs.toLong()
            prefs[KEY_IS_PLAYING]     = isPlaying.toString()
            prefs[KEY_SHUFFLE]        = shuffleEnabled.toString()
            prefs[KEY_REPEAT]         = repeatMode.name
            if (activePlaylistId != null) {
                prefs[KEY_ACTIVE_PLAYLIST] = activePlaylistId
            } else {
                prefs.remove(KEY_ACTIVE_PLAYLIST)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Restore
    // -------------------------------------------------------------------------

    suspend fun restoreQueue(): SavedQueue? {
        val prefs = context.dataStore.data.first()
        val idsRaw = prefs[KEY_QUEUE] ?: return null
        if (idsRaw.isBlank()) return null

        val songIds = idsRaw.split(",").mapNotNull { it.toLongOrNull() }
        if (songIds.isEmpty()) return null

        val shuffledRaw = prefs[KEY_SHUFFLED_QUEUE] ?: ""
        val shuffledIds = if (shuffledRaw.isBlank()) emptyList()
        else shuffledRaw.split(",").mapNotNull { it.toLongOrNull() }

        return SavedQueue(
            songIds         = songIds,
            shuffledSongIds = shuffledIds,
            index           = prefs[KEY_INDEX] ?: 0,
            positionMs      = prefs[KEY_POSITION_MS]?.toInt() ?: 0,
            isPlaying        = prefs[KEY_IS_PLAYING]?.toBooleanStrictOrNull() ?: false,
            shuffleEnabled  = prefs[KEY_SHUFFLE]?.toBooleanStrictOrNull() ?: false,
            repeatMode      = prefs[KEY_REPEAT]?.let {
                runCatching { RepeatMode.valueOf(it) }.getOrDefault(RepeatMode.REPEAT_ALL)
            } ?: RepeatMode.REPEAT_ALL,
            activePlaylistId = prefs[KEY_ACTIVE_PLAYLIST]   // null if not set
        )
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

data class SavedQueue(
    val songIds: List<Long>,
    val shuffledSongIds: List<Long>,
    val index: Int,
    val positionMs: Int,
    val isPlaying: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val activePlaylistId: Long?
)