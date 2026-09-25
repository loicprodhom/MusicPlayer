package com.example.musicplayer.viewmodel

import android.annotation.SuppressLint
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
import com.example.musicplayer.data.QueuePersistence
import com.example.musicplayer.data.RepeatMode
import com.example.musicplayer.data.Song
import com.example.musicplayer.data.SortOrder
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

private const val RECENTLY_ADDED_ID    = -1L
private const val RECENTLY_ADDED_LIMIT = 200

class MusicViewModel(application: Application) : AndroidViewModel(application),
    MusicService.Listener {

    private val repository       = MusicRepository(application)
    private val queuePersistence = QueuePersistence(application)

    @SuppressLint("StaticFieldLeak")
    private var musicService: MusicService? = null

    // Tracks which playlist the active queue belongs to (null = library)
    private var _activePlaylistId: Long? = null

    // -------------------------------------------------------------------------
    // Broadcast receiver — notification buttons
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
    // MusicService.Listener — direct callbacks from the service
    // -------------------------------------------------------------------------

    override fun onSongCompleted() {
        viewModelScope.launch {
            // Flush deferred removals before advancing
            if (pendingQueueRemovals.isNotEmpty()) {
                val toRemove = pendingQueueRemovals.toSet()
                pendingQueueRemovals.clear()
                val newQueue = _queue.value.filter { it.id !in toRemove }
                _queue.update { newQueue }
                if (_shuffleEnabled.value) {
                    _shuffledQueue.update { sq -> sq.filter { it.id !in toRemove } }
                }
            }

            when (_repeatMode.value) {
                RepeatMode.REPEAT_ONE -> {
                    _currentSong.value?.let { startPlayback(it) }
                }
                RepeatMode.REPEAT_ALL -> {
                    val queue = activeQueue
                    val next  = _queueIndex.value + 1
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

    override fun onSkipToNext()     { skipToNext() }
    override fun onSkipToPrevious() { skipToPrevious() }

    override fun onAudioBecomingNoisy() {
        _isPlaying.update { false }
        stopProgressPolling()
        persistQueue()
    }

    // -------------------------------------------------------------------------
    // Queue restoration
    // -------------------------------------------------------------------------

    private var _restoredPositionMs: Int = 0

    private suspend fun restoreQueueIfAvailable() {
        val saved = withContext(Dispatchers.IO) { queuePersistence.restoreQueue() } ?: return

        val resolvedQueue = saved.songIds.mapNotNull { id ->
            _allSongs.value.find { it.id == id }
        }
        if (resolvedQueue.isEmpty()) return

        val resolvedShuffled = if (saved.shuffledSongIds.isNotEmpty())
            saved.shuffledSongIds.mapNotNull { id -> _allSongs.value.find { it.id == id } }
        else emptyList()

        val song = if (saved.shuffleEnabled && resolvedShuffled.isNotEmpty())
            resolvedShuffled.getOrNull(saved.index) ?: resolvedShuffled.first()
        else
            resolvedQueue.getOrNull(saved.index) ?: resolvedQueue.first()

        _queue.update { resolvedQueue }
        _shuffledQueue.update { resolvedShuffled }
        _queueIndex.update { saved.index }
        _currentSong.update { song }
        _shuffleEnabled.update { saved.shuffleEnabled }
        _repeatMode.update { saved.repeatMode }
        _activePlaylistId    = saved.activePlaylistId
        _isPlaying.update { false }
        _restoredPositionMs  = saved.positionMs
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
        musicService       = service
        service.listener   = this
        registerReceiver()
        if (_hasPermission.value) {
            viewModelScope.launch {
                loadSongsInternal()
                restoreQueueIfAvailable()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Song library
    // -------------------------------------------------------------------------

    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

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

    // Called from the MediaStore observer — fire and forget
    private fun loadSongs() {
        viewModelScope.launch { loadSongsInternal() }
    }

    // Suspend version used by onServiceConnected so restoreQueue runs after songs load
    private suspend fun loadSongsInternal() {
        val loaded = withContext(Dispatchers.IO) { repository.loadSongs() }
        _allSongs.update { loaded }
        applyFilter(_searchQuery.value)
        refreshRecentlyAdded()
        observePlaylists()
    }

    // -------------------------------------------------------------------------
    // Recently Added — synthetic playlist
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

    fun removeSongsFromRecentlyAdded(songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { repository.excludeSongFromRecentlyAdded(it.id) }
            refreshRecentlyAdded()
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
            repository.observePlaylists().collect { rawList ->
                val resolved = rawList.map { raw ->
                    Playlist(
                        id        = raw.id,
                        name      = raw.name,
                        sortOrder = raw.sortOrder,
                        songs     = raw.songIds.mapNotNull { songId ->
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
            songs.forEach { repository.addSongToPlaylist(newId, it.id) }
        }
    }

    fun updatePlaylistSortOrder(playlistId: Long, sortOrder: SortOrder) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePlaylistSortOrder(playlistId, sortOrder)
        }

        // Reorder the active queue if it belongs to this playlist
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return
        val playlist = _userPlaylists.value.find { it.id == playlistId } ?: return
        val playlistSongIds = playlist.songs.map { it.id }.toSet()
        val queueSongIds    = currentQueue.map { it.id }.toSet()
        if (playlistSongIds != queueSongIds) return

        val reorderedQueue = when (sortOrder) {
            SortOrder.DEFAULT        -> playlist.songs
            SortOrder.ALPHABETICAL   -> playlist.songs.sortedBy { it.title.lowercase() }
            SortOrder.DATE_ASCENDING -> playlist.songs.sortedBy { it.dateAdded }
        }
        val newIndex = reorderedQueue.indexOfFirst { it.id == _currentSong.value?.id }
        _queue.update { reorderedQueue }
        _queueIndex.update { if (newIndex >= 0) newIndex else 0 }
        if (_shuffleEnabled.value) buildShuffledQueue(preserveCurrent = true)
    }

    fun deletePlaylist(playlistId: Long) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) { repository.deletePlaylist(playlistId) }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) { repository.addSongToPlaylist(playlistId, song.id) }
        appendSongsToQueueIfActive(playlistId, listOf(song))
    }

    fun addSongsToPlaylist(playlistId: Long, songs: Set<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { repository.addSongToPlaylist(playlistId, it.id) }
        }
        appendSongsToQueueIfActive(playlistId, songs.toList())
    }

    fun removeSongsFromPlaylist(playlistId: Long, songs: Set<Song>) {
        if (playlistId == RECENTLY_ADDED_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            songs.forEach { repository.removeSongFromPlaylist(playlistId, it.id) }
        }
        removeSongsFromQueueIfActive(playlistId, songs)
    }

    private fun appendSongsToQueueIfActive(playlistId: Long, songs: List<Song>) {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return
        val playlist = _userPlaylists.value.find { it.id == playlistId } ?: return
        val playlistSongIds = playlist.songs.map { it.id }.toSet()
        val queueSongIds    = currentQueue.map { it.id }.toSet()
        if (playlistSongIds != queueSongIds) return
        val newSongs = songs.filter { song -> currentQueue.none { it.id == song.id } }
        if (newSongs.isEmpty()) return
        _queue.update { currentQueue + newSongs }
        if (_shuffleEnabled.value) {
            _shuffledQueue.update { _shuffledQueue.value + newSongs.shuffled() }
        }
    }

    private fun removeSongsFromQueueIfActive(playlistId: Long, songs: Set<Song>) {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return
        val playlist = _userPlaylists.value.find { it.id == playlistId } ?: return
        val removedIds      = songs.map { it.id }.toSet()
        val playlistSongIds = playlist.songs.map { it.id }.toSet()
        val queueSongIds    = currentQueue.map { it.id }.toSet()
        if (queueSongIds != playlistSongIds && queueSongIds != (playlistSongIds + removedIds)) return

        val currentSong = _currentSong.value
        val (deferred, removable) = songs.partition { it.id == currentSong?.id }

        if (removable.isNotEmpty()) {
            val removableIds = removable.map { it.id }.toSet()
            val newQueue     = currentQueue.filter { it.id !in removableIds }
            val newIndex     = newQueue.indexOfFirst { it.id == currentSong?.id }
                .takeIf { it >= 0 } ?: _queueIndex.value.coerceAtMost(newQueue.size - 1)
            _queue.update { newQueue }
            _queueIndex.update { newIndex }
            if (_shuffleEnabled.value) {
                _shuffledQueue.update { sq -> sq.filter { it.id !in removableIds } }
            }
        }

        if (deferred.isNotEmpty()) {
            pendingQueueRemovals.addAll(deferred.map { it.id })
        }
    }

    private val pendingQueueRemovals = mutableSetOf<Long>()

    // -------------------------------------------------------------------------
    // Shuffle & repeat
    // -------------------------------------------------------------------------

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.REPEAT_ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _queue         = MutableStateFlow<List<Song>>(emptyList())
    private val _shuffledQueue = MutableStateFlow<List<Song>>(emptyList())
    private val _queueIndex    = MutableStateFlow(-1)

    private val activeQueue get() =
        if (_shuffleEnabled.value) _shuffledQueue.value else _queue.value

    fun toggleShuffle() {
        val enabling = !_shuffleEnabled.value
        _shuffleEnabled.update { enabling }
        if (enabling) {
            buildShuffledQueue(preserveCurrent = true)
        } else {
            // Re-apply the playlist's persisted sort order when turning shuffle off
            val playlist = _activePlaylistId?.let { id ->
                _userPlaylists.value.find { it.id == id }
            }
            val sortedQueue = if (playlist != null) {
                when (playlist.sortOrder) {
                    SortOrder.DEFAULT        -> playlist.songs
                    SortOrder.ALPHABETICAL   -> playlist.songs.sortedBy { it.title.lowercase() }
                    SortOrder.DATE_ASCENDING -> playlist.songs.sortedBy { it.dateAdded }
                }
            } else {
                _queue.value
            }
            val newIndex = sortedQueue.indexOfFirst { it.id == _currentSong.value?.id }
            _queue.update { sortedQueue }
            _queueIndex.update { if (newIndex >= 0) newIndex else 0 }
        }
        persistQueue()
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

    private fun buildShuffledQueue(preserveCurrent: Boolean = false) {
        val original = _queue.value.toMutableList()
        val current  = _currentSong.value
        if (preserveCurrent && current != null) {
            original.remove(current)
            original.shuffle()
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
        _activePlaylistId = null
        val queue = _allSongs.value
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

    fun playSongInContext(song: Song, queue: List<Song>, playlistId: Long? = null) {
        _activePlaylistId = playlistId
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
        _activePlaylistId = playlist.id
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
            persistQueue()
        } else {
            val current = _currentSong.value
            if (current != null && !service.isPlaying() && service.getDuration() == 0) {
                // Cold resume after restore — start player and seek to saved position
                val positionToSeek  = _restoredPositionMs
                _restoredPositionMs = 0
                stopProgressPolling()
                service.play(current)
                _isPlaying.update { true }
                startProgressPolling()
                if (positionToSeek > 0) service.seekTo(positionToSeek)
            } else {
                service.resume()
                _isPlaying.update { true }
                startProgressPolling()
            }
        }
    }

    fun seekTo(fraction: Float) {
        val service    = musicService ?: return
        val positionMs = (fraction * service.getDuration()).toInt()
        service.seekTo(positionMs)
        _progress.update { fraction }
    }

    fun skipToNext() {
        val queue = activeQueue
        val next  = _queueIndex.value + 1
        if (next < queue.size) {
            _queueIndex.update { next }
            startPlayback(queue[next])
        } else {
            when (_repeatMode.value) {
                RepeatMode.REPEAT_ALL -> {
                    if (_shuffleEnabled.value) buildShuffledQueue(preserveCurrent = false)
                    _queueIndex.update { 0 }
                    activeQueue.getOrNull(0)?.let { startPlayback(it) }
                }
                else -> {
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
        val prev  = (_queueIndex.value - 1).coerceAtLeast(0)
        if (prev != _queueIndex.value) {
            _queueIndex.update { prev }
            startPlayback(queue[prev])
        }
    }

    private fun advanceQueue() {
        val queue = activeQueue
        val next  = _queueIndex.value + 1
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
        stopProgressPolling()
        musicService?.play(song)
        _currentSong.update { song }
        _isPlaying.update { true }
        startProgressPolling()
        persistQueue()
    }

    private fun persistQueue() {
        val service = musicService ?: return
        viewModelScope.launch(Dispatchers.IO) {
            queuePersistence.saveQueue(
                queue            = _queue.value,
                shuffledQueue    = _shuffledQueue.value,
                index            = _queueIndex.value,
                positionMs       = service.getCurrentPosition(),
                shuffleEnabled   = _shuffleEnabled.value,
                repeatMode       = _repeatMode.value,
                activePlaylistId = _activePlaylistId
            )
        }
    }

    // -------------------------------------------------------------------------
    // Progress polling
    // -------------------------------------------------------------------------

    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(500)
                if (_isPlaying.value) {
                    val service = musicService ?: continue
                    val dur     = service.getDuration()
                    if (dur > 0) {
                        _progress.update {
                            service.getCurrentPosition().toFloat() / dur.toFloat()
                        }
                    }
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
        }
        ContextCompat.registerReceiver(
            getApplication(),
            playbackReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        repository.registerMediaObserver { loadSongs() }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        musicService?.listener = null
        repository.unregisterObserver()
        getApplication<Application>().unregisterReceiver(playbackReceiver)
    }
}