package com.lollo1344.cryptionchat

import java.util.Base64

/**
 * Wrapper difensivo per la decodifica Base64/Base64URL.
 *
 * Valida rigorosamente lunghezza massima, alfabeto consentito e struttura del padding
 * prima di delegare a [java.util.Base64], evitando che payload malformati di rete
 * raggiungano direttamente i decoder nativi/intrinsic JVM (difesa in profondità per CVE-2026-114938).
 */
object SafeBase64 {

    const val DEFAULT_MAX_INPUT_CHARS = 4 * 1024 * 1024

    fun decodeStandard(input: String, maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS): ByteArray {
        validate(input, urlSafe = false, maxInputChars = maxInputChars)
        if (input.isEmpty()) return ByteArray(0)
        return Base64.getDecoder().decode(input)
    }

    fun decodeUrlSafe(input: String, maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS): ByteArray {
        validate(input, urlSafe = true, maxInputChars = maxInputChars)
        if (input.isEmpty()) return ByteArray(0)
        return Base64.getUrlDecoder().decode(input)
    }

    /**
     * Variante tollerante a CR/LF/spazi solo per retrocompatibilità con ciphertext legacy AES-CBC.
     */
    fun decodeStandardLenientWhitespace(input: String, maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS): ByteArray {
        if (input.length > maxInputChars) {
            throw IllegalArgumentException("Base64 payload exceeds maximum allowed length ($maxInputChars)")
        }
        val normalized = stripAsciiWhitespaceIfPresent(input)
        return decodeStandard(normalized, maxInputChars)
    }

    private fun stripAsciiWhitespaceIfPresent(input: String): String {
        var hasWhitespace = false
        for (i in input.indices) {
            val c = input[i]
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                hasWhitespace = true
                break
            }
        }
        if (!hasWhitespace) return input
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            val c = input[i]
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun validate(input: String, urlSafe: Boolean, maxInputChars: Int) {
        val len = input.length
        if (len == 0) return
        if (len > maxInputChars) {
            throw IllegalArgumentException("Base64 payload exceeds maximum allowed length ($maxInputChars)")
        }
        if (len % 4 == 1) {
            throw IllegalArgumentException("Invalid Base64 length: $len")
        }

        var paddingStarted = false
        var paddingCount = 0

        for (i in 0 until len) {
            val c = input[i]
            if (c == '=') {
                paddingStarted = true
                paddingCount++
                if (paddingCount > 2) {
                    throw IllegalArgumentException("Invalid Base64 padding count")
                }
                continue
            }
            if (paddingStarted) {
                throw IllegalArgumentException("Non-trailing Base64 padding at index $i")
            }
            if (!isValidAlphabetChar(c, urlSafe)) {
                throw IllegalArgumentException("Invalid Base64 character at index $i")
            }
        }

        if (paddingCount > 0 && (len % 4 != 0)) {
            throw IllegalArgumentException("Padded Base64 length must be a multiple of 4")
        }
    }

    private fun isValidAlphabetChar(c: Char, urlSafe: Boolean): Boolean {
        return (c in 'A'..'Z') ||
            (c in 'a'..'z') ||
            (c in '0'..'9') ||
            if (urlSafe) (c == '-' || c == '_') else (c == '+' || c == '/')
    }
}
