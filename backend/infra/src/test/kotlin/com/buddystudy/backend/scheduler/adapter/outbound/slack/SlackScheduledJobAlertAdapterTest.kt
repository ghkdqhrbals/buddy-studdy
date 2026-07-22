package com.buddystudy.backend.scheduler.adapter.outbound.slack

import kotlinx.coroutines.runBlocking

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.springframework.mock.http.client.MockClientHttpRequest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SlackScheduledJobAlertAdapterTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `notifyFailed posts scheduler failure to Slack webhook`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                environmentName = "test",
                serviceName = "BuddyStudy test",
                adminBaseUrl = "https://admin.ghkdqhrbals.org",
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build())

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("BuddyStudy test job failed")))
            .andExpect(content().string(containsString("question-scheduler")))
            .andExpect(content().string(containsString("*Status*: FAILED")))
            .andExpect(content().string(containsString("*Created by*: admin")))
            .andExpect(content().string(containsString("*Retry of run*: 3")))
            .andExpect(content().string(containsString("*Finished*: 2026-07-02T00:00:02Z")))
            .andExpect(content().string(containsString("*Run URL*: https://admin.ghkdqhrbals.org/operations/scheduler-runs?jobName=question-scheduler&runId=12")))
            .andExpect(content().string(containsString("\"type\":\"button\"")))
            .andExpect(content().string(containsString("\"text\":\"Open scheduler run\"")))
            .andExpect(content().string(containsString("\"url\":\"https://admin.ghkdqhrbals.org/operations/scheduler-runs?jobName=question-scheduler&runId=12\"")))
            .andExpect(content().string(containsString("boom")))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed encodes scheduler run url query parameters`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                adminBaseUrl = "https://admin.ghkdqhrbals.org",
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build())

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect(content().string(containsString("jobName=question+schedule%2Fprod%26urgent&runId=12")))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun(jobName = "question schedule/prod&urgent"))

        server.verify()
    }

    @Test
    fun `notifyFailed is no-op when Slack webhook is not configured`(): Unit = runBlocking {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(BuddyStudyProperties(), builder.build())

        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed propagates Slack delivery failure`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build())

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("slack unavailable"))

        assertThatThrownBy { runBlocking { adapter.notifyFailed(failedRun()) } }
            .hasMessageContaining("500")

        server.verify()
    }

    @Test
    fun `slack timeout is bounded`(): Unit = runBlocking {
        val low = SlackScheduledJobAlertAdapter(
            BuddyStudyProperties(monitoring = BuddyStudyProperties.Monitoring(slackTimeoutMs = 1)),
            RestClient.builder(),
        )
        val high = SlackScheduledJobAlertAdapter(
            BuddyStudyProperties(monitoring = BuddyStudyProperties.Monitoring(slackTimeoutMs = 999_999)),
            RestClient.builder(),
        )

        assertThat(low.slackTimeout()).isEqualTo(Duration.ofMillis(1_000))
        assertThat(high.slackTimeout()).isEqualTo(Duration.ofMillis(25_000))
    }

    @Test
    fun `notifyFailed bounds Slack block text lengths`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                environmentName = "prod-" + "x".repeat(1_000),
                serviceName = "BuddyStudy " + "x".repeat(1_000),
                adminBaseUrl = "https://admin.ghkdqhrbals.org/" + "path/".repeat(400),
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build())

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect { request ->
                val payload = objectMapper.readValue(
                    (request as MockClientHttpRequest).bodyAsString,
                    object : TypeReference<Map<String, Any>>() {},
                )
                val blocks = payload["blocks"] as List<Map<String, Any>>
                val headerText = ((blocks[0]["text"] as Map<String, Any>)["text"] as String)
                val sectionText = ((blocks[1]["text"] as Map<String, Any>)["text"] as String)

                assertThat(headerText).hasSizeLessThanOrEqualTo(150)
                assertThat(sectionText).hasSizeLessThanOrEqualTo(3_000)
            }
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(
            failedRun(
                jobName = "question-scheduler-" + "x".repeat(1_000),
                createdBy = "admin-" + "x".repeat(1_000),
                errorMessage = "boom-" + "x".repeat(5_000),
            ),
        )

        server.verify()
    }

    @Test
    fun `notifyFailed suppresses repeated failure alerts within repeat interval`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                schedulerFailureAlertRepeatSeconds = 300,
            ),
        )
        val clock = MutableClock(Instant.parse("2026-07-02T00:00:00Z"))
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build(), clock)

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun())
        clock.advance(Duration.ofSeconds(120))
        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed sends repeated failure alert after repeat interval`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                schedulerFailureAlertRepeatSeconds = 300,
            ),
        )
        val clock = MutableClock(Instant.parse("2026-07-02T00:00:00Z"))
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build(), clock)

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))
        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun())
        clock.advance(Duration.ofSeconds(301))
        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed preserves error label when payload values are long`(): Unit = runBlocking {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                environmentName = "prod-" + "x".repeat(1_000),
                serviceName = "BuddyStudy " + "x".repeat(1_000),
                adminBaseUrl = "https://admin.ghkdqhrbals.org/" + "path/".repeat(400),
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(properties, builder.build())

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect { request ->
                val payload = objectMapper.readValue(
                    (request as MockClientHttpRequest).bodyAsString,
                    object : TypeReference<Map<String, Any>>() {},
                )
                val blocks = payload["blocks"] as List<Map<String, Any>>
                val sectionText = ((blocks[1]["text"] as Map<String, Any>)["text"] as String)

                assertThat(sectionText).contains("*Error*:")
                assertThat(sectionText).contains("boom-")
            }
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(
            failedRun(
                jobName = "question-scheduler-" + "x".repeat(1_000),
                createdBy = "admin-" + "x".repeat(1_000),
                errorMessage = "boom-" + "x".repeat(5_000),
            ),
        )

        server.verify()
    }

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
            durationMs = 2000,
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
