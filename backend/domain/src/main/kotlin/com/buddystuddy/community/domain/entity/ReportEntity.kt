package com.buddystuddy.community.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "reports")
class ReportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "question_id")
    var questionId: Long? = null,
    @Column(name = "reporter_device_id", length = 191)
    var reporterDeviceId: String? = null,
    @Column(name = "reporter_user_id")
    var reporterUserId: Long? = null,
    @Column(nullable = false, length = 120)
    var reason: String = "",
    @Column(nullable = false, length = 1000)
    var message: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
