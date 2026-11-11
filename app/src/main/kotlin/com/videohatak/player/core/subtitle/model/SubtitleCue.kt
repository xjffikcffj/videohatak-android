package com.videohatak.player.core.subtitle.model

data class SubtitleCue(
    val index: Int = 0,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val text: String = ""
) {
    // Secondary constructor to maintain backward compatibility with (startTimeMs, endTimeMs, text)
    constructor(startTimeMs: Long, endTimeMs: Long, text: String) : this(
        index = 0,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        text = text
    )
}
