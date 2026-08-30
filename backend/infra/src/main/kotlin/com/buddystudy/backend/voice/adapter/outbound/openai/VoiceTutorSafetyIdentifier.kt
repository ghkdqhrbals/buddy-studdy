package com.buddystudy.backend.voice.adapter.outbound.openai

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object VoiceTutorSafetyIdentifier {
    private const val ALGORITHM = "HmacSHA256"

    fun create(userId: Long, secret: String): String {
        require(secret.isNotBlank()) { "A server OpenAI API key is required for the safety identifier." }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM))
        return mac.doFinal("buddystudy:$userId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
