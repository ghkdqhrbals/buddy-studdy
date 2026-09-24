package com.buddystudy.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactive.awaitSingle

import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.GradingPromptPreviewPort
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptDefaults
import com.buddystudy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.UserMembershipTierRepository
import com.buddystudy.backend.community.adapter.outbound.persistence.QuestionCommentRepository
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.account.domain.entity.UserMembershipTierEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
        "buddystudy.openai.user-content-api-key=test-user-content-openai-key",
        "buddystudy.openai.system-api-key=test-system-openai-key",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
class StudyApiIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var stats: QuestionStatsPort
    @Autowired lateinit var statsRepository: QuestionStatsRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var roles: RoleAssignmentPort
    @Autowired lateinit var refreshUserStats: RefreshUserStatsUseCase
    @Autowired lateinit var databaseClient: DatabaseClient
    @Autowired lateinit var followUpProcessor: com.buddystudy.backend.study.application.port.inbound.ProcessQuestionGenerationUseCase
    @Autowired lateinit var followUpQuota: com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
    @Autowired lateinit var membershipTiers: UserMembershipTierRepository
    @Autowired lateinit var contentLocalizations: ContentLocalizationPort
    @Autowired lateinit var comments: QuestionCommentRepository
    @LocalServerPort var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun clearStudyData(): Unit = runBlocking {
        membershipTiers.save(
            UserMembershipTierEntity(
                tierCode = "TIER1",
                monthlyQuestionLimit = 30,
                description = "Integration test default tier.",
            ),
        )
        statsRepository.deleteAll()
        questions.deleteAll()
        studies.deleteAll()
        Unit
    }

    @Test
    fun `follow-up HTTP request is owner scoped durable idempotent and reserves one question`(): Unit = runBlocking {
        val owner = registerActiveUser("followup-admission")
        val other = registerActiveUser("followup-outsider")
        val study = createStudy(owner, "Follow-up admission")
        val original = questions.save(gradedQuestion(owner.deviceId, study.userId, study.id, study.topic,
            "Explain atomicity", Instant.now().minusSeconds(120)))
        val quotaTime = Instant.now()
        val quota = checkNotNull(followUpQuota.quotaStatusForUser(study.userId, quotaTime))
        repeat(quota.monthlyQuestionLimit - 1) { index ->
            val key = "follow-up-fill-${study.userId}-$index"
            check(followUpQuota.reserveMonthlySystemQuestion(study.userId, checkNotNull(quota.periodStartedAt), key, key, quotaTime))
        }
        val before = getJson("/api/v1/questions/quota", owner.accessToken, owner.deviceId, owner.clientSecret).json()
        assertThat(before["remainingCount"].asInt()).isEqualTo(1)
        val path = "/api/v1/records/${original.id}/follow-ups"
        val forbidden = postJson(path, "", other.accessToken, other.deviceId, other.clientSecret, "foreign")
        assertThat(forbidden.statusCode()).isEqualTo(404)
        val first = postJson(path, "", owner.accessToken, owner.deviceId, owner.clientSecret, "followup-tap")
        assertThat(first.statusCode()).describedAs(first.body()).isEqualTo(202)
        val replay = postJson(path, "", owner.accessToken, owner.deviceId, owner.clientSecret, "followup-tap")
        assertThat(replay.statusCode()).isEqualTo(202)
        assertThat(replay.json()["correlationId"].asText()).isEqualTo(first.json()["correlationId"].asText())
        val process = getJson("/api/v1/question-processes/${first.json()["correlationId"].asText()}",
            owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(process.statusCode()).isEqualTo(200)
        assertThat(process.json()["status"].asText()).isEqualTo("QUEUED")
        val after = getJson("/api/v1/questions/quota", owner.accessToken, owner.deviceId, owner.clientSecret).json()
        assertThat(after["reservedCount"].asInt()).isEqualTo(before["reservedCount"].asInt() + 1)
        assertThat(after["remainingCount"].asInt()).isEqualTo(before["remainingCount"].asInt() - 1)
        assertThat(questions.findQuestionById(original.id)?.answer).isEqualTo(original.answer)
        // Exercise the real worker/persistence/quota path with this test context's fake OpenAI.
        val ownerUser = checkNotNull(users.findById(study.userId))
        ownerUser.appLanguage = com.buddystudy.common.domain.SupportedLanguage.ENGLISH
        users.save(ownerUser)
        followUpProcessor.process(com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent(
            eventId = "follow-up-worker-${java.util.UUID.randomUUID()}",
            correlationId = first.json()["correlationId"].asText(),
            userId = study.userId, studyId = study.id, topicId = study.id,
            source = com.buddystudy.backend.study.application.model.QuestionGenerationSource.FOLLOW_UP,
            occurredAt = Instant.now(),
        ), "study.question.generate.v1")
        val generated = questions.findThreadByRootAndUser(original.id, study.userId).last()
        assertThat(generated.id).isNotEqualTo(original.id)
        assertThat(generated.parentRecordId).isEqualTo(original.id)
        assertThat(generated.rootRecordId).isEqualTo(original.id)
        assertThat(generated.followUpDepth).isEqualTo(1)
        assertThat(generated.source).isEqualTo(QuestionSource.FOLLOW_UP)
        assertThat(generated.publicQuestion).isFalse()
        assertThat(generated.topic).isEqualTo(original.topic)
        assertThat(generated.difficultyLevel).isEqualTo(original.difficultyLevel)
        val committed = getJson("/api/v1/questions/quota", owner.accessToken, owner.deviceId, owner.clientSecret).json()
        assertThat(committed["usedCount"].asInt()).isEqualTo(before["usedCount"].asInt() + 1)
        assertThat(committed["reservedCount"].asInt()).isEqualTo(before["reservedCount"].asInt())
    }

    @Test
    fun `skipped follow-up remains in private thread history and cannot reopen an earlier branch`(): Unit = runBlocking {
        val owner = registerActiveUser("followup-skipped")
        val study = createStudy(owner, "Skipped follow-up")
        val now = Instant.now().minusSeconds(120)
        val original = questions.save(gradedQuestion(owner.deviceId, study.userId, study.id, study.topic, "Original", now))
        val child = questions.save(pendingQuestion(owner.deviceId, study.userId, study.id, study.topic, "Skipped practice", now.plusSeconds(1)).apply {
            source = QuestionSource.FOLLOW_UP
            parentRecordId = original.id
            rootRecordId = original.id
            followUpDepth = 1
            publicQuestion = false
        })
        val skipped = postJson("/api/v1/records/${child.id}/skip", "", owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(skipped.statusCode()).describedAs(skipped.body()).isEqualTo(200)
        val thread = getJson("/api/v1/records/${original.id}/thread?tl=en&view=original", owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(thread.statusCode()).describedAs(thread.body()).isEqualTo(200)
        assertThat(thread.json()["records"].map { it["id"].asText() }).containsExactly(original.id.toString(), child.id.toString())
        assertThat(thread.json()["records"][1]["questionStatus"].asText()).isEqualTo("SKIPPED")
        assertThat(thread.json()["records"][1]["followUpDepth"].asInt()).isEqualTo(1)
        assertThat(thread.json()["records"][1]["question"]["question"].asText()).isEqualTo("Skipped practice")
        // The current owner-detail contract reconciles terminal skips, while browse pages hide them.
        val detail = getJson("/api/v1/records/${child.id}", owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(detail.statusCode()).isEqualTo(200)
        assertThat(detail.json()["questionStatus"].asText()).isEqualTo("SKIPPED")
        for (recordId in listOf(original.id, child.id)) {
            val retry = postJson("/api/v1/records/$recordId/follow-ups", "", owner.accessToken, owner.deviceId, owner.clientSecret, "skip-retry-$recordId")
            assertThat(retry.statusCode()).describedAs(retry.body()).isEqualTo(409)
            assertThat(retry.body()).contains("FOLLOW_UP_NOT_AVAILABLE")
        }
    }

    @Test
    fun `follow-up HTTP thread preserves lineage and enforces two-turn limit after deletion`(): Unit = runBlocking {
        val owner = registerActiveUser("followup-thread")
        val other = registerActiveUser("followup-thread-other")
        val study = createStudy(owner, "Follow-up thread")
        val now = Instant.now().minusSeconds(120)
        val original = questions.save(gradedQuestion(owner.deviceId, study.userId, study.id, study.topic, "Original", now))
        val first = questions.save(gradedQuestion(owner.deviceId, study.userId, study.id, study.topic, "First follow-up", now.plusSeconds(1), false).apply {
            source = QuestionSource.FOLLOW_UP; parentRecordId = original.id; rootRecordId = original.id; followUpDepth = 1
        })
        val second = questions.save(gradedQuestion(owner.deviceId, study.userId, study.id, study.topic, "Second follow-up", now.plusSeconds(2), false).apply {
            source = QuestionSource.FOLLOW_UP; parentRecordId = first.id; rootRecordId = original.id; followUpDepth = 2
        })
        val path = "/api/v1/records/${second.id}/thread?tl=en&view=original"
        val response = getJson(path, owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        val records = response.json()["records"]
        assertThat(records.map { it["id"].asText() }).containsExactly(original.id.toString(), first.id.toString(), second.id.toString())
        assertThat(records[2]["parentRecordId"].asText()).isEqualTo(first.id.toString())
        assertThat(records[2]["rootRecordId"].asText()).isEqualTo(original.id.toString())
        assertThat(records[2]["followUpDepth"].asInt()).isEqualTo(2)
        assertThat(records[2]["source"].asText()).isEqualTo("follow_up")
        assertThat(records[2]["public"].asBoolean()).isFalse()
        assertThat(getJson(path, other.accessToken, other.deviceId, other.clientSecret).statusCode()).isEqualTo(404)
        assertThat(delete("/api/v1/records/${first.id}", owner).statusCode()).isEqualTo(204)
        val third = postJson("/api/v1/records/${second.id}/follow-ups", "", owner.accessToken, owner.deviceId, owner.clientSecret, "third")
        assertThat(third.statusCode()).describedAs(third.body()).isEqualTo(409)
        assertThat(third.body()).contains("FOLLOW_UP_LIMIT_REACHED")
        assertThat(delete("/api/v1/records/${original.id}", owner).statusCode()).isEqualTo(204)
        val deletedRoot = postJson("/api/v1/records/${second.id}/follow-ups", "", owner.accessToken, owner.deviceId, owner.clientSecret, "deleted-root")
        assertThat(deletedRoot.statusCode()).isEqualTo(404)
    }

    @Test
    fun `latest failed or graded question permits another question while in-progress latest question blocks it`(): Unit =
        runBlocking {
            val owner = registerActiveUser("latest-question-status")
            val study = createStudy(owner, "Latest status")
            val base = Instant.parse("2026-08-03T00:00:00Z")
            questions.save(
                QuestionEntity(
                    deviceId = owner.deviceId,
                    userId = study.userId,
                    studyId = study.id,
                    question = "Older pending question",
                    topic = study.topic,
                    status = QuestionStatus.UNGRADED,
                    createdAt = base,
                    updatedAt = base,
                ),
            )
            questions.save(
                QuestionEntity(
                    deviceId = owner.deviceId,
                    userId = study.userId,
                    studyId = study.id,
                    question = "Latest failed question",
                    topic = study.topic,
                    status = QuestionStatus.FAILED,
                    gradingStatus = com.buddystudy.study.domain.entity.AnswerGradingStatus.FAILED,
                    createdAt = base.plusSeconds(1),
                    updatedAt = base.plusSeconds(1),
                ),
            )

            assertThat(questions.findLatestStatusByStudyId(study.id)).isEqualTo(QuestionStatus.FAILED)
            assertThat(questions.countPendingForStudy(study.id)).isZero()

            questions.save(
                QuestionEntity(
                    deviceId = owner.deviceId,
                    userId = study.userId,
                    studyId = study.id,
                    question = "Latest grading question",
                    topic = study.topic,
                    status = QuestionStatus.GRADING,
                    createdAt = base.plusSeconds(2),
                    updatedAt = base.plusSeconds(2),
                ),
            )

            assertThat(questions.findLatestStatusByStudyId(study.id)).isEqualTo(QuestionStatus.GRADING)
            assertThat(questions.countPendingForStudy(study.id)).isEqualTo(1)
        }

    @Test
    fun `all studies exposes only successfully graded questions`(): Unit = runBlocking {
        val owner = registerActiveUser("public-graded-only")
        val study = createStudy(owner, "Public status")
        val graded = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = study.topic,
                question = "Successfully graded",
                createdAt = Instant.parse("2026-08-03T00:00:00Z"),
                publicQuestion = true,
            ),
        )
        questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = study.topic,
                question = "Failed grading",
                createdAt = Instant.parse("2026-08-03T00:00:01Z"),
                publicQuestion = true,
            ).apply {
                status = QuestionStatus.FAILED
                gradingStatus = com.buddystudy.study.domain.entity.AnswerGradingStatus.FAILED
            },
        )

        val page = questions.findPublicAnswered(Pageable.ofSize(20))

        assertThat(page.content.map(QuestionEntity::id)).containsExactly(graded.id)
    }

    @Test
    fun `study endpoint returns my studies while records endpoint returns only completed records`(): Unit = runBlocking {
        val registration = postJson(
            "/api/v1/devices/register",
            """
            {
              "apnsToken": "test-token",
              "platform": "ios",
              "apnsEnvironment": "sandbox",
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

        // Settings synchronization no longer creates a root study. Exercise the
        // explicit creation boundary before checking record/page reconciliation.
        assertThat(studies.findAll()).isEmpty()
        postJson(
            "/api/v1/studies",
            """{"topic":"Redis","difficultyLevel":2,"intervalMinutes":15,"customPrompt":"짧게 질문하세요.","openaiModel":"gpt-5.4","maxHistoryCount":100}""",
            accessToken, deviceId, clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }

        val study = studies.findAll().single()
        val swiftTopic = "SwiftUI-${Instant.now().toEpochMilli()}"
        val swiftStudy = studies.save(
            StudyEntity(
                deviceId = deviceId,
                userId = study.userId,
                topic = swiftTopic,
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
                status = QuestionStatus.UNGRADED,
                source = QuestionSource.SCHEDULED,
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
                status = QuestionStatus.GRADED,
                answer = "랭킹처럼 score가 필요한 목록에 씁니다.",
                score = 88,
                correct = true,
                feedback = "좋습니다.",
                explanation = "score 기반 정렬이 핵심입니다.",
                answeredAt = Instant.parse("2026-06-09T01:01:00Z"),
                gradedAt = Instant.parse("2026-06-09T01:01:10Z"),
                source = QuestionSource.MANUAL,
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
                status = QuestionStatus.SKIPPED,
                skippedAt = Instant.parse("2026-06-09T01:31:00Z"),
                source = QuestionSource.MANUAL,
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
                topic = swiftTopic,
                difficultyLevel = 6,
                scheduledFor = Instant.parse("2026-06-09T02:00:00Z"),
                sentAt = Instant.parse("2026-06-09T02:00:00Z"),
                status = QuestionStatus.GRADED,
                answer = "뷰가 소유하는 observable object를 유지할 때 씁니다.",
                score = 92,
                correct = true,
                feedback = "핵심을 잘 설명했습니다.",
                explanation = "StateObject is retained by the view lifecycle.",
                answeredAt = Instant.parse("2026-06-09T02:01:00Z"),
                gradedAt = Instant.parse("2026-06-09T02:01:10Z"),
                source = QuestionSource.MANUAL,
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
        assertThat(studyPage["studies"].map { it["topic"].asText() }).contains(study.topic, swiftTopic)
        val pendingStudyNode = studyPage["studies"].first {
            it.path("pendingQuestion").path("id").asText() == pending.id.toString()
        }
        assertThat(pendingStudyNode["pendingQuestion"]["topic"].asText()).isEqualTo("Redis")
        assertThat(pendingStudyNode["pendingQuestion"]["question"]["question"].asText()).isEqualTo("Redis의 Stream이 무엇인지 설명하세요.")

        val studyDetail = getJson("/api/v1/studies/${study.id}?tl=ko", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyDetail["id"].asLong()).isEqualTo(study.id)
        assertThat(studyDetail["pendingQuestion"]["id"].asText()).isEqualTo(pending.id.toString())
        assertThat(studyDetail["latestQuestion"]["id"].asText()).isEqualTo(graded.id.toString())
        assertThat(studyDetail["latestQuestion"]["answer"].asText()).isEqualTo("랭킹처럼 score가 필요한 목록에 씁니다.")
        assertThat(studyDetail["latestQuestion"]["gradingResult"]["feedback"].asText()).isEqualTo("좋습니다.")

        val searchedStudies = getJson("/api/v1/studies?limit=100&offset=0&query=swift", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(searchedStudies["studies"].map { it["topic"].asText() }).contains(swiftTopic)

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
        val skippedDetail = getJson("/api/v1/records/${skipped.id}", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(skippedDetail["id"].asText()).isEqualTo(skipped.id.toString())
        assertThat(skippedDetail["questionStatus"].asText()).isEqualTo("SKIPPED")

        val settings = getJson("/api/v1/settings", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(settings["topic"].asText()).isEqualTo(study.topic)
        assertThat(settings["appLanguage"].asText()).isEqualTo("ko")

        val studySettings = getJson("/api/v1/studies/${study.id}/settings", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studySettings["topic"].asText()).isEqualTo(study.topic)

        val apiStatus = getJson("/api/v1/api", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(apiStatus["openaiKeyConfigured"].asBoolean()).isTrue()

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
        assertThat(searchedStatsPage["topics"].map { it["topic"].asText() }).contains(swiftTopic)

        val publicQuestions = getJson("/api/v1/public/questions?limit=20&offset=0&query=sorted", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(publicQuestions["questions"]).hasSize(1)
        assertThat(publicQuestions["questions"][0]["id"].asText()).isEqualTo(graded.id.toString())
        assertThat(publicQuestions["questions"].map { it["id"].asText() }).doesNotContain(skipped.id.toString())
    }

    @Test
    fun `post study creates and updates a study room`(): Unit = runBlocking {
        val registration = postJson(
            "/api/v1/devices/register",
            """
            {
              "apnsToken": "test-token-create-study",
              "platform": "ios",
              "apnsEnvironment": "sandbox",
              "language": "ko",
              "timezone": "Asia/Seoul"
            }
            """.trimIndent(),
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        val deviceId = registration["deviceId"].asText()
        val clientSecret = registration["clientSecret"].asText()
        val accessToken = registration["accessToken"].asText()
        activateRegisteredUser(deviceId)
        membershipTiers.save(
            UserMembershipTierEntity(
                tierCode = "TIER1",
                monthlyQuestionLimit = 0,
                description = "No question quota for study creation regression.",
            ),
        )

        val exhaustedQuota = getJson("/api/v1/questions/quota", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(exhaustedQuota["remainingCount"].asInt()).isZero()

        val created = postJson(
            "/api/v1/studies",
            """
            {
              "topic": "Kotlin Architecture",
              "difficultyLevel": 7,
              "intervalMinutes": 30,
              "customPrompt": null,
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
        assertThat(created["customPrompt"].asText()).isEqualTo(QuestionPromptDefaults.DEFAULT)
        assertThat(created["pendingQuestion"].isNull).isTrue()

        val updated = postJson(
            "/api/v1/studies",
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

        val child = postJson(
            "/api/v1/studies/${created["id"].asLong()}/topics",
            """
            {
              "topic": "Kotlin Coroutines",
              "difficultyLevel": 6,
              "sortOrder": 1,
              "activeForQuestions": true
            }
            """.trimIndent(),
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()

        assertThat(child["parentStudyId"].asLong()).isEqualTo(created["id"].asLong())
        assertThat(child["topic"].asText()).isEqualTo("Kotlin Coroutines")
        assertThat(child["enabled"].asBoolean()).isFalse()
        assertThat(child["activeForQuestions"].asBoolean()).isTrue()
        assertThat(questions.countPendingForStudy(created["id"].asLong())).isZero()

        val retriedChild = postJson(
            "/api/v1/studies/${created["id"].asLong()}/topics",
            """
            {
              "topic": "  kotlin   coroutines ",
              "difficultyLevel": 6,
              "sortOrder": 1,
              "activeForQuestions": true
            }
            """.trimIndent(),
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()

        assertThat(retriedChild["id"].asLong()).isEqualTo(child["id"].asLong())

        val studyPage = getJson("/api/v1/studies?limit=100&offset=0", accessToken, deviceId, clientSecret)
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(studyPage["studies"]).hasSize(2)
        assertThat(studyPage["studies"].map { it["id"].asLong() })
            .containsExactlyInAnyOrder(created["id"].asLong(), child["id"].asLong())

        val quotaDenied = postJson(
            "/api/v1/studies/${child["id"].asLong()}/questions",
            "",
            accessToken,
            deviceId,
            clientSecret,
            idempotencyKey = "create-study-question-quota-denied",
        )
        assertThat(quotaDenied.statusCode()).isEqualTo(403)
        assertThat(quotaDenied.json()["error"]["errorCode"].asText()).isEqualTo("QUOTA_EXCEEDED")

        membershipTiers.save(
            UserMembershipTierEntity(
                tierCode = "TIER1",
                monthlyQuestionLimit = 30,
                description = "Integration test default tier.",
            ),
        )

        // Policy v5 snapshots the authoritative current-period base limit in user_quota.
        // Restoring only the catalog cannot change this deliberately exhausted test fixture.
        databaseClient.sql("update user_quota set base_limit = 30 where user_id = :userId")
            .bind("userId", studies.findById(created["id"].asLong())!!.userId)
            .fetch().rowsUpdated().awaitSingle()

        val accepted = postJson(
            "/api/v1/studies/${child["id"].asLong()}/questions",
            "",
            accessToken,
            deviceId,
            clientSecret,
            idempotencyKey = "create-study-question-1",
        ).also { assertThat(it.statusCode()).isEqualTo(202) }.json()
        assertThat(accepted["status"].asText()).isEqualTo("QUEUED")
        assertThat(accepted["correlationId"].asText()).isNotBlank()

        val process = getJson(
            "/api/v1/question-processes/${accepted["correlationId"].asText()}",
            accessToken,
            deviceId,
            clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(process["status"].asText()).isEqualTo("QUEUED")
        assertThat(process["terminal"].asBoolean()).isFalse()

        assertThat(questions.countPendingForStudy(child["id"].asLong())).isZero()
        assertThat(questions.countPendingForStudy(created["id"].asLong())).isZero()
    }

    @Test
    fun `deleting a study subtree preserves records until the record is explicitly deleted`(): Unit = runBlocking {
        val owner = registerActiveUser("study-record-lifecycle")
        val root = createStudy(owner, "Lifecycle Archive")
        val child = postJson(
            "/api/v1/studies/${root.id}/topics",
            """
            {
              "topic": "Lifecycle Child",
              "difficultyLevel": 4,
              "sortOrder": 1,
              "activeForQuestions": true
            }
            """.trimIndent(),
            owner.accessToken,
            owner.deviceId,
            owner.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        val childId = child["id"].asLong()
        val record = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = root.userId,
                studyId = childId,
                topic = "Lifecycle Child",
                question = "Explain the lifecycle archive boundary.",
                createdAt = Instant.parse("2026-08-06T00:00:00Z"),
            ),
        )

        delete("/api/v1/studies/${root.id}", owner)
            .also { assertThat(it.statusCode()).isEqualTo(204) }

        assertThat(studies.findById(root.id)).isNull()
        assertThat(studies.findById(childId)).isNull()
        assertThat(questions.findById(record.id)?.studyId).isNull()

        val records = getJson(
            "/api/v1/records?limit=100&offset=0&query=lifecycle",
            owner.accessToken,
            owner.deviceId,
            owner.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(records["records"].map { it["id"].asText() }).contains(record.id.toString())

        val publicQuestions = get("/api/v1/public/questions?limit=20&offset=0&query=lifecycle")
            .also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(publicQuestions["questions"].map { it["id"].asText() }).contains(record.id.toString())

        delete("/api/v1/records/${record.id}", owner)
            .also { assertThat(it.statusCode()).isEqualTo(204) }

        val recordsAfterDeletion = getJson(
            "/api/v1/records?limit=100&offset=0&query=lifecycle",
            owner.accessToken,
            owner.deviceId,
            owner.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(recordsAfterDeletion["records"].map { it["id"].asText() }).doesNotContain(record.id.toString())

        val publicAfterDeletion = get("/api/v1/public/questions?limit=20&offset=0&query=lifecycle")
            .also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(publicAfterDeletion["questions"].map { it["id"].asText() }).doesNotContain(record.id.toString())
    }

    @Test
    fun `custom question creation is idempotent and preserves pending draft and original content`(): Unit = runBlocking {
        val owner = registerActiveUser("custom-roundtrip")
        val study = createStudy(owner, "Custom topic")
        val pending = questions.save(pendingQuestion(owner.deviceId, study.userId, study.id, study.topic, "Pending draft stays here", Instant.now().minusSeconds(30)))
        val path = "/api/v1/studies/${study.id}/custom-questions"
        val body = mapper.writeValueAsString(mapOf("question" to "My custom question", "answer" to "My own **answer**", "language" to "en"))
        val concurrent = (1..2).map {
            java.util.concurrent.CompletableFuture.supplyAsync {
                postJson(path, body, owner.accessToken, owner.deviceId, owner.clientSecret, "custom-concurrent-key")
            }
        }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        concurrent.forEach { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(201) }
        val created = concurrent.first().json()
        val id = created["id"].asText()
        assertThat(concurrent.map { it.json()["id"].asText() }.distinct()).containsExactly(id)
        assertThat(created["source"].asText()).isEqualTo("custom_question")
        assertThat(created["questionStatus"].asText()).isEqualTo("GRADED")
        assertThat(created["gradingResult"].isNull).isTrue()
        assertThat(created["gradingStatus"].isNull).isTrue()
        assertThat(created["public"].asBoolean()).isFalse()
        assertThat(created["answer"].asText()).isEqualTo("My own **answer**")
        val detail = getJson("/api/v1/records/$id?language=ko", owner.accessToken, owner.deviceId, owner.clientSecret)
            .also { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200) }.json()
        assertThat(detail["question"]["question"].asText()).isEqualTo("My custom question")
        assertThat(detail["localization"]["question"]["translationState"].asText()).isEqualTo("ORIGINAL")
        assertThat(detail["answer"].asText()).isEqualTo("My own **answer**")
        val history = getJson("/api/v1/records?query=custom", owner.accessToken, owner.deviceId, owner.clientSecret).json()
        assertThat(history["records"].map { it["id"].asText() }).contains(id)
        val room = getJson("/api/v1/studies/${study.id}", owner.accessToken, owner.deviceId, owner.clientSecret).json()
        assertThat(room["pendingQuestion"]["id"].asText()).isEqualTo(pending.id.toString())
        assertThat(room["latestQuestion"]["id"].asText()).isEqualTo(id)
        assertThat(questions.countPendingForStudy(study.id)).isEqualTo(1)
        val changed = postJson(path, body.replace("My own", "Changed"), owner.accessToken, owner.deviceId, owner.clientSecret, "custom-concurrent-key")
        assertThat(changed.statusCode()).describedAs(changed.body()).isEqualTo(409)
    }

    @Test
    fun `custom question HTTP rejects missing ownership blank null and oversized fields`(): Unit = runBlocking {
        val owner = registerActiveUser("custom-validation-owner")
        val other = registerActiveUser("custom-validation-other")
        val study = createStudy(owner, "Private custom topic")
        val path = "/api/v1/studies/${study.id}/custom-questions"
        val valid = """{"question":"Q","answer":"A","language":"en"}"""
        assertThat(postJson(path, valid, other.accessToken, other.deviceId, other.clientSecret, "custom-other-key").statusCode()).isEqualTo(404)
        for ((index, fields) in listOf(
            mapOf("question" to "", "answer" to "A", "language" to "en"),
            mapOf("question" to "Q", "answer" to null, "language" to "en"),
            mapOf("question" to null, "answer" to "A", "language" to "en"),
            mapOf("question" to "Q", "answer" to "A", "language" to "xx"),
            mapOf("question" to "Q".repeat(4001), "answer" to "A", "language" to "en"),
            mapOf("question" to "Q", "answer" to "A".repeat(12001), "language" to "en"),
        ).withIndex()) {
            val response = postJson(path, mapper.writeValueAsString(fields), owner.accessToken, owner.deviceId, owner.clientSecret, "custom-invalid-$index")
            assertThat(response.statusCode()).describedAs(response.body()).isBetween(400, 499)
        }
        assertThat(questions.findVisibleByUser(study.userId, true, org.springframework.data.domain.PageRequest.of(0, 20)).content).isEmpty()
    }

    @Test
    fun `custom questions cannot be graded published skipped or read by another owner and can be deleted`(): Unit = runBlocking {
        val owner = registerActiveUser("custom-lifecycle-owner")
        val other = registerActiveUser("custom-lifecycle-other")
        val study = createStudy(owner, "Custom lifecycle")
        val created = postJson("/api/v1/studies/${study.id}/custom-questions", """{"question":"Private notes","answer":"My answer","language":"en"}""", owner.accessToken, owner.deviceId, owner.clientSecret, "custom-lifecycle-key")
            .also { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(201) }.json()
        val id = created["id"].asText()
        assertThat(getJson("/api/v1/records/$id", other.accessToken, other.deviceId, other.clientSecret).statusCode()).isEqualTo(404)
        assertThat(postJson("/api/v1/records/$id/answer", """{"answer":"My answer","sourceLanguage":"en"}""", owner.accessToken, owner.deviceId, owner.clientSecret).statusCode()).isEqualTo(409)
        assertThat(postJson("/api/v1/records/$id/follow-ups", "{}", owner.accessToken, owner.deviceId, owner.clientSecret, "custom-no-followup").statusCode()).isEqualTo(409)
        assertThat(postJson("/api/v1/records/$id/skip", "{}", owner.accessToken, owner.deviceId, owner.clientSecret).statusCode()).isEqualTo(409)
        val publicity = request("PATCH", "/api/v1/records/$id/publicity", """{"isPublic":true}""", owner.accessToken, owner.deviceId, owner.clientSecret)
        assertThat(publicity.statusCode()).describedAs(publicity.body()).isEqualTo(409)
        val stored = questions.findById(id.toLong())!!
        assertThat(stored.score).isNull()
        assertThat(stored.gradingRequestId).isNull()
        assertThat(stored.publicQuestion).isFalse()
        assertThat(questions.findPublicAnsweredById(id.toLong())).isNull()
        assertThat(delete("/api/v1/records/$id", owner).statusCode()).isEqualTo(204)
        assertThat(getJson("/api/v1/records/$id", owner.accessToken, owner.deviceId, owner.clientSecret).statusCode()).isEqualTo(404)
    }

    @Test
    fun `custom questions are saved with exhausted AI quota without consuming or reserving allowance`(): Unit = runBlocking {
        val owner = registerActiveUser("custom-quota-owner")
        val study = createStudy(owner, "Custom quota")
        getJson("/api/v1/questions/quota", owner.accessToken, owner.deviceId, owner.clientSecret)
        databaseClient.sql("update user_quota set committed_count = base_limit + bonus_limit where user_id = :id")
            .bind("id", study.userId).fetch().rowsUpdated().awaitSingle()
        suspend fun quota() = databaseClient.sql("select committed_count, reserved_count, base_limit, bonus_limit from user_quota where user_id = :id")
            .bind("id", study.userId).fetch().one().awaitSingle()
        val before = quota()
        val created = postJson("/api/v1/studies/${study.id}/custom-questions", """{"question":"Q","answer":"A","language":"en"}""", owner.accessToken, owner.deviceId, owner.clientSecret, "custom-zero-quota")
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(201)
        assertThat(quota()).isEqualTo(before)
        val id = created.json()["id"].asLong()
        assertThat(questions.findAllGradedForStats(org.springframework.data.domain.PageRequest.of(0, 100)).content.map { it.id }).doesNotContain(id)
    }

    @TestConfiguration
    class OpenAITestConfig {
        @Bean
        fun gradingPromptPreviewPort(): GradingPromptPreviewPort =
            Mockito.mock(GradingPromptPreviewPort::class.java)

        @Bean("openAIClient")
        fun openAIClient(): OpenAIPort = object : OpenAIPort {
            override suspend fun validate(apiKey: String) = Unit

            override suspend fun generateQuestion(
                apiKey: String,
                model: String,
                prompt: QuestionGenerationPrompt,
            ) = GeneratedQuestion("Generated question for ${prompt.fallbackTopic}", "Generated hint")

            override suspend fun embedText(apiKey: String, text: String): List<Float> = listOf(0f, 0f, 1f)

            override suspend fun generateQuestionCoverageBlueprint(
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

            override suspend fun grade(
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
    fun `study and records endpoints clamp pagination and isolate authenticated users`(): Unit = runBlocking {
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
    fun `public questions are readable anonymously but reactions comments and reports require authentication`(): Unit = runBlocking {
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
        val sourceHashes = ContentSourceHashPolicy.recordHashes(publicQuestion)
        contentLocalizations.ensureRecordPending(
            publicQuestion,
            "en",
            sourceHashes,
            publicQuestion.updatedAt,
            publicQuestion.updatedAt.minusSeconds(300),
        )
        contentLocalizations.saveQuestionReady(
            question = publicQuestion,
            targetLanguage = "en",
            sourceHash = sourceHashes.question,
            result = ContentTranslationResult(
                fields = mapOf(
                    "topic" to "Public Boundary Topic",
                    "question" to "Translated public boundary question",
                    "hint" to "Translated public boundary hint",
                ),
                provider = "test",
            ),
            now = publicQuestion.updatedAt,
        )
        contentLocalizations.saveAnswerReady(
            question = publicQuestion,
            targetLanguage = "en",
            sourceHash = sourceHashes.answer!!,
            result = ContentTranslationResult(
                fields = mapOf("answer" to "Translated public boundary answer"),
                provider = "test",
            ),
            now = publicQuestion.updatedAt,
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

        val translatedList = get("/api/v1/public/questions?query=boundary&tl=en")
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(translatedList["questions"]).hasSize(1)
        assertThat(translatedList["questions"][0]["topic"].asText()).isEqualTo("Public Boundary Topic")
        assertThat(translatedList["questions"][0]["question"].asText()).isEqualTo("Translated public boundary question")
        assertThat(translatedList["questions"][0]["answer"].asText()).isEqualTo("Translated public boundary answer")

        val detail = get("/api/v1/public/questions/${publicQuestion.id}?tl=en")
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(detail["id"].asText()).isEqualTo(publicQuestion.id.toString())
        assertThat(detail["likedByMe"].asBoolean()).isFalse()
        assertThat(detail["topic"].asText()).isEqualTo("Public Boundary Topic")
        assertThat(detail["question"].asText()).isEqualTo("Translated public boundary question")
        assertThat(detail["answer"].asText()).isEqualTo("Translated public boundary answer")

        val ownerDetail = getJson(
            "/api/v1/public/questions/${publicQuestion.id}?tl=en",
            owner.accessToken,
            owner.deviceId,
            owner.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(ownerDetail["question"].asText()).isEqualTo("Translated public boundary question")
        assertThat(ownerDetail["answer"].asText()).isEqualTo("Translated public boundary answer")
        assertThat(ownerDetail["localization"]["answer"]["translationState"].asText()).isEqualTo("TRANSLATED")
        assertThat(ownerDetail["localization"]["answer"]["translationReason"].asText()).isEqualTo("EXPLICIT_TL")

        val ownerRecord = getJson(
            "/api/v1/records/${publicQuestion.id}?tl=en",
            owner.accessToken,
            owner.deviceId,
            owner.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        assertThat(ownerRecord["question"]["question"].asText()).isEqualTo("Translated public boundary question")
        assertThat(ownerRecord["answer"].asText()).isEqualTo("Translated public boundary answer")
        assertThat(ownerRecord["localization"]["answer"]["translationState"].asText()).isEqualTo("TRANSLATED")
        assertThat(ownerRecord["localization"]["answer"]["translationReason"].asText()).isEqualTo("EXPLICIT_TL")

        val legacyDetail = get("/api/v1/public/questions/${publicQuestion.id}?language=en")
            .also { assertThat(it.statusCode()).isEqualTo(200) }
            .json()
        assertThat(legacyDetail["question"].asText()).isEqualTo("Translated public boundary question")

        assertAuthRequired(putJson("/api/v1/public/questions/${publicQuestion.id}/like", ""))
        assertAuthRequired(postJson("/api/v1/public/questions/${publicQuestion.id}/comments", """{"body":"hello"}"""))
        assertAuthRequired(postJson("/api/v1/public/questions/${publicQuestion.id}/report", """{"reason":"spam"}"""))
    }

    @Test
    fun `authenticated user can create and immediately read a public question comment`(): Unit = runBlocking {
        val owner = registerActiveUser("comment-owner")
        val commenter = registerActiveUser("comment-author")
        val study = createStudy(owner, "Comment Flow")
        val publicQuestion = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = "Comment Flow",
                question = "Can this question receive comments?",
                createdAt = Instant.parse("2026-06-09T06:00:00Z"),
                publicQuestion = true,
            )
        )

        val blank = postJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments",
            """{"body":"   "}""",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        )
        assertThat(blank.statusCode()).isEqualTo(422)

        val created = postJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments",
            """{"body":"The comment should be returned immediately."}""",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        )
        assertThat(created.statusCode()).isEqualTo(200)
        val createdBody = created.json()
        assertThat(createdBody["questionId"].asText()).isEqualTo(publicQuestion.id.toString())
        assertThat(createdBody["body"].asText()).isEqualTo("The comment should be returned immediately.")
        assertThat(createdBody["author"]["id"].asLong()).isPositive()
        assertThat(createdBody["createdAt"].asText()).isNotBlank()

        val listed = getJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments?limit=30&offset=0",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        )
        assertThat(listed.statusCode()).isEqualTo(200)
        assertThat(listed.json()["comments"]).hasSize(1)
        assertThat(listed.json()["comments"][0]["id"].asText()).isEqualTo(createdBody["id"].asText())
        assertThat(listed.json()["totalCount"].asInt()).isEqualTo(1)
    }

    @Test
    fun `stale failed content translation receives one new durable request token`(): Unit = runBlocking {
        val owner = registerActiveUser("translation-retry-owner")
        val study = createStudy(owner, "Translation Retry")
        val question = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = "Translation Retry",
                question = "번역 재시도를 설명하세요.",
                createdAt = Instant.parse("2026-06-09T06:00:00Z"),
            )
        )
        val hashes = ContentSourceHashPolicy.recordHashes(question)
        val firstRequestedAt = Instant.parse("2026-06-09T06:01:00Z")

        val first = contentLocalizations.ensureRecordPending(
            question,
            "en",
            hashes,
            firstRequestedAt,
            firstRequestedAt.minusSeconds(300),
        )
        val duplicate = contentLocalizations.ensureRecordPending(
            question,
            "en",
            hashes,
            firstRequestedAt.plusSeconds(60),
            firstRequestedAt.minusSeconds(240),
        )

        assertThat(first).hasSize(3)
        assertThat(duplicate).isEmpty()

        databaseClient.sql(
            """
            update question_localizations
            set status = 'FAILED', error = 'provider unavailable', updated_at = :failedAt
            where question_id = :questionId and target_language = 'en'
            """.trimIndent(),
        )
            .bind("failedAt", firstRequestedAt)
            .bind("questionId", question.id)
            .fetch().rowsUpdated().awaitSingle()

        val retry = contentLocalizations.ensureRecordPending(
            question,
            "en",
            hashes,
            firstRequestedAt.plusSeconds(301),
            firstRequestedAt.plusSeconds(1),
        )

        val firstQuestion = first.single { it.contentType == LocalizableContentType.QUESTION }
        val retriedQuestion = retry.single { it.contentType == LocalizableContentType.QUESTION }
        assertThat(retriedQuestion.requestToken).isNotEqualTo(firstQuestion.requestToken)
        assertThat(contentLocalizations.record(question.id, "en").question?.status).isEqualTo("PENDING")
    }

    @Test
    fun `comment keeps its original text and reads translation from comment localizations`(): Unit = runBlocking {
        val owner = registerActiveUser("localized-comment-owner")
        val commenter = registerActiveUser("localized-comment-author")
        val viewer = registerActiveUser("localized-comment-viewer")
        val study = createStudy(owner, "Localized Comment")
        val publicQuestion = questions.save(
            gradedQuestion(
                deviceId = owner.deviceId,
                userId = study.userId,
                studyId = study.id,
                topic = "Localized Comment",
                question = "Can comments be translated independently?",
                createdAt = Instant.parse("2026-06-09T06:30:00Z"),
            )
        )

        val created = postJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments",
            """{"body":"원문 댓글입니다.","sourceLanguage":"ko"}""",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()
        val comment = comments.findById(created["id"].asLong())!!
        val sourceHash = ContentSourceHashPolicy.sha256(comment.body)
        contentLocalizations.ensureCommentPending(
            comment,
            "en",
            sourceHash,
            comment.updatedAt,
            comment.updatedAt.minusSeconds(300),
        )
        contentLocalizations.saveCommentReady(
            comment = comment,
            targetLanguage = "en",
            sourceHash = sourceHash,
            result = ContentTranslationResult(
                fields = mapOf("body" to "This is the original comment."),
                provider = "test",
            ),
            now = comment.updatedAt,
        )

        val authorView = getJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments?tl=en",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()["comments"][0]
        assertThat(authorView["body"].asText()).isEqualTo("원문 댓글입니다.")
        assertThat(authorView["localization"]["displayLanguage"].asText()).isEqualTo("ko")
        assertThat(authorView["localization"]["translationState"].asText()).isEqualTo("ORIGINAL")
        assertThat(authorView["localization"]["translationReason"].asText()).isEqualTo("AUTHOR_ORIGINAL")

        val localized = getJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments?tl=en",
            viewer.accessToken,
            viewer.deviceId,
            viewer.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()["comments"][0]
        assertThat(localized["body"].asText()).isEqualTo("This is the original comment.")
        assertThat(localized["localization"]["sourceLanguage"].asText()).isEqualTo("ko")
        assertThat(localized["localization"]["displayLanguage"].asText()).isEqualTo("en")

        val original = getJson(
            "/api/v1/public/questions/${publicQuestion.id}/comments?tl=en&view=original",
            commenter.accessToken,
            commenter.deviceId,
            commenter.clientSecret,
        ).also { assertThat(it.statusCode()).isEqualTo(200) }.json()["comments"][0]
        assertThat(original["body"].asText()).isEqualTo("원문 댓글입니다.")
        assertThat(original["localization"]["displayLanguage"].asText()).isEqualTo("ko")
    }

    private fun postJson(
        path: String,
        body: String,
        bearerToken: String? = null,
        deviceId: String? = null,
        clientSecret: String? = null,
        idempotencyKey: String? = null,
    ): HttpResponse<String> =
        request("POST", path, body, bearerToken, deviceId, clientSecret, idempotencyKey)

    private fun putJson(path: String, body: String, bearerToken: String? = null, deviceId: String? = null, clientSecret: String? = null): HttpResponse<String> =
        request("PUT", path, body, bearerToken, deviceId, clientSecret)

    private fun delete(path: String, auth: AuthHeaders): HttpResponse<String> =
        request("DELETE", path, "", auth.accessToken, auth.deviceId, auth.clientSecret)

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getJson(path: String, bearerToken: String, deviceId: String, clientSecret: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        addAuthHeaders(builder, bearerToken, deviceId, clientSecret)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun request(
        method: String,
        path: String,
        body: String,
        bearerToken: String?,
        deviceId: String?,
        clientSecret: String?,
        idempotencyKey: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        if (!idempotencyKey.isNullOrBlank()) {
            builder.header("Idempotency-Key", idempotencyKey)
        }
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
              "apnsEnvironment": "sandbox",
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

    private suspend fun registerActiveUser(label: String): AuthHeaders =
        registerDevice(label).also { activateRegisteredUser(it.deviceId) }

    private suspend fun activateRegisteredUser(deviceId: String) {
        val user = users.findByProviderAndProviderId(UserProvider.ANONYMOUS, deviceId) ?: return
        user.status = UserStatus.ACTIVE
        users.save(user)
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
        databaseClient.sql(
            """
            insert into user_term_agreements
                (user_id, device_id, terms_id, action, source, created_at)
            select :userId, :deviceId, active_terms.id, 'AGREED', 'SIGNUP', current_timestamp
            from (
                select ranked.id, ranked.code
                from (
                    select t.id, t.code,
                           row_number() over (
                               partition by t.code
                               order by t.effective_at desc, t.id desc
                           ) as rn
                    from term_context_requirements tcr
                    join terms t on t.code = tcr.terms_code
                    where tcr.context = 'SIGNUP'
                      and tcr.required = true
                      and tcr.effective_at <= current_timestamp
                      and (tcr.retired_at is null or tcr.retired_at > current_timestamp)
                      and t.effective_at <= current_timestamp
                      and (t.retired_at is null or t.retired_at > current_timestamp)
                ) ranked
                where ranked.rn = 1
            ) active_terms
            """.trimIndent(),
        )
            .bind("userId", user.id)
            .bind("deviceId", deviceId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun createStudy(auth: AuthHeaders, topic: String): StudyEntity {
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
        return studies.findById(response["id"].asLong())!!
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
        status = QuestionStatus.GRADED,
        answer = "Answer for $topic",
        score = 87,
        correct = true,
        feedback = "Good",
        explanation = "Because",
        answeredAt = createdAt.plusSeconds(30),
        gradedAt = createdAt.plusSeconds(40),
        source = QuestionSource.MANUAL,
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
        status = QuestionStatus.UNGRADED,
        source = QuestionSource.SCHEDULED,
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
