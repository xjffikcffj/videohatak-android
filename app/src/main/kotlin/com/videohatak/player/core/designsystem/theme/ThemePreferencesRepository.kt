package com.videohatak.player.core.designsystem.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "videohatak_settings")

sealed interface ThemeState {
    data object Loading : ThemeState
    data class Success(val themeMode: ThemeMode) : ThemeState
}

interface ThemePreferencesRepository {
    val themeModeFlow: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}

class ThemePreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : ThemePreferencesRepository {

    constructor(context: Context) : this(context.themeDataStore)

    private val themeKey = stringPreferencesKey("app_theme_mode")

    override val themeModeFlow: Flow<ThemeMode> = dataStore.data.map { preferences ->
        when (preferences[themeKey]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[themeKey] = mode.name
        }
    }
}

// Backward-compatible alias for direct instantiation
typealias ThemePreferencesRepositoryAlias = ThemePreferencesRepositoryImpl
