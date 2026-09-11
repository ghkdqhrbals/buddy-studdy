package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningPhase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VoiceTutorCanonicalQuestionCoordinatorTest {
    @Test
    fun `generation event binds its exact saved question once and later lookup only offers recovery`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList(); generationReady = false }
        val requested = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
        val progress = requested.learningProgress!!
        assertThat(progress.phase).isEqualTo(VoiceTutorLearningPhase.QUESTION_GENERATING)
        val waiting = fixture.coordinator.pollLearningProgress(fixture.context(), progress)
        assertThat(waiting.learningProgress).isEqualTo(progress)
        // Losing/closing an observer must not lose the request or spend quota again.
        repeat(3) {
            val resumed = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
            assertThat(resumed.learningProgress?.correlationId).isEqualTo(progress.correlationId)
        }
        fixture.generationReady = true
        val ready = fixture.coordinator.pollLearningProgress(fixture.context(), progress)
        assertThat(ready.learningProgress?.phase).isEqualTo(VoiceTutorLearningPhase.QUESTION_READY)
        assertThat(ready.questionChange?.recordId).isEqualTo("201")
        assertThat(ready.questionReadback?.recordId).isEqualTo("201")
        assertThat(ready.output).isEqualTo("{}")
        val spoken = fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to progress.correlationId!!))
        assertThat(spoken.questionReadback).isNull()
        assertThat(spoken.questionReadbackRecovery?.recordId).isEqualTo("201")
        assertThat(fixture.calls.count { it.name == "request_question" }).isEqualTo(1)
    }

    @Test
    fun `terminal observed generation is released for a new explicit request and old correlation cannot replace it`(): Unit = runBlocking {
        for (failed in listOf(false, true)) {
            val fixture = Fixture().apply { records = emptyList(); if (failed) generationRecord = null }
            val first = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY)).learningProgress!!
            val terminal = fixture.coordinator.pollLearningProgress(fixture.context(), first)
            assertThat(terminal.learningProgress?.phase).isEqualTo(if (failed) VoiceTutorLearningPhase.QUESTION_FAILED else VoiceTutorLearningPhase.QUESTION_READY)
            // Simulate canonical completion/removal of the prior pending question.
            fixture.records = emptyList(); fixture.learner = checkNotNull(fixture.learner) + 1
            val next = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY)).learningProgress!!
            assertThat(next.correlationId).isNotEqualTo(first.correlationId)
            assertThat(fixture.coordinator.pollLearningProgress(fixture.context(), first).isError).isTrue()
            assertThat(fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to first.correlationId!!)).isError).isTrue()
            assertThat(fixture.calls.count { it.name == "request_question" }).isEqualTo(2)
        }
    }

    @Test
    fun `background grading reads the submitted record without any model poll or microphone turn`(): Unit = runBlocking {
        for (failed in listOf(false, true)) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            val submitted = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
            val progress = submitted.learningProgress!!
            assertThat(progress.phase).isEqualTo(VoiceTutorLearningPhase.GRADING)
            assertThat(fixture.coordinator.pollLearningProgress(fixture.context(), progress).learningProgress?.phase).isEqualTo(VoiceTutorLearningPhase.GRADING)
            fixture.gradeReady = true; fixture.gradingFails = failed; fixture.learner = 99
            val completed = fixture.coordinator.pollLearningProgress(fixture.context(), progress)
            assertThat(completed.learningProgress?.phase).isEqualTo(if (failed) VoiceTutorLearningPhase.GRADING_FAILED else VoiceTutorLearningPhase.GRADED)
            assertThat(completed.questionChange?.recordId).isEqualTo("101")
            assertThat(completed.questionReadback).isNull()
            assertThat(completed.output).isEqualTo("{}")
            assertThat(fixture.calls.none { it.name == "get_grading_process" }).isTrue()
        }
    }

    @Test
    fun `background observations reject mismatched process record revoked ownership and post read focus changes`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        val progress = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed()).learningProgress!!
        for (forged in listOf(progress.copy(recordId = "999"), progress.copy(correlationId = "another-grade"), progress.copy(studyId = STUDY + 1))) {
            val reads = fixture.calls.size
            assertThat(fixture.coordinator.pollLearningProgress(fixture.context(), forged).isError).isTrue()
            assertThat(fixture.calls).hasSize(reads)
        }
        fixture.isAuthorized = false
        assertThat(fixture.coordinator.pollLearningProgress(fixture.context(), progress).learningProgress).isNull()
        fixture.isAuthorized = true
        fixture.afterInvoke = { if (it == "get_record") fixture.focus = (STUDY + 1) to REVISION }
        assertThat(fixture.coordinator.pollLearningProgress(fixture.context(), progress).learningProgress).isNull()
    }

    @Test
    fun `a canonical failed generation retains its process identity after clearing the pending request`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList(); generationRecord = null }
        val pending = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY)).learningProgress!!
        val failed = fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to pending.correlationId!!))
        assertThat(failed.learningProgress?.phase).isEqualTo(VoiceTutorLearningPhase.QUESTION_FAILED)
        assertThat(failed.learningProgress?.correlationId).isEqualTo(pending.correlationId)
    }

    @Test
    fun `selection reuses the oldest saved pending question at its original level without revealing hints`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.records = listOf(
            fixture.record(102, createdAt = "2026-09-10T01:00:00Z"),
            fixture.record(101, difficulty = 3, createdAt = "2026-09-09T01:00:00Z"),
            fixture.record(103, status = "GRADING", answer = "Already submitted"),
        )

        val result = fixture.coordinator.selected(fixture.context())

        assertThat(result.isError).isFalse()
        val body = fixture.json(result)
        assertThat(body.path("pendingQuestion").path("id").asText()).isEqualTo("101")
        assertThat(body.path("pendingQuestion").path("difficulty").asInt()).isEqualTo(3)
        assertThat(body.path("pendingQuestion").path("topic").asText()).isEqualTo("Dependency injection")
        assertThat(body.path("pendingQuestion").path("question").path("question").asText()).isEqualTo(PROMPT)
        assertThat(body.path("gradingQuestions").map { it.path("id").asText() }).containsExactly("103")
        assertThat(result.output).doesNotContain("SECRET_HINT", "SECRET_RUBRIC", "Already submitted")
        assertThat(body.path("gradingQuestions").first().fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("id", "questionStatus")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
        assertThat(fixture.calls.single().arguments).isEqualTo(mapOf("study_id" to STUDY, "limit" to 10, "offset" to 0))
    }

    @Test
    fun `requesting a question rechecks pending and never spends quota over an existing question`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 11

        val result = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))

        assertThat(result.isError).isFalse()
        assertThat(fixture.json(result).path("pendingQuestion").path("id").asText()).isEqualTo("101")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions", "list_pending_questions")
    }

    @Test
    fun `refreshing the same question preserves the already spoken question and current original answer`(): Unit = runBlocking {
        val fixture = Fixture()
        val initial = fixture.coordinator.selected(fixture.context())
        assertThat(initial.questionReadback).isNotNull()
        assertThat(initial.questionReadbackRecovery).isNull()
        fixture.learner = 12

        val refreshed = fixture.coordinator.execute(fixture.answerContext(), "list_pending_questions", mapOf("study_id" to STUDY))
        assertThat(refreshed.isError).isFalse()
        assertThat(refreshed.questionReadback).isNull()
        assertThat(refreshed.questionChange).isNull()
        assertThat(refreshed.questionReadbackRecovery).isEqualTo(initial.questionReadback)
        assertThat(refreshed.output).doesNotContain("questionReadbackRecovery", "SECRET_HINT", "SECRET_RUBRIC")
        assertThat(fixture.json(refreshed).path("notice").asText()).contains("handle the learner's present answer")

        val submitted = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        assertThat(submitted.isError).isFalse()
        assertThat(fixture.calls.last().arguments["answer"]).isEqualTo(EDITED_ANSWER)
        assertThat(fixture.excluded).containsExactly("question-read", "answer-1", "answer-2")
    }

    @Test
    fun `readback recovery refresh requires a currently owned unanswered readable question`(): Unit = runBlocking {
        for (scenario in 0..4) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            when (scenario) {
                0 -> fixture.isAuthorized = false
                1 -> fixture.focus = (STUDY + 1) to REVISION
                2 -> fixture.records = emptyList()
                3 -> fixture.records = listOf(fixture.record(101, status = "GRADING", answer = "Submitted"))
                4 -> fixture.records = listOf(fixture.record(101, questionText = ""))
            }
            val refreshed = fixture.coordinator.execute(fixture.answerContext(), "list_pending_questions", mapOf("study_id" to STUDY))
            assertThat(refreshed.questionReadbackRecovery).describedAs("scenario %s", scenario).isNull()
            assertThat(refreshed.questionReadback).isNull()
        }
    }

    @Test
    fun `request returning the same pending question still allows the current explicit replacement request to skip it`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12

        val existing = fixture.coordinator.execute(fixture.answerContext(), "request_question", mapOf("study_id" to STUDY))
        assertThat(existing.isError).isFalse()
        assertThat(existing.questionReadback).isNull()
        assertThat(existing.questionChange).isNull()
        val skipped = fixture.coordinator.execute(fixture.answerContext(), "skip_question", mapOf("record_id" to 101L))
        assertThat(skipped.isError).isFalse()
        assertThat(skipped.questionChange?.recordId).isEqualTo("101")
        assertThat(fixture.calls.none { it.name == "request_question" || it.name == "submit_answer" }).isTrue()

        fixture.records = emptyList()
        val next = fixture.coordinator.execute(fixture.answerContext(), "request_question", mapOf("study_id" to STUDY))
        assertThat(next.isError).isFalse()
        assertThat(fixture.calls.count { it.name == "request_question" }).isEqualTo(1)
    }

    @Test
    fun `a changed question identity original text or saved level requires its own readback and fresh answer`(): Unit = runBlocking {
        for (change in 0..2) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            fixture.learner = 12
            val id = if (change == 0) 102L else 101L
            fixture.records = listOf(fixture.record(id, difficulty = if (change == 1) 4 else 3,
                questionText = if (change == 2) "의존성 주입의 장점은 무엇인가요?" else PROMPT))
            val refreshed = fixture.coordinator.execute(fixture.answerContext(), "list_pending_questions", mapOf("study_id" to STUDY))
            assertThat(refreshed.isError).isFalse()
            assertThat(refreshed.questionReadback?.recordId).isEqualTo(id.toString())
            assertThat(refreshed.questionChange?.recordId).isEqualTo(id.toString())
            assertThat(refreshed.questionReadbackRecovery).isNull()
            assertCode(fixture, fixture.coordinator.execute(fixture.answerContext(), "submit_answer", mapOf("record_id" to id)), "USER_CONFIRMATION_REQUIRED")
            assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
        }
    }

    @Test
    fun `oversized pending output reads bounded single record pages and preserves the oldest original question`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            pendingPageTooLarge = true
            records = listOf(record(103, status = "GRADING", answer = "Submitted"),
                record(102, createdAt = "2026-09-10T01:00:00Z"), record(101))
        }
        val selected = fixture.coordinator.selected(fixture.context())
        assertThat(selected.isError).isFalse()
        assertThat(selected.questionReadback?.recordId).isEqualTo("101")
        assertThat(fixture.json(selected).path("gradingQuestions").map { it.path("id").asText() }).containsExactly("103")
        assertThat(fixture.calls.map { call -> call.arguments.mapValues { (_, value) -> (value as Number).toLong() } }).containsExactly(
            mapOf("study_id" to STUDY, "limit" to 10L, "offset" to 0L),
            mapOf("study_id" to STUDY, "limit" to 1L, "offset" to 0L),
            mapOf("study_id" to STUDY, "limit" to 1L, "offset" to 1L),
            mapOf("study_id" to STUDY, "limit" to 1L, "offset" to 2L))
        assertThat(selected.output).doesNotContain("SECRET_HINT", "SECRET_RUBRIC")
    }

    @Test
    fun `changing or excessive pending page totals cannot authorize question generation`(): Unit = runBlocking {
        for (excessive in listOf(false, true)) {
            val fixture = Fixture().apply {
                pendingPageTooLarge = true
                records = listOf(record(102, status = "GRADING", answer = "Submitted"), record(101))
                if (excessive) totalCount = 11
            }
            if (!excessive) fixture.afterInvoke = { name ->
                if (name == "list_pending_questions" && fixture.calls.last().arguments["limit"] == 1) fixture.totalCount = 3
            }
            val result = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
            assertCode(fixture, result, "PENDING_PAGE_INCOMPLETE")
            assertThat(result.questionReadback).isNull()
            assertThat(fixture.calls.none { it.name == "request_question" }).isTrue()
            assertThat(fixture.calls.size).isLessThanOrEqualTo(11)
        }
    }

    @Test
    fun `model pagination arguments cannot change the canonical pending scope`(): Unit = runBlocking {
        val fixture = Fixture()
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "list_pending_questions",
            mapOf("study_id" to STUDY, "limit" to 1, "offset" to 1)), "QUESTION_SCOPE_MISMATCH")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `an unread pending page or missing learner source cannot authorize generation`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList(); totalCount = 11 }
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY)), "PENDING_PAGE_INCOMPLETE")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
        fixture.calls.clear()
        fixture.learner = null
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY)), "INPUT_PERSISTENCE_PENDING")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `generation keys are server derived stable per learner and process IDs cannot cross requests`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList(); generationRecord = null }
        val first = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
        val firstCorrelation = fixture.json(first).path("generation").path("correlationId").asText()
        fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
        assertThat(fixture.calls.count { it.name == "request_question" }).isEqualTo(1)
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to "other-call")), "QUESTION_CONTEXT_UNAVAILABLE")
        assertThat(fixture.calls.none { it.name == "get_question_process" }).isTrue()

        fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to firstCorrelation))
        val second = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
        val secondCorrelation = fixture.json(second).path("generation").path("correlationId").asText()
        fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to secondCorrelation))
        fixture.learner = 11
        fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))

        val requests = fixture.calls.filter { it.name == "request_question" }
        assertThat(requests).hasSize(3)
        assertThat(requests[0].arguments["idempotency_key"]).isEqualTo(requests[1].arguments["idempotency_key"])
        assertThat(requests[2].arguments["idempotency_key"]).isNotEqualTo(requests[0].arguments["idempotency_key"])
        assertThat(requests).allMatch { it.arguments.keys == setOf("study_id", "idempotency_key") && it.arguments["study_id"] == STUDY }
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY, "idempotency_key" to "model-key")), "QUESTION_SCOPE_MISMATCH")
    }

    @Test
    fun `generation binds only the exact canonical saved result and reports its change`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList(); generationRecord = record(201, difficulty = 4) }
        val request = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
        val correlation = fixture.json(request).path("generation").path("correlationId").asText()

        val result = fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to correlation))

        assertThat(result.isError).isFalse()
        assertThat(fixture.json(result).path("pendingQuestion").path("id").asText()).isEqualTo("201")
        assertThat(result.questionChange?.recordId).isEqualTo("201")
        assertThat(result.questionChange?.studyId).isEqualTo(STUDY)
        assertThat(result.output).doesNotContain("SECRET_HINT")
    }

    @Test
    fun `wrong provider correlation and cross-topic generation result are rejected`(): Unit = runBlocking {
        for (wrongCorrelation in listOf(true, false)) {
            val fixture = Fixture().apply { records = emptyList(); generationRecord = record(201, study = STUDY + 1) }
            val request = fixture.coordinator.execute(fixture.context(), "request_question", mapOf("study_id" to STUDY))
            val correlation = fixture.json(request).path("generation").path("correlationId").asText()
            fixture.processCorrelationOverride = if (wrongCorrelation) "another-request" else null
            val result = fixture.coordinator.execute(fixture.context(), "get_question_process", mapOf("correlation_id" to correlation))
            assertCode(fixture, result, "INVALID_QUESTION_RESULT")
            assertThat(result.questionChange).isNull()
        }
    }

    @Test
    fun `skip requires a fresh learner turn and exact selected question without generating another`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "skip_question", mapOf("record_id" to 101L)), "FRESH_LEARNER_REQUEST_REQUIRED")
        fixture.learner = 11
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "skip_question", mapOf("record_id" to 999L)), "QUESTION_SCOPE_MISMATCH")
        assertCode(fixture, fixture.coordinator.execute(fixture.context(), "list_pending_questions", mapOf("study_id" to STUDY + 1)), "QUESTION_SCOPE_MISMATCH")

        val result = fixture.coordinator.execute(fixture.context(), "skip_question", mapOf("record_id" to 101L))

        assertThat(result.isError).isFalse()
        assertThat(fixture.json(result).path("skipped").asBoolean()).isTrue()
        assertThat(result.questionChange?.recordId).isEqualTo("101")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions", "skip_question")
        assertThat(fixture.calls.last().arguments).isEqualTo(mapOf("record_id" to 101L))
    }

    @Test
    fun `model supplied answer text and an unknown record never reach canonical submission`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12
        for (args in listOf(
            mapOf("record_id" to 101L, "answer" to "Invented model answer"),
            mapOf("record_id" to 101L, "source_language" to "en"),
            mapOf("record_id" to 999L),
        )) {
            assertCode(fixture, fixture.coordinator.execute(fixture.answerContext(), "submit_answer", args), "QUESTION_SCOPE_MISMATCH")
        }
        assertThat(fixture.persistenceCalls).isEmpty()
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
    }

    @Test
    fun `model submission requires explicit UI confirmation regardless of the completed read boundary`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12
        val beforeRead = fixture.answerContext().let { it.copy(dialogueBoundary = it.dialogueBoundary!!.copy(precedingSpokenResponseGeneration = 5)) }

        for (context in listOf(beforeRead, fixture.answerContext())) {
            assertCode(fixture, fixture.coordinator.execute(context, "submit_answer", mapOf("record_id" to 101L)), "USER_CONFIRMATION_REQUIRED")
        }
        assertThat(fixture.persistenceCalls).isEmpty()
        assertThat(fixture.excluded).isEmpty()
        assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
    }

    @Test
    fun `explicit submission keeps edited text authoritative and excludes unchanged raw source before canonical write`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12
        fixture.events.clear()

        val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())

        assertThat(result.isError).isFalse()
        assertThat(fixture.events).containsExactly("read-source", "exclude-source", "submit_answer")
        assertThat(fixture.persistenceCalls.first()).isEqualTo(listOf(7L, SESSION, "answer-2", "question-read", REVISION))
        assertThat(fixture.excluded).containsExactly("question-read", "answer-1", "answer-2")
        assertThat(fixture.calls.last().arguments).isEqualTo(mapOf(
            "record_id" to 101L, "answer" to EDITED_ANSWER, "source_language" to "ko",
        ))
        assertThat(fixture.source.filter { it.role == VoiceTutorTranscriptRole.USER }.joinToString("\n") { it.transcript }).isEqualTo(ANSWER)
        assertThat(result.questionChange?.recordId).isEqualTo("101")
    }

    @Test
    fun `an explicitly typed answer without microphone source submits without inventing or excluding transcript rows`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = null
        fixture.source = emptyList()
        val reviewed = fixture.reviewed("직접 입력한 답변입니다.").copy(
            precedingTutorProviderItemId = null, learnerProviderItemIds = emptyList(),
        )

        val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), reviewed)

        assertThat(result.isError).isFalse()
        assertThat(fixture.calls.single { it.name == "submit_answer" }.arguments["answer"]).isEqualTo(reviewed.text)
        assertThat(fixture.persistenceCalls).isEmpty()
        assertThat(fixture.excluded).isEmpty()
        assertThat(result.questionChange?.recordId).isEqualTo("101")
    }

    @Test
    fun `reviewed text must be nonblank and at most 8000 UTF16 units before any canonical read or write`(): Unit = runBlocking {
        for (text in listOf("", " \n\t", "a".repeat(8_001), "😀".repeat(4_001))) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())

            assertCode(fixture, fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed(text)), "ANSWER_UNAVAILABLE")

            assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
            assertThat(fixture.persistenceCalls).isEmpty()
        }
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        val reviewed = fixture.reviewed("😀".repeat(4_000)).copy(learnerProviderItemIds = emptyList())
        assertThat(fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), reviewed).isError).isFalse()
        assertThat(fixture.calls.single { it.name == "submit_answer" }.arguments["answer"]).isEqualTo(reviewed.text)
    }

    @Test
    fun `reviewed submission rejects another question topic revision or malformed capture identity before persistence`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        val reviewed = fixture.reviewed()
        for (invalid in listOf(
            reviewed.copy(recordId = "999"),
            reviewed.copy(studyId = STUDY + 1),
            reviewed.copy(lessonRevision = REVISION + 1),
            reviewed.copy(answerId = "not-a-uuid"),
            reviewed.copy(answerId = "1-1-1-1-1"),
            reviewed.copy(learnerProviderItemIds = listOf("answer-1", "answer-1")),
            reviewed.copy(learnerProviderItemIds = (1..33).map { "answer-$it" }),
            reviewed.copy(learnerProviderItemIds = listOf(" ")),
            reviewed.copy(learnerProviderItemIds = listOf("a".repeat(192))),
        )) {
            assertCode(fixture, fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), invalid), "QUESTION_CONTEXT_UNAVAILABLE")
        }
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
        assertThat(fixture.persistenceCalls).isEmpty()
    }

    @Test
    fun `reviewed audio source must match the exact complete tutor and ordered learner window before exclusion`(): Unit = runBlocking {
        for (variant in 0..5) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            fixture.source = when (variant) {
                0 -> emptyList()
                1 -> listOf(fixture.source[0], fixture.source[2], fixture.source[1])
                2 -> fixture.source.mapIndexed { index, turn -> if (index == 0) turn.copy(providerItemId = "other-question") else turn }
                3 -> fixture.source.mapIndexed { index, turn -> if (index == 1) turn.copy(role = VoiceTutorTranscriptRole.TUTOR) else turn }
                4 -> fixture.source.dropLast(1)
                else -> fixture.source + fixture.source.last().copy(providerItemId = "unreviewed-answer")
            }

            assertCode(fixture, fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed()), "INPUT_PERSISTENCE_PENDING")

            assertThat(fixture.events).containsExactly("read-source")
            assertThat(fixture.excluded).isEmpty()
            assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
        }
    }

    @Test
    fun `reviewed audio source without a tutor boundary cannot be excluded or submitted`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())

        assertCode(fixture, fixture.coordinator.submitReviewedAnswer(
            fixture.answerContext(), fixture.reviewed().copy(precedingTutorProviderItemId = null),
        ), "INPUT_PERSISTENCE_PENDING")

        assertThat(fixture.persistenceCalls).isEmpty()
        assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
    }

    @Test
    fun `explicit UI skip needs no invented learner turn and generates no replacement`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = null
        val reviewed = fixture.reviewed("").copy(precedingTutorProviderItemId = null, learnerProviderItemIds = emptyList())

        val result = fixture.coordinator.skipReviewedQuestion(fixture.answerContext(), reviewed)

        assertThat(result.isError).isFalse()
        assertThat(result.questionChange?.recordId).isEqualTo("101")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions", "skip_question")
        assertThat(fixture.calls.last().arguments).isEqualTo(mapOf("record_id" to 101L))
        assertThat(fixture.persistenceCalls).isEmpty()
        assertThat(fixture.excluded).isEmpty()
    }

    @Test
    fun `explicit UI skip excludes only its exact raw source and never submits reviewed text`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        val rawSource = fixture.source.toList()
        assertCode(fixture, fixture.coordinator.skipReviewedQuestion(fixture.answerContext(), fixture.reviewed()), "QUESTION_CONTEXT_UNAVAILABLE")
        assertCode(fixture, fixture.coordinator.skipReviewedQuestion(fixture.answerContext(), fixture.reviewed("").copy(recordId = "999")), "QUESTION_CONTEXT_UNAVAILABLE")

        val result = fixture.coordinator.skipReviewedQuestion(fixture.answerContext(), fixture.reviewed(""))

        assertThat(result.isError).isFalse()
        assertThat(fixture.events).containsExactly("read-source", "exclude-source")
        assertThat(fixture.excluded).containsExactly("question-read", "answer-1", "answer-2")
        assertThat(fixture.source).isEqualTo(rawSource)
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions", "skip_question")
    }

    @Test
    fun `typed submission rejects owner or focus loss during canonical preflight without touching microphone evidence`(): Unit = runBlocking {
        for (loseOwner in listOf(true, false)) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            fixture.afterInvoke = { name -> if (name == "get_record") {
                if (loseOwner) fixture.isAuthorized = false else fixture.focus = (STUDY + 1) to (REVISION + 1)
            } }

            val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed().copy(learnerProviderItemIds = emptyList()))

            assertCode(fixture, result, "QUESTION_CONTEXT_UNAVAILABLE")
            assertThat(result.questionChange).isNull()
            assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
            assertThat(fixture.persistenceCalls).isEmpty()
        }
    }

    @Test
    fun `failed source exclusion blocks writes and failed canonical submission cannot undo source exclusion`(): Unit = runBlocking {
        for (excludeSucceeds in listOf(false, true)) {
            val fixture = Fixture().apply { exclusionAccepted = excludeSucceeds; submissionFails = true }
            fixture.coordinator.selected(fixture.context())
            fixture.learner = 12
            fixture.events.clear()
            val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
            assertThat(result.isError).isTrue()
            if (excludeSucceeds) {
                assertThat(fixture.events).containsExactly("read-source", "exclude-source", "submit_answer")
                assertThat(fixture.excluded).containsExactly("question-read", "answer-1", "answer-2")
            } else {
                assertThat(fixture.events).containsExactly("read-source", "exclude-source")
                assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
            }
        }
    }

    @Test
    fun `grading uses the submitted canonical record directly without depending on the process endpoint`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12
        fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        assertCode(fixture, fixture.coordinator.execute(fixture.answerContext(), "get_grading_process", mapOf("correlation_id" to "someone-elses-grade")), "GRADING_CONTEXT_UNAVAILABLE")

        fixture.gradeReady = true
        val result = fixture.coordinator.execute(fixture.answerContext(), "get_grading_process", mapOf("correlation_id" to "grade-1"))

        assertThat(result.isError).isFalse()
        assertThat(fixture.json(result).path("record").path("gradingResult").path("score").asInt()).isEqualTo(91)
        assertThat(fixture.json(result).path("record").path("gradingResult").path("feedback").asText()).isEqualTo("Canonical feedback")
        assertThat(fixture.calls.last().name).isEqualTo("get_record")
        assertThat(fixture.calls.none { it.name == "get_grading_process" }).isTrue()
        assertThat(fixture.calls.last().arguments).isEqualTo(mapOf("record_id" to 101L, "language" to "ko", "view" to "original"))
        assertThat(result.output).doesNotContain("Wrong poll feedback", "SECRET_HINT")
    }

    @Test
    fun `accepted submission with a lost result recovers the exact saved answer and retry never submits twice`(): Unit = runBlocking {
        val fixture = Fixture().apply { submissionResultLost = true }
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12

        val recovered = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        assertThat(recovered.isError).isFalse()
        assertThat(recovered.questionChange?.recordId).isEqualTo("101")
        assertThat(fixture.calls.single { it.name == "submit_answer" }.arguments["answer"]).isEqualTo(EDITED_ANSWER)
        assertThat(fixture.calls.takeLast(2).map { it.name }).containsExactly("submit_answer", "get_record")

        val retried = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        assertThat(retried.isError).isFalse()
        assertThat(fixture.calls.count { it.name == "submit_answer" }).isEqualTo(1)
        assertThat(fixture.calls.last().name).isEqualTo("get_record")

        val changedAnswer = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed("A different reviewed answer"))
        assertCode(fixture, changedAnswer, "QUESTION_ALREADY_HANDLED")
        assertThat(fixture.calls.count { it.name == "submit_answer" }).isEqualTo(1)

        fixture.records = listOf(fixture.record(101, status = "GRADING", answer = EDITED_ANSWER, gradingId = "grade-1"))
        val pending = fixture.coordinator.execute(fixture.answerContext(), "list_pending_questions", mapOf("study_id" to STUDY))
        assertThat(fixture.json(pending).path("record").path("id").asText()).isEqualTo("101")
        assertThat(pending.questionReadback).isNull()
        fixture.gradeReady = true
        val graded = fixture.coordinator.execute(fixture.answerContext(), "get_grading_process", mapOf("correlation_id" to "grade-1"))
        assertThat(graded.isError).isFalse()
        assertThat(graded.questionChange?.recordId).isEqualTo("101")
        assertThat(fixture.json(graded).path("record").path("gradingResult").path("score").asInt()).isEqualTo(91)
        assertThat(fixture.calls.count { it.name == "submit_answer" }).isEqualTo(1)
    }

    @Test
    fun `legacy grading cursor matches incident and recovers exact record after a transient read failure`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        val before = fixture.calls.size
        fixture.recordReadFails = true
        val failed = fixture.coordinator.execute(fixture.answerContext(), "get_grading_process",
            mapOf("correlation_id" to "grade-1", "after_event_id" to 0))
        assertCode(fixture, failed, "GRADING_STATUS_UNAVAILABLE")
        assertThat(failed.learningProgress).isNull()
        assertThat(failed.gradingReadback).isNull()
        fixture.recordReadFails = false; fixture.gradeReady = true
        val recovered = fixture.coordinator.execute(fixture.answerContext(), "get_answer_status", mapOf("record_id" to 101L))
        assertThat(recovered.learningProgress?.phase).isEqualTo(VoiceTutorLearningPhase.GRADED)
        assertThat(recovered.learningProgress?.recordId).isEqualTo("101")
        assertThat(recovered.gradingReadback?.correlationId).isEqualTo("grade-1")
        assertThat(recovered.gradingReadback?.score).isEqualTo(91)
        assertThat(recovered.gradingReadback?.feedback).isEqualTo("Canonical feedback")
        assertThat(recovered.gradingReadback?.explanation).isEqualTo("Canonical explanation")
        val legacy = fixture.coordinator.execute(fixture.answerContext(), "get_grading_process",
            mapOf("correlation_id" to "grade-1", "record_id" to 101L, "after_event_id" to 2L))
        assertThat(legacy.gradingReadback).isEqualTo(recovered.gradingReadback)
        assertThat(fixture.calls.drop(before).map { it.name }).containsExactly("get_record", "get_record", "get_record")
        assertThat(fixture.calls.count { it.name == "submit_answer" }).isEqualTo(1)
    }

    @Test
    fun `pending refresh cannot replace a submitted answer with a newly arrived question while grading or graded`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        fixture.records = listOf(fixture.record(202, questionText = "A different arrived question"))
        val before = fixture.calls.size
        for (graded in listOf(false, true)) {
            fixture.gradeReady = graded
            val refreshed = fixture.coordinator.execute(fixture.answerContext(), "list_pending_questions", mapOf("study_id" to STUDY))
            assertThat(refreshed.isError).isFalse()
            assertThat(refreshed.learningProgress?.recordId).isEqualTo("101")
            assertThat(refreshed.learningProgress?.phase).isEqualTo(if (graded) VoiceTutorLearningPhase.GRADED else VoiceTutorLearningPhase.GRADING)
            assertThat(refreshed.questionReadback).isNull()
            assertThat(refreshed.questionReadbackRecovery).isNull()
            assertThat(refreshed.output).doesNotContain("A different arrived question")
        }
        assertThat(fixture.calls.drop(before).map { it.name }).containsExactly("get_record", "get_record")
        val next = fixture.coordinator.execute(fixture.answerContext(), "request_question", mapOf("study_id" to STUDY))
        assertThat(next.questionReadback?.recordId).isEqualTo("202")
        val late = fixture.coordinator.execute(fixture.answerContext(), "get_answer_status", mapOf("record_id" to 101L))
        assertCode(fixture, late, "GRADING_CONTEXT_UNAVAILABLE")
        assertThat(late.questionChange).isNull()
        assertThat(late.gradingReadback).isNull()
    }

    @Test
    fun `exact answer status rejects wrong record correlation and legacy malformed cursor before any read`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
        val before = fixture.calls.size
        for ((tool, args) in listOf(
            "get_answer_status" to mapOf("record_id" to 202L),
            "get_answer_status" to mapOf("record_id" to 101L, "correlation_id" to "other"),
            "get_grading_process" to mapOf("correlation_id" to "grade-1", "record_id" to 202L),
            "get_grading_process" to mapOf("correlation_id" to "grade-1", "after_event_id" to -1),
            "get_grading_process" to mapOf("correlation_id" to "grade-1", "after_event_id" to 0.5),
        )) {
            val result = fixture.coordinator.execute(fixture.answerContext(), tool, args)
            assertThat(result.isError).isTrue()
            assertThat(result.learningProgress).isNull()
            assertThat(result.gradingReadback).isNull()
        }
        assertThat(fixture.calls).hasSize(before)
    }

    @Test
    fun `saved grade must match original submitted question record study answer and grading attempt`(): Unit = runBlocking {
        for (mismatch in listOf("record", "study", "answer", "correlation", "question")) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())
            fixture.recordOverride = fixture.record(if (mismatch == "record") 202 else 101,
                study = if (mismatch == "study") STUDY + 1 else STUDY,
                status = "GRADED", answer = if (mismatch == "answer") "wrong answer" else EDITED_ANSWER,
                gradingId = if (mismatch == "correlation") "other" else "grade-1",
                questionText = if (mismatch == "question") "Changed question" else PROMPT,
                grade = mapOf("score" to 91, "feedback" to "unverified feedback"))
            val result = fixture.coordinator.execute(fixture.answerContext(), "get_answer_status", mapOf("record_id" to 101L))
            assertCode(fixture, result, "GRADING_RESULT_MISMATCH")
            assertThat(result.gradingReadback).isNull()
            assertThat(result.questionChange).isNull()
        }
    }

    @Test
    fun `saved grading failure stays attached to submitted record and background completion supplies bounded typed feedback`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        val progress = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed()).learningProgress!!
        fixture.gradeReady = true; fixture.gradingFails = true
        val failed = fixture.coordinator.execute(fixture.answerContext(), "get_answer_status", mapOf("record_id" to 101L))
        assertThat(failed.learningProgress?.phase).isEqualTo(VoiceTutorLearningPhase.GRADING_FAILED)
        assertThat(failed.learningProgress?.recordId).isEqualTo("101")
        assertThat(failed.gradingReadback).isNull()
        fixture.gradingFails = false
        val completed = fixture.coordinator.pollLearningProgress(fixture.context(), progress)
        assertThat(completed.gradingReadback?.recordId).isEqualTo("101")
        assertThat(completed.gradingReadback?.score).isEqualTo(91)
        assertThat(completed.output).isEqualTo("{}")
        fixture.recordOverride = fixture.record(101, status = "GRADED", answer = EDITED_ANSWER, gradingId = "grade-1",
            grade = mapOf("score" to 101, "feedback" to "x".repeat(8_001), "explanation" to "Saved explanation"))
        val large = fixture.coordinator.execute(fixture.context(), "get_answer_status", mapOf("record_id" to 101L))
        assertThat(large.gradingReadback?.score).isNull()
        assertThat(large.gradingReadback?.feedback).isEmpty()
        assertThat(large.gradingReadback?.explanation).isEqualTo("Saved explanation")
        assertThat(large.gradingReadback?.detailsAvailableInRecord).isTrue()
    }

    @Test
    fun `automatic continuation replaced during pending lookup cannot spend question quota`(): Unit = runBlocking {
        val fixture = Fixture().apply { records = emptyList() }
        var current = true
        fixture.afterInvoke = { if (it == "list_pending_questions") current = false }
        val result = fixture.coordinator.execute(fixture.context().copy(operationStillCurrent = { current }),
            "request_question", mapOf("study_id" to STUDY))
        assertCode(fixture, result, "STALE_TURN")
        assertThat(fixture.calls.map { it.name }).containsExactly("list_pending_questions")
        assertThat(result.learningProgress).isNull()
    }

    @Test
    fun `owner loss or focus change while pending read returns no late question`(): Unit = runBlocking {
        for (loseOwner in listOf(true, false)) {
            val fixture = Fixture()
            fixture.afterInvoke = { name -> if (name == "list_pending_questions") {
                if (loseOwner) fixture.isAuthorized = false else fixture.focus = (STUDY + 1) to (REVISION + 1)
            } }
            val result = fixture.coordinator.selected(fixture.context())
            assertCode(fixture, result, "QUESTION_CONTEXT_UNAVAILABLE")
            assertThat(result.questionChange).isNull()
        }
    }

    @Test
    fun `focus changing during the learner source await cannot publish a newly bound pending question`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.onLearnerRead = { fixture.focus = (STUDY + 1) to (REVISION + 1) }

        val result = fixture.coordinator.selected(fixture.context())

        assertCode(fixture, result, "QUESTION_CONTEXT_UNAVAILABLE")
        assertThat(result.questionChange).isNull()
    }

    @Test
    fun `stale scope after source read or exclusion never reaches canonical submission`(): Unit = runBlocking {
        for (loseScopeDuringExclusion in listOf(false, true)) {
            val fixture = Fixture()
            fixture.coordinator.selected(fixture.context())
            fixture.learner = 12
            if (loseScopeDuringExclusion) fixture.onExclude = { fixture.isAuthorized = false }
            else fixture.onSourceRead = { fixture.focus = (STUDY + 1) to (REVISION + 1) }

            val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())

            assertCode(fixture, result, if (loseScopeDuringExclusion) "QUESTION_CONTEXT_UNAVAILABLE" else "INPUT_PERSISTENCE_PENDING")
            assertThat(fixture.calls.none { it.name == "submit_answer" }).isTrue()
            assertThat(result.questionChange).isNull()
        }
    }

    @Test
    fun `authorization lost during canonical submission suppresses the late result without inventing rollback`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.selected(fixture.context())
        fixture.learner = 12
        fixture.afterInvoke = { name -> if (name == "submit_answer") fixture.isAuthorized = false }

        val result = fixture.coordinator.submitReviewedAnswer(fixture.answerContext(), fixture.reviewed())

        assertCode(fixture, result, "QUESTION_CONTEXT_UNAVAILABLE")
        assertThat(result.questionChange).isNull()
        assertThat(fixture.calls.count { it.name == "submit_answer" }).isEqualTo(1)
        assertThat(fixture.excluded).containsExactly("question-read", "answer-1", "answer-2")
    }

    private fun assertCode(fixture: Fixture, result: VoiceTutorMcpToolResult, code: String) {
        assertThat(result.isError).isTrue()
        assertThat(fixture.json(result).path("error").path("code").asText()).isEqualTo(code)
    }

    private data class Call(val name: String, val arguments: Map<String, Any>)

    private class Fixture {
        val mapper = jacksonObjectMapper()
        val now: Instant = Instant.parse("2026-09-10T04:00:00Z")
        val principal = Principal(7, "device", 9, false)
        val calls = mutableListOf<Call>()
        val events = mutableListOf<String>()
        val persistenceCalls = mutableListOf<List<Any?>>()
        val excluded = mutableListOf<String>()
        var records: List<JsonNode> = listOf(record(101))
        var totalCount: Int? = null
        var learner: Long? = 10
        var focus: Pair<Long, Long>? = STUDY to REVISION
        var isAuthorized = true
        var exclusionAccepted = true
        var submissionFails = false
        var submissionResultLost = false
        var pendingPageTooLarge = false
        var generationRecord: JsonNode? = record(201)
        var processCorrelationOverride: String? = null
        var afterInvoke: (String) -> Unit = {}
        var onLearnerRead: () -> Unit = {}
        var onSourceRead: () -> Unit = {}
        var onExclude: () -> Unit = {}
        var source = listOf(
            turn(1, "question-read", VoiceTutorTranscriptRole.TUTOR, "좋아요. $PROMPT"),
            turn(2, "answer-1", VoiceTutorTranscriptRole.USER, "필요한 객체를  밖에서 받고"),
            turn(3, "answer-2", VoiceTutorTranscriptRole.USER, "직접 생성하지 않습니다."),
        )
        private var generationCount = 0
        private var savedAnswer: String? = null
        var gradeReady = false
        var gradingFails = false
        var recordReadFails = false
        var recordOverride: JsonNode? = null
        var generationReady = true
        private val persistence = Proxy.newProxyInstance(
            VoiceTutorPersistencePort::class.java.classLoader,
            arrayOf(VoiceTutorPersistencePort::class.java),
        ) { _, method, args ->
            when (method.name) {
                "canonicalAnswerTurns" -> {
                    persistenceCalls += args!!.dropLast(1)
                    events += "read-source"
                    onSourceRead()
                    source
                }
                "excludeCanonicalQuestionTurns" -> {
                    persistenceCalls += args!!.dropLast(1)
                    events += "exclude-source"
                    onExclude()
                    if (exclusionAccepted) excluded += (args[2] as List<*>).map { it as String }
                    exclusionAccepted
                }
                "toString" -> "CanonicalQuestionTestPersistence"
                else -> error("Unexpected persistence call ${method.name}")
            }
        } as VoiceTutorPersistencePort
        val coordinator = VoiceTutorCanonicalQuestionCoordinator(
            mapper, Clock.fixed(now, ZoneOffset.UTC), persistence,
            authorized = { isAuthorized && it.principal == principal },
            currentFocus = { focus },
            learnerTurn = { _, _ -> onLearnerRead(); learner },
            invoke = { _, name, args ->
                calls += Call(name, args.toMap())
                val result = when (name) {
                    "list_pending_questions" -> {
                        val limit = (args.getValue("limit") as Number).toInt()
                        val offset = (args.getValue("offset") as Number).toInt()
                        if (pendingPageTooLarge && limit > 1) VoiceTutorMcpToolResult("{\"error\":{\"code\":\"RESULT_TOO_LARGE\"}}", true)
                        else success(mapOf("records" to records.drop(offset).take(limit), "totalCount" to (totalCount ?: records.size)))
                    }
                    "request_question" -> success(mapOf("topicId" to STUDY, "correlationId" to "generation-${++generationCount}"))
                    "get_question_process" -> success(mapOf("correlationId" to (processCorrelationOverride ?: args["correlation_id"]), "terminal" to generationReady, "question" to if (generationReady) generationRecord else null))
                    "skip_question" -> success(record((args.getValue("record_id") as Number).toLong(), status = "SKIPPED"))
                    "submit_answer" -> {
                        events += "submit_answer"
                        if (submissionFails) VoiceTutorMcpToolResult("{\"error\":{\"code\":\"CANONICAL_WRITE_FAILED\"}}", true)
                        else {
                            savedAnswer = args.getValue("answer") as String
                            if (submissionResultLost) VoiceTutorMcpToolResult("{\"error\":{\"code\":\"RESULT_UNCONFIRMED\"}}", true)
                            else success(record((args.getValue("record_id") as Number).toLong(), status = "GRADING", answer = savedAnswer, gradingId = "grade-1"))
                        }
                    }
                    "get_grading_process" -> {
                        gradeReady = true
                        success(mapOf("correlationId" to args["correlation_id"], "terminal" to true,
                            "gradingResult" to mapOf("score" to 17, "feedback" to "Wrong poll feedback")))
                    }
                    "get_record" -> if (recordReadFails) VoiceTutorMcpToolResult("{\"error\":{\"code\":\"READ_TIMEOUT\"}}", true)
                    else success(recordOverride ?: record((args.getValue("record_id") as Number).toLong(),
                        status = if (savedAnswer == null) "UNGRADED" else if (gradeReady && gradingFails) "FAILED" else if (gradeReady) "GRADED" else "GRADING", answer = savedAnswer,
                        gradingId = if (savedAnswer != null) "grade-1" else null,
                        grade = if (gradeReady && !gradingFails) mapOf("score" to 91, "feedback" to "Canonical feedback", "explanation" to "Canonical explanation") else null))
                    else -> error("Unexpected canonical call $name")
                }
                afterInvoke(name)
                result
            },
        )

        fun context() = VoiceTutorWebRtcControlContext(
            session(), "call", principal, initialLessonRevision = REVISION, realtimeModelTools = true,
            dialogueBoundary = VoiceTutorDialogueBoundary(5, 100, 90, 4, "prior-tutor", "selection", REVISION),
        )

        fun answerContext() = context().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
            7, 130, 120, 6, "question-read", "answer-2", REVISION,
        ))

        fun reviewed(text: String = EDITED_ANSWER) = VoiceTutorReviewedAnswer(
            answerId = "739a067a-7578-4d42-80bf-f66d80725802", studyId = STUDY, recordId = "101",
            lessonRevision = REVISION, text = text, precedingTutorProviderItemId = "question-read",
            learnerProviderItemIds = listOf("answer-1", "answer-2"),
        )

        fun record(
            id: Long, study: Long = STUDY, status: String = "UNGRADED", answer: String? = null,
            difficulty: Int = 3, createdAt: String = "2026-09-09T01:00:00Z", gradingId: String? = null,
            grade: Map<String, Any>? = null, questionText: String = PROMPT,
        ): JsonNode = mapper.valueToTree(mapOf(
            "id" to id.toString(), "studyId" to study, "topic" to "Dependency injection", "difficulty" to difficulty,
            "questionStatus" to status, "answer" to answer, "gradingRequestId" to gradingId, "gradingResult" to grade,
            "question" to mapOf("question" to questionText, "createdAt" to createdAt, "expectedAnswerHint" to "SECRET_HINT"),
            "expectedAnswerHint" to "SECRET_HINT", "gradingRubric" to "SECRET_RUBRIC",
        ))

        fun json(result: VoiceTutorMcpToolResult): JsonNode = mapper.readTree(result.output)
        private fun success(value: Any) = VoiceTutorMcpToolResult(mapper.writeValueAsString(value), false)
        private fun turn(id: Long, providerId: String, role: VoiceTutorTranscriptRole, text: String) = VoiceTutorTranscriptTurn(
            id, SESSION, providerId, role, text, id, now, lessonRevision = REVISION, postCallEvidence = true,
        )

        private fun session() = VoiceTutorSession(
            id = SESSION, userId = 7, studyId = STUDY, idempotencyKey = "reserved", providerSessionId = "provider",
            status = VoiceTutorSessionStatus.ACTIVE, resultStatus = VoiceTutorResultStatus.PENDING,
            language = "ko", model = "realtime", voice = "marin", topic = "Dependency injection", difficulty = 9,
            periodStartedAt = now, periodEndsAt = now.plusSeconds(86_400), reservedSeconds = 600, chargedSeconds = 0,
            maxSessionSeconds = 600, hardEndsAt = now.plusSeconds(600), connectedAt = now, relayHeartbeatAt = now,
            acceptedAudioBytes = 0, endedAt = null, finalizedAt = null, endReason = null, failureCode = null,
            failureMessage = null, createdAt = now, updatedAt = now,
        )
    }

    private companion object {
        const val STUDY = 41L
        const val REVISION = 3L
        const val SESSION = "canonical-voice-session"
        const val PROMPT = "의존성 주입이 무엇인가요?"
        const val ANSWER = "필요한 객체를  밖에서 받고\n직접 생성하지 않습니다."
        const val EDITED_ANSWER = "  객체를 직접 만들지 않고 외부에서 전달받습니다.\n테스트에서는 가짜 구현을 주입할 수 있습니다.  "
    }
}
