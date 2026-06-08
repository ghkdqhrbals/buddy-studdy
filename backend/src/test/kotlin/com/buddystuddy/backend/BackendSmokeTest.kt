package com.buddystuddy.backend

import com.buddystuddy.backend.auth.application.service.LoginService
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class BackendSmokeTest {
    @Autowired lateinit var login: LoginService

    @Test
    fun `register creates anonymous user and access token with device id`() {
        val response = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        assertThat(response.deviceId).startsWith("dev-")
        assertThat(response.clientSecret).startsWith("sec-")
        assertThat(response.accessToken).contains(".")
    }
}
