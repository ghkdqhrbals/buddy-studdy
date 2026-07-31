package com.buddystudy.backend.common.adapter.inbound.scheduler

import com.buddystudy.backend.common.application.outbox.RecoverOutboxUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRecoveryScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val recoveryJob: OutboxRecoveryJob,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.outbox.poll-ms:1000}")
    suspend fun recoverPending() {
        jobs.execute(recoveryJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class OutboxRecoveryJob(
    private val outboxes: RecoverOutboxUseCase,
) : ManagedJob {
    override val name: String = "event-outbox-dispatch"
    override val displayName: String = "Event outbox recovery"
    override val description: String =
        "Finds unpublished database outbox events and republishes them to the appropriate Redis Stream."

    override suspend fun run(): String {
        val result = outboxes.recoverPending()
        return "attempted=${result.attempted},published=${result.published},retryScheduled=${result.retryScheduled}"
    }
}
