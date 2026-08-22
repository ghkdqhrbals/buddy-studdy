package com.buddystudy.backend.learningcontext.application.model

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant

data class LearningContextResponse(
    val resumeMarkdown: String? = null,
    val interests: List<String> = emptyList(),
    val updatedAt: Instant? = null,
)

data class LearningContextPatchCommand(
    val resumeMarkdown: String? = null,
    val interests: List<String>? = null,
)

internal fun UserLearningContextEntity.toResponse() = LearningContextResponse(
    resumeMarkdown = resumeMarkdown,
    interests = decodedInterests(),
    updatedAt = updatedAt,
)

internal fun UserLearningContextEntity.decodedInterests(): List<String> =
    JsonMapperProvider.mapper.readValue(interestsJson)

internal fun encodeInterests(interests: List<String>): String =
    JsonMapperProvider.mapper.writeValueAsString(interests)
