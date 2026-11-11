package com.videohatak.player.core.thumbnail.cache

import android.graphics.Bitmap
import android.util.LruCache

class ThumbnailCache(val maxCacheSizeKb: Int = ThumbnailCache.calculateSafeMemoryLimitKb()) {

    // Secondary constructor supporting item count if maxSize is passed
    constructor(maxSize: Int) : this(maxCacheSizeKb = (maxSize * 64).coerceAtLeast(1024))

    companion object {
        fun calculateSafeMemoryLimitKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            // Allocate 5% of max heap, clamped between 4MB (4096KB) and 20MB (20480KB)
            return (maxMemoryKb / 20).coerceIn(4096, 20480)
        }
    }

    private val lruCache = object : LruCache<Long, Bitmap>(maxCacheSizeKb) {
        override fun sizeOf(key: Long, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun quantizePosition(positionMs: Long): Long {
        if (positionMs <= 0L) return 0L
        return (positionMs / 1000L) * 1000L
    }

    fun get(positionMs: Long): Bitmap? {
        val roundedKey = quantizePosition(positionMs)
        return lruCache.get(roundedKey)
    }

    fun put(positionMs: Long, bitmap: Bitmap) {
        val roundedKey = quantizePosition(positionMs)
        lruCache.put(roundedKey, bitmap)
    }

    fun clear() {
        lruCache.evictAll()
    }
}
