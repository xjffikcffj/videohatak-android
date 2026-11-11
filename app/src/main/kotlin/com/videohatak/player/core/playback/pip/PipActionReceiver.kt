package com.videohatak.player.core.playback.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.videohatak.player.core.playback.VideoPlayerManager

class PipActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PLAY = "com.videohatak.player.PIP_PLAY"
        const val ACTION_PAUSE = "com.videohatak.player.PIP_PAUSE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = VideoPlayerManager.getInstance(context)
        when (intent.action) {
            PipHelper.ACTION_PIP_PLAY_PAUSE, ACTION_PLAY, ACTION_PAUSE -> {
                manager.togglePlayPause()
            }
            PipHelper.ACTION_PIP_FORWARD -> {
                val current = manager.exoPlayer.currentPosition
                manager.seekTo(current + 10_000L)
            }
            PipHelper.ACTION_PIP_REPLAY -> {
                val current = manager.exoPlayer.currentPosition
                manager.seekTo((current - 10_000L).coerceAtLeast(0L))
            }
        }
    }
}
