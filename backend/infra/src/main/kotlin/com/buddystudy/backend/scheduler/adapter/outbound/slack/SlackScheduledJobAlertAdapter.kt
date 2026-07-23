package com.buddystudy.backend.scheduler.adapter.outbound.slack

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobAlertPort
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

@Component
class SlackScheduledJobAlertAdapter internal constructor(
    private val properties: BuddyStudyProperties,
    private val webClient: WebClient,
    private val clock: Clock = Clock.systemUTC(),
) : ScheduledJobAlertPort {
    private val lastSuccessfulAlertAtByJob = ConcurrentHashMap<String, Instant>()

    @Autowired
    constructor(
        properties: BuddyStudyProperties,
        webClientBuilder: WebClient.Builder,
    ) : this(
        properties,
        slackWebClient(properties, webClientBuilder),
    )

    override suspend fun notifyFailed(run: ScheduledJobRun) {
        val webhookUrl = properties.monitoring.slackWebhookUrl.trim()
        if (webhookUrl.isBlank()) return
        val now = clock.instant()
        if (isSuppressed(run.jobName, now)) return

        webClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload(run))
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()
        lastSuccessfulAlertAtByJob[run.jobName] = now
    }

    private fun isSuppressed(jobName: String, now: Instant): Boolean {
        val lastAlertAt = lastSuccessfulAlertAtByJob[jobName] ?: return false
        return Duration.between(lastAlertAt, now) < schedulerFailureAlertRepeat()
    }

    private fun payload(run: ScheduledJobRun): Map<String, Any> {
        val title = ":rotating_light: ${properties.monitoring.serviceName} scheduled job failed"
        val runUrl = adminRunUrl(run)
        val lines = listOf(
            slackLine("Environment", properties.monitoring.environmentName),
            slackLine("Job", run.jobName),
            slackLine("Run", run.id),
            slackLine("Created by", run.createdBy),
            slackLine("Retry of run", run.retryOfRunId ?: "none"),
            slackLine("Trigger", run.triggerType),
            slackLine("Status", run.status),
            slackLine("Started", DateTimeFormatter.ISO_INSTANT.format(run.startedAt)),
            slackLine("Finished", run.finishedAt?.let { DateTimeFormatter.ISO_INSTANT.format(it) } ?: "unknown"),
            slackLine("Duration", "${run.durationMs ?: 0}ms"),
            slackLine("Run URL", runUrl ?: "not configured"),
            slackLine("Error", run.errorMessage ?: "Unknown error"),
        )
        val sectionText = truncateSlackText(lines.joinToString("\n"), SECTION_TEXT_LIMIT)
        val blocks = mutableListOf<Map<String, Any>>(
            mapOf(
                "type" to "header",
                "text" to mapOf(
                    "type" to "plain_text",
                    "text" to truncateSlackText("${properties.monitoring.serviceName} job failed", HEADER_TEXT_LIMIT),
                ),
            ),
            mapOf(
                "type" to "section",
                "text" to mapOf(
                    "type" to "mrkdwn",
                    "text" to sectionText,
                ),
            ),
        )
        if (runUrl != null && runUrl.length <= BUTTON_URL_LIMIT) {
            blocks += mapOf(
                "type" to "actions",
                "elements" to listOf(
                    mapOf(
                        "type" to "button",
                        "text" to mapOf(
                            "type" to "plain_text",
                            "text" to "Open scheduler run",
                        ),
                        "url" to runUrl,
                    ),
                ),
            )
        }
        return mapOf(
            "text" to truncateSlackText("$title - ${run.jobName}", FALLBACK_TEXT_LIMIT),
            "blocks" to blocks,
        )
    }

    internal fun slackTimeout(): Duration =
        slackTimeout(properties)

    internal fun schedulerFailureAlertRepeat(): Duration =
        schedulerFailureAlertRepeat(properties)

    private fun adminRunUrl(run: ScheduledJobRun): String? {
        val baseUrl = properties.monitoring.adminBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return null
        val jobName = URLEncoder.encode(run.jobName, StandardCharsets.UTF_8)
        return "$baseUrl/operations/scheduler-runs?jobName=$jobName&runId=${run.id}"
    }

    private companion object {
        private const val HEADER_TEXT_LIMIT = 150
        private const val SECTION_TEXT_LIMIT = 3_000
        private const val LINE_VALUE_LIMIT = 220
        private const val FALLBACK_TEXT_LIMIT = 4_000
        private const val BUTTON_URL_LIMIT = 3_000

        fun slackTimeout(properties: BuddyStudyProperties): Duration =
            Duration.ofMillis(properties.monitoring.slackTimeoutMs.coerceIn(1_000, 25_000))

        fun schedulerFailureAlertRepeat(properties: BuddyStudyProperties): Duration =
            Duration.ofSeconds(properties.monitoring.schedulerFailureAlertRepeatSeconds.coerceIn(60, 86_400))

        fun slackWebClient(properties: BuddyStudyProperties, builder: WebClient.Builder): WebClient {
            val timeout = slackTimeout(properties)
            val client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt())
                .responseTimeout(timeout)
            return builder
                .clientConnector(ReactorClientHttpConnector(client))
                .build()
        }

        fun truncateSlackText(value: String, maxLength: Int): String {
            if (value.length <= maxLength) return value
            if (maxLength <= 3) return value.take(maxLength)
            return value.take(maxLength - 3) + "..."
        }

        fun slackLine(label: String, value: Any?): String =
            "*$label*: ${truncateSlackText(value?.toString() ?: "unknown", LINE_VALUE_LIMIT)}"
    }
}
