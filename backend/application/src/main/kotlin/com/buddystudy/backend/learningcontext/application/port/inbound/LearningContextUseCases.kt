package com.buddystudy.backend.learningcontext.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.learningcontext.application.model.LearningContextResponse

interface LearningContextUseCase {
    suspend fun get(principal: Principal): LearningContextResponse
    suspend fun patch(principal: Principal, command: LearningContextPatchCommand): LearningContextResponse
}
