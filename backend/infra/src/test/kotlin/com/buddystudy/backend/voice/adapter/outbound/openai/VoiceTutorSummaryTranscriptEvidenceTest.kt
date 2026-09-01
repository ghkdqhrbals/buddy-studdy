package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorSummaryTranscriptEvidenceTest {
    private val now = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `summary contains exact question answer feedback and excludes checkpoints and setup`() {
        val turns = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "음, 외부에서"),
            turn(3, VoiceTutorTranscriptRole.USER, "외부에서 구현체를 주입합니다.", studyQuestionTurnId = 1),
            turn(4, VoiceTutorTranscriptRole.TUTOR, "85점입니다. 결합도를 짚었습니다.", studyAnswerTurnId = 3),
            turn(5, VoiceTutorTranscriptRole.TUTOR, "Spring 루트를 만들까요?"),
            turn(6, VoiceTutorTranscriptRole.USER, "네"),
        )

        val result = VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, turns, listOf(VoiceTutorLessonFocus(42, 1)),
        )

        assertThat(result.map { it.id }).containsExactly(1, 3, 4)
    }

    @Test
    fun `unlinked other answer revision next question and setup tutor cannot become feedback`() {
        val base = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "결합도를 낮춥니다.", studyQuestionTurnId = 1),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "다음 질문입니다.", isStudyQuestion = true),
            turn(4, VoiceTutorTranscriptRole.TUTOR, "90점입니다.", studyAnswerTurnId = 2),
            turn(5, VoiceTutorTranscriptRole.TUTOR, "레벨을 바꿀까요?", studyAnswerTurnId = 999),
            turn(6, VoiceTutorTranscriptRole.TUTOR, "80점입니다.", revision = 2, studyAnswerTurnId = 2),
        )

        val result = VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, base, listOf(VoiceTutorLessonFocus(42, 1)),
        )

        assertThat(result.map { it.id }).containsExactly(1, 2)
    }

    @Test
    fun `mutation command after a real tutor question is not an answer or summary evidence`() {
        val turns = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", isStudyQuestion = true),
            turn(
                2, VoiceTutorTranscriptRole.USER,
                "Spring 주제 이름을 Spring Boot로 바꾸고 레벨을 7로 수정해줘.",
            ),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "Spring Boot, 레벨 7로 수정했습니다.", revision = 2),
        )

        val result = VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, turns, listOf(VoiceTutorLessonFocus(42, 1)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `intervening tutor invalidates an old question link while user checkpoint does not`() {
        val checkpointOnly = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "질문", isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "생각 중"),
            turn(3, VoiceTutorTranscriptRole.USER, "답", studyQuestionTurnId = 1),
        )
        val interveningTutor = checkpointOnly.toMutableList().apply {
            add(2, turn(20, VoiceTutorTranscriptRole.TUTOR, "설정 안내", sequence = 2))
            this[1] = this[1].copy(sequenceNumber = 3)
            this[3] = this[3].copy(sequenceNumber = 4)
        }

        assertThat(VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, checkpointOnly, listOf(VoiceTutorLessonFocus(42, 1)),
        ).map { it.id }).containsExactly(1, 3)
        assertThat(VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, interveningTutor, listOf(VoiceTutorLessonFocus(42, 1)),
        )).isEmpty()
    }

    @Test
    fun `character budget includes every multipart answer part or excludes the whole exchange`() {
        val turns = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "질문", isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "첫 답", studyQuestionTurnId = 1),
            turn(3, VoiceTutorTranscriptRole.USER, "둘째 답", studyQuestionTurnId = 1),
            turn(4, VoiceTutorTranscriptRole.TUTOR, "피드백", studyAnswerTurnId = 3),
        )
        val evidence = VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, turns, listOf(VoiceTutorLessonFocus(42, 1)),
        )

        assertThat(VoiceTutorSummaryTranscriptEvidence.completeExchangePrefix(evidence, 7)).isEmpty()
        assertThat(VoiceTutorSummaryTranscriptEvidence.completeExchangePrefix(evidence, 12).map { it.id })
            .containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `summary excludes feedback linked to an earlier multipart answer part`() {
        val turns = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "질문", isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "첫 답", studyQuestionTurnId = 1),
            turn(3, VoiceTutorTranscriptRole.USER, "마지막 답", studyQuestionTurnId = 1),
            turn(4, VoiceTutorTranscriptRole.TUTOR, "85점입니다.", studyAnswerTurnId = 2),
        )

        val result = VoiceTutorSummaryTranscriptEvidence.verified(
            SESSION, 42, turns, listOf(VoiceTutorLessonFocus(42, 1)),
        )

        assertThat(result.map { it.id }).containsExactly(1, 2, 3)
    }

    private fun turn(
        id: Long,
        role: VoiceTutorTranscriptRole,
        text: String,
        revision: Long = 1,
        sequence: Long = id,
        studyQuestionTurnId: Long? = null,
        studyAnswerTurnId: Long? = null,
        isStudyQuestion: Boolean = false,
    ) = VoiceTutorTranscriptTurn(
        id, SESSION, "item-$id", role, text, sequence, now.plusSeconds(sequence), revision,
        studyQuestionTurnId, studyAnswerTurnId, false, isStudyQuestion,
    )

    private companion object {
        const val SESSION = "summary-evidence"
    }
}
