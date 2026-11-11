package com.videohatak.player.core.thumbnail.manager

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.thumbnail.cache.ThumbnailCache
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import com.videohatak.player.core.thumbnail.retriever.ThumbnailRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ThumbnailPreviewManager(context: Context) {
    private val retriever = ThumbnailRetriever(context)
    private val cache = ThumbnailCache(maxCacheSizeKb = ThumbnailCache.calculateSafeMemoryLimitKb())
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _previewState = MutableStateFlow(ThumbnailPreviewState())
    val previewState: StateFlow<ThumbnailPreviewState> = _previewState.asStateFlow()

    private var activeJob: Job? = null
    private var currentVideoUri: Uri? = null

    fun setVideoUri(uri: Uri?) {
        if (currentVideoUri != uri) {
            currentVideoUri = uri
            cache.clear()
            _previewState.update { ThumbnailPreviewState() }
        }
    }

    fun onScrubStart(positionMs: Long, fraction: Float) {
        _previewState.update {
            it.copy(
                isVisible = true,
                positionMs = positionMs,
                xOffsetFraction = fraction
            )
        }
        fetchThumbnail(positionMs)
    }

    fun onScrubProgress(positionMs: Long, fraction: Float) {
        _previewState.update {
            it.copy(
                positionMs = positionMs,
                xOffsetFraction = fraction
            )
        }
        fetchThumbnail(positionMs)
    }

    fun onScrubStop() {
        activeJob?.cancel()
        _previewState.update { it.copy(isVisible = false) }
    }

    private fun fetchThumbnail(positionMs: Long) {
        val uri = currentVideoUri ?: return
        val cached = cache.get(positionMs)
        if (cached != null) {
            _previewState.update { it.copy(bitmap = cached) }
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val bitmap = retriever.extractFrame(uri, positionMs)
            if (bitmap != null) {
                cache.put(positionMs, bitmap)
                _previewState.update {
                    if (it.isVisible && it.positionMs == positionMs) {
                        it.copy(bitmap = bitmap)
                    } else it
                }
            }
        }
    }
}
