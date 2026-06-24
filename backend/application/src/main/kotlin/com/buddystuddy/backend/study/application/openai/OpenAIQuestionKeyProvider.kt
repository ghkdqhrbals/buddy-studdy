package com.buddystuddy.backend.study.application.openai

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.study.application.port.outbound.QuestionMembershipPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

data class OpenAIQuestionKey(
    val apiKey: String,
    val usesSystemMembershipQuota: Boolean,
    val user: UserEntity?,
)

enum class QuestionMembershipTier(val monthlyQuestionLimit: Int) {
    TIER0(0),
    TIER1(30),
    TIER2(1000),
    TIER3(3000),
    ;

    companion object {
        fun fromDb(code: String?): QuestionMembershipTier =
            entries.firstOrNull { it.name == code } ?: TIER1
    }
}

@Component
class OpenAIQuestionKeyProvider(
    private val properties: BuddyStuddyProperties,
    private val cipher: KeyCipher,
    private val memberships: QuestionMembershipPort,
) {
    fun resolveForQuestionGeneration(user: UserEntity?): OpenAIQuestionKey {
        val userApiKey = cipher.decrypt(user?.openaiApiKeyCipher)
        if (!userApiKey.isNullOrBlank()) {
            return OpenAIQuestionKey(userApiKey, usesSystemMembershipQuota = false, user = user)
        }

        val systemApiKey = properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")

        if (user == null) {
            throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "Monthly question limit reached. Add your OpenAI API key to continue.")
        }

        val tier = QuestionMembershipTier.fromDb(memberships.activeTierCodeForUser(user.id))
        val consumed = memberships.tryConsumeMonthlySystemQuestion(
            userId = user.id,
            yearMonth = YearMonth.now(ZoneOffset.UTC),
            limit = tier.monthlyQuestionLimit,
            now = Instant.now(),
        )
        if (!consumed) {
            throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "Monthly question limit reached. Add your OpenAI API key to continue.")
        }

        return OpenAIQuestionKey(systemApiKey, usesSystemMembershipQuota = true, user = user)
    }

    fun markQuestionCreated(key: OpenAIQuestionKey, now: Instant = Instant.now()) {
        if (!key.usesSystemMembershipQuota) return
    }
}
