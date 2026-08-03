package com.buddystudy.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactive.awaitSingle

import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DevicePlatform
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.backend.auth.adapter.outbound.persistence.DeviceRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyQuestionCoveragePersistenceAdapter
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.GradingVerdict
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
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
    fun `billing ledger tables and documented state constraints are installed`(): Unit = runBlocking {
        val tables = databaseClient.sql(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in (
                'membership_tier_products', 'apple_billing_accounts', 'invoices', 'invoice_events',
                'payments', 'payments_history', 'billing_actions', 'billing_jobs',
                'apple_billing_notifications'
              )
            """.trimIndent(),
        ).map { row, _ -> row.get("table_name", String::class.java)!! }
            .all().collectList().awaitSingle()

        assertThat(tables).containsExactlyInAnyOrder(
            "membership_tier_products",
            "apple_billing_accounts",
            "invoices",
            "invoice_events",
            "payments",
            "payments_history",
            "billing_actions",
            "billing_jobs",
            "apple_billing_notifications",
        )

        val comments = databaseClient.sql(
            """
            select table_name, column_name, column_comment
            from information_schema.columns
            where table_schema = database()
              and (
                (table_name = 'invoices' and column_name = 'status')
                or (table_name = 'payments' and column_name = 'status')
                or (table_name = 'billing_actions' and column_name in ('action_type', 'status'))
              )
            """.trimIndent(),
        ).map { row, _ ->
            Triple(
                row.get("table_name", String::class.java)!!,
                row.get("column_name", String::class.java)!!,
                row.get("column_comment", String::class.java)!!,
            )
        }.all().collectList().awaitSingle().associate { "${it.first}.${it.second}" to it.third }

        assertThat(comments.getValue("invoices.status")).contains("FULFILLED", "COMPENSATION_REQUIRED", "REFUNDED")
        assertThat(comments.getValue("payments.status")).contains("SETTLED", "REFUND_PENDING", "REVOKED")
        assertThat(comments.getValue("billing_actions.action_type")).contains("REFUND", "CANCELLATION", "COMPENSATION")
        assertThat(comments.getValue("billing_actions.status")).contains("AWAITING_APPLE", "COMPLETED", "DECLINED")

        data class TierProduct(
            val tierCode: String,
            val productId: String,
            val billingPeriod: String,
            val monthlyLimit: Int,
        )

        val tierProducts = databaseClient.sql(
            """
            select p.tier_code, p.product_id, p.billing_period, t.monthly_question_limit
            from membership_tier_products p
            join user_membership_tiers t on t.tier_code = p.tier_code
            where p.provider = 'APPLE' and p.enabled = true
            order by p.sort_order
            """.trimIndent(),
        ).map { row, _ ->
            TierProduct(
                tierCode = row.get("tier_code", String::class.java)!!,
                productId = row.get("product_id", String::class.java)!!,
                billingPeriod = row.get("billing_period", String::class.java)!!,
                monthlyLimit = row.get("monthly_question_limit", java.lang.Integer::class.java)!!.toInt(),
            )
        }.all().collectList().awaitSingle()

        assertThat(tierProducts).containsExactly(
            TierProduct("TIER2", "io.github.ghkdqhrbals.StudyMate.tier2.monthly", "P1M", 300),
            TierProduct("TIER2", "io.github.ghkdqhrbals.StudyMate.tier2.yearly", "P1Y", 300),
            TierProduct("TIER3", "io.github.ghkdqhrbals.StudyMate.tier3.monthly", "P1M", 1000),
            TierProduct("TIER3", "io.github.ghkdqhrbals.StudyMate.tier3.yearly", "P1Y", 1000),
        )
    }

    @Test
    fun `enum columns expose allowed values through database comments and checks`(): Unit = runBlocking {
        data class ColumnComment(val table: String, val column: String, val comment: String)

        val comments = databaseClient.sql(
            """
            select table_name, column_name, column_comment
            from information_schema.columns
            where table_schema = database()
              and (
                (table_name = 'users' and column_name in ('provider', 'status', 'avatar_mode', 'app_language'))
                or (table_name = 'user_memberships' and column_name = 'status')
                or (table_name = 'devices' and column_name in ('platform', 'apns_environment', 'language'))
                or (table_name = 'questions' and column_name in ('status', 'source', 'grading_verdict', 'grading_status'))
              )
            """.trimIndent(),
        )
            .map { row, _ ->
                ColumnComment(
                    table = row.get("table_name", String::class.java)!!,
                    column = row.get("column_name", String::class.java)!!,
                    comment = row.get("column_comment", String::class.java)!!,
                )
            }
            .all()
            .collectList()
            .awaitSingle()
            .associateBy { "${it.table}.${it.column}" }

        assertThat(comments).hasSize(12)
        assertThat(comments.getValue("users.provider").comment).contains("APPLE", "GOOGLE", "EMAIL")
        assertThat(comments.getValue("users.status").comment).contains("PENDING_TERMS", "WITHDRAWN")
        assertThat(comments.getValue("user_memberships.status").comment).contains("ACTIVE", "INACTIVE")
        assertThat(comments.getValue("users.app_language").comment).contains("ko", "en", "ja")
        assertThat(comments.getValue("devices.apns_environment").comment).contains("sandbox", "production")
        assertThat(comments.getValue("questions.status").comment).contains("ungraded", "graded", "skipped")
        assertThat(comments.getValue("questions.grading_status").comment).contains("QUEUED", "COMPLETED", "FAILED")

        val checks = databaseClient.sql(
            """
            select constraint_name
            from information_schema.table_constraints
            where constraint_schema = database()
              and constraint_type = 'CHECK'
              and constraint_name in (
                'chk_users_provider',
                'chk_users_status',
                'chk_user_memberships_status',
                'chk_devices_apns_environment',
                'chk_questions_status',
                'chk_questions_grading_status'
              )
            """.trimIndent(),
        )
            .map { row, _ -> row.get("constraint_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(checks).containsExactlyInAnyOrder(
            "chk_users_provider",
            "chk_users_status",
            "chk_user_memberships_status",
            "chk_devices_apns_environment",
            "chk_questions_status",
            "chk_questions_grading_status",
        )
    }

    @Test
    fun `R2DBC persists typed enums using their documented varchar values`(): Unit = runBlocking {
        val user = users.save(
            UserEntity(
                provider = UserProvider.APPLE,
                providerId = "enum-contract-apple",
                email = "enum-contract@example.com",
                status = UserStatus.PENDING_TERMS,
                appLanguage = SupportedLanguage.JAPANESE,
                displayName = "Enum-Contract-0001",
            ),
        )
        val device = devices.save(
            DeviceEntity(
                deviceId = "enum-contract-device",
                clientSecretHash = "enum-contract-secret",
                userId = user.id,
                platform = DevicePlatform.IOS,
                apnsEnvironment = ApnsEnvironment.SANDBOX,
                language = SupportedLanguage.ENGLISH,
            ),
        )
        val question = questions.save(
            QuestionEntity(
                deviceId = device.deviceId,
                userId = user.id,
                question = "Enum persistence?",
                topic = "Persistence",
                sourceLanguage = SupportedLanguage.JAPANESE,
                status = QuestionStatus.UNGRADED,
                source = QuestionSource.MANUAL,
            ),
        )

        val raw = databaseClient.sql(
            """
            select u.provider, u.status, u.app_language,
                   d.platform, d.apns_environment, d.language,
                   q.source_language, q.status as question_status, q.source as question_source
            from users u
            join devices d on d.user_id = u.id
            join questions q on q.user_id = u.id
            where u.id = :userId and d.id = :deviceId and q.id = :questionId
            """.trimIndent(),
        )
            .bind("userId", user.id)
            .bind("deviceId", device.id)
            .bind("questionId", question.id)
            .map { row, _ ->
                listOf(
                    row.get("provider", String::class.java)!!,
                    row.get("status", String::class.java)!!,
                    row.get("app_language", String::class.java)!!,
                    row.get("platform", String::class.java)!!,
                    row.get("apns_environment", String::class.java)!!,
                    row.get("language", String::class.java)!!,
                    row.get("source_language", String::class.java)!!,
                    row.get("question_status", String::class.java)!!,
                    row.get("question_source", String::class.java)!!,
                )
            }
            .one()
            .awaitSingle()

        assertThat(raw).containsExactly(
            "APPLE",
            "PENDING_TERMS",
            "ja",
            "ios",
            "sandbox",
            "en",
            "ja",
            "ungraded",
            "manual",
        )
        assertThat(users.findById(user.id)?.appLanguage).isEqualTo(SupportedLanguage.JAPANESE)
        assertThat(devices.findById(device.id)?.apnsEnvironment).isEqualTo(ApnsEnvironment.SANDBOX)
        assertThat(questions.findById(question.id)?.source).isEqualTo(QuestionSource.MANUAL)
    }

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
                provider = UserProvider.EMAIL,
                providerId = "flyway@example.com",
                email = "flyway@example.com",
                status = UserStatus.ACTIVE,
                displayName = "Flyway-User-0001",
                openaiApiKeyCipher = "cipher",
            )
        )

        assertThat(saved.id).isPositive()
        assertThat(users.findByProviderAndProviderId(UserProvider.EMAIL, "flyway@example.com")?.openaiApiKeyCipher)
            .isEqualTo("cipher")
    }

    @Test
    fun `registered display names are unique while anonymous buddy names can repeat`(): Unit = runBlocking {
        users.save(
            UserEntity(
                provider = UserProvider.EMAIL,
                providerId = "unique-name-one@example.com",
                email = "unique-name-one@example.com",
                status = UserStatus.ACTIVE,
                displayName = "Bright-Fox-4321",
            )
        )

        assertThatThrownBy {
            runBlocking {
                users.save(
                    UserEntity(
                        provider = UserProvider.GOOGLE,
                        providerId = "unique-name-google",
                        email = "unique-name-two@example.com",
                        status = UserStatus.PENDING_TERMS,
                        displayName = "bright-fox-4321",
                    )
                )
            }
        }.hasMessageContaining("uq_users_display_name_key")

        users.save(UserEntity(provider = UserProvider.ANONYMOUS, providerId = "anonymous-one", displayName = "Buddy"))
        users.save(UserEntity(provider = UserProvider.ANONYMOUS, providerId = "anonymous-two", displayName = "Buddy"))
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
                gradingVerdict = GradingVerdict.PARTIALLY_CORRECT,
                gradingConfidence = 0.91,
                gradingPolicyVersion = "ai-judge-v1",
                gradingModel = "test-model",
            )
        )

        val reloaded = questions.findById(saved.id)

        assertThat(reloaded?.gradingRubricJson).contains("question-rubric-v1")
        assertThat(reloaded?.gradingAssessmentJson).contains("criteria")
        assertThat(reloaded?.gradingVerdict).isEqualTo(GradingVerdict.PARTIALLY_CORRECT)
        assertThat(reloaded?.gradingConfidence).isEqualTo(0.91)
        assertThat(reloaded?.gradingPolicyVersion).isEqualTo("ai-judge-v1")
        assertThat(reloaded?.gradingModel).isEqualTo("test-model")
    }

    @Test
    fun `flyway schema supports nested question coverage tree`(): Unit = runBlocking {
        val user = users.save(
            UserEntity(
                provider = UserProvider.EMAIL,
                providerId = "coverage-tree@example.com",
                email = "coverage-tree@example.com",
                status = UserStatus.ACTIVE,
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
                provider = UserProvider.EMAIL,
                providerId = "schedule-lease@example.com",
                email = "schedule-lease@example.com",
                status = UserStatus.ACTIVE,
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
