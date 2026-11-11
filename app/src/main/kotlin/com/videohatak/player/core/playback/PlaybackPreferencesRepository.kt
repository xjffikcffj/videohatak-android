package com.videohatak.player.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "videohatak_playback")

interface PlaybackPreferencesRepository {
    val playbackSpeed: Flow<Float>
    val resizeMode: Flow<Int>
    val repeatMode: Flow<String>
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun setResizeMode(mode: Int)
    suspend fun setRepeatMode(mode: String)
    suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long)
    suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long = 0L, legacyVideoId: Long? = null): Long
}

class PlaybackPreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : PlaybackPreferencesRepository {

    constructor(context: Context) : this(context.playbackDataStore)

    private val speedKey = floatPreferencesKey("playback_speed")
    private val resizeModeKey = intPreferencesKey("resize_mode")
    private val repeatModeKey = stringPreferencesKey("repeat_mode")

    /**
     * Privacy-preserving hash key for position storage.
     * Prevents leaking raw file paths or vault names into preferences storage.
     */
    private fun positionKey(uri: String): Preferences.Key<Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return longPreferencesKey("vid_pos_$hex")
    }

    override val playbackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[speedKey] ?: 1.0f
    }

    override val resizeMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[resizeModeKey] ?: 0
    }

    override val repeatMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[repeatModeKey] ?: "OFF"
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { prefs ->
            prefs[speedKey] = speed
        }
    }

    override suspend fun setResizeMode(mode: Int) {
        dataStore.edit { prefs ->
            prefs[resizeModeKey] = mode
        }
    }

    override suspend fun setRepeatMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[repeatModeKey] = mode
        }
    }

    private fun safeExtractMediaStoreId(uri: String): Long? {
        if (!uri.startsWith("content://media/") && !uri.startsWith("content://com.android.providers.media")) {
            return null
        }
        val lastSegment = uri.substringAfterLast('/')
        return lastSegment.toLongOrNull()
    }

    private fun legacyKeyFromUri(uri: String, explicitLegacyId: Long? = null): Preferences.Key<Long>? {
        val id = explicitLegacyId ?: safeExtractMediaStoreId(uri) ?: return null
        return longPreferencesKey("playback_pos_$id")
    }

    override suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long) {
        if (videoUri.isBlank()) return
        val effectivePos = PlaybackPositionThresholds.calculateEffectivePosition(positionMs, durationMs)
        dataStore.edit { prefs ->
            prefs[positionKey(videoUri)] = effectivePos
        }
    }

    override suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long, legacyVideoId: Long?): Long {
        if (videoUri.isBlank()) return 0L
        val prefs = dataStore.data.first()
        var pos = prefs[positionKey(videoUri)]

        // Backward compatibility: check legacy key format "playback_pos_<id>" if modern key is absent
        if (pos == null) {
            val legacyKey = legacyKeyFromUri(videoUri, legacyVideoId)
            if (legacyKey != null) {
                val legacyVal = prefs[legacyKey]
                if (legacyVal != null && legacyVal > 0L) {
                    pos = legacyVal
                    // Transparently and safely migrate to current vid_pos_<hash> key without losing data
                    try {
                        dataStore.edit { editPrefs ->
                            editPrefs[positionKey(videoUri)] = legacyVal
                        }
                    } catch (_: Exception) {
                        // Ignore migration write error; pos is still read correctly
                    }
                }
            }
        }

        val raw = pos ?: 0L
        if (durationMs > 0L) {
            return PlaybackPositionThresholds.calculateEffectivePosition(raw, durationMs)
        }
        return raw
    }
}

/**
 * Proportional position calculation thresholds.
 * Scales dynamically with video duration, ensuring that short videos (1s, 3s, 5s)
 * can be saved and resumed while still resetting finished videos and tiny initial scrub offsets.
 */
object PlaybackPositionThresholds {
    fun calculateEffectivePosition(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0L) return 0L
        if (durationMs <= 0L) {
            // Duration unknown: keep position if beyond tiny noise offset
            return if (positionMs < 500L) 0L else positionMs
        }

        // Start threshold (tiny intro offsets):
        val startThresholdMs = when {
            durationMs <= 2000L -> 100L
            durationMs <= 5000L -> 300L
            durationMs <= 10000L -> 1000L
            else -> 2000L
        }

        // End threshold (video finished):
        val endWindowMs = when {
            durationMs <= 2000L -> 150L
            durationMs <= 5000L -> 400L
            durationMs <= 15000L -> 1000L
            durationMs <= 60000L -> 2000L
            else -> 3000L
        }

        return when {
            positionMs >= (durationMs - endWindowMs) -> 0L // Finished -> restart
            positionMs < startThresholdMs -> 0L // At start -> start at 0
            else -> positionMs // In progress -> preserve exact position
        }
    }
}

