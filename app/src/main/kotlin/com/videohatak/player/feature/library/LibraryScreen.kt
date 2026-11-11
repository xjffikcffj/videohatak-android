package com.videohatak.player.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.feature.library.components.FolderCard
import com.videohatak.player.feature.library.components.PartialAccessBanner
import com.videohatak.player.feature.library.components.PermissionCard
import com.videohatak.player.feature.library.components.SortBottomSheet
import com.videohatak.player.feature.library.components.VideoCard
import com.videohatak.player.feature.playlist.components.AddToPlaylistDialog
import com.videohatak.player.feature.playlist.components.CreatePlaylistDialog
import com.videohatak.player.feature.playlist.components.RenamePlaylistDialog
import com.videohatak.player.feature.vault.VaultImportState
import com.videohatak.player.feature.vault.VaultScreen

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    sortOrder: SortOrder,
    onSortOrderChanged: (SortOrder) -> Unit,
    currentTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    selectedFolder: VideoFolder?,
    onFolderSelected: (VideoFolder) -> Unit,
    clearFolderSelection: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    // Task 9 Playlist Callbacks
    playlists: List<Playlist>,
    selectedPlaylistWithItems: Pair<Playlist, List<PlaylistItem>>?,
    onSelectPlaylist: (Playlist?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemovePlaylistItem: (Long, Long) -> Unit,
    onMovePlaylistItemUp: (Int) -> Unit,
    onMovePlaylistItemDown: (Int) -> Unit,
    onPlayPlaylist: (Long, Long?, Boolean) -> Unit,
    onAddToPlaylists: (VideoItem, List<Long>) -> Unit,
    // Task 10 Vault Callbacks & State
    vaultIsUnlocked: Boolean,
    vaultVideos: List<VaultVideo>,
    vaultImportState: VaultImportState,
    vaultBiometricError: String?,
    onVaultAuthenticate: () -> Unit,
    onVaultLock: () -> Unit,
    onVaultImportRequest: () -> Unit,
    onVaultCancelImport: () -> Unit,
    onVaultDismissImportState: () -> Unit,
    onVaultVideoClick: (VaultVideo) -> Unit,
    onVaultRenameVideo: (Long, String) -> Unit,
    onVaultDeleteVideo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSortSheetVisible by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var videoForPlaylistAddition by remember { mutableStateOf<VideoItem?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentTab == LibraryTab.VIDEOS,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.VIDEOS)
                    },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("فيديوهات") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.FOLDERS,
                    onClick = { onTabSelected(LibraryTab.FOLDERS) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("مجلدات") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.PLAYLISTS,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.PLAYLISTS)
                    },
                    icon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    label = { Text("قوائم التشغيل") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.VAULT,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.VAULT)
                    },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    label = { Text("الخزنة") }
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                LibraryTab.VIDEOS, LibraryTab.FOLDERS -> {
                    // Check permission
                    val hasAccess = PermissionHelper.hasAnyAccess(uiState.permissionStatus)
                    if (!hasAccess && uiState.permissionStatus !is VideoPermissionStatus.NotRequested) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PermissionCard(onRequestPermission = onRequestPermission)
                        }
                    } else {
                        if (uiState.permissionStatus is VideoPermissionStatus.PartialAccess) {
                            PartialAccessBanner(onManageClick = onRequestPermission)
                        }

                        // Search and Header Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedFolder != null) {
                                IconButton(onClick = clearFolderSelection) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع للمجلدات")
                                }
                                Text(
                                    text = selectedFolder.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChanged,
                                    placeholder = { Text("بحث عن فيديو...") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(onClick = { isSortSheetVisible = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "ترتيب", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (uiState.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (currentTab == LibraryTab.FOLDERS && selectedFolder == null) {
                            // Folders view
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                items(uiState.folders, key = { it.bucketId }) { folder ->
                                    FolderCard(
                                        folder = folder,
                                        onClick = { onFolderSelected(folder) },
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            }
                        } else {
                            // Videos list
                            val videos = uiState.filteredVideos
                            if (videos.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "لا توجد نتائج بحث" else "لا توجد مقاطع فيديو",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp)
                                ) {
                                    items(videos, key = { it.id }) { video ->
                                        VideoCard(
                                            video = video,
                                            onClick = { onVideoClick(video) },
                                            onMoreClick = { videoForPlaylistAddition = video },
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LibraryTab.PLAYLISTS -> {
                    val currentPlaylistPair = selectedPlaylistWithItems
                    if (currentPlaylistPair != null) {
                        // Detailed playlist view
                        val playlist = currentPlaylistPair.first
                        val items = currentPlaylistPair.second

                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { onSelectPlaylist(null) }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                                }
                                Text(
                                    text = playlist.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onPlayPlaylist(playlist.id, null, false) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تشغيل")
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                items(items, key = { it.id }) { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                            .clickable { onPlayPlaylist(playlist.id, item.videoId, false) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = item.videoTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                Text(text = if (item.isAvailable) "متاح" else "غير متاح", style = MaterialTheme.typography.bodySmall)
                                            }
                                            IconButton(onClick = { onMovePlaylistItemUp(item.orderIndex) }) {
                                                Icon(Icons.Default.ArrowUpward, contentDescription = "أعلى")
                                            }
                                            IconButton(onClick = { onMovePlaylistItemDown(item.orderIndex) }) {
                                                Icon(Icons.Default.ArrowDownward, contentDescription = "أسفل")
                                            }
                                            IconButton(onClick = { onRemovePlaylistItem(item.id, playlist.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Playlists overview
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (playlists.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("لا توجد قوائم تشغيل حتى الآن", style = MaterialTheme.typography.bodyLarge)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp)
                                ) {
                                    items(playlists, key = { it.id }) { playlist ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp)
                                                .clickable { onSelectPlaylist(playlist) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = playlist.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    Text(text = "${playlist.itemCount} فيديو", style = MaterialTheme.typography.bodySmall)
                                                }
                                                IconButton(onClick = { playlistToRename = playlist }) {
                                                    Icon(Icons.Default.Sort, contentDescription = "إعادة تسمية")
                                                }
                                                IconButton(onClick = { onDeletePlaylist(playlist.id) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            FloatingActionButton(
                                onClick = { showCreatePlaylistDialog = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.primary
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "قائمة جديدة")
                            }
                        }
                    }
                }

                LibraryTab.VAULT -> {
                    VaultScreen(
                        isUnlocked = vaultIsUnlocked,
                        videos = vaultVideos,
                        importState = vaultImportState,
                        biometricError = vaultBiometricError,
                        onAuthenticateClick = onVaultAuthenticate,
                        onLockClick = onVaultLock,
                        onImportClick = onVaultImportRequest,
                        onCancelImport = onVaultCancelImport,
                        onDismissImportState = onVaultDismissImportState,
                        onVideoClick = onVaultVideoClick,
                        onRenameVideo = onVaultRenameVideo,
                        onDeleteVideo = onVaultDeleteVideo
                    )
                }
            }
        }

        if (isSortSheetVisible) {
            SortBottomSheet(
                currentSort = sortOrder,
                onSelectSort = {
                    onSortOrderChanged(it)
                    isSortSheetVisible = false
                },
                onDismiss = { isSortSheetVisible = false }
            )
        }

        videoForPlaylistAddition?.let { video ->
            AddToPlaylistDialog(
                playlists = playlists,
                onSelectPlaylist = { playlist ->
                    onAddToPlaylists(video, listOf(playlist.id))
                    videoForPlaylistAddition = null
                },
                onCreateNewPlaylist = {
                    showCreatePlaylistDialog = true
                },
                onDismiss = { videoForPlaylistAddition = null }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    onCreatePlaylist(name)
                    showCreatePlaylistDialog = false
                },
                onDismiss = { showCreatePlaylistDialog = false }
            )
        }

        playlistToRename?.let { playlist ->
            RenamePlaylistDialog(
                currentName = playlist.title,
                onConfirm = { newName ->
                    onRenamePlaylist(playlist.id, newName)
                    playlistToRename = null
                },
                onDismiss = { playlistToRename = null }
            )
        }
    }
}
