package com.buddystudy.backend.availability.application.service

import com.buddystudy.backend.availability.application.model.AdminServiceMaintenanceOverview
import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.ServiceAvailability
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.inbound.AdminServiceMaintenanceUseCase
import com.buddystudy.backend.availability.application.port.inbound.ServiceAvailabilityUseCase
import com.buddystudy.backend.availability.application.port.outbound.ServiceMaintenancePort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@Service
class ServiceMaintenanceService(
    private val maintenance: ServiceMaintenancePort,
) : ServiceAvailabilityUseCase, AdminServiceMaintenanceUseCase {
    private val cache = AtomicReference<CachedMaintenance?>()
    private val cacheMutex = Mutex()

    override suspend fun availability(locale: Locale): ServiceAvailability {
        val now = Instant.now()
        val active = activeMaintenance(now)
        if (active == null) {
            return ServiceAvailability(status = "OPERATIONAL", checkedAt = now)
        }
        val content = active.content.forLocale(locale)
        return ServiceAvailability(
            status = "MAINTENANCE",
            maintenanceId = active.id,
            title = content.title,
            message = content.message,
            startsAt = active.startsAt,
            endsAt = active.endsAt,
            retryAfterSeconds = retryAfterSeconds(active.endsAt, now),
            checkedAt = now,
        )
    }

    override suspend fun activeMaintenance(): ServiceMaintenanceWindow? =
        activeMaintenance(Instant.now())

    @Transactional(readOnly = true)
    override suspend fun overview(): AdminServiceMaintenanceOverview {
        val now = Instant.now()
        return AdminServiceMaintenanceOverview(
            current = maintenance.activeAt(now),
            upcoming = maintenance.upcomingAt(now, UPCOMING_LIMIT),
            checkedAt = now,
        )
    }

    @Transactional(readOnly = true)
    override suspend fun history(limit: Int, offset: Int): ServiceMaintenanceHistoryPage =
        maintenance.history(limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    @Transactional
    override suspend fun create(
        command: CreateServiceMaintenanceCommand,
        actor: String,
    ): ServiceMaintenanceWindow {
        validate(command)
        if (maintenance.hasOverlap(command.startsAt, command.endsAt)) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.VALIDATION_ERROR,
                "The maintenance window overlaps an existing active or scheduled window.",
            )
        }
        val created = maintenance.create(command, actor.sanitizedActor(), Instant.now())
        cache.set(null)
        return created
    }

    @Transactional
    override suspend fun terminate(id: Long, actor: String): ServiceMaintenanceWindow {
        val terminated = maintenance.terminate(id, actor.sanitizedActor(), Instant.now())
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Active or scheduled maintenance window was not found.",
            )
        cache.set(null)
        return terminated
    }

    private suspend fun activeMaintenance(now: Instant): ServiceMaintenanceWindow? {
        cache.get()?.takeIf { Duration.between(it.loadedAt, now) < CACHE_TTL }?.let {
            return it.window
        }
        return cacheMutex.withLock {
            val cached = cache.get()?.takeIf { Duration.between(it.loadedAt, now) < CACHE_TTL }
            if (cached != null) {
                cached.window
            } else {
                maintenance.activeAt(now).also { cache.set(CachedMaintenance(now, it)) }
            }
        }
    }

    private fun validate(command: CreateServiceMaintenanceCommand) {
        if (command.endsAt != null && !command.endsAt.isAfter(command.startsAt)) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Maintenance end time must be after the start time.",
            )
        }
        val values = listOf(
            command.content.titleKo to MAX_TITLE_LENGTH,
            command.content.titleEn to MAX_TITLE_LENGTH,
            command.content.titleJa to MAX_TITLE_LENGTH,
            command.content.messageKo to MAX_MESSAGE_LENGTH,
            command.content.messageEn to MAX_MESSAGE_LENGTH,
            command.content.messageJa to MAX_MESSAGE_LENGTH,
        )
        if (values.any { (value, max) -> value.isBlank() || value.length > max }) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Maintenance titles and messages are required and exceed the allowed length.",
            )
        }
    }

    private fun retryAfterSeconds(endsAt: Instant?, now: Instant): Long =
        endsAt?.let { Duration.between(now, it).seconds.coerceIn(15, 300) } ?: 60

    private fun String.sanitizedActor(): String = trim().ifEmpty { "admin" }.take(100)

    private data class CachedMaintenance(
        val loadedAt: Instant,
        val window: ServiceMaintenanceWindow?,
    )

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(5)
        const val UPCOMING_LIMIT = 20
        const val MAX_TITLE_LENGTH = 120
        const val MAX_MESSAGE_LENGTH = 1_000
    }
}
