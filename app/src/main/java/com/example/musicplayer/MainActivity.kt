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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.musicplayer.data.Song
import com.example.musicplayer.service.MusicService
import com.example.musicplayer.ui.library.LibraryScreen
import com.example.musicplayer.ui.nowplaying.NowPlayingScreen
import com.example.musicplayer.ui.permission.PermissionScreen
import com.example.musicplayer.ui.playlistdetail.PlaylistDetailScreen
import com.example.musicplayer.ui.playlists.PlaylistsScreen
import com.example.musicplayer.ui.theme.MusicPlayerTheme
import com.example.musicplayer.viewmodel.MusicViewModel

// ---------------------------------------------------------------------------
// Navigation destinations
// ---------------------------------------------------------------------------

sealed class Screen(val route: String, val label: String, val iconRes: Int) {
    object Library : Screen("library", "Library", R.drawable.music_note)
    object Playlists : Screen("playlists", "Playlists", R.drawable.music_note)
    object NowPlaying : Screen("now_playing", "Now Playing", R.drawable.play_arrow)
}

private val bottomNavItems = listOf(Screen.Library, Screen.Playlists, Screen.NowPlaying)

// ---------------------------------------------------------------------------
// MainActivity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    // --- Service binding ---------------------------------------------------

    private var musicService: MusicService? = null
    private var serviceBound = false

    private val viewModel: MusicViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicService.MusicBinder
            musicService = b.getService()
            serviceBound = true
            viewModel.onServiceConnected(b.getService()) // add this line
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    // --- Permission launcher -----------------------------------------------

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult?.invoke(granted)
    }

    // --- Lifecycle ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start and bind to MusicService so it survives configuration changes
        Intent(this, MusicService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MusicPlayerTheme {
                MusicPlayerApp(
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
    onRequestPermission: (String, (Boolean) -> Unit) -> Unit,
    getService: () -> MusicService?
) {
    // Shared ViewModel — survives recomposition, holds all player state
    val viewModel: MusicViewModel = viewModel()
    val hasPermission by viewModel.hasPermission.collectAsState()

    // Gate the whole app behind the storage permission
    if (!hasPermission) {
        PermissionScreen(
            onGrantClick = {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE

                onRequestPermission(permission) { granted ->
                    if (granted) {
                        viewModel.onPermissionGranted(getService())
                    }
                }
            }
        )
        return
    }

    // Main app with bottom navigation
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
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
                                // Avoid building up a large back stack
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
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AppNavHost(navController = navController, viewModel = viewModel)
        }
    }
}

// ---------------------------------------------------------------------------
// NavHost — one composable() per screen
// ---------------------------------------------------------------------------
@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: MusicViewModel
) {
    val songs by viewModel.songs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress by viewModel.progress.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route
    ) {

        composable(Screen.Library.route) {
            LibraryScreen(
                songs = songs,
                searchQuery = searchQuery,
                onSearchChange = viewModel::onSearchQueryChange,
                onSongClick = { song ->
                    viewModel.playSong(song)
                    navController.navigate(Screen.NowPlaying.route)
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
                }
            )
        }

        composable(Screen.NowPlaying.route) {
            NowPlayingScreen(
                song = currentSong,
                isPlaying = isPlaying,
                progress = progress,
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::skipToNext,
                onPrevious = viewModel::skipToPrevious,
                onSeek = viewModel::seekTo
            )
        }

        composable("playlist/{playlistId}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull()
            val playlist = playlists.find { it.id == id }
            playlist?.let {
                PlaylistDetailScreen(
                    playlist = it,
                    onPlayAll = {
                        viewModel.playPlaylist(it)
                        navController.navigate(Screen.NowPlaying.route)
                    },
                    onSongClick = { song ->
                        viewModel.playSong(song)
                        navController.navigate(Screen.NowPlaying.route)
                    },
                    onRemoveSong = { song ->
                        viewModel.removeSongFromPlaylist(it.id, song)
                    }
                )
            }
        }
    }
}