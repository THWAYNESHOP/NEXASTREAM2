package com.nexastream.app.utils

object SportsSecurityUtils {
    var activeSalt: String = "9HY(#b1q6" // Default salt from the code

    private val alphabet = "fFgGjJkKlLaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYsS"
    private val standard = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"

    fun decodeObfuscatedString(input: String): String {
        try {
            // Logic: Character Rotation -> Base64 Decode
            val rotated = rotateCharacters(input)
            val decodedBytes = android.util.Base64.decode(rotated, android.util.Base64.DEFAULT)
            return String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return input
        }
    }

    fun decodeBase64(input: String): String {
        return try {
            val decodedBytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun rotateCharacters(input: String): String {
        val result = StringBuilder()
        for (char in input) {
            val index = alphabet.indexOf(char)
            if (index != -1 && index < standard.length) {
                result.append(standard[index])
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }

    fun getDomainSegment(url: String): String {
        try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return "unknown"
            // Extract the domain part (e.g., "streamed" from "streamed.st")
            return host.substringBefore(".")
        } catch (e: Exception) {
            return "unknown"
        }
    }

    fun generateToken(domainPart: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val expiry = timestamp + 3600 // Increased expiry to 1 hour
        
        // Pattern: domainPart + activeSalt + timestamp + expiry
        val input = "$domainPart$activeSalt$timestamp$expiry"
        val hash = sha256(input)
        
        return "?token=$hash-$expiry-$timestamp"
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
