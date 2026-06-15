package com.example.musicplayer.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.data.MusicRepository
import com.example.musicplayer.data.Playlist
import com.example.musicplayer.data.Song
import com.example.musicplayer.service.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    // Reference to the bound service — set by MainActivity once bound
    private var musicService: MusicService? = null

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

    /** Called by MainActivity after the user grants storage permission. */
    fun onPermissionGranted(service: MusicService?) {
        _hasPermission.update { true }
        musicService = service
        loadSongs()
    }

    /** Called by MainActivity once the ServiceConnection is established. */
    fun onServiceConnected(service: MusicService) {
        musicService = service
        // If permission was already granted (returning user), load songs now.
        // If not, onPermissionGranted() will call loadSongs() after the grant.
        if (_hasPermission.value) {
            loadSongs()
        }
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
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.loadSongs()
            }
            _allSongs.update { loaded }
            applyFilter(_searchQuery.value)
        }
    }

    // -------------------------------------------------------------------------
    // Playlists  (in-memory for now — swap for Room later)
    // -------------------------------------------------------------------------

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun createPlaylist(name: String) {
        val newPlaylist = Playlist(
            id = System.currentTimeMillis(),
            name = name,
            songs = emptyList()
        )
        _playlists.update { it + newPlaylist }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        _playlists.update { list ->
            list.map { playlist ->
                if (playlist.id == playlistId)
                    playlist.copy(songs = playlist.songs + song)
                else playlist
            }
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

    // 0f..1f fraction used by the Slider in NowPlayingScreen
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var progressJob: Job? = null

    /** Play a single song, setting the full library as the queue. */
    fun playSong(song: Song) {
        val queue = _allSongs.value
        val index = queue.indexOf(song)
        _queue.update { queue }
        _queueIndex.update { index }
        startPlayback(song)
    }

    /** Play all songs from a playlist. */
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

    fun skipToNext() {
        val queue = _queue.value
        val next = (_queueIndex.value + 1).coerceAtMost(queue.size - 1)
        if (next != _queueIndex.value) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        }
    }

    fun skipToPrevious() {
        val service = musicService
        // If more than 3 s in, restart current track instead of going back
        if (service != null && service.getCurrentPosition() > 3_000) {
            service.resume()
            musicService?.let {
                // seek to 0 — add seekTo() to MusicService when ready
            }
            return
        }
        val queue = _queue.value
        val prev = (_queueIndex.value - 1).coerceAtLeast(0)
        if (prev != _queueIndex.value) {
            _queueIndex.update { prev }
            startPlayback(queue[prev])
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
    // Polls the service every 500 ms and converts position → 0f..1f
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
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
    }
}