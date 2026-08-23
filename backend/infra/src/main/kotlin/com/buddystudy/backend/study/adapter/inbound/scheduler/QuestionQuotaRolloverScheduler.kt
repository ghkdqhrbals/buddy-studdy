package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "buddystudy.quota.rollover",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class QuestionQuotaRolloverScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val rolloverJob: QuestionQuotaRolloverJob,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.quota.rollover.poll-ms:60000}",
        initialDelayString = "\${buddystudy.quota.rollover.initial-delay-ms:10000}",
    )
    suspend fun rollover() {
        jobs.execute(rolloverJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class QuestionQuotaRolloverJob(
    private val rollover: QuestionQuotaRolloverUseCase,
) : ManagedJob {
    override val name: String = "user-quota-rollover"
    override val displayName: String = "Monthly question quota rollover"
    override val description: String =
        "Advances expired user quota rows and records each reset in the append-only quota history."

    override suspend fun run(): String = "rolledOver=${rollover.rolloverDue().rolledOver}"
}
