package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.inbound.ManagePushCampaignsUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class AdminPushCampaignWebAdapterTest {
    @Test
    fun `every operation requires administrator authentication before campaign access`(): Unit = runBlocking {
        val authentication = Mockito.mock(AdminAnalyticsUseCase::class.java)
        val campaigns = Mockito.mock(ManagePushCampaignsUseCase::class.java)
        val adapter = AdminPushCampaignWebAdapter(authentication, campaigns)
        Mockito.`when`(authentication.validate("invalid")).thenThrow(IllegalArgumentException("Invalid admin session"))
        val command = PushCampaignCommand(title = "Title", body = "Body", deepLink = "buddystudy://public", audience = PushCampaignAudience.ALL_REGISTERED)
        val operations: List<suspend () -> Any> = listOf(
            { adapter.list("invalid", 20, 0) }, { adapter.campaign("invalid", "id") },
            { adapter.preview("invalid", command) }, { adapter.create("invalid", command) }, { adapter.send("invalid", "id") },
        )
        for (operation in operations) assertThatThrownBy { runBlocking { operation() } }.isInstanceOf(IllegalArgumentException::class.java)
        Mockito.verifyNoInteractions(campaigns)
    }
}
