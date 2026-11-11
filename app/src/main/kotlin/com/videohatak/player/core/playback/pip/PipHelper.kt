package com.videohatak.player.core.playback.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational

class PipHelper(private val context: Context) {

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.videohatak.player.PIP_PLAY_PAUSE"
        const val ACTION_PIP_FORWARD = "com.videohatak.player.PIP_FORWARD"
        const val ACTION_PIP_REPLAY = "com.videohatak.player.PIP_REPLAY"

        fun isPipSupported(context: Context): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }

        fun getValidAspectRatio(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) {
                return Rational(16, 9)
            }
            val rawRatio = width.toFloat() / height.toFloat()
            return when {
                rawRatio > 2.39f -> Rational(239, 100)
                rawRatio < (1f / 2.39f) -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }

        /**
         * AUD-08: Check isFinishing / isDestroyed before entering PiP to avoid IllegalStateException
         */
        fun enterPip(
            activity: Activity,
            videoWidth: Int = 1920,
            videoHeight: Int = 1080,
            isPlaying: Boolean = true
        ): Boolean {
            if (!isPipSupported(activity) || activity.isFinishing ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
            ) {
                return false
            }

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ratio = getValidAspectRatio(videoWidth, videoHeight)
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(ratio)
                        .setActions(buildActions(activity, isPlaying))
                        .build()
                    activity.enterPictureInPictureMode(params)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        fun buildActions(context: Context, isPlaying: Boolean): List<RemoteAction> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

            val actions = mutableListOf<RemoteAction>()

            // Replay 10s
            val replayIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_REPLAY
            }
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, android.R.drawable.ic_media_rew),
                    "ترجيع 10ث",
                    "ترجيع 10 ثوان",
                    PendingIntent.getBroadcast(
                        context,
                        101,
                        replayIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            // Play / Pause
            val playPauseIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_PLAY_PAUSE
            }
            val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, playPauseIcon),
                    if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                    if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                    PendingIntent.getBroadcast(
                        context,
                        102,
                        playPauseIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            // Forward 10s
            val forwardIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_FORWARD
            }
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, android.R.drawable.ic_media_ff),
                    "تقديم 10ث",
                    "تقديم 10 ثوان",
                    PendingIntent.getBroadcast(
                        context,
                        103,
                        forwardIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            return actions
        }
    }

    fun isPipSupported(): Boolean = isPipSupported(context)

    fun enterPip(activity: Activity, isPlaying: Boolean, videoAspectRatio: Rational? = null): Boolean {
        val w = videoAspectRatio?.numerator ?: 16
        val h = videoAspectRatio?.denominator ?: 9
        return enterPip(activity, w, h, isPlaying)
    }

    fun updatePipActions(activity: Activity, isPlaying: Boolean) {
        if (!isPipSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.isFinishing) return
        try {
            val params = PictureInPictureParams.Builder()
                .setActions(buildActions(context, isPlaying))
                .build()
            activity.setPictureInPictureParams(params)
        } catch (_: Exception) {}
    }
}
