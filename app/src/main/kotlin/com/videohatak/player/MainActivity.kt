package com.videohatak.player

import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.designsystem.component.VideoHatakTopBar
import com.videohatak.player.core.designsystem.theme.LocalWindowSizeClass
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepositoryImpl
import com.videohatak.player.core.designsystem.theme.ThemeState
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import com.videohatak.player.core.designsystem.theme.VideoHatakTheme
import com.videohatak.player.core.designsystem.theme.themeDataStore
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.pip.PipActionReceiver
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.core.playback.playbackDataStore
import com.videohatak.player.core.playlist.model.toVideoItem
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.vault.model.toVideoItem
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.feature.library.LibraryScreen
import com.videohatak.player.feature.library.LibraryTab
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.player.PlayerScreen
import com.videohatak.player.feature.player.PlayerViewModel
import com.videohatak.player.feature.player.components.MiniPlayerBar
import com.videohatak.player.feature.playlist.PlaylistViewModel
import com.videohatak.player.feature.vault.VaultViewModel

class MainActivity : FragmentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels {
        ThemeViewModel.provideFactory(
            ThemePreferencesRepositoryImpl(applicationContext.themeDataStore)
        )
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.provideFactory(
            LocalVideoRepositoryImpl(applicationContext)
        )
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.provideFactory(
            application,
            PlaybackPreferencesRepositoryImpl(applicationContext.playbackDataStore)
        )
    }

    private val playlistViewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.provideFactory(
            PlaylistRepositoryImpl(applicationContext)
        )
    }

    private val vaultViewModel: VaultViewModel by viewModels {
        VaultViewModel.provideFactory(
            VaultRepositoryImpl.getInstance(applicationContext),
            applicationContext
        )
    }

    private val pipReceiver = PipActionReceiver()
    private val isInPipModeState = mutableStateOf(false)
    private var activeVideoState: VideoItem? = null

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Register PiP action receiver for window controls (Play/Pause, Seek)
        val pipFilter = IntentFilter().apply {
            addAction(PipHelper.ACTION_PIP_PLAY_PAUSE)
            addAction(PipHelper.ACTION_PIP_FORWARD)
            addAction(PipHelper.ACTION_PIP_REPLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, pipReceiver, pipFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, pipFilter)
        }

        // Prevent theme flash by keeping splash screen visible until theme is read from DataStore
        val startTimestamp = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val isLoading = themeViewModel.themeState.value is ThemeState.Loading
            val isTimedOut = (System.currentTimeMillis() - startTimestamp) > 400
            isLoading && !isTimedOut
        }

        enableEdgeToEdge()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val themeState by themeViewModel.themeState.collectAsStateWithLifecycle()

            val currentThemeMode = when (val state = themeState) {
                is ThemeState.Success -> state.themeMode
                is ThemeState.Loading -> ThemeMode.SYSTEM
            }

            val isDark = when (currentThemeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Dynamically update system bars icon appearance via WindowInsetsControllerCompat
            DisposableEffect(isDark) {
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                onDispose {}
            }

            // Permission Launcher for Android 14+ / 13 / 12 and Notifications
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                val status = PermissionHelper.checkPermissionStatus(this@MainActivity)
                libraryViewModel.onPermissionStatusChanged(status)
            }

            // Initial and on-resume permission check
            LaunchedEffect(Unit) {
                val initialStatus = PermissionHelper.checkPermissionStatus(this@MainActivity)
                libraryViewModel.onPermissionStatusChanged(initialStatus)
            }

            var activeVideoForPlayback by rememberSaveable(stateSaver = null) {
                mutableStateOf<VideoItem?>(null)
            }
            activeVideoState = activeVideoForPlayback

            val isInPip by isInPipModeState

            val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
            val searchQuery by libraryViewModel.searchQuery.collectAsStateWithLifecycle()
            val sortOrder by libraryViewModel.sortOrder.collectAsStateWithLifecycle()
            val currentTab by libraryViewModel.currentTab.collectAsStateWithLifecycle()
            val selectedFolder by libraryViewModel.selectedFolder.collectAsStateWithLifecycle()
            val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()

            // Task 9: Playlist States
            val playlists by playlistViewModel.playlists.collectAsStateWithLifecycle()
            val selectedPlaylistWithItems by playlistViewModel.selectedPlaylistWithItems.collectAsStateWithLifecycle()

            // Task 10: Secure Vault States
            val vaultIsUnlocked by vaultViewModel.isUnlocked.collectAsStateWithLifecycle()
            val vaultVideos by vaultViewModel.vaultVideos.collectAsStateWithLifecycle()
            val vaultImportState by vaultViewModel.importState.collectAsStateWithLifecycle()
            val vaultBiometricError by vaultViewModel.biometricError.collectAsStateWithLifecycle()

            // Launcher for importing external video into Secure Vault
            val vaultImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    vaultViewModel.importVideoFromUri(
                        sourceUri = uri,
                        displayName = null,
                        deleteOriginal = false
                    )
                }
            }

            // Task 10 Security: Dynamically enforce FLAG_SECURE when Vault is on screen or playing vault video
            val isVaultActive = currentTab == LibraryTab.VAULT || activeVideoForPlayback?.bucketId == "secure_vault"
            DisposableEffect(isVaultActive) {
                if (isVaultActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Sync full screen player if playlist auto-advances or changes track
            LaunchedEffect(playerViewModel.currentVideo) {
                if (activeVideoForPlayback != null && playerViewModel.currentVideo != null) {
                    activeVideoForPlayback = playerViewModel.currentVideo
                }
            }

            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                VideoHatakTheme(darkTheme = isDark) {
                    val activeVideo = activeVideoForPlayback

                    if (activeVideo != null) {
                        // Full Player View (or PiP window when in PiP)
                        PlayerScreen(
                            video = activeVideo,
                            viewModel = playerViewModel,
                            isInPipMode = isInPip,
                            onBackClick = {
                                activeVideoForPlayback = null
                            }
                        )
                    } else {
                        // Library Navigation View with persistent background playback MiniPlayerBar
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                VideoHatakTopBar(
                                    title = stringResource(id = R.string.app_name),
                                    actions = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    val nextMode = when (currentThemeMode) {
                                                        ThemeMode.SYSTEM -> ThemeMode.DARK
                                                        ThemeMode.DARK -> ThemeMode.LIGHT
                                                        ThemeMode.LIGHT -> ThemeMode.SYSTEM
                                                    }
                                                    themeViewModel.setThemeMode(nextMode)
                                                }
                                            ) {
                                                Text(
                                                    text = when (currentThemeMode) {
                                                        ThemeMode.SYSTEM -> "⚙️"
                                                        ThemeMode.DARK -> "🌙"
                                                        ThemeMode.LIGHT -> "☀️"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            },
                            bottomBar = {
                                val backgroundVideo = playerViewModel.currentVideo
                                if (backgroundVideo != null && (playerUiState.isPlaying || playerUiState.currentPositionMs > 0)) {
                                    MiniPlayerBar(
                                        video = backgroundVideo,
                                        uiState = playerUiState,
                                        onExpand = {
                                            activeVideoForPlayback = backgroundVideo
                                        },
                                        onTogglePlayPause = playerViewModel.playerManager::togglePlayPause,
                                        onClose = playerViewModel::stopPlaybackCompletely
                                    )
                                }
                            }
                        ) { innerPadding ->
                            LibraryScreen(
                                uiState = uiState,
                                searchQuery = searchQuery,
                                onSearchQueryChanged = libraryViewModel::onSearchQueryChanged,
                                sortOrder = sortOrder,
                                onSortOrderChanged = libraryViewModel::onSortOrderChanged,
                                currentTab = currentTab,
                                onTabSelected = libraryViewModel::onTabSelected,
                                selectedFolder = selectedFolder,
                                onFolderSelected = libraryViewModel::onFolderSelected,
                                clearFolderSelection = libraryViewModel::clearFolderSelection,
                                onRequestPermission = {
                                    permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                                },
                                onOpenSettings = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", packageName, null)
                                    }
                                    startActivity(intent)
                                },
                                onRefresh = libraryViewModel::refresh,
                                onVideoClick = { video ->
                                    activeVideoForPlayback = video
                                },
                                // Task 9 Playlist Callbacks
                                playlists = playlists,
                                selectedPlaylistWithItems = selectedPlaylistWithItems,
                                onSelectPlaylist = playlistViewModel::selectPlaylist,
                                onCreatePlaylist = playlistViewModel::createPlaylist,
                                onRenamePlaylist = playlistViewModel::renamePlaylist,
                                onDeletePlaylist = playlistViewModel::deletePlaylist,
                                onRemovePlaylistItem = playlistViewModel::removeItemFromPlaylist,
                                onMovePlaylistItemUp = { index -> playlistViewModel.moveItem(index, index - 1) },
                                onMovePlaylistItemDown = { index -> playlistViewModel.moveItem(index, index + 1) },
                                onPlayPlaylist = { playlistId, startVideoId, shuffle ->
                                    playerViewModel.playPlaylist(playlistId, startVideoId, shuffle)
                                    val targetItem = if (startVideoId != null) {
                                        selectedPlaylistWithItems?.second?.firstOrNull { it.videoId == startVideoId }
                                    } else {
                                        selectedPlaylistWithItems?.second?.firstOrNull { it.isAvailable }
                                    }
                                    if (targetItem != null) {
                                        activeVideoForPlayback = targetItem.toVideoItem()
                                    }
                                },
                                onAddToPlaylists = playlistViewModel::addVideoToPlaylists,
                                // Task 10 Vault Callbacks & State
                                vaultIsUnlocked = vaultIsUnlocked,
                                vaultVideos = vaultVideos,
                                vaultImportState = vaultImportState,
                                vaultBiometricError = vaultBiometricError,
                                onVaultAuthenticate = {
                                    vaultViewModel.authenticateWithBiometrics(this@MainActivity)
                                },
                                onVaultLock = vaultViewModel::lockVault,
                                onVaultImportRequest = {
                                    vaultImportLauncher.launch("video/*")
                                },
                                onVaultCancelImport = vaultViewModel::cancelImport,
                                onVaultDismissImportState = vaultViewModel::dismissImportState,
                                onVaultVideoClick = { vaultVideo ->
                                    activeVideoForPlayback = vaultVideo.toVideoItem()
                                },
                                onVaultRenameVideo = vaultViewModel::renameVideo,
                                onVaultDeleteVideo = vaultViewModel::deleteVideo,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Task 10 Zero-Leak Security: Automatically lock vault on backgrounding / screen off
        vaultViewModel.lockVault()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Automatic PiP entry fallback for Android 8.0 - 11 when user presses Home
        val video = activeVideoState
        if (video != null && playerViewModel.playerManager.exoPlayer.isPlaying) {
            PipHelper.enterPip(
                activity = this,
                videoWidth = video.width,
                videoHeight = video.height,
                isPlaying = true
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val currentStatus = PermissionHelper.checkPermissionStatus(this)
        libraryViewModel.onPermissionStatusChanged(currentStatus)
        playerViewModel.playerManager.syncWithCurrentPlayer()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {
            // Receiver already unregistered
        }
        super.onDestroy()
    }
}
