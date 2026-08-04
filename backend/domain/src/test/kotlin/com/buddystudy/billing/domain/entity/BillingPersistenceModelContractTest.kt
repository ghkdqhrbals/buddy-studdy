package com.buddystudy.billing.domain.entity

import org.springframework.data.relational.core.mapping.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BillingPersistenceModelContractTest {
    @Test
    fun `every billing ledger table has an explicit persistence model`() {
        val models = mapOf(
            MembershipTierProductEntity::class.java to "membership_tier_products",
            AppleBillingAccountEntity::class.java to "apple_billing_accounts",
            InvoiceEntity::class.java to "invoices",
            InvoiceEventEntity::class.java to "invoice_events",
            PaymentEntity::class.java to "payments",
            PaymentHistoryEntity::class.java to "payments_history",
            BillingActionEntity::class.java to "billing_actions",
            BillingJobEntity::class.java to "billing_jobs",
            AppleBillingNotificationEntity::class.java to "apple_billing_notifications",
            RevenueCatBillingEventEntity::class.java to "revenuecat_billing_events",
        )

        models.forEach { (model, tableName) ->
            val table = assertNotNull(model.getAnnotation(Table::class.java), "${model.simpleName} must declare @Table")
            assertEquals(tableName, table.value, "${model.simpleName} points to the wrong table")
        }
    }
}
