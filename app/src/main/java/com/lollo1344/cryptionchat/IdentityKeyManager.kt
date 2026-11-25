package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestisce Identity Keys persistenti (IK_sign + IK_dh), Signed PreKeys (SPK con rotazione
 * ogni 14 giorni e grazia per la precedente), batch di 50 One-Time PreKeys (OPK) monouso
 * e pinning TOFU fail-closed in `trusted_identities`.
 */
object IdentityKeyManager {

    private const val TAG = "IdentityKeyManager"
    const val SPK_ROTATION_INTERVAL_MS = 14L * 24L * 60L * 60L * 1000L
    const val DEFAULT_OPK_BATCH_SIZE = 50
    const val LOW_OPK_THRESHOLD = 15
    const val MIN_VALID_OPK_ID = 1_000_000_000
    private const val MIN_VALID_PROD_SPK_ID = 10_000_000
    private const val MAX_SPK_HISTORY = 30
    private const val MAX_ARCHIVED_OPKS = 250

    private const val KEY_ACTIVE_USER_ID = "ikm_active_user_id"

    private val gson = Gson()
    private val lock = Any()

    interface KeyStorage {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun getInt(key: String, defaultValue: Int): Int
        fun putInt(key: String, value: Int)
        fun getLong(key: String, defaultValue: Long): Long
        fun putLong(key: String, value: Long)
        fun remove(key: String)
        fun clearAll()
    }

    interface TrustedIdentityStore {
        fun getByPeerId(peerUserId: Int): TrustedIdentityEntity?
        fun getAll(): List<TrustedIdentityEntity>
        fun upsert(entity: TrustedIdentityEntity)
        fun updateTrustState(peerUserId: Int, trustState: String, verifiedAt: Long?)
        fun deleteByPeerId(peerUserId: Int)
        fun clearAll()
    }

    class InMemoryKeyStorage : KeyStorage {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = (map[key] as? Int) ?: defaultValue
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getLong(key: String, defaultValue: Long): Long = (map[key] as? Long) ?: defaultValue
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clearAll() { map.clear() }
    }

    class InMemoryTrustedIdentityStore : TrustedIdentityStore {
        private val map = ConcurrentHashMap<Int, TrustedIdentityEntity>()
        override fun getByPeerId(peerUserId: Int): TrustedIdentityEntity? = map[peerUserId]
        override fun getAll(): List<TrustedIdentityEntity> = map.values.sortedBy { it.peerUserId }
        override fun upsert(entity: TrustedIdentityEntity) { map[entity.peerUserId] = entity }
        override fun updateTrustState(peerUserId: Int, trustState: String, verifiedAt: Long?) {
            val current = map[peerUserId] ?: return
            map[peerUserId] = current.copy(trustState = trustState, verifiedAt = verifiedAt)
        }
        override fun deleteByPeerId(peerUserId: Int) { map.remove(peerUserId) }
        override fun clearAll() { map.clear() }
    }

    private class AndroidSecurePrefsStorage(context: Context) : KeyStorage {
        private val appContext = context.applicationContext

        override fun getString(key: String): String? = try {
            SecurePrefs.get(appContext).getString(key, null)
        } catch (e: Exception) {
            logError("Errore lettura KeyStorage", e)
            null
        }

        override fun putString(key: String, value: String) {
            try {
                SecurePrefs.get(appContext).edit().putString(key, value).commit()
            } catch (e: Exception) {
                logError("Errore scrittura KeyStorage", e)
            }
        }

        override fun getInt(key: String, defaultValue: Int): Int = try {
            SecurePrefs.get(appContext).getInt(key, defaultValue)
        } catch (e: Exception) {
            logError("Errore lettura Int KeyStorage", e)
            defaultValue
        }

        override fun putInt(key: String, value: Int) {
            try {
                SecurePrefs.get(appContext).edit().putInt(key, value).commit()
            } catch (e: Exception) {
                logError("Errore scrittura Int KeyStorage", e)
            }
        }

        override fun getLong(key: String, defaultValue: Long): Long = try {
            SecurePrefs.get(appContext).getLong(key, defaultValue)
        } catch (e: Exception) {
            logError("Errore lettura Long KeyStorage", e)
            defaultValue
        }

        override fun putLong(key: String, value: Long) {
            try {
                SecurePrefs.get(appContext).edit().putLong(key, value).commit()
            } catch (e: Exception) {
                logError("Errore scrittura Long KeyStorage", e)
            }
        }

        override fun remove(key: String) {
            try {
                SecurePrefs.get(appContext).edit().remove(key).commit()
            } catch (e: Exception) {
                logError("Errore rimozione KeyStorage", e)
            }
        }

        override fun clearAll() {
            try {
                val prefs = SecurePrefs.get(appContext)
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("ikm_") }.forEach { editor.remove(it) }
                editor.commit()
            } catch (e: Exception) {
                logError("Errore pulizia KeyStorage", e)
            }
        }
    }

    private class AndroidRoomTrustedIdentityStore(context: Context) : TrustedIdentityStore {
        private val appContext = context.applicationContext

        override fun getByPeerId(peerUserId: Int): TrustedIdentityEntity? = try {
            AppDatabase.getInstance(appContext).trustedIdentityDao().getByPeerIdSync(peerUserId)
        } catch (e: Exception) {
            logError("Errore lettura trusted_identities", e)
            null
        }

        override fun getAll(): List<TrustedIdentityEntity> = try {
            AppDatabase.getInstance(appContext).trustedIdentityDao().getAllSync()
        } catch (e: Exception) {
            logError("Errore lista trusted_identities", e)
            emptyList()
        }

        override fun upsert(entity: TrustedIdentityEntity) {
            try {
                AppDatabase.getInstance(appContext).trustedIdentityDao().upsertSync(entity)
            } catch (e: Exception) {
                logError("Errore upsert trusted_identities", e)
            }
        }

        override fun updateTrustState(peerUserId: Int, trustState: String, verifiedAt: Long?) {
            try {
                AppDatabase.getInstance(appContext).trustedIdentityDao().updateTrustStateSync(peerUserId, trustState, verifiedAt)
            } catch (e: Exception) {
                logError("Errore aggiornamento stato trusted_identities", e)
            }
        }

        override fun deleteByPeerId(peerUserId: Int) {
            try {
                AppDatabase.getInstance(appContext).trustedIdentityDao().deleteByPeerIdSync(peerUserId)
            } catch (e: Exception) {
                logError("Errore eliminazione trusted_identities", e)
            }
        }

        override fun clearAll() {
            try {
                AppDatabase.getInstance(appContext).trustedIdentityDao().clearAllSync()
            } catch (e: Exception) {
                logError("Errore pulizia trusted_identities", e)
            }
        }
    }

    data class LocalIdentityKeys(
        @SerializedName("user_id") val userId: Int,
        @SerializedName("ik_sign_priv") val ikSignPrivB64: String,
        @SerializedName("ik_sign_pub") val ikSignPubB64: String,
        @SerializedName("ik_dh_priv") val ikDhPrivB64: String,
        @SerializedName("ik_dh_pub") val ikDhPubB64: String,
        @SerializedName("ik_dh_sig") val ikDhSigB64: String
    )

    data class SignedPreKeyRecord(
        @SerializedName("spk_id") val spkId: Int,
        @SerializedName("priv") val privB64: String,
        @SerializedName("pub") val pubB64: String,
        @SerializedName("sig") val sigB64: String,
        @SerializedName("created_at") val createdAt: Long
    )

    data class OneTimePreKeyRecord(
        @SerializedName("opk_id") val opkId: Int,
        @SerializedName("priv") val privB64: String,
        @SerializedName("pub") val pubB64: String
    )

    data class OneTimePreKeyUploadDto(
        @SerializedName("opk_id") val opkId: Int,
        @SerializedName("opk_pub") val opkPub: String
    )

    private data class OpkStoreWrapper(
        @SerializedName("next_opk_id") var nextOpkId: Int = 1,
        @SerializedName("keys") val keys: MutableMap<Int, OneTimePreKeyRecord> = mutableMapOf()
    )

    private data class SpkHistoryWrapper(
        @SerializedName("records") val records: MutableMap<Int, SignedPreKeyRecord> = mutableMapOf()
    )

    data class LocalKeyBundleUpload(
        val userId: Int,
        val ikSignPubB64: String,
        val ikDhPubB64: String,
        val ikDhSigB64: String,
        val spkId: Int,
        val spkPubB64: String,
        val spkSigB64: String,
        val spkCreatedAt: Long,
        val oneTimePreKeys: List<OneTimePreKeyRecord>,
        val opksJson: String
    )

    data class RemotePreKeyBundle(
        val userId: Int,
        val ikSignPubB64: String,
        val ikDhPubB64: String,
        val ikDhSigB64: String,
        val spkId: Int,
        val spkPubB64: String,
        val spkSigB64: String,
        val spkCreatedAt: Long,
        val opkId: Int? = null,
        val opkPubB64: String? = null
    )

    data class TofuEvaluation(
        val peerUserId: Int,
        val trustState: String,
        val allowed: Boolean,
        val isFirstContact: Boolean,
        val keyChanged: Boolean,
        val fingerprintHex: String,
        val entity: TrustedIdentityEntity
    )

    @Volatile
    private var keyStorage: KeyStorage = InMemoryKeyStorage()

    @Volatile
    private var identityStore: TrustedIdentityStore = InMemoryTrustedIdentityStore()

    @Volatile
    private var isTestBackend: Boolean = false

    fun init(context: Context) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            keyStorage = AndroidSecurePrefsStorage(appCtx)
            identityStore = AndroidRoomTrustedIdentityStore(appCtx)
            isTestBackend = false
        }
    }

    internal fun setStorageForTests(
        keyBackend: KeyStorage = InMemoryKeyStorage(),
        identityBackend: TrustedIdentityStore = InMemoryTrustedIdentityStore()
    ) {
        synchronized(lock) {
            keyStorage = keyBackend
            identityStore = identityBackend
            isTestBackend = true
        }
    }

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun ensureAndroidStores(context: Context?) {
        if (context != null && !isTestBackend && keyStorage is InMemoryKeyStorage) {
            val appCtx = context.applicationContext
            keyStorage = AndroidSecurePrefsStorage(appCtx)
            identityStore = AndroidRoomTrustedIdentityStore(appCtx)
        }
    }

    private fun identityKeyPref(userId: Int) = "ikm_identity_$userId"
    private fun spkCurrentPref(userId: Int) = "ikm_spk_current_$userId"
    private fun spkPreviousPref(userId: Int) = "ikm_spk_prev_$userId"
    private fun spkHistoryPref(userId: Int) = "ikm_spk_history_$userId"
    private fun spkNextIdPref(userId: Int) = "ikm_spk_next_id_$userId"
    private fun opkStorePref(userId: Int) = "ikm_opk_store_$userId"
    private fun opkArchivePref(userId: Int) = "ikm_opk_archive_$userId"

    private fun defaultInitialSpkId(): Int =
        (((System.currentTimeMillis() / 1000L) % 10_000_000L).toInt().coerceAtLeast(1) * 10) + 1

    private fun loadSpkHistory(userId: Int): SpkHistoryWrapper {
        val json = keyStorage.getString(spkHistoryPref(userId)) ?: return SpkHistoryWrapper()
        return try {
            gson.fromJson(json, SpkHistoryWrapper::class.java) ?: SpkHistoryWrapper()
        } catch (_: Exception) {
            SpkHistoryWrapper()
        }
    }

    private fun saveToSpkHistory(userId: Int, vararg records: SignedPreKeyRecord?) {
        val history = loadSpkHistory(userId)
        for (rec in records) {
            if (rec != null) {
                history.records[rec.spkId] = rec
            }
        }
        if (history.records.size > MAX_SPK_HISTORY) {
            val toRemove = history.records.values
                .sortedWith(compareBy<SignedPreKeyRecord> { it.createdAt }.thenBy { it.spkId })
                .take(history.records.size - MAX_SPK_HISTORY)
            toRemove.forEach { history.records.remove(it.spkId) }
        }
        keyStorage.putString(spkHistoryPref(userId), gson.toJson(history))
    }

    private fun loadOpkArchive(userId: Int): OpkStoreWrapper {
        val json = keyStorage.getString(opkArchivePref(userId)) ?: return OpkStoreWrapper()
        return try {
            gson.fromJson(json, OpkStoreWrapper::class.java) ?: OpkStoreWrapper()
        } catch (_: Exception) {
            OpkStoreWrapper()
        }
    }

    private fun archiveOpks(userId: Int, records: Collection<OneTimePreKeyRecord>) {
        if (records.isEmpty()) return
        val archive = loadOpkArchive(userId)
        for (rec in records) {
            archive.keys[rec.opkId] = rec
        }
        if (archive.keys.size > MAX_ARCHIVED_OPKS) {
            val toRemove = archive.keys.keys.sorted().take(archive.keys.size - MAX_ARCHIVED_OPKS)
            toRemove.forEach { archive.keys.remove(it) }
        }
        keyStorage.putString(opkArchivePref(userId), gson.toJson(archive))
    }

    private fun resolveUserId(userId: Int?): Int {
        if (userId != null && userId > 0) return userId
        return keyStorage.getInt(KEY_ACTIVE_USER_ID, 0)
    }

    private fun isValidEcPublicKey(pubB64: String): Boolean {
        if (pubB64.isBlank()) return false
        return try {
            val bytes = SafeBase64.decodeStandard(pubB64)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildIkDhSignedData(ikDhPubB64: String): ByteArray {
        return "CC-IKDH-v1|$ikDhPubB64".toByteArray(Charsets.UTF_8)
    }

    fun buildSpkSignedData(userId: Int, spkId: Int, spkPubB64: String, createdAt: Long): ByteArray {
        return "CC-SPK-v1|$userId|$spkId|$spkPubB64|$createdAt".toByteArray(Charsets.UTF_8)
    }

    fun ensureLocalIdentity(context: Context? = null, userId: Int): LocalIdentityKeys {
        require(userId > 0) { "userId non valido: $userId" }
        synchronized(lock) {
            ensureAndroidStores(context)
            keyStorage.putInt(KEY_ACTIVE_USER_ID, userId)

            keyStorage.getString(identityKeyPref(userId))?.let { json ->
                val existing = try { gson.fromJson(json, LocalIdentityKeys::class.java) } catch (_: Exception) { null }
                if (existing != null && existing.userId == userId && existing.ikSignPubB64.isNotBlank() && existing.ikDhPubB64.isNotBlank()) {
                    return existing
                }
            }

            val signPair = EcdhHelper.generateKeyPair()
                ?: throw IllegalStateException("Impossibile generare coppia IK_sign secp256r1")
            val dhPair = EcdhHelper.generateKeyPair()
                ?: throw IllegalStateException("Impossibile generare coppia IK_dh secp256r1")
            val ikDhSig = EcdhHelper.signData(signPair.first, buildIkDhSignedData(dhPair.second))
                ?: throw IllegalStateException("Impossibile firmare IK_dh con IK_sign")

            val created = LocalIdentityKeys(
                userId = userId,
                ikSignPrivB64 = signPair.first,
                ikSignPubB64 = signPair.second,
                ikDhPrivB64 = dhPair.first,
                ikDhPubB64 = dhPair.second,
                ikDhSigB64 = ikDhSig
            )
            keyStorage.putString(identityKeyPref(userId), gson.toJson(created))
            return created
        }
    }

    fun getLocalIdentity(context: Context? = null, userId: Int? = null): LocalIdentityKeys? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return null
            val json = keyStorage.getString(identityKeyPref(uid)) ?: return null
            return try {
                gson.fromJson(json, LocalIdentityKeys::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun ensureSignedPreKey(
        context: Context? = null,
        userId: Int,
        nowMillis: Long = System.currentTimeMillis(),
        forceRotate: Boolean = false
    ): Pair<SignedPreKeyRecord, Boolean> {
        require(userId > 0) { "userId non valido: $userId" }
        synchronized(lock) {
            val identity = ensureLocalIdentity(context, userId)
            val currentJson = keyStorage.getString(spkCurrentPref(userId))
            val current = currentJson?.let {
                try { gson.fromJson(it, SignedPreKeyRecord::class.java) } catch (_: Exception) { null }
            }

            val needsProdIdUpgrade = !isTestBackend && current != null && current.spkId < MIN_VALID_PROD_SPK_ID
            if (!forceRotate && !needsProdIdUpgrade && current != null && (nowMillis - current.createdAt) < SPK_ROTATION_INTERVAL_MS) {
                saveToSpkHistory(userId, current)
                return Pair(current, false)
            }

            if (current != null) {
                keyStorage.putString(spkPreviousPref(userId), gson.toJson(current))
                saveToSpkHistory(userId, current)
            }

            val minSpkId = if (isTestBackend) 1 else defaultInitialSpkId()
            val nextId = maxOf(minSpkId, keyStorage.getInt(spkNextIdPref(userId), minSpkId))
            keyStorage.putInt(spkNextIdPref(userId), nextId + 1)

            val spkPair = EcdhHelper.generateKeyPair()
                ?: throw IllegalStateException("Impossibile generare coppia SignedPreKey")
            val spkSig = EcdhHelper.signData(
                identity.ikSignPrivB64,
                buildSpkSignedData(userId, nextId, spkPair.second, nowMillis)
            ) ?: throw IllegalStateException("Impossibile firmare SignedPreKey")

            val fresh = SignedPreKeyRecord(
                spkId = nextId,
                privB64 = spkPair.first,
                pubB64 = spkPair.second,
                sigB64 = spkSig,
                createdAt = nowMillis
            )
            keyStorage.putString(spkCurrentPref(userId), gson.toJson(fresh))
            saveToSpkHistory(userId, fresh)
            return Pair(fresh, true)
        }
    }

    fun getSignedPreKey(spkId: Int, userId: Int? = null, context: Context? = null): SignedPreKeyRecord? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return null

            val current = keyStorage.getString(spkCurrentPref(uid))?.let {
                try { gson.fromJson(it, SignedPreKeyRecord::class.java) } catch (_: Exception) { null }
            }
            if (current != null && current.spkId == spkId) return current

            val previous = keyStorage.getString(spkPreviousPref(uid))?.let {
                try { gson.fromJson(it, SignedPreKeyRecord::class.java) } catch (_: Exception) { null }
            }
            if (previous != null && previous.spkId == spkId) return previous

            val history = loadSpkHistory(uid)
            return history.records[spkId]
        }
    }

    fun getCurrentSignedPreKey(userId: Int? = null, context: Context? = null): SignedPreKeyRecord? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return null
            return keyStorage.getString(spkCurrentPref(uid))?.let {
                try { gson.fromJson(it, SignedPreKeyRecord::class.java) } catch (_: Exception) { null }
            }
        }
    }

    fun getPreviousSignedPreKey(userId: Int? = null, context: Context? = null): SignedPreKeyRecord? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return null
            return keyStorage.getString(spkPreviousPref(uid))?.let {
                try { gson.fromJson(it, SignedPreKeyRecord::class.java) } catch (_: Exception) { null }
            }
        }
    }

    private fun defaultInitialOpkId(): Int =
        MIN_VALID_OPK_ID + (((System.currentTimeMillis() / 1000L) % 10_000_000L).toInt().coerceAtLeast(1) * 100) + 1

    private fun loadOpkStore(userId: Int): OpkStoreWrapper {
        val json = keyStorage.getString(opkStorePref(userId)) ?: return OpkStoreWrapper(nextOpkId = defaultInitialOpkId())
        val loaded = try {
            gson.fromJson(json, OpkStoreWrapper::class.java) ?: OpkStoreWrapper(nextOpkId = defaultInitialOpkId())
        } catch (_: Exception) {
            OpkStoreWrapper(nextOpkId = defaultInitialOpkId())
        }
        if (loaded.nextOpkId < MIN_VALID_OPK_ID) {
            if (loaded.keys.isNotEmpty()) {
                archiveOpks(userId, loaded.keys.values)
                loaded.keys.clear()
            }
            loaded.nextOpkId = defaultInitialOpkId()
            saveOpkStore(userId, loaded)
        }
        return loaded
    }

    private fun saveOpkStore(userId: Int, wrapper: OpkStoreWrapper) {
        keyStorage.putString(opkStorePref(userId), gson.toJson(wrapper))
    }

    fun generateOneTimePreKeys(
        userId: Int,
        count: Int = DEFAULT_OPK_BATCH_SIZE,
        context: Context? = null
    ): List<OneTimePreKeyRecord> {
        require(userId > 0) { "userId non valido: $userId" }
        if (count <= 0) return emptyList()
        synchronized(lock) {
            ensureAndroidStores(context)
            keyStorage.putInt(KEY_ACTIVE_USER_ID, userId)

            val store = loadOpkStore(userId)
            val generated = ArrayList<OneTimePreKeyRecord>(count)
            for (i in 0 until count) {
                val opkId = store.nextOpkId++
                val pair = EcdhHelper.generateKeyPair()
                    ?: throw IllegalStateException("Impossibile generare OPK #$opkId")
                val rec = OneTimePreKeyRecord(opkId = opkId, privB64 = pair.first, pubB64 = pair.second)
                store.keys[opkId] = rec
                generated.add(rec)
            }
            saveOpkStore(userId, store)
            return generated
        }
    }

    fun replenishOneTimePreKeysIfNeeded(
        userId: Int,
        serverCount: Int? = null,
        targetCount: Int = DEFAULT_OPK_BATCH_SIZE,
        minThreshold: Int = LOW_OPK_THRESHOLD,
        context: Context? = null
    ): List<OneTimePreKeyRecord> {
        require(userId > 0) { "userId non valido: $userId" }
        synchronized(lock) {
            ensureAndroidStores(context)
            val store = loadOpkStore(userId)
            val localCount = store.keys.size
            val effectiveCount = serverCount?.let { minOf(localCount, it) } ?: localCount
            if (localCount > 0 && effectiveCount >= minThreshold) {
                return emptyList()
            }
            val missing = maxOf(1, targetCount - effectiveCount)
            return generateOneTimePreKeys(userId, missing, context)
        }
    }

    fun consumeOneTimePreKey(
        opkId: Int,
        userId: Int? = null,
        context: Context? = null
    ): OneTimePreKeyRecord? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return null
            val store = loadOpkStore(uid)
            val removed = store.keys.remove(opkId)
            if (removed != null) {
                saveOpkStore(uid, store)
                return removed
            }
            val archive = loadOpkArchive(uid)
            val archivedRemoved = archive.keys.remove(opkId) ?: return null
            keyStorage.putString(opkArchivePref(uid), gson.toJson(archive))
            return archivedRemoved
        }
    }

    fun getLocalOneTimePreKeyCount(userId: Int? = null, context: Context? = null): Int {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return 0
            return loadOpkStore(uid).keys.size
        }
    }

    fun getAllLocalOneTimePreKeys(userId: Int? = null, context: Context? = null): List<OneTimePreKeyRecord> {
        synchronized(lock) {
            ensureAndroidStores(context)
            val uid = resolveUserId(userId)
            if (uid <= 0) return emptyList()
            return loadOpkStore(uid).keys.values.sortedBy { it.opkId }
        }
    }

    fun toOpksUploadJson(opks: List<OneTimePreKeyRecord>): String {
        val dtos = opks.map { OneTimePreKeyUploadDto(opkId = it.opkId, opkPub = it.pubB64) }
        return gson.toJson(dtos)
    }

    fun ensureLocalBundle(
        context: Context? = null,
        userId: Int,
        nowMillis: Long = System.currentTimeMillis(),
        forceRotate: Boolean = false
    ): LocalKeyBundleUpload {
        synchronized(lock) {
            val identity = ensureLocalIdentity(context, userId)
            val (spk, _) = ensureSignedPreKey(context, userId, nowMillis, forceRotate = forceRotate)
            val opkStore = loadOpkStore(userId)
            val opks = if (opkStore.keys.isEmpty()) {
                generateOneTimePreKeys(userId, DEFAULT_OPK_BATCH_SIZE, context)
            } else if (forceRotate && opkStore.keys.size < DEFAULT_OPK_BATCH_SIZE) {
                replenishOneTimePreKeysIfNeeded(
                    userId = userId,
                    serverCount = opkStore.keys.size,
                    targetCount = DEFAULT_OPK_BATCH_SIZE,
                    minThreshold = DEFAULT_OPK_BATCH_SIZE,
                    context = context
                )
                getAllLocalOneTimePreKeys(userId, context)
            } else {
                opkStore.keys.values.sortedBy { it.opkId }
            }
            return LocalKeyBundleUpload(
                userId = userId,
                ikSignPubB64 = identity.ikSignPubB64,
                ikDhPubB64 = identity.ikDhPubB64,
                ikDhSigB64 = identity.ikDhSigB64,
                spkId = spk.spkId,
                spkPubB64 = spk.pubB64,
                spkSigB64 = spk.sigB64,
                spkCreatedAt = spk.createdAt,
                oneTimePreKeys = opks,
                opksJson = toOpksUploadJson(opks)
            )
        }
    }

    fun verifyRemoteBundle(bundle: RemotePreKeyBundle): Boolean {
        return verifyRemoteBundle(
            userId = bundle.userId,
            ikSignPubB64 = bundle.ikSignPubB64,
            ikDhPubB64 = bundle.ikDhPubB64,
            ikDhSigB64 = bundle.ikDhSigB64,
            spkId = bundle.spkId,
            spkPubB64 = bundle.spkPubB64,
            spkSigB64 = bundle.spkSigB64,
            spkCreatedAt = bundle.spkCreatedAt,
            opkPubB64 = bundle.opkPubB64
        )
    }

    fun verifyRemoteBundle(
        userId: Int,
        ikSignPubB64: String,
        ikDhPubB64: String,
        ikDhSigB64: String,
        spkId: Int,
        spkPubB64: String,
        spkSigB64: String,
        spkCreatedAt: Long,
        opkPubB64: String? = null
    ): Boolean {
        if (userId <= 0) return false
        if (!isValidEcPublicKey(ikSignPubB64)) return false
        if (!isValidEcPublicKey(ikDhPubB64)) return false
        if (!isValidEcPublicKey(spkPubB64)) return false
        if (!opkPubB64.isNullOrBlank() && !isValidEcPublicKey(opkPubB64)) return false

        val ikDhOk = EcdhHelper.verifySignature(
            pubB64 = ikSignPubB64,
            dataBytes = buildIkDhSignedData(ikDhPubB64),
            sigB64 = ikDhSigB64
        )
        if (!ikDhOk) return false

        val spkOk = EcdhHelper.verifySignature(
            pubB64 = ikSignPubB64,
            dataBytes = buildSpkSignedData(userId, spkId, spkPubB64, spkCreatedAt),
            sigB64 = spkSigB64
        )
        return spkOk
    }

    // ==================== TOFU FAIL-CLOSED ====================

    fun evaluateOrPinIdentity(
        peerUserId: Int,
        ikSignPubB64: String,
        ikDhPubB64: String
    ): TofuEvaluation = evaluateOrPinIdentity(null, peerUserId, ikSignPubB64, ikDhPubB64)

    fun evaluateOrPinIdentity(
        context: Context?,
        peerUserId: Int,
        ikSignPubB64: String,
        ikDhPubB64: String
    ): TofuEvaluation {
        synchronized(lock) {
            ensureAndroidStores(context)
            val now = System.currentTimeMillis()
            val fpHex = EcdhHelper.computeIdentityFingerprint(peerUserId, ikSignPubB64, ikDhPubB64)
            val existing = identityStore.getByPeerId(peerUserId)

            if (existing == null) {
                val fresh = TrustedIdentityEntity(
                    peerUserId = peerUserId,
                    ikSignPubB64 = ikSignPubB64,
                    ikDhPubB64 = ikDhPubB64,
                    fingerprintHex = fpHex,
                    trustState = TrustedIdentityEntity.STATE_UNVERIFIED,
                    firstSeenAt = now,
                    verifiedAt = null
                )
                identityStore.upsert(fresh)
                return TofuEvaluation(
                    peerUserId = peerUserId,
                    trustState = fresh.trustState,
                    allowed = true,
                    isFirstContact = true,
                    keyChanged = false,
                    fingerprintHex = fpHex,
                    entity = fresh
                )
            }

            val keysMatch = existing.ikSignPubB64 == ikSignPubB64 && existing.ikDhPubB64 == ikDhPubB64
            if (keysMatch) {
                val allowed = existing.trustState != TrustedIdentityEntity.STATE_KEY_CHANGED
                return TofuEvaluation(
                    peerUserId = peerUserId,
                    trustState = existing.trustState,
                    allowed = allowed,
                    isFirstContact = false,
                    keyChanged = !allowed,
                    fingerprintHex = existing.fingerprintHex,
                    entity = existing
                )
            }

            // Chiave cambiata: Fail-Closed -> blocca invii automatici finché l'utente non conferma esplicitamente
            val changed = existing.copy(
                ikSignPubB64 = ikSignPubB64,
                ikDhPubB64 = ikDhPubB64,
                fingerprintHex = fpHex,
                trustState = TrustedIdentityEntity.STATE_KEY_CHANGED,
                verifiedAt = null
            )
            identityStore.upsert(changed)
            return TofuEvaluation(
                peerUserId = peerUserId,
                trustState = TrustedIdentityEntity.STATE_KEY_CHANGED,
                allowed = false,
                isFirstContact = false,
                keyChanged = true,
                fingerprintHex = fpHex,
                entity = changed
            )
        }
    }

    fun confirmVerifiedIdentity(
        peerUserId: Int,
        ikSignPubB64: String? = null,
        ikDhPubB64: String? = null
    ): TrustedIdentityEntity? = confirmVerifiedIdentity(null, peerUserId, ikSignPubB64, ikDhPubB64)

    fun confirmVerifiedIdentity(
        context: Context?,
        peerUserId: Int,
        ikSignPubB64: String? = null,
        ikDhPubB64: String? = null
    ): TrustedIdentityEntity? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val now = System.currentTimeMillis()
            val existing = identityStore.getByPeerId(peerUserId)
            val signKey = ikSignPubB64?.takeIf { it.isNotBlank() } ?: existing?.ikSignPubB64 ?: return null
            val dhKey = ikDhPubB64?.takeIf { it.isNotBlank() } ?: existing?.ikDhPubB64 ?: return null
            val fpHex = EcdhHelper.computeIdentityFingerprint(peerUserId, signKey, dhKey)

            val updated = TrustedIdentityEntity(
                peerUserId = peerUserId,
                ikSignPubB64 = signKey,
                ikDhPubB64 = dhKey,
                fingerprintHex = fpHex,
                trustState = TrustedIdentityEntity.STATE_VERIFIED,
                firstSeenAt = existing?.firstSeenAt ?: now,
                verifiedAt = now
            )
            identityStore.upsert(updated)
            return updated
        }
    }

    fun acceptKeyChange(
        peerUserId: Int,
        ikSignPubB64: String? = null,
        ikDhPubB64: String? = null
    ): TrustedIdentityEntity? = acceptKeyChange(null, peerUserId, ikSignPubB64, ikDhPubB64)

    fun acceptKeyChange(
        context: Context?,
        peerUserId: Int,
        ikSignPubB64: String? = null,
        ikDhPubB64: String? = null
    ): TrustedIdentityEntity? {
        synchronized(lock) {
            ensureAndroidStores(context)
            val now = System.currentTimeMillis()
            val existing = identityStore.getByPeerId(peerUserId)
            val signKey = ikSignPubB64?.takeIf { it.isNotBlank() } ?: existing?.ikSignPubB64 ?: return null
            val dhKey = ikDhPubB64?.takeIf { it.isNotBlank() } ?: existing?.ikDhPubB64 ?: return null
            val fpHex = EcdhHelper.computeIdentityFingerprint(peerUserId, signKey, dhKey)

            val updated = TrustedIdentityEntity(
                peerUserId = peerUserId,
                ikSignPubB64 = signKey,
                ikDhPubB64 = dhKey,
                fingerprintHex = fpHex,
                trustState = TrustedIdentityEntity.STATE_UNVERIFIED,
                firstSeenAt = existing?.firstSeenAt ?: now,
                verifiedAt = null
            )
            identityStore.upsert(updated)
            return updated
        }
    }

    fun getTrustedIdentity(context: Context? = null, peerUserId: Int): TrustedIdentityEntity? {
        synchronized(lock) {
            ensureAndroidStores(context)
            return identityStore.getByPeerId(peerUserId)
        }
    }

    fun isIdentityAllowedForSend(context: Context? = null, peerUserId: Int): Boolean {
        synchronized(lock) {
            ensureAndroidStores(context)
            val existing = identityStore.getByPeerId(peerUserId) ?: return true
            return existing.trustState != TrustedIdentityEntity.STATE_KEY_CHANGED
        }
    }

    fun clearUserKeys(context: Context? = null, userId: Int) {
        synchronized(lock) {
            ensureAndroidStores(context)
            val currentSpk = getCurrentSignedPreKey(userId, context)
            val prevSpk = getPreviousSignedPreKey(userId, context)
            saveToSpkHistory(userId, currentSpk, prevSpk)

            val existingOpkStore = loadOpkStore(userId)
            if (existingOpkStore.keys.isNotEmpty()) {
                archiveOpks(userId, existingOpkStore.keys.values)
            }

            val existingNextSpkId = keyStorage.getInt(spkNextIdPref(userId), 1)
            val bumpedSpkId = if (isTestBackend) {
                existingNextSpkId + 1
            } else {
                maxOf(existingNextSpkId + 1, defaultInitialSpkId())
            }
            keyStorage.putInt(spkNextIdPref(userId), bumpedSpkId)

            keyStorage.remove(identityKeyPref(userId))
            keyStorage.remove(spkCurrentPref(userId))
            keyStorage.remove(spkPreviousPref(userId))
            keyStorage.remove(opkStorePref(userId))
            if (keyStorage.getInt(KEY_ACTIVE_USER_ID, 0) == userId) {
                keyStorage.remove(KEY_ACTIVE_USER_ID)
            }
        }
    }

    fun clearAll(context: Context? = null) {
        synchronized(lock) {
            ensureAndroidStores(context)
            keyStorage.clearAll()
            identityStore.clearAll()
        }
    }
}
