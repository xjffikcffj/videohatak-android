package com.videohatak.player.core.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PositionSaveRequest(
    val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val sequenceNumber: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

/**
 * Application-scoped playback position persistence manager.
 *
 * Core Architecture & Guarantees:
 * 1. Single Serial Writer: A single background worker coroutine processes requests from an unbounded
 *    Channel, eliminating racing writers (no separate "normal worker" vs "emergency flush" competing).
 * 2. Monotonic Ordering: Every update receives a strictly monotonic sequenceNumber (AtomicLong),
 *    guaranteeing that an older update never overwrites a newer update, even across delayed tasks or retries.
 * 3. Application Lifecycle Ownership: Runs on an application-level CoroutineScope (SupervisorJob + Dispatchers.IO),
 *    so closing or destroying a ViewModel does not cancel, truncate, or abort pending writes.
 * 4. Non-Blocking Teardown: ViewModel.onCleared() enqueues final positions without blocking the Android
 *    Main Thread via runBlocking.
 * 5. Transparent Error Tracking & Recovery: Requests that exhaust all retries are recorded in failedRequests,
 *    never silently dropped claiming false success, and can be retried via retryFailedRequests().
 */
class PlaybackPositionManager(
    val repository: PlaybackPreferencesRepository,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "PlaybackPositionManager"
        const val MAX_RETRIES = 3

        @Volatile
        private var instance: PlaybackPositionManager? = null

        fun getInstance(context: Context): PlaybackPositionManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackPositionManager(
                    repository = PlaybackPreferencesRepositoryImpl(context.applicationContext)
                ).also { instance = it }
            }
        }

        fun resetInstanceForTesting() {
            instance = null
        }
    }

    private val sequenceCounter = AtomicLong(0L)
    val saveChannel = Channel<PositionSaveRequest>(Channel.UNLIMITED)

    private val stateLock = Any()
    private val pendingByUri = mutableMapOf<String, PositionSaveRequest>()
    private val lastSavedSequencePerUri = ConcurrentHashMap<String, Long>()
    private val failedRequestsMap = ConcurrentHashMap<String, PositionSaveRequest>()

    @Volatile
    private var isClosed = false

    @Volatile
    var inFlightRequest: PositionSaveRequest? = null
        internal set

    var onRequestPermanentlyFailedForTesting: ((PositionSaveRequest) -> Unit)? = null

    private var writerJob: Job? = null

    init {
        startWriter()
    }

    private fun startWriter() {
        synchronized(stateLock) {
            if (isClosed) return
            val currentJob = writerJob
            if (currentJob != null && currentJob.isActive) return
            writerJob = scope.launch {
                try {
                    for (req in saveChannel) {
                        try {
                            processSingleRequest(req)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error processing single request for ${req.uri}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Save worker terminated unexpectedly with exception", e)
                } finally {
                    synchronized(stateLock) {
                        if (writerJob == coroutineContext[Job]) {
                            writerJob = null
                        }
                        if (!isClosed) {
                            Log.i(TAG, "Restarting save worker...")
                            startWriter()
                        }
                    }
                }
            }
        }
    }

    /**
     * Enqueues a position save request.
     * Conflates requests per URI so only the latest sequence is kept in the pending queue.
     * Returns the PositionSaveRequest if successfully enqueued, or null if rejected.
     */
    fun enqueueSave(uri: String, positionMs: Long, durationMs: Long = 0L): PositionSaveRequest? {
        if (uri.isBlank() || positionMs < 0L) return null
        val dur = durationMs.coerceAtLeast(0L)

        val seq = sequenceCounter.incrementAndGet()
        val request = PositionSaveRequest(
            uri = uri,
            positionMs = positionMs,
            durationMs = dur,
            sequenceNumber = seq,
            timestamp = System.currentTimeMillis()
        )

        synchronized(stateLock) {
            if (isClosed) {
                Log.w(TAG, "Manager is closed; rejecting save for $uri")
                failedRequestsMap[uri] = request
                return null
            }

            val existing = pendingByUri[uri]
            if (existing != null && existing.sequenceNumber > seq) {
                // Obsolete: newer request already pending
                return null
            }
            pendingByUri[uri] = request
            // If a newer save is enqueued, remove any previous failed request for this URI
            failedRequestsMap.remove(uri)
        }

        val sendResult = saveChannel.trySend(request)
        if (!sendResult.isSuccess) {
            Log.e(TAG, "Failed to send request for $uri into saveChannel: channel rejected")
            synchronized(stateLock) {
                if (pendingByUri[uri]?.sequenceNumber == seq) {
                    pendingByUri.remove(uri)
                }
                failedRequestsMap[uri] = request
            }
            return null
        }

        return request
    }

    /**
     * Internal processing of a single save request by the single serial writer.
     */
    suspend fun processSingleRequest(req: PositionSaveRequest) {
        // Obsolete check: if a newer sequence was already written to disk for this URI, skip
        val lastSavedSeq = lastSavedSequencePerUri[req.uri] ?: 0L
        if (req.sequenceNumber < lastSavedSeq) {
            Log.d(TAG, "Skipping obsolete save for ${req.uri} (seq ${req.sequenceNumber} < lastSaved $lastSavedSeq)")
            synchronized(stateLock) {
                if (pendingByUri[req.uri]?.sequenceNumber == req.sequenceNumber) {
                    pendingByUri.remove(req.uri)
                }
            }
            return
        }

        // Check if a newer request for this URI is already queued in pendingByUri
        val isNewerQueued = synchronized(stateLock) {
            val pending = pendingByUri[req.uri]
            pending != null && pending.sequenceNumber > req.sequenceNumber
        }
        if (isNewerQueued) {
            Log.d(TAG, "Skipping save for ${req.uri}: newer request (seq > ${req.sequenceNumber}) already queued")
            return
        }

        var permanentlyFailed: PositionSaveRequest? = null
        try {
            inFlightRequest = req
            var current = req

            while (current.retryCount <= MAX_RETRIES) {
                // Check if a newer request for this URI arrived while we were waiting/retrying
                val isObsolete = synchronized(stateLock) {
                    val pending = pendingByUri[current.uri]
                    pending != null && pending.sequenceNumber > current.sequenceNumber
                }
                if (isObsolete) {
                    Log.d(TAG, "Aborting retries for ${current.uri}: newer request (seq > ${current.sequenceNumber}) is pending")
                    break
                }

                try {
                    withContext(NonCancellable) {
                        repository.saveVideoPosition(current.uri, current.positionMs, current.durationMs)
                    }
                    lastSavedSequencePerUri[current.uri] = current.sequenceNumber

                    synchronized(stateLock) {
                        // Only remove from pending if current request matches the pending request
                        if (pendingByUri[current.uri]?.sequenceNumber == current.sequenceNumber) {
                            pendingByUri.remove(current.uri)
                        }
                        failedRequestsMap.remove(current.uri)
                    }
                    Log.d(TAG, "Successfully persisted position for ${current.uri}: ${current.positionMs}ms (seq=${current.sequenceNumber})")
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Save attempt ${current.retryCount + 1}/${MAX_RETRIES + 1} failed for ${current.uri}", e)
                    if (current.retryCount < MAX_RETRIES) {
                        current = current.copy(retryCount = current.retryCount + 1)
                        try {
                            delay(50L * (1 shl current.retryCount))
                        } catch (ce: CancellationException) {
                            synchronized(stateLock) {
                                val isStillPending = pendingByUri[current.uri]?.sequenceNumber == current.sequenceNumber
                                val lastSaved = lastSavedSequencePerUri[current.uri] ?: 0L
                                if (!isClosed && isStillPending && lastSaved < current.sequenceNumber) {
                                    saveChannel.trySend(current)
                                }
                            }
                            throw ce
                        }
                    } else {
                        Log.e(TAG, "Permanent failure saving position for ${current.uri} after ${MAX_RETRIES + 1} attempts.", e)
                        synchronized(stateLock) {
                            // Request failed permanently: remove from pendingByUri and add to failedRequestsMap
                            if (pendingByUri[current.uri]?.sequenceNumber == current.sequenceNumber) {
                                pendingByUri.remove(current.uri)
                            }
                            failedRequestsMap[current.uri] = current
                        }
                        permanentlyFailed = current
                        break
                    }
                }
            }
        } finally {
            // Guaranteed cleanup of inFlightRequest even if cancelled or on exception
            inFlightRequest = null
            permanentlyFailed?.let { failedReq ->
                onRequestPermanentlyFailedForTesting?.invoke(failedReq)
            }
        }
    }

    /**
     * Retries all requests currently in failedRequestsMap.
     * Enqueues them through the same serial channel without creating competing writers.
     * Skips requests if a newer update for the URI has already been saved or queued.
     * Returns the count of successfully re-enqueued requests.
     */
    fun retryFailedRequests(): Int {
        val toRetry = synchronized(stateLock) {
            failedRequestsMap.values.toList()
        }
        var count = 0
        for (failed in toRetry) {
            val lastSaved = lastSavedSequencePerUri[failed.uri] ?: 0L
            val isObsolete = synchronized(stateLock) {
                val pending = pendingByUri[failed.uri]
                (pending != null && pending.sequenceNumber > failed.sequenceNumber) || (lastSaved > failed.sequenceNumber)
            }
            if (isObsolete) {
                synchronized(stateLock) {
                    failedRequestsMap.remove(failed.uri)
                }
                continue
            }
            val enqueued = enqueueSave(failed.uri, failed.positionMs, failed.durationMs)
            if (enqueued != null) {
                synchronized(stateLock) {
                    failedRequestsMap.remove(failed.uri)
                }
                count++
            }
        }
        return count
    }

    /**
     * Awaits the final persistence outcome of a specific PositionSaveRequest (save ticket).
     * Returns true if successfully saved to disk.
     * Returns false if the request failed permanently, was rejected, or if timeout expired.
     * Historical failures for other URIs have zero impact on this result.
     */
    suspend fun awaitRequest(request: PositionSaveRequest, timeoutMs: Long = 5000L): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // 1. Success check: Has this sequence (or newer) been saved to repository?
            val lastSaved = lastSavedSequencePerUri[request.uri] ?: 0L
            if (lastSaved >= request.sequenceNumber) {
                return true
            }

            // 2. Failure check: Did this request exhaust retries and land in failedRequestsMap?
            val isFailed = synchronized(stateLock) {
                val f = failedRequestsMap[request.uri]
                f != null && f.sequenceNumber == request.sequenceNumber
            }
            if (isFailed) {
                return false
            }

            // 3. Obsolete check: Was a newer request already saved for this URI?
            if (lastSaved > request.sequenceNumber) {
                return true
            }

            delay(20)
        }

        // Final check upon timeout expiry
        val lastSaved = lastSavedSequencePerUri[request.uri] ?: 0L
        return lastSaved >= request.sequenceNumber
    }

    /**
     * Awaits completion of requests targeted by the current flush call.
     * If targetRequest is provided, delegates directly to awaitRequest(targetRequest, timeoutMs).
     * If targetRequest is null, waits until all currently pending/in-flight writes finish.
     */
    suspend fun flushPending(timeoutMs: Long = 5000L, targetRequest: PositionSaveRequest? = null): Boolean {
        if (targetRequest != null) {
            return awaitRequest(targetRequest, timeoutMs)
        }

        val targetSeq = sequenceCounter.get()
        val targetedSeqs = synchronized(stateLock) {
            val pendingSeqs = pendingByUri.values.filter { it.sequenceNumber <= targetSeq }.map { it.sequenceNumber }
            val inFlightSeq = inFlightRequest?.let { if (it.sequenceNumber <= targetSeq) listOf(it.sequenceNumber) else emptyList() } ?: emptyList()
            (pendingSeqs + inFlightSeq).toSet()
        }

        // If no targeted request is currently pending or in-flight, check if the latest request failed
        if (targetedSeqs.isEmpty()) {
            val latestFailed = synchronized(stateLock) {
                failedRequestsMap.values.any { it.sequenceNumber == targetSeq }
            }
            return !latestFailed
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val hasTargetPending = synchronized(stateLock) {
                pendingByUri.values.any { it.sequenceNumber <= targetSeq }
            }
            val hasTargetInFlight = inFlightRequest?.let { it.sequenceNumber <= targetSeq } ?: false
            if (!hasTargetPending && !hasTargetInFlight) {
                // Check if any request targeted by this specific flush failed permanently
                val targetedFailed = synchronized(stateLock) {
                    failedRequestsMap.values.any { it.sequenceNumber in targetedSeqs }
                }
                return !targetedFailed
            }
            delay(20)
        }

        val hasTargetPending = synchronized(stateLock) {
            pendingByUri.values.any { it.sequenceNumber <= targetSeq }
        }
        val hasTargetInFlight = inFlightRequest?.let { it.sequenceNumber <= targetSeq } ?: false
        val targetedFailed = synchronized(stateLock) {
            failedRequestsMap.values.any { it.sequenceNumber in targetedSeqs }
        }
        return !hasTargetPending && !hasTargetInFlight && !targetedFailed
    }

    /**
     * Orderly shutdown of manager and writer job for tests or application termination.
     * Ensures all pending writes drain before closing the channel.
     */
    suspend fun shutdown(drainTimeoutMs: Long = 3000L) {
        synchronized(stateLock) {
            isClosed = true
        }
        flushPending(drainTimeoutMs)
        saveChannel.close()
        writerJob?.cancel()
    }

    fun getFailedRequests(): Map<String, PositionSaveRequest> = failedRequestsMap.toMap()

    fun getPendingCount(): Int = synchronized(stateLock) { pendingByUri.size }

    fun getLastSavedSequence(uri: String): Long = lastSavedSequencePerUri[uri] ?: 0L

    fun getCurrentSequenceNumber(): Long = sequenceCounter.get()

    fun getWriterJobForTesting(): Job? = synchronized(stateLock) { writerJob }
}
