package com.buddystuddy.backend.settings.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.settings.application.model.StudySettingsResponse
import com.buddystuddy.backend.settings.application.model.ScheduleResponse

interface SettingsUseCase {
    fun upsertSchedule(principal: Principal, command: ScheduleCommand): ScheduleResponse
    fun settings(principal: Principal): StudySettingsResponse
    fun studySettings(principal: Principal, studyId: Long): StudySettingsResponse
    fun upsertStudySettings(principal: Principal, studyId: Long, command: ScheduleCommand): ScheduleResponse
}
