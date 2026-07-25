package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/v3/api-docs",
        "springdoc.swagger-ui.enabled=true",
        "springdoc.swagger-ui.path=/docs",
        "springdoc.swagger-ui.url=/v3/api-docs",
    ]
)
class OpenApiDocumentationTest : MySqlIntegrationTestSupport() {
    @LocalServerPort var port: Int = 0

    @Test
    fun `openapi document includes split study endpoint descriptions`(): Unit = runBlocking {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body())
            .contains("\"/api/v1/study\"")
            .contains("\"/api/v1/studies\"")
            .contains("\"/api/v1/records\"")
            .contains("\"/api/v1/stats\"")
            .contains("\"/api/v1/studies/{studyId}/settings\"")
            .contains("\"securitySchemes\"")
            .contains("\"accessToken\"")
            .contains("\"scheme\":\"bearer\"")
            .contains("Fetch my studies")
            .contains("Create a study")
            .contains("Each study can include one pendingQuestion")
            .contains("Record history is intentionally split into /api/v1/records")
            .contains("Maximum number of studies to include")
            .contains("scheduler freshness for external Slack monitoring")
            .contains("Runtime monitoring must use the Cloudflare Health Monitor Worker")
            .doesNotContain("deployment smoke tests")
    }

    @Test
    fun `swagger ui remote configuration loads`(): Unit = runBlocking {
        val client = HttpClient.newHttpClient()
        val docs = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val config = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v3/api-docs/swagger-config")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertThat(docs.statusCode()).isIn(200, 302)
        assertThat(config.statusCode()).isEqualTo(200)
        assertThat(config.body()).contains("/v3/api-docs")
    }
}
