package com.buddystudy.backend.mcp.application.model

import com.buddystudy.backend.learningcontext.application.model.LearningContextResponse
import com.buddystudy.backend.profile.application.model.UserProfileResponse

data class McpUserContextResponse(
    val profile: UserProfileResponse,
    val learningContext: LearningContextResponse,
)

data class McpDeletionResponse(
    val deleted: Boolean,
    val studyId: Long,
)
