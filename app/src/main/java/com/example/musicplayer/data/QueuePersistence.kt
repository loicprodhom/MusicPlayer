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
        private val KEY_QUEUE        = stringPreferencesKey("queue_song_ids")
        private val KEY_INDEX        = intPreferencesKey("queue_index")
        private val KEY_POSITION_MS  = longPreferencesKey("queue_position_ms")
        private val KEY_SHUFFLE      = stringPreferencesKey("shuffle_enabled")
        private val KEY_REPEAT       = stringPreferencesKey("repeat_mode")
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    suspend fun saveQueue(
        queue: List<Song>,
        index: Int,
        positionMs: Int,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode
    ) {
        context.dataStore.edit { prefs ->
            // Store song IDs as a comma-separated string
            prefs[KEY_QUEUE]       = queue.joinToString(",") { it.id.toString() }
            prefs[KEY_INDEX]       = index
            prefs[KEY_POSITION_MS] = positionMs.toLong()
            prefs[KEY_SHUFFLE]     = shuffleEnabled.toString()
            prefs[KEY_REPEAT]      = repeatMode.name
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

        return SavedQueue(
            songIds      = songIds,
            index        = prefs[KEY_INDEX] ?: 0,
            positionMs   = prefs[KEY_POSITION_MS]?.toInt() ?: 0,
            shuffleEnabled = prefs[KEY_SHUFFLE]?.toBooleanStrictOrNull() ?: false,
            repeatMode   = prefs[KEY_REPEAT]?.let {
                runCatching { RepeatMode.valueOf(it) }.getOrDefault(RepeatMode.REPEAT_ALL)
            } ?: RepeatMode.REPEAT_ALL
        )
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

data class SavedQueue(
    val songIds: List<Long>,
    val index: Int,
    val positionMs: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode
)