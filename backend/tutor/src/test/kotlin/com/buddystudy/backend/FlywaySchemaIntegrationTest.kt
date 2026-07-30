package com.buddystudy.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactive.awaitSingle

import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.backend.auth.adapter.outbound.persistence.DeviceRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyQuestionCoveragePersistenceAdapter
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/migration-mysql",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.scheduler.processing-timeout-seconds=30",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class FlywaySchemaIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var devices: DeviceRepository
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var questionCoverage: StudyQuestionCoveragePersistenceAdapter
    @Autowired lateinit var databaseClient: DatabaseClient

    @Test
    fun `current legal documents match the published fixed copies`(): Unit = runBlocking {
        data class LegalDocument(
            val code: String,
            val version: String,
            val url: String,
            val contentHash: String,
            val required: Boolean,
            val mutable: Boolean,
        )

        val documents = databaseClient.sql(
            """
            select code, version, url, content_hash, required, mutable
            from terms
            where version = '2026-07-30'
            order by code
            """.trimIndent(),
        )
            .map { row, _ ->
                LegalDocument(
                    code = row.get("code", String::class.java)!!,
                    version = row.get("version", String::class.java)!!,
                    url = row.get("url", String::class.java)!!,
                    contentHash = row.get("content_hash", String::class.java)!!,
                    required = row.get("required", java.lang.Boolean::class.java)!!.booleanValue(),
                    mutable = row.get("mutable", java.lang.Boolean::class.java)!!.booleanValue(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(documents).containsExactly(
            LegalDocument(
                code = "MARKETING_NOTIFICATION",
                version = "2026-07-30",
                url = "https://ghkdqhrbals.github.io/buddy-studdy/marketing-consent-2026-07-30.html",
                contentHash = "984adea3e746ce793405f431eb8a554419d64cc11633d697d7962adc6fa4a12e",
                required = false,
                mutable = true,
            ),
            LegalDocument(
                code = "PRIVACY_POLICY",
                version = "2026-07-30",
                url = "https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-07-30.html",
                contentHash = "f6af1a6389b4b7bb9a1221da5ce7b3671780300e3a61448273231a3d130061b6",
                required = true,
                mutable = false,
            ),
            LegalDocument(
                code = "TERMS_OF_SERVICE",
                version = "2026-07-30",
                url = "https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-30.html",
                contentHash = "00544e21ee0921edc23d70c51d1977a57c3ddb9fb5d9d76ba7479fb4019a7edd",
                required = true,
                mutable = false,
            ),
        )
    }

    @Test
    fun `final localization schema keeps originals separate from translations`(): Unit = runBlocking {
        val columns = databaseClient.sql(
            """
            select column_name
            from information_schema.columns
            where table_schema = database() and table_name = 'questions'
            """.trimIndent(),
        )
            .map { row, _ -> row.get("column_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(columns).contains("source_language", "answer_source_language", "ai_response_source_language")
        assertThat(columns).doesNotContain(
            "language",
            "question_en",
            "topic_en",
            "hint_en",
            "translation_status",
            "translation_error",
        )

        val tables = databaseClient.sql(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in (
                'question_localizations',
                'answer_localizations',
                'grading_localizations',
                'question_comment_localizations',
                'question_search'
              )
            """.trimIndent(),
        )
            .map { row, _ -> row.get("table_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
        assertThat(tables).containsExactlyInAnyOrder(
            "question_localizations",
            "answer_localizations",
            "grading_localizations",
            "question_comment_localizations",
            "question_search",
        )
    }

    @Test
    fun `feedback schema supports operator review and reply state`(): Unit = runBlocking {
        val columns = databaseClient.sql(
            """
            select column_name
            from information_schema.columns
            where table_schema = database() and table_name = 'feedbacks'
            """.trimIndent(),
        )
            .map { row, _ -> row.get("column_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(columns).contains("status", "reviewed_at", "replied_at")

        val indexes = databaseClient.sql(
            """
            select distinct index_name
            from information_schema.statistics
            where table_schema = database() and table_name = 'feedbacks'
            """.trimIndent(),
        )
            .map { row, _ -> row.get("index_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(indexes).contains("idx_feedbacks_status_created")
    }

    @Test
    fun `flyway schema supports user openai settings`(): Unit = runBlocking {
        val saved = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "flyway@example.com",
                email = "flyway@example.com",
                status = "ACTIVE",
                displayName = "Flyway-User-0001",
                openaiApiKeyCipher = "cipher",
            )
        )

        assertThat(saved.id).isPositive()
        assertThat(users.findByProviderAndProviderId("EMAIL", "flyway@example.com")?.openaiApiKeyCipher).isEqualTo("cipher")
    }

    @Test
    fun `registered display names are unique while anonymous buddy names can repeat`(): Unit = runBlocking {
        users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "unique-name-one@example.com",
                email = "unique-name-one@example.com",
                status = "ACTIVE",
                displayName = "Bright-Fox-4321",
            )
        )

        assertThatThrownBy {
            runBlocking {
                users.save(
                    UserEntity(
                        provider = "GOOGLE",
                        providerId = "unique-name-google",
                        email = "unique-name-two@example.com",
                        status = "PENDING_TERMS",
                        displayName = "bright-fox-4321",
                    )
                )
            }
        }.hasMessageContaining("uq_users_display_name_key")

        users.save(UserEntity(provider = "ANONYMOUS", providerId = "anonymous-one", displayName = "Buddy"))
        users.save(UserEntity(provider = "ANONYMOUS", providerId = "anonymous-two", displayName = "Buddy"))
    }

    @Test
    fun `flyway schema supports idempotent installation lookup`(): Unit = runBlocking {
        val installationKeyHash = "a".repeat(64)
        devices.save(
            DeviceEntity(
                deviceId = "flyway-installation-device",
                installationKeyHash = installationKeyHash,
                clientSecretHash = "secret-hash",
            )
        )

        assertThat(devices.findByInstallationKeyHash(installationKeyHash)?.deviceId)
            .isEqualTo("flyway-installation-device")
    }

    @Test
    fun `flyway schema persists immutable rubric and final AI grading metadata`(): Unit = runBlocking {
        val saved = questions.save(
            QuestionEntity(
                deviceId = "flyway-grading-device",
                question = "Explain Redis persistence.",
                topic = "Redis",
                gradingRubricJson = """{"version":"question-rubric-v1","criteria":[]}""",
                gradingAssessmentJson = """{"criteria":[]}""",
                gradingVerdict = "PARTIALLY_CORRECT",
                gradingConfidence = 0.91,
                gradingPolicyVersion = "ai-judge-v1",
                gradingModel = "test-model",
            )
        )

        val reloaded = questions.findById(saved.id)

        assertThat(reloaded?.gradingRubricJson).contains("question-rubric-v1")
        assertThat(reloaded?.gradingAssessmentJson).contains("criteria")
        assertThat(reloaded?.gradingVerdict).isEqualTo("PARTIALLY_CORRECT")
        assertThat(reloaded?.gradingConfidence).isEqualTo(0.91)
        assertThat(reloaded?.gradingPolicyVersion).isEqualTo("ai-judge-v1")
        assertThat(reloaded?.gradingModel).isEqualTo("test-model")
    }

    @Test
    fun `flyway schema supports nested question coverage tree`(): Unit = runBlocking {
        val user = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "coverage-tree@example.com",
                email = "coverage-tree@example.com",
                status = "ACTIVE",
                displayName = "Coverage-Tree-0001",
            )
        )
        val study = studies.save(
            StudyEntity(
                deviceId = "flyway-device",
                userId = user.id,
                topic = "Redis Persistence",
            )
        )

        questionCoverage.ensureCoverage(
            studyId = study.id,
            topic = study.topic,
            concepts = listOf(
                QuestionCoveragePort.CoverageConceptBlueprint(
                    key = "persistence",
                    name = "Persistence",
                    angles = emptyList(),
                    children = listOf(
                        QuestionCoveragePort.CoverageConceptBlueprint(
                            key = "aof",
                            name = "AOF",
                            angles = emptyList(),
                            children = listOf(
                                QuestionCoveragePort.CoverageConceptBlueprint(
                                    key = "recovery",
                                    name = "Recovery",
                                    angles = listOf(
                                        QuestionCoveragePort.CoverageAngleBlueprint(
                                            key = "failure_mode",
                                            name = "Failure Mode",
                                        )
                                    ),
                                )
                            ),
                        )
                    ),
                )
            ),
        )

        val selected = questionCoverage.selectNext(study.id)

        assertThat(selected?.conceptKey).isEqualTo("recovery")
        assertThat(selected?.conceptName).isEqualTo("Recovery")
        assertThat(selected?.conceptKeyPath).isEqualTo("persistence/aof/recovery")
        assertThat(selected?.conceptPath).isEqualTo("Persistence > AOF > Recovery")
        assertThat(selected?.angleKey).isEqualTo("failure_mode")
    }

    @Test
    fun `due study claim is exclusive until its lease expires`(): Unit = runBlocking {
        studies.deleteAll()
        val now = java.time.Instant.parse("2030-01-01T00:00:00Z")
        val user = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "schedule-lease@example.com",
                email = "schedule-lease@example.com",
                status = "ACTIVE",
                displayName = "Schedule-Lease-0001",
            ),
        )
        val study = studies.save(
            StudyEntity(
                deviceId = "schedule-lease-device",
                userId = user.id,
                topic = "Schedule Lease",
                enabled = true,
                nextDueAt = now.minusSeconds(1),
            ),
        )

        val firstClaim = studies.claimDue(now, 1)
        val duplicateClaim = studies.claimDue(now.plusSeconds(29), 1)
        val reclaimed = studies.claimDue(now.plusSeconds(31), 1)

        assertThat(firstClaim.map { it.id }).containsExactly(study.id)
        assertThat(firstClaim.single().scheduleClaimedUntil).isEqualTo(now.plusSeconds(30))
        assertThat(duplicateClaim).isEmpty()
        assertThat(reclaimed.map { it.id }).containsExactly(study.id)
        assertThat(reclaimed.single().scheduleClaimedUntil).isEqualTo(now.plusSeconds(61))
    }
}
