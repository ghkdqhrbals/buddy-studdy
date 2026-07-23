package com.buddystudy.backend.scheduler.adapter.outbound.slack

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class SlackScheduledJobAlertAdapterTest {
    private val objectMapper = jacksonObjectMapper()
    private var server: DisposableServer? = null
    private val requestBodies = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() {
        server?.disposeNow()
        server = null
        requestBodies.clear()
    }

    @Test
    fun `notifyFailed posts scheduler failure without blocking a Reactor thread`() {
        val webhookUrl = startServer()
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = webhookUrl,
                environmentName = "test",
                serviceName = "BuddyStudy test",
                adminBaseUrl = "https://admin.ghkdqhrbals.org",
            ),
        )

        mono { adapter.notifyFailed(failedRun()) }
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(3))

        assertThat(requestBodies).hasSize(1)
        assertThat(requestBodies.single())
            .contains("BuddyStudy test job failed")
            .contains("question-scheduler")
            .contains("*Status*: FAILED")
            .contains("*Created by*: admin")
            .contains("*Retry of run*: 3")
            .contains("*Finished*: 2026-07-02T00:00:02Z")
            .contains("jobName=question-scheduler&runId=12")
            .contains("\"type\":\"button\"")
            .contains("Open scheduler run")
            .contains("boom")
    }

    @Test
    fun `notifyFailed encodes scheduler run url query parameters`(): Unit = runBlocking {
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = startServer(),
                adminBaseUrl = "https://admin.ghkdqhrbals.org",
            ),
        )

        adapter.notifyFailed(failedRun(jobName = "question schedule/prod&urgent"))

        assertThat(requestBodies.single())
            .contains("jobName=question+schedule%2Fprod%26urgent&runId=12")
    }

    @Test
    fun `notifyFailed is no-op when Slack webhook is not configured`(): Unit = runBlocking {
        adapter().notifyFailed(failedRun())

        assertThat(requestBodies).isEmpty()
    }

    @Test
    fun `notifyFailed propagates Slack delivery failure`() {
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = startServer(HttpStatus.INTERNAL_SERVER_ERROR),
            ),
        )

        assertThatThrownBy { runBlocking { adapter.notifyFailed(failedRun()) } }
            .hasMessageContaining("500")
    }

    @Test
    fun `slack timeout is bounded`() {
        val low = adapter(BuddyStudyProperties.Monitoring(slackTimeoutMs = 1))
        val high = adapter(BuddyStudyProperties.Monitoring(slackTimeoutMs = 999_999))

        assertThat(low.slackTimeout()).isEqualTo(Duration.ofMillis(1_000))
        assertThat(high.slackTimeout()).isEqualTo(Duration.ofMillis(25_000))
    }

    @Test
    fun `notifyFailed bounds Slack block text lengths and preserves error label`(): Unit = runBlocking {
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = startServer(),
                environmentName = "prod-" + "x".repeat(1_000),
                serviceName = "BuddyStudy " + "x".repeat(1_000),
                adminBaseUrl = "https://admin.ghkdqhrbals.org/" + "path/".repeat(400),
            ),
        )

        adapter.notifyFailed(
            failedRun(
                jobName = "question-scheduler-" + "x".repeat(1_000),
                createdBy = "admin-" + "x".repeat(1_000),
                errorMessage = "boom-" + "x".repeat(5_000),
            ),
        )

        val payload = objectMapper.readValue(
            requestBodies.single(),
            object : TypeReference<Map<String, Any>>() {},
        )
        @Suppress("UNCHECKED_CAST")
        val blocks = payload["blocks"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val headerText = (blocks[0]["text"] as Map<String, Any>)["text"] as String
        @Suppress("UNCHECKED_CAST")
        val sectionText = (blocks[1]["text"] as Map<String, Any>)["text"] as String

        assertThat(headerText).hasSizeLessThanOrEqualTo(150)
        assertThat(sectionText).hasSizeLessThanOrEqualTo(3_000)
        assertThat(sectionText).contains("*Error*:").contains("boom-")
    }

    @Test
    fun `notifyFailed suppresses repeated failure alerts within repeat interval`(): Unit = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-02T00:00:00Z"))
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = startServer(),
                schedulerFailureAlertRepeatSeconds = 300,
            ),
            clock = clock,
        )

        adapter.notifyFailed(failedRun())
        clock.advance(Duration.ofSeconds(120))
        adapter.notifyFailed(failedRun())

        assertThat(requestBodies).hasSize(1)
    }

    @Test
    fun `notifyFailed sends repeated failure alert after repeat interval`(): Unit = runBlocking {
        val clock = MutableClock(Instant.parse("2026-07-02T00:00:00Z"))
        val adapter = adapter(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = startServer(),
                schedulerFailureAlertRepeatSeconds = 300,
            ),
            clock = clock,
        )

        adapter.notifyFailed(failedRun())
        clock.advance(Duration.ofSeconds(301))
        adapter.notifyFailed(failedRun())

        assertThat(requestBodies).hasSize(2)
    }

    private fun startServer(status: HttpStatus = HttpStatus.OK): String {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route { routes ->
                routes.post("/scheduler") { request, response ->
                    request.receive().aggregate().asString().flatMap { body ->
                        requestBodies += body
                        response.status(status.value())
                            .sendString(Mono.just(if (status.is2xxSuccessful) "ok" else "slack unavailable"))
                            .then()
                    }
                }
            }
            .bindNow()
        return "http://127.0.0.1:${server!!.port()}/scheduler"
    }

    private fun adapter(
        monitoring: BuddyStudyProperties.Monitoring = BuddyStudyProperties.Monitoring(),
        clock: Clock = Clock.systemUTC(),
    ): SlackScheduledJobAlertAdapter =
        SlackScheduledJobAlertAdapter(
            properties = BuddyStudyProperties(monitoring = monitoring),
            webClient = WebClient.builder().build(),
            clock = clock,
        )

    private fun failedRun(
        jobName: String = "question-scheduler",
        createdBy: String = "admin",
        errorMessage: String = "boom",
    ): ScheduledJobRun =
        ScheduledJobRun(
            id = 12,
            jobName = jobName,
            triggerType = JobTriggerType.SCHEDULED,
            status = JobRunStatus.FAILED,
            startedAt = Instant.parse("2026-07-02T00:00:00Z"),
            finishedAt = Instant.parse("2026-07-02T00:00:02Z"),
            durationMs = 2_000,
            errorMessage = errorMessage,
            retryOfRunId = 3,
            createdBy = createdBy,
        )

    private class MutableClock(private var current: Instant) : Clock() {
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }

        override fun instant(): Instant = current

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
