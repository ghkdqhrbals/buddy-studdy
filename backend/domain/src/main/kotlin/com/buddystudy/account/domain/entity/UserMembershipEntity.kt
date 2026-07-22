package com.buddystudy.account.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_memberships")
class UserMembershipEntity(
    @Id
    var id: Long = 0,
    var userId: Long = 0,
    var tier: String = "TIER1",
    var status: String = "ACTIVE",
    var startedAt: Instant = Instant.now(),
    var expiresAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
