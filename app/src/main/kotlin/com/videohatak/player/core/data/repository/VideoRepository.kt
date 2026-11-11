package com.videohatak.player.core.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.videohatak.player.core.model.SortDirection
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortField
import com.videohatak.player.core.model.VideoSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

interface VideoRepository {
    fun observeVideos(): Flow<List<VideoItem>>
    suspend fun getVideos(): List<VideoItem>
    fun filterAndSort(
        videos: List<VideoItem>,
        query: String,
        sortOrder: VideoSortOrder,
        folderId: String? = null
    ): List<VideoItem>
    fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder>
}

class LocalVideoRepositoryImpl(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver
) : VideoRepository {

    override fun observeVideos(): Flow<List<VideoItem>> = callbackFlow {
        trySend(getVideos())

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val updatedVideos = queryVideosInternal()
                trySend(updatedVideos)
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        queryVideosInternal()
    }

    private fun queryVideosInternal(): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
        }

        val selection = "${MediaStore.Video.Media.DURATION} > 0 AND ${MediaStore.Video.Media.SIZE} > 0"
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLongOrNull(idCol) ?: continue
                        val displayName = cursor.getStringOrNull(nameCol) ?: ""
                        val title = cursor.getStringOrNull(titleCol) ?: ""
                        val durationMs = cursor.getLongOrNull(durationCol) ?: 0L
                        val sizeBytes = cursor.getLongOrNull(sizeCol) ?: 0L
                        val dateAddedSec = cursor.getLongOrNull(dateAddedCol) ?: 0L
                        val dateModifiedSec = cursor.getLongOrNull(dateModifiedCol) ?: 0L
                        val width = cursor.getIntOrNull(widthCol) ?: 0
                        val height = cursor.getIntOrNull(heightCol) ?: 0
                        val mimeType = cursor.getStringOrNull(mimeCol) ?: "video/*"
                        val path = cursor.getStringOrNull(dataCol) ?: ""
                        val rawBucketId = cursor.getStringOrNull(bucketIdCol)
                        val rawBucketName = cursor.getStringOrNull(bucketNameCol)
                        val relativePath = if (relativePathCol >= 0) {
                            cursor.getStringOrNull(relativePathCol) ?: ""
                        } else ""

                        val resolvedBucketId = rawBucketId ?: if (path.isNotBlank()) {
                            File(path).parentFile?.name.hashCode().toString()
                        } else "default_bucket"

                        val resolvedBucketName = when {
                            !rawBucketName.isNullOrBlank() -> rawBucketName
                            relativePath.isNotBlank() -> relativePath.trim('/').substringAfterLast('/')
                            path.isNotBlank() -> File(path).parentFile?.name ?: "مجلد الفيديوهات"
                            else -> "مجلد الفيديوهات"
                        }

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        videos.add(
                            VideoItem(
                                id = id,
                                contentUri = contentUri,
                                title = title,
                                displayName = displayName,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                dateAddedSec = dateAddedSec,
                                dateModifiedSec = dateModifiedSec,
                                width = width,
                                height = height,
                                mimeType = mimeType,
                                path = path,
                                relativePath = relativePath,
                                bucketId = resolvedBucketId,
                                bucketDisplayName = resolvedBucketName
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    override fun filterAndSort(
        videos: List<VideoItem>,
        query: String,
        sortOrder: VideoSortOrder,
        folderId: String?
    ): List<VideoItem> {
        val normalizedQuery = query.trim().lowercase()

        val filtered = videos.filter { video ->
            val matchesFolder = folderId == null || video.bucketId == folderId
            val matchesQuery = normalizedQuery.isEmpty() ||
                    video.safeTitle.lowercase().contains(normalizedQuery) ||
                    video.displayName.lowercase().contains(normalizedQuery) ||
                    video.bucketDisplayName.lowercase().contains(normalizedQuery)

            matchesFolder && matchesQuery
        }

        return when (sortOrder.field) {
            VideoSortField.DATE_ADDED -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.dateAddedSec }
                } else {
                    filtered.sortedBy { it.dateAddedSec }
                }
            }
            VideoSortField.NAME -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.safeTitle.lowercase() }
                } else {
                    filtered.sortedBy { it.safeTitle.lowercase() }
                }
            }
            VideoSortField.DURATION -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.durationMs }
                } else {
                    filtered.sortedBy { it.durationMs }
                }
            }
            VideoSortField.SIZE -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.sizeBytes }
                } else {
                    filtered.sortedBy { it.sizeBytes }
                }
            }
        }
    }

    override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
        return videos
            .groupBy { it.bucketId }
            .map { (bucketId, itemsInFolder) ->
                val firstItem = itemsInFolder.firstOrNull()
                val displayName = firstItem?.bucketDisplayName ?: "مجلد الفيديوهات"
                val totalSize = itemsInFolder.sumOf { it.sizeBytes }

                VideoFolder(
                    bucketId = bucketId,
                    displayName = displayName,
                    videoCount = itemsInFolder.size,
                    totalSizeBytes = totalSize,
                    firstVideoItem = firstItem
                )
            }
            .sortedByDescending { it.videoCount }
    }
}
