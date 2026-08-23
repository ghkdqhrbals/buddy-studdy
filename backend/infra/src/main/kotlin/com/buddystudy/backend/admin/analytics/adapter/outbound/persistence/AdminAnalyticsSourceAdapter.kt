package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Repository
class AdminAnalyticsSourceAdapter(
    private val client: DatabaseClient,
) : AdminAnalyticsSourcePort {
    override suspend fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint> {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val weekStart = date.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC)
        val previousStart = date.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val created = count("select count(*) from questions where created_at >= :start and created_at < :end and deleted_at is null", start, end)
        val answers = count("select count(*) from questions where answered_at >= :start and answered_at < :end and deleted_at is null", start, end)
        val pushSent = count("select count(*) from app_notifications where should_push = true and push_sent_at >= :start and push_sent_at < :end and deleted_at is null", start, end)
        val pushOpened = count("select count(*) from app_notifications where should_push = true and push_sent_at >= :start and push_sent_at < :end and read_at is not null and deleted_at is null", start, end)
        return listOf(
            point(date, "daily_active_users", count("select count(distinct user_id) from user_devices where last_seen_at >= :start and last_seen_at < :end", start, end)),
            point(date, "weekly_active_learners", count("select count(distinct user_id) from questions where answered_at >= :start and answered_at < :end and deleted_at is null", weekStart, end)),
            point(date, "question_created_count", created), point(date, "answer_submitted_count", answers),
            AdminDailyMetricPoint(date, "answer_rate", null, ratio(answers, created), created),
            AdminDailyMetricPoint(date, "push_open_rate", null, ratio(pushOpened, pushSent), pushSent),
            AdminDailyMetricPoint(date, "question_to_answer_latency", null, averageLatency(start, end), answers),
            point(date, "study_streak", streakUsers(previousStart, start, end)),
            point(date, "quota_used_count", quotaUsed(date)),
        )
    }

    private suspend fun count(sql: String, start: Instant, end: Instant): Long =
        client.sql(sql).bind("start", start).bind("end", end)
            .map { row, _ -> (row.get(0) as Number).toLong() }.one().awaitSingleOrNull() ?: 0

    private suspend fun averageLatency(start: Instant, end: Instant): Double =
        client.sql(
            """
            select coalesce(avg(timestampdiff(microsecond, created_at, answered_at) / 1000000.0), 0) from questions
            where answered_at >= :start and answered_at < :end and created_at is not null and deleted_at is null
            """.trimIndent(),
        ).bind("start", start).bind("end", end)
            .map { row, _ -> (row.get(0) as Number).toDouble() }.one().awaitSingleOrNull() ?: 0.0

    private suspend fun streakUsers(previousStart: Instant, start: Instant, end: Instant): Long =
        client.sql(
            """
            select count(distinct today.user_id) from questions today
            where today.answered_at >= :start and today.answered_at < :end and today.deleted_at is null
              and exists (select 1 from questions previous where previous.user_id = today.user_id
                and previous.answered_at >= :previousStart and previous.answered_at < :start and previous.deleted_at is null)
            """.trimIndent(),
        ).bind("previousStart", previousStart).bind("start", start).bind("end", end)
            .map { row, _ -> (row.get(0) as Number).toLong() }.one().awaitSingleOrNull() ?: 0

    private suspend fun quotaUsed(date: LocalDate): Long {
        val at = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1)
        return client.sql(
            """
            select coalesce(sum(committed_delta), 0) as quota_used
            from user_quota_history
            where affected_period_started_at <= :at
              and affected_period_ends_at > :at
              and occurred_at <= :at
            """.trimIndent(),
        ).bind("at", at)
            .map { row, _ -> (row.get("quota_used") as Number).toLong().coerceAtLeast(0) }
            .one().awaitSingleOrNull() ?: 0L
    }

    private fun point(date: LocalDate, key: String, value: Long) = AdminDailyMetricPoint(date, key, null, value.toDouble(), value)
    private fun ratio(numerator: Long, denominator: Long) = if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
}
