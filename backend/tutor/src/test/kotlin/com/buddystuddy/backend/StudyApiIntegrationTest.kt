package com.buddystuddy.backend

import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.buddystuddy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-study-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class StudyApiIntegrationTest {
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var stats: QuestionStatsRepository
    @LocalServerPort var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `study endpoint returns my studies while records endpoint returns only completed records`() {
        val registration = postJson(
            "/api/v1/devices/register",
            """
            {
              "apnsToken": "test-token",
              "platform": "ios",
              "apnsEnvironment": "development",
              "language": "ko",
              "timezone": "Asia/Seoul"
            }
            """.trimIndent(),
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        val deviceId = registration["deviceId"].asText()
        val clientSecret = registration["clientSecret"].asText()
        val accessToken = registration["accessToken"].asText()

        val schedule = putJson(
            "/api/v1/settings",
            """
            {
              "topic": "Redis",
              "difficultyLevel": 2,
              "intervalMinutes": 15,
              "enabled": true,
              "notificationSound": "default",
              "customPrompt": "짧게 질문하세요.",
              "appLanguage": "ko",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "isQuestionPublic": true,
              "schedules": [
                {
                  "topic": "Redis",
                  "difficultyLevel": 2,
                  "customPrompt": "짧게 질문하세요.",
                  "openaiModel": "gpt-5.4"
                }
              ]
            }
            """.trimIndent(),
            accessToken,
            deviceId,
            clientSecret,
        )
        assertThat(schedule.statusCode()).isEqualTo(200)

        val study = studies.findAll().single()
        val pending = questions.save(
            QuestionEntity(
                deviceId = deviceId,
                userId = study.userId,
                studyId = study.id,
                question = "Redis의 Stream이 무엇인지 설명하세요.",
                hint = "append-only log 관점에서 생각하세요.",
                topic = "Redis",
                difficultyLevel = 2,
                scheduledFor = Instant.parse("2026-06-09T00:00:00Z"),
                sentAt = Instant.parse("2026-06-09T00:00:00Z"),
                status = "ungraded",
                source = "scheduled",
                publicQuestion = false,
                createdAt = Instant.parse("2026-06-09T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-09T00:00:00Z"),
            )
        )
        val graded = questions.save(
            QuestionEntity(
                deviceId = deviceId,
                userId = study.userId,
                studyId = study.id,
                question = "Redis Sorted Set은 언제 쓰나요?",
                hint = "ranking",
                topic = "Redis",
                difficultyLevel = 2,
                scheduledFor = Instant.parse("2026-06-09T01:00:00Z"),
                sentAt = Instant.parse("2026-06-09T01:00:00Z"),
                status = "graded",
                answer = "랭킹처럼 score가 필요한 목록에 씁니다.",
                score = 88,
                correct = true,
                feedback = "좋습니다.",
                explanation = "score 기반 정렬이 핵심입니다.",
                answeredAt = Instant.parse("2026-06-09T01:01:00Z"),
                gradedAt = Instant.parse("2026-06-09T01:01:10Z"),
                source = "manual",
                publicQuestion = true,
                createdAt = Instant.parse("2026-06-09T01:00:00Z"),
                updatedAt = Instant.parse("2026-06-09T01:01:10Z"),
            )
        )
        stats.save(QuestionStatsEntity(questionId = graded.id, likeCount = 2, commentCount = 1, viewCount = 5))

        val studyPage = getJson("/api/v1/studies?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyPage["studies"]).hasSize(1)
        assertThat(studyPage["studies"][0]["topic"].asText()).isEqualTo("Redis")
        assertThat(studyPage["studies"][0]["pendingQuestion"]["id"].asText()).isEqualTo(pending.id.toString())
        assertThat(studyPage["studies"][0]["pendingQuestion"]["question"]["question"].asText()).isEqualTo("Redis의 Stream이 무엇인지 설명하세요.")

        val records = getJson("/api/v1/records?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(records["records"]).hasSize(1)
        assertThat(records["records"][0]["id"].asText()).isEqualTo(graded.id.toString())
        assertThat(records["records"].map { it["id"].asText() }).doesNotContain(pending.id.toString())
        assertThat(records["records"][0]["likeCount"].asInt()).isEqualTo(2)
        assertThat(records["records"][0]["commentCount"].asInt()).isEqualTo(1)
        assertThat(records["records"][0]["viewCount"].asInt()).isEqualTo(5)

        val recordDetail = getJson("/api/v1/records/${graded.id}", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(recordDetail["id"].asText()).isEqualTo(graded.id.toString())

        val settings = getJson("/api/v1/settings", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(settings["topic"].asText()).isEqualTo("Redis")
        assertThat(settings["appLanguage"].asText()).isEqualTo("ko")

        val studySettings = getJson("/api/v1/studies/${study.id}/settings", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studySettings["topic"].asText()).isEqualTo("Redis")

        val apiStatus = getJson("/api/v1/api", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(apiStatus["openaiKeyConfigured"].asBoolean()).isFalse()

        val statsPage = getJson("/api/v1/stats?limit=10&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(statsPage.toString()).contains("Redis")

        val publicQuestions = getJson("/api/v1/public/questions?limit=20&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(publicQuestions["questions"]).hasSize(1)
        assertThat(publicQuestions["questions"][0]["id"].asText()).isEqualTo(graded.id.toString())
    }

    private fun postJson(path: String, body: String, bearerToken: String? = null, deviceId: String? = null, clientSecret: String? = null): HttpResponse<String> =
        request("POST", path, body, bearerToken, deviceId, clientSecret)

    private fun putJson(path: String, body: String, bearerToken: String, deviceId: String, clientSecret: String): HttpResponse<String> =
        request("PUT", path, body, bearerToken, deviceId, clientSecret)

    private fun getJson(path: String, bearerToken: String, deviceId: String, clientSecret: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        addAuthHeaders(builder, bearerToken, deviceId, clientSecret)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun request(method: String, path: String, body: String, bearerToken: String?, deviceId: String?, clientSecret: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        addAuthHeaders(builder, bearerToken, deviceId, clientSecret)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun addAuthHeaders(builder: HttpRequest.Builder, bearerToken: String?, deviceId: String?, clientSecret: String?) {
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        if (!deviceId.isNullOrBlank()) {
            builder.header("X-Device-Id", deviceId)
        }
        if (!clientSecret.isNullOrBlank()) {
            builder.header("X-Client-Secret", clientSecret)
        }
    }

    private fun HttpResponse<String>.json(): JsonNode = mapper.readTree(body())
}
