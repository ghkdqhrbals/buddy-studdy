package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class AdminAnalyticsSourceAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///admin-record-types-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = AdminAnalyticsSourceAdapter(database)
    private val date = LocalDate.parse("2026-08-31")

    @Test
    fun `voice records do not change generated question answer latency or either side of streak metrics`(): Unit = runBlocking {
        schema()
        question(1, 1, "QUESTION", "2026-08-30T01:00:00Z", 20)
        question(2, 1, "QUESTION", "2026-08-31T01:00:00Z", 10)
        question(3, 2, "QUESTION", "2026-08-30T02:00:00Z", 20)
        question(4, 3, "QUESTION", "2026-08-31T02:00:00Z", 30)
        val before = adapter.collectDailyMetrics(date).associateBy { it.metricKey }
        assertThat(before.getValue("question_created_count").value).isEqualTo(2.0)
        assertThat(before.getValue("answer_submitted_count").value).isEqualTo(2.0)
        assertThat(before.getValue("question_to_answer_latency").value).isEqualTo(20.0)
        assertThat(before.getValue("weekly_active_learners").value).isEqualTo(3.0)
        assertThat(before.getValue("study_streak").value).isEqualTo(1.0)

        // Each row exercises an independent way that shared records could contaminate a question metric.
        question(10, 2, "VOICE_TUTOR", "2026-08-31T04:00:00Z", 150)
        question(11, 3, "VOICE_TUTOR", "2026-08-30T04:00:00Z", 150)
        question(12, 4, "VOICE_TUTOR", "2026-08-30T05:00:00Z", 150)
        question(13, 4, "VOICE_TUTOR", "2026-08-31T05:00:00Z", 150)

        assertThat(adapter.collectDailyMetrics(date).associateBy { it.metricKey }).isEqualTo(before)
        assertThat(before.getValue("daily_active_users").value).isEqualTo(1.0)
        assertThat(before.getValue("quota_used_count").value).isEqualTo(5.0)
    }

    @Test
    fun `voice only activity leaves generated question metrics at zero without changing device activity`(): Unit = runBlocking {
        schema()
        question(20, 1, "VOICE_TUTOR", "2026-08-30T05:00:00Z", 60)
        question(21, 1, "VOICE_TUTOR", "2026-08-31T05:00:00Z", 60)

        val metrics = adapter.collectDailyMetrics(date).associateBy { it.metricKey }
        listOf("question_created_count", "answer_submitted_count", "question_to_answer_latency", "weekly_active_learners", "study_streak", "answer_rate").forEach {
            assertThat(metrics.getValue(it).value).describedAs(it).isZero()
            assertThat(metrics.getValue(it).sampleCount).describedAs("$it sample count").isZero()
        }
        assertThat(metrics.getValue("daily_active_users").value).isEqualTo(1.0)
    }

    private suspend fun schema() {
        execute("create table questions (id bigint primary key, user_id bigint not null, record_type varchar(24) not null, created_at timestamp with time zone, answered_at timestamp with time zone, deleted_at timestamp with time zone)")
        execute("create table user_devices (user_id bigint, last_seen_at timestamp with time zone)")
        execute("create table app_notifications (should_push boolean, push_sent_at timestamp with time zone, read_at timestamp with time zone, deleted_at timestamp with time zone)")
        execute("create table user_quota_history (committed_delta bigint, affected_period_started_at timestamp with time zone, affected_period_ends_at timestamp with time zone, occurred_at timestamp with time zone)")
        execute("insert into user_devices values (1, '2026-08-31 05:00:00+00')")
        execute("insert into app_notifications values (true, '2026-08-31 01:00:00+00', '2026-08-31 01:01:00+00', null)")
        execute("insert into user_quota_history values (5, '2026-08-01 00:00:00+00', '2026-09-01 00:00:00+00', '2026-08-31 01:00:00+00')")
    }

    private suspend fun question(id: Long, user: Long, type: String, created: String, delay: Long) {
        val at = OffsetDateTime.parse(created)
        database.sql("insert into questions (id,user_id,record_type,created_at,answered_at) values (:id,:user,:type,:created,:answered)")
            .bind("id", id).bind("user", user).bind("type", type).bind("created", at).bind("answered", at.plusSeconds(delay))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
}
