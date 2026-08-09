package com.buddystudy.billing.domain.entity

import org.springframework.data.relational.core.mapping.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BillingPersistenceModelContractTest {
    @Test
    fun `every billing ledger table has an explicit persistence model`() {
        val models = mapOf(
            UserQuotaEntity::class.java to "user_quota",
            UserQuotaHistoryEntity::class.java to "user_quota_history",
            QuotaReservationEntity::class.java to "quota_reservations",
            MembershipTierProductEntity::class.java to "membership_tier_products",
            AppleBillingAccountEntity::class.java to "apple_billing_accounts",
            InvoiceEntity::class.java to "invoices",
            InvoiceEventEntity::class.java to "invoice_events",
            PaymentEntity::class.java to "payments",
            PaymentHistoryEntity::class.java to "payments_history",
            BillingActionEntity::class.java to "billing_actions",
            BillingJobEntity::class.java to "billing_fulfillment_outbox",
            AppleBillingNotificationEntity::class.java to "billing_apple_notification_inbox",
            RevenueCatBillingEventEntity::class.java to "billing_revenuecat_event_inbox",
        )

        models.forEach { (model, tableName) ->
            val table = assertNotNull(model.getAnnotation(Table::class.java), "${model.simpleName} must declare @Table")
            assertEquals(tableName, table.value, "${model.simpleName} points to the wrong table")
        }
    }
}
