package com.buddystudy.backend.stats.adapter.inbound.scheduler

import com.buddystudy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.stats", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class UserStatsRefreshScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val userStatsRefreshJob: UserStatsRefreshJob,
) {
    @Scheduled(cron = "\${buddystudy.stats.cron:0 */5 * * * *}")
    suspend fun refresh() {
        jobs.execute(userStatsRefreshJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class UserStatsRefreshJob(
    private val refreshUserStats: RefreshUserStatsUseCase,
) : ManagedJob {
    override val name: String = "user-stats-refresh"
    override val displayName: String = "User learning statistics refresh"
    override val description: String =
        "Rebuilds user and topic learning statistics from completed grading records."

    override suspend fun run(): String {
        val now = Instant.now()
        refreshUserStats.refreshAll(now)
        return "refreshedAt=$now"
    }
}
