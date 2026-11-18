package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

/**
 * Implementazione del protocollo X3DH (Extended Triple Diffie-Hellman) su curva secp256r1
 * per bootstrap asincrono Zero-Knowledge senza dipendenza da chiavi generate dal server.
 */
object X3dhHelper {

    private const val TAG = "X3dhHelper"
    private val X3DH_INFO = "CryptionChat-X3DH-v1".toByteArray(Charsets.UTF_8)
    private val gson = Gson()
    private val sameHandshakeOpkCache = java.util.concurrent.ConcurrentHashMap<String, IdentityKeyManager.OneTimePreKeyRecord>()

    data class X3dhInitHeader(
        @SerializedName("ver") val ver: Int = 2,
        @SerializedName("ik_sign_a") val ikSignA: String,
        @SerializedName("ik_dh_a") val ikDhA: String,
        @SerializedName("ik_dh_sig_a") val ikDhSigA: String,
        @SerializedName("ek_a") val ekA: String,
        @SerializedName("spk_id_b") val spkIdB: Int,
        @SerializedName("opk_id_b") val opkIdB: Int? = null,
        @SerializedName("ts") val ts: Long = System.currentTimeMillis()
    )

    fun init(context: Context) {
        val appCtx = context.applicationContext
        IdentityKeyManager.init(appCtx)
        DoubleRatchetHelper.init(appCtx)
    }

    internal fun setStorageForTests(
        keyStorage: IdentityKeyManager.KeyStorage = IdentityKeyManager.InMemoryKeyStorage(),
        identityStore: IdentityKeyManager.TrustedIdentityStore = IdentityKeyManager.InMemoryTrustedIdentityStore(),
        ratchetStorage: DoubleRatchetHelper.StorageBackend = DoubleRatchetHelper.InMemoryStorageBackend()
    ) {
        sameHandshakeOpkCache.clear()
        IdentityKeyManager.setStorageForTests(keyStorage, identityStore)
        DoubleRatchetHelper.setStorageForTests(ratchetStorage)
    }

    private fun logError(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        } catch (_: Throwable) {
        }
    }

    fun parseHeader(x3dhInitHeaderJson: String): X3dhInitHeader? {
        if (x3dhInitHeaderJson.isBlank()) return null
        return try {
            gson.fromJson(x3dhInitHeaderJson, X3dhInitHeader::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun computeAssociatedData(
        ikSignA: String,
        ikDhA: String,
        ikSignB: String,
        ikDhB: String
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("CC-X3DH-AD-v1|$ikSignA|$ikDhA|$ikSignB|$ikDhB".toByteArray(Charsets.UTF_8))
    }

    private fun deriveSharedSecret(vararg dhOutputs: ByteArray): ByteArray {
        val fPrefix = ByteArray(32) { 0xFF.toByte() }
        var totalLen = fPrefix.size
        for (dh in dhOutputs) {
            totalLen += dh.size
        }
        val ikm = ByteArray(totalLen)
        System.arraycopy(fPrefix, 0, ikm, 0, fPrefix.size)
        var offset = fPrefix.size
        for (dh in dhOutputs) {
            System.arraycopy(dh, 0, ikm, offset, dh.size)
            offset += dh.size
        }
        return try {
            EcdhHelper.hkdfSha256(
                ikm = ikm,
                salt = ByteArray(32),
                info = X3DH_INFO,
                outputLength = 32
            )
        } finally {
            ikm.fill(0)
        }
    }

    /**
     * Inizializza una sessione Double Ratchet lato iniziatore (Alice) a partire dal
     * PreKey Bundle remoto verificato di Bob.
     *
     * @return Pair(sessionKeyId, x3dhInitHeaderJson) oppure null se verifica firma/TOFU fallisce.
     */
    fun initiateSession(
        myUserId: Int,
        remoteBundle: IdentityKeyManager.RemotePreKeyBundle,
        sessionAliasKey: String = DoubleRatchetHelper.pairwiseSessionKey(remoteBundle.userId),
        context: Context? = null
    ): Pair<String, String>? {
        if (myUserId <= 0 || sessionAliasKey.isBlank()) return null

        if (!IdentityKeyManager.verifyRemoteBundle(remoteBundle)) {
            logError("Verifica firme PreKeyBundle remoto fallita per userId=${remoteBundle.userId}")
            return null
        }

        val tofu = IdentityKeyManager.evaluateOrPinIdentity(
            context = context,
            peerUserId = remoteBundle.userId,
            ikSignPubB64 = remoteBundle.ikSignPubB64,
            ikDhPubB64 = remoteBundle.ikDhPubB64
        )
        if (!tofu.allowed) {
            logError("TOFU fail-closed: Identity Key cambiata per peer=${remoteBundle.userId}")
            return null
        }

        val myIdentity = IdentityKeyManager.ensureLocalIdentity(context, myUserId)
        val ekA = EcdhHelper.generateKeyPair() ?: return null
        val ratchetPairA = EcdhHelper.generateKeyPair() ?: return null

        val dh1 = EcdhHelper.computeRawSharedSecret(myIdentity.ikDhPrivB64, remoteBundle.spkPubB64)
        val dh2 = EcdhHelper.computeRawSharedSecret(ekA.first, remoteBundle.ikDhPubB64)
        val dh3 = EcdhHelper.computeRawSharedSecret(ekA.first, remoteBundle.spkPubB64)

        val useOpk = remoteBundle.opkId != null &&
            remoteBundle.opkId >= IdentityKeyManager.MIN_VALID_OPK_ID &&
            !remoteBundle.opkPubB64.isNullOrBlank()
        val dh4 = if (useOpk) {
            EcdhHelper.computeRawSharedSecret(ekA.first, remoteBundle.opkPubB64!!)
        } else {
            null
        }

        if (dh1 == null || dh2 == null || dh3 == null || (useOpk && dh4 == null)) {
            dh1?.fill(0)
            dh2?.fill(0)
            dh3?.fill(0)
            dh4?.fill(0)
            return null
        }

        val sk = try {
            if (dh4 != null) deriveSharedSecret(dh1, dh2, dh3, dh4) else deriveSharedSecret(dh1, dh2, dh3)
        } finally {
            dh1.fill(0)
            dh2.fill(0)
            dh3.fill(0)
            dh4?.fill(0)
        }

        val ad = computeAssociatedData(
            ikSignA = myIdentity.ikSignPubB64,
            ikDhA = myIdentity.ikDhPubB64,
            ikSignB = remoteBundle.ikSignPubB64,
            ikDhB = remoteBundle.ikDhPubB64
        )

        val nowTs = System.currentTimeMillis()
        val initHeader = X3dhInitHeader(
            ver = 2,
            ikSignA = myIdentity.ikSignPubB64,
            ikDhA = myIdentity.ikDhPubB64,
            ikDhSigA = myIdentity.ikDhSigB64,
            ekA = ekA.second,
            spkIdB = remoteBundle.spkId,
            opkIdB = if (useOpk) remoteBundle.opkId else null,
            ts = nowTs
        )
        val x3dhInitHeaderJson = gson.toJson(initHeader)

        return try {
            val ok = DoubleRatchetHelper.initSessionFromX3dh(
                sessionAliasKey = sessionAliasKey,
                rootKey = sk,
                adBytes = ad,
                myDhPriv = ratchetPairA.first,
                myDhPub = ratchetPairA.second,
                theirRatchetPub = remoteBundle.spkPubB64,
                isInitiator = true,
                x3dhInitHeaderJson = x3dhInitHeaderJson,
                initTimestamp = nowTs
            )
            if (ok) Pair(sessionAliasKey, x3dhInitHeaderJson) else null
        } finally {
            sk.fill(0)
            ad.fill(0)
        }
    }

    /**
     * Accetta una sessione X3DH lato responder (Bob) a partire dall'header [x3dhInitHeaderJson]
     * ricevuto nel primo pacchetto `DR1:`. Consuma ed elimina immediatamente la OPK privata se usata.
     *
     * @return sessionKeyId inizializzato oppure null se verifica firma/TOFU/chiavi fallisce.
     */
    fun acceptSession(
        myUserId: Int,
        peerUserId: Int,
        x3dhInitHeaderJson: String,
        sessionAliasKey: String = DoubleRatchetHelper.pairwiseSessionKey(peerUserId),
        context: Context? = null
    ): String? {
        val header = parseHeader(x3dhInitHeaderJson) ?: return null
        return acceptSession(myUserId, peerUserId, header, sessionAliasKey, context)
    }

    fun acceptSession(
        myUserId: Int,
        peerUserId: Int,
        header: X3dhInitHeader,
        sessionAliasKey: String = DoubleRatchetHelper.pairwiseSessionKey(peerUserId),
        context: Context? = null
    ): String? {
        if (myUserId <= 0 || peerUserId <= 0 || sessionAliasKey.isBlank()) return null
        if (header.ver != 2) return null
        if (header.ikSignA.isBlank() || header.ikDhA.isBlank() || header.ikDhSigA.isBlank() || header.ekA.isBlank()) {
            return null
        }

        val sigOk = EcdhHelper.verifySignature(
            pubB64 = header.ikSignA,
            dataBytes = IdentityKeyManager.buildIkDhSignedData(header.ikDhA),
            sigB64 = header.ikDhSigA
        )
        if (!sigOk) {
            logError("Firma ik_dh_sig_a non valida per peer=$peerUserId")
            return null
        }

        val tofu = IdentityKeyManager.evaluateOrPinIdentity(
            context = context,
            peerUserId = peerUserId,
            ikSignPubB64 = header.ikSignA,
            ikDhPubB64 = header.ikDhA
        )
        if (!tofu.allowed) {
            logError("TOFU fail-closed: Identity Key cambiata per peer=$peerUserId")
            return null
        }

        val myIdentity = IdentityKeyManager.ensureLocalIdentity(context, myUserId)
        val spkB = IdentityKeyManager.getSignedPreKey(header.spkIdB, myUserId, context) ?: run {
            logError("SignedPreKey ${header.spkIdB} non trovata per userId=$myUserId")
            return null
        }

        val opkB = if (header.opkIdB != null) {
            val cacheKey = "$myUserId|$peerUserId|${header.ekA}|${header.opkIdB}"
            val consumed = IdentityKeyManager.consumeOneTimePreKey(header.opkIdB, myUserId, context)
            if (consumed != null) {
                if (sameHandshakeOpkCache.size > 64) sameHandshakeOpkCache.clear()
                sameHandshakeOpkCache[cacheKey] = consumed
                consumed
            } else {
                sameHandshakeOpkCache[cacheKey] ?: run {
                    logError("OneTimePreKey ${header.opkIdB} non trovata o già consumata per userId=$myUserId")
                    return null
                }
            }
        } else {
            null
        }

        val dh1 = EcdhHelper.computeRawSharedSecret(spkB.privB64, header.ikDhA)
        val dh2 = EcdhHelper.computeRawSharedSecret(myIdentity.ikDhPrivB64, header.ekA)
        val dh3 = EcdhHelper.computeRawSharedSecret(spkB.privB64, header.ekA)
        val dh4 = opkB?.let { EcdhHelper.computeRawSharedSecret(it.privB64, header.ekA) }

        if (dh1 == null || dh2 == null || dh3 == null || (opkB != null && dh4 == null)) {
            dh1?.fill(0)
            dh2?.fill(0)
            dh3?.fill(0)
            dh4?.fill(0)
            return null
        }

        val sk = try {
            if (dh4 != null) deriveSharedSecret(dh1, dh2, dh3, dh4) else deriveSharedSecret(dh1, dh2, dh3)
        } finally {
            dh1.fill(0)
            dh2.fill(0)
            dh3.fill(0)
            dh4?.fill(0)
        }

        val ad = computeAssociatedData(
            ikSignA = header.ikSignA,
            ikDhA = header.ikDhA,
            ikSignB = myIdentity.ikSignPubB64,
            ikDhB = myIdentity.ikDhPubB64
        )

        val initTs = if (header.ts > 0L) header.ts else System.currentTimeMillis()
        return try {
            val ok = DoubleRatchetHelper.initSessionFromX3dh(
                sessionAliasKey = sessionAliasKey,
                rootKey = sk,
                adBytes = ad,
                myDhPriv = spkB.privB64,
                myDhPub = spkB.pubB64,
                theirRatchetPub = header.ekA,
                isInitiator = false,
                x3dhInitHeaderJson = null,
                initTimestamp = initTs
            )
            if (ok) sessionAliasKey else null
        } finally {
            sk.fill(0)
            ad.fill(0)
        }
    }
}
