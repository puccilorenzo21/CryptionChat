package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implementazione Sender Keys per chat di gruppo Zero-Knowledge (`SK1:`).
 *
 * Ogni mittente mantiene una catena simmetrica per gruppo `(groupId, senderUserId)` con:
 * - Avanzamento HMAC-SHA256 (`0x01 -> messageKey`, `0x02 -> nextChainKey`) per Forward Secrecy
 * - Firma ECDSA P-256 (`SHA256withECDSA`) per autenticazione dell'origine del mittente nel gruppo
 * - Padding crittografico `PAD1` e cifratura `AES/GCM/NoPadding` con AAD autenticata
 * - Salvataggio dei plaintext decifrati in `dr_vault` (`VaultDao`) per rilettura UI/anteprime
 *   senza conservare vecchie `messageKey`.
 */
object SenderKeyHelper {

    private const val TAG = "SenderKeyHelper"
    const val SK_PREFIX = "SK1:"
    private const val MAX_SKIP = 64
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val lock = Any()

    interface StorageBackend {
        fun loadStateJson(stateKey: String): String?
        fun saveStateJson(stateKey: String, json: String)
        fun deleteStateJson(stateKey: String)
        fun deleteStatesByPrefix(prefix: String)
        fun clearAllStates()

        fun getVaultPlaintext(ciphertextHash: String): String?
        fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String)
        fun deleteVaultBySession(sessionId: String)
        fun clearAllVault()
    }

    class InMemoryStorageBackend : StorageBackend {
        private val states = ConcurrentHashMap<String, String>()
        private val vault = ConcurrentHashMap<String, Pair<String, String>>() // hash -> (sessionId, plaintext)

        override fun loadStateJson(stateKey: String): String? = states[stateKey]
        override fun saveStateJson(stateKey: String, json: String) { states[stateKey] = json }
        override fun deleteStateJson(stateKey: String) { states.remove(stateKey) }
        override fun deleteStatesByPrefix(prefix: String) {
            states.keys.removeIf { it.startsWith(prefix) }
        }
        override fun clearAllStates() { states.clear() }

        override fun getVaultPlaintext(ciphertextHash: String): String? = vault[ciphertextHash]?.second
        override fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String) {
            vault[ciphertextHash] = Pair(sessionId, plaintext)
        }
        override fun deleteVaultBySession(sessionId: String) {
            vault.entries.removeIf { it.value.first == sessionId }
        }
        override fun clearAllVault() { vault.clear() }
    }

    private class DrStorageAdapter(
        private val drBackend: DoubleRatchetHelper.StorageBackend
    ) : StorageBackend {
        private val trackedKeys = ConcurrentHashMap.newKeySet<String>()

        override fun loadStateJson(stateKey: String): String? = drBackend.loadSessionJson(stateKey)

        override fun saveStateJson(stateKey: String, json: String) {
            trackedKeys.add(stateKey)
            drBackend.saveSessionJson(stateKey, json)
        }

        override fun deleteStateJson(stateKey: String) {
            trackedKeys.remove(stateKey)
            drBackend.deleteSessionJson(stateKey)
        }

        override fun deleteStatesByPrefix(prefix: String) {
            val matching = trackedKeys.filter { it.startsWith(prefix) }
            matching.forEach {
                trackedKeys.remove(it)
                drBackend.deleteSessionJson(it)
            }
        }

        override fun clearAllStates() {
            trackedKeys.forEach { drBackend.deleteSessionJson(it) }
            trackedKeys.clear()
        }

        override fun getVaultPlaintext(ciphertextHash: String): String? =
            drBackend.getVaultPlaintext(ciphertextHash)

        override fun putVaultPlaintext(ciphertextHash: String, sessionId: String, plaintext: String) {
            drBackend.putVaultPlaintext(ciphertextHash, sessionId, plaintext)
        }

        override fun deleteVaultBySession(sessionId: String) {
            drBackend.deleteVaultBySession(sessionId)
        }

        override fun clearAllVault() {
            drBackend.clearAllVault()
        }
    }

    private class AndroidStorageBackend(context: Context) : StorageBackend {
        private val appContext = context.applicationContext
        private val memoryStateCache = ConcurrentHashMap<String, String>()
        private val memoryVaultCache = ConcurrentHashMap<String, String>()

        private fun prefKey(stateKey: String) = "sk_state_$stateKey"

        override fun loadStateJson(stateKey: String): String? {
            memoryStateCache[stateKey]?.let { return it }
            return try {
                val json = SecurePrefs.get(appContext).getString(prefKey(stateKey), null)
                if (json != null) memoryStateCache[stateKey] = json
                json
            } catch (e: Exception) {
                logError("Errore lettura stato SenderKey", e)
                null
            }
        }

        override fun saveStateJson(stateKey: String, json: String) {
            memoryStateCache[stateKey] = json
            try {
                SecurePrefs.get(appContext).edit().putString(prefKey(stateKey), json).commit()
            } catch (e: Exception) {
                logError("Errore salvataggio stato SenderKey", e)
            }
        }

        override fun deleteStateJson(stateKey: String) {
            memoryStateCache.remove(stateKey)
            try {
                SecurePrefs.get(appContext).edit().remove(prefKey(stateKey)).commit()
            } catch (e: Exception) {
                logError("Errore eliminazione stato SenderKey", e)
            }
        }

        override fun deleteStatesByPrefix(prefix: String) {
            memoryStateCache.keys.removeIf { it.startsWith(prefix) }
            try {
                val fullPrefix = prefKey(prefix)
                val prefs = SecurePrefs.get(appContext)
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith(fullPrefix) }.forEach { editor.remove(it) }
                editor.commit()
            } catch (e: Exception) {
                logError("Errore pulizia stati SenderKey per gruppo", e)
            }
        }

        override fun clearAllStates() {
            memoryStateCache.clear()
            try {
                val prefs = SecurePrefs.get(appContext)
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("sk_state_") }.forEach { editor.remove(it) }
                editor.commit()
            } catch (e: Exception) {
                logError("Errore pulizia completa stati SenderKey", e)
            }
        }

        override fun getVaultPlaintext(ciphertextHash: String): String? {
            memoryVaultCache[ciphertextHash]?.let { return it }
            return try {
                val plain = AppDatabase.getInstance(appContext).vaultDao().getPlaintextSync(ciphertextHash)
                if (plain != null) memoryVaultCache[ciphertextHash] = plain
                plain
            } catch (e: Exception) {
                logError("Errore lettura LocalVault per SK1", e)
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
                logError("Errore scrittura LocalVault per SK1", e)
            }
        }

        override fun deleteVaultBySession(sessionId: String) {
            memoryVaultCache.clear()
            try {
                AppDatabase.getInstance(appContext).vaultDao().deleteBySessionSync(sessionId)
            } catch (e: Exception) {
                logError("Errore eliminazione LocalVault SK1 per sessione", e)
            }
        }

        override fun clearAllVault() {
            memoryVaultCache.clear()
            try {
                AppDatabase.getInstance(appContext).vaultDao().clearAllSync()
            } catch (e: Exception) {
                logError("Errore pulizia completa LocalVault SK1", e)
            }
        }
    }

    data class SenderKeyState(
        @SerializedName("group_id") val groupId: Int,
        @SerializedName("sender_user_id") val senderUserId: Int,
        @SerializedName("chain_id") val chainId: Int,
        @SerializedName("iteration") var iteration: Int,
        @SerializedName("chain_key_b64") var chainKeyB64: String,
        @SerializedName("sig_pub_b64") val sigPubB64: String,
        @SerializedName("sig_priv_b64") val sigPrivB64: String? = null,
        @SerializedName("skipped_keys") val skippedKeys: MutableMap<String, String> = mutableMapOf()
    ) {
        fun deepCopy(): SenderKeyState {
            return SenderKeyState(
                groupId = groupId,
                senderUserId = senderUserId,
                chainId = chainId,
                iteration = iteration,
                chainKeyB64 = chainKeyB64,
                sigPubB64 = sigPubB64,
                sigPrivB64 = sigPrivB64,
                skippedKeys = skippedKeys.toMutableMap()
            )
        }
    }

    data class SenderKeyDistributionMessage(
        @SerializedName("ver") val ver: Int = 1,
        @SerializedName("group_id") val groupId: Int,
        @SerializedName("sender_user_id") val senderUserId: Int,
        @SerializedName("chain_id") val chainId: Int,
        @SerializedName("iteration") val iteration: Int,
        @SerializedName("chain_key_b64") val chainKeyB64: String,
        @SerializedName("sig_pub_b64") val sigPubB64: String
    )

    data class SkEnvelopeHeader(
        val groupId: Int,
        val senderUserId: Int,
        val chainId: Int,
        val iteration: Int
    )

    @Volatile
    private var storage: StorageBackend = InMemoryStorageBackend()

    fun init(context: Context) {
        synchronized(lock) {
            storage = AndroidStorageBackend(context.applicationContext)
        }
    }

    internal fun setStorageForTests(backend: StorageBackend = InMemoryStorageBackend()) {
        synchronized(lock) {
            storage = backend
        }
    }

    internal fun setStorageForTests(drBackend: DoubleRatchetHelper.StorageBackend) {
        synchronized(lock) {
            storage = DrStorageAdapter(drBackend)
        }
    }

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decodeB64(str: String): ByteArray = Base64.getDecoder().decode(str)

    private fun stateKey(groupId: Int, senderUserId: Int): String = "${groupId}_${senderUserId}"
    private fun groupPrefix(groupId: Int): String = "${groupId}_"
    private fun vaultSessionId(groupId: Int): String = "sk_$groupId"

    private fun isValidEcPublicKey(pubB64: String): Boolean {
        if (pubB64.isBlank()) return false
        return try {
            val bytes = decodeB64(pubB64)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val messageKey = mac.doFinal(byteArrayOf(0x01))

        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val nextChainKey = mac.doFinal(byteArrayOf(0x02))
        return Pair(nextChainKey, messageKey)
    }

    private fun buildAadBytes(
        groupId: Int,
        senderUserId: Int,
        chainId: Int,
        iteration: Int,
        ivB64: String
    ): ByteArray {
        return "SK1|$groupId|$senderUserId|$chainId|$iteration|$ivB64".toByteArray(Charsets.UTF_8)
    }

    private fun buildSignedData(aadBytes: ByteArray, gcmCiphertext: ByteArray): ByteArray {
        val result = ByteArray(aadBytes.size + gcmCiphertext.size)
        System.arraycopy(aadBytes, 0, result, 0, aadBytes.size)
        System.arraycopy(gcmCiphertext, 0, result, aadBytes.size, gcmCiphertext.size)
        return result
    }

    private fun loadState(groupId: Int, senderUserId: Int): SenderKeyState? {
        val json = storage.loadStateJson(stateKey(groupId, senderUserId)) ?: return null
        return try {
            gson.fromJson(json, SenderKeyState::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveState(state: SenderKeyState) {
        storage.saveStateJson(stateKey(state.groupId, state.senderUserId), gson.toJson(state))
    }

    private fun createFreshSenderKey(groupId: Int, myUserId: Int): SenderKeyState {
        val sigPair = EcdhHelper.generateKeyPair()
            ?: throw IllegalStateException("Impossibile generare coppia ECDSA per SenderKey")
        val chainKey = ByteArray(32)
        secureRandom.nextBytes(chainKey)
        val chainId = secureRandom.nextInt(Int.MAX_VALUE - 1) + 1
        val state = SenderKeyState(
            groupId = groupId,
            senderUserId = myUserId,
            chainId = chainId,
            iteration = 0,
            chainKeyB64 = encodeB64(chainKey),
            sigPubB64 = sigPair.second,
            sigPrivB64 = sigPair.first
        )
        chainKey.fill(0)
        saveState(state)
        return state
    }

    fun getOrCreateMySenderKey(groupId: Int, myUserId: Int): SenderKeyState {
        require(groupId > 0 && myUserId > 0) { "groupId e myUserId devono essere positivi" }
        synchronized(lock) {
            val existing = loadState(groupId, myUserId)
            if (existing != null && !existing.sigPrivB64.isNullOrBlank() && existing.chainKeyB64.isNotBlank()) {
                return existing
            }
            return createFreshSenderKey(groupId, myUserId)
        }
    }

    fun rotateMySenderKey(groupId: Int, myUserId: Int): SenderKeyState {
        require(groupId > 0 && myUserId > 0) { "groupId e myUserId devono essere positivi" }
        synchronized(lock) {
            return createFreshSenderKey(groupId, myUserId)
        }
    }

    fun exportDistributionJson(groupId: Int, myUserId: Int): String {
        require(groupId > 0 && myUserId > 0) { "groupId e myUserId devono essere positivi" }
        synchronized(lock) {
            val state = getOrCreateMySenderKey(groupId, myUserId)
            val dist = SenderKeyDistributionMessage(
                ver = 1,
                groupId = state.groupId,
                senderUserId = state.senderUserId,
                chainId = state.chainId,
                iteration = state.iteration,
                chainKeyB64 = state.chainKeyB64,
                sigPubB64 = state.sigPubB64
            )
            return gson.toJson(dist)
        }
    }

    fun importDistributionJson(json: String): Boolean {
        if (json.isBlank()) return false
        val dist = try {
            gson.fromJson(json, SenderKeyDistributionMessage::class.java)
        } catch (_: Exception) {
            return false
        } ?: return false

        if (dist.ver != 1 || dist.groupId <= 0 || dist.senderUserId <= 0 || dist.chainId <= 0 || dist.iteration < 0) {
            return false
        }
        val ckBytes = try { decodeB64(dist.chainKeyB64) } catch (_: Exception) { return false }
        if (ckBytes.size != 32) {
            ckBytes.fill(0)
            return false
        }
        ckBytes.fill(0)
        if (!isValidEcPublicKey(dist.sigPubB64)) return false

        synchronized(lock) {
            val existing = loadState(dist.groupId, dist.senderUserId)
            if (existing != null && existing.chainId == dist.chainId && existing.sigPubB64 == dist.sigPubB64) {
                // Non riavvolgere una catena già avanzata localmente (es. pacchetto duplicato o nostro stato locale)
                if (existing.iteration >= dist.iteration || !existing.sigPrivB64.isNullOrBlank()) {
                    return true
                }
            }

            val imported = SenderKeyState(
                groupId = dist.groupId,
                senderUserId = dist.senderUserId,
                chainId = dist.chainId,
                iteration = dist.iteration,
                chainKeyB64 = dist.chainKeyB64,
                sigPubB64 = dist.sigPubB64,
                sigPrivB64 = null
            )
            saveState(imported)
            return true
        }
    }

    fun parseEnvelopeHeader(skPayload: String): SkEnvelopeHeader? {
        if (!skPayload.startsWith(SK_PREFIX)) return null
        val parts = skPayload.substring(SK_PREFIX.length).split(':', limit = 5)
        if (parts.size != 5) return null
        val groupId = parts[0].toIntOrNull() ?: return null
        val senderUserId = parts[1].toIntOrNull() ?: return null
        val chainId = parts[2].toIntOrNull() ?: return null
        val iteration = parts[3].toIntOrNull() ?: return null
        if (groupId <= 0 || senderUserId <= 0 || chainId <= 0 || iteration < 0 || parts[4].isBlank()) return null
        return SkEnvelopeHeader(groupId, senderUserId, chainId, iteration)
    }

    fun hasMatchingChainKey(groupId: Int, senderUserId: Int, chainId: Int): Boolean {
        if (groupId <= 0 || senderUserId <= 0 || chainId <= 0) return false
        synchronized(lock) {
            val state = loadState(groupId, senderUserId) ?: return false
            return state.chainId == chainId
        }
    }

    /**
     * Cifra un messaggio di gruppo usando il Sender Key locale di `(groupId, myUserId)`.
     *
     * Formato: `SK1:<groupId>:<senderUserId>:<chainId>:<iteration>:<base64(iv12 || gcmCiphertext(PAD1(plaintext)) || ecdsaSig || sigLen2B)>`
     */
    fun encryptGroupMessage(groupId: Int, myUserId: Int, plaintext: String): String? {
        if (groupId <= 0 || myUserId <= 0) return null
        synchronized(lock) {
            val state = getOrCreateMySenderKey(groupId, myUserId)
            val sigPriv = state.sigPrivB64 ?: return null
            val currentChainKey = try { decodeB64(state.chainKeyB64) } catch (_: Exception) { return null }
            val (nextChainKey, messageKey) = kdfChainKey(currentChainKey)
            currentChainKey.fill(0)

            val currentIteration = state.iteration
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)
            val ivB64 = encodeB64(iv)
            val aadBytes = buildAadBytes(groupId, myUserId, state.chainId, currentIteration, ivB64)

            return try {
                val paddedPlain = EncryptionHelper.applyPadding(plaintext.toByteArray(Charsets.UTF_8))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD(aadBytes)
                val gcmCiphertext = cipher.doFinal(paddedPlain)

                val sigB64 = EcdhHelper.signData(sigPriv, buildSignedData(aadBytes, gcmCiphertext))
                    ?: return null
                val ecdsaSig = decodeB64(sigB64)
                if (ecdsaSig.isEmpty() || ecdsaSig.size > 0xFFFF) return null

                val combinedLen = iv.size + gcmCiphertext.size + ecdsaSig.size + 2
                val combined = ByteArray(combinedLen)
                var pos = 0
                System.arraycopy(iv, 0, combined, pos, iv.size)
                pos += iv.size
                System.arraycopy(gcmCiphertext, 0, combined, pos, gcmCiphertext.size)
                pos += gcmCiphertext.size
                System.arraycopy(ecdsaSig, 0, combined, pos, ecdsaSig.size)
                pos += ecdsaSig.size
                val sigLenBytes = ByteBuffer.allocate(2).putShort(ecdsaSig.size.toShort()).array()
                System.arraycopy(sigLenBytes, 0, combined, pos, 2)

                val skPayload = "$SK_PREFIX$groupId:$myUserId:${state.chainId}:$currentIteration:${encodeB64(combined)}"

                // Avanza catena solo dopo cifratura e firma riuscite
                state.chainKeyB64 = encodeB64(nextChainKey)
                state.iteration = currentIteration + 1
                saveState(state)

                storage.putVaultPlaintext(
                    DoubleRatchetHelper.sha256Hex(skPayload),
                    vaultSessionId(groupId),
                    plaintext
                )

                skPayload
            } catch (e: Exception) {
                logError("Errore cifratura SenderKey SK1", e)
                null
            } finally {
                messageKey.fill(0)
                nextChainKey.fill(0)
            }
        }
    }

    fun decryptGroupMessage(skPayload: String): String? {
        if (!skPayload.startsWith(SK_PREFIX)) return null
        synchronized(lock) {
            val payloadHash = DoubleRatchetHelper.sha256Hex(skPayload)
            storage.getVaultPlaintext(payloadHash)?.let { return it }

            val parts = skPayload.substring(SK_PREFIX.length).split(':', limit = 5)
            if (parts.size != 5) return null
            val groupId = parts[0].toIntOrNull() ?: return null
            val senderUserId = parts[1].toIntOrNull() ?: return null
            val chainId = parts[2].toIntOrNull() ?: return null
            val iteration = parts[3].toIntOrNull() ?: return null
            if (groupId <= 0 || senderUserId <= 0 || chainId <= 0 || iteration < 0) return null

            val combined = try { decodeB64(parts[4]) } catch (_: Exception) { return null }
            if (combined.size <= GCM_IV_LENGTH + 16 + 2) return null

            val sigLen = ((combined[combined.size - 2].toInt() and 0xFF) shl 8) or
                (combined[combined.size - 1].toInt() and 0xFF)
            val sigStart = combined.size - 2 - sigLen
            if (sigLen <= 0 || sigStart <= GCM_IV_LENGTH + 16) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val gcmCiphertext = combined.copyOfRange(GCM_IV_LENGTH, sigStart)
            val ecdsaSig = combined.copyOfRange(sigStart, combined.size - 2)

            val originalState = loadState(groupId, senderUserId) ?: return null
            if (originalState.chainId != chainId) return null

            val ivB64 = encodeB64(iv)
            val aadBytes = buildAadBytes(groupId, senderUserId, chainId, iteration, ivB64)
            val sigOk = EcdhHelper.verifySignature(
                pubB64 = originalState.sigPubB64,
                dataBytes = buildSignedData(aadBytes, gcmCiphertext),
                sigB64 = encodeB64(ecdsaSig)
            )
            if (!sigOk) {
                logError("Firma ECDSA non valida per messaggio SK1 groupId=$groupId sender=$senderUserId")
                return null
            }

            val working = originalState.deepCopy()
            val skippedMapKey = "$chainId#$iteration"
            val messageKey: ByteArray = try {
                val skippedMkB64 = working.skippedKeys.remove(skippedMapKey)
                if (skippedMkB64 != null) {
                    decodeB64(skippedMkB64)
                } else {
                    if (iteration < working.iteration) return null
                    if (iteration - working.iteration > MAX_SKIP) return null

                    var ck = decodeB64(working.chainKeyB64)
                    while (working.iteration < iteration) {
                        val (nextCk, skippedMk) = kdfChainKey(ck)
                        ck.fill(0)
                        ck = nextCk
                        working.skippedKeys["$chainId#${working.iteration}"] = encodeB64(skippedMk)
                        skippedMk.fill(0)
                        while (working.skippedKeys.size > MAX_SKIP) {
                            val oldestKey = working.skippedKeys.keys.firstOrNull() ?: break
                            working.skippedKeys.remove(oldestKey)
                        }
                        working.iteration += 1
                    }

                    val (nextCk, mk) = kdfChainKey(ck)
                    ck.fill(0)
                    working.chainKeyB64 = encodeB64(nextCk)
                    nextCk.fill(0)
                    working.iteration += 1
                    mk
                }
            } catch (e: Exception) {
                logError("Errore avanzamento catena SenderKey", e)
                return null
            }

            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD(aadBytes)
                val paddedPlain = cipher.doFinal(gcmCiphertext)
                val plainBytes = EncryptionHelper.removePadding(paddedPlain)
                val plaintext = String(plainBytes, Charsets.UTF_8)

                saveState(working)
                storage.putVaultPlaintext(payloadHash, vaultSessionId(groupId), plaintext)
                plaintext
            } catch (_: Exception) {
                null
            } finally {
                messageKey.fill(0)
            }
        }
    }

    fun clearGroup(groupId: Int) {
        if (groupId <= 0) return
        synchronized(lock) {
            storage.deleteStatesByPrefix(groupPrefix(groupId))
            storage.deleteVaultBySession(vaultSessionId(groupId))
        }
    }

    fun clearAll() {
        synchronized(lock) {
            storage.clearAllStates()
            storage.clearAllVault()
        }
    }
}
