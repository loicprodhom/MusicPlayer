package com.example.musicplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.musicplayer.MainActivity
import com.example.musicplayer.R
import com.example.musicplayer.data.Song

class MusicService : Service() {

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    // -------------------------------------------------------------------------
    // Playback controls
    // -------------------------------------------------------------------------

    fun play(song: Song) {
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, song.uri)
            prepare()
            start()
            setOnCompletionListener {
                // Post a broadcast so the ViewModel can advance the queue.
                // Wire this up in MusicViewModel with a BroadcastReceiver when ready.
                sendBroadcast(Intent(ACTION_SONG_COMPLETED))
            }
        }

        currentSong = song
        startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying = true))
    }

    fun pause() {
        mediaPlayer?.pause()
        currentSong?.let {
            // Update notification to show play button instead of pause
            updateNotification(it, isPlaying = false)
        }
    }

    fun resume() {
        mediaPlayer?.start()
        currentSong?.let {
            updateNotification(it, isPlaying = true)
        }
    }

    fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePlayer()
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW  // low = no sound, still visible
            ).apply {
                description = "Shows the currently playing song"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(song: Song, isPlaying: Boolean): Notification {
        // Tapping the notification reopens the app
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Pause / Play action
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_TOGGLE_PLAYBACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Previous action
        val prevIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Next action
        val nextIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) R.drawable.pause else R.drawable.play_arrow

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(R.drawable.music_note)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.skip_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", toggleIntent)
            .addAction(R.drawable.skip_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2) // prev, toggle, next
            )
            .build()
    }

    private fun updateNotification(song: Song, isPlaying: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(song, isPlaying))
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1

        // Broadcast actions — MusicViewModel should register a receiver for these
        const val ACTION_TOGGLE_PLAYBACK = "com.example.musicplayer.TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS        = "com.example.musicplayer.PREVIOUS"
        const val ACTION_NEXT            = "com.example.musicplayer.NEXT"
        const val ACTION_SONG_COMPLETED  = "com.example.musicplayer.SONG_COMPLETED"
    }
}