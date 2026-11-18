package com.lollo1344.cryptionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PanicHelperUnitTest {

    @Test
    fun secureDelete_directoryTreeDeletesWithoutStackOverflow() {
        val root = Files.createTempDirectory("panic_helper_test_").toFile()
        try {
            val nested = File(root, "sub/deeper").apply { mkdirs() }
            val topFile = File(root, "top.bin").apply { writeBytes(ByteArray(128) { 0x5A }) }
            val nestedFile = File(nested, "secret.dat").apply { writeBytes(ByteArray(4099) { 0x3C }) }
            val emptyDir = File(root, "empty_dir").apply { mkdirs() }

            assertTrue(topFile.exists())
            assertTrue(nestedFile.exists())
            assertTrue(emptyDir.exists())

            val result = PanicHelper.secureDelete(root)

            assertTrue(result)
            assertFalse(root.exists())
            assertFalse(topFile.exists())
            assertFalse(nestedFile.exists())
            assertFalse(emptyDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parseRemoteKillSuffix_ignoresSelfSentMessagesAndMatchesIncomingCommands() {
        assertNull(
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "panic999",
                remoteKillCode = "panic999",
                senderUserId = 42,
                myUserId = 42
            )
        )

        assertEquals(
            "",
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "  PANIC999  ",
                remoteKillCode = "panic999",
                senderUserId = 99,
                myUserId = 42
            )
        )

        assertEquals(
            "/off",
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "panic999 /OFF",
                remoteKillCode = "panic999",
                senderUserId = 99,
                myUserId = 42
            )
        )

        assertEquals(
            "/reboot",
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "panic999 /reboot",
                remoteKillCode = "panic999"
            )
        )

        assertNull(
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "panic9990",
                remoteKillCode = "panic999",
                senderUserId = 99,
                myUserId = 42
            )
        )

        assertNull(
            PanicHelper.parseRemoteKillSuffix(
                decryptedText = "ciao",
                remoteKillCode = "   ",
                senderUserId = 99,
                myUserId = 42
            )
        )
    }
}
