package com.buddystuddy.backend.auth.application.service

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64

@Service
class RandomTokenService {
    private val random = SecureRandom()

    fun create(prefix: String): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "$prefix-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
