package com.buddystudy.backend.notification.adapter.inbound.scheduler

import com.buddystudy.backend.notification.application.port.inbound.DispatchPushCampaignsUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PushCampaignScheduler(private val jobs: ManagedJobExecutionUseCase, private val dispatch: PushCampaignDispatchJob) {
    @Scheduled(fixedDelayString = "\${buddystudy.push-campaigns.poll-ms:5000}")
    suspend fun dispatch() { jobs.execute(dispatch, JobTriggerType.SCHEDULED) }
}

@Component
class PushCampaignDispatchJob(private val campaigns: DispatchPushCampaignsUseCase) : ManagedJob {
    override val name = "push-campaign-dispatch"
    override val displayName = "Push campaign dispatch"
    override val description = "Appends one bounded batch of saved campaign recipients to the durable notification outbox."
    override suspend fun run(): String = "enqueued=${campaigns.dispatchBatch()}"
}
