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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.musicplayer.MainActivity
import com.example.musicplayer.R
import com.example.musicplayer.data.Song

class MusicService : Service() {

    // -------------------------------------------------------------------------
    // Callback interface
    // -------------------------------------------------------------------------

    interface Listener {
        fun onSongCompleted()
        fun onPlaybackError()
    }

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPreparing = false
    private var pendingSeekMs: Int? = null

    var listener: Listener? = null

    // -------------------------------------------------------------------------
    // MediaSession — drives lock screen controls
    // -------------------------------------------------------------------------

    private lateinit var mediaSession: MediaSessionCompat

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaSession.release()
        releasePlayer()
    }

    // -------------------------------------------------------------------------
    // MediaSession setup
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerSession").apply {
            // Handle lock screen / Bluetooth / headset button actions
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()     { resume() }
                override fun onPause()    { pause() }
                override fun onSkipToNext()     { sendBroadcast(Intent(ACTION_NEXT)) }
                override fun onSkipToPrevious() { sendBroadcast(Intent(ACTION_PREVIOUS)) }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop()     { stop() }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
    }

    @Suppress("DEPRECATION")
    private fun updateMediaSessionMetadata(song: Song) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .build()
        )
    }

    @Suppress("DEPRECATION")
    private fun updatePlaybackState(isPlaying: Boolean, positionMs: Long = 0L) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, positionMs, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
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
        updateMediaSessionMetadata(song)
        updatePlaybackState(isPlaying = false)

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, song.uri)
            setOnPreparedListener { mp ->
                isPreparing = false
                pendingSeekMs?.let { mp.seekTo(it) }
                pendingSeekMs = null
                mp.start()
                updatePlaybackState(isPlaying = true, positionMs = 0L)
                startForeground(NOTIFICATION_ID, buildNotification(song, isPlaying = true))
            }
            setOnCompletionListener {
                updatePlaybackState(isPlaying = false)
                handler.post { listener?.onSongCompleted() }
            }
            setOnErrorListener { _, _, _ ->
                isPreparing = false
                pendingSeekMs = null
                updatePlaybackState(isPlaying = false)
                handler.post { listener?.onPlaybackError() }
                true
            }
            prepareAsync()
        }

        currentSong = song
    }

    fun pause() {
        mediaPlayer?.pause()
        updatePlaybackState(
            isPlaying = false,
            positionMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
        )
        currentSong?.let { updateNotification(it, isPlaying = false) }
    }

    fun resume() {
        mediaPlayer?.start()
        updatePlaybackState(
            isPlaying = true,
            positionMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
        )
        currentSong?.let { updateNotification(it, isPlaying = true) }
    }

    fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        updatePlaybackState(isPlaying = false)
        releasePlayer()
    }

    fun seekTo(positionMs: Int) {
        if (isPreparing) {
            pendingSeekMs = positionMs
        } else {
            mediaPlayer?.seekTo(positionMs)
            updatePlaybackState(
                isPlaying = mediaPlayer?.isPlaying == true,
                positionMs = positionMs.toLong()
            )
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
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionToken)  // ← links to lock screen
            )
            .build()
    }

    private fun updateNotification(song: Song, isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(song, isPlaying))
    }

    // -------------------------------------------------------------------------
    // Constants
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