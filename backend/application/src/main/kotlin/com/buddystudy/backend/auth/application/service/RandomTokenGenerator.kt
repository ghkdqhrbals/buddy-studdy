package com.buddystudy.backend.auth.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class RandomTokenGenerator {
    private val random = SecureRandom()

    fun create(prefix: String): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "$prefix-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
