package com.buddystudy.backend.community.application.port.outbound

interface PublicQuestionReactionPublishPort {
    suspend fun publishViewed(questionId: Long, userId: Long?): Boolean
}
