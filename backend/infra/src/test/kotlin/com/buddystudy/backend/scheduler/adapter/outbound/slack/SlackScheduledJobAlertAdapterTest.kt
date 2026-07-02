package com.buddystudy.backend.scheduler.adapter.outbound.slack

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
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
        val adapter = SlackScheduledJobAlertAdapter(properties, builder)

        server.expect(requestTo("https://hooks.slack.test/scheduler"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("BuddyStudy test job failed")))
            .andExpect(content().string(containsString("question-scheduler")))
            .andExpect(content().string(containsString("boom")))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))

        adapter.notifyFailed(failedRun())

        server.verify()
    }

    @Test
    fun `notifyFailed is no-op when Slack webhook is not configured`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val adapter = SlackScheduledJobAlertAdapter(BuddyStudyProperties(), builder)

        adapter.notifyFailed(failedRun())

        server.verify()
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
