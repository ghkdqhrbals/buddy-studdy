package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.FeedbackPort
import com.buddystudy.community.domain.entity.FeedbackEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface FeedbackRepository : CoroutineCrudRepository<FeedbackEntity, Long>

@Component
class FeedbackPersistenceAdapter(
    private val repository: FeedbackRepository,
) : FeedbackPort {
    override suspend fun save(entity: FeedbackEntity): FeedbackEntity = repository.save(entity)
}
