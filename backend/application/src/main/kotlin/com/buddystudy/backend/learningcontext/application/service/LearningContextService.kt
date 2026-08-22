package com.buddystudy.backend.learningcontext.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.learningcontext.application.model.LearningContextResponse
import com.buddystudy.backend.learningcontext.application.model.decodedInterests
import com.buddystudy.backend.learningcontext.application.model.encodeInterests
import com.buddystudy.backend.learningcontext.application.model.toResponse
import com.buddystudy.backend.learningcontext.application.port.inbound.LearningContextUseCase
import com.buddystudy.backend.learningcontext.application.port.outbound.LearningContextPort
import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LearningContextService(
    private val contexts: LearningContextPort,
) : LearningContextUseCase {
    @Transactional(readOnly = true)
    override suspend fun get(principal: Principal): LearningContextResponse =
        contexts.findByUserId(principal.userId)?.toResponse() ?: LearningContextResponse()

    @Transactional
    override suspend fun patch(
        principal: Principal,
        command: LearningContextPatchCommand,
    ): LearningContextResponse {
        val existing = contexts.findByUserId(principal.userId)
        if (command.resumeMarkdown == null && command.interests == null) {
            return existing?.toResponse() ?: LearningContextResponse()
        }

        val resumeMarkdown = if (command.resumeMarkdown == null) {
            existing?.resumeMarkdown
        } else {
            normalizeResume(command.resumeMarkdown)
        }
        val interests = if (command.interests == null) {
            existing?.decodedInterests().orEmpty()
        } else {
            normalizeInterests(command.interests)
        }

        if (resumeMarkdown == null && interests.isEmpty()) {
            if (existing != null) {
                contexts.deleteByUserId(principal.userId)
            }
            return LearningContextResponse()
        }

        val now = Instant.now()
        val entity = existing ?: UserLearningContextEntity(
            userId = principal.userId,
            createdAt = now,
        )
        entity.resumeMarkdown = resumeMarkdown
        entity.interestsJson = encodeInterests(interests)
        entity.updatedAt = now
        return contexts.save(entity).toResponse()
    }

    private fun normalizeResume(value: String): String? {
        val normalized = value.trim().takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > MAX_RESUME_MARKDOWN_LENGTH) {
            throw validation("Resume Markdown must contain at most $MAX_RESUME_MARKDOWN_LENGTH characters.")
        }
        return normalized
    }

    private fun normalizeInterests(values: List<String>): List<String> {
        val unique = linkedMapOf<String, String>()
        values.forEach { value ->
            val normalized = value.trim().replace(WHITESPACE, " ")
            if (normalized.isEmpty()) return@forEach
            if (normalized.length > MAX_INTEREST_LENGTH) {
                throw validation("Each interest must contain at most $MAX_INTEREST_LENGTH characters.")
            }
            unique.putIfAbsent(normalized.lowercase(), normalized)
        }
        if (unique.size > MAX_INTEREST_COUNT) {
            throw validation("Interests must contain at most $MAX_INTEREST_COUNT unique values.")
        }
        return unique.values.toList()
    }

    private fun validation(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private companion object {
        const val MAX_RESUME_MARKDOWN_LENGTH = 50_000
        const val MAX_INTEREST_COUNT = 50
        const val MAX_INTEREST_LENGTH = 100
        val WHITESPACE = Regex("\\s+")
    }
}
