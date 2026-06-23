package com.example.musicplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        ExcludedSongEntity::class    // ← add this
    ],
    version = 2,                     // ← bump from 1 to 2
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun excludedSongDao(): ExcludedSongDao    // ← add this

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_player.db"
                )
                    .addMigrations(MIGRATION_1_2)    // ← add this
                    .build().also { INSTANCE = it }
            }
        }

        // Migration so existing playlists are preserved
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS excluded_songs (songId INTEGER NOT NULL, PRIMARY KEY(songId))"
                )
            }
        }
    }
}