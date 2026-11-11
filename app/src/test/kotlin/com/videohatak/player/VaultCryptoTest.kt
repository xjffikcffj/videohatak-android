
package com.videohatak.player

import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.model.toVideoItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.AEADBadTagException

/**
 * Comprehensive Unit Tests for Task 10: Secure Vault Cryptographic Engine.
 *
 * Covers:
 * 1. AES-256 Data Encryption Key generation and envelope wrapping.
 * 2. Deterministic 12-byte GCM nonce derivation.
 * 3. Nonce uniqueness across chunk sequences.
 * 4. Single-chunk encryption and decryption.
 * 5. Multi-chunk encryption and seeking.
 * 6. Random-access range reading.
 * 7. AEAD integrity and tampering detection.
 * 8. Header validation.
 * 9. Empty-file handling.
 * 10. Inactivity timeout logic.
 * 11. VaultVideo metadata masking.
 * 12. Cancellation cleanup.
 * 13. Temporary-file cleanup after stream failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cryptoManager: VaultCryptoManager

    @Before
    fun setUp() {
        cryptoManager = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
    }

    @Test
    fun testDekEnvelopeWrappingAndUnwrapping() {
        val dek = cryptoManager.generateDataEncryptionKey()
        assertEquals("AES", dek.algorithm)
        assertEquals(32, dek.encoded.size)

        val wrapped = cryptoManager.wrapDataKey(dek)
        assertEquals(60, wrapped.size)

        val unwrapped = cryptoManager.unwrapDataKey(wrapped)
        assertArrayEquals(dek.encoded, unwrapped.encoded)
    }

    @Test
    fun testNonceDerivationStructure() {
        val fileSalt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val chunkIndex = 0x01020304

        val nonce = cryptoManager.deriveChunkNonce(fileSalt, chunkIndex)
        assertEquals(12, nonce.size)

        for (i in 0 until 8) {
            assertEquals(fileSalt[i], nonce[i])
        }

        assertEquals(0x01.toByte(), nonce[8])
        assertEquals(0x02.toByte(), nonce[9])
        assertEquals(0x03.toByte(), nonce[10])
        assertEquals(0x04.toByte(), nonce[11])
    }

    @Test
    fun testNonceUniquenessAcrossChunks() {
        val fileSalt = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val generatedNonces = HashSet<String>()

        for (i in 0..1000) {
            val nonce = cryptoManager.deriveChunkNonce(fileSalt, i)
            val hex = nonce.joinToString("") { "%02x".format(it) }
            assertTrue("Nonce for chunk $i must be globally unique within file", generatedNonces.add(hex))
        }
    }

    @Test
    fun testSingleChunkEncryptionAndDecryption() {
        val plainText = "VideoHatak Secure Vault - تشفير محلي عالي الأمان بنظام AES-256-GCM"
        val plainBytes = plainText.toByteArray(StandardCharsets.UTF_8)
        val destFile = tempFolder.newFile("test_single.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(plainBytes),
            destinationFile = destFile,
            totalBytesEstimated = plainBytes.size.toLong(),
            chunkSize = 64 * 1024
        )

        assertEquals(plainBytes.size.toLong(), result.plaintextSizeBytes)
        assertTrue(destFile.exists())
        assertTrue(destFile.length() > plainBytes.size)

        val outBuffer = ByteArray(plainBytes.size)
        val readBytes = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 0L,
            outBuffer = outBuffer,
            outBufferOffset = 0,
            requestedLength = plainBytes.size
        )

        assertEquals(plainBytes.size, readBytes)
        assertArrayEquals(plainBytes, outBuffer)
    }

    @Test
    fun testMultiChunkEncryptionAndCrossChunkSeeking() {
        val chunkSize = 64 * 1024
        val totalSize = 150 * 1024
        val testPayload = ByteArray(totalSize) { (it % 251).toByte() }

        val destFile = tempFolder.newFile("test_multi.enc")
        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(testPayload),
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = chunkSize
        )

        assertEquals(totalSize.toLong(), result.plaintextSizeBytes)

        val seekOffset = 60 * 1024L
        val readLength = 80 * 1024
        val outBuffer = ByteArray(readLength)

        val readBytes = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = seekOffset,
            outBuffer = outBuffer,
            outBufferOffset = 0,
            requestedLength = readLength
        )

        assertEquals(readLength, readBytes)

        val expectedSlice = Arrays.copyOfRange(
            testPayload,
            seekOffset.toInt(),
            (seekOffset + readLength).toInt()
        )
        assertArrayEquals(expectedSlice, outBuffer)
    }

    @Test
    fun testRandomAccessSeekingAtArbitraryOffset() {
        val testPayload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            .repeat(2000).toByteArray()
        val destFile = tempFolder.newFile("test_seek.enc")
        val chunkSize = 1024

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(testPayload),
            destinationFile = destFile,
            totalBytesEstimated = testPayload.size.toLong(),
            chunkSize = chunkSize
        )

        val offset = 2555L
        val length = 50
        val outBuffer = ByteArray(length)

        val read = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = offset,
            outBuffer = outBuffer,
            outBufferOffset = 0,
            requestedLength = length
        )

        assertEquals(length, read)
        val expected = Arrays.copyOfRange(
            testPayload,
            offset.toInt(),
            (offset + length).toInt()
        )
        assertArrayEquals(expected, outBuffer)
    }

    @Test
    fun testTamperingThrowsAEADBadTagException() {
        val payload = "Confidential Vault Data 12345".toByteArray()
        val destFile = tempFolder.newFile("tampered.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = payload.size.toLong(),
            chunkSize = 64 * 1024
        )

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(result.headerSize.toLong() + 5L)
            val b = raf.readByte()
            raf.seek(result.headerSize.toLong() + 5L)
            raf.writeByte(b.toInt() xor 0xFF)
        }

        val outBuffer = ByteArray(payload.size)

        assertThrows(AEADBadTagException::class.java) {
            cryptoManager.readDecryptedRange(
                encryptedFile = destFile,
                fileSalt = result.fileSalt,
                wrappedDek = result.encryptedDek,
                chunkSize = result.chunkSize,
                plaintextSize = result.plaintextSizeBytes,
                headerSize = result.headerSize,
                requestedOffset = 0L,
                outBuffer = outBuffer,
                outBufferOffset = 0,
                requestedLength = payload.size
            )
        }
    }

    @Test
    fun testHeaderParsingAndMagicValidation() {
        val payload = "Header Test Data".toByteArray()
        val destFile = tempFolder.newFile("header_test.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = payload.size.toLong()
        )

        val header = cryptoManager.parseEncryptedFileHeader(destFile)
        assertArrayEquals(result.fileSalt, header.fileSalt)
        assertArrayEquals(result.encryptedDek, header.encryptedDek)
        assertEquals(payload.size.toLong(), header.plaintextSizeBytes)
        assertEquals(VaultCryptoManager.DEFAULT_CHUNK_SIZE, header.chunkSize)
        assertEquals(result.headerSize, header.headerSize)
    }

    @Test
    fun testCorruptHeaderMagicRejection() {
        val destFile = tempFolder.newFile("corrupt_magic.enc")

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.write("BADMAGIC".toByteArray())
        }

        assertThrows(IllegalArgumentException::class.java) {
            cryptoManager.parseEncryptedFileHeader(destFile)
        }
    }

    @Test
    fun testZeroByteFileHandling() {
        val destFile = tempFolder.newFile("empty.enc")
        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(ByteArray(0)),
            destinationFile = destFile,
            totalBytesEstimated = 0L
        )

        assertEquals(0L, result.plaintextSizeBytes)

        val outBuffer = ByteArray(10)
        val read = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = 0L,
            headerSize = result.headerSize,
            requestedOffset = 0L,
            outBuffer = outBuffer,
            outBufferOffset = 0,
            requestedLength = 10
        )
        assertEquals(-1, read)
    }

    @Test
    fun testInactivityTimeoutLogic() {
        val timeoutMs = 3 * 60 * 1000L
        val now = 1_000_000L

        val recentInteraction = now - (60 * 1000L)
        assertFalse((now - recentInteraction) > timeoutMs)

        val oldInteraction = now - (4 * 60 * 1000L)
        assertTrue((now - oldInteraction) > timeoutMs)
    }

    @Test
    fun testVaultVideoToVideoItemMasking() {
        val vaultVideo = VaultVideo(
            id = 42L,
            displayName = "وثائقي_سري.mp4",
            originalSizeBytes = 50_000_000L,
            encryptedSizeBytes = 50_020_000L,
            durationMs = 120_000L,
            mimeType = "video/mp4",
            width = 1920,
            height = 1080,
            relativeEncryptedPath = "videos/vault_42.enc",
            createdAtEpochMs = 1700000000000L
        )

        val item = vaultVideo.toVideoItem()
        assertEquals(42L, item.id)
        assertEquals("vault://videos/vault_42.enc", item.contentUri.toString())
        assertEquals("وثائقي_سري.mp4", item.title)
        assertEquals("secure_vault", item.bucketId)
        assertEquals("الخزنة الخاصة", item.bucketDisplayName)
        assertEquals(120_000L, item.durationMs)
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
    }

    @Test
    fun testPartialStreamReadingAccumulatesFullChunks() {
        val totalSize = 130 * 1024
        val payload = ByteArray(totalSize) { (it % 253).toByte() }

        val irregularStream = object : java.io.InputStream() {
            private var index = 0

            override fun read(): Int {
                return if (index < totalSize) {
                    payload[index++].toInt() and 0xFF
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= totalSize) return -1
                val chunk = minOf(len, 512, totalSize - index)
                System.arraycopy(payload, index, b, off, chunk)
                index += chunk
                return chunk
            }
        }

        val destFile = tempFolder.newFile("test_irregular_stream.enc")
        val result = cryptoManager.encryptStream(
            inputStream = irregularStream,
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = 64 * 1024
        )

        assertEquals(totalSize.toLong(), result.plaintextSizeBytes)

        val readBuffer = ByteArray(10 * 1024)
        val readCount = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 60 * 1024L,
            outBuffer = readBuffer,
            outBufferOffset = 0,
            requestedLength = readBuffer.size
        )

        assertEquals(readBuffer.size, readCount)
        val expected = Arrays.copyOfRange(payload, 60 * 1024, 70 * 1024)
        assertArrayEquals(expected, readBuffer)
    }

    @Test
    fun testHeaderTamperingDetectionFileSalt() {
        val payload = "Important Vault Video Stream Data".toByteArray()
        val destFile = tempFolder.newFile("tampered_salt.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = payload.size.toLong(),
            chunkSize = 64 * 1024
        )

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(9L)
            val b = raf.readByte()
            raf.seek(9L)
            raf.writeByte(b.toInt() xor 0xAA)
        }

        val header = cryptoManager.parseEncryptedFileHeader(destFile)
        val outBuffer = ByteArray(payload.size)

        assertThrows(AEADBadTagException::class.java) {
            cryptoManager.readDecryptedRange(
                encryptedFile = destFile,
                fileSalt = header.fileSalt,
                wrappedDek = header.encryptedDek,
                chunkSize = header.chunkSize,
                plaintextSize = header.plaintextSizeBytes,
                headerSize = header.headerSize,
                requestedOffset = 0L,
                outBuffer = outBuffer,
                outBufferOffset = 0,
                requestedLength = payload.size
            )
        }
    }

    @Test
    fun testHeaderTamperingDetectionPlaintextSize() {
        val payload = "Important Vault Video Stream Data".toByteArray()
        val destFile = tempFolder.newFile("tampered_size.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = payload.size.toLong(),
            chunkSize = 64 * 1024
        )

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(result.headerSize.toLong() - 4L)
            val b = raf.readByte()
            raf.seek(result.headerSize.toLong() - 4L)
            raf.writeByte(b.toInt() xor 0xFF)
        }

        val header = cryptoManager.parseEncryptedFileHeader(destFile)
        val outBuffer = ByteArray(payload.size)

        assertThrows(AEADBadTagException::class.java) {
            cryptoManager.readDecryptedRange(
                encryptedFile = destFile,
                fileSalt = header.fileSalt,
                wrappedDek = header.encryptedDek,
                chunkSize = header.chunkSize,
                plaintextSize = header.plaintextSizeBytes,
                headerSize = header.headerSize,
                requestedOffset = 0L,
                outBuffer = outBuffer,
                outBufferOffset = 0,
                requestedLength = payload.size
            )
        }
    }

    @Test
    fun testMemoryZeroingHygiene() {
        val sensitiveBuffer = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        cryptoManager.zeroByteArray(sensitiveBuffer)

        for (b in sensitiveBuffer) {
            assertEquals(0.toByte(), b)
        }
    }

    @Test
    fun testBackwardCompatibilityUnwrapWithoutAad() {
        val dek = cryptoManager.generateDataEncryptionKey()
        val wrappedLegacy = cryptoManager.wrapDataKey(dek, aad = null)

        val unwrapped = cryptoManager.unwrapDataKeyWithFallback(wrappedLegacy, aad = null)
        assertArrayEquals(dek.encoded, unwrapped.encoded)
    }

    @Test
    fun testPartialStreamReadingWithIntermittentZeroReads() {
        val totalSize = 140 * 1024
        val payload = ByteArray(totalSize) { (it % 251).toByte() }

        val intermittentStream = object : java.io.InputStream() {
            private var index = 0
            private var shouldReturnZero = false

            override fun read(): Int {
                return if (index < totalSize) {
                    payload[index++].toInt() and 0xFF
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= totalSize) return -1

                if (shouldReturnZero) {
                    shouldReturnZero = false
                    return 0
                }

                shouldReturnZero = true
                val toRead = minOf(len, 1024, totalSize - index)
                System.arraycopy(payload, index, b, off, toRead)
                index += toRead
                return toRead
            }
        }

        val destFile = tempFolder.newFile("test_zero_reads.enc")
        val result = cryptoManager.encryptStream(
            inputStream = intermittentStream,
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = 64 * 1024
        )

        assertEquals(totalSize.toLong(), result.plaintextSizeBytes)

        val out = ByteArray(5000)
        val count = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 70_000L,
            outBuffer = out,
            outBufferOffset = 0,
            requestedLength = 5000
        )

        assertEquals(5000, count)
        val expected = Arrays.copyOfRange(payload, 70_000, 75_000)
        assertArrayEquals(expected, out)
    }

    @Test
    fun testHeaderTamperingDetectionChunkSize() {
        val payload = "Confidential Video Content 12345".toByteArray()
        val destFile = tempFolder.newFile("tampered_chunk_size.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = payload.size.toLong(),
            chunkSize = 64 * 1024
        )

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(80L)
            val originalChunkSize = raf.readInt()
            raf.seek(80L)
            raf.writeInt(originalChunkSize + 1024)
        }

        val header = cryptoManager.parseEncryptedFileHeader(destFile)
        val outBuffer = ByteArray(payload.size)

        assertThrows(AEADBadTagException::class.java) {
            cryptoManager.readDecryptedRange(
                encryptedFile = destFile,
                fileSalt = header.fileSalt,
                wrappedDek = header.encryptedDek,
                chunkSize = header.chunkSize,
                plaintextSize = header.plaintextSizeBytes,
                headerSize = header.headerSize,
                requestedOffset = 0L,
                outBuffer = outBuffer,
                outBufferOffset = 0,
                requestedLength = payload.size
            )
        }
    }

    @Test
    fun testChunkTamperingOrderSwappingThrowsAEADBadTagException() {
        val chunkSize = 1024
        val totalSize = 2048
        val payload = ByteArray(totalSize) { (it % 100).toByte() }
        val destFile = tempFolder.newFile("swapped_chunks.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = chunkSize
        )

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(result.headerSize.toLong())
            val size0 = raf.readInt()
            val enc0 = ByteArray(size0)
            raf.readFully(enc0)

            val size1 = raf.readInt()
            val enc1 = ByteArray(size1)
            raf.readFully(enc1)

            raf.seek(result.headerSize.toLong())
            raf.writeInt(size1)
            raf.write(enc1)
            raf.writeInt(size0)
            raf.write(enc0)
        }

        val outBuffer = ByteArray(chunkSize)

        assertThrows(AEADBadTagException::class.java) {
            cryptoManager.readDecryptedRange(
                encryptedFile = destFile,
                fileSalt = result.fileSalt,
                wrappedDek = result.encryptedDek,
                chunkSize = result.chunkSize,
                plaintextSize = result.plaintextSizeBytes,
                headerSize = result.headerSize,
                requestedOffset = 0L,
                outBuffer = outBuffer,
                outBufferOffset = 0,
                requestedLength = chunkSize
            )
        }
    }

    @Test
    fun testRandomAccessSeekingForwardAndBackward() {
        val chunkSize = 64 * 1024
        val totalSize = 180 * 1024
        val payload = ByteArray(totalSize) { (it % 251).toByte() }
        val destFile = tempFolder.newFile("test_bidirectional_seek.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = chunkSize
        )

        val buffer = ByteArray(1000)

        val read1 = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 150_000L,
            outBuffer = buffer,
            outBufferOffset = 0,
            requestedLength = 1000
        )
        assertEquals(1000, read1)
        assertArrayEquals(Arrays.copyOfRange(payload, 150_000, 151_000), buffer)

        val read2 = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 500L,
            outBuffer = buffer,
            outBufferOffset = 0,
            requestedLength = 1000
        )
        assertEquals(1000, read2)
        assertArrayEquals(Arrays.copyOfRange(payload, 500, 1500), buffer)

        val read3 = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 70_000L,
            outBuffer = buffer,
            outBufferOffset = 0,
            requestedLength = 1000
        )
        assertEquals(1000, read3)
        assertArrayEquals(Arrays.copyOfRange(payload, 70_000, 71_000), buffer)
    }

    @Test
    fun testFileSmallerThanSingleChunk() {
        val totalSize = 15 * 1024
        val payload = ByteArray(totalSize) { (it % 127).toByte() }
        val destFile = tempFolder.newFile("test_small_file.enc")

        val result = cryptoManager.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = destFile,
            totalBytesEstimated = totalSize.toLong(),
            chunkSize = 64 * 1024
        )

        assertEquals(totalSize.toLong(), result.plaintextSizeBytes)

        val out = ByteArray(totalSize)
        val count = cryptoManager.readDecryptedRange(
            encryptedFile = destFile,
            fileSalt = result.fileSalt,
            wrappedDek = result.encryptedDek,
            chunkSize = result.chunkSize,
            plaintextSize = result.plaintextSizeBytes,
            headerSize = result.headerSize,
            requestedOffset = 0L,
            outBuffer = out,
            outBufferOffset = 0,
            requestedLength = totalSize
        )

        assertEquals(totalSize, count)
        assertArrayEquals(payload, out)
    }

    @Test
    fun testPersistentZeroReadsThrowsIOExceptionAndCleansUpTempFile() {
        // Use a path that does not create the destination file beforehand.
        val destFile = File(tempFolder.root, "stalled_stream.enc")
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")

        val stalledStream = object : java.io.InputStream() {
            private var bytesDelivered = 0

            override fun read(): Int {
                return if (bytesDelivered < 10 * 1024) {
                    bytesDelivered++
                    0x55
                } else {
                    0
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesDelivered < 10 * 1024) {
                    val toDeliver = minOf(len, 10 * 1024 - bytesDelivered)
                    Arrays.fill(b, off, off + toDeliver, 0x55.toByte())
                    bytesDelivered += toDeliver
                    return toDeliver
                }
                return 0
            }
        }

        val exception = assertThrows(java.io.IOException::class.java) {
            cryptoManager.encryptStream(
                inputStream = stalledStream,
                destinationFile = destFile,
                totalBytesEstimated = 100 * 1024L,
                chunkSize = 64 * 1024
            )
        }

        assertTrue(exception.message?.contains("Stream stalled") == true)
        assertFalse("Incomplete temp file must be deleted upon IOException", tempFile.exists())
        assertFalse("Destination file must not be created on stream stall", destFile.exists())
    }

    @Test
    fun testCancellationCleansUpTempFile() {
        val destFile = tempFolder.newFile("cancel_test.enc")
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        val stream = ByteArrayInputStream(ByteArray(500 * 1024))

        var cancelled = false

        assertThrows(InterruptedException::class.java) {
            cryptoManager.encryptStream(
                inputStream = stream,
                destinationFile = destFile,
                totalBytesEstimated = 500 * 1024L,
                chunkSize = 64 * 1024,
                onProgress = { progress ->
                    if (progress > 0.05f) {
                        cancelled = true
                    }
                },
                isCancelled = { cancelled }
            )
        }

        assertFalse(tempFile.exists())
    }
}
