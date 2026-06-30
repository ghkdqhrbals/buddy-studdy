package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.PermissionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface PermissionRepository : JpaRepository<PermissionEntity, Long> {
    fun findByCode(code: String): PermissionEntity?
}
