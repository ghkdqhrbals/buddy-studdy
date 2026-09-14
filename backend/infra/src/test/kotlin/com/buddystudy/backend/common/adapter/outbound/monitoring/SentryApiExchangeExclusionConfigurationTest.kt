package com.buddystudy.backend.common.adapter.outbound.monitoring

import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SentryApiExchangeExclusionConfigurationTest {
    private val configuration = SentryApiExchangeExclusionConfiguration()

    @Test
    fun `raw api exchange events are excluded from Sentry`() {
        val event = SentryEvent().apply {
            logger = RequestLoggingFilter::class.java.name
            message = Message().apply {
                formatted = "api_exchange {\"requestHeaders\":{\"authorization\":\"Bearer value\"}}"
            }
        }

        assertThat(configuration.apiExchangeBeforeSendCallback().execute(event, Hint())).isNull()
    }

    @Test
    fun `raw api exchange breadcrumbs are excluded from Sentry`() {
        val breadcrumb = Breadcrumb().apply {
            category = RequestLoggingFilter::class.java.name
            message = "api_response {\"responseHeaders\":{\"set-cookie\":\"session=value\"}}"
        }

        assertThat(configuration.apiExchangeBeforeBreadcrumbCallback().execute(breadcrumb, Hint())).isNull()
    }

    @Test
    fun `other backend diagnostics remain available to Sentry`() {
        val event = SentryEvent().apply {
            logger = RequestLoggingFilter::class.java.name
            message = Message().apply { formatted = "api_exchange_logging_failed message=serialization" }
        }

        assertThat(configuration.apiExchangeBeforeSendCallback().execute(event, Hint())).isSameAs(event)
    }
}
