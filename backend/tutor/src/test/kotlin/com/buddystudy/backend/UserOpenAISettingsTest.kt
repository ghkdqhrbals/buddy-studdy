package com.buddystudy.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

import com.buddystudy.backend.admin.application.service.AdminService
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.service.LoginService
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystudy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystudy.backend.settings.application.service.SettingsService
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.study.domain.entity.QuestionEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class UserOpenAISettingsTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var settings: SettingsService
    @Autowired lateinit var admin: AdminService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var cipher: KeyCipher

    @Test
    fun `openai key is stored on user while model is read from study schedule`(): Unit = runBlocking {
        val registered = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val principal = login.authenticateDevice(registered.deviceId, registered.clientSecret)
        val otherDevicePrincipal = Principal(
            userId = principal.userId,
            deviceId = "another-device",
            sessionId = principal.sessionId,
            anonymous = principal.anonymous,
        )

        settings.upsertSchedule(
            principal,
            ScheduleCommand(
                topic = "SwiftUI",
                intervalMinutes = 10,
                enabled = true,
                openaiApiKey = "sk-test-user-key",
                openaiModel = "gpt-5.2",
                appLanguage = "en",
                schedules = listOf(ScheduleItemCommand(topic = "SwiftUI", openaiModel = "gpt-5.4")),
            ),
        )

        val user = users.findAll().first { it.id == principal.userId }
        val study = studies.findByUserIdAndTopic(principal.userId, "SwiftUI")

        assertThat(cipher.decrypt(user.openaiApiKeyCipher)).isEqualTo("sk-test-user-key")
        assertThat(user.appLanguage).isEqualTo("en")
        assertThat(study?.openaiModel).isEqualTo("gpt-5.4")
        assertThat(admin.apiStatus(otherDevicePrincipal).openaiKeyConfigured).isTrue()
        assertThat(admin.apiStatus(otherDevicePrincipal).openaiModel).isEqualTo("gpt-5.4")
        assertThat(settings.settings(otherDevicePrincipal).openaiKeyConfigured).isTrue()
        assertThat(settings.settings(otherDevicePrincipal).openaiModel).isEqualTo("gpt-5.4")
        assertThat(settings.settings(otherDevicePrincipal).appLanguage).isEqualTo("en")
    }

    @Test
    fun `study owns generated questions through study id`(): Unit = runBlocking {
        val registered = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val principal = login.authenticateDevice(registered.deviceId, registered.clientSecret)

        settings.upsertSchedule(
            principal,
            ScheduleCommand(
                topic = "Kotlin",
                intervalMinutes = 10,
                enabled = true,
                openaiApiKey = "sk-test-user-key",
                schedules = listOf(
                    ScheduleItemCommand(topic = "Kotlin", openaiModel = "gpt-5.4"),
                    ScheduleItemCommand(topic = "SwiftUI", openaiModel = "gpt-5.4"),
                ),
            ),
        )

        val kotlinStudy = studies.findByUserIdAndTopic(principal.userId, "Kotlin")!!
        val swiftStudy = studies.findByUserIdAndTopic(principal.userId, "SwiftUI")!!

        questions.save(
            QuestionEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                studyId = kotlinStudy.id,
                question = "What is a Kotlin data class?",
                topic = kotlinStudy.topic,
                difficultyLevel = kotlinStudy.difficultyLevel,
            ),
        )

        assertThat(questions.countPendingForStudy(kotlinStudy.id)).isEqualTo(1)
        assertThat(questions.countPendingForStudy(swiftStudy.id)).isZero()
    }
}
