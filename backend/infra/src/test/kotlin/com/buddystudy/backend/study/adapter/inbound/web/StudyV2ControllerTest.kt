package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.settings.adapter.inbound.web.SettingsWebPort
import com.buddystudy.backend.study.application.model.QuestionItemResponse
import com.buddystudy.backend.study.application.model.QuestionThreadResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.time.Instant

class StudyV2ControllerTest {
    private val authentication = UsernamePasswordAuthenticationToken(Principal(7, "device", 11, false), null)
    private val study = mock(StudyWebPort::class.java)
    private val followUps = mock(FollowUpQuestionWebPort::class.java)
    private val learning = mock(StudyLearningRecordsWebPort::class.java)
    private val adapter = StudyV2WebAdapter(study, followUps, learning)
    private val record = StudyRecordResponse(
        id = "42", question = QuestionItemResponse("Which structure?", createdAt = Instant.parse("2026-09-20T00:00:00Z")),
        answer = "Answer", gradingResult = null, topic = "Anatomy", difficulty = 3,
        answeredAt = Instant.parse("2026-09-27T00:00:00Z"), isPublic = false,
    )
    private val page = RecordsPageResponse(listOf(record, record.copy(id = "43", answeredAt = null)), 7, 2, 3)

    @Test
    fun `v1 omits time display and v2 preserves query pagination and timestamps with one localized snapshot`(): Unit = runBlocking {
        `when`(study.records(2, 3, "bone", 10, "en", "original", authentication)).thenReturn(page)
        val client = client()
        val query = "?limit=2&offset=3&query=bone&studyId=10&tl=en&language=ko&view=original"

        client.get().uri("/api/v1/records$query").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.records[0].timeDisplay").doesNotExist()
            .jsonPath("$.records[0].answeredAt").isEqualTo("2026-09-27T00:00:00Z")
        client.get().uri("/api/v2/records$query").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.totalCount").isEqualTo(7)
            .jsonPath("$.limit").isEqualTo(2)
            .jsonPath("$.offset").isEqualTo(3)
            .jsonPath("$.records[0].timeDisplay.timestamp").isEqualTo("2026-09-27T00:00:00Z")
            .jsonPath("$.records[1].timeDisplay.timestamp").isEqualTo("2026-09-20T00:00:00Z")
            .jsonPath("$.records[0].timeDisplay.language").isEqualTo("en")
            .jsonPath("$.records[0].timeDisplay.relativeText").isNotEmpty
            .jsonPath("$.records[0].question.createdAt").isEqualTo("2026-09-20T00:00:00Z")

        val mapped = adapter.records(2, 3, "bone", 10, "en", "original", authentication)
        assertThat(mapped.records.map { it.timeDisplay!!.generatedAt }.distinct()).hasSize(1)
        assertThat(page.records).allSatisfy { assertThat(it.timeDisplay).isNull() }
    }

    @Test
    fun `record thread and node history routes include localized time without changing their source records`(): Unit = runBlocking {
        `when`(study.record(42, "ja", "original", authentication)).thenReturn(record)
        `when`(followUps.thread(42, "ja", "original", authentication)).thenReturn(QuestionThreadResponse(page.records))
        val nodePage = StudyLearningRecordsPageResponse(
            items = listOf(StudyLearningRecordResponse("42", StudyLearningRecordSource.QUESTION, 10, record.answeredAt!!, questionRecord = record, record = record)),
            nextCursor = "opaque", hasMore = true, limit = 2,
        )
        `when`(learning.learningRecords(10, "subtree", 2, "previous", "ja", "original", authentication)).thenReturn(nodePage)
        val client = client()

        client.get().uri("/api/v2/records/42?tl=ja&view=original").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.timeDisplay.language").isEqualTo("ja")
            .jsonPath("$.timeDisplay.timestamp").isEqualTo("2026-09-27T00:00:00Z")
        client.get().uri("/api/v2/records/42/thread?tl=ja&view=original").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.records[0].timeDisplay.language").isEqualTo("ja")
            .jsonPath("$.records[1].timeDisplay.language").isEqualTo("ja")
        client.get().uri("/api/v2/studies/10/learning-records?scope=subtree&limit=2&cursor=previous&tl=ja&view=original")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.nextCursor").isEqualTo("opaque")
            .jsonPath("$.hasMore").isEqualTo(true)
            .jsonPath("$.items[0].record.timeDisplay.language").isEqualTo("ja")
            .jsonPath("$.items[0].questionRecord.timeDisplay.language").isEqualTo("ja")

        verify(study).record(42, "ja", "original", authentication)
        verify(followUps).thread(42, "ja", "original", authentication)
        verify(learning).learningRecords(10, "subtree", 2, "previous", "ja", "original", authentication)
        assertThat(record.timeDisplay).isNull()
    }

    @Test
    fun `study list and detail attach matching times to pending and latest questions`(): Unit = runBlocking {
        val room = StudyRoomResponse(
            id = 10, parentStudyId = null, sortOrder = 0, topic = "Anatomy", difficultyLevel = 3,
            intervalMinutes = 60, enabled = true, activeForQuestions = true, notificationSound = null,
            customPrompt = "", openaiModel = "gpt-5-mini", maxHistoryCount = 30, nextDueAt = null,
            lastSentAt = null, lastError = null, pendingQuestion = record.copy(answeredAt = null), latestQuestion = record,
            createdAt = record.question.createdAt, updatedAt = record.answeredAt!!,
        )
        `when`(study.study(2, 3, "bone", "ko", authentication)).thenReturn(StudyPageResponse(listOf(room), 7, 2, 3, Instant.EPOCH))
        `when`(study.study(10, "ko", authentication)).thenReturn(room)
        val client = client()
        client.get().uri("/api/v2/studies?limit=2&offset=3&query=bone&language=ko").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.studies[0].pendingQuestion.timeDisplay.timestamp").isEqualTo("2026-09-20T00:00:00Z")
            .jsonPath("$.studies[0].latestQuestion.timeDisplay.timestamp").isEqualTo("2026-09-27T00:00:00Z")
            .jsonPath("$.studies[0].latestQuestion.timeDisplay.language").isEqualTo("ko")
        client.get().uri("/api/v2/studies/10?tl=ko").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.pendingQuestion.timeDisplay.language").isEqualTo("ko")
            .jsonPath("$.latestQuestion.timeDisplay.language").isEqualTo("ko")
        val mapped = adapter.study(10, "ko", authentication)
        assertThat(mapped.pendingQuestion!!.timeDisplay!!.generatedAt).isEqualTo(mapped.latestQuestion!!.timeDisplay!!.generatedAt)
    }

    @Test
    fun `v2 record ownership failures propagate from the existing read port`(): Unit = runBlocking {
        `when`(study.record(99, "ko", "localized", authentication)).thenThrow(
            org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND),
        )
        client().get().uri("/api/v2/records/99").exchange().expectStatus().isNotFound
    }

    private fun client(): WebTestClient = WebTestClient.bindToController(
        StudyController(study, mock(SettingsWebPort::class.java)), StudyV2Controller(adapter),
    ).webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
        chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
    }).build()
}
