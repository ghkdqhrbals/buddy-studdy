package com.buddystudy.backend.settings.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.settings.application.model.StudySettingsResponse
import com.buddystudy.backend.settings.application.model.ScheduleResponse

interface SettingsUseCase {
    suspend fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse
    suspend fun settings(principal: Principal): StudySettingsResponse
    suspend fun studySettings(principal: Principal, studyId: Long): StudySettingsResponse
    suspend fun upsertStudySettings(principal: Principal, studyId: Long, command: ScheduleCommand): ScheduleResponse
}
