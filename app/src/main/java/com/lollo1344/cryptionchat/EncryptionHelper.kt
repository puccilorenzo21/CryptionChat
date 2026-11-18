package com.lollo1344.cryptionchat

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {

    private const val TAG = "EncryptionHelper"
    private const val GCM_PREFIX = "GCM1:"
    private val STREAM_MAGIC_CTR1 = "CTR1".toByteArray(Charsets.US_ASCII)
    private val STREAM_MAGIC_CTR2 = "CTR2".toByteArray(Charsets.US_ASCII)
    private val PAD_MAGIC = "PAD1".toByteArray(Charsets.US_ASCII)
    private const val PAD_BLOCK_SIZE = 128
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val STREAM_IV_LENGTH = 16
    private const val CTR2_CHUNK_SIZE = 65536
    private const val CTR2_HMAC_SIZE = 32
    private const val CTR2_MAX_CHUNK_SIZE = 1024 * 1024

    private val secureRandom = SecureRandom()

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Applica padding crittografico a blocchi da [blockSize] byte (default 128B)
     * per mitigare l'analisi della lunghezza dei messaggi sul server.
     * Formato: [PAD1 (4B)] + [payloadLen (4B big-endian)] + [payload] + [random padding].
     */
    fun applyPadding(data: ByteArray, blockSize: Int = PAD_BLOCK_SIZE): ByteArray {
        val headerAndDataLen = PAD_MAGIC.size + 4 + data.size
        val paddedLen = ((headerAndDataLen + blockSize - 1) / blockSize) * blockSize
        val result = ByteArray(paddedLen)
        val padLen = paddedLen - headerAndDataLen
        if (padLen > 0) {
            val randomPad = ByteArray(padLen)
            secureRandom.nextBytes(randomPad)
            System.arraycopy(randomPad, 0, result, headerAndDataLen, padLen)
        }
        System.arraycopy(PAD_MAGIC, 0, result, 0, PAD_MAGIC.size)
        val lenBytes = ByteBuffer.allocate(4).putInt(data.size).array()
        System.arraycopy(lenBytes, 0, result, PAD_MAGIC.size, 4)
        System.arraycopy(data, 0, result, PAD_MAGIC.size + 4, data.size)
        return result
    }

    /**
     * Rimuove il padding PAD1 se presente; mantiene retrocompatibilità con messaggi non paddati.
     */
    fun removePadding(data: ByteArray): ByteArray {
        if (data.size < PAD_MAGIC.size + 4) return data
        for (i in PAD_MAGIC.indices) {
            if (data[i] != PAD_MAGIC[i]) return data
        }
        val payloadLen = ByteBuffer.wrap(data, PAD_MAGIC.size, 4).int
        val offset = PAD_MAGIC.size + 4
        if (payloadLen < 0 || offset + payloadLen > data.size) {
            return data
        }
        return data.copyOfRange(offset, offset + payloadLen)
    }

    /**
     * Cifrario legacy AES/CBC/PKCS5Padding per garantire la retrocompatibilità.
     */
    fun getCipher(mode: Int, keyHex: String, ivHex: String): Cipher? {
        return try {
            val key = hexStringToByteArray(keyHex)
            val iv = hexStringToByteArray(ivHex)
            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(mode, secretKeySpec, ivParameterSpec)
            cipher
        } catch (e: Exception) {
            logError("Errore nella creazione del cifrario legacy CBC", e)
            null
        }
    }

    /**
     * Cifra una stringa usando Double Ratchet (DR1:) se una sessione ZK è attiva per [keyHex],
     * altrimenti usa AES-256-GCM con padding crittografico e IV casuale (GCM1:).
     */
    fun encrypt(value: String, keyHex: String, ivHex: String): String? {
        val isControlPacket = value.startsWith("[ZK_ECDH]:") || value.startsWith("[ZK_EXCHANGE]:")
        if (!isControlPacket && DoubleRatchetHelper.hasActiveSession(keyHex)) {
            DoubleRatchetHelper.encryptMessage(keyHex, value)?.let { return it }
        }
        return encryptDirect(value, keyHex, ivHex)
    }

    /**
     * Cifra direttamente con AES-256-GCM (senza avanzare il Double Ratchet).
     * Utile per pacchetti di controllo e segnalazione WebRTC fuori banda.
     */
    fun encryptDirect(value: String, keyHex: String, ivHex: String): String? {
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val key = hexStringToByteArray(keyHex)
            val secretKeySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmSpec)

            val padded = applyPadding(value.toByteArray(Charsets.UTF_8))
            val cipherText = cipher.doFinal(padded)
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            GCM_PREFIX + Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            logError("Errore durante la cifratura GCM del testo", e)
            null
        }
    }

    /**
     * Decifra una stringa:
     * - "DR1:": decifratura Double Ratchet (con LocalVault cifrato per riletture UI)
     * - "SK1:": decifratura Sender Key di gruppo (con LocalVault cifrato per riletture UI)
     * - "GCM1:": AES/GCM/NoPadding + rimozione padding PAD1
     * - Altro: fallback legacy AES/CBC/PKCS5Padding
     */
    fun decrypt(value: String, keyHex: String, ivHex: String): String? {
        if (value.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
            return DoubleRatchetHelper.decryptMessage(keyHex, value)
        }
        if (value.startsWith(SenderKeyHelper.SK_PREFIX)) {
            return SenderKeyHelper.decryptGroupMessage(value)
        }

        if (value.startsWith(GCM_PREFIX)) {
            return try {
                val base64Payload = value.substring(GCM_PREFIX.length)
                val combined = Base64.getDecoder().decode(base64Payload)
                if (combined.size < GCM_IV_LENGTH) return null

                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

                val key = hexStringToByteArray(keyHex)
                val secretKeySpec = SecretKeySpec(key, "AES")
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmSpec)

                val decryptedBytes = removePadding(cipher.doFinal(cipherText))
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        // Fallback di retrocompatibilità su AES/CBC/PKCS5Padding
        return try {
            val decodedValue = Base64.getDecoder().decode(value)
            val cipher = getCipher(Cipher.DECRYPT_MODE, keyHex, ivHex) ?: return null
            val decryptedValue = cipher.doFinal(decodedValue)
            String(decryptedValue, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveCtr2Keys(masterKey: ByteArray, fileIv: ByteArray): Pair<ByteArray, ByteArray> {
        val material = EcdhHelper.hkdfSha256(
            ikm = masterKey,
            salt = fileIv,
            info = "CryptionChat-CTR2-v1".toByteArray(Charsets.UTF_8),
            outputLength = 64
        )
        val encKey = material.copyOfRange(0, 32)
        val macKey = material.copyOfRange(32, 64)
        material.fill(0)
        return Pair(encKey, macKey)
    }

    private fun computeChunkHmac(
        macKey: ByteArray,
        chunkIndex: Long,
        isFinal: Byte,
        chunkLen: Int,
        ciphertext: ByteArray,
        ciphertextLen: Int
    ): ByteArray {
        val meta = ByteBuffer.allocate(13)
            .putLong(chunkIndex)
            .put(isFinal)
            .putInt(chunkLen)
            .array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(meta)
        if (ciphertextLen > 0) {
            mac.update(ciphertext, 0, ciphertextLen)
        }
        return mac.doFinal()
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val r = input.read(buffer, offset, length - offset)
            if (r == -1) throw EOFException("Stream terminato prematuramente (attesi $length byte, letti $offset).")
            offset += r
        }
    }

    /**
     * Cifra un flusso di dati per file con schema autenticato CTR2 (Chunked Encrypt-then-MAC):
     * Header: [CTR2 (4B)] + [Random IV (16B)]
     * Per ogni chunk (max 64KB): [chunkLen (4B)] + [isFinal (1B)] + [ciphertext] + [HMAC-SHA256 (32B)]
     */
    fun encryptStream(inputStream: InputStream, outputStream: OutputStream, keyHex: String, ivHex: String) {
        val randomIv = ByteArray(STREAM_IV_LENGTH)
        secureRandom.nextBytes(randomIv)

        val masterKey = hexStringToByteArray(keyHex)
        val (encKey, macKey) = deriveCtr2Keys(masterKey, randomIv)
        masterKey.fill(0)

        try {
            outputStream.write(STREAM_MAGIC_CTR2)
            outputStream.write(randomIv)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(randomIv))

            val chunkBuffer = ByteArray(CTR2_CHUNK_SIZE)
            var currentRead = readUpTo(inputStream, chunkBuffer)
            var chunkIndex = 0L

            if (currentRead == 0) {
                val emptyCipher = cipher.doFinal()
                val hmac = computeChunkHmac(macKey, chunkIndex, 1, emptyCipher.size, emptyCipher, emptyCipher.size)
                val header = ByteBuffer.allocate(5).putInt(emptyCipher.size).put(1.toByte()).array()
                outputStream.write(header)
                if (emptyCipher.isNotEmpty()) outputStream.write(emptyCipher)
                outputStream.write(hmac)
            } else {
                val nextBuffer = ByteArray(CTR2_CHUNK_SIZE)
                while (currentRead > 0) {
                    val nextRead = readUpTo(inputStream, nextBuffer)
                    val isFinal: Byte = if (nextRead == 0) 1 else 0

                    val cipherBytes = if (isFinal == 1.toByte()) {
                        cipher.doFinal(chunkBuffer, 0, currentRead)
                    } else {
                        cipher.update(chunkBuffer, 0, currentRead) ?: ByteArray(0)
                    }

                    val hmac = computeChunkHmac(macKey, chunkIndex, isFinal, cipherBytes.size, cipherBytes, cipherBytes.size)
                    val chunkHeader = ByteBuffer.allocate(5).putInt(cipherBytes.size).put(isFinal).array()
                    outputStream.write(chunkHeader)
                    if (cipherBytes.isNotEmpty()) outputStream.write(cipherBytes)
                    outputStream.write(hmac)

                    chunkIndex++
                    if (nextRead > 0) {
                        System.arraycopy(nextBuffer, 0, chunkBuffer, 0, nextRead)
                    }
                    currentRead = nextRead
                }
            }
            outputStream.flush()
        } finally {
            encKey.fill(0)
            macKey.fill(0)
            try {
                outputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Decifra un flusso di dati per file:
     * - "CTR2": Chunked Encrypt-then-MAC (verifica HMAC-SHA256 e anti-troncamento prima di scrivere)
     * - "CTR1": Legacy AES/CTR senza MAC
     * - Altro: Legacy AES/CBC/PKCS5Padding
     */
    fun decryptStream(inputStream: InputStream, outputStream: OutputStream, keyHex: String, ivHex: String) {
        val headerLen = STREAM_MAGIC_CTR2.size + STREAM_IV_LENGTH
        val pushbackStream = PushbackInputStream(inputStream, headerLen)

        val headerBuffer = ByteArray(headerLen)
        var totalRead = 0
        while (totalRead < headerLen) {
            val r = pushbackStream.read(headerBuffer, totalRead, headerLen - totalRead)
            if (r == -1) break
            totalRead += r
        }

        fun matchesMagic(magic: ByteArray): Boolean {
            if (totalRead != headerLen) return false
            for (i in magic.indices) {
                if (headerBuffer[i] != magic[i]) return false
            }
            return true
        }

        if (matchesMagic(STREAM_MAGIC_CTR2)) {
            val fileIv = headerBuffer.copyOfRange(STREAM_MAGIC_CTR2.size, headerLen)
            val masterKey = hexStringToByteArray(keyHex)
            val (encKey, macKey) = deriveCtr2Keys(masterKey, fileIv)
            masterKey.fill(0)

            try {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(fileIv))

                val chunkHeader = ByteArray(5)
                var chunkIndex = 0L
                var sawFinalChunk = false

                while (!sawFinalChunk) {
                    val firstByte = pushbackStream.read()
                    if (firstByte == -1) {
                        throw IOException("File CTR2 troncato: chunk finale mancante.")
                    }
                    chunkHeader[0] = firstByte.toByte()
                    val remainingHeader = ByteArray(4)
                    readFully(pushbackStream, remainingHeader, 4)
                    System.arraycopy(remainingHeader, 0, chunkHeader, 1, 4)

                    val bb = ByteBuffer.wrap(chunkHeader)
                    val chunkLen = bb.int
                    val isFinal = bb.get()

                    if (chunkLen < 0 || chunkLen > CTR2_MAX_CHUNK_SIZE) {
                        throw IOException("Dimensione chunk CTR2 non valida: $chunkLen")
                    }
                    if (isFinal != 0.toByte() && isFinal != 1.toByte()) {
                        throw IOException("Flag finale chunk CTR2 non valido.")
                    }

                    val cipherBytes = ByteArray(chunkLen)
                    if (chunkLen > 0) {
                        readFully(pushbackStream, cipherBytes, chunkLen)
                    }
                    val receivedHmac = ByteArray(CTR2_HMAC_SIZE)
                    readFully(pushbackStream, receivedHmac, CTR2_HMAC_SIZE)

                    val expectedHmac = computeChunkHmac(macKey, chunkIndex, isFinal, chunkLen, cipherBytes, chunkLen)
                    if (!MessageDigest.isEqual(expectedHmac, receivedHmac)) {
                        throw SecurityException("Integrità del file compromessa (HMAC non valido al chunk $chunkIndex).")
                    }

                    val plainBytes = if (isFinal == 1.toByte()) {
                        cipher.doFinal(cipherBytes)
                    } else {
                        cipher.update(cipherBytes) ?: ByteArray(0)
                    }
                    if (plainBytes.isNotEmpty()) {
                        outputStream.write(plainBytes)
                    }

                    chunkIndex++
                    if (isFinal == 1.toByte()) {
                        sawFinalChunk = true
                    }
                }

                if (pushbackStream.read() != -1) {
                    throw SecurityException("Dati extra non autenticati rilevati dopo la fine dello stream CTR2.")
                }

                outputStream.flush()
            } finally {
                encKey.fill(0)
                macKey.fill(0)
                try {
                    outputStream.close()
                } catch (_: Exception) {
                }
            }
        } else if (matchesMagic(STREAM_MAGIC_CTR1)) {
            val fileIv = headerBuffer.copyOfRange(STREAM_MAGIC_CTR1.size, headerLen)
            val key = hexStringToByteArray(keyHex)
            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(fileIv)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

            val cipherInputStream = CipherInputStream(pushbackStream, cipher)
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (cipherInputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            } catch (e: Exception) {
                throw IOException("Errore I/O durante la decifratura CTR1 dello stream.", e)
            } finally {
                cipherInputStream.close()
            }
        } else {
            // Rollback dei byte letti per il fallback su CBC
            if (totalRead > 0) {
                pushbackStream.unread(headerBuffer, 0, totalRead)
            }

            val cipher = getCipher(Cipher.DECRYPT_MODE, keyHex, ivHex)
                ?: throw SecurityException("Impossibile creare il cifrario per la decifratura legacy.")
            val cipherOutputStream = CipherOutputStream(outputStream, cipher)
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (pushbackStream.read(buffer).also { read = it } != -1) {
                    cipherOutputStream.write(buffer, 0, read)
                }
                cipherOutputStream.flush()
            } catch (e: Exception) {
                throw IOException("Errore di I/O durante la lettura del file cifrato legacy.", e)
            } finally {
                try {
                    cipherOutputStream.close()
                } catch (e: Exception) {
                    throw IOException("Il file legacy è corrotto o le chiavi sono errate.", e)
                }
            }
        }
    }

    fun encrypt(data: ByteArray, keyHex: String, ivHex: String): ByteArray? {
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val key = hexStringToByteArray(keyHex)
            val secretKeySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmSpec)

            val cipherText = cipher.doFinal(data)
            val prefix = GCM_PREFIX.toByteArray(Charsets.US_ASCII)
            val combined = ByteArray(prefix.size + iv.size + cipherText.size)
            System.arraycopy(prefix, 0, combined, 0, prefix.size)
            System.arraycopy(iv, 0, combined, prefix.size, iv.size)
            System.arraycopy(cipherText, 0, combined, prefix.size + iv.size, cipherText.size)
            combined
        } catch (e: Exception) {
            logError("Errore durante la cifratura ByteArray GCM", e)
            null
        }
    }

    fun decrypt(data: ByteArray, keyHex: String, ivHex: String): ByteArray? {
        val prefix = GCM_PREFIX.toByteArray(Charsets.US_ASCII)
        if (data.size >= prefix.size + GCM_IV_LENGTH) {
            var isGcm = true
            for (i in prefix.indices) {
                if (data[i] != prefix[i]) {
                    isGcm = false
                    break
                }
            }
            if (isGcm) {
                return try {
                    val iv = data.copyOfRange(prefix.size, prefix.size + GCM_IV_LENGTH)
                    val cipherText = data.copyOfRange(prefix.size + GCM_IV_LENGTH, data.size)

                    val key = hexStringToByteArray(keyHex)
                    val secretKeySpec = SecretKeySpec(key, "AES")
                    val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmSpec)
                    cipher.doFinal(cipherText)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // Fallback su CBC
        return try {
            val cipher = getCipher(Cipher.DECRYPT_MODE, keyHex, ivHex) ?: return null
            cipher.doFinal(data)
        } catch (_: Exception) {
            null
        }
    }
}
