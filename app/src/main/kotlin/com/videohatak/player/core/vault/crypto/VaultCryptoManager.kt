package com.videohatak.player.core.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedFileHeader(
    val fileSalt: ByteArray,
    val encryptedDek: ByteArray,
    val plaintextSizeBytes: Long,
    val chunkSize: Int,
    val headerSize: Int
)

data class EncryptionResult(
    val fileSalt: ByteArray,
    val encryptedDek: ByteArray,
    val plaintextSizeBytes: Long,
    val chunkSize: Int,
    val headerSize: Int
)

class VaultCryptoManager(
    private val keyStoreProvider: String = ANDROID_KEYSTORE
) {

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "VideoHatakVaultMasterKey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MAGIC_HEADER = "VHTKVLT1"
    }

    private val secureRandom = SecureRandom()
    private var testMasterKey: SecretKey? = null

    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStoreProvider != ANDROID_KEYSTORE) {
            if (testMasterKey == null) {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                testMasterKey = keyGen.generateKey()
            }
            return testMasterKey!!
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun generateDataEncryptionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun buildHeaderAad(magic: String, fileSalt: ByteArray, chunkSize: Int, plaintextSize: Long): ByteArray {
        val magicBytes = magic.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(magicBytes.size + fileSalt.size + 4 + 8)
            .put(magicBytes)
            .put(fileSalt)
            .putInt(chunkSize)
            .putLong(plaintextSize)
            .array()
    }

    fun buildChunkAad(fileSalt: ByteArray, chunkIndex: Int): ByteArray {
        return ByteBuffer.allocate(fileSalt.size + 4)
            .put(fileSalt)
            .putInt(chunkIndex)
            .array()
    }

    fun wrapDataKey(dek: SecretKey, aad: ByteArray? = null): ByteArray {
        val masterKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(dek.encoded)
        return iv + encrypted
    }

    fun unwrapDataKey(wrapped: ByteArray, aad: ByteArray? = null): SecretKey {
        val masterKey = getOrCreateSecretKey()
        val iv = wrapped.copyOfRange(0, 12)
        val ciphertext = wrapped.copyOfRange(12, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        if (aad != null) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
                cipher.updateAAD(aad)
                val raw = cipher.doFinal(ciphertext)
                val key = SecretKeySpec(raw, "AES")
                zeroByteArray(raw)
                return key
            } catch (e: AEADBadTagException) {
                // If AAD was provided but failed, we rethrow if we want strict enforcement
                // but we also check if unwrap without AAD succeeds for legacy files
                throw e
            }
        }

        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        val raw = cipher.doFinal(ciphertext)
        val key = SecretKeySpec(raw, "AES")
        zeroByteArray(raw)
        return key
    }

    fun unwrapDataKeyWithFallback(wrapped: ByteArray, aad: ByteArray?): SecretKey {
        if (aad != null) {
            try {
                return unwrapDataKey(wrapped, aad)
            } catch (_: AEADBadTagException) {
                // Backward compatibility fallback for files created without AAD binding
                return unwrapDataKey(wrapped, null)
            }
        }
        return unwrapDataKey(wrapped, null)
    }

    fun deriveChunkNonce(fileSalt: ByteArray, chunkIndex: Int): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(fileSalt, 0, nonce, 0, 8)
        nonce[8] = (chunkIndex ushr 24).toByte()
        nonce[9] = (chunkIndex ushr 16).toByte()
        nonce[10] = (chunkIndex ushr 8).toByte()
        nonce[11] = chunkIndex.toByte()
        return nonce
    }

    fun zeroByteArray(array: ByteArray?) {
        if (array != null) {
            Arrays.fill(array, 0.toByte())
        }
    }

    /**
     * Reads a full chunk of exactly [targetSize] bytes from [inputStream] into [buffer],
     * handling partial stream reads and intermittent 0-byte non-blocking reads safely.
     * Prevents silent corruption and infinite loops:
     * - Only returns a partial chunk if the stream genuinely reaches EOF (bytesRead == -1).
     * - If the stream returns 0 bytes repeatedly without making forward progress,
     *   it throws an IOException to abort encryption cleanly and prevent writing malformed chunks.
     * Returns total bytes read, or -1 if genuine EOF occurred before reading any byte.
     */
    fun readChunkFully(inputStream: InputStream, buffer: ByteArray, targetSize: Int = buffer.size): Int {
        var totalBytesRead = 0
        var consecutiveZeroReads = 0
        val maxConsecutiveZeroReads = 50

        while (totalBytesRead < targetSize) {
            val bytesRead = inputStream.read(buffer, totalBytesRead, targetSize - totalBytesRead)
            if (bytesRead == -1) {
                // Genuine EOF reached
                break
            } else if (bytesRead == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= maxConsecutiveZeroReads) {
                    throw java.io.IOException(
                        "Stream stalled: stream made no progress after $maxConsecutiveZeroReads consecutive 0-byte reads (read $totalBytesRead of $targetSize bytes)"
                    )
                }
                Thread.yield()
            } else {
                // Forward progress made, reset zero-read counter
                consecutiveZeroReads = 0
                totalBytesRead += bytesRead
            }
        }
        return if (totalBytesRead == 0) -1 else totalBytesRead
    }

    /**
     * Legacy single-stream encryption
     */
    fun encryptStream(outputStream: OutputStream): Pair<CipherOutputStream, ByteArray> {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherOutputStream = CipherOutputStream(outputStream, cipher)
        return Pair(cipherOutputStream, iv)
    }

    fun decryptStream(inputStream: InputStream, iv: ByteArray): CipherInputStream {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return CipherInputStream(inputStream, cipher)
    }

    fun encryptStream(
        inputStream: InputStream,
        destinationFile: File,
        totalBytesEstimated: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): EncryptionResult {
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        val fileSalt = ByteArray(8).apply { secureRandom.nextBytes(this) }
        val dek = generateDataEncryptionKey()

        // Initial placeholder wrapped DEK (60 bytes: 12 IV + 32 key + 16 tag)
        val initialWrappedDek = wrapDataKey(dek)
        val buffer = ByteArray(chunkSize)
        var plaintextSize = 0L
        var raf: RandomAccessFile? = null

        try {
            val fileRaf = RandomAccessFile(tempFile, "rw")
            raf = fileRaf

            // Write magic and header
            fileRaf.write(MAGIC_HEADER.toByteArray(Charsets.UTF_8))
            fileRaf.write(fileSalt)
            fileRaf.writeInt(initialWrappedDek.size)
            val wrappedDekOffset = fileRaf.filePointer
            fileRaf.write(initialWrappedDek)
            fileRaf.writeInt(chunkSize)
            val plainSizeOffset = fileRaf.filePointer
            fileRaf.writeLong(0L) // Placeholder for total plain size
            val headerSize = fileRaf.filePointer.toInt()

            var chunkIndex = 0
            var bytesRead: Int

            while (readChunkFully(inputStream, buffer, chunkSize).also { bytesRead = it } != -1) {
                if (isCancelled()) {
                    fileRaf.close()
                    raf = null
                    tempFile.delete()
                    zeroByteArray(buffer)
                    throw InterruptedException("Encryption cancelled by user")
                }

                val nonce = deriveChunkNonce(fileSalt, chunkIndex)
                val chunkAad = buildChunkAad(fileSalt, chunkIndex)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                cipher.updateAAD(chunkAad)

                val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)
                fileRaf.writeInt(encryptedChunk.size)
                fileRaf.write(encryptedChunk)

                plaintextSize += bytesRead
                chunkIndex++

                if (totalBytesEstimated > 0) {
                    onProgress((plaintextSize.toFloat() / totalBytesEstimated).coerceIn(0f, 1f))
                }
            }

            // Now that exact plaintextSize is known, bind header fields with AAD
            val headerAad = buildHeaderAad(MAGIC_HEADER, fileSalt, chunkSize, plaintextSize)
            val finalWrappedDek = wrapDataKey(dek, headerAad)

            // Seek back and rewrite authenticated wrapped DEK & actual plaintext size
            fileRaf.seek(wrappedDekOffset)
            fileRaf.write(finalWrappedDek)
            fileRaf.seek(plainSizeOffset)
            fileRaf.writeLong(plaintextSize)
            fileRaf.close()
            raf = null

            if (destinationFile.exists()) destinationFile.delete()
            tempFile.renameTo(destinationFile)

            return EncryptionResult(
                fileSalt = fileSalt,
                encryptedDek = finalWrappedDek,
                plaintextSizeBytes = plaintextSize,
                chunkSize = chunkSize,
                headerSize = headerSize
            )
        } catch (e: Exception) {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            if (tempFile.exists()) tempFile.delete()
            throw e
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            zeroByteArray(buffer)
        }
    }

    fun parseEncryptedFileHeader(file: File): EncryptedFileHeader {
        val raf = RandomAccessFile(file, "r")
        raf.use {
            val magicBytes = ByteArray(8)
            it.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.UTF_8)
            if (magic != MAGIC_HEADER) {
                throw IllegalArgumentException("Invalid vault magic header: $magic")
            }

            val fileSalt = ByteArray(8)
            it.readFully(fileSalt)

            val wrappedDekSize = it.readInt()
            val wrappedDek = ByteArray(wrappedDekSize)
            it.readFully(wrappedDek)

            val chunkSize = it.readInt()
            val plaintextSize = it.readLong()
            val headerSize = it.filePointer.toInt()

            return EncryptedFileHeader(
                fileSalt = fileSalt,
                encryptedDek = wrappedDek,
                plaintextSizeBytes = plaintextSize,
                chunkSize = chunkSize,
                headerSize = headerSize
            )
        }
    }

    fun readDecryptedRange(
        encryptedFile: File,
        fileSalt: ByteArray,
        wrappedDek: ByteArray,
        chunkSize: Int,
        plaintextSize: Long,
        headerSize: Int,
        requestedOffset: Long,
        outBuffer: ByteArray,
        outBufferOffset: Int,
        requestedLength: Int
    ): Int {
        if (requestedOffset >= plaintextSize || plaintextSize == 0L) {
            return -1
        }

        val headerAad = buildHeaderAad(MAGIC_HEADER, fileSalt, chunkSize, plaintextSize)
        val dek = unwrapDataKey(wrappedDek, headerAad)
        val bytesToRead = minOf(requestedLength.toLong(), plaintextSize - requestedOffset).toInt()

        val startChunk = (requestedOffset / chunkSize).toInt()
        val endChunk = ((requestedOffset + bytesToRead - 1) / chunkSize).toInt()

        var totalCopied = 0
        var currentOffsetInRead = requestedOffset

        val raf = RandomAccessFile(encryptedFile, "r")
        raf.use {
            // Seek to chunk positions
            val fullChunkEncryptedTotal = 4L + chunkSize + 16L
            val startChunkOffset = headerSize.toLong() + startChunk.toLong() * fullChunkEncryptedTotal

            if (startChunkOffset + 4 <= raf.length()) {
                it.seek(startChunkOffset)
            } else {
                it.seek(headerSize.toLong())
                var currentChunk = 0
                while (currentChunk < startChunk) {
                    val encSize = it.readInt()
                    it.skipBytes(encSize)
                    currentChunk++
                }
            }

            var currentChunk = startChunk
            while (currentChunk <= endChunk && totalCopied < bytesToRead) {
                val encSize = it.readInt()
                val encBytes = ByteArray(encSize)
                it.readFully(encBytes)

                val nonce = deriveChunkNonce(fileSalt, currentChunk)
                val chunkAad = buildChunkAad(fileSalt, currentChunk)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                cipher.updateAAD(chunkAad)

                val plainChunk = try {
                    cipher.doFinal(encBytes)
                } catch (e: AEADBadTagException) {
                    // Try fallback without chunk AAD for legacy chunks
                    try {
                        val fallbackCipher = Cipher.getInstance(TRANSFORMATION)
                        fallbackCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                        fallbackCipher.doFinal(encBytes)
                    } catch (_: Exception) {
                        throw e
                    }
                }

                try {
                    val chunkStartOffset = currentChunk.toLong() * chunkSize
                    val offsetInChunk = (currentOffsetInRead - chunkStartOffset).toInt()
                    val availableInChunk = plainChunk.size - offsetInChunk
                    val needToCopy = minOf(availableInChunk, bytesToRead - totalCopied)

                    System.arraycopy(plainChunk, offsetInChunk, outBuffer, outBufferOffset + totalCopied, needToCopy)
                    totalCopied += needToCopy
                    currentOffsetInRead += needToCopy
                    currentChunk++
                } finally {
                    zeroByteArray(plainChunk)
                }
            }
        }

        return totalCopied
    }
}

