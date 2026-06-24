package com.buddystuddy.backend.study.application.openai

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

data class OpenAIQuestionKey(
    val apiKey: String,
    val usesSystemFreeQuota: Boolean,
    val user: UserEntity?,
)

@Component
class OpenAIQuestionKeyProvider(
    private val properties: BuddyStuddyProperties,
    private val cipher: KeyCipher,
    private val users: UserPort,
) {
    fun resolveForQuestionGeneration(user: UserEntity?): OpenAIQuestionKey {
        val userApiKey = cipher.decrypt(user?.openaiApiKeyCipher)
        if (!userApiKey.isNullOrBlank()) {
            return OpenAIQuestionKey(userApiKey, usesSystemFreeQuota = false, user = user)
        }

        val systemApiKey = properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")

        val limit = properties.openai.freeQuestionLimit.coerceAtLeast(0)
        if (user == null || user.freeSystemQuestionCount >= limit) {
            throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "Free question limit reached. Add your OpenAI API key to continue.")
        }

        return OpenAIQuestionKey(systemApiKey, usesSystemFreeQuota = true, user = user)
    }

    fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        if (!key.usesSystemFreeQuota) return
        val user = key.user ?: return
        user.freeSystemQuestionCount += 1
        user.updatedAt = now
        users.save(user)
    }
}
