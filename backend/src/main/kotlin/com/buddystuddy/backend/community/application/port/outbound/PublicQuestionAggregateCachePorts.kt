package com.buddystuddy.backend.community.application.port.outbound

import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot

interface PublicQuestionAggregateQueryPort {
    fun findByQuestionId(questionId: Long): PublicQuestionSnapshot?
}

interface PublicQuestionAggregateCommandPort {
    fun save(questionId: Long, snapshot: PublicQuestionSnapshot)
    fun evict(questionId: Long)
}
