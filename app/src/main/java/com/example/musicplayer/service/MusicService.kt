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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.musicplayer.MainActivity
import com.example.musicplayer.R
import com.example.musicplayer.data.Song

class MusicService : Service() {

    // -------------------------------------------------------------------------
    // Callback interface — ViewModel implements this instead of using broadcasts
    // for time-critical events like song completion
    // -------------------------------------------------------------------------

    interface Listener {
        fun onSongCompleted()
        fun onPlaybackError()
    }

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private val handler = Handler(Looper.getMainLooper())

    // Set by the ViewModel once the service is bound
    var listener: Listener? = null

    private var isPreparing = false
    private var pendingSeekMs: Int? = null

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
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // -------------------------------------------------------------------------
    // Playback controls
    // -------------------------------------------------------------------------

    fun play(song: Song) {
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        isPreparing = false
        pendingSeekMs = null
        handler.post { oldPlayer?.release() }

        isPreparing = true

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, song.uri)
            setOnPreparedListener { mp ->
                isPreparing = false
                // Apply any seek that arrived during preparation
                pendingSeekMs?.let { mp.seekTo(it) }
                pendingSeekMs = null
                mp.start()
                startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying = true))
            }
            setOnCompletionListener {
                handler.post { listener?.onSongCompleted() }
            }
            setOnErrorListener { _, _, _ ->
                isPreparing = false
                pendingSeekMs = null
                handler.post { listener?.onPlaybackError() }
                true
            }
            prepareAsync()
        }

        currentSong = song
    }

    fun pause() {
        mediaPlayer?.pause()
        currentSong?.let { updateNotification(it, isPlaying = false) }
    }

    fun resume() {
        mediaPlayer?.start()
        currentSong?.let { updateNotification(it, isPlaying = true) }
    }

    fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePlayer()
    }

    fun seekTo(positionMs: Int) {
        if (isPreparing) {
            pendingSeekMs = positionMs   // defer until onPrepared
        } else {
            mediaPlayer?.seekTo(positionMs)
        }
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
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows the currently playing song" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(song: Song, isPlaying: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_TOGGLE_PLAYBACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) R.drawable.baseline_pause_24
        else R.drawable.baseline_play_arrow_24

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(R.drawable.baseline_music_note_24)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.baseline_skip_previous_24, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", toggleIntent)
            .addAction(R.drawable.baseline_skip_next_24, "Next", nextIntent)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateNotification(song: Song, isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(song, isPlaying))
    }

    // -------------------------------------------------------------------------
    // Constants — only broadcast actions remain here; completion uses listener
    // -------------------------------------------------------------------------

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_TOGGLE_PLAYBACK = "com.example.musicplayer.TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS        = "com.example.musicplayer.PREVIOUS"
        const val ACTION_NEXT            = "com.example.musicplayer.NEXT"
        const val ACTION_PLAYBACK_ERROR  = "com.example.musicplayer.PLAYBACK_ERROR"
    }
}