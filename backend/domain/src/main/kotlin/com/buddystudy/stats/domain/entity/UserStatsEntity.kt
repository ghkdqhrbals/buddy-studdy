package com.buddystudy.stats.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate

@Table("user_stats")
class UserStatsEntity(
    @Id
    var id: Long = 0,
    var userId: Long = 0,
    var statDate: LocalDate = LocalDate.now(),
    var topicKey: String = "",
    var topic: String = "",
    var difficultyLevel: Int = 5,
    var responseCount: Int = 0,
    var scoreCount: Int = 0,
    var scoreSum: Int = 0,
    var bestScore: Int = 0,
    var correctCount: Int = 0,
    var latestAt: Instant = Instant.now(),
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
