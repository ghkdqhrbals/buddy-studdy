package com.buddystudy.community.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("reports")
class ReportEntity(
    @Id
    var id: Long = 0,
    var questionId: Long? = null,
    var reporterDeviceId: String? = null,
    var reporterUserId: Long? = null,
    var reason: String = "",
    var message: String = "",
    var createdAt: Instant = Instant.now(),
)
