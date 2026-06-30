package com.buddystudy.backend

import com.buddystudy.study.domain.StudyRoom
import com.buddystudy.study.domain.StudyRoomSchedule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultQuestionPublicityTest {
    @Test
    fun `new questions default to public without study visibility settings`() {
        val room = StudyRoom.of(
            StudyRoomSchedule(
                id = 1,
                deviceId = "device-1",
                userId = 2,
                topic = "SwiftUI",
                difficultyLevel = 5,
                openaiModel = "gpt-5.4",
                appLanguage = "ko",
                customPrompt = "",
            ),
            pendingCount = 0,
        )

        assertThat(room.createQuestion("Question?", null, "manual").publicQuestion).isTrue()
    }
}
