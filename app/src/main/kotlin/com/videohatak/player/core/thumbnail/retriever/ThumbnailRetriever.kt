package com.videohatak.player.core.thumbnail.retriever

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailRetriever(private val context: Context) {

    suspend fun extractFrame(videoUri: Uri, positionMs: Long, targetWidth: Int = 160, targetHeight: Int = 90): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (videoUri.scheme == "content" || videoUri.scheme == "file") {
                    retriever.setDataSource(context, videoUri)
                } else {
                    retriever.setDataSource(videoUri.toString(), HashMap())
                }

                val timeUs = positionMs * 1000L
                val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                frame
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) { }
            }
        }
}
