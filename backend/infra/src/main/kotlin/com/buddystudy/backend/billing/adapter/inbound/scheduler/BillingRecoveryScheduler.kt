package com.buddystudy.backend.billing.adapter.inbound.scheduler

import com.buddystudy.backend.billing.application.port.inbound.BillingRecoveryUseCase
import com.buddystudy.backend.billing.application.port.inbound.BillingReconciliationUseCase
import com.buddystudy.backend.billing.application.port.inbound.RevenueCatEventProjectionUseCase
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

@Component
@ConditionalOnProperty(prefix = "buddystudy.billing.reconciliation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BillingReconciliationScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val reconciliationJob: BillingReconciliationJob,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.billing.reconciliation.poll-ms:900000}",
        initialDelayString = "\${buddystudy.billing.reconciliation.initial-delay-ms:60000}",
    )
    suspend fun reconcile() {
        jobs.execute(reconciliationJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class BillingReconciliationJob(
    private val reconciliation: BillingReconciliationUseCase,
) : ManagedJob {
    override val name: String = "billing-subscription-reconciliation"
    override val displayName: String = "RevenueCat subscription reconciliation"
    override val description: String =
        "Reconciles due subscription projections with RevenueCat; failures retry at most three times and remain auditable."

    override suspend fun run(): String = "claimed=${reconciliation.reconcileDueSubscriptions()}"
}

@Component
@ConditionalOnProperty(prefix = "buddystudy.billing.event-projector", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BillingSubscriptionEventProjectorScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val projectorJob: BillingSubscriptionEventProjectorJob,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.billing.event-projector.poll-ms:1000}",
        initialDelayString = "\${buddystudy.billing.event-projector.initial-delay-ms:1000}",
    )
    suspend fun project() {
        jobs.execute(projectorJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class BillingSubscriptionEventProjectorJob(
    private val projector: RevenueCatEventProjectionUseCase,
) : ManagedJob {
    override val name: String = "billing-subscription-event-projector"
    override val displayName: String = "Subscription event projector"
    override val description: String =
        "Projects committed RevenueCat receipts asynchronously; failed events retry three times and remain auditable."

    override suspend fun run(): String = "claimed=${projector.projectDueEvents()}"
}
