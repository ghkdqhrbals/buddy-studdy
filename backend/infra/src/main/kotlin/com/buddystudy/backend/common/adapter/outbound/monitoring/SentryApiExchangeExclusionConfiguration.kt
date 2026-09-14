package com.buddystudy.backend.common.adapter.outbound.monitoring

import com.buddystudy.backend.common.adapter.inbound.web.RequestLoggingFilter
import io.sentry.SentryOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SentryApiExchangeExclusionConfiguration {
    @Bean
    fun apiExchangeBeforeSendCallback(): SentryOptions.BeforeSendCallback =
        SentryOptions.BeforeSendCallback { event, _ ->
            if (ApiExchangeSentryBoundary.matches(event.logger, event.message?.formatted)) null else event
        }

    @Bean
    fun apiExchangeBeforeBreadcrumbCallback(): SentryOptions.BeforeBreadcrumbCallback =
        SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            if (ApiExchangeSentryBoundary.matches(breadcrumb.category, breadcrumb.message)) null else breadcrumb
        }
}

internal object ApiExchangeSentryBoundary {
    private val loggerName = RequestLoggingFilter::class.java.name

    fun matches(logger: String?, message: String?): Boolean =
        logger == loggerName &&
            (message?.startsWith("api_exchange ") == true || message?.startsWith("api_response ") == true)
}
