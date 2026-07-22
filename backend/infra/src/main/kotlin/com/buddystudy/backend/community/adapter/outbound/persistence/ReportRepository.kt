package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.community.domain.entity.ReportEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface ReportRepository : CoroutineCrudRepository<ReportEntity, Long>

@Component
class ReportPersistenceAdapter(private val repository: ReportRepository) : ReportPort {
    override suspend fun save(entity: ReportEntity) = repository.save(entity)
}
