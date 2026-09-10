package com.nexastream.app.utils

object SportsSecurityUtils {
    var activeSalt: String = "9HY(#b1q6" // Default salt from the code

    private val alphabet = "fFgGjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZaAbBcCdDeEhHiI"
    private val standard = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"

    fun decodeObfuscatedString(input: String): String {
        try {
            // Logic: Reverse -> Base64 -> Character Rotation
            val reversed = input.reversed()
            val decodedBytes = android.util.Base64.decode(reversed, android.util.Base64.DEFAULT)
            val base64Decoded = String(decodedBytes)
            
            return rotateCharacters(base64Decoded)
        } catch (e: Exception) {
            return input
        }
    }

    private fun rotateCharacters(input: String): String {
        val result = StringBuilder()
        for (char in input) {
            val index = alphabet.indexOf(char)
            if (index != -1) {
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
        val expiry = timestamp + 77 // The app uses +77 for expiry offset
        
        // Pattern: domainPart + activeSalt + timestamp
        val input = "$domainPart$activeSalt$timestamp"
        val hash = sha256(input)
        
        return "?token=$hash-$expiry-$timestamp"
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}