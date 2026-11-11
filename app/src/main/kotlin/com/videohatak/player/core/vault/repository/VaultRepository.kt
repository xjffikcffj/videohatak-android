package com.videohatak.player.core.vault.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

interface VaultRepository {
    val vaultVideos: Flow<List<VaultVideo>>
    val isUnlocked: StateFlow<Boolean>
    fun setUnlocked(unlocked: Boolean)
    suspend fun refresh()
    suspend fun importVideo(
        sourceUri: Uri,
        displayName: String? = null,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): VaultVideo?
    suspend fun renameVideo(videoId: Long, newTitle: String): Boolean
    suspend fun deleteVaultVideo(id: Long): Boolean
    fun getVideoById(id: Long): VaultVideo?
}

class VaultRepositoryImpl private constructor(
    private val context: Context,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val dbHelper: VaultDatabaseHelper = VaultDatabaseHelper(context)
) : VaultRepository {

    companion object {
        @Volatile
        private var instance: VaultRepositoryImpl? = null

        fun getInstance(context: Context): VaultRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: VaultRepositoryImpl(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    override fun setUnlocked(unlocked: Boolean) {
        _isUnlocked.value = unlocked
    }

    private val _vaultVideos = MutableStateFlow<List<VaultVideo>>(emptyList())
    override val vaultVideos: Flow<List<VaultVideo>> = _vaultVideos.asStateFlow()

    private val vaultDir: File by lazy {
        File(context.filesDir, "vault_encrypted").apply {
            if (!exists()) mkdirs()
        }
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = dbHelper.getAllVideos()
        _vaultVideos.update { list }
    }

    /**
     * Imports a video into the secure vault using Envelope Encryption (AES-256-GCM + Chunks).
     * CRITICAL SAFETY GUARANTEE: Never deletes or modifies the original source file.
     */
    override suspend fun importVideo(
        sourceUri: Uri,
        displayName: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): VaultVideo? = withContext(Dispatchers.IO) {
        val uniqueName = "vault_${UUID.randomUUID()}"
        val destFile = File(vaultDir, "$uniqueName.enc")
        val resolvedName = displayName?.ifBlank { null } ?: queryFileName(sourceUri) ?: "$uniqueName.mp4"

        val inputStream: InputStream? = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (_: Exception) {
            null
        }

        if (inputStream == null) return@withContext null

        val estimatedSize = try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }

        try {
            val encResult = inputStream.use { input ->
                cryptoManager.encryptStream(
                    inputStream = input,
                    destinationFile = destFile,
                    totalBytesEstimated = estimatedSize,
                    onProgress = onProgress,
                    isCancelled = isCancelled
                )
            }

            val ivBase64 = Base64.encodeToString(encResult.encryptedDek, Base64.NO_WRAP)
            val vaultVideo = VaultVideo(
                id = 0L,
                displayName = resolvedName,
                originalSizeBytes = encResult.plaintextSizeBytes,
                encryptedSizeBytes = destFile.length(),
                durationMs = 0L,
                mimeType = "video/mp4",
                width = 0,
                height = 0,
                relativeEncryptedPath = destFile.absolutePath,
                createdAtEpochMs = System.currentTimeMillis(),
                ivBase64 = ivBase64
            )

            val newId = dbHelper.insertVideo(vaultVideo)
            val savedVideo = vaultVideo.copy(id = newId)
            refresh()
            savedVideo
        } catch (e: Exception) {
            if (destFile.exists()) {
                destFile.delete() // Clean up partial encrypted file on failure
            }
            null
        }
    }

    override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val rows = dbHelper.renameVideo(videoId, newTitle.trim())
        if (rows > 0) {
            refresh()
            true
        } else false
    }

    override suspend fun deleteVaultVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
        val video = dbHelper.getVideoById(id) ?: return@withContext false
        val file = File(video.relativeEncryptedPath)
        if (file.exists()) {
            file.delete()
        }
        val rows = dbHelper.deleteVideo(id)
        refresh()
        rows > 0
    }

    override fun getVideoById(id: Long): VaultVideo? = dbHelper.getVideoById(id)

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }
}
