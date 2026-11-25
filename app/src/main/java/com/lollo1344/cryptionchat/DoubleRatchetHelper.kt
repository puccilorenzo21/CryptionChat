package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implementazione del protocollo Double Ratchet per le chat Zero-Knowledge.
 *
 * Garantisce:
 * - Forward Secrecy per singolo messaggio (KDF catena simmetrica HMAC-SHA256)
 * - Post-Compromise Security (step DH Ratchet ad ogni turno su curva secp256r1 + HKDF-SHA256)
 * - Autenticazione dell'header tramite AAD in AES-256-GCM
 * - Gestione sicura dei messaggi fuori ordine (max 64 chiavi saltate per sessione)
 * - LocalVault cifrato (SQLCipher + cache RAM) per preservare la leggibilità della cronologia
 *   locale dopo la distruzione immediata delle message key effimere.
 */
object DoubleRatchetHelper {

    private const val TAG = "DoubleRatchetHelper"
    const val DR_PREFIX = "DR1:"
    private const val MAX_SKIP = 64
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val lock = Any()

    interface StorageBackend {
        fun loadSessionJson(keyHash: String): String?
        fun saveSessionJson(keyHash: String, json: String)
        fun deleteSessionJson(keyHash: String)
        fun clearAllSessions()

        fun getVaultPlaintext(ciphertextHash: String): String?
        fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String)
        fun deleteVaultBySession(sessionId: String)
        fun clearAllVault()
    }

    class InMemoryStorageBackend : StorageBackend {
        private val sessions = ConcurrentHashMap<String, String>()
        private val vault = ConcurrentHashMap<String, Pair<String, String>>() // hash -> (sessionId, plaintext)

        override fun loadSessionJson(keyHash: String): String? = sessions[keyHash]
        override fun saveSessionJson(keyHash: String, json: String) { sessions[keyHash] = json }
        override fun deleteSessionJson(keyHash: String) { sessions.remove(keyHash) }
        override fun clearAllSessions() { sessions.clear() }

        override fun getVaultPlaintext(ciphertextHash: String): String? = vault[ciphertextHash]?.second
        override fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String) {
            vault[ciphertextHash] = Pair(sessionId, plaintext)
        }
        override fun deleteVaultBySession(sessionId: String) {
            vault.entries.removeIf { it.value.first == sessionId }
        }
        override fun clearAllVault() { vault.clear() }
    }

    private class AndroidStorageBackend(context: Context) : StorageBackend {
        private val appContext = context.applicationContext
        private val memorySessionCache = ConcurrentHashMap<String, String>()
        private val memoryVaultCache = ConcurrentHashMap<String, String>()

        private fun prefKey(keyHash: String) = "dr_session_$keyHash"

        override fun loadSessionJson(keyHash: String): String? {
            memorySessionCache[keyHash]?.let { return it }
            return try {
                val json = SecurePrefs.get(appContext).getString(prefKey(keyHash), null)
                if (json != null) memorySessionCache[keyHash] = json
                json
            } catch (e: Exception) {
                logError("Errore lettura sessione ratchet", e)
                null
            }
        }

        override fun saveSessionJson(keyHash: String, json: String) {
            memorySessionCache[keyHash] = json
            try {
                SecurePrefs.get(appContext).edit().putString(prefKey(keyHash), json).commit()
            } catch (e: Exception) {
                logError("Errore salvataggio sessione ratchet", e)
            }
        }

        override fun deleteSessionJson(keyHash: String) {
            memorySessionCache.remove(keyHash)
            try {
                SecurePrefs.get(appContext).edit().remove(prefKey(keyHash)).commit()
            } catch (e: Exception) {
                logError("Errore eliminazione sessione ratchet", e)
            }
        }

        override fun clearAllSessions() {
            memorySessionCache.clear()
            try {
                val prefs = SecurePrefs.get(appContext)
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("dr_session_") }.forEach { editor.remove(it) }
                editor.commit()
            } catch (e: Exception) {
                logError("Errore pulizia sessioni ratchet", e)
            }
        }

        override fun getVaultPlaintext(ciphertextHash: String): String? {
            memoryVaultCache[ciphertextHash]?.let { return it }
            return try {
                val plain = AppDatabase.getInstance(appContext).vaultDao().getPlaintextSync(ciphertextHash)
                if (plain != null) memoryVaultCache[ciphertextHash] = plain
                plain
            } catch (e: Exception) {
                logError("Errore lettura LocalVault", e)
                null
            }
        }

        override fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String) {
            memoryVaultCache[ciphertextHash] = plaintext
            try {
                AppDatabase.getInstance(appContext).vaultDao().insertEntrySync(
                    VaultEntryEntity(
                        ciphertextHash = ciphertextHash,
                        sessionId = sessionId,
                        plaintext = plaintext,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logError("Errore scrittura LocalVault", e)
            }
        }

        override fun deleteVaultBySession(sessionId: String) {
            memoryVaultCache.clear()
            try {
                AppDatabase.getInstance(appContext).vaultDao().deleteBySessionSync(sessionId)
            } catch (e: Exception) {
                logError("Errore eliminazione LocalVault per sessione", e)
            }
        }

        override fun clearAllVault() {
            memoryVaultCache.clear()
            try {
                AppDatabase.getInstance(appContext).vaultDao().clearAllSync()
            } catch (e: Exception) {
                logError("Errore pulizia completa LocalVault", e)
            }
        }
    }

    data class RatchetHeader(
        val sid: String,
        val dh: String,
        val n: Int,
        val pn: Int,
        val x3dh: String? = null
    )

    data class RatchetSessionState(
        val sessionId: String,
        var rootKeyB64: String,
        var sendChainKeyB64: String?,
        var recvChainKeyB64: String?,
        var dhSendPrivB64: String,
        var dhSendPubB64: String,
        var dhRecvPubB64: String,
        var ns: Int,
        var nr: Int,
        var pn: Int,
        val skippedKeys: MutableMap<String, String> = mutableMapOf(),
        val adB64: String? = null,
        var pendingX3dhInitJson: String? = null,
        val initTimestamp: Long = System.currentTimeMillis()
    ) {
        fun deepCopy(): RatchetSessionState {
            return RatchetSessionState(
                sessionId = sessionId,
                rootKeyB64 = rootKeyB64,
                sendChainKeyB64 = sendChainKeyB64,
                recvChainKeyB64 = recvChainKeyB64,
                dhSendPrivB64 = dhSendPrivB64,
                dhSendPubB64 = dhSendPubB64,
                dhRecvPubB64 = dhRecvPubB64,
                ns = ns,
                nr = nr,
                pn = pn,
                skippedKeys = skippedKeys.toMutableMap(),
                adB64 = adB64,
                pendingX3dhInitJson = pendingX3dhInitJson,
                initTimestamp = initTimestamp
            )
        }
    }

    @Volatile
    private var storage: StorageBackend = InMemoryStorageBackend()

    fun init(context: Context) {
        synchronized(lock) {
            storage = AndroidStorageBackend(context.applicationContext)
        }
    }

    internal fun setStorageForTests(backend: StorageBackend) {
        synchronized(lock) {
            storage = backend
        }
    }

    internal fun currentStorage(): StorageBackend {
        synchronized(lock) {
            return storage
        }
    }

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
        }
    }

    fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decodeB64(str: String): ByteArray = SafeBase64.decodeStandard(str)
    private fun encodeUrlB64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decodeUrlB64(str: String): ByteArray = SafeBase64.decodeUrlSafe(str)

    private fun kdfRootKey(rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = EcdhHelper.hkdfSha256(
            ikm = dhOut,
            salt = rootKey,
            info = "CryptionChat-DR-RK-v1".toByteArray(Charsets.UTF_8),
            outputLength = 64
        )
        val newRootKey = derived.copyOfRange(0, 32)
        val newChainKey = derived.copyOfRange(32, 64)
        derived.fill(0)
        return Pair(newRootKey, newChainKey)
    }

    private fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val messageKey = mac.doFinal(byteArrayOf(0x01))

        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val nextChainKey = mac.doFinal(byteArrayOf(0x02))
        return Pair(nextChainKey, messageKey)
    }

    private fun computeSessionId(pubA: String, pubB: String): String {
        val ordered = if (pubA <= pubB) "$pubA|$pubB" else "$pubB|$pubA"
        return sha256Hex(ordered).substring(0, 16)
    }

    private fun computeX3dhSessionId(rootKey: ByteArray, adBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(rootKey, "HmacSHA256"))
        mac.update("CC-DR-SID-v2|".toByteArray(Charsets.UTF_8))
        mac.update(adBytes)
        return mac.doFinal().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun buildAad(headerBytes: ByteArray, adB64: String?): ByteArray {
        if (adB64.isNullOrBlank()) return headerBytes
        val adBytes = decodeB64(adB64)
        val aad = ByteArray(headerBytes.size + adBytes.size)
        System.arraycopy(headerBytes, 0, aad, 0, headerBytes.size)
        System.arraycopy(adBytes, 0, aad, headerBytes.size, adBytes.size)
        return aad
    }

    fun pairwiseSessionKey(peerUserId: Int): String = "pw_$peerUserId"

    fun hasPairwiseSession(peerUserId: Int): Boolean = hasActiveSession(pairwiseSessionKey(peerUserId))

    fun extractX3dhInitHeader(drPayload: String): String? {
        if (!drPayload.startsWith(DR_PREFIX)) return null
        val body = drPayload.substring(DR_PREFIX.length)
        val sepIdx = body.indexOf(':')
        if (sepIdx <= 0) return null
        return try {
            val headerBytes = decodeUrlB64(body.substring(0, sepIdx))
            val header = gson.fromJson(String(headerBytes, Charsets.UTF_8), RatchetHeader::class.java)
            header?.x3dh
        } catch (_: Exception) {
            null
        }
    }

    fun hasActiveSession(keyHex: String): Boolean {
        if (keyHex.isBlank()) return false
        synchronized(lock) {
            return storage.loadSessionJson(sha256Hex(keyHex)) != null
        }
    }

    fun getSessionInitTimestamp(keyHex: String): Long {
        if (keyHex.isBlank()) return 0L
        synchronized(lock) {
            val json = storage.loadSessionJson(sha256Hex(keyHex)) ?: return 0L
            return try {
                gson.fromJson(json, RatchetSessionState::class.java)?.initTimestamp ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun getVaultPlaintextForPayload(drPayload: String): String? {
        if (!drPayload.startsWith(DR_PREFIX)) return null
        synchronized(lock) {
            return storage.getVaultPlaintext(sha256Hex(drPayload))
        }
    }

    fun exportSessionJson(keyHex: String): String? {
        if (keyHex.isBlank()) return null
        synchronized(lock) {
            return storage.loadSessionJson(sha256Hex(keyHex))
        }
    }

    fun importSessionJson(keyHex: String, sessionJson: String?) {
        if (keyHex.isBlank()) return
        synchronized(lock) {
            val keyHash = sha256Hex(keyHex)
            if (sessionJson.isNullOrBlank()) {
                storage.deleteSessionJson(keyHash)
            } else {
                storage.saveSessionJson(keyHash, sessionJson)
            }
        }
    }

    /**
     * Inizializza una sessione Double Ratchet a partire dal segreto condiviso X3DH [rootKey]
     * e dai dati associati [adBytes] (vincolati nell'AAD di AES-GCM).
     * Supporta alias arbitrari incluso il canale pairwise `"pw_$peerUserId"`.
     */
    fun initSessionFromX3dh(
        sessionAliasKey: String,
        rootKey: ByteArray,
        adBytes: ByteArray,
        myDhPriv: String,
        myDhPub: String,
        theirRatchetPub: String,
        isInitiator: Boolean,
        x3dhInitHeaderJson: String? = null,
        initTimestamp: Long = System.currentTimeMillis()
    ): Boolean {
        if (sessionAliasKey.isBlank() || rootKey.isEmpty() || adBytes.isEmpty()) return false
        if (myDhPriv.isBlank() || myDhPub.isBlank()) return false
        if (isInitiator && theirRatchetPub.isBlank()) return false

        synchronized(lock) {
            val keyHash = sha256Hex(sessionAliasKey)
            val sessionId = computeX3dhSessionId(rootKey, adBytes)

            storage.loadSessionJson(keyHash)?.let { existingJson ->
                val existing = try { gson.fromJson(existingJson, RatchetSessionState::class.java) } catch (_: Exception) { null }
                if (existing != null && existing.sessionId == sessionId) {
                    return true
                }
            }

            val adB64 = encodeB64(adBytes)
            val state = if (isInitiator) {
                val dhOut = EcdhHelper.computeRawSharedSecret(myDhPriv, theirRatchetPub)
                    ?: return false
                val (rk1, cks) = kdfRootKey(rootKey, dhOut)
                dhOut.fill(0)

                val s = RatchetSessionState(
                    sessionId = sessionId,
                    rootKeyB64 = encodeB64(rk1),
                    sendChainKeyB64 = encodeB64(cks),
                    recvChainKeyB64 = null,
                    dhSendPrivB64 = myDhPriv,
                    dhSendPubB64 = myDhPub,
                    dhRecvPubB64 = theirRatchetPub,
                    ns = 0,
                    nr = 0,
                    pn = 0,
                    adB64 = adB64,
                    pendingX3dhInitJson = x3dhInitHeaderJson,
                    initTimestamp = initTimestamp
                )
                rk1.fill(0)
                cks.fill(0)
                s
            } else {
                RatchetSessionState(
                    sessionId = sessionId,
                    rootKeyB64 = encodeB64(rootKey),
                    sendChainKeyB64 = null,
                    recvChainKeyB64 = null,
                    dhSendPrivB64 = myDhPriv,
                    dhSendPubB64 = myDhPub,
                    dhRecvPubB64 = "",
                    ns = 0,
                    nr = 0,
                    pn = 0,
                    adB64 = adB64,
                    pendingX3dhInitJson = null,
                    initTimestamp = initTimestamp
                )
            }

            storage.saveSessionJson(keyHash, gson.toJson(state))
            return true
        }
    }

    /**
     * Inizializza una sessione Double Ratchet per la chiave di gruppo ZK [keyHex].
     * È idempotente per lo stesso handshake ([sessionId]).
     */
    fun initSession(
        keyHex: String,
        myPrivateKeyBase64: String,
        myPublicKeyBase64: String,
        otherPublicKeyBase64: String,
        isInitiator: Boolean
    ): Boolean {
        if (keyHex.isBlank()) return false
        synchronized(lock) {
            val keyHash = sha256Hex(keyHex)
            val sessionId = computeSessionId(myPublicKeyBase64, otherPublicKeyBase64)

            storage.loadSessionJson(keyHash)?.let { existingJson ->
                val existing = try { gson.fromJson(existingJson, RatchetSessionState::class.java) } catch (_: Exception) { null }
                if (existing != null && existing.sessionId == sessionId) {
                    return true
                }
            }

            val sharedSecret = EcdhHelper.computeRawSharedSecret(myPrivateKeyBase64, otherPublicKeyBase64)
                ?: return false

            val orderedPair = if (myPublicKeyBase64 <= otherPublicKeyBase64) {
                "$myPublicKeyBase64|$otherPublicKeyBase64"
            } else {
                "$otherPublicKeyBase64|$myPublicKeyBase64"
            }
            val salt = MessageDigest.getInstance("SHA-256").digest(orderedPair.toByteArray(Charsets.UTF_8))
            val initMaterial = EcdhHelper.hkdfSha256(
                ikm = sharedSecret,
                salt = salt,
                info = "CryptionChat-DR-Init-v1".toByteArray(Charsets.UTF_8),
                outputLength = 64
            )
            sharedSecret.fill(0)

            val rk0 = initMaterial.copyOfRange(0, 32)
            val ckRespToInit = initMaterial.copyOfRange(32, 64)
            initMaterial.fill(0)

            val state = if (isInitiator) {
                val freshPair = EcdhHelper.generateKeyPair() ?: run {
                    rk0.fill(0)
                    ckRespToInit.fill(0)
                    return false
                }
                val dhOut = EcdhHelper.computeRawSharedSecret(freshPair.first, otherPublicKeyBase64) ?: run {
                    rk0.fill(0)
                    ckRespToInit.fill(0)
                    return false
                }
                val (rk1, cks) = kdfRootKey(rk0, dhOut)
                rk0.fill(0)
                dhOut.fill(0)

                val s = RatchetSessionState(
                    sessionId = sessionId,
                    rootKeyB64 = encodeB64(rk1),
                    sendChainKeyB64 = encodeB64(cks),
                    recvChainKeyB64 = encodeB64(ckRespToInit),
                    dhSendPrivB64 = freshPair.first,
                    dhSendPubB64 = freshPair.second,
                    dhRecvPubB64 = otherPublicKeyBase64,
                    ns = 0,
                    nr = 0,
                    pn = 0
                )
                rk1.fill(0)
                cks.fill(0)
                ckRespToInit.fill(0)
                s
            } else {
                val s = RatchetSessionState(
                    sessionId = sessionId,
                    rootKeyB64 = encodeB64(rk0),
                    sendChainKeyB64 = encodeB64(ckRespToInit),
                    recvChainKeyB64 = null,
                    dhSendPrivB64 = myPrivateKeyBase64,
                    dhSendPubB64 = myPublicKeyBase64,
                    dhRecvPubB64 = otherPublicKeyBase64,
                    ns = 0,
                    nr = 0,
                    pn = 0
                )
                rk0.fill(0)
                ckRespToInit.fill(0)
                s
            }

            storage.saveSessionJson(keyHash, gson.toJson(state))
            return true
        }
    }

    fun clearSessionForKey(keyHex: String?) {
        if (keyHex.isNullOrBlank()) return
        synchronized(lock) {
            val keyHash = sha256Hex(keyHex)
            storage.loadSessionJson(keyHash)?.let { existingJson ->
                val existing = try { gson.fromJson(existingJson, RatchetSessionState::class.java) } catch (_: Exception) { null }
                if (existing != null) {
                    storage.deleteVaultBySession(existing.sessionId)
                }
            }
            storage.deleteSessionJson(keyHash)
        }
    }

    /**
     * Reimposta la sessione Double Ratchet attiva senza cancellare la cronologia già decifrata dal Vault locale.
     */
    fun resetSessionKeepVault(keyHex: String?) {
        if (keyHex.isNullOrBlank()) return
        synchronized(lock) {
            storage.deleteSessionJson(sha256Hex(keyHex))
        }
    }

    fun clearAllSessionsKeepVault() {
        synchronized(lock) {
            storage.clearAllSessions()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            storage.clearAllSessions()
            storage.clearAllVault()
        }
    }

    fun encryptMessage(keyHex: String, plaintext: String): String? {
        if (keyHex.isBlank()) return null
        synchronized(lock) {
            val keyHash = sha256Hex(keyHex)
            val sessionJson = storage.loadSessionJson(keyHash) ?: return null
            val session = try {
                gson.fromJson(sessionJson, RatchetSessionState::class.java)
            } catch (_: Exception) {
                return null
            } ?: return null

            val sendCkB64 = session.sendChainKeyB64 ?: return null
            val sendCk = decodeB64(sendCkB64)
            val (nextSendCk, messageKey) = kdfChainKey(sendCk)
            sendCk.fill(0)

            val header = RatchetHeader(
                sid = session.sessionId,
                dh = session.dhSendPubB64,
                n = session.ns,
                pn = session.pn,
                x3dh = if (session.ns == 0 || session.pendingX3dhInitJson != null) session.pendingX3dhInitJson else null
            )
            val headerBytes = gson.toJson(header).toByteArray(Charsets.UTF_8)
            val headerB64 = encodeUrlB64(headerBytes)
            val aadBytes = buildAad(headerBytes, session.adB64)

            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            return try {
                val paddedPlain = EncryptionHelper.applyPadding(plaintext.toByteArray(Charsets.UTF_8))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD(aadBytes)
                val cipherText = cipher.doFinal(paddedPlain)

                val combined = ByteArray(iv.size + cipherText.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

                val drPayload = "$DR_PREFIX$headerB64:${encodeB64(combined)}"

                // Avanza stato catena solo dopo cifratura riuscita
                session.sendChainKeyB64 = encodeB64(nextSendCk)
                session.ns += 1
                storage.saveSessionJson(keyHash, gson.toJson(session))

                // Salva nel vault locale cifrato per rilettura UI/storico dopo distruzione di messageKey
                storage.putVaultPlaintext(sha256Hex(drPayload), session.sessionId, plaintext)

                drPayload
            } catch (e: Exception) {
                logError("Errore cifratura Double Ratchet", e)
                null
            } finally {
                messageKey.fill(0)
                nextSendCk.fill(0)
            }
        }
    }

    fun decryptMessage(keyHex: String, drPayload: String): String? {
        if (!drPayload.startsWith(DR_PREFIX)) return null
        synchronized(lock) {
            val payloadHash = sha256Hex(drPayload)
            storage.getVaultPlaintext(payloadHash)?.let { return it }

            if (keyHex.isBlank()) return null
            val keyHash = sha256Hex(keyHex)
            val sessionJson = storage.loadSessionJson(keyHash) ?: return null
            val originalSession = try {
                gson.fromJson(sessionJson, RatchetSessionState::class.java)
            } catch (_: Exception) {
                return null
            } ?: return null

            val body = drPayload.substring(DR_PREFIX.length)
            val sepIdx = body.indexOf(':')
            if (sepIdx <= 0 || sepIdx >= body.length - 1) return null

            val headerBytes = try {
                decodeUrlB64(body.substring(0, sepIdx))
            } catch (_: Exception) {
                return null
            }
            val header = try {
                gson.fromJson(String(headerBytes, Charsets.UTF_8), RatchetHeader::class.java)
            } catch (_: Exception) {
                return null
            } ?: return null

            if (header.sid != originalSession.sessionId) return null

            val combined = try {
                decodeB64(body.substring(sepIdx + 1))
            } catch (_: Exception) {
                return null
            }
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            // Operiamo su una copia: se la decifratura/tag GCM fallisce, lo stato persistito resta integro.
            val working = originalSession.deepCopy()
            val skippedMapKey = "${header.dh}#${header.n}"

            val messageKey: ByteArray = try {
                val skippedMkB64 = working.skippedKeys.remove(skippedMapKey)
                if (skippedMkB64 != null) {
                    decodeB64(skippedMkB64)
                } else {
                    if (header.dh != working.dhRecvPubB64) {
                        skipMessageKeys(working, header.pn)
                        performDhRatchet(working, header.dh)
                    }
                    skipMessageKeys(working, header.n)

                    val recvCkB64 = working.recvChainKeyB64 ?: return null
                    val recvCk = decodeB64(recvCkB64)
                    val (nextRecvCk, mk) = kdfChainKey(recvCk)
                    recvCk.fill(0)
                    working.recvChainKeyB64 = encodeB64(nextRecvCk)
                    nextRecvCk.fill(0)
                    working.nr += 1
                    mk
                }
            } catch (e: Exception) {
                logError("Errore avanzamento Double Ratchet", e)
                return null
            }

            return try {
                val aadBytes = buildAad(headerBytes, working.adB64)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD(aadBytes)
                val paddedBytes = cipher.doFinal(cipherText)
                val plainBytes = EncryptionHelper.removePadding(paddedBytes)
                val plaintext = String(plainBytes, Charsets.UTF_8)

                working.pendingX3dhInitJson = null
                storage.saveSessionJson(keyHash, gson.toJson(working))
                storage.putVaultPlaintext(payloadHash, working.sessionId, plaintext)
                plaintext
            } catch (_: Exception) {
                null
            } finally {
                messageKey.fill(0)
            }
        }
    }

    private fun skipMessageKeys(session: RatchetSessionState, until: Int) {
        if (session.nr + MAX_SKIP < until) {
            throw IllegalStateException("Troppi messaggi saltati nella catena Double Ratchet")
        }
        val ckB64 = session.recvChainKeyB64 ?: return
        var ck = decodeB64(ckB64)
        while (session.nr < until) {
            val (nextCk, mk) = kdfChainKey(ck)
            ck.fill(0)
            ck = nextCk
            val mapKey = "${session.dhRecvPubB64}#${session.nr}"
            session.skippedKeys[mapKey] = encodeB64(mk)
            mk.fill(0)
            while (session.skippedKeys.size > MAX_SKIP) {
                val oldestKey = session.skippedKeys.keys.firstOrNull() ?: break
                session.skippedKeys.remove(oldestKey)
            }
            session.nr += 1
        }
        session.recvChainKeyB64 = encodeB64(ck)
        ck.fill(0)
    }

    private fun performDhRatchet(session: RatchetSessionState, newPeerDhPub: String) {
        session.pn = session.ns
        session.ns = 0
        session.nr = 0
        session.dhRecvPubB64 = newPeerDhPub

        val oldRoot = decodeB64(session.rootKeyB64)
        val dhRecv = EcdhHelper.computeRawSharedSecret(session.dhSendPrivB64, session.dhRecvPubB64)
            ?: throw IllegalStateException("ECDH fallito nello step recv del Double Ratchet")
        val (rkAfterRecv, newRecvCk) = kdfRootKey(oldRoot, dhRecv)
        oldRoot.fill(0)
        dhRecv.fill(0)
        session.recvChainKeyB64 = encodeB64(newRecvCk)
        newRecvCk.fill(0)

        val newSendPair = EcdhHelper.generateKeyPair()
            ?: throw IllegalStateException("Generazione coppia ECDH fallita nel Double Ratchet")
        session.dhSendPrivB64 = newSendPair.first
        session.dhSendPubB64 = newSendPair.second

        val dhSend = EcdhHelper.computeRawSharedSecret(session.dhSendPrivB64, session.dhRecvPubB64)
            ?: throw IllegalStateException("ECDH fallito nello step send del Double Ratchet")
        val (rkAfterSend, newSendCk) = kdfRootKey(rkAfterRecv, dhSend)
        rkAfterRecv.fill(0)
        dhSend.fill(0)
        session.rootKeyB64 = encodeB64(rkAfterSend)
        session.sendChainKeyB64 = encodeB64(newSendCk)
        rkAfterSend.fill(0)
        newSendCk.fill(0)
    }
}
