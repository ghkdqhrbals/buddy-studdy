package com.buddystudy.backend.scheduler.adapter.outbound.slack

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobAlertPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.format.DateTimeFormatter

@Component
class SlackScheduledJobAlertAdapter internal constructor(
    private val properties: BuddyStudyProperties,
    private val restClient: RestClient,
) : ScheduledJobAlertPort {
    @Autowired
    constructor(
        properties: BuddyStudyProperties,
        restClientBuilder: RestClient.Builder,
    ) : this(
        properties,
        restClientBuilder
            .requestFactory(slackRequestFactory(properties))
            .build(),
    )

    override fun notifyFailed(run: ScheduledJobRun) {
        val webhookUrl = properties.monitoring.slackWebhookUrl.trim()
        if (webhookUrl.isBlank()) return

        restClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload(run))
            .retrieve()
            .toBodilessEntity()
    }

    private fun payload(run: ScheduledJobRun): Map<String, Any> {
        val title = ":rotating_light: ${properties.monitoring.serviceName} scheduled job failed"
        val lines = listOf(
            "*Environment*: ${properties.monitoring.environmentName}",
            "*Job*: ${run.jobName}",
            "*Run*: ${run.id}",
            "*Trigger*: ${run.triggerType}",
            "*Started*: ${DateTimeFormatter.ISO_INSTANT.format(run.startedAt)}",
            "*Duration*: ${run.durationMs ?: 0}ms",
            "*Error*: ${run.errorMessage?.take(600) ?: "Unknown error"}",
        )
        return mapOf(
            "text" to "$title - ${run.jobName}",
            "blocks" to listOf(
                mapOf(
                    "type" to "header",
                    "text" to mapOf(
                        "type" to "plain_text",
                        "text" to "${properties.monitoring.serviceName} job failed",
                    ),
                ),
                mapOf(
                    "type" to "section",
                    "text" to mapOf(
                        "type" to "mrkdwn",
                        "text" to lines.joinToString("\n"),
                    ),
                ),
            ),
        )
    }

    internal fun slackTimeout(): Duration =
        slackTimeout(properties)

    private companion object {
        fun slackTimeout(properties: BuddyStudyProperties): Duration =
            Duration.ofMillis(properties.monitoring.slackTimeoutMs.coerceIn(1_000, 25_000))

        fun slackRequestFactory(properties: BuddyStudyProperties): JdkClientHttpRequestFactory {
            val timeout = slackTimeout(properties)
            val client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
            return JdkClientHttpRequestFactory(client).apply {
                setReadTimeout(timeout)
            }
        }
    }
}
