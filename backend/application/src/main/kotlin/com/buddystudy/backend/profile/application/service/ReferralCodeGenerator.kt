package com.buddystudy.backend.profile.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

fun interface ReferralCodeGenerator {
    fun next(): String
}

@Component
class SecureReferralCodeGenerator : ReferralCodeGenerator {
    private val random = SecureRandom()

    override fun next(): String = buildString(PREFIX.length + LENGTH) {
        append(PREFIX)
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private companion object {
        const val PREFIX = "BS-"
        const val LENGTH = 8
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
