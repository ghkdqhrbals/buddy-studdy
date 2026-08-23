package com.buddystudy.learningcontext.domain.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_learning_contexts")
class UserLearningContextEntity(
    @Id
    var userId: Long = 0,
    var resumeMarkdown: String? = null,
    var interestsJson: String = "[]",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
