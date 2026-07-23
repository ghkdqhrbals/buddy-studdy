package com.buddystudy.backend.monitoring.adapter.inbound.configuration

import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ReactorNettyMetricsConfiguration {
    @Bean
    fun reactorNettyServerMetricsCustomizer(): NettyServerCustomizer =
        NettyServerCustomizer { server ->
            server.metrics(true, ::normalizeMetricUri)
        }
}

internal fun normalizeMetricUri(uri: String): String {
    val path = uri.substringBefore('?')
    if (!path.startsWith('/')) return "UNKNOWN"
    return path.split('/')
        .joinToString("/") { segment ->
            when {
                segment.matches(UUID_SEGMENT) -> "{id}"
                segment.matches(NUMERIC_SEGMENT) -> "{id}"
                segment.length >= 24 && segment.matches(OPAQUE_ID_SEGMENT) -> "{id}"
                else -> segment
            }
        }
}

private val UUID_SEGMENT =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val NUMERIC_SEGMENT = Regex("\\d+")
private val OPAQUE_ID_SEGMENT = Regex("[A-Za-z0-9_-]+")
