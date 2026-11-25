package com.lollo1344.cryptionchat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SafeBase64UnitTest {

    @Test
    fun decodeStandard_roundTripsValidPayloads() {
        val samples = listOf(
            ByteArray(0),
            "a".toByteArray(Charsets.UTF_8),
            "ab".toByteArray(Charsets.UTF_8),
            "abc".toByteArray(Charsets.UTF_8),
            "CryptionChat E2EE payload 1234567890!".toByteArray(Charsets.UTF_8),
            ByteArray(256) { it.toByte() }
        )

        for (raw in samples) {
            val encoded = Base64.getEncoder().encodeToString(raw)
            val decoded = SafeBase64.decodeStandard(encoded)
            assertArrayEquals(raw, decoded)
        }
    }

    @Test
    fun decodeUrlSafe_roundTripsUnpaddedAndPaddedPayloads() {
        val raw = ByteArray(191) { (it * 31).toByte() }
        val encodedNoPad = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val encodedPadded = Base64.getUrlEncoder().encodeToString(raw)

        assertArrayEquals(raw, SafeBase64.decodeUrlSafe(encodedNoPad))
        assertArrayEquals(raw, SafeBase64.decodeUrlSafe(encodedPadded))
    }

    @Test
    fun decodeStandard_rejectsMalformedCharactersAndPadding() {
        val malformedInputs = listOf(
            "A",                  // length % 4 == 1
            "ABCDE",              // length % 4 == 1
            "AA=A",               // non-trailing padding
            "A===",               // >2 padding chars
            "AB=",                // padded but length not multiple of 4
            "SGVs!G8=",           // illegal ASCII char
            "SGVs\u0000G8=",      // NUL byte injection
            "SGVsbG8\n",          // newline rejected in strict mode
            "SGVs-G8_",           // URL-safe chars rejected in standard decoder
            "SGVsbG8€"            // non-ASCII char
        )

        for (bad in malformedInputs) {
            assertThrows("Expected rejection for '$bad'", IllegalArgumentException::class.java) {
                SafeBase64.decodeStandard(bad)
            }
        }
    }

    @Test
    fun decodeUrlSafe_rejectsStandardSymbolsAndMalformedPadding() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeBase64.decodeUrlSafe("SGVs+G8/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeBase64.decodeUrlSafe("A=BC")
        }
    }

    @Test
    fun decodeStandard_enforcesMaxLengthBound() {
        val oversized = "AAAA".repeat(10)
        assertThrows(IllegalArgumentException::class.java) {
            SafeBase64.decodeStandard(oversized, maxInputChars = 16)
        }
    }

    @Test
    fun decodeStandardLenientWhitespace_acceptsLegacyLineWrappedCiphertext() {
        val raw = "Legacy AES-CBC ciphertext content".toByteArray(Charsets.UTF_8)
        val wrapped = Base64.getEncoder().encodeToString(raw).chunked(12).joinToString("\r\n")
        assertArrayEquals(raw, SafeBase64.decodeStandardLenientWhitespace(wrapped))
    }
}
