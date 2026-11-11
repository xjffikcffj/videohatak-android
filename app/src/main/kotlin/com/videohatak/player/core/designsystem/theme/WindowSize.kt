package com.videohatak.player.core.designsystem.theme

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal providing access to the current [WindowSizeClass].
 * Automatically updates upon device rotation, folding/unfolding, and split-screen changes.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }

object WindowSizeHelper {
    val current: WindowSizeClass?
        @Composable
        @ReadOnlyComposable
        get() = LocalWindowSizeClass.current

    val isCompact: Boolean
        @Composable
        @ReadOnlyComposable
        get() = current?.widthSizeClass == WindowWidthSizeClass.Compact

    val isMedium: Boolean
        @Composable
        @ReadOnlyComposable
        get() = current?.widthSizeClass == WindowWidthSizeClass.Medium

    val isExpanded: Boolean
        @Composable
        @ReadOnlyComposable
        get() = current?.widthSizeClass == WindowWidthSizeClass.Expanded
}
