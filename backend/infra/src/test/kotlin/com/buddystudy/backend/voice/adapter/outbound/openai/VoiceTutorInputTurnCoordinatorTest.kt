package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorInputTurnCoordinator.Action
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorInputTurnCoordinator.RetryReason
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Deterministic state tests: fake classifier decisions and clock, no provider or audio. */
class VoiceTutorInputTurnCoordinatorTest {
    @Test
    fun `meaningful transcript becomes ready only after exact publication confirmation`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        assertThat(coordinator.observeCommitted("item-one", 7, true, 0)).isEmpty()
        val assessment = assess(coordinator.observeTranscript("item-one", "응", "original-event", 1))

        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 2))
            .containsExactly(Action.Publish("item-one", "original-event", sequence = 7, checkpoint = true))
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.confirmPublished("other", 3)).isEmpty()
        assertThat(coordinator.confirmDeleted("item-one", 3)).isEmpty()
        assertThat(coordinator.confirmPublished("item-one", 4)).containsExactly(Action.Ready(7, true))
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.confirmPublished("item-one", 5)).isEmpty()
    }

    @Test
    fun `structured spoken lesson end intent stays bound to the exact meaningful publication`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.observeCommitted("end-item", 9, false, 0)
        val assessment = assess(coordinator.observeTranscript("end-item", "학습 끝낼게", "end-raw", 1))
        val result = VoiceTutorInputAssessmentResult(listOf(
            VoiceTutorInputItemAssessment(
                "end-item", VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
            ),
        ))

        assertThat(coordinator.completeAssessment(assessment.token, Result.success(result), 2)).containsExactly(
            Action.Publish(
                "end-item", "end-raw", sequence = 9,
                intent = VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
            ),
        )
        assertThat(coordinator.confirmPublished("other-item", 3)).isEmpty()
        assertThat(coordinator.confirmPublished("end-item", 4)).containsExactly(Action.Ready(9, false))
        assertThat(coordinator.confirmPublished("end-item", 5)).isEmpty()
    }

    @Test
    fun `non communicative input blocks response until exact provider deletion acknowledgement`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "음…")

        assertThat(coordinator.completeAssessment(assessment.token, nonCommunicative(assessment), 1))
            .containsExactly(Action.Delete("item-one"))
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.confirmPublished("item-one", 2)).isEmpty()
        assertThat(coordinator.confirmDeleted("tutor-item", 2)).isEmpty()
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.confirmDeleted("item-one", 3)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 4)).isEmpty()
    }

    @Test
    fun `ASR before commit stays untrusted until its exact user item is registered`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        assertThat(coordinator.observeTranscript("item-one", "Redis", "verbatim-raw", 0)).isEmpty()
        assertThat(coordinator.bufferedTranscriptCount).isEqualTo(1)
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.confirmPublished("item-one", 1)).isEmpty()
        assertThat(coordinator.confirmDeleted("item-one", 1)).isEmpty()

        val assessment = assess(coordinator.observeCommitted("item-one", 2, false, 2))
        assertThat(assessment.utterances.single().transcript).isEqualTo("Redis")
        assertThat(coordinator.bufferedTranscriptCount).isZero()
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 3))
            .containsExactly(Action.Publish("item-one", "verbatim-raw", sequence = 2))
    }

    @Test
    fun `transcription failure before acknowledgement is retried and deleted not classified as filler`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        assertThat(coordinator.observeTranscriptionFailure("item-one", 0)).isEmpty()
        assertThat(coordinator.observeTranscript("item-one", "later replacement", "replacement", 1)).isEmpty()

        assertThat(coordinator.observeCommitted("item-one", 1, false, 2)).containsExactly(
            Action.Retry(RetryReason.TRANSCRIPTION_FAILED),
            Action.Delete("item-one"),
        )
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.confirmDeleted("item-one", 3)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `first completed ASR and original commit metadata cannot be replaced by duplicates`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "first text")

        assertThat(coordinator.observeCommitted("item-one", 999, true, 1)).isEmpty()
        assertThat(coordinator.observeTranscript("item-one", "replacement", "replacement-event", 2)).isEmpty()
        assertThat(coordinator.observeTranscriptionFailure("item-one", 3)).isEmpty()
        assertThat(coordinator.completeAssessment(999, meaningful(assessment), 4)).isEmpty()
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 5))
            .containsExactly(Action.Publish("item-one", raw("item-one", "first text"), sequence = 1))
        assertThat(coordinator.completeAssessment(assessment.token, nonCommunicative(assessment), 6)).isEmpty()
        assertThat(coordinator.confirmPublished("item-one", 7)).containsExactly(Action.Ready(1, false))
        assertThat(coordinator.observeCommitted("item-one", 1000, true, 8)).isEmpty()
        assertThat(coordinator.observeTranscript("item-one", "late text", "late-event", 9)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `out of order ASR and assessment IDs preserve acknowledged user order in a mixed batch`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.teacherResponseStarted()
        coordinator.observeCommitted("first", 11, true, 0)
        coordinator.observeCommitted("second", 12, false, 0)
        assertThat(coordinator.observeTranscript("second", "주제 알려줘", "second-raw", 1)).isEmpty()
        assertThat(coordinator.teacherResponseCompleted("준비됐나요?", 2)).isEmpty()
        val assessment = assess(coordinator.observeTranscript("first", "어…", "first-raw", 3))
        assertThat(assessment.utterances.map { it.itemId }).containsExactly("first", "second")

        val reversedResult = Result.success(
            VoiceTutorInputAssessmentResult(
                listOf(
                    VoiceTutorInputItemAssessment("second", VoiceTutorInputDecision.MEANINGFUL),
                    VoiceTutorInputItemAssessment("first", VoiceTutorInputDecision.NON_COMMUNICATIVE),
                ),
            ),
        )
        assertThat(coordinator.completeAssessment(assessment.token, reversedResult, 4)).containsExactly(
            Action.Delete("first"), Action.Publish("second", "second-raw", sequence = 12),
        )
        assertThat(coordinator.confirmPublished("second", 5)).containsExactly(Action.Ready(12, false))
        assertThat(coordinator.hasPending).isTrue()
        assertThat(coordinator.confirmDeleted("first", 6)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `short meaningful words numbers punctuation and hesitation text all reach classifier verbatim`() {
        val originals = listOf("응", "아니", "네", "이수민", "Redis", "2", "어, 준비됐어.", "주제 알려줘.", "어…", "음…", "…", " '음' ")
        for (text in originals) {
            val coordinator = VoiceTutorInputTurnCoordinator()
            val assessment = registered(coordinator, "item-one", text)
            assertThat(assessment.utterances.single().transcript).isEqualTo(text)
            // These are deliberately fake decisions. This proves no local word
            // list or minimum-length rule overrides the semantic assessment.
            assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 1))
                .containsExactly(Action.Publish("item-one", raw("item-one", text), sequence = 1))
        }
    }

    @Test
    fun `empty transcript has no semantic content but still requires deletion confirmation`() {
        for (text in listOf("", " \n\t", "　")) {
            val coordinator = VoiceTutorInputTurnCoordinator()
            coordinator.observeCommitted("empty", 1, false, 0)
            assertThat(coordinator.observeTranscript("empty", text, "empty-raw", 1))
                .containsExactly(Action.Delete("empty"))
            assertThat(coordinator.hasPending).isTrue()
            assertThat(coordinator.confirmDeleted("empty", 2)).isEmpty()
            assertThat(coordinator.hasPending).isFalse()
        }
    }

    @Test
    fun `classifier failure retries once per batch and removes every unapproved user item`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = preparedBatch(coordinator, listOf("one", "two", "three"))
        val failure = Result.failure<VoiceTutorInputAssessmentResult>(
            VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.UNAVAILABLE),
        )

        assertThat(coordinator.completeAssessment(assessment.token, failure, 1)).containsExactly(
            Action.Retry(RetryReason.ASSESSMENT_UNAVAILABLE),
            Action.Delete("item-0"), Action.Delete("item-1"), Action.Delete("item-2"),
        )
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 2)).isEmpty()
        for (index in 0..2) {
            assertThat(coordinator.hasPending).isTrue()
            assertThat(coordinator.confirmDeleted("item-$index", 3)).isEmpty()
        }
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `missing duplicate extra or wrong classifier IDs never approve a partial batch`() {
        val invalidIds = listOf(
            emptyList(), listOf("item-0"), listOf("item-0", "item-0"),
            listOf("item-0", "other"), listOf("item-0", "item-1", "other"),
        )
        for (ids in invalidIds) {
            val coordinator = VoiceTutorInputTurnCoordinator()
            val assessment = preparedBatch(coordinator, listOf("응", "2"))
            val result = Result.success(
                VoiceTutorInputAssessmentResult(ids.map { VoiceTutorInputItemAssessment(it, VoiceTutorInputDecision.MEANINGFUL) }),
            )
            assertThat(coordinator.completeAssessment(assessment.token, result, 1)).containsExactly(
                Action.Retry(RetryReason.INVALID_ASSESSMENT_RESULT),
                Action.Delete("item-0"), Action.Delete("item-1"),
            )
            assertThat(coordinator.hasPending).isTrue()
        }
    }

    @Test
    fun `ASR expiry is exactly eight seconds and duplicate commits cannot extend it`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.observeCommitted("item-one", 1, false, 0)
        assertThat(coordinator.expire(ms(7_999))).isEmpty()
        assertThat(coordinator.observeCommitted("item-one", 1, false, ms(7_999))).isEmpty()
        assertThat(coordinator.expire(ms(8_000))).containsExactly(
            Action.Retry(RetryReason.TRANSCRIPTION_TIMEOUT), Action.Delete("item-one"),
        )
        assertThat(coordinator.expire(ms(8_001))).isEmpty()
        assertThat(coordinator.observeTranscript("item-one", "too late", "late-event", ms(8_001))).isEmpty()
        assertThat(coordinator.confirmDeleted("item-one", ms(8_002))).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `explicit ASR failure for a committed user gives retry and delete only`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.observeCommitted("item-one", 1, false, 0)
        assertThat(coordinator.observeTranscriptionFailure("item-one", 1)).containsExactly(
            Action.Retry(RetryReason.TRANSCRIPTION_FAILED), Action.Delete("item-one"),
        )
        assertThat(coordinator.observeTranscriptionFailure("item-one", 2)).isEmpty()
        assertThat(coordinator.hasPending).isTrue()
    }

    @Test
    fun `assessment expires exactly at deadline and late successful callback cannot publish`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "응")
        assertThat(coordinator.expire(ms(4_999))).isEmpty()
        assertThat(coordinator.expire(ms(5_000))).containsExactly(
            Action.Retry(RetryReason.ASSESSMENT_TIMEOUT), Action.Delete("item-one"),
        )
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), ms(5_001))).isEmpty()
        assertThat(coordinator.expire(ms(5_001))).isEmpty()
        assertThat(coordinator.confirmDeleted("item-one", ms(5_002))).isEmpty()
    }

    @Test
    fun `callback at expired assessment deadline cannot bypass missing timer tick`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "응")
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), ms(5_000))).containsExactly(
            Action.Retry(RetryReason.ASSESSMENT_TIMEOUT), Action.Delete("item-one"),
        )
        assertThat(coordinator.expire(ms(5_000))).isEmpty()
        assertThat(coordinator.hasPending).isTrue()
    }

    @Test
    fun `success before deadline cancels batch expiry and starts a separate publication lease`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "응")
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), ms(4_999)))
            .containsExactly(Action.Publish("item-one", raw("item-one", "응"), sequence = 1))
        assertThat(coordinator.expire(ms(5_000))).isEmpty()
        assertThat(coordinator.confirmPublished("item-one", ms(5_001))).containsExactly(Action.Ready(1, false))
        assertThat(coordinator.expire(ms(20_000))).isEmpty()
    }

    @Test
    fun `publication acknowledgement timeout closes coordinator with a typed terminal failure`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "응")
        coordinator.completeAssessment(assessment.token, meaningful(assessment), 0)
        assertThat(coordinator.expire(ms(4_999))).isEmpty()
        terminal(VoiceTutorInputTurnCoordinatorFailure.PUBLISH_ACK_TIMEOUT) {
            coordinator.confirmPublished("item-one", ms(5_000))
        }
        assertThat(coordinator.isClosed).isTrue()
        assertThat(coordinator.confirmPublished("item-one", ms(5_001))).isEmpty()
    }

    @Test
    fun `delete acknowledgement timeout cannot forget an undeleted user item and resume`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "음…")
        coordinator.completeAssessment(assessment.token, nonCommunicative(assessment), 0)
        assertThat(coordinator.expire(ms(4_999))).isEmpty()
        terminal(VoiceTutorInputTurnCoordinatorFailure.DELETE_ACK_TIMEOUT) { coordinator.expire(ms(5_000)) }
        assertThat(coordinator.isClosed).isTrue()
        assertThat(coordinator.confirmDeleted("item-one", ms(5_001))).isEmpty()
    }

    @Test
    fun `new teacher context prevents classifying until the completed transcript is available`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.teacherResponseStarted()
        coordinator.observeCommitted("item-one", 1, false, 0)
        assertThat(coordinator.observeTranscript("item-one", "아니", "raw", 1)).isEmpty()
        assertThat(coordinator.expire(2)).isEmpty()
        val assessment = assess(coordinator.teacherResponseCompleted("지금 학습을 시작할까요?", 3))
        assertThat(assessment.teacherContext).isEqualTo("지금 학습을 시작할까요?")
    }

    @Test
    fun `assessment from previous context is discarded and reissued only after new teacher completion`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val first = registered(coordinator, "item-one", "응")
        coordinator.teacherResponseStarted()
        assertThat(coordinator.completeAssessment(first.token, meaningful(first), 1)).isEmpty()
        val second = assess(coordinator.teacherResponseCompleted("숫자를 말해 주세요.", 2))
        assertThat(second.token).isGreaterThan(first.token)
        assertThat(second.teacherContext).isEqualTo("숫자를 말해 주세요.")
        assertThat(second.utterances).isEqualTo(first.utterances)
        assertThat(coordinator.completeAssessment(first.token, meaningful(first), 3)).isEmpty()
        assertThat(coordinator.completeAssessment(second.token, meaningful(second), 4))
            .containsExactly(Action.Publish("item-one", raw("item-one", "응"), sequence = 1))
    }

    @Test
    fun `context completing before an old callback does not start concurrent assessments`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val first = registered(coordinator, "item-one", "응")
        coordinator.teacherResponseStarted()
        assertThat(coordinator.teacherResponseCompleted("발음할 단어를 고르세요.", 1)).isEmpty()
        val second = assess(coordinator.completeAssessment(first.token, nonCommunicative(first), 2))
        assertThat(second.token).isGreaterThan(first.token)
        assertThat(second.teacherContext).isEqualTo("발음할 단어를 고르세요.")
        assertThat(coordinator.hasPending).isTrue()
    }

    @Test
    fun `expired old context assessment releases its batch without using its stale decision`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val first = registered(coordinator, "item-one", "2")
        coordinator.teacherResponseStarted()
        assertThat(coordinator.expire(ms(5_000))).isEmpty()
        val second = assess(coordinator.teacherResponseCompleted("다음 항목을 선택해 주세요.", ms(5_001)))
        assertThat(second.token).isGreaterThan(first.token)
        assertThat(coordinator.completeAssessment(first.token, meaningful(first), ms(5_002))).isEmpty()
    }

    @Test
    fun `blank teacher completion retains bounded previous context without modifying user text`() {
        val coordinator = VoiceTutorInputTurnCoordinator(VoiceTutorInputAssessmentProperties(maxTeacherContextCharacters = 8))
        coordinator.teacherResponseCompleted("가나다라마바사아자차카타파하", 0)
        coordinator.teacherResponseStarted()
        coordinator.observeCommitted("item-one", 1, false, 1)
        coordinator.observeTranscript("item-one", " 응 ", "raw", 2)
        val assessment = assess(coordinator.teacherResponseCompleted(" \n", 3))
        assertThat(assessment.teacherContext).isEqualTo("가나다라마바사아자차카타파하".takeLast(8))
        assertThat(assessment.utterances.single().transcript).isEqualTo(" 응 ")
    }

    @Test
    fun `sixteen pending inputs form at most eight item batches without overtaking outstanding deletes`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val first = preparedBatch(coordinator, (0..15).map { "단어$it" })
        assertThat(first.utterances).hasSize(8)
        assertThat(coordinator.pendingCount).isEqualTo(16)
        assertThat(coordinator.completeAssessment(first.token, nonCommunicative(first), 1).filterIsInstance<Action.Assess>()).isEmpty()
        for (index in 0..6) assertThat(coordinator.confirmDeleted("item-$index", 2)).isEmpty()
        val second = assess(coordinator.confirmDeleted("item-7", 2))
        assertThat(second.utterances.map { it.itemId }).containsExactlyElementsOf((8..15).map { "item-$it" })
        assertThat(coordinator.pendingCount).isEqualTo(8)
    }

    @Test
    fun `batch total character bound splits intact utterances without truncation`() {
        val limits = VoiceTutorInputAssessmentProperties(maxTranscriptCharacters = 4, maxBatchTranscriptCharacters = 6)
        val coordinator = VoiceTutorInputTurnCoordinator(limits)
        val first = preparedBatch(coordinator, listOf("1234", "5678", "90"))
        assertThat(first.utterances.map { it.transcript }).containsExactly("1234")
        coordinator.completeAssessment(first.token, meaningful(first), 1)
        val nextActions = coordinator.confirmPublished("item-0", 2)
        assertThat(nextActions.first()).isEqualTo(Action.Ready(1, false))
        val second = nextActions.filterIsInstance<Action.Assess>().single()
        assertThat(second.utterances.map { it.transcript }).containsExactly("5678", "90")
    }

    @Test
    fun `final tail assessment receives bounded same speech checkpoint context without rewriting either item`() {
        val coordinator = VoiceTutorInputTurnCoordinator(
            VoiceTutorInputAssessmentProperties(maxTranscriptCharacters = 32, maxBatchTranscriptCharacters = 64),
        )
        coordinator.observeCommitted("checkpoint", 7, checkpoint = true, nowNanos = 0)
        val checkpoint = assess(coordinator.observeTranscript(
            "checkpoint", "Redis는 메모리 데이터 저장소예요", "checkpoint-original", 1,
        ))
        assertThat(checkpoint.utterances.single().sameSpeechContext).isNull()
        assertThat(coordinator.completeAssessment(checkpoint.token, meaningful(checkpoint), 2)).containsExactly(
            Action.Publish("checkpoint", "checkpoint-original", sequence = 7, checkpoint = true),
        )
        coordinator.confirmPublished("checkpoint", 3)

        coordinator.observeCommitted("tail", 7, checkpoint = false, nowNanos = 4)
        val completed = assess(coordinator.observeTranscript("tail", "음…", "tail-original", 5))
        val utterance = completed.utterances.single()
        assertThat(utterance.transcript).isEqualTo("음…")
        assertThat(utterance.sameSpeechContext)
            .endsWith("Redis는 메모리 데이터 저장소예요\n음…")
            .hasSizeLessThanOrEqualTo(32)

        val result = Result.success(VoiceTutorInputAssessmentResult(listOf(
            VoiceTutorInputItemAssessment(
                "tail", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
            ),
        )))
        assertThat(coordinator.completeAssessment(completed.token, result, 6)).containsExactly(
            Action.Publish(
                "tail", "tail-original", sequence = 7,
                intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
            ),
        )
    }

    @Test
    fun `rejected checkpoint noise never becomes semantic context for the final tail`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.observeCommitted("noise-checkpoint", 8, checkpoint = true, nowNanos = 0)
        val noise = assess(coordinator.observeTranscript("noise-checkpoint", "어… 음…", "noise-original", 1))
        assertThat(coordinator.completeAssessment(noise.token, nonCommunicative(noise), 2)).containsExactly(
            Action.Delete("noise-checkpoint"),
        )
        coordinator.confirmDeleted("noise-checkpoint", 3)

        coordinator.observeCommitted("final-tail", 8, checkpoint = false, nowNanos = 4)
        val completed = assess(coordinator.observeTranscript("final-tail", "Redis", "tail-original", 5))
        assertThat(completed.utterances.single().transcript).isEqualTo("Redis")
        assertThat(completed.utterances.single().sameSpeechContext).isNull()
    }

    @Test
    fun `oversized individual transcript or raw event retries and deletes rather than truncating`() {
        for ((text, event) in listOf("x".repeat(4_001) to "raw", "응" to "x".repeat(64_001))) {
            val coordinator = VoiceTutorInputTurnCoordinator()
            coordinator.observeCommitted("item-one", 1, false, 0)
            assertThat(coordinator.observeTranscript("item-one", text, event, 1)).containsExactly(
                Action.Retry(RetryReason.INPUT_TOO_LARGE), Action.Delete("item-one"),
            )
            assertThat(coordinator.hasPending).isTrue()
        }
    }

    @Test
    fun `oversized early transcript retains rejection only until its user acknowledgement`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        assertThat(coordinator.observeTranscript("item-one", "x".repeat(4_001), "raw", 0)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.observeCommitted("item-one", 1, false, 1)).containsExactly(
            Action.Retry(RetryReason.INPUT_TOO_LARGE), Action.Delete("item-one"),
        )
    }

    @Test
    fun `individual text exceeding configured batch bound is rejected instead of getting stuck forever`() {
        val coordinator = VoiceTutorInputTurnCoordinator(VoiceTutorInputAssessmentProperties(maxBatchTranscriptCharacters = 2))
        coordinator.observeCommitted("item-one", 1, false, 0)
        assertThat(coordinator.observeTranscript("item-one", "123", "raw", 1)).containsExactly(
            Action.Retry(RetryReason.INPUT_TOO_LARGE), Action.Delete("item-one"),
        )
    }

    @Test
    fun `pending capacity overflow is terminal rather than forgetting an unapproved provider item`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        for (index in 1..16) coordinator.observeCommitted("item-$index", index.toLong(), false, 0)
        assertThat(coordinator.pendingCount).isEqualTo(16)
        terminal(VoiceTutorInputTurnCoordinatorFailure.PENDING_CAPACITY_EXCEEDED) {
            coordinator.observeCommitted("item-17", 17, false, 0)
        }
        assertThat(coordinator.isClosed).isTrue()
        assertThat(coordinator.observeTranscript("item-1", "응", "raw", 1)).isEmpty()
    }

    @Test
    fun `invalid registered item identifiers or speech sequence cannot produce arbitrary deletion actions`() {
        for ((id, sequence) in listOf("" to 1L, "user\nitem" to 1L, "x".repeat(257) to 1L, "item" to 0L)) {
            val coordinator = VoiceTutorInputTurnCoordinator()
            terminal(VoiceTutorInputTurnCoordinatorFailure.INVALID_COMMITTED_ITEM) {
                coordinator.observeCommitted(id, sequence, false, 0)
            }
            assertThat(coordinator.isClosed).isTrue()
        }
    }

    @Test
    fun `unknown ASR cache is bounded and eviction never grants readiness`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        for (index in 0..16) {
            assertThat(coordinator.observeTranscript("item-$index", "text-$index", "raw-$index", 0)).isEmpty()
        }
        assertThat(coordinator.bufferedTranscriptCount).isEqualTo(16)
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.observeCommitted("item-0", 1, false, 1)).isEmpty()
        assertThat(coordinator.observeCommitted("item-1", 2, false, 2)).isEmpty()
        val assessment = assess(coordinator.observeTranscript("item-0", "text-0", "raw-0", 3))
        assertThat(assessment.utterances.map { it.transcript }).containsExactly("text-0", "text-1")
    }

    @Test
    fun `unregistered transcript cache expires without publishing deleting or marking pending`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.observeTranscript("unknown", "응", "raw", 0)
        assertThat(coordinator.expire(ms(8_000))).isEmpty()
        assertThat(coordinator.bufferedTranscriptCount).isZero()
        assertThat(coordinator.hasPending).isFalse()
    }

    @Test
    fun `recent finalized user IDs remain idempotent across sixty four items`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        for (index in 1..64) {
            val id = "item-$index"
            coordinator.observeCommitted(id, index.toLong(), false, 0)
            assertThat(coordinator.observeTranscript(id, "", "raw", 0)).containsExactly(Action.Delete(id))
            coordinator.confirmDeleted(id, 0)
        }
        assertThat(coordinator.observeCommitted("item-1", 65, false, 1)).isEmpty()
        assertThat(coordinator.observeTranscript("item-1", "late text", "late-raw", 1)).isEmpty()
        assertThat(coordinator.observeTranscriptionFailure("item-64", 1)).isEmpty()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.bufferedTranscriptCount).isZero()
    }

    @Test
    fun `configured limits are snapshotted and assessment deadline follows configured service budget`() {
        val limits = VoiceTutorInputAssessmentProperties(timeoutMilliseconds = 2_000, maxUtterances = 1)
        val coordinator = VoiceTutorInputTurnCoordinator(limits)
        limits.timeoutMilliseconds = 15_000
        limits.maxUtterances = 8
        val assessment = preparedBatch(coordinator, listOf("응", "2"))
        assertThat(assessment.utterances).hasSize(1)
        assertThat(coordinator.expire(ms(1_999))).isEmpty()
        assertThat(coordinator.expire(ms(2_000))).containsExactly(
            Action.Retry(RetryReason.ASSESSMENT_TIMEOUT), Action.Delete("item-0"),
        )
    }

    @Test
    fun `close clears state and ignores every late callback without Ready`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val assessment = registered(coordinator, "item-one", "응")
        coordinator.observeTranscript("early", "2", "raw", 1)
        coordinator.close()
        coordinator.close()
        assertThat(coordinator.pendingCount).isZero()
        assertThat(coordinator.bufferedTranscriptCount).isZero()
        assertThat(coordinator.hasPending).isFalse()
        assertThat(coordinator.observeCommitted("new", 2, false, 2)).isEmpty()
        assertThat(coordinator.observeTranscript("item-one", "late", "raw", 2)).isEmpty()
        assertThat(coordinator.observeTranscriptionFailure("item-one", 2)).isEmpty()
        coordinator.teacherResponseStarted()
        assertThat(coordinator.teacherResponseCompleted("late context", 2)).isEmpty()
        assertThat(coordinator.completeAssessment(assessment.token, meaningful(assessment), 2)).isEmpty()
        assertThat(coordinator.confirmPublished("item-one", 2)).isEmpty()
        assertThat(coordinator.confirmDeleted("item-one", 2)).isEmpty()
        assertThat(coordinator.expire(ms(100_000))).isEmpty()
    }

    @Test
    fun `monotonic expiry handles nanoTime wrap and does not expire backwards clock values`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        val start = Long.MAX_VALUE - ms(2_000)
        coordinator.observeCommitted("item-one", 1, false, start)
        assertThat(coordinator.expire(start - 1)).isEmpty()
        assertThat(coordinator.expire(start + ms(7_999))).isEmpty()
        assertThat(coordinator.expire(start + ms(8_000))).containsExactly(
            Action.Retry(RetryReason.TRANSCRIPTION_TIMEOUT), Action.Delete("item-one"),
        )
    }

    @Test
    fun `action and failure diagnostics contain neither private utterances nor provider payloads`() {
        val coordinator = VoiceTutorInputTurnCoordinator()
        coordinator.teacherResponseCompleted("private-teacher-context", 0)
        val assessment = registered(coordinator, "private-item-id", "private-user-text")
        assertThat(assessment.toString()).doesNotContain("private-teacher-context", "private-user-text", "private-item-id")
        val actions = coordinator.completeAssessment(assessment.token, meaningful(assessment), 1)
        assertThat(actions.toString()).doesNotContain("private-user-text", "private-item-id")
        assertThat(Action.Delete("private-item-id").toString()).doesNotContain("private-item-id")
        val error = VoiceTutorInputTurnCoordinatorException(VoiceTutorInputTurnCoordinatorFailure.DELETE_ACK_TIMEOUT)
        assertThat(error.message).isEqualTo("Voice Tutor input turn failed: DELETE_ACK_TIMEOUT.")
    }

    private fun registered(coordinator: VoiceTutorInputTurnCoordinator, itemId: String, text: String): Action.Assess {
        assertThat(coordinator.observeCommitted(itemId, 1, false, 0)).isEmpty()
        return assess(coordinator.observeTranscript(itemId, text, raw(itemId, text), 0))
    }

    private fun preparedBatch(coordinator: VoiceTutorInputTurnCoordinator, texts: List<String>): Action.Assess {
        coordinator.teacherResponseStarted()
        texts.forEachIndexed { index, text ->
            val id = "item-$index"
            assertThat(coordinator.observeCommitted(id, index + 1L, false, 0)).isEmpty()
            assertThat(coordinator.observeTranscript(id, text, raw(id, text), 0)).isEmpty()
        }
        return assess(coordinator.teacherResponseCompleted("학습을 시작할 준비가 됐나요?", 0))
    }

    private fun assess(actions: List<Action>): Action.Assess {
        assertThat(actions).hasSize(1)
        return actions.single() as Action.Assess
    }

    private fun meaningful(assessment: Action.Assess) = decisions(assessment, VoiceTutorInputDecision.MEANINGFUL)

    private fun nonCommunicative(assessment: Action.Assess) = decisions(assessment, VoiceTutorInputDecision.NON_COMMUNICATIVE)

    private fun decisions(assessment: Action.Assess, decision: VoiceTutorInputDecision) = Result.success(
        VoiceTutorInputAssessmentResult(assessment.utterances.map { VoiceTutorInputItemAssessment(it.itemId, decision) }),
    )

    private fun terminal(reason: VoiceTutorInputTurnCoordinatorFailure, action: () -> Unit) {
        val failure = assertThrows(VoiceTutorInputTurnCoordinatorException::class.java) { action() }
        assertThat(failure.reason).isEqualTo(reason)
    }

    private fun raw(itemId: String, text: String): String = "original-final-event:$itemId:$text"
    private fun ms(value: Long): Long = value * 1_000_000L
}
