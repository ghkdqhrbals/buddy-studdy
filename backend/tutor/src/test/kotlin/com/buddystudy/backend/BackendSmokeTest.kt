package com.buddystudy.backend

import com.buddystudy.backend.auth.application.service.LoginService
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystudy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class BackendSmokeTest {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `application starts with a reactive web server`() {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext::class.java)
        assertThat(runCatching { Class.forName("org.springframework.web.servlet.DispatcherServlet") }.isFailure).isTrue()
    }

    @Test
    fun `register creates anonymous user and access token with device id`() {
        val response = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        assertThat(response.deviceId).startsWith("dev-")
        assertThat(response.clientSecret).startsWith("sec-")
        assertThat(response.accessToken).contains(".")
    }
}
