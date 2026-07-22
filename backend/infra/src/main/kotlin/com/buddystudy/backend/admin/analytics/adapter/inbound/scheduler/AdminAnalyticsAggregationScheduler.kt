package com.buddystudy.backend.admin.analytics.adapter.inbound.scheduler

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
@ConditionalOnProperty(prefix = "buddystudy.analytics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AdminAnalyticsAggregationScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val recentJob: AdminAnalyticsRecentJob,
    private val correctionJob: AdminAnalyticsCorrectionJob,
) {
    @Scheduled(cron = "\${buddystudy.analytics.recent-cron:0 */5 * * * *}")
    suspend fun refreshRecent() {
        jobs.execute(recentJob, JobTriggerType.SCHEDULED)
    }

    @Scheduled(cron = "\${buddystudy.analytics.correction-cron:0 20 3 * * *}")
    suspend fun refreshCorrection() {
        jobs.execute(correctionJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class AdminAnalyticsRecentJob(
    private val analytics: AdminAnalyticsAggregationUseCase,
) : ManagedJob {
    override val name: String = "admin-analytics-recent"

    override suspend fun run(): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        return "rows=${analytics.refreshRecent(today)} referenceDate=$today"
    }
}

@Component
class AdminAnalyticsCorrectionJob(
    private val analytics: AdminAnalyticsAggregationUseCase,
) : ManagedJob {
    override val name: String = "admin-analytics-correction"

    override suspend fun run(): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        return "rows=${analytics.refreshCorrection(today)} referenceDate=$today"
    }
}
