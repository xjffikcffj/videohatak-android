
@Test
fun test9_repeatAllNormalTransitionRestoresPositionForNewItem() = runBlocking {
    val scope = createTestScope()
    val (dataStore, _) = createRealDataStore(scope)
    val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
    val posManager = createManager(realRepo, scope)

    val restorationQueriesCount = java.util.concurrent.atomic.AtomicInteger(0)
    val trackingRepo = object : PlaybackPreferencesRepository by realRepo {
        override suspend fun getSavedVideoPosition(uri: String, durationMs: Long, legacyVideoId: Long?): Long {
            restorationQueriesCount.incrementAndGet()
            return realRepo.getSavedVideoPosition(uri, durationMs, legacyVideoId)
        }
    }

    val uriA = Uri.parse("content://media/external/video/media/repeat_all_A")
    val uriB = Uri.parse("content://media/external/video/media/repeat_all_B")

    realRepo.saveVideoPosition(uriA.toString(), 15_000L, 100_000L)
    realRepo.saveVideoPosition(uriB.toString(), 40_000L, 100_000L)

    val itemA = VideoItem(id = 111L, contentUri = uriA, displayName = "A", title = "A", durationMs = 100_000L, sizeBytes = 1000L, dateAddedSec = 1L)
    val itemB = VideoItem(id = 222L, contentUri = uriB, displayName = "B", title = "B", durationMs = 100_000L, sizeBytes = 1000L, dateAddedSec = 1L)

    val fakePlayer = FakeExoPlayer().apply {
        duration = 100_000L
        currentPosition = 0L
        repeatMode = Player.REPEAT_MODE_ALL
    }
    val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

    val viewModel = PlayerViewModel(
        application = TestApplication(),
        playerManager = playerManager,
        playbackPreferences = trackingRepo,
        positionManager = posManager,
        subtitlePreferences = null,
        thumbnailManager = null
    )

    val playlistField = PlayerViewModel::class.java.getDeclaredField("playlistVideos")
    playlistField.isAccessible = true
    playlistField.set(viewModel, listOf(itemA, itemB))

    viewModel.playVideo(itemA)

    // The player must be READY before the asynchronous restoration is evaluated.
    fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
    testDispatcher.scheduler.advanceUntilIdle()

    withTimeout(5_000L) {
        while (!fakePlayer.seekCalls.contains(15_000L)) {
            testDispatcher.scheduler.advanceUntilIdle()
            delay(10L)
        }
    }

    assertEquals(15_000L, fakePlayer.currentPosition)
    assertEquals(15_000L, viewModel.uiState.value.currentPositionMs)
    val queriesAfterA = restorationQueriesCount.get()

    fakePlayer.currentMediaItemIndex = 1
    fakePlayer.currentPosition = 0L
    fakePlayer.duration = 100_000L
    fakePlayer.playbackState = Player.STATE_READY

    val mediaItemB = MediaItem.Builder().setMediaId("222").setUri(uriB).build()
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
        "Normal transition to new item must trigger DataStore restoration query",
        restorationQueriesCount.get() > queriesAfterA
    )
    assertTrue(
        "seekTo must be executed on FakeExoPlayer with position 40,000ms",
        fakePlayer.seekCalls.contains(40_000L)
    )
    assertEquals(40_000L, fakePlayer.currentPosition)
    assertEquals(40_000L, viewModel.uiState.value.currentPositionMs)
    assertTrue(fakePlayer.currentPosition != 15_000L)
    assertEquals(uriB, viewModel.uiState.value.mediaUri)
}
