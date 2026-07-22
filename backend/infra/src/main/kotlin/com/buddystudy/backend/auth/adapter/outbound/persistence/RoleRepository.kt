package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.RoleEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface RoleRepository : CoroutineCrudRepository<RoleEntity, Long> {
    suspend fun findByCode(code: String): RoleEntity?
}
