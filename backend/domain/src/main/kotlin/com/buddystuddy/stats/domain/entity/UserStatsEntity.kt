package com.buddystuddy.stats.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(
    name = "user_stats",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_user_stats_day_topic_level", columnNames = ["user_id", "stat_date", "topic_key", "difficulty_level"]),
    ],
    indexes = [
        Index(name = "idx_user_stats_user_date", columnList = "user_id,stat_date"),
        Index(name = "idx_user_stats_user_topic", columnList = "user_id,topic_key"),
    ],
)
class UserStatsEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "stat_date", nullable = false)
    var statDate: LocalDate = LocalDate.now(),
    @Column(name = "topic_key", nullable = false, length = 255)
    var topicKey: String = "",
    @Column(nullable = false, length = 255)
    var topic: String = "",
    @Column(name = "difficulty_level", nullable = false)
    var difficultyLevel: Int = 5,
    @Column(name = "response_count", nullable = false)
    var responseCount: Int = 0,
    @Column(name = "score_count", nullable = false)
    var scoreCount: Int = 0,
    @Column(name = "score_sum", nullable = false)
    var scoreSum: Int = 0,
    @Column(name = "best_score", nullable = false)
    var bestScore: Int = 0,
    @Column(name = "correct_count", nullable = false)
    var correctCount: Int = 0,
    @Column(name = "latest_at", nullable = false)
    var latestAt: Instant = Instant.now(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
