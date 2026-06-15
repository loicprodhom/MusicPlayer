package com.example.musicplayer.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.data.MusicRepository
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private var musicService: MusicService? = null

    // -------------------------------------------------------------------------
    // Broadcast receiver — notification buttons & song completion
    // -------------------------------------------------------------------------

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicService.ACTION_TOGGLE_PLAYBACK -> togglePlayPause()
                MusicService.ACTION_PREVIOUS        -> skipToPrevious()
                MusicService.ACTION_NEXT            -> skipToNext()
                MusicService.ACTION_SONG_COMPLETED  -> onSongCompleted()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Permission
    // -------------------------------------------------------------------------

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private fun checkPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(
            getApplication(), permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionGranted(service: MusicService?) {
        _hasPermission.update { true }
        musicService = service
        loadSongs()
    }

    fun onServiceConnected(service: MusicService) {
        musicService = service
        registerReceiver()
        if (_hasPermission.value) loadSongs()
    }

    // -------------------------------------------------------------------------
    // Song library
    // -------------------------------------------------------------------------

    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.update { query }
        applyFilter(query)
    }

    private fun applyFilter(query: String) {
        _songs.update {
            if (query.isBlank()) _allSongs.value
            else _allSongs.value.filter { it.matchesQuery(query) }
        }
    }

    private fun loadSongs() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.loadSongs() }
            _allSongs.update { loaded }
            applyFilter(_searchQuery.value)
            // Start observing playlists now that we have the song list to join against
            observePlaylists()
        }
    }

    // -------------------------------------------------------------------------
    // Playlists — Room-backed
    // -------------------------------------------------------------------------

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private fun observePlaylists() {
        viewModelScope.launch {
            repository.observePlaylists(_allSongs.value).collect { list ->
                _playlists.update { list }
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createPlaylist(name)
            // observePlaylists() Flow emits automatically — no manual update needed
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeSongFromPlaylist(playlistId, song.id)
        }
    }

    // -------------------------------------------------------------------------
    // Playback queue
    // -------------------------------------------------------------------------

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    private val _queueIndex = MutableStateFlow(-1)

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var progressJob: Job? = null

    fun playSong(song: Song) {
        val queue = _allSongs.value
        _queue.update { queue }
        _queueIndex.update { queue.indexOf(song) }
        startPlayback(song)
    }

    fun playPlaylist(playlist: Playlist, startIndex: Int = 0) {
        _queue.update { playlist.songs }
        _queueIndex.update { startIndex }
        playlist.songs.getOrNull(startIndex)?.let { startPlayback(it) }
    }

    fun togglePlayPause() {
        val service = musicService ?: return
        if (service.isPlaying()) {
            service.pause()
            _isPlaying.update { false }
            stopProgressPolling()
        } else {
            service.resume()
            _isPlaying.update { true }
            startProgressPolling()
        }
    }

    fun seekTo(fraction: Float) {
        val service = musicService ?: return
        val positionMs = (fraction * service.getDuration()).toInt()
        service.seekTo(positionMs)
        _progress.update { fraction }
    }

    fun skipToNext() {
        val queue = _queue.value
        val next = (_queueIndex.value + 1).coerceAtMost(queue.size - 1)
        if (next != _queueIndex.value) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        }
    }

    fun skipToPrevious() {
        val service = musicService ?: return
        if (service.getCurrentPosition() > 3_000) {
            service.seekTo(0)
            return
        }
        val queue = _queue.value
        val prev = (_queueIndex.value - 1).coerceAtLeast(0)
        if (prev != _queueIndex.value) {
            _queueIndex.update { prev }
            startPlayback(queue[prev])
        }
    }

    private fun onSongCompleted() {
        val queue = _queue.value
        val next = _queueIndex.value + 1
        if (next < queue.size) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        } else {
            _isPlaying.update { false }
            _progress.update { 0f }
            stopProgressPolling()
        }
    }

    private fun startPlayback(song: Song) {
        musicService?.play(song)
        _currentSong.update { song }
        _isPlaying.update { true }
        startProgressPolling()
    }

    // -------------------------------------------------------------------------
    // Progress polling
    // -------------------------------------------------------------------------

    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = viewModelScope.launch {
            while (true) {
                val service = musicService
                if (service != null && service.isPlaying()) {
                    val pos = service.getCurrentPosition().toFloat()
                    val dur = service.getDuration().toFloat()
                    if (dur > 0f) _progress.update { pos / dur }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    // -------------------------------------------------------------------------
    // Receiver registration
    // -------------------------------------------------------------------------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_TOGGLE_PLAYBACK)
            addAction(MusicService.ACTION_PREVIOUS)
            addAction(MusicService.ACTION_NEXT)
            addAction(MusicService.ACTION_SONG_COMPLETED)
        }
        ContextCompat.registerReceiver(
            getApplication(),
            playbackReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        getApplication<Application>().unregisterReceiver(playbackReceiver)
    }
}