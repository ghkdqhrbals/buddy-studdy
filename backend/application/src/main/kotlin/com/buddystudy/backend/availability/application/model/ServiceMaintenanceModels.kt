package com.buddystudy.backend.availability.application.model

import java.time.Instant
import java.util.Locale

enum class ServiceMaintenanceState {
    SCHEDULED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

data class LocalizedMaintenanceContent(
    val titleKo: String,
    val titleEn: String,
    val titleJa: String,
    val messageKo: String,
    val messageEn: String,
    val messageJa: String,
) {
    fun forLocale(locale: Locale): ServiceMaintenanceContent {
        val language = locale.language.lowercase()
        return when (language) {
            "ko" -> ServiceMaintenanceContent(titleKo, messageKo)
            "ja" -> ServiceMaintenanceContent(titleJa, messageJa)
            else -> ServiceMaintenanceContent(titleEn, messageEn)
        }
    }
}

data class ServiceMaintenanceContent(
    val title: String,
    val message: String,
)

data class ServiceMaintenanceWindow(
    val id: Long,
    val content: LocalizedMaintenanceContent,
    val startsAt: Instant,
    val endsAt: Instant?,
    val terminatedAt: Instant?,
    val createdBy: String,
    val terminatedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun stateAt(now: Instant): ServiceMaintenanceState =
        when {
            terminatedAt != null && terminatedAt.isBefore(startsAt) -> ServiceMaintenanceState.CANCELLED
            terminatedAt != null -> ServiceMaintenanceState.COMPLETED
            startsAt.isAfter(now) -> ServiceMaintenanceState.SCHEDULED
            endsAt != null && !endsAt.isAfter(now) -> ServiceMaintenanceState.COMPLETED
            else -> ServiceMaintenanceState.ACTIVE
        }
}

data class CreateServiceMaintenanceCommand(
    val content: LocalizedMaintenanceContent,
    val startsAt: Instant,
    val endsAt: Instant?,
)

data class ServiceMaintenanceHistoryPage(
    val items: List<ServiceMaintenanceWindow>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)

data class ServiceAvailability(
    val status: String,
    val maintenanceId: Long? = null,
    val title: String? = null,
    val message: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val retryAfterSeconds: Long? = null,
    val checkedAt: Instant,
)

data class AdminServiceMaintenanceOverview(
    val current: ServiceMaintenanceWindow?,
    val upcoming: List<ServiceMaintenanceWindow>,
    val checkedAt: Instant,
)
