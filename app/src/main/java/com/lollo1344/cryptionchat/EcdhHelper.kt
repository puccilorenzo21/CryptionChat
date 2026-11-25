package com.lollo1344.cryptionchat

import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EcdhHelper {

    private const val TAG = "EcdhHelper"
    private const val EC_CURVE = "secp256r1"
    private const val ECDSA_ALGORITHM = "SHA256withECDSA"
    private const val FINGERPRINT_ITERATIONS = 5200

    // Lista fissa di 64 emoji chiaramente distinguibili e universalmente supportate
    private val SAFETY_EMOJIS = arrayOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
        "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
        "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺",
        "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞",
        "🐜", "🦟", "🐢", "🐍", "🐙", "🦑", "🦐", "🦀",
        "🐡", "🐠", "🐟", "🐬", "🐳", "🦈", "🐊", "🐅",
        "🐆", "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪",
        "🐫", "🦒", "🦘", "🐃", "🐂", "🐄", "🐎", "🐖"
    )

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
            // Fallback silenzioso nei test unitari JVM senza Android framework
        }
    }

    /**
     * Genera una coppia di chiavi asimmetriche EC su curva secp256r1.
     * @return Pair(privateKeyBase64, publicKeyBase64) in formato standard Base64 senza a capo
     */
    fun generateKeyPair(): Pair<String, String>? {
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance("EC")
            val ecSpec = ECGenParameterSpec(EC_CURVE)
            keyPairGenerator.initialize(ecSpec)
            val keyPair = keyPairGenerator.generateKeyPair()

            val encoder = Base64.getEncoder()
            val privateKeyBase64 = encoder.encodeToString(keyPair.private.encoded)
            val publicKeyBase64 = encoder.encodeToString(keyPair.public.encoded)

            Pair(privateKeyBase64, publicKeyBase64)
        } catch (e: Exception) {
            logError("Errore nella generazione della coppia di chiavi ECDH", e)
            null
        }
    }

    /**
     * Calcola il segreto condiviso grezzo ECDH su secp256r1.
     */
    fun computeRawSharedSecret(myPrivateKeyBase64: String, otherPublicKeyBase64: String): ByteArray? {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")

            val privateBytes = SafeBase64.decodeStandard(myPrivateKeyBase64)
            val privSpec = PKCS8EncodedKeySpec(privateBytes)
            val privateKey = keyFactory.generatePrivate(privSpec)

            val publicBytes = SafeBase64.decodeStandard(otherPublicKeyBase64)
            val pubSpec = X509EncodedKeySpec(publicBytes)
            val publicKey = keyFactory.generatePublic(pubSpec)

            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)

            keyAgreement.generateSecret()
        } catch (e: Exception) {
            logError("Errore nel calcolo del segreto grezzo ECDH", e)
            null
        }
    }

    /**
     * Implementazione standard RFC 5869 HKDF-SHA256 (Extract + Expand).
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray = ByteArray(32),
        info: ByteArray = ByteArray(0),
        outputLength: Int = 64
    ): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = prkMac.doFinal(ikm)

        val result = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < outputLength) {
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()

            val toCopy = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }

        prk.fill(0)
        return result
    }

    /**
     * Calcola la chiave AES condivisa tramite ECDH + HKDF-SHA256.
     * Primi 32 byte (64 hex) -> Chiave AES-256
     * Successivi 16 byte (32 hex) -> IV di riserva legacy
     * @return Pair(aesKeyHex, ivHex)
     */
    fun computeSharedAesKey(myPrivateKeyBase64: String, otherPublicKeyBase64: String): Pair<String, String>? {
        return try {
            val sharedSecret = computeRawSharedSecret(myPrivateKeyBase64, otherPublicKeyBase64) ?: return null
            val info = "CryptionChat-ZK-Key-v2".toByteArray(Charsets.UTF_8)
            val derived = hkdfSha256(
                ikm = sharedSecret,
                salt = "CryptionChat-ZK-Salt-v2".toByteArray(Charsets.UTF_8),
                info = info,
                outputLength = 48
            )
            sharedSecret.fill(0)

            val keyBytes = derived.copyOfRange(0, 32)
            val ivBytes = derived.copyOfRange(32, 48)
            derived.fill(0)

            val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
            val ivHex = ivBytes.joinToString("") { "%02x".format(it) }

            Pair(keyHex, ivHex)
        } catch (e: Exception) {
            logError("Errore nel calcolo della chiave condivisa ECDH", e)
            null
        }
    }

    /**
     * Calcola 4 emoji di sicurezza basate sull'hash SHA-256 della chiave hex,
     * consentendo agli utenti di verificare visualmente l'integrità anti-MitM.
     */
    fun getSafetyEmojis(keyHex: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(keyHex.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 4) {
                val byteVal = hash[i].toInt() and 0xFF
                val index = byteVal % SAFETY_EMOJIS.size
                sb.append(SAFETY_EMOJIS[index])
            }
            sb.toString()
        } catch (e: Exception) {
            logError("Errore nella generazione delle emoji di sicurezza", e)
            "🔒🛡️🔐🔑"
        }
    }

    /**
     * Firma [dataBytes] con la chiave privata EC secp256r1 in Base64 usando SHA256withECDSA.
     */
    fun signData(privB64: String, dataBytes: ByteArray): String? {
        if (privB64.isBlank()) return null
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val privBytes = SafeBase64.decodeStandard(privB64)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
            val sig = Signature.getInstance(ECDSA_ALGORITHM)
            sig.initSign(privateKey)
            sig.update(dataBytes)
            Base64.getEncoder().encodeToString(sig.sign())
        } catch (e: Exception) {
            logError("Errore nella firma ECDSA", e)
            null
        }
    }

    /**
     * Verifica la firma ECDSA SHA256withECDSA [sigB64] su [dataBytes] usando la chiave pubblica Base64.
     */
    fun verifySignature(pubB64: String, dataBytes: ByteArray, sigB64: String): Boolean {
        if (pubB64.isBlank() || sigB64.isBlank()) return false
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val pubBytes = SafeBase64.decodeStandard(pubB64)
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
            val sigBytes = SafeBase64.decodeStandard(sigB64)
            val sig = Signature.getInstance(ECDSA_ALGORITHM)
            sig.initVerify(publicKey)
            sig.update(dataBytes)
            sig.verify(sigBytes)
        } catch (e: Exception) {
            logError("Errore nella verifica della firma ECDSA", e)
            false
        }
    }

    /**
     * Calcola il fingerprint SHA-256 iterato (5200 iterazioni con domain tag "CC-ID-v1")
     * delle Identity Keys persistenti di un utente.
     */
    fun computeIdentityFingerprintBytes(
        userId: Int,
        ikSignPubB64: String,
        ikDhPubB64: String
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val identityMaterial = "CC-ID-v1|$userId|$ikSignPubB64|$ikDhPubB64".toByteArray(Charsets.UTF_8)
        var hash = md.digest(identityMaterial)
        for (i in 1 until FINGERPRINT_ITERATIONS) {
            md.reset()
            md.update(hash)
            md.update(identityMaterial)
            hash = md.digest()
        }
        return hash
    }

    /**
     * Restituisce il fingerprint esadecimale (64 caratteri hex) delle Identity Keys di [userId].
     */
    fun computeIdentityFingerprint(
        userId: Int,
        ikSignPubB64: String,
        ikDhPubB64: String
    ): String {
        return computeIdentityFingerprintBytes(userId, ikSignPubB64, ikDhPubB64)
            .joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun canonicalOrderedFingerprints(
        uidA: Int,
        ikSignA: String,
        ikDhA: String,
        uidB: Int,
        ikSignB: String,
        ikDhB: String
    ): Pair<String, String> {
        val fpA = computeIdentityFingerprint(uidA, ikSignA, ikDhA)
        val fpB = computeIdentityFingerprint(uidB, ikSignB, ikDhB)
        val aFirst = if (uidA != uidB) uidA < uidB else fpA <= fpB
        return if (aFirst) Pair(fpA, fpB) else Pair(fpB, fpA)
    }

    /**
     * Calcola il Safety Number simmetrico a 60 cifre (12 blocchi da 5 cifre, ~199 bit)
     * basato sulle Identity Keys persistenti di entrambi gli utenti.
     */
    fun getSafetyNumber60Digits(
        uidA: Int,
        ikSignA: String,
        ikDhA: String,
        uidB: Int,
        ikSignB: String,
        ikDhB: String
    ): String {
        val (firstFp, secondFp) = canonicalOrderedFingerprints(uidA, ikSignA, ikDhA, uidB, ikSignB, ikDhB)
        val md = MessageDigest.getInstance("SHA-512")
        val digest = md.digest("CC-SN-v1|$firstFp|$secondFp".toByteArray(Charsets.UTF_8))
        val blocks = ArrayList<String>(12)
        for (i in 0 until 12) {
            val offset = i * 5
            val value = ((digest[offset].toLong() and 0xFFL) shl 32) or
                ((digest[offset + 1].toLong() and 0xFFL) shl 24) or
                ((digest[offset + 2].toLong() and 0xFFL) shl 16) or
                ((digest[offset + 3].toLong() and 0xFFL) shl 8) or
                (digest[offset + 4].toLong() and 0xFFL)
            blocks.add("%05d".format(Locale.US, value % 100_000L))
        }
        return blocks.joinToString(" ")
    }

    /**
     * Calcola 11 Safety Emojis simmetriche (6 bit esatti per emoji senza bias = 66 bit)
     * basate sulle Identity Keys persistenti di entrambi gli utenti.
     */
    fun getSafetyEmojis66Bit(
        uidA: Int,
        ikSignA: String,
        ikDhA: String,
        uidB: Int,
        ikSignB: String,
        ikDhB: String
    ): String {
        val (firstFp, secondFp) = canonicalOrderedFingerprints(uidA, ikSignA, ikDhA, uidB, ikSignB, ikDhB)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("CC-SE-v1|$firstFp|$secondFp".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        var bitBuffer = 0
        var bitCount = 0
        var byteIndex = 0
        for (i in 0 until 11) {
            while (bitCount < 6) {
                bitBuffer = (bitBuffer shl 8) or (digest[byteIndex++].toInt() and 0xFF)
                bitCount += 8
            }
            bitCount -= 6
            val index = (bitBuffer ushr bitCount) and 0x3F
            sb.append(SAFETY_EMOJIS[index])
        }
        return sb.toString()
    }
}
