package com.buddystudy.community.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("question_comments")
class QuestionCommentEntity(
    @Id
    var id: Long = 0,
    var questionId: Long = 0,
    var userId: Long = 0,
    var body: String = "",
    var deletedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
