package com.videohatak.player.core.playback.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.videohatak.player.MainActivity
import com.videohatak.player.core.playback.VideoPlayerManager

class VideoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var attachedPlayer: Player? = null

    companion object {
        private const val CHANNEL_ID = "videohatak_playback_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = attachedPlayer ?: return
            if (isPlaying) {
                startForegroundNotification(player)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val playerManager = VideoPlayerManager.getInstance(applicationContext)
        val exoPlayer = playerManager.exoPlayer
        attachedPlayer = exoPlayer

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        exoPlayer.removeListener(playerListener)
        exoPlayer.addListener(playerListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun startForegroundNotification(player: Player) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("فيديوهاتك")
            .setContentText(player.currentMediaItem?.mediaMetadata?.title ?: "تشغيل في الخلفية")
            .setOngoing(player.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تشغيل الوسائط",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعارات تشغيل الفيديو في الخلفية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        // 1. Explicitly remove listener from ExoPlayer to prevent listener leak
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null

        // 2. Release MediaSession without destroying the shared ExoPlayer in VideoPlayerManager
        mediaSession?.run {
            release()
            mediaSession = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
