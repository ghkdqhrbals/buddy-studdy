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
    var yearMonth: String = "",
    var systemQuestionCount: Int = 0,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
