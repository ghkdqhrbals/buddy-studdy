package com.buddystuddy.backend

import com.buddystuddy.backend.admin.application.service.AdminService
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystuddy.backend.auth.application.service.LoginService
import com.buddystuddy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.service.SettingsService
import com.buddystuddy.backend.study.adapter.outbound.persistence.ScheduleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-user-openai-settings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class UserOpenAISettingsTest {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var settings: SettingsService
    @Autowired lateinit var admin: AdminService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var schedules: ScheduleRepository
    @Autowired lateinit var cipher: KeyCipher

    @Test
    fun `openai key and model are stored on user and read independently from device id`() {
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
                schedules = listOf(ScheduleItemCommand(topic = "SwiftUI", openaiModel = "gpt-5.4")),
            ),
        )

        val user = users.findAll().first { it.id == principal.userId }
        val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, "SwiftUI")

        assertThat(cipher.decrypt(user.openaiApiKeyCipher)).isEqualTo("sk-test-user-key")
        assertThat(user.openaiModel).isEqualTo("gpt-5.2")
        assertThat(schedule?.openaiApiKeyCipher).isNull()
        assertThat(admin.apiStatus(otherDevicePrincipal).openaiKeyConfigured).isTrue()
        assertThat(admin.apiStatus(otherDevicePrincipal).openaiModel).isEqualTo("gpt-5.2")
        assertThat(settings.settings(otherDevicePrincipal).openaiKeyConfigured).isTrue()
        assertThat(settings.settings(otherDevicePrincipal).openaiModel).isEqualTo("gpt-5.2")
    }
}
