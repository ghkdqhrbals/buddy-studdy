package com.buddystudy.account.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_membership_tiers")
class UserMembershipTierEntity(
    @Id
    var tierCode: String = "",
    var monthlyQuestionLimit: Int = 0,
    var adFree: Boolean = false,
    var description: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
