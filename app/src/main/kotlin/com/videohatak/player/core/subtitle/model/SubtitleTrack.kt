package com.videohatak.player.core.subtitle.model

import android.net.Uri

data class SubtitleTrack(
    val id: String,
    val name: String,
    val uri: Uri,
    val language: String? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val isExternal: Boolean = true
)
