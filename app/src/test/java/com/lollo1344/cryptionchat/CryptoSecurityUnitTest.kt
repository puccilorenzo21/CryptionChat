package com.lollo1344.cryptionchat

import com.google.gson.Gson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

class CryptoSecurityUnitTest {

    private class UserTestEnv(val userId: Int) {
        val keyStorage = IdentityKeyManager.InMemoryKeyStorage()
        val identityStore = IdentityKeyManager.InMemoryTrustedIdentityStore()
        val drStorage = DoubleRatchetHelper.InMemoryStorageBackend()
        val skStorage = SenderKeyHelper.InMemoryStorageBackend()

        fun activate() {
            X3dhHelper.setStorageForTests(keyStorage, identityStore, drStorage)
            SenderKeyHelper.setStorageForTests(skStorage)
        }
    }

    private fun buildRemoteBundleFromLocal(
        userEnv: UserTestEnv,
        includeOpk: Boolean
    ): IdentityKeyManager.RemotePreKeyBundle {
        userEnv.activate()
        val upload = IdentityKeyManager.ensureLocalBundle(userId = userEnv.userId)
        val opk = if (includeOpk) upload.oneTimePreKeys.firstOrNull() else null
        return IdentityKeyManager.RemotePreKeyBundle(
            userId = upload.userId,
            ikSignPubB64 = upload.ikSignPubB64,
            ikDhPubB64 = upload.ikDhPubB64,
            ikDhSigB64 = upload.ikDhSigB64,
            spkId = upload.spkId,
            spkPubB64 = upload.spkPubB64,
            spkSigB64 = upload.spkSigB64,
            spkCreatedAt = upload.spkCreatedAt,
            opkId = opk?.opkId,
            opkPubB64 = opk?.pubB64
        )
    }

    private fun runX3dhOneRttHandshake(includeOpk: Boolean): Triple<UserTestEnv, UserTestEnv, Int?> {
        val alice = UserTestEnv(userId = 1)
        val bob = UserTestEnv(userId = 2)

        val bobBundle = buildRemoteBundleFromLocal(bob, includeOpk = includeOpk)
        val initialOpkCount = IdentityKeyManager.getLocalOneTimePreKeyCount(bob.userId)
        assertEquals(IdentityKeyManager.DEFAULT_OPK_BATCH_SIZE, initialOpkCount)

        // Alice verifica bundle di Bob e avvia sessione X3DH + invia primo messaggio DR1:
        alice.activate()
        assertTrue(IdentityKeyManager.verifyRemoteBundle(bobBundle))
        val initiated = X3dhHelper.initiateSession(myUserId = alice.userId, remoteBundle = bobBundle)
        assertNotNull(initiated)
        val (aliceSessionKey, headerJson) = initiated!!
        val firstCiphertext = DoubleRatchetHelper.encryptMessage(aliceSessionKey, "Ciao Bob, bootstrap X3DH!")!!
        assertTrue(firstCiphertext.startsWith(DoubleRatchetHelper.DR_PREFIX))

        val extractedHeader = DoubleRatchetHelper.extractX3dhInitHeader(firstCiphertext)
        assertNotNull(extractedHeader)
        assertEquals(headerJson, extractedHeader)

        // Bob accetta la sessione 1-RTT dall'header nel primo pacchetto DR1: e decifra subito
        bob.activate()
        val bobSessionKey = X3dhHelper.acceptSession(
            myUserId = bob.userId,
            peerUserId = alice.userId,
            x3dhInitHeaderJson = extractedHeader!!
        )
        assertNotNull(bobSessionKey)
        assertEquals(
            "Ciao Bob, bootstrap X3DH!",
            DoubleRatchetHelper.decryptMessage(bobSessionKey!!, firstCiphertext)
        )

        // Bob risponde nel primo turno (1-RTT) e Alice decifra
        val replyCiphertext = DoubleRatchetHelper.encryptMessage(bobSessionKey, "Ciao Alice, ricevuto 1-RTT!")!!
        alice.activate()
        assertEquals(
            "Ciao Alice, ricevuto 1-RTT!",
            DoubleRatchetHelper.decryptMessage(aliceSessionKey, replyCiphertext)
        )

        return Triple(alice, bob, bobBundle.opkId)
    }

    @Test
    fun ecdhAndHkdf_deriveIdenticalKeys() {
        val alice = EcdhHelper.generateKeyPair()!!
        val bob = EcdhHelper.generateKeyPair()!!

        val aliceShared = EcdhHelper.computeSharedAesKey(alice.first, bob.second)
        val bobShared = EcdhHelper.computeSharedAesKey(bob.first, alice.second)

        assertNotNull(aliceShared)
        assertEquals(aliceShared, bobShared)
        assertEquals(64, aliceShared!!.first.length)
        assertEquals(32, aliceShared.second.length)
    }

    @Test
    fun padding128Bytes_normalizesCiphertextLengths() {
        val alice = EcdhHelper.generateKeyPair()!!
        val bob = EcdhHelper.generateKeyPair()!!
        val shared = EcdhHelper.computeSharedAesKey(alice.first, bob.second)!!

        DoubleRatchetHelper.setStorageForTests(DoubleRatchetHelper.InMemoryStorageBackend())

        val c1 = EncryptionHelper.encryptDirect("a", shared.first, shared.second)!!
        val c15 = EncryptionHelper.encryptDirect("ciao mondo 1234", shared.first, shared.second)!!
        val c100 = EncryptionHelper.encryptDirect("x".repeat(100), shared.first, shared.second)!!

        // Tutti sotto 120 byte (8B header PAD1 + <=120B payload = <=128B) producono esattamente la stessa lunghezza cifrata
        assertEquals(c1.length, c15.length)
        assertEquals(c15.length, c100.length)

        assertEquals("a", EncryptionHelper.decrypt(c1, shared.first, shared.second))
        assertEquals("ciao mondo 1234", EncryptionHelper.decrypt(c15, shared.first, shared.second))
        assertEquals("x".repeat(100), EncryptionHelper.decrypt(c100, shared.first, shared.second))
    }

    @Test
    fun doubleRatchet_fullConversationOutOfOrderAndTamperProtection() {
        val aliceKeys = EcdhHelper.generateKeyPair()!!
        val bobKeys = EcdhHelper.generateKeyPair()!!
        val shared = EcdhHelper.computeSharedAesKey(aliceKeys.first, bobKeys.second)!!
        val keyHex = shared.first

        val aliceStore = DoubleRatchetHelper.InMemoryStorageBackend()
        val bobStore = DoubleRatchetHelper.InMemoryStorageBackend()

        // Setup sessione Alice (Initiator)
        DoubleRatchetHelper.setStorageForTests(aliceStore)
        assertTrue(DoubleRatchetHelper.initSession(keyHex, aliceKeys.first, aliceKeys.second, bobKeys.second, isInitiator = true))

        // Setup sessione Bob (Responder)
        DoubleRatchetHelper.setStorageForTests(bobStore)
        assertTrue(DoubleRatchetHelper.initSession(keyHex, bobKeys.first, bobKeys.second, aliceKeys.second, isInitiator = false))

        // 1. Bob invia PRIMA di Alice (verifica assenza deadlock iniziale)
        DoubleRatchetHelper.setStorageForTests(bobStore)
        val b0 = DoubleRatchetHelper.encryptMessage(keyHex, "Ciao Alice, scrivo per primo")!!
        assertTrue(b0.startsWith(DoubleRatchetHelper.DR_PREFIX))

        DoubleRatchetHelper.setStorageForTests(aliceStore)
        assertEquals("Ciao Alice, scrivo per primo", DoubleRatchetHelper.decryptMessage(keyHex, b0))
        // Rilettura da LocalVault
        assertEquals("Ciao Alice, scrivo per primo", DoubleRatchetHelper.decryptMessage(keyHex, b0))

        // 2. Alice invia 3 messaggi consecutivi ricevuti fuori ordine da Bob
        val a0 = DoubleRatchetHelper.encryptMessage(keyHex, "Msg 1")!!
        val a1 = DoubleRatchetHelper.encryptMessage(keyHex, "Msg 2")!!
        val a2 = DoubleRatchetHelper.encryptMessage(keyHex, "Msg 3")!!
        assertNotEquals(a0, a1)

        DoubleRatchetHelper.setStorageForTests(bobStore)
        // Verifica che un pacchetto manomesso fallisca e NON corrompa lo stato
        val tamperedParts = a2.split(":")
        val tamperedBytes = Base64.getDecoder().decode(tamperedParts[2])
        tamperedBytes[tamperedBytes.size - 1] = (tamperedBytes[tamperedBytes.size - 1].toInt() xor 0x01).toByte()
        val tamperedPayload = "${tamperedParts[0]}:${tamperedParts[1]}:${Base64.getEncoder().encodeToString(tamperedBytes)}"
        assertNull(DoubleRatchetHelper.decryptMessage(keyHex, tamperedPayload))

        // Ora consegna fuori ordine: Msg 3 -> Msg 1 -> Msg 2
        assertEquals("Msg 3", DoubleRatchetHelper.decryptMessage(keyHex, a2))
        assertEquals("Msg 1", DoubleRatchetHelper.decryptMessage(keyHex, a0))
        assertEquals("Msg 2", DoubleRatchetHelper.decryptMessage(keyHex, a1))

        // 3. Bob risponde -> attiva un nuovo step DH Ratchet verso Alice
        val b1 = DoubleRatchetHelper.encryptMessage(keyHex, "Ricevuti tutti in sicurezza!")!!
        DoubleRatchetHelper.setStorageForTests(aliceStore)
        assertEquals("Ricevuti tutti in sicurezza!", DoubleRatchetHelper.decryptMessage(keyHex, b1))

        // 4. Alice risponde -> altro step DH Ratchet verso Bob
        val a3 = DoubleRatchetHelper.encryptMessage(keyHex, "Perfetto, chiave ruotata!")!!
        DoubleRatchetHelper.setStorageForTests(bobStore)
        assertEquals("Perfetto, chiave ruotata!", DoubleRatchetHelper.decryptMessage(keyHex, a3))
    }

    @Test
    fun ctr2AuthenticatedStream_multiChunkAndTamperAndTruncationDetection() {
        val alice = EcdhHelper.generateKeyPair()!!
        val bob = EcdhHelper.generateKeyPair()!!
        val shared = EcdhHelper.computeSharedAesKey(alice.first, bob.second)!!

        // 160 KB (3 chunk: 64K + 64K + 32K)
        val original = ByteArray(160 * 1024) { (it % 251).toByte() }
        val encOut = ByteArrayOutputStream()
        EncryptionHelper.encryptStream(ByteArrayInputStream(original), encOut, shared.first, shared.second)
        val encryptedBytes = encOut.toByteArray()

        // Magic header "CTR2"
        assertEquals("CTR2", String(encryptedBytes, 0, 4, Charsets.US_ASCII))

        // Decifratura valida
        val decOut = ByteArrayOutputStream()
        EncryptionHelper.decryptStream(ByteArrayInputStream(encryptedBytes), decOut, shared.first, shared.second)
        assertArrayEquals(original, decOut.toByteArray())

        // Stream vuoto (0 byte)
        val emptyEnc = ByteArrayOutputStream()
        EncryptionHelper.encryptStream(ByteArrayInputStream(ByteArray(0)), emptyEnc, shared.first, shared.second)
        val emptyDec = ByteArrayOutputStream()
        EncryptionHelper.decryptStream(ByteArrayInputStream(emptyEnc.toByteArray()), emptyDec, shared.first, shared.second)
        assertEquals(0, emptyDec.toByteArray().size)

        // Manomissione di 1 bit nel ciphertext
        val tampered = encryptedBytes.copyOf()
        tampered[40] = (tampered[40].toInt() xor 0xFF).toByte()
        try {
            EncryptionHelper.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), shared.first, shared.second)
            fail("Avrebbe dovuto rilevare la manomissione HMAC")
        } catch (_: SecurityException) {
            // Atteso
        }

        // Troncamento stream
        val truncated = encryptedBytes.copyOfRange(0, encryptedBytes.size - 10)
        try {
            EncryptionHelper.decryptStream(ByteArrayInputStream(truncated), ByteArrayOutputStream(), shared.first, shared.second)
            fail("Avrebbe dovuto rilevare il troncamento dello stream")
        } catch (_: IOException) {
            // Atteso
        }

        // Dati extra in coda dopo il chunk finale
        val extended = encryptedBytes + byteArrayOf(1, 2, 3)
        try {
            EncryptionHelper.decryptStream(ByteArrayInputStream(extended), ByteArrayOutputStream(), shared.first, shared.second)
            fail("Avrebbe dovuto rifiutare byte extra dopo il chunk finale")
        } catch (_: SecurityException) {
            // Atteso
        }
    }

    @Test
    fun x3dhOneRtt_withAndWithoutOpk_establishesBidirectionalRatchetAndConsumesOpkOnce() {
        // 1. Handshake 1-RTT CON OPK
        val (alice, bob, usedOpkId) = runX3dhOneRttHandshake(includeOpk = true)
        assertNotNull(usedOpkId)

        // Verifica consumo monouso della OPK su Bob (50 -> 49) e rifiuto replay della stessa OPK
        bob.activate()
        assertEquals(
            IdentityKeyManager.DEFAULT_OPK_BATCH_SIZE - 1,
            IdentityKeyManager.getLocalOneTimePreKeyCount(bob.userId)
        )
        assertNull(IdentityKeyManager.consumeOneTimePreKey(usedOpkId!!, bob.userId))

        // Un nuovo iniziatore che provi a riutilizzare la stessa OPK già consumata deve essere rifiutato
        val malloryBundle = buildRemoteBundleFromLocal(bob, includeOpk = false).copy(
            opkId = usedOpkId,
            opkPubB64 = EcdhHelper.generateKeyPair()!!.second
        )
        alice.activate()
        val replayInit = X3dhHelper.initiateSession(
            myUserId = alice.userId,
            remoteBundle = malloryBundle,
            sessionAliasKey = "pw_replay_test"
        )
        assertNotNull(replayInit)
        bob.activate()
        assertNull(
            X3dhHelper.acceptSession(
                myUserId = bob.userId,
                peerUserId = alice.userId,
                x3dhInitHeaderJson = replayInit!!.second,
                sessionAliasKey = "pw_replay_test"
            )
        )

        // 2. Handshake 1-RTT SENZA OPK (solo IK + SPK + EK)
        val (_, bobNoOpk, opkIdNull) = runX3dhOneRttHandshake(includeOpk = false)
        assertNull(opkIdNull)
        bobNoOpk.activate()
        assertEquals(
            IdentityKeyManager.DEFAULT_OPK_BATCH_SIZE,
            IdentityKeyManager.getLocalOneTimePreKeyCount(bobNoOpk.userId)
        )
    }

    @Test
    fun x3dhBundleVerification_rejectsTamperedSignaturesAndBinding() {
        val alice = UserTestEnv(userId = 10)
        val bob = UserTestEnv(userId = 20)
        val validBundle = buildRemoteBundleFromLocal(bob, includeOpk = true)
        val attackerKey = EcdhHelper.generateKeyPair()!!

        // Firma IK_dh manomessa / sostituita
        val badIkDhSig = validBundle.copy(
            ikDhSigB64 = EcdhHelper.signData(attackerKey.first, IdentityKeyManager.buildIkDhSignedData(validBundle.ikDhPubB64))!!
        )
        assertFalse(IdentityKeyManager.verifyRemoteBundle(badIkDhSig))
        alice.activate()
        assertNull(X3dhHelper.initiateSession(alice.userId, badIkDhSig))

        // Firma SPK manomessa
        val badSpkSig = validBundle.copy(
            spkSigB64 = EcdhHelper.signData(
                attackerKey.first,
                IdentityKeyManager.buildSpkSignedData(validBundle.userId, validBundle.spkId, validBundle.spkPubB64, validBundle.spkCreatedAt)
            )!!
        )
        assertFalse(IdentityKeyManager.verifyRemoteBundle(badSpkSig))
        assertNull(X3dhHelper.initiateSession(alice.userId, badSpkSig))

        // Sostituzione userId o spkId (domain binding nella firma SPK)
        assertFalse(IdentityKeyManager.verifyRemoteBundle(validBundle.copy(userId = 21)))
        assertFalse(IdentityKeyManager.verifyRemoteBundle(validBundle.copy(spkId = validBundle.spkId + 1)))
        assertFalse(IdentityKeyManager.verifyRemoteBundle(validBundle.copy(opkPubB64 = "not-a-valid-ec-key")))

        // Header X3DH in ingresso con firma ik_dh_sig_a forgiata deve essere rifiutato da acceptSession
        assertTrue(IdentityKeyManager.verifyRemoteBundle(validBundle))
        val validInit = X3dhHelper.initiateSession(alice.userId, validBundle)!!
        val parsedHeader = X3dhHelper.parseHeader(validInit.second)!!
        val forgedHeader = parsedHeader.copy(
            ikDhSigA = EcdhHelper.signData(attackerKey.first, IdentityKeyManager.buildIkDhSignedData(parsedHeader.ikDhA))!!
        )
        bob.activate()
        assertNull(X3dhHelper.acceptSession(bob.userId, alice.userId, forgedHeader))
    }

    @Test
    fun tofuFailClosed_blocksIdentityKeyChangeUntilExplicitConfirmation() {
        val alice = UserTestEnv(userId = 101)
        val bobInitial = UserTestEnv(userId = 202)
        val bobRotatedOrMitm = UserTestEnv(userId = 202)

        val initialBundle = buildRemoteBundleFromLocal(bobInitial, includeOpk = true)
        val changedBundle = buildRemoteBundleFromLocal(bobRotatedOrMitm, includeOpk = true)

        alice.activate()
        // Primo contatto: TOFU accetta e fissa in STATE_UNVERIFIED
        val firstEval = IdentityKeyManager.evaluateOrPinIdentity(
            peerUserId = bobInitial.userId,
            ikSignPubB64 = initialBundle.ikSignPubB64,
            ikDhPubB64 = initialBundle.ikDhPubB64
        )
        assertTrue(firstEval.allowed)
        assertTrue(firstEval.isFirstContact)
        assertFalse(firstEval.keyChanged)
        assertEquals(TrustedIdentityEntity.STATE_UNVERIFIED, firstEval.trustState)
        assertTrue(IdentityKeyManager.isIdentityAllowedForSend(peerUserId = bobInitial.userId))

        // Verifica esplicita -> STATE_VERIFIED
        val verified = IdentityKeyManager.confirmVerifiedIdentity(peerUserId = bobInitial.userId)
        assertNotNull(verified)
        assertEquals(TrustedIdentityEntity.STATE_VERIFIED, verified!!.trustState)

        // Cambio improvviso dell'Identity Key -> Fail-Closed STATE_KEY_CHANGED e blocco invii/handshake
        val changedEval = IdentityKeyManager.evaluateOrPinIdentity(
            peerUserId = bobInitial.userId,
            ikSignPubB64 = changedBundle.ikSignPubB64,
            ikDhPubB64 = changedBundle.ikDhPubB64
        )
        assertFalse(changedEval.allowed)
        assertTrue(changedEval.keyChanged)
        assertEquals(TrustedIdentityEntity.STATE_KEY_CHANGED, changedEval.trustState)
        assertFalse(IdentityKeyManager.isIdentityAllowedForSend(peerUserId = bobInitial.userId))

        // Anche una rivalutazione successiva rimane bloccata finché l'utente non conferma
        val reEval = IdentityKeyManager.evaluateOrPinIdentity(
            peerUserId = bobInitial.userId,
            ikSignPubB64 = changedBundle.ikSignPubB64,
            ikDhPubB64 = changedBundle.ikDhPubB64
        )
        assertFalse(reEval.allowed)
        assertEquals(TrustedIdentityEntity.STATE_KEY_CHANGED, reEval.trustState)

        // Handshake X3DH (sia initiateSession che acceptSession) bloccati in STATE_KEY_CHANGED
        assertNull(X3dhHelper.initiateSession(alice.userId, changedBundle))

        bobRotatedOrMitm.activate()
        val bobIdentity = IdentityKeyManager.getLocalIdentity(userId = bobRotatedOrMitm.userId)!!
        val ek = EcdhHelper.generateKeyPair()!!
        val incomingHeader = X3dhHelper.X3dhInitHeader(
            ver = 2,
            ikSignA = bobIdentity.ikSignPubB64,
            ikDhA = bobIdentity.ikDhPubB64,
            ikDhSigA = bobIdentity.ikDhSigB64,
            ekA = ek.second,
            spkIdB = 1,
            opkIdB = null
        )
        // Assicura una SPK su Alice per verificare che il blocco avvenga per TOFU
        alice.activate()
        IdentityKeyManager.ensureLocalBundle(userId = alice.userId)
        assertNull(X3dhHelper.acceptSession(alice.userId, bobInitial.userId, incomingHeader))

        // Dopo accettazione esplicita del cambio chiave, il canale torna sbloccato (STATE_UNVERIFIED)
        val accepted = IdentityKeyManager.acceptKeyChange(peerUserId = bobInitial.userId)
        assertNotNull(accepted)
        assertEquals(TrustedIdentityEntity.STATE_UNVERIFIED, accepted!!.trustState)
        assertTrue(IdentityKeyManager.isIdentityAllowedForSend(peerUserId = bobInitial.userId))
        assertNotNull(X3dhHelper.initiateSession(alice.userId, changedBundle))
    }

    @Test
    fun safetyNumbersAndEmojis_are60Digits11EmojisSymmetricAndStableAcrossEphemeralChanges() {
        val alice = UserTestEnv(userId = 11)
        val bob = UserTestEnv(userId = 22)

        alice.activate()
        val idA = IdentityKeyManager.ensureLocalIdentity(userId = alice.userId)
        bob.activate()
        val idB = IdentityKeyManager.ensureLocalIdentity(userId = bob.userId)

        val snAB = EcdhHelper.getSafetyNumber60Digits(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            bob.userId, idB.ikSignPubB64, idB.ikDhPubB64
        )
        val snBA = EcdhHelper.getSafetyNumber60Digits(
            bob.userId, idB.ikSignPubB64, idB.ikDhPubB64,
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64
        )
        val emojisAB = EcdhHelper.getSafetyEmojis66Bit(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            bob.userId, idB.ikSignPubB64, idB.ikDhPubB64
        )
        val emojisBA = EcdhHelper.getSafetyEmojis66Bit(
            bob.userId, idB.ikSignPubB64, idB.ikDhPubB64,
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64
        )

        // Formato 60 cifre in 12 gruppi da 5 cifre separati da spazio
        assertTrue(
            "Formato Safety Number non valido: $snAB",
            Regex("^\\d{5}( \\d{5}){11}$").matches(snAB)
        )
        assertEquals(60, snAB.replace(" ", "").length)
        assertEquals(12, snAB.split(" ").size)

        // Formato 11 emoji (= 66 bit)
        assertEquals(11, emojisAB.codePointCount(0, emojisAB.length))

        // Simmetria (A,B) == (B,A)
        assertEquals(snAB, snBA)
        assertEquals(emojisAB, emojisBA)

        // Simmetria anche nel tie-breaker a parità di userId
        val snSameUid1 = EcdhHelper.getSafetyNumber60Digits(
            99, idA.ikSignPubB64, idA.ikDhPubB64,
            99, idB.ikSignPubB64, idB.ikDhPubB64
        )
        val snSameUid2 = EcdhHelper.getSafetyNumber60Digits(
            99, idB.ikSignPubB64, idB.ikDhPubB64,
            99, idA.ikSignPubB64, idA.ikDhPubB64
        )
        assertEquals(snSameUid1, snSameUid2)

        // Stabilità rispetto a rotazione SPK / nuove OPK / handshake effimeri X3DH/DR
        bob.activate()
        IdentityKeyManager.ensureSignedPreKey(userId = bob.userId, nowMillis = System.currentTimeMillis() + IdentityKeyManager.SPK_ROTATION_INTERVAL_MS + 10_000L)
        IdentityKeyManager.generateOneTimePreKeys(userId = bob.userId, count = 5)
        val rotatedBundle = buildRemoteBundleFromLocal(bob, includeOpk = true)
        alice.activate()
        assertNotNull(X3dhHelper.initiateSession(alice.userId, rotatedBundle))

        val snAfterEphemeral = EcdhHelper.getSafetyNumber60Digits(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            bob.userId, rotatedBundle.ikSignPubB64, rotatedBundle.ikDhPubB64
        )
        val emojisAfterEphemeral = EcdhHelper.getSafetyEmojis66Bit(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            bob.userId, rotatedBundle.ikSignPubB64, rotatedBundle.ikDhPubB64
        )
        assertEquals(snAB, snAfterEphemeral)
        assertEquals(emojisAB, emojisAfterEphemeral)

        // Se cambia una Identity Key, Safety Number ed Emoji cambiano
        val carol = UserTestEnv(userId = bob.userId)
        carol.activate()
        val idCarol = IdentityKeyManager.ensureLocalIdentity(userId = carol.userId)
        val snDifferent = EcdhHelper.getSafetyNumber60Digits(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            carol.userId, idCarol.ikSignPubB64, idCarol.ikDhPubB64
        )
        val emojisDifferent = EcdhHelper.getSafetyEmojis66Bit(
            alice.userId, idA.ikSignPubB64, idA.ikDhPubB64,
            carol.userId, idCarol.ikSignPubB64, idCarol.ikDhPubB64
        )
        assertNotEquals(snAB, snDifferent)
        assertNotEquals(emojisAB, emojisDifferent)
    }

    @Test
    fun senderKeys_multiMemberOutOfOrderAndCtr2FileDecryption() {
        val groupId = 501
        val alice = UserTestEnv(userId = 1)
        val bob = UserTestEnv(userId = 2)
        val carol = UserTestEnv(userId = 3)
        val members = listOf(alice, bob, carol)

        // Ogni membro genera il proprio SenderKey e distribuisce agli altri due
        val distributions = members.map { member ->
            member.activate()
            SenderKeyHelper.exportDistributionJson(groupId, member.userId)
        }
        for (receiver in members) {
            receiver.activate()
            for (distJson in distributions) {
                assertTrue(SenderKeyHelper.importDistributionJson(distJson))
            }
        }

        // Alice invia 4 messaggi; Bob e Carol li ricevono fuori ordine (m3 -> m1 -> m0 -> m2)
        alice.activate()
        val m0 = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Msg 0")!!
        val m1 = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Msg 1")!!
        val m2 = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Msg 2")!!
        val m3 = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Msg 3")!!
        assertTrue(m0.startsWith(SenderKeyHelper.SK_PREFIX))

        bob.activate()
        assertEquals("Msg 3", SenderKeyHelper.decryptGroupMessage(m3))
        assertEquals("Msg 1", SenderKeyHelper.decryptGroupMessage(m1))
        assertEquals("Msg 0", SenderKeyHelper.decryptGroupMessage(m0))
        assertEquals("Msg 2", SenderKeyHelper.decryptGroupMessage(m2))
        // Rilettura da vault locale
        assertEquals("Msg 0", EncryptionHelper.decrypt(m0, "", ""))

        // Bob e Carol rispondono nel gruppo e tutti decifrano
        val bobReply = SenderKeyHelper.encryptGroupMessage(groupId, bob.userId, "Ciao da Bob")!!
        carol.activate()
        assertEquals("Msg 2", SenderKeyHelper.decryptGroupMessage(m2))
        assertEquals("Msg 0", SenderKeyHelper.decryptGroupMessage(m0))
        assertEquals("Ciao da Bob", SenderKeyHelper.decryptGroupMessage(bobReply))
        val carolMsg = SenderKeyHelper.encryptGroupMessage(groupId, carol.userId, "Ciao da Carol")!!

        alice.activate()
        assertEquals("Ciao da Bob", SenderKeyHelper.decryptGroupMessage(bobReply))
        assertEquals("Ciao da Carol", SenderKeyHelper.decryptGroupMessage(carolMsg))

        // File CTR2 con fileKeyHex / fileIvHex incapsulate nei metadati SK1:
        val rnd = SecureRandom()
        val fileKeyHex = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val fileIvHex = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val fileBytes = ByteArray(96 * 1024) { ((it * 7) % 251).toByte() }

        val encStream = ByteArrayOutputStream()
        EncryptionHelper.encryptStream(ByteArrayInputStream(fileBytes), encStream, fileKeyHex, fileIvHex)
        val encryptedFileBytes = encStream.toByteArray()
        assertEquals("CTR2", String(encryptedFileBytes, 0, 4, Charsets.US_ASCII))

        val gson = Gson()
        val metadata = FileMetadata(
            name = "report_segreto.pdf",
            mime = "application/pdf",
            size = fileBytes.size.toLong(),
            fileKeyHex = fileKeyHex,
            fileIvHex = fileIvHex,
            msgType = "📎 report_segreto.pdf"
        )
        alice.activate()
        val skMetadataPayload = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, gson.toJson(metadata))!!
        assertTrue(skMetadataPayload.startsWith(SenderKeyHelper.SK_PREFIX))

        // Carol decifra i metadati SK1:, estrae fileKeyHex/fileIvHex e decifra lo stream CTR2
        carol.activate()
        val decryptedMetaJson = EncryptionHelper.decrypt(skMetadataPayload, "", "")
        assertNotNull(decryptedMetaJson)
        val parsedMeta = gson.fromJson(decryptedMetaJson, FileMetadata::class.java)
        assertEquals(fileKeyHex, parsedMeta.fileKeyHex)
        assertEquals(fileIvHex, parsedMeta.fileIvHex)

        val decStream = ByteArrayOutputStream()
        EncryptionHelper.decryptStream(
            ByteArrayInputStream(encryptedFileBytes),
            decStream,
            parsedMeta.fileKeyHex!!,
            parsedMeta.fileIvHex!!
        )
        assertArrayEquals(fileBytes, decStream.toByteArray())
    }

    @Test
    fun senderKeys_rotationOnKickOrLeave_preventsRemovedMemberFromDecrypting() {
        val groupId = 777
        val alice = UserTestEnv(userId = 1)
        val bob = UserTestEnv(userId = 2)
        val carol = UserTestEnv(userId = 3)

        // Setup iniziale a 3 membri
        alice.activate()
        val aliceDistV1 = SenderKeyHelper.exportDistributionJson(groupId, alice.userId)
        bob.activate()
        val bobDistV1 = SenderKeyHelper.exportDistributionJson(groupId, bob.userId)
        carol.activate()
        assertTrue(SenderKeyHelper.importDistributionJson(aliceDistV1))
        assertTrue(SenderKeyHelper.importDistributionJson(bobDistV1))

        alice.activate()
        val preKickMsg = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Prima del kick")!!
        carol.activate()
        assertEquals("Prima del kick", SenderKeyHelper.decryptGroupMessage(preKickMsg))

        // Carol viene espulsa (kick/leave): Alice e Bob ruotano le proprie SenderKey e le distribuiscono SOLO ai membri rimasti
        alice.activate()
        val rotatedAlice = SenderKeyHelper.rotateMySenderKey(groupId, alice.userId)
        val aliceDistV2 = SenderKeyHelper.exportDistributionJson(groupId, alice.userId)

        bob.activate()
        val rotatedBob = SenderKeyHelper.rotateMySenderKey(groupId, bob.userId)
        val bobDistV2 = SenderKeyHelper.exportDistributionJson(groupId, bob.userId)

        assertTrue(SenderKeyHelper.importDistributionJson(aliceDistV2))
        alice.activate()
        assertTrue(SenderKeyHelper.importDistributionJson(bobDistV2))

        val postKickAlice = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Segreto dopo espulsione di Carol")!!
        bob.activate()
        assertEquals("Segreto dopo espulsione di Carol", SenderKeyHelper.decryptGroupMessage(postKickAlice))
        val postKickBob = SenderKeyHelper.encryptGroupMessage(groupId, bob.userId, "Confermato Bob senza Carol")!!
        alice.activate()
        assertEquals("Confermato Bob senza Carol", SenderKeyHelper.decryptGroupMessage(postKickBob))

        // Carol (con il vecchio stato/chainId) non deve poter decifrare i messaggi post-rotazione
        assertNotEquals(rotatedAlice.chainId, SenderKeyHelper.parseEnvelopeHeader(preKickMsg)!!.chainId)
        assertEquals(rotatedAlice.chainId, SenderKeyHelper.parseEnvelopeHeader(postKickAlice)!!.chainId)
        assertEquals(rotatedBob.chainId, SenderKeyHelper.parseEnvelopeHeader(postKickBob)!!.chainId)

        carol.activate()
        assertNull(SenderKeyHelper.decryptGroupMessage(postKickAlice))
        assertNull(SenderKeyHelper.decryptGroupMessage(postKickBob))
    }

    @Test
    fun senderKeys_rejectsForgedEcdsaSignatureWithoutCorruptingState() {
        val groupId = 888
        val alice = UserTestEnv(userId = 1)
        val bob = UserTestEnv(userId = 2)

        alice.activate()
        val distJson = SenderKeyHelper.exportDistributionJson(groupId, alice.userId)
        val validPayload = SenderKeyHelper.encryptGroupMessage(groupId, alice.userId, "Messaggio autentico")!!

        bob.activate()
        assertTrue(SenderKeyHelper.importDistributionJson(distJson))

        // Manomette 1 bit nella firma ECDSA (subito prima dei 2 byte finali di lunghezza firma)
        val parts = validPayload.removePrefix(SenderKeyHelper.SK_PREFIX).split(':', limit = 5)
        val combined = Base64.getDecoder().decode(parts[4])
        val forged = combined.copyOf()
        val sigTouchIndex = forged.size - 5
        forged[sigTouchIndex] = (forged[sigTouchIndex].toInt() xor 0x01).toByte()
        val forgedPayload = "${SenderKeyHelper.SK_PREFIX}${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}:${Base64.getEncoder().encodeToString(forged)}"

        assertNull(SenderKeyHelper.decryptGroupMessage(forgedPayload))

        // Lo stato del ricevitore non deve essersi corrotto né aver avanzato la catena: il pacchetto originale deve decifrarsi ancora
        assertEquals("Messaggio autentico", SenderKeyHelper.decryptGroupMessage(validPayload))
    }

    @Test
    fun manualKeyRegeneration_rotatesSpkAndResetsRatchetKeepingVault() {
        val alice = UserTestEnv(userId = 101)
        val bob = UserTestEnv(userId = 202)

        bob.activate()
        val bobBundle1 = IdentityKeyManager.ensureLocalBundle(userId = bob.userId)
        val bobRemote1 = buildRemoteBundleFromLocal(bob, includeOpk = true)

        // 1. Sessione iniziale 1-a-1 tra Alice e Bob
        alice.activate()
        val aliasOnAlice = DoubleRatchetHelper.pairwiseSessionKey(bob.userId)
        val init1 = X3dhHelper.initiateSession(alice.userId, bobRemote1, sessionAliasKey = aliasOnAlice)
        assertNotNull(init1)
        val msg1FromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Messaggio prima del rekey")!!

        bob.activate()
        val aliasOnBob = DoubleRatchetHelper.pairwiseSessionKey(alice.userId)
        assertEquals(
            "Messaggio prima del rekey",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, msg1FromAlice)
        )
        val reply1FromBob = DoubleRatchetHelper.encryptMessage(aliasOnBob, "Risposta prima del rekey")!!

        alice.activate()
        assertEquals(
            "Risposta prima del rekey",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, alice.userId, bob.userId, reply1FromBob)
        )

        // 2. Alice rigenera manualmente le chiavi della chat 1-a-1 con Bob
        bob.activate()
        val (spk2, rotated) = IdentityKeyManager.ensureSignedPreKey(userId = bob.userId, forceRotate = true)
        assertTrue(rotated)
        assertEquals(bobBundle1.spkId + 1, spk2.spkId)
        val bobRemote2 = buildRemoteBundleFromLocal(bob, includeOpk = true)

        alice.activate()
        val prevAlicePrimary = DoubleRatchetHelper.exportSessionJson(aliasOnAlice)
        DoubleRatchetHelper.importSessionJson("pw_rx_${bob.userId}", prevAlicePrimary)
        DoubleRatchetHelper.resetSessionKeepVault(aliasOnAlice)
        val init2 = X3dhHelper.initiateSession(alice.userId, bobRemote2, sessionAliasKey = aliasOnAlice)
        assertNotNull(init2)

        val msg2FromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Nuovo messaggio post-rigenerazione")!!
        val msg3FromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Secondo messaggio consecutivo post-rigenerazione")!!

        // 3. Bob riceve i messaggi post-rigenerazione: promuove la nuova sessione su primaryKey e risponde
        bob.activate()
        assertEquals(
            "Nuovo messaggio post-rigenerazione",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, msg2FromAlice)
        )
        assertEquals(
            "Secondo messaggio consecutivo post-rigenerazione",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, msg3FromAlice)
        )

        val reply2FromBob = DoubleRatchetHelper.encryptMessage(aliasOnBob, "Risposta di Bob sulla nuova chiave")!!
        alice.activate()
        assertEquals(
            "Risposta di Bob sulla nuova chiave",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, alice.userId, bob.userId, reply2FromBob)
        )

        // 4. I messaggi storici precedenti nel Vault restano decifrabili
        assertEquals("Messaggio prima del rekey", DoubleRatchetHelper.decryptMessage(aliasOnAlice, msg1FromAlice))
        assertEquals("Risposta prima del rekey", DoubleRatchetHelper.decryptMessage(aliasOnAlice, reply1FromBob))
    }

    @Test
    fun signedKeyRecoveryRequest_verifiesSignatureRejectsTamperAndChecksSenderKeyChainMatch() {
        val alice = UserTestEnv(userId = 31)
        val bob = UserTestEnv(userId = 32)
        val groupId = 777

        alice.activate()
        val idAlice = IdentityKeyManager.ensureLocalIdentity(userId = alice.userId)
        val signedReq = E2eeSessionOrchestrator.buildSignedRecoveryRequest(
            myUserId = alice.userId,
            groupId = groupId,
            type = "sender_key_request",
            failedChainId = 42,
            resetPairwise = true
        )
        assertNotNull(signedReq)
        val reqJson = com.google.gson.Gson().toJson(signedReq)

        bob.activate()
        IdentityKeyManager.evaluateOrPinIdentity(
            peerUserId = alice.userId,
            ikSignPubB64 = idAlice.ikSignPubB64,
            ikDhPubB64 = idAlice.ikDhPubB64
        )
        val verified = E2eeSessionOrchestrator.verifyRecoveryRequest(
            expectedSenderId = alice.userId,
            payloadJson = reqJson,
            expectedGroupId = groupId
        )
        assertNotNull(verified)
        assertEquals(42, verified!!.failedChainId)
        assertTrue(verified.resetPairwise)

        // Rifiuta manomissione gruppo o mittente errato
        val tamperedJson = com.google.gson.Gson().toJson(signedReq!!.copy(groupId = groupId + 1))
        assertNull(
            E2eeSessionOrchestrator.verifyRecoveryRequest(
                expectedSenderId = alice.userId,
                payloadJson = tamperedJson,
                expectedGroupId = groupId + 1
            )
        )
        assertNull(
            E2eeSessionOrchestrator.verifyRecoveryRequest(
                expectedSenderId = alice.userId + 99,
                payloadJson = reqJson,
                expectedGroupId = groupId
            )
        )

        // Verifica hasMatchingChainKey sui SenderKey di gruppo
        alice.activate()
        val skState = SenderKeyHelper.getOrCreateMySenderKey(groupId, alice.userId)
        val distJson = SenderKeyHelper.exportDistributionJson(groupId, alice.userId)
        bob.activate()
        assertFalse(SenderKeyHelper.hasMatchingChainKey(groupId, alice.userId, skState.chainId))
        assertTrue(SenderKeyHelper.importDistributionJson(distJson))
        assertTrue(SenderKeyHelper.hasMatchingChainKey(groupId, alice.userId, skState.chainId))
        assertFalse(SenderKeyHelper.hasMatchingChainKey(groupId, alice.userId, skState.chainId + 1))
    }

    @Test
    fun keyRegeneration_survivesMultiSpkRotationsHistoricalMessagesAndIdentityReset() {
        val alice = UserTestEnv(userId = 41)
        val bob = UserTestEnv(userId = 42)
        val aliasOnAlice = DoubleRatchetHelper.pairwiseSessionKey(bob.userId)
        val aliasOnBob = DoubleRatchetHelper.pairwiseSessionKey(alice.userId)

        // 1. Bob ruota la SignedPreKey 5 volte consecutive: la prima SPK deve restare nello storico
        val firstBobBundle = buildRemoteBundleFromLocal(bob, includeOpk = true)
        bob.activate()
        repeat(5) {
            IdentityKeyManager.ensureLocalBundle(userId = bob.userId, forceRotate = true)
        }
        assertNotNull(IdentityKeyManager.getSignedPreKey(firstBobBundle.spkId, bob.userId))

        // 2. Alice avvia una prima sessione (usando ancora il primo bundle di Bob) e invia un messaggio storico
        alice.activate()
        assertNotNull(X3dhHelper.initiateSession(alice.userId, firstBobBundle, aliasOnAlice))
        val oldHistoricalMsgFromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Messaggio storico vecchio")!!

        // 3. Alice rigenera le chiavi della chat (nuovo handshake con l'ultimo bundle di Bob e nuova OPK)
        Thread.sleep(5)
        bob.activate()
        val bobUpload2 = IdentityKeyManager.ensureLocalBundle(userId = bob.userId)
        val secondOpk = bobUpload2.oneTimePreKeys[1]
        val latestBobBundle = buildRemoteBundleFromLocal(bob, includeOpk = false).copy(
            opkId = secondOpk.opkId,
            opkPubB64 = secondOpk.pubB64
        )
        alice.activate()
        DoubleRatchetHelper.resetSessionKeepVault(aliasOnAlice)
        assertNotNull(X3dhHelper.initiateSession(alice.userId, latestBobBundle, aliasOnAlice))
        val rekeyControlFromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, """{"type":"pairwise_rekey"}""")!!
        val newMsgFromAlice = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Messaggio dopo rigenerazione")!!

        // 4. Bob riceve prima il rekey nuovo, poi legge il vecchio messaggio storico in chat, poi il nuovo messaggio:
        // il vecchio messaggio storico NON deve sovrascrivere la nuova sessione primaria su Bob!
        bob.activate()
        assertEquals(
            """{"type":"pairwise_rekey"}""",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, rekeyControlFromAlice)
        )
        assertEquals(
            "Messaggio storico vecchio",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, oldHistoricalMsgFromAlice)
        )
        assertEquals(
            "Messaggio dopo rigenerazione",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, newMsgFromAlice)
        )

        // Bob risponde sulla sessione attiva e Alice decifra subito
        val replyFromBob = DoubleRatchetHelper.encryptMessage(aliasOnBob, "Risposta immediata di Bob")!!
        alice.activate()
        assertEquals(
            "Risposta immediata di Bob",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, alice.userId, bob.userId, replyFromBob)
        )

        // 5. Alice rigenera l'Identity Key completa (clearUserKeys) e invia un messaggio a Bob:
        // prima dell'accettazione TOFU su Bob il messaggio è bloccato ma NON avvelena failedX3dhPayloads;
        // appena Bob accetta il cambio chiave, lo stesso messaggio si decifra subito!
        Thread.sleep(5)
        alice.activate()
        IdentityKeyManager.clearUserKeys(userId = alice.userId)
        DoubleRatchetHelper.resetSessionKeepVault(aliasOnAlice)
        val bobBundlePostReset = buildRemoteBundleFromLocal(bob, includeOpk = false)
        alice.activate()
        assertNotNull(X3dhHelper.initiateSession(alice.userId, bobBundlePostReset, aliasOnAlice))
        val msgAfterIdentityReset = DoubleRatchetHelper.encryptMessage(aliasOnAlice, "Ciao con nuova identità!")!!

        bob.activate()
        assertNull(E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, msgAfterIdentityReset))
        assertEquals(
            TrustedIdentityEntity.STATE_KEY_CHANGED,
            IdentityKeyManager.getTrustedIdentity(peerUserId = alice.userId)?.trustState
        )

        IdentityKeyManager.acceptKeyChange(peerUserId = alice.userId)
        DoubleRatchetHelper.resetSessionKeepVault(aliasOnBob)
        assertEquals(
            "Ciao con nuova identità!",
            E2eeSessionOrchestrator.decryptPairwiseMessage(null, bob.userId, alice.userId, msgAfterIdentityReset)
        )
    }
}
