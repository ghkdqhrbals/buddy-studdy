package com.buddystuddy.backend.stats.adapter.inbound.scheduler

import com.buddystuddy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystuddy.stats", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class UserStatsRefreshScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val userStatsRefreshJob: UserStatsRefreshJob,
) {
    @Scheduled(cron = "\${buddystuddy.stats.cron:0 */5 * * * *}")
    fun refresh() {
        jobs.execute(userStatsRefreshJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class UserStatsRefreshJob(
    private val refreshUserStats: RefreshUserStatsUseCase,
) : ManagedJob {
    override val name: String = "user-stats-refresh"

    override fun run(): String {
        val now = Instant.now()
        refreshUserStats.refreshAll(now)
        return "refreshedAt=$now"
    }
}
