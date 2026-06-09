package com.buddystuddy.backend

import com.buddystuddy.backend.settings.adapter.inbound.web.dto.ScheduleRequest
import com.buddystuddy.backend.settings.application.model.StudySettingsResponse
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.study.domain.entity.ScheduleEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultQuestionPublicityTest {
    @Test
    fun `new settings default questions to public`() {
        assertThat(ScheduleRequest().isQuestionPublic).isTrue()
        assertThat(ScheduleCommand().isQuestionPublic).isTrue()
        assertThat(StudySettingsResponse().isQuestionPublic).isTrue()
        assertThat(ScheduleEntity().questionPublic).isTrue()
        assertThat(StudyEntity().questionPublic).isTrue()
    }
}
