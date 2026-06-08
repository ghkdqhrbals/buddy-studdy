package com.buddystuddy.backend.settings.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.BackendSettingsResponse
import com.buddystuddy.backend.dto.ScheduleRequest
import com.buddystuddy.backend.dto.ScheduleResponse

interface SettingsUseCase {
    fun upsertSchedule(principal: Principal, payload: ScheduleRequest): ScheduleResponse
    fun settings(principal: Principal): BackendSettingsResponse
}
