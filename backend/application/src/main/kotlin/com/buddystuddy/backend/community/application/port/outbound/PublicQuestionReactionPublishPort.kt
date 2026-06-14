package com.buddystuddy.backend.community.application.port.outbound

interface PublicQuestionReactionPublishPort {
    fun publishViewed(questionId: Long, userId: Long?): Boolean
}
