package com.buddystudy.community.domain.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_blocks")
class UserBlockEntity(
    @Id
    var id: Long = 0,
    var blockerUserId: Long = 0,
    var blockedUserId: Long = 0,
    var createdAt: Instant = Instant.now(),
)
