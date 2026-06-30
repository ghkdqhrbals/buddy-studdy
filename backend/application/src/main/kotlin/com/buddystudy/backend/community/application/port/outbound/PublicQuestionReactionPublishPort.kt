package com.buddystudy.backend.community.application.port.outbound

interface PublicQuestionReactionPublishPort {
    fun publishViewed(questionId: Long, userId: Long?): Boolean
}
