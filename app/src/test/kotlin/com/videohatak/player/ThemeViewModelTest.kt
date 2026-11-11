package com.videohatak.player

import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemeState
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeThemePreferencesRepository : ThemePreferencesRepository {
        val flow = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeModeFlow: Flow<ThemeMode> = flow

        override suspend fun setThemeMode(mode: ThemeMode) {
            flow.value = mode
        }
    }

    private lateinit var fakeRepository: FakeThemePreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeThemePreferencesRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun defaultState_loadsFromRepository() = runTest(testDispatcher) {
        val viewModel = ThemeViewModel(fakeRepository)

        // Advance dispatcher to collect the initial flow emission
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue("Expected ThemeState.Success but was $state", state is ThemeState.Success)
        assertEquals(ThemeMode.SYSTEM, (state as ThemeState.Success).themeMode)
    }

    @Test
    fun setThemeMode_updatesRepositoryAndState() = runTest(testDispatcher) {
        val viewModel = ThemeViewModel(fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.DARK)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue(state is ThemeState.Success)
        assertEquals(ThemeMode.DARK, (state as ThemeState.Success).themeMode)
        assertEquals(ThemeMode.DARK, fakeRepository.flow.value)
    }

    @Test
    fun setThemeMode_switchToLight_updatesCorrectly() = runTest(testDispatcher) {
        val viewModel = ThemeViewModel(fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue(state is ThemeState.Success)
        assertEquals(ThemeMode.LIGHT, (state as ThemeState.Success).themeMode)
        assertEquals(ThemeMode.LIGHT, fakeRepository.flow.value)
    }
}
