package com.buddystudy.backend.availability.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.availability.application.model.AdminServiceMaintenanceOverview
import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.LocalizedMaintenanceContent
import com.buddystudy.backend.availability.application.model.ServiceAvailability
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.inbound.AdminServiceMaintenanceUseCase
import com.buddystudy.backend.availability.application.port.inbound.ServiceAvailabilityUseCase
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class ServiceAvailabilityController(
    private val availability: ServiceAvailabilityWebPort,
) {
    @GetMapping("/service-status")
    suspend fun status(): ServiceAvailability = availability.status()
}

interface ServiceAvailabilityWebPort {
    suspend fun status(): ServiceAvailability
}

@Component
class ServiceAvailabilityWebAdapter(
    private val availability: ServiceAvailabilityUseCase,
) : ServiceAvailabilityWebPort {
    override suspend fun status(): ServiceAvailability =
        availability.availability(LocaleContextHolder.getLocale())
}

@RestController
@RequestMapping("/api/v1/admin/service-maintenance")
class AdminServiceMaintenanceController(
    private val maintenance: AdminServiceMaintenanceWebPort,
) {
    @GetMapping
    suspend fun overview(
        @RequestHeader("Authorization") authorization: String?,
    ): AdminServiceMaintenanceOverview = maintenance.overview(authorization.bearerToken())

    @GetMapping("/history")
    suspend fun history(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ServiceMaintenanceHistoryPage = maintenance.history(authorization.bearerToken(), limit, offset)

    @PostMapping
    suspend fun create(
        @RequestHeader("Authorization") authorization: String?,
        @Valid @RequestBody request: CreateServiceMaintenanceRequest,
    ): ServiceMaintenanceWindow = maintenance.create(authorization.bearerToken(), request)

    @PostMapping("/{id}/terminate")
    suspend fun terminate(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable id: Long,
    ): ServiceMaintenanceWindow = maintenance.terminate(authorization.bearerToken(), id)
}

data class CreateServiceMaintenanceRequest @JsonCreator constructor(
    @field:NotBlank @field:Size(max = 120)
    @param:JsonProperty("titleKo") val titleKo: String = "",
    @field:NotBlank @field:Size(max = 120)
    @param:JsonProperty("titleEn") val titleEn: String = "",
    @field:NotBlank @field:Size(max = 120)
    @param:JsonProperty("titleJa") val titleJa: String = "",
    @field:NotBlank @field:Size(max = 1_000)
    @param:JsonProperty("messageKo") val messageKo: String = "",
    @field:NotBlank @field:Size(max = 1_000)
    @param:JsonProperty("messageEn") val messageEn: String = "",
    @field:NotBlank @field:Size(max = 1_000)
    @param:JsonProperty("messageJa") val messageJa: String = "",
    @param:JsonProperty("startsAt") val startsAt: Instant,
    @param:JsonProperty("endsAt") val endsAt: Instant? = null,
)

interface AdminServiceMaintenanceWebPort {
    suspend fun overview(adminToken: String): AdminServiceMaintenanceOverview
    suspend fun history(adminToken: String, limit: Int, offset: Int): ServiceMaintenanceHistoryPage
    suspend fun create(adminToken: String, request: CreateServiceMaintenanceRequest): ServiceMaintenanceWindow
    suspend fun terminate(adminToken: String, id: Long): ServiceMaintenanceWindow
}

@Component
class AdminServiceMaintenanceWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val maintenance: AdminServiceMaintenanceUseCase,
) : AdminServiceMaintenanceWebPort {
    override suspend fun overview(adminToken: String): AdminServiceMaintenanceOverview {
        authentication.validate(adminToken)
        return maintenance.overview()
    }

    override suspend fun history(
        adminToken: String,
        limit: Int,
        offset: Int,
    ): ServiceMaintenanceHistoryPage {
        authentication.validate(adminToken)
        return maintenance.history(limit, offset)
    }

    override suspend fun create(
        adminToken: String,
        request: CreateServiceMaintenanceRequest,
    ): ServiceMaintenanceWindow {
        authentication.validate(adminToken)
        return maintenance.create(
            CreateServiceMaintenanceCommand(
                content = LocalizedMaintenanceContent(
                    titleKo = request.titleKo.trim(),
                    titleEn = request.titleEn.trim(),
                    titleJa = request.titleJa.trim(),
                    messageKo = request.messageKo.trim(),
                    messageEn = request.messageEn.trim(),
                    messageJa = request.messageJa.trim(),
                ),
                startsAt = request.startsAt,
                endsAt = request.endsAt,
            ),
            actor = "admin",
        )
    }

    override suspend fun terminate(adminToken: String, id: Long): ServiceMaintenanceWindow {
        authentication.validate(adminToken)
        return maintenance.terminate(id, "admin")
    }
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
