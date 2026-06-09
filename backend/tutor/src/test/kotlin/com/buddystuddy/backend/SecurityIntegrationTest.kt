package com.buddystuddy.backend

import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystuddy.backend.auth.application.service.LoginService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
@ExtendWith(OutputCaptureExtension::class)
class SecurityIntegrationTest {
    @Autowired lateinit var login: LoginService
    @LocalServerPort var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `public endpoints are reachable without an access token`() {
        assertThat(get("/health").statusCode()).isEqualTo(200)
        assertThat(get("/api/v1/public/questions").statusCode()).isEqualTo(200)
    }

    @Test
    fun `protected endpoints return unified auth error without access token`() {
        val response = get("/api/v1/me/profile")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_ACCESS_TOKEN_REQUIRED")
        assertThat(response.body()).contains("Access token is required.")
    }

    @Test
    fun `invalid bearer token is rejected before controller execution`(output: CapturedOutput) {
        val response = get("/api/v1/me/profile", "not-a-token")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(output.out)
            .contains("api_auth_failed")
            .contains("path=/api/v1/me/profile")
            .contains("status=401")
            .contains("code=AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `valid bearer token reaches protected endpoint with security principal`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = get("/api/v1/me/profile", auth.accessToken)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"displayName\":\"Buddy\"")
    }

    private fun get(path: String, bearerToken: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
