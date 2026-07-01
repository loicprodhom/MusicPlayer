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
import com.example.musicplayer.data.RepeatMode
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

// Sentinel playlist ID for the synthetic "Recently Added" playlist
private const val RECENTLY_ADDED_ID = -1L
private const val RECENTLY_ADDED_LIMIT = 200

class MusicViewModel(application: Application) : AndroidViewModel(application), MusicService.Listener {

    private val repository = MusicRepository(application)
    private var musicService: MusicService? = null

    // -------------------------------------------------------------------------
    // Broadcast receiver
    // -------------------------------------------------------------------------

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicService.ACTION_TOGGLE_PLAYBACK -> togglePlayPause()
                MusicService.ACTION_PREVIOUS        -> skipToPrevious()
                MusicService.ACTION_NEXT            -> skipToNext()
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
        service.listener = this          // register direct callback
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
            refreshRecentlyAdded()
            observePlaylists()
        }
    }

    // -------------------------------------------------------------------------
    // Recently Added — synthetic playlist, refreshed on every library load
    // -------------------------------------------------------------------------

    private val _recentlyAdded = MutableStateFlow(
        Playlist(id = RECENTLY_ADDED_ID, name = "Recently Added", songs = emptyList())
    )
    val recentlyAdded: StateFlow<Playlist> = _recentlyAdded.asStateFlow()

    private suspend fun refreshRecentlyAdded() {
        val recent = withContext(Dispatchers.IO) {
            repository.loadRecentlyAdded(RECENTLY_ADDED_LIMIT)
        }
        _recentlyAdded.update {
            Playlist(id = RECENTLY_ADDED_ID, name = "Recently Added", songs = recent)
        }
    }

    // -------------------------------------------------------------------------
    // Playlists — Room-backed, with Recently Added prepended
    // -------------------------------------------------------------------------

    private val _userPlaylists = MutableStateFlow<List<Playlist>>(emptyList())

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private fun rebuildPlaylistList() {
        _playlists.update { listOf(_recentlyAdded.value) + _userPlaylists.value }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            // observePlaylists() now returns RawPlaylist(id, name, songIds).
            // We resolve songIds against _allSongs.value INSIDE the collect lambda,
            // so every Room emission uses the current song list — never a stale snapshot.
            repository.observePlaylists().collect { rawList ->
                val resolved = rawList.map { raw ->
                    Playlist(
                        id    = raw.id,
                        name  = raw.name,
                        songs = raw.songIds.mapNotNull { songId ->
                            _allSongs.value.find { it.id == songId }
                        }
                    )
                }
                _userPlaylists.update { resolved }
                rebuildPlaylistList()
            }
        }
        viewModelScope.launch {
            _recentlyAdded.collect { rebuildPlaylistList() }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.createPlaylist(name) }
    }

    fun createPlaylistAndAdd(name: String, songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = repository.createPlaylist(name)
            songs.forEach { song -> repository.addSongToPlaylist(newId, song.id) }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) { repository.deletePlaylist(playlistId) }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { song -> repository.addSongToPlaylist(playlistId, song.id) }
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: Song) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeSongFromPlaylist(playlistId, song.id)
        }
    }

    fun removeSongsFromRecentlyAdded(songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { song ->
                repository.excludeSongFromRecentlyAdded(song.id)
            }
            // Refresh the playlist immediately so the UI updates
            refreshRecentlyAdded()
        }
    }

    fun removeSongsFromPlaylist(playlistId: Long, songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { song -> repository.removeSongFromPlaylist(playlistId, song.id) }
        }
    }

    // -------------------------------------------------------------------------
    // Shuffle
    //
    // We maintain two parallel queues:
    //   _queue        — original order
    //   _shuffledQueue — Fisher-Yates shuffle, guaranteed no repeats until all
    //                    songs have played; reshuffled when repeat-all wraps
    //
    // _queueIndex always refers to the position in whichever queue is active.
    // -------------------------------------------------------------------------

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.REPEAT_ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    private val _shuffledQueue = MutableStateFlow<List<Song>>(emptyList())
    private val _queueIndex = MutableStateFlow(-1)

    /** The queue the player actually navigates — shuffled or original. */
    private val activeQueue get() =
        if (_shuffleEnabled.value) _shuffledQueue.value else _queue.value

    fun toggleShuffle() {
        val enabling = !_shuffleEnabled.value
        _shuffleEnabled.update { enabling }
        if (enabling) {
            buildShuffledQueue(preserveCurrent = true)
        }
    }

    fun cycleRepeatMode() {
        _repeatMode.update {
            when (it) {
                RepeatMode.OFF        -> RepeatMode.REPEAT_ALL
                RepeatMode.REPEAT_ALL -> RepeatMode.REPEAT_ONE
                RepeatMode.REPEAT_ONE -> RepeatMode.OFF
            }
        }
    }

    /**
     * Fisher-Yates shuffle.
     * When [preserveCurrent] is true, the current song is pinned to index 0
     * so playback continues uninterrupted; remaining songs are shuffled after it.
     */
    private fun buildShuffledQueue(preserveCurrent: Boolean = false) {
        val original = _queue.value.toMutableList()
        val current = _currentSong.value

        if (preserveCurrent && current != null) {
            original.remove(current)
            original.shuffle()                      // Fisher-Yates via Kotlin stdlib
            _shuffledQueue.update { listOf(current) + original }
            _queueIndex.update { 0 }
        } else {
            original.shuffle()
            _shuffledQueue.update { original }
            _queueIndex.update { 0 }
        }
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

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
        if (_shuffleEnabled.value) {
            buildShuffledQueue(preserveCurrent = false)
            // Place the tapped song at index 0 so it plays first
            val shuffled = _shuffledQueue.value.toMutableList()
            shuffled.remove(song)
            _shuffledQueue.update { listOf(song) + shuffled }
            _queueIndex.update { 0 }
        } else {
            _queueIndex.update { queue.indexOf(song) }
        }
        startPlayback(song)
    }

    fun playSongInContext(song: Song, queue: List<Song>) {
        _queue.update { queue }
        if (_shuffleEnabled.value) {
            buildShuffledQueue(preserveCurrent = false)
            val shuffled = _shuffledQueue.value.toMutableList()
            shuffled.remove(song)
            _shuffledQueue.update { listOf(song) + shuffled }
            _queueIndex.update { 0 }
        } else {
            _queueIndex.update { queue.indexOf(song) }
        }
        startPlayback(song)
    }

    fun playPlaylist(playlist: Playlist, startIndex: Int = 0) {
        _queue.update { playlist.songs }
        if (_shuffleEnabled.value) {
            buildShuffledQueue(preserveCurrent = false)
        } else {
            _queueIndex.update { startIndex }
        }
        val song = activeQueue.getOrNull(_queueIndex.value) ?: return
        startPlayback(song)
    }

    fun togglePlayPause() {
        val service = musicService ?: return
        if (_isPlaying.value) {
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
        val queue = activeQueue
        val next = _queueIndex.value + 1
        if (next < queue.size) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        } else {
            // End of queue
            when (_repeatMode.value) {
                RepeatMode.REPEAT_ALL -> {
                    if (_shuffleEnabled.value) buildShuffledQueue(preserveCurrent = false)
                    _queueIndex.update { 0 }
                    activeQueue.getOrNull(0)?.let { startPlayback(it) }
                }
                else -> {
                    // REPEAT_ONE handled by the service, OFF stops here
                    _isPlaying.update { false }
                    _progress.update { 0f }
                    stopProgressPolling()
                }
            }
        }
    }

    fun skipToPrevious() {
        val service = musicService ?: return
        if (service.getCurrentPosition() > 3_000) {
            service.seekTo(0)
            return
        }
        val queue = activeQueue
        val prev = (_queueIndex.value - 1).coerceAtLeast(0)
        if (prev != _queueIndex.value) {
            _queueIndex.update { prev }
            startPlayback(queue[prev])
        }
    }

    override fun onSongCompleted() {
        // Called directly from MusicService via the Listener interface.
        // Runs on the main thread (service posts it via handler.post).
        viewModelScope.launch {
            when (_repeatMode.value) {
                RepeatMode.REPEAT_ONE -> {
                    _currentSong.value?.let { startPlayback(it) }
                }
                RepeatMode.REPEAT_ALL -> {
                    val queue = activeQueue
                    val next = _queueIndex.value + 1
                    if (next < queue.size) {
                        _queueIndex.update { next }
                        startPlayback(queue[next])
                    } else {
                        if (_shuffleEnabled.value) buildShuffledQueue(preserveCurrent = false)
                        _queueIndex.update { 0 }
                        activeQueue.getOrNull(0)?.let { startPlayback(it) }
                    }
                }
                RepeatMode.OFF -> advanceQueue()
            }
        }
    }

    override fun onPlaybackError() {
        _isPlaying.update { false }
        stopProgressPolling()
    }

    override fun onSkipToNext() {
        skipToNext()
    }

    override fun onSkipToPrevious() {
        skipToPrevious()
    }

    /** Move to the next song, stopping at end of queue if repeat is off. */
    private fun advanceQueue() {
        val queue = activeQueue
        val next = _queueIndex.value + 1
        if (next < queue.size) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        } else {
            // End of queue
            _isPlaying.update { false }
            _progress.update { 0f }
            stopProgressPolling()
        }
    }

    private fun startPlayback(song: Song) {
        stopProgressPolling()
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
                delay(500)
                // Only poll if ViewModel believes we are playing
                if (_isPlaying.value) {
                    val service = musicService ?: continue
                    val dur = service.getDuration()
                    if (dur > 0) {
                        _progress.update { service.getCurrentPosition().toFloat() / dur.toFloat() }
                    }
                    // If service reports not playing but ViewModel thinks it is,
                    // it means prepareAsync hasn't completed yet — just wait
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    // -------------------------------------------------------------------------
    // Receiver + observer registration
    // -------------------------------------------------------------------------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_TOGGLE_PLAYBACK)
            addAction(MusicService.ACTION_PREVIOUS)
            addAction(MusicService.ACTION_NEXT)
            // Completion and error now go through MusicService.Listener directly
        }
        ContextCompat.registerReceiver(
            getApplication(),
            playbackReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        repository.registerMediaObserver { loadSongs() }
    }

    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        musicService?.listener = null    // avoid leaking the ViewModel
        repository.unregisterObserver()
        getApplication<Application>().unregisterReceiver(playbackReceiver)
    }
}