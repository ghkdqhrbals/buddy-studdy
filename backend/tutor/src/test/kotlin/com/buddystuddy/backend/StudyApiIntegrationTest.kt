package com.buddystuddy.backend

import com.buddystuddy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystuddy.backend.auth.application.permission.Roles
import com.buddystuddy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystuddy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.buddystuddy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
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
        "buddystuddy.openai.api-key=test-openai-key",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class StudyApiIntegrationTest {
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var stats: QuestionStatsRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var roles: RoleAssignmentPort
    @Autowired lateinit var refreshUserStats: RefreshUserStatsUseCase
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
        activateRegisteredUser(deviceId)

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
        val swiftStudy = studies.save(
            StudyEntity(
                deviceId = deviceId,
                userId = study.userId,
                topic = "SwiftUI",
                difficultyLevel = 6,
                intervalMinutes = 15,
                enabled = true,
                customPrompt = "Ask about state management.",
                openaiModel = "gpt-5.4",
                createdAt = Instant.parse("2026-06-09T00:30:00Z"),
                updatedAt = Instant.parse("2026-06-09T00:30:00Z"),
            )
        )
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
        val skipped = questions.save(
            QuestionEntity(
                deviceId = deviceId,
                userId = study.userId,
                studyId = study.id,
                question = "Redis Pub/Sub과 Stream 차이를 설명하세요.",
                hint = "delivery guarantee",
                topic = "Redis",
                difficultyLevel = 2,
                scheduledFor = Instant.parse("2026-06-09T01:30:00Z"),
                sentAt = Instant.parse("2026-06-09T01:30:00Z"),
                status = "skipped",
                skippedAt = Instant.parse("2026-06-09T01:31:00Z"),
                source = "manual",
                publicQuestion = true,
                createdAt = Instant.parse("2026-06-09T01:30:00Z"),
                updatedAt = Instant.parse("2026-06-09T01:31:00Z"),
            )
        )
        val swiftGraded = questions.save(
            QuestionEntity(
                deviceId = deviceId,
                userId = swiftStudy.userId,
                studyId = swiftStudy.id,
                question = "SwiftUI StateObject는 언제 쓰나요?",
                hint = "view owned observable state",
                topic = "SwiftUI",
                difficultyLevel = 6,
                scheduledFor = Instant.parse("2026-06-09T02:00:00Z"),
                sentAt = Instant.parse("2026-06-09T02:00:00Z"),
                status = "graded",
                answer = "뷰가 소유하는 observable object를 유지할 때 씁니다.",
                score = 92,
                correct = true,
                feedback = "핵심을 잘 설명했습니다.",
                explanation = "StateObject is retained by the view lifecycle.",
                answeredAt = Instant.parse("2026-06-09T02:01:00Z"),
                gradedAt = Instant.parse("2026-06-09T02:01:10Z"),
                source = "manual",
                publicQuestion = true,
                createdAt = Instant.parse("2026-06-09T02:00:00Z"),
                updatedAt = Instant.parse("2026-06-09T02:01:10Z"),
            )
        )
        stats.save(QuestionStatsEntity(questionId = graded.id, likeCount = 2, commentCount = 1, viewCount = 5))

        val studyPage = getJson("/api/v1/studies?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyPage["studies"]).hasSize(2)
        assertThat(studyPage["studies"].map { it["topic"].asText() }).contains("Redis", "SwiftUI")
        val redisStudyNode = studyPage["studies"].first { it["topic"].asText() == "Redis" }
        assertThat(redisStudyNode["pendingQuestion"]["id"].asText()).isEqualTo(pending.id.toString())
        assertThat(redisStudyNode["pendingQuestion"]["question"]["question"].asText()).isEqualTo("Redis의 Stream이 무엇인지 설명하세요.")

        val searchedStudies = getJson("/api/v1/studies?limit=100&offset=0&query=swift", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(searchedStudies["studies"].map { it["topic"].asText() }).containsExactly("SwiftUI")

        val records = getJson("/api/v1/records?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(records["records"]).hasSize(2)
        assertThat(records["records"].map { it["id"].asText() }).contains(graded.id.toString(), swiftGraded.id.toString())
        assertThat(records["records"].map { it["id"].asText() }).doesNotContain(pending.id.toString(), skipped.id.toString())
        val redisRecordNode = records["records"].first { it["id"].asText() == graded.id.toString() }
        assertThat(redisRecordNode["likeCount"].asInt()).isEqualTo(2)
        assertThat(redisRecordNode["commentCount"].asInt()).isEqualTo(1)
        assertThat(redisRecordNode["viewCount"].asInt()).isEqualTo(5)

        val searchedRecords = getJson("/api/v1/records?limit=100&offset=0&query=stateobject", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(searchedRecords["records"].map { it["id"].asText() }).containsExactly(swiftGraded.id.toString())

        val recordDetail = getJson("/api/v1/records/${graded.id}", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(recordDetail["id"].asText()).isEqualTo(graded.id.toString())
        getJson("/api/v1/records/${skipped.id}", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(404) }

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

        refreshUserStats.refreshAll(Instant.parse("2026-06-09T03:00:00Z"))

        val statsPage = getJson("/api/v1/stats?limit=10&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(statsPage.toString()).contains("Redis")

        val emptyQueryStatsPage = getJson("/api/v1/stats?period=all&query=&sort=count&limit=8&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(emptyQueryStatsPage.toString()).contains("Redis")

        getJson("/api/v1/stats?period=today&query=&sort=count&limit=8&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }

        val searchedStatsPage = getJson("/api/v1/stats?limit=10&offset=0&query=swift", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(searchedStatsPage["topics"].map { it["topic"].asText() }).containsExactly("SwiftUI")

        val publicQuestions = getJson("/api/v1/public/questions?limit=20&offset=0&query=sorted", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(publicQuestions["questions"]).hasSize(1)
        assertThat(publicQuestions["questions"][0]["id"].asText()).isEqualTo(graded.id.toString())
        assertThat(publicQuestions["questions"].map { it["id"].asText() }).doesNotContain(skipped.id.toString())
    }

    @Test
    fun `post study creates and updates a study room`() {
        val registration = postJson(
            "/api/v1/devices/register",
            """
            {
              "apnsToken": "test-token-create-study",
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
        activateRegisteredUser(deviceId)

        val created = postJson(
            "/api/v1/study",
            """
            {
              "topic": "Kotlin Architecture",
              "difficultyLevel": 7,
              "intervalMinutes": 30,
              "customPrompt": "Ask practical backend architecture questions.",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 300
            }
            """.trimIndent(),
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()

        assertThat(created["topic"].asText()).isEqualTo("Kotlin Architecture")
        assertThat(created["difficultyLevel"].asInt()).isEqualTo(7)
        assertThat(created["intervalMinutes"].asInt()).isEqualTo(30)
        assertThat(created["pendingQuestion"].isNull).isTrue()

        val updated = postJson(
            "/api/v1/study",
            """
            {
              "topic": "Kotlin Architecture",
              "difficultyLevel": 8,
              "intervalMinutes": 45,
              "customPrompt": "Focus on production scale-in and scale-out tradeoffs.",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 500
            }
            """.trimIndent(),
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()

        assertThat(updated["id"].asLong()).isEqualTo(created["id"].asLong())
        assertThat(updated["difficultyLevel"].asInt()).isEqualTo(8)
        assertThat(updated["intervalMinutes"].asInt()).isEqualTo(45)
        assertThat(updated["customPrompt"].asText()).isEqualTo("Focus on production scale-in and scale-out tradeoffs.")
        assertThat(updated.has("isQuestionPublic")).isFalse()

        val studyPage = getJson("/api/v1/studies?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyPage["studies"]).hasSize(1)
        assertThat(studyPage["studies"][0]["id"].asLong()).isEqualTo(created["id"].asLong())

        val question = postJson(
            "/api/v1/studies/${created["id"].asLong()}/questions",
            "",
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(question["topic"].asText()).isEqualTo("Kotlin Architecture")
        assertThat(question["question"]["question"].asText()).isEqualTo("Generated question for Kotlin Architecture")

        val pendingQuestionCount = questions.countPendingForStudy(created["id"].asLong())
        assertThat(pendingQuestionCount).isEqualTo(1)
    }

    @TestConfiguration
    class OpenAITestConfig {
        @Bean("openAIClient")
        fun openAIClient(): OpenAIPort = object : OpenAIPort {
            override fun validate(apiKey: String) = Unit

            override fun generateQuestion(
                apiKey: String,
                model: String,
                prompt: QuestionGenerationPrompt,
            ) = GeneratedQuestion("Generated question for ${prompt.fallbackTopic}", "Generated hint")

            override fun embedText(apiKey: String, text: String): List<Float> = listOf(0f, 0f, 1f)

            override fun generateQuestionCoverageBlueprint(
                apiKey: String,
                model: String,
                topic: String,
                level: Int,
                customPrompt: String,
            ): List<OpenAIPort.QuestionCoverageConcept> =
                listOf(
                    OpenAIPort.QuestionCoverageConcept(
                        key = "general",
                        name = "General",
                        angles = listOf(OpenAIPort.QuestionCoverageAngle("definition", "Definition")),
                    )
                )

            override fun grade(
                apiKey: String,
                model: String,
                question: String,
                answer: String,
                topic: String,
                level: Int,
                language: String,
            ) = GradedAnswer(90, true, "Good", "Explanation")
        }
    }

    @Test
    fun `study and records endpoints clamp pagination and isolate authenticated users`() {
        val first = registerActiveUser("pagination-owner")
        val second = registerActiveUser("pagination-other")

        val firstStudy = createStudy(first, "Pagination Redis")
        val secondStudy = createStudy(first, "Pagination Swift")
        createStudy(second, "Pagination Other User")

        val ownGraded = questions.save(
            gradedQuestion(
                deviceId = first.deviceId,
                userId = firstStudy.userId,
                studyId = firstStudy.id,
                topic = "Pagination Redis",
                question = "Visible own graded record",
                createdAt = Instant.parse("2026-06-09T04:00:00Z"),
            )
        )
        val ownPending = questions.save(
            pendingQuestion(
                deviceId = first.deviceId,
                userId = firstStudy.userId,
                studyId = secondStudy.id,
                topic = "Pagination Swift",
                question = "Hidden pending record",
                createdAt = Instant.parse("2026-06-09T04:01:00Z"),
            )
        )
        val otherStudy = studies.findAll().first { it.deviceId == second.deviceId && it.topic == "Pagination Other User" }
        val otherGraded = questions.save(
            gradedQuestion(
                deviceId = second.deviceId,
                userId = otherStudy.userId,
                studyId = otherStudy.id,
                topic = "Pagination Other User",
                question = "Other user's record",
                createdAt = Instant.parse("2026-06-09T04:02:00Z"),
            )
        )

        val studyPage = getJson("/api/v1/studies?limit=0&offset=-50", first.accessToken, first.deviceId, first.clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyPage["limit"].asInt()).isEqualTo(1)
        assertThat(studyPage["offset"].asInt()).isZero()
        assertThat(studyPage["totalCount"].asLong()).isEqualTo(2)
        assertThat(studyPage["studies"]).hasSize(1)
        assertThat(studyPage["studies"].map { it["topic"].asText() }).doesNotContain("Pagination Other User")

        val records = getJson("/api/v1/records?limit=0&offset=-50", first.accessToken, first.deviceId, first.clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(records["limit"].asInt()).isEqualTo(1)
        assertThat(records["offset"].asInt()).isZero()
        assertThat(records["totalCount"].asLong()).isEqualTo(1)
        assertThat(records["records"]).hasSize(1)
        assertThat(records["records"][0]["id"].asText()).isEqualTo(ownGraded.id.toString())
        assertThat(records["records"].map { it["id"].asText() }).doesNotContain(ownPending.id.toString(), otherGraded.id.toString())
    }

    @Test
    fun `public questions are readable anonymously but reactions comments and reports require authentication`() {
        val owner = registerActiveUser("public-boundary-owner")
        val study = createStudy(owner, "Public Boundary")
        val publicQuestion = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = "Public Boundary",
                question = "Public boundary question",
                createdAt = Instant.parse("2026-06-09T05:00:00Z"),
                publicQuestion = true,
            )
        )
        stats.save(QuestionStatsEntity(questionId = publicQuestion.id, likeCount = 9, commentCount = 3, viewCount = 14))

        val list = get("/api/v1/public/questions?query=boundary")
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(list["questions"]).hasSize(1)
        val listed = list["questions"][0]
        assertThat(listed["id"].asText()).isEqualTo(publicQuestion.id.toString())
        assertThat(listed["answer"].asText()).isEqualTo("Answer for Public Boundary")
        assertThat(listed["gradingResult"]["score"].asInt()).isEqualTo(87)
        assertThat(listed["author"]["displayName"].asText()).isEqualTo("Buddy")
        assertThat(listed["likeCount"].asInt()).isEqualTo(9)
        assertThat(listed["commentCount"].asInt()).isEqualTo(3)
        assertThat(listed["viewCount"].asInt()).isEqualTo(14)

        val detail = get("/api/v1/public/questions/${publicQuestion.id}")
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(detail["id"].asText()).isEqualTo(publicQuestion.id.toString())
        assertThat(detail["isLikedByMe"].asBoolean()).isFalse()

        assertAuthRequired(putJson("/api/v1/public/questions/${publicQuestion.id}/like", ""))
        assertAuthRequired(postJson("/api/v1/public/questions/${publicQuestion.id}/comments", """{"body":"hello"}"""))
        assertAuthRequired(postJson("/api/v1/public/questions/${publicQuestion.id}/report", """{"reason":"spam"}"""))
    }

    private fun postJson(path: String, body: String, bearerToken: String? = null, deviceId: String? = null, clientSecret: String? = null): HttpResponse<String> =
        request("POST", path, body, bearerToken, deviceId, clientSecret)

    private fun putJson(path: String, body: String, bearerToken: String? = null, deviceId: String? = null, clientSecret: String? = null): HttpResponse<String> =
        request("PUT", path, body, bearerToken, deviceId, clientSecret)

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

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

    private fun registerDevice(label: String): AuthHeaders {
        val registration = postJson(
            "/api/v1/devices/register",
            """
            {
              "apnsToken": "test-token-$label",
              "platform": "ios",
              "apnsEnvironment": "development",
              "language": "ko",
              "timezone": "Asia/Seoul"
            }
            """.trimIndent(),
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        return AuthHeaders(
            deviceId = registration["deviceId"].asText(),
            clientSecret = registration["clientSecret"].asText(),
            accessToken = registration["accessToken"].asText(),
        )
    }

    private fun registerActiveUser(label: String): AuthHeaders =
        registerDevice(label).also { activateRegisteredUser(it.deviceId) }

    private fun activateRegisteredUser(deviceId: String) {
        val user = users.findByProviderAndProviderId("ANONYMOUS", deviceId) ?: return
        user.status = "ACTIVE"
        users.save(user)
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
    }

    private fun createStudy(auth: AuthHeaders, topic: String): StudyEntity {
        val response = postJson(
            "/api/v1/study",
            """
            {
              "topic": "$topic",
              "difficultyLevel": 3,
              "intervalMinutes": 20,
              "customPrompt": "Ask one concise question.",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100
            }
            """.trimIndent(),
            auth.accessToken,
            auth.deviceId,
            auth.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        return studies.findById(response["id"].asLong()).orElseThrow()
    }

    private fun gradedQuestion(
        deviceId: String,
        userId: Long,
        studyId: Long,
        topic: String,
        question: String,
        createdAt: Instant,
        publicQuestion: Boolean = true,
    ) = QuestionEntity(
        deviceId = deviceId,
        userId = userId,
        studyId = studyId,
        question = question,
        hint = "Hint for $topic",
        topic = topic,
        difficultyLevel = 3,
        scheduledFor = createdAt,
        sentAt = createdAt,
        status = "graded",
        answer = "Answer for $topic",
        score = 87,
        correct = true,
        feedback = "Good",
        explanation = "Because",
        answeredAt = createdAt.plusSeconds(30),
        gradedAt = createdAt.plusSeconds(40),
        source = "manual",
        publicQuestion = publicQuestion,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun pendingQuestion(
        deviceId: String,
        userId: Long,
        studyId: Long,
        topic: String,
        question: String,
        createdAt: Instant,
    ) = QuestionEntity(
        deviceId = deviceId,
        userId = userId,
        studyId = studyId,
        question = question,
        hint = "Hint for $topic",
        topic = topic,
        difficultyLevel = 3,
        scheduledFor = createdAt,
        sentAt = createdAt,
        status = "ungraded",
        source = "scheduled",
        publicQuestion = true,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun assertAuthRequired(response: HttpResponse<String>) {
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_ACCESS_TOKEN_REQUIRED")
    }

    private fun HttpResponse<String>.json(): JsonNode = mapper.readTree(body())

    private data class AuthHeaders(
        val deviceId: String,
        val clientSecret: String,
        val accessToken: String,
    )
}
