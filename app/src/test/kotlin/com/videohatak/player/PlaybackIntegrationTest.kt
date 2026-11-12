
package com.videohatak.player

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.feature.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * End-to-end integration test suite verifying the interaction between:
 * - ExoPlayer (via FakeExoPlayer dynamic proxy)
 * - VideoPlayerManager
 * - PlaybackRestorationCoordinator
 * - PlayerViewModel (scrubbing, seeking, pause lifecycle)
 * - PlaybackPositionManager (monotonic sequence ordering & conflation)
 * - Real DataStore persistence on disk
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackIntegrationTest {

    private val tempFiles = mutableListOf<File>()
    private val activeManagers = mutableListOf<PlaybackPositionManager>()
    private val activeScopes = mutableListOf<CoroutineScope>()
    private val testDispatcher = StandardTestDispatcher()

    class TestApplication : Application()

    /**
     * FakeExoPlayer: Complete dynamic proxy for ExoPlayer that simulates player events,
     * position, duration, and listener callbacks without requiring Android media framework.
     */
    class FakeExoPlayer : InvocationHandler {
        var currentPosition: Long = 0L
        var duration: Long = 0L
        var bufferedPosition: Long = 0L
        var currentMediaItemIndex: Int = 0
        var playbackState: Int = Player.STATE_IDLE
        var isPlaying: Boolean = false
        var repeatMode: Int = Player.REPEAT_MODE_OFF
        var shuffleModeEnabled: Boolean = false
        var playWhenReady: Boolean = false
        val listeners = mutableListOf<Player.Listener>()
        val mediaItems = mutableListOf<MediaItem>()
        val seekCalls = mutableListOf<Long>()

        fun triggerMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            listeners.toList().forEach {
                it.onMediaItemTransition(mediaItem, reason)
            }
        }

        fun triggerPlaybackStateChanged(state: Int) {
            playbackState = state
            listeners.toList().forEach {
                it.onPlaybackStateChanged(state)
            }
        }

        val exoPlayer: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            this
        ) as ExoPlayer

        override fun invoke(
            proxy: Any?,
            method: Method,
            args: Array<out Any>?
        ): Any? {
            val nonNullArgs = args ?: emptyArray()

            return when (method.name) {
                "addListener" -> {
                    listeners.add(nonNullArgs[0] as Player.Listener)
                    null
                }

                "removeListener" -> {
                    listeners.remove(nonNullArgs[0] as Player.Listener)
                    null
                }

                "getCurrentPosition" -> currentPosition
                "getDuration" -> duration
                "getBufferedPosition" -> bufferedPosition
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getPlaybackState" -> playbackState
                "isPlaying" -> isPlaying

                "play" -> {
                    isPlaying = true
                    null
                }

                "pause" -> {
                    isPlaying = false
                    null
                }

                "stop" -> {
                    isPlaying = false
                    null
                }

                "clearMediaItems" -> {
                    mediaItems.clear()
                    null
                }

                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = nonNullArgs[0] as List<MediaItem>

                    mediaItems.clear()
                    mediaItems.addAll(items)

                    if (nonNullArgs.size >= 2) {
                        currentMediaItemIndex = nonNullArgs[1] as Int
                    }

                    if (nonNullArgs.size >= 3) {
                        currentPosition = nonNullArgs[2] as Long
                    }

                    null
                }

                "setMediaItem" -> {
                    val item = nonNullArgs[0] as MediaItem

                    mediaItems.clear()
                    mediaItems.add(item)
                    currentMediaItemIndex = 0

                    if (nonNullArgs.size >= 2) {
                        currentPosition = nonNullArgs[1] as Long
                    }

                    null
                }

                "seekTo" -> {
                    val position = if (nonNullArgs.size == 1) {
                        nonNullArgs[0] as Long
                    } else {
                        currentMediaItemIndex = nonNullArgs[0] as Int
                        nonNullArgs[1] as Long
                    }

                    seekCalls.add(position)
                    currentPosition = position
                    null
                }

                "setRepeatMode" -> {
                    repeatMode = nonNullArgs[0] as Int
                    null
                }

                "getRepeatMode" -> repeatMode

                "setShuffleModeEnabled" -> {
                    shuffleModeEnabled = nonNullArgs[0] as Boolean
                    null
                }

                "getShuffleModeEnabled" -> shuffleModeEnabled

                "prepare" -> null

                "setPlayWhenReady" -> {
                    playWhenReady = nonNullArgs[0] as Boolean
                    null
                }

                "getPlayWhenReady" -> playWhenReady

                "hasNextMediaItem" ->
                    currentMediaItemIndex < mediaItems.size - 1

                "hasPreviousMediaItem" ->
                    currentMediaItemIndex > 0

                "seekToNextMediaItem" -> {
                    if (currentMediaItemIndex < mediaItems.size - 1) {
                        currentMediaItemIndex++
                        currentPosition = 0L
                    }
                    null
                }

                "seekToPreviousMediaItem" -> {
                    if (currentMediaItemIndex > 0) {
                        currentMediaItemIndex--
                        currentPosition = 0L
                    }
                    null
                }

                "release" -> {
                    listeners.clear()
                    null
                }

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === nonNullArgs.getOrNull(0)
                "toString" ->
                    "FakeExoPlayer(pos=$currentPosition, dur=$duration, idx=$currentMediaItemIndex)"

                else -> {
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        Double::class.javaPrimitiveType -> 0.0
                        Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
    }

    private fun createTestScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScopes.add(scope)
        return scope
    }

    private fun createRealDataStore(
        scope: CoroutineScope
    ): Pair<DataStore<Preferences>, File> {
        val file = File.createTempFile(
            "test_integ_prefs_",
            ".preferences_pb"
        )

        file.deleteOnExit()
        tempFiles.add(file)

        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )

        return Pair(dataStore, file)
    }

    private fun createManager(
        repo: PlaybackPreferencesRepository,
        scope: CoroutineScope = createTestScope()
    ): PlaybackPositionManager {
        val manager = PlaybackPositionManager(repo, scope)
        activeManagers.add(manager)
        return manager
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() = runBlocking {
        Dispatchers.resetMain()

        for (manager in activeManagers) {
            try {
                manager.shutdown(drainTimeoutMs = 500L)
            } catch (_: Exception) {
            }
        }

        activeManagers.clear()

        for (scope in activeScopes) {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
        }

        activeScopes.clear()

        for (file in tempFiles) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }

        tempFiles.clear()
    }

    // Test 1
    @Test
    fun test1_onScrubStop_persistsFinalPositionDirectlyToDataStore() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = createManager(realRepo, scope)

        val fakePlayer = FakeExoPlayer().apply {
            duration = 120_000L
            currentPosition = 0L
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val viewModel = PlayerViewModel(
            application = TestApplication(),
            playerManager = playerManager,
            playbackPreferences = realRepo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        val videoUri =
            Uri.parse("content://media/external/video/media/scrub_integ_1")

        val videoItem = VideoItem(
            id = 1001L,
            contentUri = videoUri,
            displayName = "Integ Test Video",
            title = "Integ Test Video",
            durationMs = 120_000L,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L
        )

        viewModel.playVideo(videoItem)
        viewModel.onScrubStop(85_400L)

        assertEquals(85_400L, fakePlayer.currentPosition)
        assertEquals(
            85_400L,
            viewModel.uiState.value.currentPositionMs
        )

        assertTrue(posManager.flushPending(timeoutMs = 2000L))

        val saved = realRepo.getSavedVideoPosition(
            videoUri.toString(),
            120_000L
        )

        assertEquals(
            "DataStore must contain the exact scrub-stop position",
            85_400L,
            saved
        )
    }

    // Test 2
    @Test
    fun test2_olderPeriodicSaveCannotOverwriteScrubFinalPosition() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = createManager(realRepo, scope)

        val fakePlayer = FakeExoPlayer().apply {
            duration = 120_000L
            currentPosition = 0L
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val viewModel = PlayerViewModel(
            application = TestApplication(),
            playerManager = playerManager,
            playbackPreferences = realRepo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        val videoUri =
            Uri.parse("content://media/external/video/media/scrub_order_test")

        val videoItem = VideoItem(
            id = 1002L,
            contentUri = videoUri,
            displayName = "Order Test",
            title = "Order Test",
            durationMs = 120_000L,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L
        )

        viewModel.playVideo(videoItem)

        val periodicReq = posManager.enqueueSave(
            videoUri.toString(),
            12_000L,
            120_000L
        )

        assertNotNull(periodicReq)

        viewModel.onScrubStop(92_000L)

        assertTrue(posManager.flushPending(timeoutMs = 2000L))

        val persisted = realRepo.getSavedVideoPosition(
            videoUri.toString(),
            120_000L
        )

        assertEquals(
            "Persisted position must be the scrubbed 92,000ms, not the older periodic save",
            92_000L,
            persisted
        )
    }

    // Test 3
    @Test
    fun test3_exoPlayerTransitionBetweenIdenticalMediaAtDifferentIndices() {
        val fakePlayer = FakeExoPlayer()
        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val sharedUri =
            Uri.parse("content://media/external/video/media/same_item")

        val item0 = VideoItem(
            id = 555L,
            contentUri = sharedUri,
            displayName = "Same 0",
            title = "Same 0",
            durationMs = 60_000L,
            sizeBytes = 1000L,
            dateAddedSec = 1L
        )

        val item1 = VideoItem(
            id = 555L,
            contentUri = sharedUri,
            displayName = "Same 1",
            title = "Same 1",
            durationMs = 60_000L,
            sizeBytes = 1000L,
            dateAddedSec = 1L
        )

        playerManager.playPlaylist(
            listOf(item0, item1),
            startIndex = 0
        )

        assertEquals(
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )

        fakePlayer.currentMediaItemIndex = 0

        val mediaItem0 = MediaItem.Builder()
            .setMediaId("555")
            .setUri(sharedUri)
            .build()

        playerManager.playerListener.onMediaItemTransition(
            mediaItem0,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )

        assertEquals(
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )

        fakePlayer.currentMediaItemIndex = 1

        val mediaItem1 = MediaItem.Builder()
            .setMediaId("555")
            .setUri(sharedUri)
            .build()

        playerManager.playerListener.onMediaItemTransition(
            mediaItem1,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        )

        assertEquals(
            2L,
            playerManager.getCurrentPlaybackGeneration()
        )
    }

    // Test 4
    @Test
    fun test4_repeatOneTransitionResetsRestorationForNewLoop() {
        val fakePlayer = FakeExoPlayer()
        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val uri =
            Uri.parse("content://media/external/video/media/repeat_one_test")

        val mediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .build()

        playerManager.playMedia(uri, "Repeat Video")

        assertEquals(
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )

        fakePlayer.currentMediaItemIndex = 0

        playerManager.playerListener.onMediaItemTransition(
            mediaItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )

        assertEquals(
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )

        playerManager.seekTo(30_000L)

        assertTrue(
            playerManager.restorationCoordinator.isRestorationApplied
        )

        playerManager.playerListener.onMediaItemTransition(
            mediaItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        )

        assertEquals(
            2L,
            playerManager.getCurrentPlaybackGeneration()
        )

        assertFalse(
            playerManager.restorationCoordinator.isRestorationApplied
        )
    }

    // Test 5
    @Test
    fun test5_videoPlayerManagerPauseWithZeroDurationEnqueuesAndSavesPosition() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = createManager(realRepo, scope)

        val fakePlayer = FakeExoPlayer().apply {
            duration = 0L
            currentPosition = 0L
            isPlaying = true
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        playerManager.onPeriodicPositionUpdate = { uri, pos, dur ->
            posManager.enqueueSave(uri, pos, dur)
        }

        val videoUri =
            Uri.parse("content://media/external/video/media/pause_zero_dur")

        playerManager.playMedia(videoUri, "Zero Duration Video")

        fakePlayer.currentPosition = 47_300L

        playerManager.pause()

        assertFalse(fakePlayer.isPlaying)

        assertTrue(
            posManager.flushPending(timeoutMs = 2000L)
        )

        val storedPos = realRepo.getSavedVideoPosition(
            videoUri.toString(),
            durationMs = 0L
        )

        assertEquals(
            47_300L,
            storedPos
        )
    }

    // Test 6
    @Test
    fun test6_playPlaylistInitialTransitionDoesNotIncrementGenerationTwice() {
        val fakePlayer = FakeExoPlayer()
        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val uri =
            Uri.parse("content://media/external/video/media/init_gen_test")

        val item = VideoItem(
            id = 888L,
            contentUri = uri,
            displayName = "Single Item",
            title = "Single Item",
            durationMs = 50_000L,
            sizeBytes = 1000L,
            dateAddedSec = 1L
        )

        playerManager.playPlaylist(
            listOf(item),
            startIndex = 0
        )

        assertEquals(
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )

        fakePlayer.currentMediaItemIndex = 0

        val mediaItem = MediaItem.Builder()
            .setMediaId("888")
            .setUri(uri)
            .build()

        playerManager.playerListener.onMediaItemTransition(
            mediaItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )

        assertEquals(
            "Generation must remain 1 and NOT increment a second time on initial transition",
            1L,
            playerManager.getCurrentPlaybackGeneration()
        )
    }

    // Test 7
    @Test
    fun test7_scrubbingProgressDoesNotTriggerRedundantSaves() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)

        val saveCallsCount =
            java.util.concurrent.atomic.AtomicInteger(0)

        val savedPositionsList =
            java.util.concurrent.CopyOnWriteArrayList<Long>()

        val countingRepo = object : PlaybackPreferencesRepository by realRepo {
            override suspend fun saveVideoPosition(
                uri: String,
                positionMs: Long,
                durationMs: Long
            ) {
                saveCallsCount.incrementAndGet()
                savedPositionsList.add(positionMs)

                realRepo.saveVideoPosition(
                    uri,
                    positionMs,
                    durationMs
                )
            }
        }

        val posManager = createManager(countingRepo, scope)

        val fakePlayer = FakeExoPlayer().apply {
            duration = 100_000L
            currentPosition = 0L
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val viewModel = PlayerViewModel(
            application = TestApplication(),
            playerManager = playerManager,
            playbackPreferences = countingRepo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        val videoUri =
            Uri.parse("content://media/external/video/media/no_redundant_saves")

        val videoItem = VideoItem(
            id = 777L,
            contentUri = videoUri,
            displayName = "No Redundant Saves",
            title = "No Redundant Saves",
            durationMs = 100_000L,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L
        )

        viewModel.playVideo(videoItem)

        val initialSeq = posManager.getCurrentSequenceNumber()
        val initialWrites = saveCallsCount.get()

        viewModel.onScrubStart(10_000L, 0.10f)
        viewModel.onScrubProgress(20_000L, 0.20f)
        viewModel.onScrubProgress(30_000L, 0.30f)
        viewModel.onScrubProgress(40_000L, 0.40f)
        viewModel.onScrubProgress(50_000L, 0.50f)

        assertTrue(
            posManager.saveChannel.tryReceive().isFailure
        )

        assertEquals(
            initialSeq,
            posManager.getCurrentSequenceNumber()
        )

        assertEquals(
            initialWrites,
            saveCallsCount.get()
        )

        viewModel.onScrubStop(65_000L)

        assertEquals(
            initialSeq + 1,
            posManager.getCurrentSequenceNumber()
        )

        assertTrue(
            posManager.flushPending(timeoutMs = 2000L)
        )

        assertEquals(
            initialWrites + 1,
            saveCallsCount.get()
        )

        assertEquals(
            listOf(65_000L),
            savedPositionsList.toList()
        )

        val finalSaved = realRepo.getSavedVideoPosition(
            videoUri.toString(),
            100_000L
        )

        assertEquals(
            65_000L,
            finalSaved
        )
    }

    // Test 8
    @Test
    fun test8_repeatOneDoesNotTriggerDataStoreRestorationAndStartsAtZero() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = createManager(realRepo, scope)

        val restorationQueriesCount =
            java.util.concurrent.atomic.AtomicInteger(0)

        val trackingRepo = object : PlaybackPreferencesRepository by realRepo {
            override suspend fun getSavedVideoPosition(
                uri: String,
                durationMs: Long,
                legacyVideoId: Long?
            ): Long {
                restorationQueriesCount.incrementAndGet()

                return realRepo.getSavedVideoPosition(
                    uri,
                    durationMs,
                    legacyVideoId
                )
            }
        }

        val videoUri =
            Uri.parse("content://media/external/video/media/repeat_one_restore_test")

        val videoItem = VideoItem(
            id = 999L,
            contentUri = videoUri,
            displayName = "Repeat One Test Video",
            title = "Repeat One Test Video",
            durationMs = 120_000L,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L
        )

        realRepo.saveVideoPosition(
            videoUri.toString(),
            55_000L,
            120_000L
        )

        assertEquals(
            55_000L,
            realRepo.getSavedVideoPosition(
                videoUri.toString(),
                120_000L
            )
        )

        val fakePlayer = FakeExoPlayer().apply {
            duration = 120_000L
            currentPosition = 0L
            repeatMode = Player.REPEAT_MODE_ONE
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val viewModel = PlayerViewModel(
            application = TestApplication(),
            playerManager = playerManager,
            playbackPreferences = trackingRepo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        viewModel.playVideo(videoItem)
        testDispatcher.scheduler.advanceUntilIdle()

        val initialQueries = restorationQueriesCount.get()

        assertTrue(
            initialQueries >= 1
        )

        fakePlayer.currentPosition = 0L
        fakePlayer.currentMediaItemIndex = 0

        val mediaItem = MediaItem.Builder()
            .setMediaId("999")
            .setUri(videoUri)
            .build()

        playerManager.playerListener.onMediaItemTransition(
            mediaItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            2L,
            playerManager.getCurrentPlaybackGeneration()
        )

        assertEquals(
            initialQueries,
            restorationQueriesCount.get()
        )

        assertEquals(
            0L,
            fakePlayer.currentPosition
        )

        assertEquals(
            0L,
            viewModel.uiState.value.currentPositionMs
        )
    }

    // Test 9
    @Test
    fun test9_repeatAllNormalTransitionRestoresPositionForNewItem() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = createManager(realRepo, scope)

        val restorationQueriesCount =
            java.util.concurrent.atomic.AtomicInteger(0)

        val trackingRepo = object : PlaybackPreferencesRepository by realRepo {
            override suspend fun getSavedVideoPosition(
                uri: String,
                durationMs: Long,
                legacyVideoId: Long?
            ): Long {
                restorationQueriesCount.incrementAndGet()

                return realRepo.getSavedVideoPosition(
                    uri,
                    durationMs,
                    legacyVideoId
                )
            }
        }

        val uriA =
            Uri.parse("content://media/external/video/media/repeat_all_A")

        val uriB =
            Uri.parse("content://media/external/video/media/repeat_all_B")

        realRepo.saveVideoPosition(
            uriA.toString(),
            15_000L,
            100_000L
        )

        realRepo.saveVideoPosition(
            uriB.toString(),
            40_000L,
            100_000L
        )

        val itemA = VideoItem(
            id = 111L,
            contentUri = uriA,
            displayName = "A",
            title = "A",
            durationMs = 100_000L,
            sizeBytes = 1000L,
            dateAddedSec = 1L
        )

        val itemB = VideoItem(
            id = 222L,
            contentUri = uriB,
            displayName = "B",
            title = "B",
            durationMs = 100_000L,
            sizeBytes = 1000L,
            dateAddedSec = 1L
        )

        val fakePlayer = FakeExoPlayer().apply {
            duration = 100_000L
            currentPosition = 0L
            repeatMode = Player.REPEAT_MODE_ALL
        }

        val playerManager =
            VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        val viewModel = PlayerViewModel(
            application = TestApplication(),
            playerManager = playerManager,
            playbackPreferences = trackingRepo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        val playlistField =
            PlayerViewModel::class.java.getDeclaredField("playlistVideos")

        playlistField.isAccessible = true
        playlistField.set(viewModel, listOf(itemA, itemB))

        viewModel.playVideo(itemA)

        testDispatcher.scheduler.advanceUntilIdle()

        // The player must become ready before waiting for position restoration.
        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()

        withTimeout(5_000L) {
            while (!fakePlayer.seekCalls.contains(15_000L)) {
                testDispatcher.scheduler.advanceUntilIdle()
                delay(10L)
            }
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(15_000L, fakePlayer.currentPosition)
        assertEquals(
            15_000L,
            viewModel.uiState.value.currentPositionMs
        )

        val queriesAfterA = restorationQueriesCount.get()

        fakePlayer.currentMediaItemIndex = 1
        fakePlayer.currentPosition = 0L
        fakePlayer.duration = 100_000L
        fakePlayer.playbackState = Player.STATE_READY

        val mediaItemB = MediaItem.Builder()
            .setMediaId("222")
            .setUri(uriB)
            .build()

        playerManager.playerListener.onMediaItemTransition(
            mediaItemB,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        )

        testDispatcher.scheduler.advanceUntilIdle()

        withTimeout(5_000L) {
            while (!fakePlayer.seekCalls.contains(40_000L)) {
                testDispatcher.scheduler.advanceUntilIdle()
                delay(10L)
            }
        }

        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            restorationQueriesCount.get() > queriesAfterA
        )

        assertTrue(
            fakePlayer.seekCalls.contains(40_000L)
        )

        assertEquals(
            40_000L,
            fakePlayer.currentPosition
        )

        assertEquals(
            40_000L,
            viewModel.uiState.value.currentPositionMs
        )

        assertTrue(
            fakePlayer.currentPosition != 15_000L
        )

        assertEquals(
            uriB,
            viewModel.uiState.value.mediaUri
        )
    }
}
