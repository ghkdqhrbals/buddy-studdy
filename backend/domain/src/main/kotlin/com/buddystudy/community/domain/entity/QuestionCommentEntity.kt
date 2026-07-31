package com.buddystudy.community.domain.entity

import com.buddystudy.common.domain.SupportedLanguage
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
    var sourceLanguage: SupportedLanguage = SupportedLanguage.KOREAN,
    var deletedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
