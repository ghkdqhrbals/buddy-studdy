package com.buddystuddy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystuddy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Repository
class AdminAnalyticsSourceAdapter(
    private val entityManager: EntityManager,
) : AdminAnalyticsSourcePort {
    @Transactional(readOnly = true)
    override fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint> {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val weekStart = date.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC)
        val previousStart = date.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

        val questionCreated = count(
            "select count(*) from questions where created_at >= :start and created_at < :end and deleted_at is null",
            start,
            end,
        )
        val answers = count(
            "select count(*) from questions where answered_at >= :start and answered_at < :end and deleted_at is null",
            start,
            end,
        )
        val pushSent = count(
            "select count(*) from app_notifications where should_push = true and push_sent_at >= :start and push_sent_at < :end and deleted_at is null",
            start,
            end,
        )
        val pushOpened = count(
            "select count(*) from app_notifications where should_push = true and push_sent_at >= :start and push_sent_at < :end and read_at is not null and deleted_at is null",
            start,
            end,
        )
        val rows = mutableListOf(
            point(date, "daily_active_users", count("select count(distinct user_id) from user_devices where last_seen_at >= :start and last_seen_at < :end", start, end)),
            point(date, "weekly_active_learners", count("select count(distinct user_id) from questions where answered_at >= :start and answered_at < :end and deleted_at is null", weekStart, end)),
            point(date, "question_created_count", questionCreated),
            point(date, "answer_submitted_count", answers),
            AdminDailyMetricPoint(date, "answer_rate", null, ratio(answers, questionCreated), sampleCount = questionCreated),
            AdminDailyMetricPoint(date, "push_open_rate", null, ratio(pushOpened, pushSent), sampleCount = pushSent),
            AdminDailyMetricPoint(date, "question_to_answer_latency", null, averageAnswerLatencySeconds(start, end), sampleCount = answers),
            point(date, "study_streak", streakUsers(previousStart, start, end)),
            point(date, "quota_used_count", quotaUsed(date)),
        )
        return rows
    }

    private fun point(date: LocalDate, metricKey: String, value: Long): AdminDailyMetricPoint =
        AdminDailyMetricPoint(date, metricKey, null, value.toDouble(), sampleCount = value)

    private fun ratio(numerator: Long, denominator: Long): Double =
        if (denominator <= 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    private fun count(sql: String, start: Instant, end: Instant): Long =
        (entityManager.createNativeQuery(sql)
            .setParameter("start", Timestamp.from(start))
            .setParameter("end", Timestamp.from(end))
            .singleResult as Number).toLong()

    private fun averageAnswerLatencySeconds(start: Instant, end: Instant): Double =
        ((entityManager.createNativeQuery(
            """
            select coalesce(avg(extract(epoch from (answered_at - created_at))), 0)
            from questions
            where answered_at >= :start
              and answered_at < :end
              and created_at is not null
              and deleted_at is null
            """.trimIndent()
        )
            .setParameter("start", Timestamp.from(start))
            .setParameter("end", Timestamp.from(end))
            .singleResult as Number).toDouble())

    private fun streakUsers(previousStart: Instant, start: Instant, end: Instant): Long =
        (entityManager.createNativeQuery(
            """
            select count(distinct today.user_id)
            from questions today
            where today.answered_at >= :start
              and today.answered_at < :end
              and today.deleted_at is null
              and exists (
                  select 1
                  from questions previous
                  where previous.user_id = today.user_id
                    and previous.answered_at >= :previousStart
                    and previous.answered_at < :start
                    and previous.deleted_at is null
              )
            """.trimIndent()
        )
            .setParameter("previousStart", Timestamp.from(previousStart))
            .setParameter("start", Timestamp.from(start))
            .setParameter("end", Timestamp.from(end))
            .singleResult as Number).toLong()

    private fun quotaUsed(date: LocalDate): Long =
        (entityManager.createNativeQuery(
            """
            select coalesce(sum(system_question_count), 0)
            from user_monthly_question_usage
            where year_month = :yearMonth
            """.trimIndent()
        )
            .setParameter("yearMonth", date.toString().take(7))
            .singleResult as Number).toLong()

}
