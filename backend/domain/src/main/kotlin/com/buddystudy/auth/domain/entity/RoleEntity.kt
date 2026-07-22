package com.buddystudy.auth.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("roles")
class RoleEntity(
    @Id
    var id: Long = 0,
    var code: String = "",
    var name: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
