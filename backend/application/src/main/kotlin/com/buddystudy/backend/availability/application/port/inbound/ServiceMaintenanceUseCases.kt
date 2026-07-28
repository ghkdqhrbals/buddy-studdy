package com.buddystudy.backend.availability.application.port.inbound

import com.buddystudy.backend.availability.application.model.AdminServiceMaintenanceOverview
import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.ServiceAvailability
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import java.util.Locale

interface ServiceAvailabilityUseCase {
    suspend fun availability(locale: Locale): ServiceAvailability
    suspend fun activeMaintenance(): ServiceMaintenanceWindow?
}

interface AdminServiceMaintenanceUseCase {
    suspend fun overview(): AdminServiceMaintenanceOverview
    suspend fun history(limit: Int, offset: Int): ServiceMaintenanceHistoryPage
    suspend fun create(command: CreateServiceMaintenanceCommand, actor: String): ServiceMaintenanceWindow
    suspend fun terminate(id: Long, actor: String): ServiceMaintenanceWindow
}
