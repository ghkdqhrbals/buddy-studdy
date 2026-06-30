package com.buddystudy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "question_stats")
class QuestionStatsEntity(
    @Id
    @Column(name = "question_id")
    var questionId: Long = 0,
    @Column(name = "like_count", nullable = false)
    var likeCount: Int = 0,
    @Column(name = "comment_count", nullable = false)
    var commentCount: Int = 0,
    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0,
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
