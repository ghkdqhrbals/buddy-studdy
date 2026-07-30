package com.buddystudy.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.sha256
import com.buddystudy.backend.auth.adapter.outbound.persistence.DeviceRepository
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
class BackendSmokeTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var devices: DeviceRepository
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

    @Test
    fun `concurrent registration for one installation reuses one device`(): Unit = runBlocking {
        val installationId = "concurrent-installation-id-for-registration"
        val responses = coroutineScope {
            List(8) { index ->
                async(Dispatchers.IO) {
                    login.register(
                        RegisterDeviceCommand(
                            installationId = installationId,
                            apnsToken = "token-$index",
                            language = "ko",
                        )
                    )
                }
            }.awaitAll()
        }

        assertThat(responses.map { it.deviceId }.distinct()).hasSize(1)
        assertThat(devices.findByInstallationKeyHash(sha256(installationId))?.deviceId)
            .isEqualTo(responses.first().deviceId)
    }
}
