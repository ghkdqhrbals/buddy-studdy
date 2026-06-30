package com.buddystudy.backend.crypto

import com.buddystudy.backend.config.BuddyStudyProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class KeyCipher(private val properties: BuddyStudyProperties) {
    private val random = SecureRandom()
    private val key: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(properties.crypto.masterKey.toByteArray())
        SecretKeySpec(digest, "AES")
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrBlank()) return null
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }

    fun decrypt(cipherText: String?): String? {
        if (cipherText.isNullOrBlank()) return null
        val parts = cipherText.split(":")
        if (parts.size != 3 || parts[0] != "v1") return null
        val iv = Base64.getUrlDecoder().decode(parts[1])
        val encrypted = Base64.getUrlDecoder().decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
