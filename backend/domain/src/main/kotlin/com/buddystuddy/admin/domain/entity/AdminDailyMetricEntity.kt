package com.buddystuddy.admin.domain.entity

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
    name = "admin_daily_metrics",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_admin_daily_metrics_day_key_dimension", columnNames = ["metric_date", "metric_key", "dimension"]),
    ],
    indexes = [
        Index(name = "idx_admin_daily_metrics_key_date", columnList = "metric_key,metric_date"),
        Index(name = "idx_admin_daily_metrics_date", columnList = "metric_date"),
    ],
)
class AdminDailyMetricEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "metric_date", nullable = false)
    var metricDate: LocalDate = LocalDate.now(),
    @Column(name = "metric_key", nullable = false, length = 80)
    var metricKey: String = "",
    @Column(nullable = false, length = 255)
    var dimension: String = "",
    @Column(nullable = false)
    var value: Double = 0.0,
    @Column(name = "sample_count", nullable = false)
    var sampleCount: Long = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
