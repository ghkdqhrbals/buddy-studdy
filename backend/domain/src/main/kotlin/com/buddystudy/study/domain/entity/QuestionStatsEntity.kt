package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("question_stats")
class QuestionStatsEntity(
    @Id
    var questionId: Long = 0,
    var likeCount: Int = 0,
    var commentCount: Int = 0,
    var viewCount: Int = 0,
    var verifiedAt: Instant? = null,
    var updatedAt: Instant = Instant.now(),
)
