package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.musicplayer.data.Song
import com.example.musicplayer.service.MusicService
import com.example.musicplayer.ui.components.MiniPlayer
import com.example.musicplayer.ui.library.LibraryScreen
import com.example.musicplayer.ui.nowplaying.NowPlayingScreen
import com.example.musicplayer.ui.permission.PermissionScreen
import com.example.musicplayer.ui.playlists.PlaylistsScreen
import com.example.musicplayer.ui.playlistdetail.PlaylistDetailScreen
import com.example.musicplayer.ui.theme.MusicPlayerTheme
import com.example.musicplayer.viewmodel.MusicViewModel

// ---------------------------------------------------------------------------
// Navigation destinations
// ---------------------------------------------------------------------------

sealed class Screen(val route: String, val label: String, val iconRes: Int) {
    object Library    : Screen("library",     "Library",     R.drawable.baseline_library_music_24)
    object Playlists  : Screen("playlists",   "Playlists",   R.drawable.baseline_queue_music_24)
    object NowPlaying : Screen("now_playing", "Now Playing", R.drawable.baseline_play_arrow_24)
}

private val bottomNavItems = listOf(Screen.Library, Screen.Playlists, Screen.NowPlaying)

// ---------------------------------------------------------------------------
// MainActivity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    private var musicService: MusicService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicService.MusicBinder
            musicService = b.getService()
            serviceBound = true
            viewModel.onServiceConnected(b.getService())
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isNightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightNavigationBars = !isNightMode

        Intent(this, MusicService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MusicPlayerTheme {
                MusicPlayerApp(
                    viewModel = viewModel,
                    onRequestPermission = { permission, callback ->
                        onPermissionResult = callback
                        permissionLauncher.launch(permission)
                    },
                    getService = { musicService }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@Composable
fun MusicPlayerApp(
    viewModel: MusicViewModel,
    onRequestPermission: (String, (Boolean) -> Unit) -> Unit,
    getService: () -> MusicService?
) {
    val hasPermission by viewModel.hasPermission.collectAsState()

    if (!hasPermission) {
        PermissionScreen(
            onGrantClick = {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
                onRequestPermission(permission) { granted ->
                    if (granted) viewModel.onPermissionGranted(getService())
                }
            }
        )
        return
    }

    val navController = rememberNavController()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                // Mini player — shown whenever a song is loaded, and we're NOT
                // already on the NowPlaying screen
                val song = currentSong
                if (song != null && currentRoute != Screen.NowPlaying.route) {
                    MiniPlayer(
                        song = song,
                        isPlaying = isPlaying,
                        onPlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::skipToNext,
                        onPrevious = viewModel::skipToPrevious,
                        onClick = {
                            navController.navigate(Screen.NowPlaying.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                    HorizontalDivider()
                }

                // Bottom navigation bar
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    painter = painterResource(screen.iconRes),
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AppNavHost(navController = navController, viewModel = viewModel)
        }
    }
}

// ---------------------------------------------------------------------------
// NavHost
// ---------------------------------------------------------------------------

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: MusicViewModel
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route
    ) {

        composable(Screen.Library.route) {
            LibraryScreen(
                songs = songs,
                searchQuery = searchQuery,
                onSearchChange = viewModel::onSearchQueryChange,
                currentSong = currentSong,
                onSongClick = { song ->
                    viewModel.playSongInContext(song, allSongs)   // songs = filtered/displayed list
                    navController.navigate(Screen.NowPlaying.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                playlists = playlists,
                onAddToPlaylist = { selectedSongs, playlistId ->
                    selectedSongs.forEach { song ->
                        viewModel.addSongToPlaylist(playlistId, song)
                    }
                },
                onCreateAndAddToPlaylist = { name, selectedSongs ->
                    viewModel.createPlaylistAndAdd(name, selectedSongs)
                }
            )
        }

        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                playlists = playlists,
                onPlaylistClick = { playlist ->
                    navController.navigate("playlist/${playlist.id}")
                },
                onCreateClick = { name ->
                    viewModel.createPlaylist(name)
                },
                onDeletePlaylist = { playlist ->
                    viewModel.deletePlaylist(playlist.id)
                }
            )
        }

        composable(Screen.NowPlaying.route) {
            NowPlayingScreen(
                song = currentSong,
                isPlaying = isPlaying,
                progress = progress,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::skipToNext,
                onPrevious = viewModel::skipToPrevious,
                onSeek = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onBack = { navController.popBackStack() }
            )
        }

        composable("playlist/{playlistId}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull()
            val playlist = playlists.find { it.id == id }
            playlist?.let {
                PlaylistDetailScreen(
                    playlist = it,
                    currentSong = currentSong,
                    allPlaylists = playlists,
                    onPlayAll = {
                        viewModel.playPlaylist(it)
                        navController.navigate(Screen.NowPlaying.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSongClick = { song ->
                        viewModel.playSongInContext(song, it.songs)
                        navController.navigate(Screen.NowPlaying.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onRemoveSongs = { songs ->
                        if (it.id == -1L) {
                            viewModel.removeSongsFromRecentlyAdded(songs)
                        } else {
                            viewModel.removeSongsFromPlaylist(it.id, songs)
                        }
                    },
                    onAddSongsToPlaylist = { songs, playlistId ->
                        viewModel.addSongsToPlaylist(playlistId, songs)
                    },
                    onCreatePlaylistAndAdd = { name, songs ->
                        viewModel.createPlaylistAndAdd(name, songs)
                    },
                    onDeletePlaylist = {
                        viewModel.deletePlaylist(it.id)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}