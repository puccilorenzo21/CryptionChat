package com.lollo1344.cryptionchat

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestratore delle sessioni E2EE v2 (X3DH + Double Ratchet + Sender Keys + TOFU fail-closed).
 */
object E2eeSessionOrchestrator {

    private const val TAG = "E2eeOrchestrator"
    const val ACTION_IDENTITY_KEY_CHANGED = "com.lollo1344.cryptionchat.IDENTITY_KEY_CHANGED"
    const val EXTRA_PEER_USER_ID = "extra_peer_user_id"
    private const val DIST_TYPE_SENDER_KEY_REQUEST = "sender_key_request"
    private const val DIST_TYPE_PAIRWISE_REKEY_REQUEST = "pairwise_rekey_request"
    private const val DIST_TYPE_IDENTITY_CHANGED = "identity_changed"
    private const val RECOVERY_COOLDOWN_MS = 15_000L
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val syncingBundles = ConcurrentHashMap.newKeySet<Int>()
    private val failedX3dhPayloads = ConcurrentHashMap.newKeySet<String>()
    private val recoveryCooldowns = ConcurrentHashMap<String, Long>()
    private val lastPeerIdentityPollMs = ConcurrentHashMap<Int, Long>()
    @Volatile
    private var identityKeysEndpointSupported = true
    @Volatile
    private var lastAutoRotateMs: Long = 0L
    private val distSyncLock = Any()
    private val pairwiseLock = Any()
    private val groupDistLock = Any()

    data class KeyRecoveryRequestPayload(
        val type: String,
        val groupId: Int,
        val requesterId: Int,
        val failedChainId: Int = 0,
        val resetPairwise: Boolean = true,
        val timestamp: Long = System.currentTimeMillis(),
        val sigB64: String = ""
    )

    data class IdentityChangedNotice(
        val type: String = DIST_TYPE_IDENTITY_CHANGED,
        val userId: Int,
        val ikSignPubB64: String,
        val ikDhPubB64: String,
        val ikDhSigB64: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PairwiseResult(
        val ready: Boolean,
        val keyChanged: Boolean = false,
        val fallbackNoBundle: Boolean = false
    )

    data class GroupKeyResult(
        val ready: Boolean,
        val blockedPeers: List<Int> = emptyList()
    )

    private fun prefUploadedBundle(userId: Int) = "e2ee_bundle_uploaded_v3_$userId"
    private fun prefUploadedSpkId(userId: Int) = "e2ee_uploaded_spk_id_$userId"
    private fun prefUploadedIkSign(userId: Int) = "e2ee_uploaded_ik_sign_$userId"
    private fun prefGroupMembers(groupId: Int) = "sk_members_$groupId"
    private fun prefGroupEpoch(groupId: Int) = "sk_epoch_$groupId"
    private fun prefGroupPeerDist(groupId: Int, peerUserId: Int) = "sk_dist_${groupId}_$peerUserId"
    private fun prefLastHealedMsgId(groupId: Int, peerUserId: Int) = "e2ee_last_healed_${groupId}_$peerUserId"
    private fun inboundAltSessionKey(peerUserId: Int) = "pw_rx_$peerUserId"

    fun markMessagesHealedUpTo(context: Context, groupId: Int, peerUserId: Int, maxMessageId: Int) {
        if (groupId <= 0 || peerUserId <= 0 || maxMessageId <= 0) return
        val prefs = SecurePrefs.get(context.applicationContext)
        val key = prefLastHealedMsgId(groupId, peerUserId)
        val prev = prefs.getInt(key, 0)
        if (maxMessageId > prev) {
            prefs.edit().putInt(key, maxMessageId).commit()
        }
    }

    fun syncPreKeyBundleAsync(context: Context, userId: Int) {
        if (userId <= 0) return
        val appCtx = context.applicationContext
        if (!syncingBundles.add(userId)) return

        scope.launch {
            try {
                IdentityKeyManager.init(appCtx)
                X3dhHelper.init(appCtx)
                SenderKeyHelper.init(appCtx)

                val bundle = IdentityKeyManager.ensureLocalBundle(appCtx, userId)
                val prefs = SecurePrefs.get(appCtx)
                val bundleAlreadyUploaded = prefs.getBoolean(prefUploadedBundle(userId), false)
                val uploadedSpkId = prefs.getInt(prefUploadedSpkId(userId), -1)
                val uploadedIkSign = prefs.getString(prefUploadedIkSign(userId), null)

                val needsFullUpload = !bundleAlreadyUploaded ||
                    uploadedSpkId != bundle.spkId ||
                    uploadedIkSign != bundle.ikSignPubB64

                if (needsFullUpload) {
                    uploadBundleSync(appCtx, userId, bundle)
                    return@launch
                }

                val countResp = ApiClient.instance.getPrekeyCount(userId = userId).execute()
                if (countResp.isSuccessful && countResp.body()?.status == "success") {
                    val serverCount = countResp.body()?.data?.count ?: 0
                    if (serverCount < IdentityKeyManager.LOW_OPK_THRESHOLD) {
                        val freshOpks = IdentityKeyManager.replenishOneTimePreKeysIfNeeded(
                            userId = userId,
                            serverCount = serverCount,
                            targetCount = IdentityKeyManager.DEFAULT_OPK_BATCH_SIZE,
                            minThreshold = IdentityKeyManager.LOW_OPK_THRESHOLD,
                            context = appCtx
                        )
                        if (freshOpks.isNotEmpty()) {
                            val opksJson = IdentityKeyManager.toOpksUploadJson(freshOpks)
                            ApiClient.instance.uploadPrekeys(
                                userId = userId,
                                opksJson = opksJson
                            ).execute()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sincronizzazione PreKeyBundle non completata: ${e.message}")
            } finally {
                syncingBundles.remove(userId)
            }
        }
    }

    fun syncDistributions(context: Context, userId: Int, groupId: Int = 0): Boolean {
        if (userId <= 0) return false
        val appCtx = context.applicationContext

        synchronized(distSyncLock) {
            return try {
                val resp = ApiClient.instance.fetchKeyDistributions(
                    userId = userId,
                    groupId = groupId
                ).execute()
                if (!resp.isSuccessful || resp.body()?.status != "success") {
                    return false
                }

                val items = resp.body()?.data.orEmpty()
                if (items.isEmpty()) return true

                val ackedIds = ArrayList<Long>(items.size)
                var allSucceeded = true

                for (item in items) {
                    when {
                        item.distType == DIST_TYPE_IDENTITY_CHANGED -> {
                            ackedIds.add(item.id)
                            handleIdentityChangedNotice(appCtx, item.senderId, item.payload)
                        }
                        item.distType == DIST_TYPE_SENDER_KEY_REQUEST -> {
                            val req = verifyRecoveryRequest(appCtx, item.senderId, item.payload, item.groupId, userId)
                            ackedIds.add(item.id)
                            if (req != null && req.groupId > 0) {
                                fulfillSenderKeyRequestSync(appCtx, userId, req, item.epoch)
                            }
                        }
                        item.distType == DIST_TYPE_PAIRWISE_REKEY_REQUEST -> {
                            val req = verifyRecoveryRequest(appCtx, item.senderId, item.payload, item.groupId, userId)
                            ackedIds.add(item.id)
                            if (req != null && req.requesterId == item.senderId) {
                                renegotiatePairwiseSessionSync(appCtx, userId, item.senderId, req.groupId)
                            }
                        }
                        item.distType == "sender_key" || item.distType == "pairwise_rekey" || item.payload.startsWith(DoubleRatchetHelper.DR_PREFIX) -> {
                            val decrypted = decryptPairwiseMessage(
                                context = appCtx,
                                myUserId = userId,
                                peerUserId = item.senderId,
                                drPayload = item.payload
                            )
                            if (decrypted != null) {
                                if (item.distType == "pairwise_rekey" || decrypted.contains("\"pairwise_rekey\"")) {
                                    ackedIds.add(item.id)
                                } else if (SenderKeyHelper.importDistributionJson(decrypted)) {
                                    ackedIds.add(item.id)
                                } else {
                                    ackedIds.add(item.id)
                                    Log.w(TAG, "Distribuzione key id=${item.id} da sender=${item.senderId} scartata (formato non valido)")
                                }
                            } else {
                                val senderBlockedByTofu = !IdentityKeyManager.isIdentityAllowedForSend(appCtx, item.senderId)
                                if (senderBlockedByTofu) {
                                    allSucceeded = false
                                    Log.w(TAG, "Distribuzione key id=${item.id} da sender=${item.senderId} in attesa di conferma TOFU")
                                } else if (item.distType == "pairwise_rekey") {
                                    ackedIds.add(item.id)
                                } else {
                                    ackedIds.add(item.id)
                                    if (item.distType == "sender_key" && item.groupId > 0) {
                                        maybeRotateLocalPreKeysQuietly(appCtx, userId)
                                        sendRecoveryRequestSync(
                                            appCtx = appCtx,
                                            myUserId = userId,
                                            peerUserId = item.senderId,
                                            groupId = item.groupId,
                                            type = DIST_TYPE_SENDER_KEY_REQUEST,
                                            resetPairwise = true
                                        )
                                    }
                                    Log.w(TAG, "Distribuzione key id=${item.id} da sender=${item.senderId} non decifrata; rimossa dalla coda")
                                }
                            }
                        }
                    }
                }

                if (ackedIds.isNotEmpty()) {
                    val ackResp = ApiClient.instance.ackKeyDistribution(
                        userId = userId,
                        distributionIdsJson = gson.toJson(ackedIds)
                    ).execute()
                    if (!ackResp.isSuccessful || ackResp.body()?.status != "success") {
                        allSucceeded = false
                    }
                }

                allSucceeded
            } catch (e: Exception) {
                Log.w(TAG, "Errore syncDistributions: ${e.message}")
                false
            }
        }
    }

    fun syncDistributionsAsync(
        context: Context,
        userId: Int,
        groupId: Int = 0,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (userId <= 0) {
            onComplete?.invoke(false)
            return
        }
        val appCtx = context.applicationContext
        scope.launch {
            val ok = syncDistributions(appCtx, userId, groupId)
            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        }
    }

    fun ensurePairwiseSessionSync(
        context: Context,
        myUserId: Int,
        peerUserId: Int
    ): PairwiseResult {
        if (myUserId <= 0 || peerUserId <= 0 || myUserId == peerUserId) {
            return PairwiseResult(ready = false)
        }
        val appCtx = context.applicationContext

        synchronized(pairwiseLock) {
            val existingTrust = IdentityKeyManager.getTrustedIdentity(appCtx, peerUserId)
            if (existingTrust?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
                return PairwiseResult(ready = false, keyChanged = true)
            }

            if (DoubleRatchetHelper.hasPairwiseSession(peerUserId) &&
                IdentityKeyManager.isIdentityAllowedForSend(appCtx, peerUserId)
            ) {
                return PairwiseResult(ready = true)
            }

            return try {
                val bundleResp = ApiClient.instance.getPrekeyBundle(
                    userId = myUserId,
                    targetUserId = peerUserId
                ).execute()

                if (!bundleResp.isSuccessful || bundleResp.body()?.status != "success" || bundleResp.body()?.data == null) {
                    return PairwiseResult(ready = false, fallbackNoBundle = true)
                }

                val data = bundleResp.body()!!.data!!
                val remoteBundle = IdentityKeyManager.RemotePreKeyBundle(
                    userId = data.userId,
                    ikSignPubB64 = data.identitySignKey,
                    ikDhPubB64 = data.identityDhKey,
                    ikDhSigB64 = data.identityDhSig,
                    spkId = data.spkId,
                    spkPubB64 = data.spkPub,
                    spkSigB64 = data.spkSig,
                    spkCreatedAt = data.spkCreatedAt,
                    opkId = data.opkId,
                    opkPubB64 = data.opkPub
                )

                if (!IdentityKeyManager.verifyRemoteBundle(remoteBundle)) {
                    Log.e(TAG, "Verifica crittografica PreKeyBundle fallita per peer=$peerUserId")
                    return PairwiseResult(ready = false)
                }

                val tofu = IdentityKeyManager.evaluateOrPinIdentity(
                    context = appCtx,
                    peerUserId = peerUserId,
                    ikSignPubB64 = remoteBundle.ikSignPubB64,
                    ikDhPubB64 = remoteBundle.ikDhPubB64
                )
                if (!tofu.allowed || tofu.keyChanged) {
                    return PairwiseResult(ready = false, keyChanged = true)
                }

                val initiated = X3dhHelper.initiateSession(
                    myUserId = myUserId,
                    remoteBundle = remoteBundle,
                    sessionAliasKey = DoubleRatchetHelper.pairwiseSessionKey(peerUserId),
                    context = appCtx
                )
                PairwiseResult(ready = (initiated != null))
            } catch (e: Exception) {
                Log.w(TAG, "Errore ensurePairwiseSessionSync per peer=$peerUserId: ${e.message}")
                PairwiseResult(ready = false)
            }
        }
    }

    fun ensureGroupSenderKeyDistributedSync(
        context: Context,
        myUserId: Int,
        groupId: Int,
        members: List<GroupMember>,
        membershipEpoch: Int,
        forceResetPairwise: Boolean = false
    ): GroupKeyResult {
        if (myUserId <= 0 || groupId <= 0) {
            return GroupKeyResult(ready = false)
        }
        val appCtx = context.applicationContext
        val prefs = SecurePrefs.get(appCtx)

        synchronized(groupDistLock) {
            val currentMemberIds = members.map { it.userId }.filter { it > 0 }.toSortedSet()
            val prevMembersStr = prefs.getString(prefGroupMembers(groupId), null)
            val prevMemberIds = prevMembersStr
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                ?: emptySet()
            val prevEpoch = prefs.getInt(prefGroupEpoch(groupId), -1)

            val hasMembersSnapshot = currentMemberIds.isNotEmpty()
            val membersRemoved = hasMembersSnapshot && prevMemberIds.isNotEmpty() && (prevMemberIds - currentMemberIds).isNotEmpty()
            val epochIncreasedWithLoss = hasMembersSnapshot &&
                prevEpoch != -1 &&
                membershipEpoch > prevEpoch &&
                (membersRemoved || currentMemberIds.size < prevMemberIds.size)

            val myState = if (membersRemoved || epochIncreasedWithLoss) {
                clearGroupDistributionCache(appCtx, groupId)
                SenderKeyHelper.rotateMySenderKey(groupId, myUserId)
            } else {
                SenderKeyHelper.getOrCreateMySenderKey(groupId, myUserId)
            }

            if (hasMembersSnapshot) {
                prefs.edit()
                    .putString(prefGroupMembers(groupId), currentMemberIds.joinToString(","))
                    .putInt(prefGroupEpoch(groupId), membershipEpoch)
                    .commit()
            }

            val blockedPeers = mutableListOf<Int>()
            val expectedToken = "${myState.chainId}:$membershipEpoch"

            for (member in members) {
                if (member.userId <= 0 || member.userId == myUserId) continue

                val existingTrust = IdentityKeyManager.getTrustedIdentity(appCtx, member.userId)
                if (existingTrust?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
                    blockedPeers.add(member.userId)
                    continue
                }

                val sentToken = prefs.getString(prefGroupPeerDist(groupId, member.userId), null)
                if (!forceResetPairwise && sentToken == expectedToken) continue

                val pwRes = if (forceResetPairwise) {
                    resetAndEnsurePairwiseSessionSync(appCtx, myUserId, member.userId)
                } else {
                    ensurePairwiseSessionSync(appCtx, myUserId, member.userId)
                }
                if (pwRes.keyChanged) {
                    blockedPeers.add(member.userId)
                    continue
                }
                if (!pwRes.ready) continue

                val distJson = SenderKeyHelper.exportDistributionJson(groupId, myUserId)
                val encryptedDist = DoubleRatchetHelper.encryptMessage(
                    DoubleRatchetHelper.pairwiseSessionKey(member.userId),
                    distJson
                ) ?: continue

                try {
                    val sendResp = ApiClient.instance.sendKeyDistribution(
                        userId = myUserId,
                        groupId = groupId,
                        recipientId = member.userId,
                        epoch = membershipEpoch,
                        distType = "sender_key",
                        payload = encryptedDist
                    ).execute()
                    if (sendResp.isSuccessful && sendResp.body()?.status == "success") {
                        prefs.edit()
                            .putString(prefGroupPeerDist(groupId, member.userId), expectedToken)
                            .commit()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Errore invio SenderKey dist a peer=${member.userId}: ${e.message}")
                }
            }

            return GroupKeyResult(
                ready = true,
                blockedPeers = blockedPeers.distinct()
            )
        }
    }

    fun deriveZeroKnowledgeCallKeysSync(
        context: Context,
        myUserId: Int,
        peerUserId: Int
    ): Pair<String, String>? {
        if (myUserId <= 0 || peerUserId <= 0 || myUserId == peerUserId) return null
        val appCtx = context.applicationContext

        val myIdentity = IdentityKeyManager.ensureLocalIdentity(appCtx, myUserId)
        val peerIdentity = IdentityKeyManager.getTrustedIdentity(appCtx, peerUserId)
            ?: fetchAndPinPeerIdentitySync(appCtx, myUserId, peerUserId)
            ?: return null

        if (peerIdentity.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
            Log.w(TAG, "Chiave chiamata bloccata (TOFU STATE_KEY_CHANGED) per peer=$peerUserId")
            return null
        }

        return EcdhHelper.computeSharedAesKey(myIdentity.ikDhPrivB64, peerIdentity.ikDhPubB64)
    }

    /**
     * Decifra un payload `DR1:` pairwise gestendo sia il canale primario (`pw_$peerUserId`)
     * sia eventuali header X3DH iniziali (incluso il caso di inizializzazione simultanea).
     */
    fun decryptPairwiseMessage(
        context: Context? = null,
        myUserId: Int,
        peerUserId: Int,
        drPayload: String
    ): String? {
        if (!drPayload.startsWith(DoubleRatchetHelper.DR_PREFIX)) return null
        if (myUserId <= 0 || peerUserId <= 0) return null

        // Fast-path: se il payload è già decifrato nel Vault locale, non toccare le sessioni né le OPK
        DoubleRatchetHelper.getVaultPlaintextForPayload(drPayload)?.let { return it }

        val appCtx = context?.applicationContext
        val primaryKey = DoubleRatchetHelper.pairwiseSessionKey(peerUserId)
        val altKey = inboundAltSessionKey(peerUserId)
        val payloadHash = DoubleRatchetHelper.sha256Hex(drPayload)

        synchronized(pairwiseLock) {
            DoubleRatchetHelper.getVaultPlaintextForPayload(drPayload)?.let { return it }

            DoubleRatchetHelper.decryptMessage(primaryKey, drPayload)?.let { return it }

            if (DoubleRatchetHelper.hasActiveSession(altKey)) {
                DoubleRatchetHelper.decryptMessage(altKey, drPayload)?.let { return it }
            }

            if (failedX3dhPayloads.contains(payloadHash)) {
                return null
            }

            val x3dhHeader = DoubleRatchetHelper.extractX3dhInitHeader(drPayload)
            if (x3dhHeader != null) {
                val parsedHeader = X3dhHelper.parseHeader(x3dhHeader)
                val currentInitTs = DoubleRatchetHelper.getSessionInitTimestamp(primaryKey)
                val incomingInitTs = parsedHeader?.ts ?: 0L
                val isOlderHistoricalHandshake = DoubleRatchetHelper.hasActiveSession(primaryKey) &&
                    currentInitTs > 0L && incomingInitTs > 0L && incomingInitTs < currentInitTs

                if (isOlderHistoricalHandshake) {
                    val histKey = "pw_hist_${peerUserId}_${payloadHash.take(12)}"
                    val acceptedHist = X3dhHelper.acceptSession(
                        myUserId = myUserId,
                        peerUserId = peerUserId,
                        x3dhInitHeaderJson = x3dhHeader,
                        sessionAliasKey = histKey,
                        context = appCtx
                    )
                    if (acceptedHist != null) {
                        val decrypted = DoubleRatchetHelper.decryptMessage(histKey, drPayload)
                        DoubleRatchetHelper.resetSessionKeepVault(histKey)
                        if (decrypted != null) {
                            return decrypted
                        }
                    }
                    if (IdentityKeyManager.isIdentityAllowedForSend(appCtx, peerUserId)) {
                        if (failedX3dhPayloads.size > 512) failedX3dhPayloads.clear()
                        failedX3dhPayloads.add(payloadHash)
                    }
                    return null
                }

                val prevPrimaryJson = DoubleRatchetHelper.exportSessionJson(primaryKey)
                val prevAltJson = DoubleRatchetHelper.exportSessionJson(altKey)
                if (prevPrimaryJson != null) {
                    DoubleRatchetHelper.importSessionJson(altKey, prevPrimaryJson)
                }
                DoubleRatchetHelper.resetSessionKeepVault(primaryKey)

                val acceptedAlias = X3dhHelper.acceptSession(
                    myUserId = myUserId,
                    peerUserId = peerUserId,
                    x3dhInitHeaderJson = x3dhHeader,
                    sessionAliasKey = primaryKey,
                    context = appCtx
                )
                if (acceptedAlias != null) {
                    val decrypted = DoubleRatchetHelper.decryptMessage(primaryKey, drPayload)
                    if (decrypted != null) {
                        return decrypted
                    }
                }

                // Ripristina le sessioni precedenti se l'handshake era un vecchio messaggio storico non più valido
                DoubleRatchetHelper.importSessionJson(primaryKey, prevPrimaryJson)
                DoubleRatchetHelper.importSessionJson(altKey, prevAltJson)
                if (IdentityKeyManager.isIdentityAllowedForSend(appCtx, peerUserId)) {
                    if (failedX3dhPayloads.size > 512) failedX3dhPayloads.clear()
                    failedX3dhPayloads.add(payloadHash)
                }
            }

            return null
        }
    }

    fun fetchAndPinPeerIdentitySync(
        context: Context,
        myUserId: Int,
        peerUserId: Int
    ): TrustedIdentityEntity? {
        if (myUserId <= 0 || peerUserId <= 0) return null
        val appCtx = context.applicationContext
        return try {
            if (identityKeysEndpointSupported) {
                try {
                    val idResp = ApiClient.instance.getIdentityKeys(
                        userId = myUserId,
                        targetUserIdsJson = gson.toJson(listOf(peerUserId))
                    ).execute()
                    val idBody = idResp.body()
                    if (idResp.isSuccessful && idBody?.status == "success") {
                        val item = idBody.data?.firstOrNull { it.userId == peerUserId }
                        if (item != null &&
                            item.identitySignKey.isNotBlank() &&
                            item.identityDhKey.isNotBlank() &&
                            item.identityDhSig.isNotBlank()
                        ) {
                            val sigOk = EcdhHelper.verifySignature(
                                pubB64 = item.identitySignKey,
                                dataBytes = IdentityKeyManager.buildIkDhSignedData(item.identityDhKey),
                                sigB64 = item.identityDhSig
                            )
                            if (sigOk) {
                                val tofu = IdentityKeyManager.evaluateOrPinIdentity(
                                    context = appCtx,
                                    peerUserId = peerUserId,
                                    ikSignPubB64 = item.identitySignKey,
                                    ikDhPubB64 = item.identityDhKey
                                )
                                return tofu.entity
                            }
                        }
                    } else if (idBody?.message?.contains("non valida", ignoreCase = true) == true) {
                        identityKeysEndpointSupported = false
                    }
                } catch (_: Exception) {}
            }

            val resp = ApiClient.instance.getPrekeyBundle(
                userId = myUserId,
                targetUserId = peerUserId
            ).execute()
            val data = if (resp.isSuccessful && resp.body()?.status == "success") resp.body()?.data else null
            if (data == null) return null

            val remoteBundle = IdentityKeyManager.RemotePreKeyBundle(
                userId = data.userId,
                ikSignPubB64 = data.identitySignKey,
                ikDhPubB64 = data.identityDhKey,
                ikDhSigB64 = data.identityDhSig,
                spkId = data.spkId,
                spkPubB64 = data.spkPub,
                spkSigB64 = data.spkSig,
                spkCreatedAt = data.spkCreatedAt,
                opkId = data.opkId,
                opkPubB64 = data.opkPub
            )
            if (!IdentityKeyManager.verifyRemoteBundle(remoteBundle)) return null

            val tofu = IdentityKeyManager.evaluateOrPinIdentity(
                context = appCtx,
                peerUserId = peerUserId,
                ikSignPubB64 = remoteBundle.ikSignPubB64,
                ikDhPubB64 = remoteBundle.ikDhPubB64
            )
            tofu.entity
        } catch (e: Exception) {
            Log.w(TAG, "Errore fetchAndPinPeerIdentitySync per peer=$peerUserId: ${e.message}")
            null
        }
    }

    private fun uploadBundleSync(
        appCtx: Context,
        userId: Int,
        bundle: IdentityKeyManager.LocalKeyBundleUpload
    ): Boolean {
        return try {
            val uploadResp = ApiClient.instance.uploadKeyBundle(
                userId = userId,
                identitySignKey = bundle.ikSignPubB64,
                identityDhKey = bundle.ikDhPubB64,
                identityDhSig = bundle.ikDhSigB64,
                spkId = bundle.spkId,
                spkPub = bundle.spkPubB64,
                spkSig = bundle.spkSigB64,
                spkCreatedAt = bundle.spkCreatedAt,
                opksJson = bundle.opksJson
            ).execute()

            val ok = uploadResp.isSuccessful && uploadResp.body()?.status == "success"
            if (ok) {
                SecurePrefs.get(appCtx).edit()
                    .putBoolean(prefUploadedBundle(userId), true)
                    .putInt(prefUploadedSpkId(userId), bundle.spkId)
                    .putString(prefUploadedIkSign(userId), bundle.ikSignPubB64)
                    .commit()
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Errore uploadKeyBundle: ${e.message}")
            false
        }
    }

    fun ensureLocalBundleUploadedSync(
        context: Context,
        userId: Int,
        forceRotate: Boolean = false
    ): Boolean {
        if (userId <= 0) return false
        val appCtx = context.applicationContext
        IdentityKeyManager.init(appCtx)
        X3dhHelper.init(appCtx)

        val bundle = IdentityKeyManager.ensureLocalBundle(appCtx, userId, forceRotate = forceRotate)
        val prefs = SecurePrefs.get(appCtx)
        val bundleAlreadyUploaded = prefs.getBoolean(prefUploadedBundle(userId), false)
        val uploadedSpkId = prefs.getInt(prefUploadedSpkId(userId), -1)
        val uploadedIkSign = prefs.getString(prefUploadedIkSign(userId), null)

        val needsUpload = forceRotate ||
            !bundleAlreadyUploaded ||
            uploadedSpkId != bundle.spkId ||
            uploadedIkSign != bundle.ikSignPubB64

        return if (needsUpload) {
            uploadBundleSync(appCtx, userId, bundle)
        } else {
            true
        }
    }

    /**
     * Ruota manualmente il Signed PreKey (+ pool OPK), opzionalmente rigenerando anche le Identity Key.
     */
    fun rotateLocalPreKeysSync(
        context: Context,
        userId: Int,
        resetIdentity: Boolean = false
    ): Boolean {
        if (userId <= 0) return false
        val appCtx = context.applicationContext
        IdentityKeyManager.init(appCtx)
        X3dhHelper.init(appCtx)
        failedX3dhPayloads.clear()

        if (resetIdentity) {
            DoubleRatchetHelper.clearAllSessionsKeepVault()
            IdentityKeyManager.clearUserKeys(appCtx, userId)
            SecurePrefs.get(appCtx).edit()
                .remove(prefUploadedBundle(userId))
                .remove(prefUploadedSpkId(userId))
                .remove(prefUploadedIkSign(userId))
                .commit()
        }

        val bundle = IdentityKeyManager.ensureLocalBundle(appCtx, userId, forceRotate = true)
        val ok = uploadBundleSync(appCtx, userId, bundle)
        if (ok && resetIdentity) {
            broadcastIdentityChangeAsync(appCtx, userId, bundle)
        }
        return ok
    }

    private fun handleIdentityChangedNotice(appCtx: Context, senderId: Int, payloadJson: String) {
        if (senderId <= 0 || payloadJson.isBlank()) return
        val notice = try {
            gson.fromJson(payloadJson, IdentityChangedNotice::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        if (notice.userId != senderId || notice.ikSignPubB64.isBlank() || notice.ikDhPubB64.isBlank() || notice.ikDhSigB64.isBlank()) {
            return
        }
        val sigOk = EcdhHelper.verifySignature(
            pubB64 = notice.ikSignPubB64,
            dataBytes = IdentityKeyManager.buildIkDhSignedData(notice.ikDhPubB64),
            sigB64 = notice.ikDhSigB64
        )
        if (!sigOk) return

        val tofu = IdentityKeyManager.evaluateOrPinIdentity(
            context = appCtx,
            peerUserId = senderId,
            ikSignPubB64 = notice.ikSignPubB64,
            ikDhPubB64 = notice.ikDhPubB64
        )
        lastPeerIdentityPollMs[senderId] = System.currentTimeMillis()
        if (tofu.keyChanged) {
            synchronized(pairwiseLock) {
                DoubleRatchetHelper.resetSessionKeepVault(DoubleRatchetHelper.pairwiseSessionKey(senderId))
            }
            notifyIdentityKeyChanged(appCtx, senderId)
            Log.w(TAG, "Ricevuto avviso cambio identità TOFU fail-closed per peer=$senderId")
        }
    }

    private fun notifyIdentityKeyChanged(appCtx: Context, peerUserId: Int) {
        try {
            val intent = Intent(ACTION_IDENTITY_KEY_CHANGED).apply {
                putExtra(EXTRA_PEER_USER_ID, peerUserId)
            }
            LocalBroadcastManager.getInstance(appCtx).sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun broadcastIdentityChangeAsync(
        appCtx: Context,
        myUserId: Int,
        bundle: IdentityKeyManager.LocalKeyBundleUpload
    ) {
        if (myUserId <= 0) return
        val noticeJson = gson.toJson(
            IdentityChangedNotice(
                userId = myUserId,
                ikSignPubB64 = bundle.ikSignPubB64,
                ikDhPubB64 = bundle.ikDhPubB64,
                ikDhSigB64 = bundle.ikDhSigB64
            )
        )
        scope.launch {
            try {
                val groupsResp = ApiClient.instance.getUserGroups(userId = myUserId).execute()
                if (!groupsResp.isSuccessful || groupsResp.body()?.status != "success") return@launch
                val groups = groupsResp.body()?.data.orEmpty()
                val sentPairs = HashSet<String>()
                for (grp in groups) {
                    val memResp = ApiClient.instance.getGroupMembers(groupId = grp.id).execute()
                    val members = if (memResp.isSuccessful) memResp.body()?.data.orEmpty() else emptyList()
                    for (m in members) {
                        if (m.userId > 0 && m.userId != myUserId && sentPairs.add("${grp.id}:${m.userId}")) {
                            try {
                                ApiClient.instance.sendKeyDistribution(
                                    userId = myUserId,
                                    groupId = grp.id,
                                    recipientId = m.userId,
                                    epoch = grp.membershipEpoch,
                                    distType = DIST_TYPE_IDENTITY_CHANGED,
                                    payload = noticeJson
                                ).execute()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Errore broadcast cambio identità: ${e.message}")
            }
        }
    }

    /**
     * Verifica periodicamente sul server se le Identity Key di [peerIds] sono cambiate.
     * In caso di cambio, [IdentityKeyManager.evaluateOrPinIdentity] imposta subito STATE_KEY_CHANGED (TOFU fail-closed).
     */
    fun syncPeerIdentityKeysSync(
        context: Context,
        myUserId: Int,
        peerIds: List<Int>,
        minIntervalMs: Long = 15_000L
    ): Boolean {
        if (myUserId <= 0 || peerIds.isEmpty()) return false
        val appCtx = context.applicationContext
        IdentityKeyManager.init(appCtx)

        val now = System.currentTimeMillis()
        val effectiveInterval = if (identityKeysEndpointSupported || minIntervalMs == 0L) {
            minIntervalMs
        } else {
            maxOf(minIntervalMs, 15_000L)
        }

        val duePeers = peerIds
            .asSequence()
            .filter { it > 0 && it != myUserId }
            .distinct()
            .filter { pid ->
                val trust = IdentityKeyManager.getTrustedIdentity(appCtx, pid)
                if (trust?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
                    false
                } else {
                    val last = lastPeerIdentityPollMs[pid] ?: 0L
                    (now - last) >= effectiveInterval
                }
            }
            .toList()

        if (duePeers.isEmpty()) return false

        var anyKeyChanged = false
        if (identityKeysEndpointSupported) {
            try {
                val resp = ApiClient.instance.getIdentityKeys(
                    userId = myUserId,
                    targetUserIdsJson = gson.toJson(duePeers)
                ).execute()
                val body = resp.body()
                if (resp.isSuccessful && body?.status == "success") {
                    val list = body.data.orEmpty()
                    val ts = System.currentTimeMillis()
                    duePeers.forEach { lastPeerIdentityPollMs[it] = ts }
                    for (item in list) {
                        if (item.userId <= 0 || item.identitySignKey.isBlank() || item.identityDhKey.isBlank() || item.identityDhSig.isBlank()) continue
                        val sigOk = EcdhHelper.verifySignature(
                            pubB64 = item.identitySignKey,
                            dataBytes = IdentityKeyManager.buildIkDhSignedData(item.identityDhKey),
                            sigB64 = item.identityDhSig
                        )
                        if (!sigOk) continue
                        val tofu = IdentityKeyManager.evaluateOrPinIdentity(
                            context = appCtx,
                            peerUserId = item.userId,
                            ikSignPubB64 = item.identitySignKey,
                            ikDhPubB64 = item.identityDhKey
                        )
                        if (tofu.keyChanged) {
                            anyKeyChanged = true
                            synchronized(pairwiseLock) {
                                DoubleRatchetHelper.resetSessionKeepVault(DoubleRatchetHelper.pairwiseSessionKey(item.userId))
                            }
                            notifyIdentityKeyChanged(appCtx, item.userId)
                        }
                    }
                    return anyKeyChanged
                } else if (body?.message?.contains("non valida", ignoreCase = true) == true) {
                    identityKeysEndpointSupported = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Errore syncPeerIdentityKeysSync: ${e.message}")
            }
        }

        val ts = System.currentTimeMillis()
        for (pid in duePeers) {
            lastPeerIdentityPollMs[pid] = ts
            // Se l'endpoint leggero get_identity_keys non è supportato dal server,
            // evita di consumare OPK con get_prekey_bundle nei poll automatici a meno che
            // il contatto non sia ancora mai stato fissato o la richiesta sia esplicita (minIntervalMs == 0L).
            if (minIntervalMs == 0L || IdentityKeyManager.getTrustedIdentity(appCtx, pid) == null) {
                val updated = fetchAndPinPeerIdentitySync(appCtx, myUserId, pid)
                if (updated?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
                    anyKeyChanged = true
                    notifyIdentityKeyChanged(appCtx, pid)
                }
            }
        }
        return anyKeyChanged
    }

    /**
     * Dopo che l'utente accetta o verifica un cambio identità TOFU, pulisce le sessioni obsolete
     * verso [peerUserId] (preservando il Vault) così i nuovi messaggi rinegoziano con la nuova chiave.
     */
    fun onPeerIdentityChangeResolvedSync(
        context: Context,
        myUserId: Int,
        peerUserId: Int,
        groupId: Int = 0
    ) {
        if (myUserId <= 0 || peerUserId <= 0) return
        val appCtx = context.applicationContext
        val primaryKey = DoubleRatchetHelper.pairwiseSessionKey(peerUserId)
        val altKey = inboundAltSessionKey(peerUserId)
        failedX3dhPayloads.clear()
        synchronized(pairwiseLock) {
            DoubleRatchetHelper.resetSessionKeepVault(primaryKey)
            DoubleRatchetHelper.resetSessionKeepVault(altKey)
        }
        if (groupId > 0) {
            SecurePrefs.get(appCtx).edit().remove(prefGroupPeerDist(groupId, peerUserId)).commit()
        }
    }

    private fun maybeRotateLocalPreKeysQuietly(appCtx: Context, userId: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRotateMs < 30_000L) return
        lastAutoRotateMs = now
        ensureLocalBundleUploadedSync(appCtx, userId, forceRotate = false)
    }

    private fun resetAndEnsurePairwiseSessionSync(
        appCtx: Context,
        myUserId: Int,
        peerUserId: Int
    ): PairwiseResult {
        if (myUserId <= 0 || peerUserId <= 0 || myUserId == peerUserId) {
            return PairwiseResult(ready = false)
        }
        val primaryKey = DoubleRatchetHelper.pairwiseSessionKey(peerUserId)
        val altKey = inboundAltSessionKey(peerUserId)
        return synchronized(pairwiseLock) {
            val existingTrust = IdentityKeyManager.getTrustedIdentity(appCtx, peerUserId)
            if (existingTrust?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
                return PairwiseResult(ready = false, keyChanged = true)
            }
            val prevPrimaryJson = DoubleRatchetHelper.exportSessionJson(primaryKey)
            if (prevPrimaryJson != null) {
                DoubleRatchetHelper.importSessionJson(altKey, prevPrimaryJson)
            }
            DoubleRatchetHelper.resetSessionKeepVault(primaryKey)
            ensurePairwiseSessionSync(appCtx, myUserId, peerUserId)
        }
    }

    private fun recoveryCanonicalData(req: KeyRecoveryRequestPayload): ByteArray =
        "CC-KREQ-v1|${req.type}|${req.groupId}|${req.requesterId}|${req.failedChainId}|${if (req.resetPairwise) 1 else 0}|${req.timestamp}"
            .toByteArray(Charsets.UTF_8)

    internal fun buildSignedRecoveryRequest(
        context: Context? = null,
        myUserId: Int,
        groupId: Int,
        type: String,
        failedChainId: Int = 0,
        resetPairwise: Boolean = true
    ): KeyRecoveryRequestPayload? {
        if (myUserId <= 0 || groupId <= 0) return null
        val myIdentity = IdentityKeyManager.ensureLocalIdentity(context, myUserId)
        val unsigned = KeyRecoveryRequestPayload(
            type = type,
            groupId = groupId,
            requesterId = myUserId,
            failedChainId = failedChainId,
            resetPairwise = resetPairwise,
            timestamp = System.currentTimeMillis(),
            sigB64 = ""
        )
        val sig = EcdhHelper.signData(myIdentity.ikSignPrivB64, recoveryCanonicalData(unsigned)) ?: return null
        return unsigned.copy(sigB64 = sig)
    }

    internal fun verifyRecoveryRequest(
        context: Context? = null,
        expectedSenderId: Int,
        payloadJson: String,
        expectedGroupId: Int = 0,
        myUserId: Int = 0
    ): KeyRecoveryRequestPayload? {
        if (expectedSenderId <= 0 || payloadJson.isBlank()) return null
        val req = try {
            gson.fromJson(payloadJson, KeyRecoveryRequestPayload::class.java)
        } catch (_: Exception) {
            return null
        } ?: return null

        if (req.requesterId != expectedSenderId || req.groupId <= 0 || req.sigB64.isBlank()) return null
        if (expectedGroupId > 0 && req.groupId != expectedGroupId) return null

        val resolvedMyId = if (myUserId > 0) myUserId else expectedSenderId
        val peerTrust = IdentityKeyManager.getTrustedIdentity(context, expectedSenderId)
            ?: (if (context != null) fetchAndPinPeerIdentitySync(context, resolvedMyId, expectedSenderId) else null)
            ?: return null
        if (peerTrust.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) {
            Log.w(TAG, "Richiesta recovery ignorata (TOFU STATE_KEY_CHANGED) per peer=$expectedSenderId")
            return null
        }
        val sigOk = EcdhHelper.verifySignature(
            pubB64 = peerTrust.ikSignPubB64,
            dataBytes = recoveryCanonicalData(req),
            sigB64 = req.sigB64
        )
        return if (sigOk) req else null
    }

    private fun sendRecoveryRequestSync(
        appCtx: Context,
        myUserId: Int,
        peerUserId: Int,
        groupId: Int,
        type: String,
        failedChainId: Int = 0,
        resetPairwise: Boolean = true
    ): Boolean {
        if (myUserId <= 0 || peerUserId <= 0 || myUserId == peerUserId || groupId <= 0) return false
        val cooldownKey = "$type:$groupId:$peerUserId"
        val now = System.currentTimeMillis()
        val prev = recoveryCooldowns[cooldownKey] ?: 0L
        if (now - prev < RECOVERY_COOLDOWN_MS) return false

        val trust = IdentityKeyManager.getTrustedIdentity(appCtx, peerUserId)
        if (trust?.trustState == TrustedIdentityEntity.STATE_KEY_CHANGED) return false

        recoveryCooldowns[cooldownKey] = now
        val signedReq = buildSignedRecoveryRequest(
            context = appCtx,
            myUserId = myUserId,
            groupId = groupId,
            type = type,
            failedChainId = failedChainId,
            resetPairwise = resetPairwise
        ) ?: return false

        return try {
            val epoch = SecurePrefs.get(appCtx).getInt("membership_epoch_$groupId", 1)
            val resp = ApiClient.instance.sendKeyDistribution(
                userId = myUserId,
                groupId = groupId,
                recipientId = peerUserId,
                epoch = epoch,
                distType = type,
                payload = gson.toJson(signedReq)
            ).execute()
            resp.isSuccessful && resp.body()?.status == "success"
        } catch (e: Exception) {
            Log.w(TAG, "Errore invio $type a peer=$peerUserId: ${e.message}")
            false
        }
    }

    private fun fulfillSenderKeyRequestSync(
        appCtx: Context,
        myUserId: Int,
        req: KeyRecoveryRequestPayload,
        fallbackEpoch: Int
    ) {
        if (myUserId <= 0 || req.requesterId <= 0 || req.groupId <= 0) return
        val prefs = SecurePrefs.get(appCtx)
        prefs.edit().remove(prefGroupPeerDist(req.groupId, req.requesterId)).commit()

        val pwRes = if (req.resetPairwise) {
            resetAndEnsurePairwiseSessionSync(appCtx, myUserId, req.requesterId)
        } else {
            ensurePairwiseSessionSync(appCtx, myUserId, req.requesterId)
        }
        if (!pwRes.ready || pwRes.keyChanged) return

        val state = SenderKeyHelper.getOrCreateMySenderKey(req.groupId, myUserId)
        val distJson = SenderKeyHelper.exportDistributionJson(req.groupId, myUserId)
        val encryptedDist = DoubleRatchetHelper.encryptMessage(
            DoubleRatchetHelper.pairwiseSessionKey(req.requesterId),
            distJson
        ) ?: return

        val epoch = prefs.getInt("membership_epoch_${req.groupId}", fallbackEpoch.coerceAtLeast(1))
        try {
            val sendResp = ApiClient.instance.sendKeyDistribution(
                userId = myUserId,
                groupId = req.groupId,
                recipientId = req.requesterId,
                epoch = epoch,
                distType = "sender_key",
                payload = encryptedDist
            ).execute()
            if (sendResp.isSuccessful && sendResp.body()?.status == "success") {
                prefs.edit()
                    .putString(prefGroupPeerDist(req.groupId, req.requesterId), "${state.chainId}:$epoch")
                    .commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore reinvio SenderKey su richiesta a peer=${req.requesterId}: ${e.message}")
        }
    }

    fun healUndecryptableMessagesAsync(
        context: Context,
        myUserId: Int,
        groupId: Int,
        isPrivateChat: Boolean,
        messages: List<Message>,
        failedMessageIds: Set<Int>
    ) {
        if (myUserId <= 0 || groupId <= 0 || failedMessageIds.isEmpty() || messages.isEmpty()) return
        val appCtx = context.applicationContext
        val snapshot = messages.toList()
        val failedIds = failedMessageIds.toSet()

        scope.launch {
            val latestBySender = HashMap<Int, Message>()
            for (msg in snapshot) {
                if (msg.userId <= 0 || msg.userId == myUserId || msg.isViewOnce) continue
                if (msg.message.startsWith(SenderKeyHelper.SK_PREFIX) || msg.message.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
                    latestBySender[msg.userId] = msg
                }
            }
            if (latestBySender.isEmpty()) return@launch

            val prefs = SecurePrefs.get(appCtx)
            for ((peerId, latestMsg) in latestBySender) {
                if (!failedIds.contains(latestMsg.id)) continue
                if (!IdentityKeyManager.isIdentityAllowedForSend(appCtx, peerId)) continue

                val healedPrefKey = prefLastHealedMsgId(groupId, peerId)
                val lastHealedId = prefs.getInt(healedPrefKey, 0)
                if (latestMsg.id <= lastHealedId) continue

                if (!isPrivateChat && latestMsg.message.startsWith(SenderKeyHelper.SK_PREFIX)) {
                    val hdr = SenderKeyHelper.parseEnvelopeHeader(latestMsg.message) ?: continue
                    if (SenderKeyHelper.hasMatchingChainKey(groupId, peerId, hdr.chainId)) continue
                    prefs.edit().putInt(healedPrefKey, latestMsg.id).commit()
                    maybeRotateLocalPreKeysQuietly(appCtx, myUserId)
                    sendRecoveryRequestSync(
                        appCtx = appCtx,
                        myUserId = myUserId,
                        peerUserId = peerId,
                        groupId = groupId,
                        type = DIST_TYPE_SENDER_KEY_REQUEST,
                        failedChainId = hdr.chainId,
                        resetPairwise = true
                    )
                } else if (isPrivateChat && latestMsg.message.startsWith(DoubleRatchetHelper.DR_PREFIX)) {
                    prefs.edit().putInt(healedPrefKey, latestMsg.id).commit()
                    maybeRotateLocalPreKeysQuietly(appCtx, myUserId)
                    sendRecoveryRequestSync(
                        appCtx = appCtx,
                        myUserId = myUserId,
                        peerUserId = peerId,
                        groupId = groupId,
                        type = DIST_TYPE_PAIRWISE_REKEY_REQUEST,
                        resetPairwise = true
                    )
                }
            }
        }
    }

    /**
     * Rigenera la sessione pairwise X3DH + Double Ratchet con [peerUserId] senza perdere i messaggi nel Vault.
     */
    fun renegotiatePairwiseSessionSync(
        context: Context,
        myUserId: Int,
        peerUserId: Int,
        groupId: Int = 0
    ): PairwiseResult {
        if (myUserId <= 0 || peerUserId <= 0 || myUserId == peerUserId) {
            return PairwiseResult(ready = false)
        }
        val appCtx = context.applicationContext
        IdentityKeyManager.init(appCtx)
        X3dhHelper.init(appCtx)
        failedX3dhPayloads.clear()

        ensureLocalBundleUploadedSync(appCtx, myUserId, forceRotate = false)

        val primaryKey = DoubleRatchetHelper.pairwiseSessionKey(peerUserId)
        val result = resetAndEnsurePairwiseSessionSync(appCtx, myUserId, peerUserId)

        if (result.ready) {
            try {
                val rekeyControlJson = """{"type":"pairwise_rekey","uid":$myUserId,"ts":${System.currentTimeMillis()}}"""
                val drControl = DoubleRatchetHelper.encryptMessage(primaryKey, rekeyControlJson)
                if (drControl != null) {
                    ApiClient.instance.sendKeyDistribution(
                        userId = myUserId,
                        recipientId = peerUserId,
                        groupId = maxOf(0, groupId),
                        distType = "pairwise_rekey",
                        payload = drControl
                    ).execute()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invio distribuzione pairwise_rekey non riuscito: ${e.message}")
            }
        }
        return result
    }

    fun clearGroupDistributionCache(context: Context, groupId: Int) {
        if (groupId <= 0) return
        val prefs = SecurePrefs.get(context.applicationContext)
        val prefix = "sk_dist_${groupId}_"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.commit()
    }
}
