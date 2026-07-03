package com.buddystudy.backend

import com.buddystudy.auth.domain.entity.UserRoleEntity
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.adapter.outbound.persistence.RoleRepository
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRoleRepository
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.service.LoginService
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
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystudy-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
@ExtendWith(OutputCaptureExtension::class)
class SecurityIntegrationTest {
    @Autowired lateinit var login: LoginService
    @Autowired lateinit var tokenProvider: TokenProvider
    @Autowired lateinit var roles: RoleRepository
    @Autowired lateinit var userRoles: UserRoleRepository
    @LocalServerPort var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `public endpoints are reachable without an access token`() {
        assertThat(get("/health").statusCode()).isEqualTo(200)
        assertThat(get("/api/v1/health").statusCode()).isEqualTo(200)
        assertThat(get("/api/v1/health/readiness").statusCode()).isIn(200, 503)
        assertThat(get("/api/v1/health/dependencies").statusCode()).isIn(200, 503)
        assertThat(get("/api/v1/public/questions").statusCode()).isEqualTo(200)
    }

    @Test
    fun `protected endpoints return unified auth error without access token`() {
        val response = get("/api/v1/profile")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_ACCESS_TOKEN_REQUIRED")
        assertThat(response.body()).contains("Access token is required.")
    }

    @Test
    fun `invalid bearer token is rejected before controller execution`(output: CapturedOutput) {
        val response = get("/api/v1/profile", "not-a-token")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(output.out)
            .contains("api_auth_failed")
            .contains("path=/api/v1/profile")
            .contains("status=401")
            .contains("code=AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `invalid bearer token is ignored on public endpoints`() {
        val publicQuestions = get("/api/v1/public/questions", "not-a-token")
        val health = get("/api/v1/health", "not-a-token")
        val readiness = get("/api/v1/health/readiness", "not-a-token")
        val dependencies = get("/api/v1/health/dependencies", "not-a-token")

        assertThat(publicQuestions.statusCode()).isEqualTo(200)
        assertThat(publicQuestions.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(readiness.statusCode()).isIn(200, 503)
        assertThat(readiness.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(dependencies.statusCode()).isIn(200, 503)
        assertThat(dependencies.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `unknown non api scanner paths return not found without auth warning`(output: CapturedOutput) {
        val response = get("/wp-su.php")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).doesNotContain("AUTH_ACCESS_TOKEN_REQUIRED")
        assertThat(output.out)
            .doesNotContain("api_auth_failed")
            .doesNotContain("path=/wp-su.php")
    }

    @Test
    fun `invalid bearer token on unknown non api path is ignored before not found`(output: CapturedOutput) {
        val response = get("/ZSLeDE.php", "not-a-token")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
        assertThat(output.out)
            .doesNotContain("api_auth_failed")
            .doesNotContain("path=/ZSLeDE.php")
    }

    @Test
    fun `invalid bearer token does not block login endpoints before controller handling`() {
        val response = post("/api/v1/auth/google", """{"idToken":"invalid-google-token"}""", "not-a-token")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_DEVICE_CREDENTIALS_REQUIRED")
        assertThat(response.body()).contains("Device credentials are required.")
        assertThat(response.body()).doesNotContain("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `new email login without verification code returns email verification error`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = post(
            path = "/api/v1/auth/email",
            body = """{"email":"new-tester@example.com","password":"password123"}""",
            headers = mapOf(
                "X-Device-Id" to auth.deviceId,
                "X-Client-Secret" to auth.clientSecret,
            )
        )

        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(response.body()).contains("AUTH_EMAIL_VERIFICATION_REQUIRED")
        assertThat(response.body()).contains("Email verification code is required.")
    }

    @Test
    fun `valid bearer token reaches protected endpoint with security principal`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = get("/api/v1/profile", auth.accessToken)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"displayName\":\"Buddy\"")
    }

    @Test
    fun `page access returns backend computed permissions for anonymous token`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = get("/api/v1/me/access", auth.accessToken)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"ANONYMOUS\"")
        assertThat(response.body()).contains("\"publicQuestions\":true")
        assertThat(response.body()).contains("\"myStudies\":false")
        assertThat(response.body()).contains("\"records\":false")
        assertThat(response.body()).contains("\"stats\":false")
    }

    @Test
    fun `admin role includes debug permission and exposes developer page access`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val adminRole = roles.findByCode("ADMIN") ?: error("ADMIN role must be seeded")
        val principal = tokenProvider.parse(auth.accessToken)
        val now = Instant.now()
        userRoles.save(UserRoleEntity(userId = principal.userId, roleId = adminRole.id, createdAt = now, updatedAt = now))

        val response = get("/api/v1/me/access", auth.accessToken)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"developer\":true")
        assertThat(response.body()).contains("\"admin\":true")
    }

    @Test
    fun `permission denied response includes required permissions for protected mutation`() {
        val auth = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = post(
            path = "/api/v1/study",
            body = """{"topic":"Auth","difficultyLevel":3,"intervalMinutes":20}""",
            bearerToken = auth.accessToken,
            headers = mapOf(
                "X-Device-Id" to auth.deviceId,
                "X-Client-Secret" to auth.clientSecret,
            )
        )

        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(response.body()).contains("PERMISSION_DENIED")
        assertThat(response.body()).contains("study:read")
        assertThat(response.body()).contains("study:create")
        assertThat(response.body()).contains("\"loginRequired\":true")
    }

    private fun get(path: String, bearerToken: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String,
        bearerToken: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        headers.forEach { (key, value) -> builder.header(key, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
