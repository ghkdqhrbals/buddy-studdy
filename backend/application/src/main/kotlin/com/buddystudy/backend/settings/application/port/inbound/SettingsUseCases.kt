package com.buddystudy.backend.settings.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.settings.application.model.StudySettingsResponse
import com.buddystudy.backend.settings.application.model.ScheduleResponse

interface SettingsUseCase {
    fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse
    fun settings(principal: Principal): StudySettingsResponse
    fun studySettings(principal: Principal, studyId: Long): StudySettingsResponse
    fun upsertStudySettings(principal: Principal, studyId: Long, command: ScheduleCommand): ScheduleResponse
}
