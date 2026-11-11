package com.videohatak.player.core.subtitle.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.subtitleDataStore by preferencesDataStore(name = "videohatak_subtitles")

class SubtitlePreferencesRepository(private val context: Context) {
    private val primarySizeKey = floatPreferencesKey("sub_primary_size")
    private val secondarySizeKey = floatPreferencesKey("sub_secondary_size")

    val primaryStyle: Flow<SubtitleStyleConfig> = context.subtitleDataStore.data.map { prefs ->
        SubtitleStyleConfig(
            fontSizeSp = prefs[primarySizeKey] ?: 18f,
            bottomPaddingDp = 48f
        )
    }

    val secondaryStyle: Flow<SubtitleStyleConfig> = context.subtitleDataStore.data.map { prefs ->
        SubtitleStyleConfig(
            fontSizeSp = prefs[secondarySizeKey] ?: 15f,
            bottomPaddingDp = 88f
        )
    }

    suspend fun updatePrimaryFontSize(sizeSp: Float) {
        context.subtitleDataStore.edit { prefs ->
            prefs[primarySizeKey] = sizeSp
        }
    }

    suspend fun updateSecondaryFontSize(sizeSp: Float) {
        context.subtitleDataStore.edit { prefs ->
            prefs[secondarySizeKey] = sizeSp
        }
    }
}
