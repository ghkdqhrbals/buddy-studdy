package com.buddystuddy.backend.community.application.port.outbound

interface PublicQuestionReactionPublishPort {
    fun publishViewed(questionId: Long, userId: Long?): Boolean
    fun publishLiked(questionId: Long, userId: Long): Boolean
    fun publishUnliked(questionId: Long, userId: Long): Boolean
    fun publishCommented(questionId: Long, userId: Long): Boolean
}
