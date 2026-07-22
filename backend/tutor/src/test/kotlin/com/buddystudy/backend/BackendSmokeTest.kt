package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

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
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class BackendSmokeTest : PostgresIntegrationTestSupport() {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `application starts with a reactive web server`(): Unit = runBlocking {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext::class.java)
        assertThat(runCatching { Class.forName("org.springframework.web.servlet.DispatcherServlet") }.isFailure).isTrue()
    }

    @Test
    fun `register creates anonymous user and access token with device id`(): Unit = runBlocking {
        val response = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        assertThat(response.deviceId).startsWith("dev-")
        assertThat(response.clientSecret).startsWith("sec-")
        assertThat(response.accessToken).contains(".")
    }
}
