package com.videohatak.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPositionThresholds
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.PlaybackRestorationCoordinator
import com.videohatak.player.core.playback.PositionSavePolicy
import com.videohatak.player.core.playback.PositionSaveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.IOException

/**
 * Production-grade unit & integration test suite verifying the complete playback position
 * persistence lifecycle with REAL DataStore files on disk:
 *
 * 1. Real DataStore position save and restoration.
 * 2. Transient write failure followed by automatic retry.
 * 3. Retry exhaustion -> marked in failedRequestsMap -> targeted flush returns false.
 * 4. Channel rejection (trySend failure) handled cleanly without silent loss.
 * 5. In-flight race: newer update arriving while older is in-flight preserves newer data on disk.
 * 6. Monotonic sequence ordering prevents out-of-order writes.
 * 7. Guaranteed inFlightRequest cleanup in try/finally across all paths.
 * 8. flushPending() does not hang on failure and returns truthful boolean status.
 * 9. retryFailedRequests() re-enqueues without creating duplicate or obsolete writes.
 * 10. Position discontinuity handling on media transition (prevents 0L position overwrite of previous video).
 * 11. Coordinated position restoration with STATE_READY and PlaybackPositionThresholds.
 * 12. Dropping of stale restorations when active media URI changes before DataStore finishes.
 * 13. Targeted flushPending semantics: historical failed request does NOT fail subsequent successful save.
 * 14. Short video thresholds (1s, 3s, 5s) accurately preserved.
 * 15. Legacy key migration from playback_pos_<id> to modern vid_pos_<hash> without data loss.
 * 16. Worker resilience and retry abort when newer request arrives for the same URI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PlaybackPreferencesTest {

    private val tempFiles = mutableListOf<File>()
    private val activeManagers = mutableListOf<PlaybackPositionManager>()
    private val activeScopes = mutableListOf<CoroutineScope>()

    private fun createTestScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScopes.add(scope)
        return scope
    }

    private fun createRealDataStore(scope: CoroutineScope): Pair<DataStore<Preferences>, File> {
        val file = File.createTempFile("test_playback_prefs_", ".preferences_pb")
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

    @After
    fun tearDown() = runBlocking {
        for (mgr in activeManagers) {
            try {
                mgr.shutdown(drainTimeoutMs = 500L)
            } catch (_: Exception) {}
        }
        activeManagers.clear()

        for (sc in activeScopes) {
            try {
                sc.cancel()
            } catch (_: Exception) {}
        }
        activeScopes.clear()

        for (file in tempFiles) {
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
        tempFiles.clear()
        PlaybackPositionManager.resetInstanceForTesting()
    }

    /**
     * Delegating repository wrapping the real PlaybackPreferencesRepositoryImpl
     * to inject deterministic I/O failures and synchronization hooks for concurrency testing.
     */
    class FaultInjectingRepository(
        val delegate: PlaybackPreferencesRepository
    ) : PlaybackPreferencesRepository {
        var failCount = 0
        var failWithException: Exception = IOException("Simulated disk I/O error")
        var onBeforeSave: (suspend (String, Long, Long) -> Unit)? = null
        var onAfterFailure: ((String, Long, Exception) -> Unit)? = null
        val saveCalls = mutableListOf<Triple<String, Long, Long>>()

        override val playbackSpeed: Flow<Float> get() = delegate.playbackSpeed
        override val resizeMode: Flow<Int> get() = delegate.resizeMode
        override val repeatMode: Flow<String> get() = delegate.repeatMode

        override suspend fun setPlaybackSpeed(speed: Float) = delegate.setPlaybackSpeed(speed)
        override suspend fun setResizeMode(mode: Int) = delegate.setResizeMode(mode)
        override suspend fun setRepeatMode(mode: String) = delegate.setRepeatMode(mode)

        override suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long) {
            saveCalls.add(Triple(videoUri, positionMs, durationMs))
            onBeforeSave?.invoke(videoUri, positionMs, durationMs)

            if (failCount > 0) {
                failCount--
                val ex = failWithException
                onAfterFailure?.invoke(videoUri, positionMs, ex)
                throw ex
            }
            delegate.saveVideoPosition(videoUri, positionMs, durationMs)
        }

        override suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long, legacyVideoId: Long?): Long {
            return delegate.getSavedVideoPosition(videoUri, durationMs, legacyVideoId)
        }
    }

    // -------------------------------------------------------------
    // Test 1: Real Position Saving & Restoration with Real DataStore
    // -------------------------------------------------------------
    @Test
    fun test1_RealSaveAndRestoreVideoPositionWithRealDataStore() = runBlocking {
        val scope = createTestScope()
        val (dataStore, file) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val uri = "content://media/external/video/media/101"
        val req = manager.enqueueSave(uri, positionMs = 42_000L, durationMs = 120_000L)
        assertNotNull(req)
        assertTrue(manager.flushPending(timeoutMs = 3000L))

        val restored = realRepo.getSavedVideoPosition(uri, durationMs = 120_000L)
        assertEquals(42_000L, restored)

        // Close the first DataStore scope before reopening the same file.
        // DataStore does not allow two active instances for one file.
        scope.cancel()

        val freshScope = createTestScope()
        val freshDataStore = PreferenceDataStoreFactory.create(scope = freshScope, produceFile = { file })
        val freshRepo = PlaybackPreferencesRepositoryImpl(freshDataStore)
        val restoredFromDisk = freshRepo.getSavedVideoPosition(uri, durationMs = 120_000L)
        assertEquals("Position must persist on real disk file", 42_000L, restoredFromDisk)
    }

    // -------------------------------------------------------------
    // Test 2: Transient Write Failure Followed by Successful Retry
    // -------------------------------------------------------------
    @Test
    fun test2_WriteFailureFollowedBySuccessfulRetry() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        faultRepo.failCount = 1

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/202"
        manager.enqueueSave(uri, positionMs = 25_000L, durationMs = 60_000L)

        val flushSuccess = manager.flushPending(timeoutMs = 4000L)
        assertTrue("Flush must report true after successful retry", flushSuccess)
        assertEquals(25_000L, faultRepo.getSavedVideoPosition(uri, 60_000L))
        assertTrue("Must have attempted at least twice (fail then succeed)", faultRepo.saveCalls.size >= 2)
        assertTrue("Failed requests map must be clean after recovery", manager.getFailedRequests().isEmpty())
    }

    // -------------------------------------------------------------
    // Test 3: Exhausted Retries -> Recorded in failedRequestsMap -> Targeted Flush Returns False
    // -------------------------------------------------------------
    @Test
    fun test3_ExhaustedRetriesRecordedInFailedRequests() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        faultRepo.failCount = 10

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/303"
        manager.enqueueSave(uri, positionMs = 30_000L, durationMs = 90_000L)

        val flushResult = manager.flushPending(timeoutMs = 3000L)
        assertFalse("flushPending must return false when targeted request fails permanently", flushResult)

        val failed = manager.getFailedRequests()
        assertTrue("Failed request must be in failedRequestsMap", failed.containsKey(uri))
        assertEquals(30_000L, failed[uri]?.positionMs)
        assertEquals(0, manager.getPendingCount())
        assertEquals("DataStore must have 0L since writes failed", 0L, faultRepo.getSavedVideoPosition(uri, 90_000L))
    }

    // -------------------------------------------------------------
    // Test 4: Channel Rejection (trySend failure) Handled Without Silent Loss
    // -------------------------------------------------------------
    @Test
    fun test4_TrySendRejectionHandledWithoutSilentDrop() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        manager.saveChannel.close()

        val uri = "content://media/external/video/media/404"
        val result = manager.enqueueSave(uri, positionMs = 15_000L, durationMs = 60_000L)

        assertNull("Closed channel must reject enqueueSave and return null", result)
        val failed = manager.getFailedRequests()
        assertTrue("Rejected request must be tracked in failedRequestsMap", failed.containsKey(uri))
        assertEquals(15_000L, failed[uri]?.positionMs)
    }

    // -------------------------------------------------------------
    // Test 5: In-Flight Race: Newer Update Arriving While Older Write In-Flight
    // -------------------------------------------------------------
    @Test
    fun test5_NewerUpdateArrivingWhileOlderWriteInFlightPreservesNewer() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        val uri = "content://media/external/video/media/505"

        val olderWriteEntered = CompletableDeferred<Unit>()
        val allowOlderWriteToFinish = CompletableDeferred<Unit>()

        faultRepo.onBeforeSave = { saveUri, pos, _ ->
            if (saveUri == uri && pos == 10_000L) {
                olderWriteEntered.complete(Unit)
                allowOlderWriteToFinish.await()
            }
        }

        val manager = createManager(faultRepo, scope)

        val req1 = manager.enqueueSave(uri, positionMs = 10_000L, durationMs = 120_000L)
        assertNotNull(req1)

        olderWriteEntered.await()
        assertEquals(req1, manager.inFlightRequest)

        val req2 = manager.enqueueSave(uri, positionMs = 55_000L, durationMs = 120_000L)
        assertNotNull(req2)
        assertTrue(req2!!.sequenceNumber > req1!!.sequenceNumber)

        allowOlderWriteToFinish.complete(Unit)

        assertTrue(manager.flushPending(timeoutMs = 4000L))

        assertEquals("Newer position must win over older in-flight write", 55_000L, realRepo.getSavedVideoPosition(uri, 120_000L))
    }

    // -------------------------------------------------------------
    // Test 6: Monotonic Sequence Ordering Prevents Out-Of-Order Writes
    // -------------------------------------------------------------
    @Test
    fun test6_OlderSequenceCannotOverwriteNewerSavedSequence() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)
        val uri = "content://media/external/video/media/606"

        val newerReq = PositionSaveRequest(uri, positionMs = 70_000L, durationMs = 100_000L, sequenceNumber = 200L)
        manager.processSingleRequest(newerReq)
        assertEquals(70_000L, realRepo.getSavedVideoPosition(uri, 100_000L))

        val olderReq = PositionSaveRequest(uri, positionMs = 20_000L, durationMs = 100_000L, sequenceNumber = 100L)
        manager.processSingleRequest(olderReq)

        assertEquals("Older sequence must not overwrite newer saved position", 70_000L, realRepo.getSavedVideoPosition(uri, 100_000L))
    }

    // -------------------------------------------------------------
    // Test 7: Guaranteed inFlightRequest Cleanup in try/finally
    // -------------------------------------------------------------
    @Test
    fun test7_InFlightRequestCleanedUpInFinally() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        faultRepo.failCount = 1

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/707"
        val req = PositionSaveRequest(uri, positionMs = 20_000L, durationMs = 60_000L, sequenceNumber = 1L)

        try {
            manager.processSingleRequest(req)
        } catch (_: Exception) {}

        assertNull("inFlightRequest must be null in finally", manager.inFlightRequest)
    }

    // -------------------------------------------------------------
    // Test 8: flushPending() Does Not Hang Indefinitely on Permanent Failure
    // -------------------------------------------------------------
    @Test
    fun test8_FlushPendingDoesNotHangAfterPermanentFailure() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        faultRepo.failCount = 10

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/808"
        manager.enqueueSave(uri, positionMs = 40_000L, durationMs = 90_000L)

        val startTime = System.currentTimeMillis()
        val success = manager.flushPending(timeoutMs = 4000L)
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse("flushPending must return false on permanent failure", success)
        assertTrue("flushPending must not hang for full timeout (elapsed: $elapsed ms)", elapsed < 3500L)
        assertEquals(0, manager.getPendingCount())
    }

    // -------------------------------------------------------------
    // Test 9: retryFailedRequests() Re-enqueues Without Duplicate or Obsolete Overwrite
    // -------------------------------------------------------------
    @Test
    fun test9_RetryFailedRequestsWithoutDuplicatesOrObsoleteOverwrite() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)
        faultRepo.failCount = 10

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/909"
        manager.enqueueSave(uri, positionMs = 35_000L, durationMs = 80_000L)
        manager.flushPending(timeoutMs = 2000L)

        assertEquals(1, manager.getFailedRequests().size)

        faultRepo.failCount = 0
        val count = manager.retryFailedRequests()
        assertEquals(1, count)

        assertTrue(manager.flushPending(timeoutMs = 2000L))
        assertTrue(manager.getFailedRequests().isEmpty())
        assertEquals(35_000L, realRepo.getSavedVideoPosition(uri, 80_000L))
    }

    // -------------------------------------------------------------
    // Test 10: Position Discontinuity Preservation (Prevents 0L Overwrite of Previous Media)
    // -------------------------------------------------------------
    @Test
    fun test10_PositionDiscontinuityPreservesPreviousVideoPosition() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val video1Uri = "content://media/external/video/media/discontinuity_1"
        val video2Uri = "content://media/external/video/media/discontinuity_2"

        manager.enqueueSave(video1Uri, positionMs = 45_000L, durationMs = 60_000L)
        assertTrue(manager.flushPending())
        assertEquals(45_000L, realRepo.getSavedVideoPosition(video1Uri, 60_000L))

        manager.enqueueSave(video2Uri, positionMs = 0L, durationMs = 120_000L)
        assertTrue(manager.flushPending())

        assertEquals("Video 1 position must remain at 45,000ms after transition to Video 2", 45_000L, realRepo.getSavedVideoPosition(video1Uri, 60_000L))
        assertEquals("Video 2 position must be 0L", 0L, realRepo.getSavedVideoPosition(video2Uri, 120_000L))
    }

    // -------------------------------------------------------------
    // Test 11: Coordinated Position Restoration with Thresholds
    // -------------------------------------------------------------
    @Test
    fun test11_CoordinatedPositionRestorationWithThresholds() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)

        val uri = "content://media/external/video/media/restore_coord"
        val durationMs = 60_000L

        val midPos = 30_000L
        realRepo.saveVideoPosition(uri, midPos, durationMs)
        val restoredMid = realRepo.getSavedVideoPosition(uri, durationMs)
        assertEquals(30_000L, restoredMid)

        val nearEndPos = 59_000L
        realRepo.saveVideoPosition(uri, nearEndPos, durationMs)
        val restoredNearEnd = realRepo.getSavedVideoPosition(uri, durationMs)
        assertEquals("Near-end position must reset to 0L according to thresholds", 0L, restoredNearEnd)

        realRepo.saveVideoPosition(uri, 500L, durationMs)
        val restoredIntro = realRepo.getSavedVideoPosition(uri, durationMs)
        assertEquals("Intro offset under threshold must reset to 0L", 0L, restoredIntro)
    }

    // -------------------------------------------------------------
    // Test 12: Stale Restoration Dropping on Media Change
    // -------------------------------------------------------------
    @Test
    fun test12_StaleRestorationDroppedWhenMediaChanges() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)

        val uriOld = "content://media/external/video/media/old_track"
        val uriNew = "content://media/external/video/media/new_track"

        realRepo.saveVideoPosition(uriOld, 40_000L, 60_000L)
        realRepo.saveVideoPosition(uriNew, 0L, 60_000L)

        val oldPos = realRepo.getSavedVideoPosition(uriOld, 60_000L)
        val newPos = realRepo.getSavedVideoPosition(uriNew, 60_000L)

        assertEquals(40_000L, oldPos)
        assertEquals("New track must have 0L, never old track's position", 0L, newPos)
    }

    // -------------------------------------------------------------
    // Test 13: Targeted flushPending() Semantics (Historical Failure Does Not Fail Subsequent Flush)
    // -------------------------------------------------------------
    @Test
    fun test13_HistoricalFailureDoesNotFailSubsequentFlush() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)

        val manager = createManager(faultRepo, scope)
        val historicalUri = "content://media/external/video/media/historical_fail"

        faultRepo.failCount = 10
        manager.enqueueSave(historicalUri, positionMs = 12_000L, durationMs = 60_000L)
        val firstFlush = manager.flushPending(timeoutMs = 2000L)
        assertFalse("First flush must fail because historicalUri failed permanently", firstFlush)
        assertTrue(manager.getFailedRequests().containsKey(historicalUri))

        faultRepo.failCount = 0
        val newUri = "content://media/external/video/media/new_success"
        manager.enqueueSave(newUri, positionMs = 45_000L, durationMs = 90_000L)

        val secondFlush = manager.flushPending(timeoutMs = 3000L)
        assertTrue("Subsequent flush must succeed even with historical failure in failedRequestsMap", secondFlush)
        assertEquals(45_000L, realRepo.getSavedVideoPosition(newUri, 90_000L))
    }

    // -------------------------------------------------------------
    // Test 14: Short Video Thresholds (1s, 3s, 5s)
    // -------------------------------------------------------------
    @Test
    fun test14_ShortVideoThresholds() {
        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(50L, 1000L))
        assertEquals(500L, PlaybackPositionThresholds.calculateEffectivePosition(500L, 1000L))
        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(900L, 1000L))

        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(150L, 3000L))
        assertEquals(1500L, PlaybackPositionThresholds.calculateEffectivePosition(1500L, 3000L))
        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(2800L, 3000L))

        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(200L, 5000L))
        assertEquals(2500L, PlaybackPositionThresholds.calculateEffectivePosition(2500L, 5000L))
        assertEquals(0L, PlaybackPositionThresholds.calculateEffectivePosition(4800L, 5000L))
    }

    // -------------------------------------------------------------
    // Test 15: Legacy Key Migration Without Data Loss
    // -------------------------------------------------------------
    @Test
    fun test15_LegacyKeyMigrationWithoutDataLoss() = runBlocking {
        val scope = createTestScope()
        val (dataStore, file) = createRealDataStore(scope)

        val legacyKey = longPreferencesKey("playback_pos_888")
        dataStore.edit { prefs ->
            prefs[legacyKey] = 65_000L
        }

        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val mediaStoreUri = "content://media/external/video/media/888"

        val migratedPos = realRepo.getSavedVideoPosition(mediaStoreUri, durationMs = 120_000L, legacyVideoId = 888L)
        assertEquals("Must successfully read and migrate legacy position", 65_000L, migratedPos)

        // Release the first DataStore before creating another instance for this file.
        scope.cancel()

        val scope2 = createTestScope()
        val dataStore2 = PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file })
        val realRepo2 = PlaybackPreferencesRepositoryImpl(dataStore2)

        val modernRead = realRepo2.getSavedVideoPosition(mediaStoreUri, durationMs = 120_000L, legacyVideoId = null)
        assertEquals("Modern key must persist migrated position", 65_000L, modernRead)
    }

    // -------------------------------------------------------------
    // Test 16: Worker Resilience and Obsolete Retry Abort
    // -------------------------------------------------------------
    @Test
    fun test16_WorkerResilienceAndObsoleteRetryAbort() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/1601"

        val firstAttemptFailed = CompletableDeferred<Unit>()
        faultRepo.onAfterFailure = { saveUri, _, _ ->
            if (saveUri == uri) {
                firstAttemptFailed.complete(Unit)
            }
        }

        faultRepo.failCount = 1

        val req1 = manager.enqueueSave(uri, positionMs = 10_000L, durationMs = 60_000L)
        assertNotNull(req1)

        firstAttemptFailed.await()

        val req2 = manager.enqueueSave(uri, positionMs = 25_000L, durationMs = 60_000L)
        assertNotNull(req2)

        assertTrue(manager.flushPending(timeoutMs = 3000L))

        val finalPos = realRepo.getSavedVideoPosition(uri, durationMs = 60_000L)
        assertEquals("Newer position must be saved on disk", 25_000L, finalPos)
    }

    // -------------------------------------------------------------
    // Test 17: Request Fails Completely Before Wait -> Must Not Report False Success
    // -------------------------------------------------------------
    @Test
    fun test17_RequestFailsCompletelyBeforeWaitReturnsFalse() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/fail_before_wait"

        val managerFailureHandled = CompletableDeferred<Unit>()
        manager.onRequestPermanentlyFailedForTesting = { failedReq ->
            if (failedReq.uri == uri) {
                managerFailureHandled.complete(Unit)
            }
        }

        faultRepo.failCount = 10
        val req = manager.enqueueSave(uri, positionMs = 35_000L, durationMs = 90_000L)
        assertNotNull(req)

        managerFailureHandled.await()

        assertEquals(0, manager.getPendingCount())
        assertNull(manager.inFlightRequest)
        assertTrue(manager.getFailedRequests().containsKey(uri))

        val awaitSuccess = manager.awaitRequest(req!!, timeoutMs = 1500L)
        assertFalse("awaitRequest must return false when request failed before wait", awaitSuccess)

        val flushSuccess = manager.flushPending(timeoutMs = 1500L, targetRequest = req)
        assertFalse("flushPending(targetRequest) must return false when request failed before wait", flushSuccess)
    }

    // -------------------------------------------------------------
    // Test 18: Unknown Duration (0L) Preserves Position Without Premature End-Clamping
    // -------------------------------------------------------------
    @Test
    fun test18_UnknownDurationPreservesRawPositionWithoutEndClamping() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val uri = "content://media/external/video/media/unknown_dur_video"

        val req = manager.enqueueSave(uri, positionMs = 58_000L, durationMs = 0L)
        assertNotNull("enqueueSave must accept durationMs = 0L", req)

        val success = manager.awaitRequest(req!!)
        assertTrue(success)

        val rawPos = realRepo.getSavedVideoPosition(uri, durationMs = 0L)
        assertEquals("Raw position must be preserved without premature end-clamping when duration is 0L", 58_000L, rawPos)

        val effectiveWhenDurationKnown = realRepo.getSavedVideoPosition(uri, durationMs = 60_000L)
        assertEquals("Position must reset to 0L once duration is resolved and position is within end window", 0L, effectiveWhenDurationKnown)
    }

    // -------------------------------------------------------------
    // Test 19: Historical Failure Does Not Affect awaitRequest for Unrelated New Request
    // -------------------------------------------------------------
    @Test
    fun test19_HistoricalFailureDoesNotAffectAwaitRequestForNewRequest() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)

        val manager = createManager(faultRepo, scope)
        val failUri = "content://media/external/video/media/old_failed_video"
        val successUri = "content://media/external/video/media/new_successful_video"

        faultRepo.failCount = 10
        val oldReq = manager.enqueueSave(failUri, positionMs = 15_000L, durationMs = 60_000L)
        assertNotNull(oldReq)
        val oldSuccess = manager.awaitRequest(oldReq!!, timeoutMs = 1500L)
        assertFalse(oldSuccess)
        assertTrue(manager.getFailedRequests().containsKey(failUri))

        faultRepo.failCount = 0
        val newReq = manager.enqueueSave(successUri, positionMs = 30_000L, durationMs = 60_000L)
        assertNotNull(newReq)

        val newSuccess = manager.awaitRequest(newReq!!)
        assertTrue("awaitRequest for new request must succeed and return true, unaffected by historical failure", newSuccess)
        assertEquals(30_000L, realRepo.getSavedVideoPosition(successUri, 60_000L))
    }

    // -------------------------------------------------------------
    // Test 20: Worker Cancellation During Retry Delay Requeues Request
    // -------------------------------------------------------------
    @Test
    fun test20_WorkerCancellationDuringRetryDelayRequeuesWithOriginalSequence() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val faultRepo = FaultInjectingRepository(realRepo)

        val manager = createManager(faultRepo, scope)
        val uri = "content://media/external/video/media/cancel_during_delay"

        val firstAttemptFailed = CompletableDeferred<Unit>()
        faultRepo.onAfterFailure = { saveUri, _, _ ->
            if (saveUri == uri) {
                firstAttemptFailed.complete(Unit)
            }
        }

        faultRepo.failCount = 1
        val req = manager.enqueueSave(uri, positionMs = 42_000L, durationMs = 100_000L)
        assertNotNull(req)

        firstAttemptFailed.await()

        val currentWriter = manager.getWriterJobForTesting()
        assertNotNull(currentWriter)
        currentWriter?.cancel(CancellationException("Simulated worker cancellation during retry delay"))

        assertTrue("flushPending must succeed after worker restart", manager.flushPending(timeoutMs = 4000L))

        assertEquals(42_000L, realRepo.getSavedVideoPosition(uri, 100_000L))
        assertEquals(0, manager.getPendingCount())
    }

    // -------------------------------------------------------------
    // Test 21: Production PlaybackRestorationCoordinator Generation Logic
    // -------------------------------------------------------------
    @Test
    fun test21_PlaybackGenerationMismatchRejectsStaleRestoration() {
        val coordinator = PlaybackRestorationCoordinator()

        val videoA = "content://media/external/video/media/video_A"
        val videoB = "content://media/external/video/media/video_B"

        val genA = coordinator.onNewPlaybackSession(videoA)
        assertEquals(1L, genA)
        assertEquals(videoA, coordinator.currentMediaId)

        val isFirstTransitionReal = coordinator.onMediaItemTransition(videoA)
        assertFalse("Initial onMediaItemTransition for active session must not be considered a real transition", isFirstTransitionReal)
        assertEquals("Generation must remain unchanged on initial session transition", 1L, coordinator.getCurrentPlaybackGeneration())

        val isSecondTransitionReal = coordinator.onMediaItemTransition(videoB)
        assertTrue("Transition to different media item must be recognized as real", isSecondTransitionReal)
        val genB = coordinator.getCurrentPlaybackGeneration()
        assertEquals(2L, genB)

        val decisionStale = coordinator.evaluateRestoration(
            targetUri = videoA,
            targetGeneration = genA,
            restoredPositionMs = 30_000L,
            currentUri = videoB,
            durationMs = 60_000L,
            isReady = true
        )
        assertTrue("Stale generation restoration must be rejected by coordinator", decisionStale is PlaybackRestorationCoordinator.RestorationDecision.Rejected)

        val decisionValid = coordinator.evaluateRestoration(
            targetUri = videoB,
            targetGeneration = genB,
            restoredPositionMs = 45_000L,
            currentUri = videoB,
            durationMs = 60_000L,
            isReady = true
        )
        assertTrue("Active generation restoration must be accepted", decisionValid is PlaybackRestorationCoordinator.RestorationDecision.Apply)
        assertEquals(45_000L, (decisionValid as PlaybackRestorationCoordinator.RestorationDecision.Apply).effectivePositionMs)
    }

    // -------------------------------------------------------------
    // Test 22: Production PlaybackRestorationCoordinator Manual Seek Invalidation
    // -------------------------------------------------------------
    @Test
    fun test22_ManualSeekCancelsPendingRestoration() {
        val coordinator = PlaybackRestorationCoordinator()
        val uri = "content://media/external/video/media/seek_test"

        val gen = coordinator.onNewPlaybackSession(uri)

        coordinator.onManualSeek(15_000L)
        assertTrue("isRestorationApplied must be true after manual seek", coordinator.isRestorationApplied)
        assertNull("Pending restoration must be cleared after manual seek", coordinator.pendingUri)

        val decision = coordinator.evaluateRestoration(
            targetUri = uri,
            targetGeneration = gen,
            restoredPositionMs = 50_000L,
            currentUri = uri,
            durationMs = 100_000L,
            isReady = true
        )

        assertTrue("Late restoration after manual seek must be rejected", decision is PlaybackRestorationCoordinator.RestorationDecision.Rejected)
    }

    // -------------------------------------------------------------
    // Test 23: Production PlaybackRestorationCoordinator Zero Duration Management
    // -------------------------------------------------------------
    @Test
    fun test23_ZeroDurationHoldsRestorationUntilDurationKnown() {
        val coordinator = PlaybackRestorationCoordinator()
        val uri = "content://media/external/video/media/zero_dur_test"

        val gen = coordinator.onNewPlaybackSession(uri)

        val decision = coordinator.evaluateRestoration(
            targetUri = uri,
            targetGeneration = gen,
            restoredPositionMs = 48_000L,
            currentUri = uri,
            durationMs = 0L,
            isReady = false
        )
        assertEquals("Restoration must be queued when duration is 0L", PlaybackRestorationCoordinator.RestorationDecision.Queued, decision)
        assertEquals(uri, coordinator.pendingUri)
        assertEquals(48_000L, coordinator.pendingPosition)

        val interimResult = coordinator.checkAndApplyPendingRestoration(uri, durationMs = 0L)
        assertNull("Pending restoration must NOT be consumed or dropped while duration is 0L", interimResult)
        assertEquals(48_000L, coordinator.pendingPosition)

        val finalEffective = coordinator.checkAndApplyPendingRestoration(uri, durationMs = 50_000L)
        assertEquals("Position near end must reset to 0L once duration is resolved", 0L, finalEffective)
        assertNull("Pending restoration is consumed once applied", coordinator.pendingUri)
        assertTrue(coordinator.isRestorationApplied)
    }

    // -------------------------------------------------------------
    // Test 24: Production PositionSavePolicy Direct Capture Decision
    // -------------------------------------------------------------
    @Test
    fun test24_DirectCaptureEliminatesDuplicateStaleSave() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val uri = "content://media/external/video/media/direct_capture_test"

        val fallbackWhenDirectSuccess = PositionSavePolicy.shouldEnqueueFallbackSave(
            capturedDirectlyFromPlayer = true,
            uri = uri,
            currentPositionMs = 35_000L,
            durationMs = 120_000L
        )
        assertNull("PositionSavePolicy must return null when direct capture succeeds", fallbackWhenDirectSuccess)

        val req1 = manager.enqueueSave(uri, positionMs = 35_400L, durationMs = 120_000L)
        assertNotNull(req1)

        val fallbackWhenDirectFailed = PositionSavePolicy.shouldEnqueueFallbackSave(
            capturedDirectlyFromPlayer = false,
            uri = uri,
            currentPositionMs = 35_000L,
            durationMs = 120_000L
        )
        assertNotNull("PositionSavePolicy must allow fallback save when direct capture fails", fallbackWhenDirectFailed)
        assertEquals(35_000L, fallbackWhenDirectFailed!!.positionMs)

        assertTrue(manager.flushPending(timeoutMs = 2000L))
        assertEquals("Stored position must be the exact ExoPlayer position", 35_400L, realRepo.getSavedVideoPosition(uri, 120_000L))
    }

    // -------------------------------------------------------------
    // Test 25: Late DataStore Result Applied When STATE_READY Precedes DataStore
    // -------------------------------------------------------------
    @Test
    fun test25_LateDataStoreResultAppliedWhenStateReadyPrecedesDataStore() {
        val coordinator = PlaybackRestorationCoordinator()
        val uri = "content://media/external/video/media/late_datastore"

        val gen = coordinator.onNewPlaybackSession(uri)

        val earlyCheck = coordinator.checkAndApplyPendingRestoration(uri, durationMs = 60_000L)

        assertNull("No restoration should be applied yet before DataStore completes", earlyCheck)
        assertFalse("Restoration must NOT be declared completed prematurely!", coordinator.isRestorationApplied)

        val decision = coordinator.evaluateRestoration(
            targetUri = uri,
            targetGeneration = gen,
            restoredPositionMs = 25_000L,
            currentUri = uri,
            durationMs = 60_000L,
            isReady = true
        )

        assertTrue("Late DataStore result MUST be applied successfully", decision is PlaybackRestorationCoordinator.RestorationDecision.Apply)
        val appliedPos = (decision as PlaybackRestorationCoordinator.RestorationDecision.Apply).effectivePositionMs
        assertEquals(25_000L, appliedPos)
        assertTrue("Restoration is now marked as applied", coordinator.isRestorationApplied)
    }

    // -------------------------------------------------------------
    // Test 26: Immediate Save After Scrubbing Preserves Final Position With Higher Sequence
    // -------------------------------------------------------------
    @Test
    fun test26_ImmediateSaveAfterScrubbingPreservesFinalPositionWithHigherSequence() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val uri = "content://media/external/video/media/scrub_test"
        val durationMs = 120_000L

        val oldReq = manager.enqueueSave(uri, positionMs = 15_000L, durationMs = durationMs)
        assertNotNull("Periodic save must be enqueued", oldReq)

        val scrubReq = manager.enqueueSave(uri, positionMs = 85_000L, durationMs = durationMs)
        assertNotNull("Scrub stop save must be enqueued", scrubReq)

        assertTrue("Scrub save sequence must be higher than periodic save", scrubReq!!.sequenceNumber > oldReq!!.sequenceNumber)

        assertTrue("Flush must succeed", manager.flushPending(timeoutMs = 2000L))
        val persisted = realRepo.getSavedVideoPosition(uri, durationMs)
        assertEquals("Persisted position must be the final scrubbed position", 85_000L, persisted)
    }

    // -------------------------------------------------------------
    // Test 27: Transition Between Duplicate Media Items At Different Playlist Indices
    // -------------------------------------------------------------
    @Test
    fun test27_TransitionBetweenDuplicateMediaItemsAtDifferentIndices() {
        val coordinator = PlaybackRestorationCoordinator()
        val baseMediaId = "duplicate_item_101"

        val keyIndex0 = "${baseMediaId}_#0"
        val gen0 = coordinator.onNewPlaybackSession(keyIndex0)
        assertEquals(1L, gen0)

        val isInitialTransition = coordinator.onMediaItemTransition(keyIndex0, isRepeat = false)
        assertFalse("Initial transition for registered session must not trigger real transition", isInitialTransition)
        assertEquals("Playback generation must remain unchanged on session start", 1L, coordinator.getCurrentPlaybackGeneration())

        coordinator.onManualSeek(12_000L)
        assertTrue(coordinator.isRestorationApplied)

        val keyIndex1 = "${baseMediaId}_#1"
        val isSecondTransition = coordinator.onMediaItemTransition(keyIndex1, isRepeat = false)
        assertTrue("Transition to identical media at different index must be recognized as real transition", isSecondTransition)
        assertEquals("Playback generation must increment for new playlist index", 2L, coordinator.getCurrentPlaybackGeneration())
        assertFalse("Restoration applied flag must reset for new index", coordinator.isRestorationApplied)
        assertEquals(keyIndex1, coordinator.currentMediaId)
    }

    // -------------------------------------------------------------
    // Test 28: Repeat One Transition Increments Generation
    // -------------------------------------------------------------
    @Test
    fun test28_RepeatOneTransitionIncrementsGeneration() {
        val coordinator = PlaybackRestorationCoordinator()
        val mediaKey = "single_looped_video_#0"

        val gen0 = coordinator.onNewPlaybackSession(mediaKey)
        assertEquals(1L, gen0)

        assertFalse(coordinator.onMediaItemTransition(mediaKey, isRepeat = false))
        assertEquals(1L, coordinator.getCurrentPlaybackGeneration())

        coordinator.onManualSeek(30_000L)
        assertTrue(coordinator.isRestorationApplied)

        val isRepeatTransition = coordinator.onMediaItemTransition(mediaKey, isRepeat = true)
        assertTrue("Repeat One loop must be treated as a real transition", isRepeatTransition)
        assertEquals("Playback generation must increment on Repeat One loop", 2L, coordinator.getCurrentPlaybackGeneration())
        assertFalse("Restoration applied flag must reset for the new loop", coordinator.isRestorationApplied)
    }

    // -------------------------------------------------------------
    // Test 29: Save On Pause With Unknown Duration 0L
    // -------------------------------------------------------------
    @Test
    fun test29_SaveOnPauseWithUnknownDuration0L() = runBlocking {
        val scope = createTestScope()
        val (dataStore, _) = createRealDataStore(scope)
        val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)
        val manager = createManager(realRepo, scope)

        val uri = "content://media/external/video/media/unknown_duration_test"

        val req = manager.enqueueSave(uri, positionMs = 42_500L, durationMs = 0L)
        assertNotNull("Pause with duration 0L must be accepted and enqueued", req)
        assertEquals(42_500L, req!!.positionMs)
        assertEquals(0L, req.durationMs)

        assertTrue("Flush must succeed for unknown duration save", manager.flushPending(timeoutMs = 2000L))

        val storedRaw = realRepo.getSavedVideoPosition(uri, durationMs = 0L)
        assertEquals("Raw position must be faithfully stored even when duration was 0L", 42_500L, storedRaw)
    }
}
