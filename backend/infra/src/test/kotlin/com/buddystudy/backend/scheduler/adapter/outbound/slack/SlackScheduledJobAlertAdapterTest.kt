package com.buddystudy.backend.scheduler.adapter.outbound.slack

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
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
import java.time.Duration
import java.time.Instant

class SlackScheduledJobAlertAdapterTest {
    @Test
    fun `notifyFailed posts scheduler failure to Slack webhook`() {
        val properties = BuddyStudyProperties(
            monitoring = BuddyStudyProperties.Monitoring(
                slackWebhookUrl = "https://hooks.slack.test/scheduler",
                environmentName = "test",
                serviceName = "BuddyStudy test",
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
            .andExpect(content().string(containsString("*Finished*: 2026-07-02T00:00:02Z")))
            .andExpect(content().string(containsString("boom")))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed is no-op when Slack webhook is not configured`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(BuddyStudyProperties(), builder.build())

        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed propagates Slack delivery failure`() {
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

        assertThatThrownBy { adapter.notifyFailed(failedRun()) }
            .hasMessageContaining("500")

        server.verify()
    }

    @Test
    fun `slack timeout is bounded`() {
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

    private fun failedRun(): ScheduledJobRun =
        ScheduledJobRun(
            id = 12,
            jobName = "question-scheduler",
            triggerType = JobTriggerType.SCHEDULED,
            status = JobRunStatus.FAILED,
            startedAt = Instant.parse("2026-07-02T00:00:00Z"),
            finishedAt = Instant.parse("2026-07-02T00:00:02Z"),
            durationMs = 2000,
            errorMessage = "boom",
        )
}
