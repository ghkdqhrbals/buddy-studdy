package com.buddystudy.account.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "user_monthly_question_usage",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_user_monthly_question_usage_user_month", columnNames = ["user_id", "year_month"]),
    ],
    indexes = [
        Index(name = "idx_user_monthly_question_usage_user", columnList = "user_id"),
    ],
)
class UserMonthlyQuestionUsageEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "year_month", nullable = false, length = 7)
    var yearMonth: String = "",
    @Column(name = "system_question_count", nullable = false)
    var systemQuestionCount: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
