package com.buddystuddy.backend.community.adapter.outbound

import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateCommandPort
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateQueryPort
import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Primary
@Component
class PublicQuestionAggregatePrimaryAdapter(
    @param:Qualifier("publicQuestionAggregateRedisAdapter")
    private val redisQueryPort: PublicQuestionAggregateQueryPort,
    @param:Qualifier("publicQuestionAggregateRedisAdapter")
    private val redisCommandPort: PublicQuestionAggregateCommandPort,
    @param:Qualifier("publicQuestionAggregateRdbAdapter")
    private val rdbQueryPort: PublicQuestionAggregateQueryPort,
) : PublicQuestionAggregateQueryPort {
    override fun findByQuestionId(questionId: Long): PublicQuestionSnapshot? =
        redisQueryPort.findByQuestionId(questionId)
            ?: rdbQueryPort.findByQuestionId(questionId)?.also {
                redisCommandPort.save(questionId, it)
            }
}
