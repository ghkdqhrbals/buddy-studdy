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
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryQuery
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.outbound.ExternalApiHistoryPort
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

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
    @Autowired lateinit var externalApiHistory: ExternalApiHistoryPort

    @Test
    fun `scheduler run history has a global newest-first index`(): Unit = runBlocking {
        val columns = databaseClient.sql(
            """
            select column_name
            from information_schema.statistics
            where table_schema = database()
              and table_name = 'scheduled_job_runs'
              and index_name = 'idx_scheduled_job_runs_started_id'
            order by seq_in_index
            """.trimIndent(),
        ).map { row, _ -> row.get("column_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(columns).containsExactly("started_at", "id")
    }

    @Test
    fun `external API history keeps complete request and response columns`(): Unit = runBlocking {
        val columns = databaseClient.sql(
            """
            select column_name
            from information_schema.columns
            where table_schema = database()
              and table_name = 'external_api_call_history'
            """.trimIndent(),
        ).map { row, _ -> row.get("column_name", String::class.java)!! }
            .all().collectList().awaitSingle()

        assertThat(columns).contains(
            "call_id",
            "provider",
            "operation",
            "request_headers_json",
            "request_body",
            "response_status",
            "response_headers_json",
            "response_body",
            "error_type",
            "error_message",
            "started_at",
            "finished_at",
            "duration_ms",
        )
    }

    @Test
    fun `external API history persists and cursor pages complete provider exchanges`(): Unit = runBlocking {
        val callId = "00000000-0000-0000-0000-000000000080"
        val startedAt = Instant.parse("2026-08-19T12:00:00Z")
        databaseClient.sql("delete from external_api_call_history where call_id = :callId")
            .bind("callId", callId).fetch().rowsUpdated().awaitSingle()
        externalApiHistory.start(
            StartExternalApiCallCommand(
                callId = callId,
                correlationId = "request-80",
                provider = "openai",
                operation = "test-exchange",
                httpMethod = "POST",
                requestUrl = "https://api.openai.com/v1/test",
                requestHeadersJson = "{}",
                requestBody = "{\"request\":\"full\"}",
                startedAt = startedAt,
            ),
        )
        assertThat(
            externalApiHistory.finish(
                FinishExternalApiCallCommand(
                    callId = callId,
                    status = "SUCCEEDED",
                    responseStatus = 200,
                    responseHeadersJson = "{}",
                    responseBody = "{\"response\":\"full\"}",
                    errorType = null,
                    errorMessage = null,
                    finishedAt = startedAt.plusMillis(125),
                ),
            ),
        ).isTrue()

        val page = externalApiHistory.page(
            ExternalApiHistoryQuery(null, 20, "openai", "SUCCEEDED", "test-exchange"),
        )
        val summary = page.items.single { it.callId == callId }
        assertThat(summary.durationMs).isEqualTo(125)
        val detail = externalApiHistory.find(summary.id)
        assertThat(detail?.requestBody).isEqualTo("{\"request\":\"full\"}")
        assertThat(detail?.responseBody).isEqualTo("{\"response\":\"full\"}")
    }

    @Test
    fun `billing fulfillment recovery job is registered for readiness monitoring`(): Unit = runBlocking {
        val schedule = databaseClient.sql(
            """
            select schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
            from scheduled_jobs
            where job_name = 'billing-fulfillment-recovery'
            """.trimIndent(),
        ).map { row, _ ->
            listOf(
                row.get("schedule_type", String::class.java),
                row.get("schedule_value", String::class.java),
                row.get("max_retry_count", java.lang.Integer::class.java)?.toInt(),
                row.get("timeout_seconds", java.lang.Integer::class.java)?.toInt(),
                row.get("lock_seconds", java.lang.Integer::class.java)?.toInt(),
            )
        }.one().awaitSingle()

        assertThat(schedule).containsExactly("FIXED_DELAY", "5s", 3, 300, 300)
    }

    @Test
    fun `user quota rollover job is registered with bounded retries`(): Unit = runBlocking {
        val schedule = databaseClient.sql(
            """
            select enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
            from scheduled_jobs
            where job_name = 'user-quota-rollover'
            """.trimIndent(),
        ).map { row, _ ->
            listOf(
                row.get("enabled", java.lang.Boolean::class.java)?.booleanValue(),
                row.get("schedule_type", String::class.java),
                row.get("schedule_value", String::class.java),
                row.get("max_retry_count", java.lang.Integer::class.java)?.toInt(),
                row.get("timeout_seconds", java.lang.Integer::class.java)?.toInt(),
                row.get("lock_seconds", java.lang.Integer::class.java)?.toInt(),
            )
        }.one().awaitSingle()

        assertThat(schedule).containsExactly(true, "FIXED_DELAY", "60s", 3, 300, 300)
    }

    @Test
    fun `user quota current row history and reservation snapshots match policy version five`(): Unit = runBlocking {
        val tables = databaseClient.sql(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in ('user_quota', 'user_quota_history', 'quota_reservations')
            """.trimIndent(),
        ).map { row, _ -> row.get("table_name", String::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(tables).containsExactlyInAnyOrder("user_quota", "user_quota_history", "quota_reservations")

        val uncommentedColumns = databaseClient.sql(
            """
            select concat(table_name, '.', column_name) as column_key
            from information_schema.columns
            where table_schema = database()
              and table_name in ('user_quota', 'user_quota_history')
              and column_comment = ''
            """.trimIndent(),
        ).map { row, _ -> row.get("column_key", String::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(uncommentedColumns).isEmpty()

        data class ColumnContract(
            val nullable: String,
            val extra: String,
            val generationExpression: String,
            val comment: String,
        )

        val columnContracts = databaseClient.sql(
            """
            select table_name, column_name, is_nullable, extra, generation_expression, column_comment
            from information_schema.columns
            where table_schema = database()
              and (
                (table_name = 'user_quota' and column_name in ('remaining_count', 'policy_version'))
                or (table_name = 'user_quota_history' and column_name = 'event_type')
                or (table_name = 'quota_reservations' and column_name in ('period_started_at', 'period_ends_at'))
              )
            """.trimIndent(),
        ).map { row, _ ->
            val key = "${row.get("table_name", String::class.java)!!}.${row.get("column_name", String::class.java)!!}"
            key to ColumnContract(
                nullable = row.get("is_nullable", String::class.java)!!,
                extra = row.get("extra", String::class.java)!!,
                generationExpression = row.get("generation_expression", String::class.java) ?: "",
                comment = row.get("column_comment", String::class.java)!!,
            )
        }.all().collectList().awaitSingle().toMap()

        assertThat(columnContracts).hasSize(5)
        assertThat(columnContracts.getValue("user_quota.remaining_count").extra).contains("STORED GENERATED")
        assertThat(columnContracts.getValue("user_quota.remaining_count").generationExpression)
            .contains("greatest", "base_limit", "bonus_limit", "committed_count", "reserved_count")
        assertThat(columnContracts.getValue("user_quota.remaining_count").comment).contains("max(0")
        assertThat(columnContracts.getValue("user_quota.policy_version").comment).contains("version 5")
        assertThat(columnContracts.getValue("user_quota_history.event_type").comment)
            .contains("ANCHOR_CHANGED", "PERIOD_RESET", "MIGRATION_ADJUSTMENT")
        assertThat(columnContracts.getValue("quota_reservations.period_started_at").nullable).isEqualTo("NO")
        assertThat(columnContracts.getValue("quota_reservations.period_ends_at").nullable).isEqualTo("NO")
        assertThat(columnContracts.getValue("quota_reservations.period_started_at").comment).contains("captured")
        assertThat(columnContracts.getValue("quota_reservations.period_ends_at").comment).contains("Exclusive UTC end")

        val indexes = databaseClient.sql(
            """
            select distinct concat(table_name, '.', index_name) as index_key
            from information_schema.statistics
            where table_schema = database()
              and table_name in ('user_quota', 'user_quota_history', 'quota_reservations')
            """.trimIndent(),
        ).map { row, _ -> row.get("index_key", String::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(indexes).contains(
            "user_quota.idx_user_quota_period_end",
            "user_quota_history.uq_user_quota_history_event",
            "user_quota_history.uq_user_quota_history_version",
            "user_quota_history.idx_user_quota_history_user_time",
            "user_quota_history.idx_user_quota_history_period_user",
            "user_quota_history.idx_user_quota_history_reservation_time",
            "quota_reservations.idx_quota_reservations_user_period_status",
        )

        val checks = databaseClient.sql(
            """
            select constraint_name, check_clause
            from information_schema.check_constraints
            where constraint_schema = database()
              and constraint_name in (
                'chk_user_quota_policy',
                'chk_user_quota_history_type',
                'chk_quota_reservations_period_snapshot'
              )
            """.trimIndent(),
        ).map { row, _ ->
            row.get("constraint_name", String::class.java)!! to row.get("check_clause", String::class.java)!!
        }.all().collectList().awaitSingle().toMap()
        assertThat(checks).hasSize(3)
        assertThat(checks.getValue("chk_user_quota_policy")).contains("policy_version", "5")
        assertThat(checks.getValue("chk_user_quota_history_type")).contains("ANCHOR_CHANGED")
        assertThat(checks.getValue("chk_quota_reservations_period_snapshot"))
            .contains("period_ends_at", "period_started_at")

        val user = users.save(
            UserEntity(
                provider = UserProvider.EMAIL,
                providerId = "quota-v73-contract@example.com",
                email = "quota-v73-contract@example.com",
                status = UserStatus.ACTIVE,
                displayName = "Quota-V73-Contract",
            ),
        )
        val periodStartedAt = Instant.parse("2030-01-15T00:00:00Z")
        val periodEndsAt = Instant.parse("2030-02-15T00:00:00Z")

        try {
            databaseClient.sql(
                """
                insert into user_quota (
                    user_id, tier_code, anchor_type, anchor_at, anchor_day, first_paid_at,
                    period_started_at, period_ends_at, base_limit, bonus_limit,
                    committed_count, reserved_count, policy_version, version, created_at, updated_at
                ) values (
                    :userId, 'TIER1', 'ACCOUNT_CREATED', :periodStartedAt, 15, null,
                    :periodStartedAt, :periodEndsAt, 30, 5, 10, 3, 5, 41, :periodStartedAt, :periodStartedAt
                )
                """.trimIndent(),
            ).bind("userId", user.id)
                .bind("periodStartedAt", periodStartedAt)
                .bind("periodEndsAt", periodEndsAt)
                .fetch().rowsUpdated().awaitSingle()

            databaseClient.sql(
                """
                insert into quota_reservations (
                    reservation_key, correlation_id, user_id, quota_period_id,
                    period_started_at, period_ends_at, status, reserved_at, created_at, updated_at
                ) values (
                    :reservationKey, :correlationId, :userId, null,
                    :periodStartedAt, :periodEndsAt, 'RESERVED', :periodStartedAt, :periodStartedAt, :periodStartedAt
                )
                """.trimIndent(),
            ).bind("reservationKey", "quota-v73-reservation-${user.id}")
                .bind("correlationId", "quota-v73-correlation-${user.id}")
                .bind("userId", user.id)
                .bind("periodStartedAt", periodStartedAt)
                .bind("periodEndsAt", periodEndsAt)
                .fetch().rowsUpdated().awaitSingle()

            databaseClient.sql(
                """
                insert into user_quota_history (
                    event_id, user_id, event_type,
                    affected_period_started_at, affected_period_ends_at, applied_to_current,
                    tier_code_before, tier_code_after, quota_version_after, occurred_at, created_at
                ) values (
                    :eventId, :userId, 'ANCHOR_CHANGED',
                    :periodStartedAt, :periodEndsAt, true,
                    'TIER1', 'TIER1', 42, :periodStartedAt, :periodStartedAt
                )
                """.trimIndent(),
            ).bind("eventId", "quota-v73-anchor-${user.id}")
                .bind("userId", user.id)
                .bind("periodStartedAt", periodStartedAt)
                .bind("periodEndsAt", periodEndsAt)
                .fetch().rowsUpdated().awaitSingle()

            val remaining = databaseClient.sql(
                "select remaining_count from user_quota where user_id = :userId",
            ).bind("userId", user.id)
                .map { row, _ -> row.get("remaining_count", java.lang.Integer::class.java)!!.toInt() }
                .one().awaitSingle()
            assertThat(remaining).isEqualTo(22)

            databaseClient.sql(
                "update user_quota set committed_count = 99 where user_id = :userId",
            ).bind("userId", user.id).fetch().rowsUpdated().awaitSingle()
            val exhaustedRemaining = databaseClient.sql(
                "select remaining_count from user_quota where user_id = :userId",
            ).bind("userId", user.id)
                .map { row, _ -> row.get("remaining_count", java.lang.Integer::class.java)!!.toInt() }
                .one().awaitSingle()
            assertThat(exhaustedRemaining).isZero()

            val reservationSnapshot = databaseClient.sql(
                """
                select quota_period_id, period_started_at, period_ends_at
                from quota_reservations
                where reservation_key = :reservationKey
                """.trimIndent(),
            ).bind("reservationKey", "quota-v73-reservation-${user.id}")
                .map { row, _ ->
                    listOf(
                        row.get("quota_period_id", java.lang.Long::class.java),
                        row.get("period_started_at", java.time.LocalDateTime::class.java),
                        row.get("period_ends_at", java.time.LocalDateTime::class.java),
                    )
                }.one().awaitSingle()
            assertThat(reservationSnapshot[0]).isNull()
            assertThat(reservationSnapshot[1]).isNotNull()
            assertThat(reservationSnapshot[2]).isNotNull()
        } finally {
            databaseClient.sql("delete from users where id = :userId")
                .bind("userId", user.id)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    @Test
    fun `billing ledger tables and documented state constraints are installed`(): Unit = runBlocking {
        val tables = databaseClient.sql(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in (
                'membership_tier_products', 'apple_billing_accounts', 'invoices', 'invoice_events',
                'payments', 'payments_history', 'billing_actions', 'billing_fulfillment_outbox',
                'billing_apple_notification_inbox', 'billing_revenuecat_event_inbox',
                'billing_accounts', 'subscription_events', 'subscriptions',
                'user_entitlement_projection', 'quota_accounts', 'quota_periods',
                'quota_reservations', 'quota_ledger'
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
            "billing_fulfillment_outbox",
            "billing_apple_notification_inbox",
            "billing_revenuecat_event_inbox",
            "billing_accounts",
            "subscription_events",
            "subscriptions",
            "user_entitlement_projection",
            "quota_accounts",
            "quota_periods",
            "quota_reservations",
            "quota_ledger",
        )

        val comments = databaseClient.sql(
            """
            select table_name, column_name, column_comment
            from information_schema.columns
            where table_schema = database()
              and (
                (table_name = 'invoices' and column_name in ('type', 'status'))
                or (table_name = 'payments' and column_name = 'status')
                or (table_name = 'billing_actions' and column_name in ('action_type', 'status'))
                or (table_name = 'billing_revenuecat_event_inbox' and column_name in (
                    'processing_status', 'original_app_user_id', 'cancel_reason', 'expiration_reason'
                ))
                or (table_name = 'billing_accounts' and column_name in ('app_account_token', 'status', 'anonymized_subject_hash'))
                or (table_name = 'subscription_events' and column_name in ('event_type', 'processing_status', 'payload_sha256'))
              )
            """.trimIndent(),
        ).map { row, _ ->
            Triple(
                row.get("table_name", String::class.java)!!,
                row.get("column_name", String::class.java)!!,
                row.get("column_comment", String::class.java)!!,
            )
        }.all().collectList().awaitSingle().associate { "${it.first}.${it.second}" to it.third }

        assertThat(comments.getValue("invoices.type")).contains("NORMAL", "REFUND")
        assertThat(comments.getValue("invoices.status")).contains("WAITING", "COMPLETED", "FAILED")
        assertThat(comments.getValue("payments.status")).contains("SETTLED", "REFUND_PENDING", "REVOKED")
        assertThat(comments.getValue("billing_actions.action_type")).contains("REFUND", "CANCELLATION", "COMPENSATION")
        assertThat(comments.getValue("billing_actions.status")).contains("AWAITING_APPLE", "COMPLETED", "DECLINED")
        assertThat(comments.getValue("billing_revenuecat_event_inbox.processing_status"))
            .contains("RECEIVED", "PROCESSED", "IGNORED", "FAILED")
        assertThat(comments.getValue("billing_revenuecat_event_inbox.original_app_user_id"))
            .contains("RevenueCat App User ID", "appAccountToken")
        assertThat(comments.getValue("billing_revenuecat_event_inbox.cancel_reason"))
            .contains("CUSTOMER_SUPPORT")
        assertThat(comments.getValue("billing_revenuecat_event_inbox.expiration_reason"))
            .contains("BILLING_ERROR")
        assertThat(comments.getValue("billing_accounts.status")).contains("ACTIVE", "ANONYMIZED")
        assertThat(comments.getValue("subscription_events.processing_status"))
            .contains("PENDING", "COMPLETED", "FAILED")

        val compatibilityObjects = databaseClient.sql(
            """
            select table_name, table_type
            from information_schema.tables
            where table_schema = database()
              and table_name in (
                'billing_jobs', 'apple_billing_notifications', 'revenuecat_billing_events',
                'billing_fulfillment_outbox', 'billing_apple_notification_inbox',
                'billing_revenuecat_event_inbox'
              )
            """.trimIndent(),
        ).map { row, _ ->
            row.get("table_name", String::class.java)!! to row.get("table_type", String::class.java)!!
        }.all().collectList().awaitSingle().toMap()

        assertThat(compatibilityObjects).containsEntry("billing_fulfillment_outbox", "BASE TABLE")
        assertThat(compatibilityObjects).containsEntry("billing_apple_notification_inbox", "BASE TABLE")
        assertThat(compatibilityObjects).containsEntry("billing_revenuecat_event_inbox", "BASE TABLE")
        assertThat(compatibilityObjects).containsEntry("billing_jobs", "VIEW")
        assertThat(compatibilityObjects).containsEntry("apple_billing_notifications", "VIEW")
        assertThat(compatibilityObjects).containsEntry("revenuecat_billing_events", "VIEW")

        val compatibilityViews = databaseClient.sql(
            """
            select table_name, is_updatable
            from information_schema.views
            where table_schema = database()
              and table_name in (
                'billing_jobs', 'apple_billing_notifications', 'revenuecat_billing_events'
              )
            """.trimIndent(),
        ).map { row, _ ->
            row.get("table_name", String::class.java)!! to row.get("is_updatable", String::class.java)!!
        }.all().collectList().awaitSingle().toMap()

        assertThat(compatibilityViews).containsEntry("billing_jobs", "YES")
        assertThat(compatibilityViews).containsEntry("apple_billing_notifications", "YES")
        assertThat(compatibilityViews).containsEntry("revenuecat_billing_events", "YES")

        data class TierProduct(
            val tierCode: String,
            val productId: String,
            val billingPeriod: String,
            val monthlyLimit: Int,
            val adFree: Boolean,
        )

        val tierProducts = databaseClient.sql(
            """
            select p.tier_code, p.product_id, p.billing_period, t.monthly_question_limit, t.ad_free
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
                adFree = row.get("ad_free", java.lang.Boolean::class.java)!!.booleanValue(),
            )
        }.all().collectList().awaitSingle()

        assertThat(tierProducts).containsExactly(
            TierProduct("TIER2", "io.github.ghkdqhrbals.StudyMate.tier2.monthly", "P1M", 300, true),
            TierProduct("TIER3", "io.github.ghkdqhrbals.StudyMate.tier3.monthly", "P1M", 1000, true),
        )

        val freeTierAdFree = databaseClient.sql(
            "select ad_free from user_membership_tiers where tier_code = 'TIER1'",
        ).map { row, _ -> row.get("ad_free", java.lang.Boolean::class.java)!!.booleanValue() }
            .one().awaitSingle()
        assertThat(freeTierAdFree).isFalse()

        val retiredAnnualProducts = databaseClient.sql(
            """
            select count(*) as product_count
            from membership_tier_products
            where provider = 'APPLE' and billing_period = 'P1Y' and enabled = false
            """.trimIndent(),
        ).map { row, _ -> row.get("product_count", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        assertThat(retiredAnnualProducts).isEqualTo(2)

        val monthlyOnlySaleConstraint = databaseClient.sql(
            """
            select count(*) as constraint_count
            from information_schema.table_constraints
            where constraint_schema = database()
              and table_name = 'membership_tier_products'
              and constraint_name = 'chk_membership_tier_products_no_annual_sale'
              and constraint_type = 'CHECK'
            """.trimIndent(),
        ).map { row, _ -> row.get("constraint_count", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        assertThat(monthlyOnlySaleConstraint).isEqualTo(1)

        val annualEnableFailure = runCatching {
            databaseClient.sql(
                """
                update membership_tier_products
                set enabled = true
                where provider = 'APPLE' and billing_period = 'P1Y'
                """.trimIndent(),
            ).fetch().rowsUpdated().awaitSingle()
        }.exceptionOrNull()
        assertThat(annualEnableFailure).isNotNull()
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

        val membershipCheck = databaseClient.sql(
            """
            select check_clause
            from information_schema.check_constraints
            where constraint_schema = database()
              and constraint_name = 'chk_user_memberships_status'
            """.trimIndent(),
        ).map { row, _ -> row.get("check_clause", String::class.java)!! }
            .one().awaitSingle()
        assertThat(membershipCheck).contains("ACTIVE", "INACTIVE")

        val billingRepairMigrations = databaseClient.sql(
            """
            select version
            from flyway_schema_history
            where version in ('65', '66', '67') and success = true
            order by installed_rank
            """.trimIndent(),
        ).map { row, _ -> row.get("version", String::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(billingRepairMigrations).containsExactly("65", "66", "67")
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
    fun `2026 08 25 privacy policy is staged while 2026 08 14 remains active`(): Unit = runBlocking {
        data class PrivacyDocument(
            val version: String,
            val locale: String,
            val url: String,
            val contentHash: String,
            val effectiveAt: Instant,
            val required: Boolean,
            val mutable: Boolean,
        )

        val document = databaseClient.sql(
            """
            select version, locale, url, content_hash, effective_at, required, mutable
            from terms
            where code = 'PRIVACY_POLICY'
              and version = '2026-08-25'
              and retired_at is null
            """.trimIndent(),
        )
            .map { row, _ ->
                PrivacyDocument(
                    version = row.get("version", String::class.java)!!,
                    locale = row.get("locale", String::class.java)!!,
                    url = row.get("url", String::class.java)!!,
                    contentHash = row.get("content_hash", String::class.java)!!,
                    effectiveAt = row.get("effective_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
                    required = row.get("required", java.lang.Boolean::class.java)!!.booleanValue(),
                    mutable = row.get("mutable", java.lang.Boolean::class.java)!!.booleanValue(),
                )
            }
            .one()
            .awaitSingle()

        assertThat(document).isEqualTo(
            PrivacyDocument(
                version = "2026-08-25",
                locale = "ko",
                url = "https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-25.html",
                contentHash = "4902b5ef4d6937830ffcb30666d4b0d90e375c10ea1492725956805ec913ed3a",
                effectiveAt = Instant.parse("9999-12-31T00:00:00Z"),
                required = true,
                mutable = false,
            ),
        )

        val activeVersion = databaseClient.sql(
            """
            select version
            from terms
            where code = 'PRIVACY_POLICY'
              and effective_at <= :now
              and (retired_at is null or retired_at > :now)
            order by effective_at desc, id desc
            limit 1
            """.trimIndent(),
        ).bind("now", Instant.parse("2026-08-25T12:00:00Z"))
            .map { row, _ -> row.get("version", String::class.java)!! }
            .one()
            .awaitSingle()

        assertThat(activeVersion).isEqualTo("2026-08-14")
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
    fun `native ad slot schema defaults off and enforces idempotency plus account deletion cascades`(): Unit = runBlocking {
        val tables = databaseClient.sql(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in ('native_ad_placement_policies', 'native_ad_slots', 'native_ad_delivery_state')
            """.trimIndent(),
        ).map { row, _ -> row.get("table_name", String::class.java)!! }
            .all().collectList().awaitSingle()
        assertThat(tables).containsExactlyInAnyOrder(
            "native_ad_placement_policies",
            "native_ad_slots",
            "native_ad_delivery_state",
        )

        data class PlacementDefaults(
            val enabled: Boolean,
            val dailyCap: Int,
            val interval: Int,
            val minimumItems: Int,
            val earliest: Int,
            val latest: Int,
        )
        val defaults = databaseClient.sql(
            """
            select enabled, daily_delivery_cap, minimum_seconds_between_deliveries,
                   minimum_feed_item_count, earliest_position, latest_position
            from native_ad_placement_policies
            where placement = 'COMMUNITY_FEED'
            """.trimIndent(),
        ).map { row, _ ->
            PlacementDefaults(
                enabled = row.get("enabled", java.lang.Boolean::class.java)!!.booleanValue(),
                dailyCap = row.get("daily_delivery_cap", java.lang.Integer::class.java)!!.toInt(),
                interval = row.get("minimum_seconds_between_deliveries", java.lang.Integer::class.java)!!.toInt(),
                minimumItems = row.get("minimum_feed_item_count", java.lang.Integer::class.java)!!.toInt(),
                earliest = row.get("earliest_position", java.lang.Integer::class.java)!!.toInt(),
                latest = row.get("latest_position", java.lang.Integer::class.java)!!.toInt(),
            )
        }.one().awaitSingle()
        assertThat(defaults).isEqualTo(PlacementDefaults(false, 2, 21_600, 4, 2, 7))

        databaseClient.sql(
            "update native_ad_placement_policies " +
                "set minimum_seconds_between_deliveries = 0 where placement = 'COMMUNITY_FEED'",
        ).fetch().rowsUpdated().awaitSingle()
        assertThatThrownBy {
            runBlocking {
                databaseClient.sql(
                    "update native_ad_placement_policies " +
                        "set minimum_seconds_between_deliveries = 59 where placement = 'COMMUNITY_FEED'",
                ).fetch().rowsUpdated().awaitSingle()
            }
        }.hasMessageContaining("chk_native_ad_placement_policy_limits")
        databaseClient.sql(
            "update native_ad_placement_policies " +
                "set minimum_seconds_between_deliveries = 21600 where placement = 'COMMUNITY_FEED'",
        ).fetch().rowsUpdated().awaitSingle()

        val slotIndexes = databaseClient.sql(
            """
            select index_name, non_unique
            from information_schema.statistics
            where table_schema = database()
              and table_name = 'native_ad_selection_history'
              and index_name = 'uk_native_ad_selection_history_slot'
            """.trimIndent(),
        ).map { row, _ ->
            row.get("index_name", String::class.java)!! to
                row.get("non_unique", java.lang.Long::class.java)!!.toLong()
        }.all().collectList().awaitSingle()
        assertThat(slotIndexes).containsExactly("uk_native_ad_selection_history_slot" to 0L)

        val cascades = databaseClient.sql(
            """
            select constraint_name, delete_rule
            from information_schema.referential_constraints
            where constraint_schema = database()
              and constraint_name in (
                'fk_native_ad_slots_user',
                'fk_native_ad_delivery_state_user',
                'fk_native_ad_selection_history_slot'
              )
            """.trimIndent(),
        ).map { row, _ ->
            row.get("constraint_name", String::class.java)!! to row.get("delete_rule", String::class.java)!!
        }.all().collectList().awaitSingle().toMap()
        assertThat(cascades).containsEntry("fk_native_ad_slots_user", "CASCADE")
        assertThat(cascades).containsEntry("fk_native_ad_delivery_state_user", "CASCADE")
        assertThat(cascades).containsEntry("fk_native_ad_selection_history_slot", "CASCADE")
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
