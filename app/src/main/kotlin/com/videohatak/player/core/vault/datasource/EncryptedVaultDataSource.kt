package com.videohatak.player.core.vault.datasource

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.videohatak.player.core.vault.crypto.EncryptedFileHeader
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * High-performance Media3 DataSource for Encrypted Vault videos.
 *
 * Security & Reliability Features:
 * 1. Vault Lock Enforcement: Strictly blocks opening or reading data if the vault is locked.
 * 2. Envelope Encryption Chunked Streaming: Decrypts 64KB chunks on-demand using DEK and unique nonces,
 *    verifying GCM authentication tags per chunk without holding entire video in memory.
 * 3. Backward Compatibility: Automatically detects legacy single-stream files and falls back seamlessly.
 * 4. Zero Memory Leaks: Properly frees RandomAccessFile and decrypt streams on close().
 */
class EncryptedVaultDataSource(
    private val context: Context,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val dbHelper: VaultDatabaseHelper = VaultDatabaseHelper(context)
) : BaseDataSource(false) {

    private val vaultRepo by lazy { VaultRepositoryImpl.getInstance(context) }

    private var currentDataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0L
    private var currentPosition: Long = 0L

    // Envelope Chunked Mode State
    private var isEnvelopeMode: Boolean = false
    private var randomAccessFile: RandomAccessFile? = null
    private var envelopeHeader: EncryptedFileHeader? = null
    private var dataEncryptionKey: SecretKey? = null
    private var cachedChunk: ByteArray? = null
    private var cachedChunkIndex: Int = -1

    // Legacy Fallback Stream
    private var legacyInputStream: InputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        // Enforce lock state
        if (!vaultRepo.isUnlocked.value) {
            throw SecurityException("Secure Vault is locked. Authentication required to access media.")
        }

        currentDataSpec = dataSpec
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        val pathSegment = uri.path?.removePrefix("/") ?: uri.host ?: ""
        val numericId = pathSegment.toLongOrNull()

        val vaultVideo = (if (numericId != null) dbHelper.getVideoById(numericId) else null)
            ?: dbHelper.getAllVideos().find {
                it.relativeEncryptedPath == pathSegment ||
                it.relativeEncryptedPath.endsWith(pathSegment) ||
                pathSegment.endsWith(File(it.relativeEncryptedPath).name)
            }
            ?: throw IllegalArgumentException("Vault video not found for uri: $uri")

        val encryptedFile = File(vaultVideo.relativeEncryptedPath)
        if (!encryptedFile.exists()) {
            throw IllegalArgumentException("Encrypted file not found on disk: ${vaultVideo.relativeEncryptedPath}")
        }

        isEnvelopeMode = isEnvelopeFile(encryptedFile)

        if (isEnvelopeMode) {
            val header = cryptoManager.parseEncryptedFileHeader(encryptedFile)
            envelopeHeader = header
            val headerAad = cryptoManager.buildHeaderAad(
                magic = VaultCryptoManager.MAGIC_HEADER,
                fileSalt = header.fileSalt,
                chunkSize = header.chunkSize,
                plaintextSize = header.plaintextSizeBytes
            )
            dataEncryptionKey = cryptoManager.unwrapDataKeyWithFallback(header.encryptedDek, headerAad)

            val raf = RandomAccessFile(encryptedFile, "r")
            randomAccessFile = raf
            currentPosition = dataSpec.position

            bytesRemaining = if (header.plaintextSizeBytes == 0L) {
                0L
            } else if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (header.plaintextSizeBytes - dataSpec.position).coerceAtLeast(0L)
            }
        } else {
            // Legacy single-stream fallback
            val rawStream = FileInputStream(encryptedFile)
            val iv = Base64.decode(vaultVideo.ivBase64, Base64.NO_WRAP)
            val decryptedStream = cryptoManager.decryptStream(rawStream, iv)

            if (dataSpec.position > 0) {
                var skipped = 0L
                while (skipped < dataSpec.position) {
                    val skip = decryptedStream.skip(dataSpec.position - skipped)
                    if (skip <= 0) break
                    skipped += skip
                }
            }

            legacyInputStream = decryptedStream
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (vaultVideo.encryptedSizeBytes - dataSpec.position).coerceAtLeast(0L)
            }
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Enforce lock state on ongoing read
        if (!vaultRepo.isUnlocked.value) {
            throw SecurityException("Secure Vault was locked. Playback halted.")
        }

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            length.toLong().coerceAtMost(bytesRemaining).toInt()
        }

        val bytesRead: Int = if (isEnvelopeMode) {
            readEnvelopeChunk(buffer, offset, bytesToRead)
        } else {
            legacyInputStream?.read(buffer, offset, bytesToRead) ?: -1
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }

        bytesTransferred(bytesRead)
        return bytesRead
    }

    private fun readEnvelopeChunk(outBuffer: ByteArray, outOffset: Int, requestedLength: Int): Int {
        val raf = randomAccessFile ?: return -1
        val header = envelopeHeader ?: return -1
        val dek = dataEncryptionKey ?: return -1

        if (currentPosition >= header.plaintextSizeBytes || header.plaintextSizeBytes == 0L) {
            return -1
        }

        val bytesToCopy = minOf(requestedLength.toLong(), header.plaintextSizeBytes - currentPosition).toInt()
        val chunkIndex = (currentPosition / header.chunkSize).toInt()
        val offsetInChunk = (currentPosition % header.chunkSize).toInt()

        val plainChunk: ByteArray = if (chunkIndex == cachedChunkIndex && cachedChunk != null) {
            cachedChunk!!
        } else {
            // Zero out old chunk before replacing
            cachedChunk?.let { java.util.Arrays.fill(it, 0.toByte()) }
            val decrypted = loadAndDecryptChunk(raf, header, dek, chunkIndex)
            cachedChunk = decrypted
            cachedChunkIndex = chunkIndex
            decrypted
        }

        val availableInChunk = plainChunk.size - offsetInChunk
        val actualCopy = minOf(bytesToCopy, availableInChunk)

        System.arraycopy(plainChunk, offsetInChunk, outBuffer, outOffset, actualCopy)
        currentPosition += actualCopy
        return actualCopy
    }

    private fun loadAndDecryptChunk(
        raf: RandomAccessFile,
        header: EncryptedFileHeader,
        dek: SecretKey,
        targetChunk: Int
    ): ByteArray {
        val fullChunkTotalBytes = 4L + header.chunkSize + 16L
        val estimatedOffset = header.headerSize.toLong() + targetChunk.toLong() * fullChunkTotalBytes

        val encBytes: ByteArray = if (estimatedOffset + 4 <= raf.length()) {
            raf.seek(estimatedOffset)
            val encSize = raf.readInt()
            val bytes = ByteArray(encSize)
            raf.readFully(bytes)
            bytes
        } else {
            // Sequential seek fallback for non-uniform chunk boundaries
            raf.seek(header.headerSize.toLong())
            var currentIdx = 0
            while (currentIdx < targetChunk) {
                val size = raf.readInt()
                raf.skipBytes(size)
                currentIdx++
            }
            val encSize = raf.readInt()
            val bytes = ByteArray(encSize)
            raf.readFully(bytes)
            bytes
        }

        val nonce = cryptoManager.deriveChunkNonce(header.fileSalt, targetChunk)
        val chunkAad = cryptoManager.buildChunkAad(header.fileSalt, targetChunk)
        val cipher = Cipher.getInstance(VaultCryptoManager.TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(VaultCryptoManager.GCM_TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(chunkAad)

        return try {
            cipher.doFinal(encBytes)
        } catch (e: javax.crypto.AEADBadTagException) {
            // Fallback without chunk AAD for legacy chunks
            try {
                val fallbackCipher = Cipher.getInstance(VaultCryptoManager.TRANSFORMATION)
                fallbackCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(VaultCryptoManager.GCM_TAG_LENGTH_BITS, nonce))
                fallbackCipher.doFinal(encBytes)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private fun isEnvelopeFile(file: File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(8)
                raf.readFully(magic)
                String(magic, Charsets.UTF_8) == VaultCryptoManager.MAGIC_HEADER
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun getUri(): Uri? = currentDataSpec?.uri

    override fun close() {
        try {
            legacyInputStream?.close()
        } catch (_: Exception) {}
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {}

        // Memory hygiene: Zero out cached decrypted bytes
        cachedChunk?.let { java.util.Arrays.fill(it, 0.toByte()) }

        legacyInputStream = null
        randomAccessFile = null
        envelopeHeader = null
        dataEncryptionKey = null
        cachedChunk = null
        cachedChunkIndex = -1

        if (currentDataSpec != null) {
            transferEnded()
            currentDataSpec = null
        }
    }
}

