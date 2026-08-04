package com.buddystudy.backend.billing.adapter.inbound.scheduler

import com.buddystudy.backend.billing.application.port.inbound.BillingRecoveryUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.billing.recovery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BillingRecoveryScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val billingRecoveryJob: BillingRecoveryJob,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.billing.recovery.poll-ms:5000}",
        initialDelayString = "\${buddystudy.billing.recovery.initial-delay-ms:5000}",
    )
    suspend fun recover() {
        jobs.execute(billingRecoveryJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class BillingRecoveryJob(
    private val billingRecovery: BillingRecoveryUseCase,
) : ManagedJob {
    override val name: String = "billing-fulfillment-recovery"
    override val displayName: String = "Apple billing recovery"
    override val description: String =
        "Expires unpaid checkouts and recovers verified Apple charges interrupted during membership fulfillment."

    override suspend fun run(): String {
        val result = billingRecovery.recoverDueFulfillments()
        return "expiredCheckouts=${result.expiredCheckouts}, claimed=${result.claimed}, " +
            "completed=${result.completed}, retried=${result.retried}, " +
            "compensationRequired=${result.compensationRequired}"
    }
}
