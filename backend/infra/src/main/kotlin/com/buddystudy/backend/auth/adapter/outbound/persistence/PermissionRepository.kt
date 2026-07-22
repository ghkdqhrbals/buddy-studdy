package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.PermissionEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface PermissionRepository : CoroutineCrudRepository<PermissionEntity, Long> {
    suspend fun findByCode(code: String): PermissionEntity?
}
