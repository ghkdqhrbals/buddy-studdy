package com.buddystudy.backend

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactive.awaitSingle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.billing.recovery.enabled=false",
        "buddystudy.billing.reconciliation.enabled=false",
        "buddystudy.billing.event-projector.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
        "buddystudy.admin.username=admin",
        "buddystudy.admin.password=admin",
        "buddystudy.monitoring.scheduler-stale-threshold-minutes=15",
        "buddystudy.monitoring.scheduler-monitored-jobs=question-schedule,user-stats-refresh",
    ],
)
class AdminSchedulerStatusIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var analytics: AdminAnalyticsAggregationUseCase
    @Autowired lateinit var databaseClient: DatabaseClient
    @Autowired lateinit var mapper: ObjectMapper
    @LocalServerPort var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun setUpSchema(): Unit = runBlocking {
        databaseClient.sql("delete from scheduled_job_runs").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql(
            "delete from scheduled_jobs where job_name in ('question-schedule', 'user-stats-refresh')",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("delete from admin_daily_metrics").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql(
            """
            insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value)
            values
                ('question-schedule', true, 'FIXED_DELAY', '30s'),
                ('user-stats-refresh', true, 'CRON', '0 */5 * * * *')
            """.trimIndent(),
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql(
            """
            insert into scheduled_job_runs (
                job_name, trigger_type, status, started_at, finished_at, duration_ms, summary, created_by
            ) values (:jobName, 'SCHEDULED', 'SUCCESS', :startedAt, :finishedAt, 12, 'created=3', 'system')
            """.trimIndent(),
        ).bind("jobName", "question-schedule")
            .bind("startedAt", Instant.now())
            .bind("finishedAt", Instant.now())
            .fetch().rowsUpdated().awaitSingle()
        databaseClient.sql(
            """
            insert into scheduled_job_runs (
                job_name, trigger_type, status, started_at, finished_at, duration_ms, error_message, created_by
            ) values (:jobName, 'SCHEDULED', 'FAILED', :startedAt, :finishedAt, 18, 'aggregation failed', 'system')
            """.trimIndent(),
        ).bind("jobName", "user-stats-refresh")
            .bind("startedAt", Instant.now())
            .bind("finishedAt", Instant.now())
            .fetch().rowsUpdated().awaitSingle()
        databaseClient.sql(
            """
            insert into scheduled_job_runs (
                job_name, trigger_type, status, started_at, finished_at, duration_ms, error_message, created_by
            ) values (:jobName, 'RETRY', 'FAILED', :startedAt, :finishedAt, 9, 'older retry failed', 'admin')
            """.trimIndent(),
        ).bind("jobName", "question-schedule")
            .bind("startedAt", Instant.now().minusSeconds(60))
            .bind("finishedAt", Instant.now().minusSeconds(60))
            .fetch().rowsUpdated().awaitSingle()
        Unit
    }

    @Test
    fun `admin analytics job writes derived metrics through the primary mysql connection`(): Unit = runBlocking {
        val date = LocalDate.parse("2026-01-01")

        val refreshed = analytics.refreshRange(date, date)
        val stored = databaseClient.sql(
            "select count(*) as total from admin_daily_metrics where metric_date = :metricDate",
        ).bind("metricDate", date)
            .map { row, _ -> (row.get("total") as Number).toLong() }
            .one()
            .awaitSingle()

        assertThat(refreshed).isEqualTo(9)
        assertThat(stored).isEqualTo(9)
    }

    @Test
    fun `admin can inspect scheduler statuses through HTTP`(): Unit = runBlocking {
        val token = loginAdmin()

        val response = get("/api/v1/admin/jobs/statuses", token)

        assertThat(response.statusCode()).isEqualTo(200)
        val body = response.json()
        assertThat(body["jobs"].size()).isGreaterThanOrEqualTo(2)
        val questionSchedule = body["jobs"].first { it["jobName"].asText() == "question-schedule" }
        val statsRefresh = body["jobs"].first { it["jobName"].asText() == "user-stats-refresh" }
        assertThat(questionSchedule["displayName"].asText()).isEqualTo("Scheduled question dispatch")
        assertThat(questionSchedule["description"].asText()).isNotBlank()
        assertThat(questionSchedule["monitored"].asBoolean()).isTrue()
        assertThat(questionSchedule["enabled"].asBoolean()).isTrue()
        assertThat(questionSchedule["stale"].asBoolean()).isFalse()
        assertThat(questionSchedule["latestRun"]["status"].asText()).isEqualTo("SUCCESS")
        assertThat(statsRefresh["stale"].asBoolean()).isTrue()
        assertThat(statsRefresh["latestRun"]["status"].asText()).isEqualTo("FAILED")
        assertThat(statsRefresh["latestRun"]["errorMessage"].asText()).isEqualTo("aggregation failed")
    }

    @Test
    fun `admin can page scheduler run history through HTTP`(): Unit = runBlocking {
        val token = loginAdmin()

        val response = get("/api/v1/admin/jobs/runs?jobName=%20%20%20&limit=1&offset=0", token)

        assertThat(response.statusCode()).isEqualTo(200)
        val body = response.json()
        assertThat(body["runs"]).hasSize(1)
        assertThat(body["totalCount"].asLong()).isEqualTo(3)
        assertThat(body["limit"].asInt()).isEqualTo(1)
        assertThat(body["offset"].asInt()).isEqualTo(0)
        assertThat(body["runs"][0]["jobName"].asText()).isIn("question-schedule", "user-stats-refresh")
    }

    @Test
    fun `admin can open a scheduler run by run id through HTTP`(): Unit = runBlocking {
        val token = loginAdmin()
        val olderRunId = databaseClient.sql(
            "select id from scheduled_job_runs where job_name = 'question-schedule' and error_message = 'older retry failed'",
        ).map { row, _ -> (row.get("id") as Number).toLong() }.one().awaitSingle()

        val response = get("/api/v1/admin/jobs/runs?jobName=question-schedule&runId=$olderRunId&limit=10&offset=0", token)

        assertThat(response.statusCode()).isEqualTo(200)
        val body = response.json()
        assertThat(body["runs"]).hasSize(1)
        assertThat(body["totalCount"].asLong()).isEqualTo(1)
        assertThat(body["runs"][0]["id"].asLong()).isEqualTo(olderRunId)
        assertThat(body["runs"][0]["jobName"].asText()).isEqualTo("question-schedule")
    }

    @Test
    fun `scheduler run history requires admin token`(): Unit = runBlocking {
        val response = get("/api/v1/admin/jobs/runs", bearerToken = null)

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `admin metrics require admin token`(): Unit = runBlocking {
        val response = get("/api/v1/admin/analytics/metrics?startDate=2026-01-01&endDate=2026-01-02", bearerToken = null)

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `admin analytics refresh requires admin token`(): Unit = runBlocking {
        val response = post("/api/v1/admin/analytics/refresh?startDate=2026-01-01&endDate=2026-01-02", "", bearerToken = null)

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `scheduler retry requires admin token`(): Unit = runBlocking {
        val response = post("/api/v1/admin/jobs/question-schedule/retry", "", bearerToken = null)

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `admin can see monitored scheduler jobs missing from seed table`(): Unit = runBlocking {
        databaseClient.sql("delete from scheduled_job_runs where job_name = 'user-stats-refresh'")
            .fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("delete from scheduled_jobs where job_name = 'user-stats-refresh'")
            .fetch().rowsUpdated().awaitSingle()
        val token = loginAdmin()

        val response = get("/api/v1/admin/jobs/statuses", token)

        assertThat(response.statusCode()).isEqualTo(200)
        val missing = response.json()["jobs"].first { it["jobName"].asText() == "user-stats-refresh" }
        assertThat(missing["enabled"].asBoolean()).isTrue()
        assertThat(missing["scheduleType"].asText()).isEqualTo("MISSING")
        assertThat(missing["scheduleValue"].asText()).isEqualTo("not seeded")
        assertThat(missing["stale"].asBoolean()).isTrue()
        assertThat(missing["latestRun"].isNull).isTrue()
    }

    @Test
    fun `scheduler statuses require admin token`(): Unit = runBlocking {
        val response = get("/api/v1/admin/jobs/statuses", bearerToken = null)

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("AUTH_INVALID_ACCESS_TOKEN")
    }

    private fun loginAdmin(): String {
        val response = post(
            "/api/v1/admin/login",
            """{"username":"admin","password":"admin"}""",
            bearerToken = null,
        )
        assertThat(response.statusCode()).isEqualTo(200)
        return response.json()["adminToken"].asText()
    }

    private fun get(path: String, bearerToken: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, bearerToken: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<String>.json(): JsonNode = mapper.readTree(body())
}
