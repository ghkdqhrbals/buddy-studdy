package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.RoleEntity
import org.springframework.data.jpa.repository.JpaRepository

interface RoleRepository : JpaRepository<RoleEntity, Long> {
    fun findByCode(code: String): RoleEntity?
}
