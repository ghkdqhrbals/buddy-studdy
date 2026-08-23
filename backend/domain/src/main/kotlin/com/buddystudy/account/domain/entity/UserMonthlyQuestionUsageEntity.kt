package com.buddystudy.account.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_monthly_question_usage")
class UserMonthlyQuestionUsageEntity(
    @Id
    var id: Long = 0,
    var userId: Long = 0,
    @Column("usage_month")
    var usageMonth: String = "",
    var periodStart: Instant = Instant.now(),
    var systemQuestionCount: Int = 0,
    var currentPeriodQuestionLimitOverride: Int? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
