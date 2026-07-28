package com.buddystudy.backend.availability.application.port.outbound

import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import java.time.Instant

interface ServiceMaintenancePort {
    suspend fun activeAt(now: Instant): ServiceMaintenanceWindow?
    suspend fun upcomingAt(now: Instant, limit: Int): List<ServiceMaintenanceWindow>
    suspend fun history(limit: Int, offset: Int): ServiceMaintenanceHistoryPage
    suspend fun hasOverlap(startsAt: Instant, endsAt: Instant?): Boolean
    suspend fun create(
        command: CreateServiceMaintenanceCommand,
        actor: String,
        now: Instant,
    ): ServiceMaintenanceWindow
    suspend fun terminate(id: Long, actor: String, now: Instant): ServiceMaintenanceWindow?
}
