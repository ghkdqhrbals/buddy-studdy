package com.buddystudy.account.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "user_membership_tiers")
class UserMembershipTierEntity(
    @Id
    @Column(name = "tier_code", nullable = false, length = 32)
    var tierCode: String = "",
    @Column(name = "monthly_question_limit", nullable = false)
    var monthlyQuestionLimit: Int = 0,
    @Column(nullable = false, length = 255)
    var description: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
