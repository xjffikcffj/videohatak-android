package com.videohatak.player.core.playback

/**
 * Encapsulates coordinated playback restoration logic, generation tracking,
 * and manual seek invalidation across video sessions and playlist transitions.
 */
class PlaybackRestorationCoordinator {

    sealed class RestorationDecision {
        data class Apply(val effectivePositionMs: Long) : RestorationDecision()
        object Queued : RestorationDecision()
        data class Rejected(val reason: String) : RestorationDecision()
    }

    private var playbackGeneration: Long = 0L
    private var currentSessionMediaId: String? = null
    private var pendingRestorationUri: String? = null
    private var pendingRestorationPositionMs: Long? = null
    private var pendingRestorationGeneration: Long? = null
    private var isRestorationAppliedForCurrentUri = false

    val isRestorationApplied: Boolean get() = isRestorationAppliedForCurrentUri
    val pendingUri: String? get() = pendingRestorationUri
    val pendingPosition: Long? get() = pendingRestorationPositionMs
    val pendingGeneration: Long? get() = pendingRestorationGeneration
    val currentMediaId: String? get() = currentSessionMediaId

    fun getCurrentPlaybackGeneration(): Long = playbackGeneration

    /**
     * Called when a new playback session is initiated via playMedia or playPlaylist.
     * Advances generation and records the expected media identifier so the subsequent
     * initial onMediaItemTransition does NOT increment generation a second time.
     */
    fun onNewPlaybackSession(mediaIdentifier: String): Long {
        playbackGeneration++
        currentSessionMediaId = mediaIdentifier
        pendingRestorationUri = null
        pendingRestorationPositionMs = null
        pendingRestorationGeneration = null
        isRestorationAppliedForCurrentUri = false
        return playbackGeneration
    }

    /**
     * Called when ExoPlayer transitions to a media item.
     * Distinguishes the first transition of the current session from an actual transition
     * to a different/subsequent item in a playlist or repeat loop.
     *
     * @return true if an actual transition occurred (new generation started), false if it was
     * the initial transition for the already registered session.
     */
    fun onMediaItemTransition(mediaIdentifier: String, isRepeat: Boolean = false): Boolean {
        if (!isRepeat && currentSessionMediaId != null && currentSessionMediaId == mediaIdentifier) {
            // Initial transition for the session we already started; keep current generation intact!
            return false
        }

        // Real transition to a different item or repeat loop
        playbackGeneration++
        currentSessionMediaId = mediaIdentifier
        pendingRestorationUri = null
        pendingRestorationPositionMs = null
        pendingRestorationGeneration = null
        isRestorationAppliedForCurrentUri = false
        return true
    }

    /**
     * Invalidate any pending restoration when user performs a manual seek.
     * Prevents late DataStore read from snapping the player back.
     */
    fun onManualSeek(seekPositionMs: Long) {
        pendingRestorationUri = null
        pendingRestorationPositionMs = null
        pendingRestorationGeneration = null
        isRestorationAppliedForCurrentUri = true
    }

    /**
     * Checks if a pending restoration matches the active URI and current generation,
     * and applies it once duration is known (> 0L).
     *
     * CRITICAL: If no pending restoration matches, state is left unchanged!
     * Never marks isRestorationApplied = true prematurely while DataStore is still reading.
     */
    fun checkAndApplyPendingRestoration(currentUri: String?, durationMs: Long): Long? {
        if (currentUri.isNullOrBlank() || durationMs <= 0L) return null

        if (pendingRestorationUri == currentUri &&
            pendingRestorationGeneration == playbackGeneration &&
            !isRestorationAppliedForCurrentUri
        ) {
            val raw = pendingRestorationPositionMs ?: 0L
            pendingRestorationUri = null
            pendingRestorationPositionMs = null
            pendingRestorationGeneration = null
            isRestorationAppliedForCurrentUri = true
            return PlaybackPositionThresholds.calculateEffectivePosition(raw, durationMs)
        }

        return null
    }

    /**
     * Evaluates incoming restoration position from DataStore.
     * - Rejects if URI or generation does not match active session.
     * - Rejects if restoration was already applied or user sought manually.
     * - Applies immediately if duration is already known (> 0L) and player is ready.
     * - Queues restoration if duration is not yet known (0L) or player is still preparing.
     */
    fun evaluateRestoration(
        targetUri: String,
        targetGeneration: Long,
        restoredPositionMs: Long,
        currentUri: String?,
        durationMs: Long,
        isReady: Boolean
    ): RestorationDecision {
        if (currentUri == null || currentUri != targetUri || targetGeneration != playbackGeneration) {
            return RestorationDecision.Rejected(
                "Dropping stale restoration for $targetUri (gen $targetGeneration vs current $playbackGeneration, active URI $currentUri)"
            )
        }

        if (isRestorationAppliedForCurrentUri) {
            return RestorationDecision.Rejected(
                "Restoration already applied or overridden for $targetUri (generation $targetGeneration)"
            )
        }

        if (durationMs > 0L && isReady) {
            val effective = PlaybackPositionThresholds.calculateEffectivePosition(restoredPositionMs, durationMs)
            isRestorationAppliedForCurrentUri = true
            pendingRestorationUri = null
            pendingRestorationPositionMs = null
            pendingRestorationGeneration = null
            return RestorationDecision.Apply(effective)
        } else {
            pendingRestorationUri = targetUri
            pendingRestorationPositionMs = restoredPositionMs
            pendingRestorationGeneration = targetGeneration
            return RestorationDecision.Queued
        }
    }

    fun onPlaybackStopped() {
        playbackGeneration++
        currentSessionMediaId = null
        pendingRestorationUri = null
        pendingRestorationPositionMs = null
        pendingRestorationGeneration = null
        isRestorationAppliedForCurrentUri = false
    }
}

/**
 * Pure policy determining whether fallback UI position should be enqueued.
 * Prevents redundant/stale position saves when ExoPlayer direct capture succeeds.
 */
object PositionSavePolicy {
    data class FallbackSaveData(val uri: String, val positionMs: Long, val durationMs: Long)

    fun shouldEnqueueFallbackSave(
        capturedDirectlyFromPlayer: Boolean,
        uri: String?,
        currentPositionMs: Long,
        durationMs: Long
    ): FallbackSaveData? {
        if (capturedDirectlyFromPlayer) return null
        if (uri.isNullOrBlank() || currentPositionMs < 0L || durationMs < 0L) return null
        return FallbackSaveData(uri, currentPositionMs, durationMs)
    }
}
