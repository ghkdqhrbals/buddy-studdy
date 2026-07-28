package com.buddystudy.community.domain.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("feedbacks")
class FeedbackEntity(
    @Id
    var id: Long = 0,
    var userId: Long? = null,
    var deviceId: String? = null,
    var content: String = "",
    var createdAt: Instant = Instant.now(),
)
