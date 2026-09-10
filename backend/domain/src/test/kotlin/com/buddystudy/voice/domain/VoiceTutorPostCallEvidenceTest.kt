package com.buddystudy.voice.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorPostCallEvidenceTest {
    private val focus = listOf(VoiceTutorLessonFocus(42, 1))
    private val source = listOf(
        turn(1, VoiceTutorTranscriptRole.TUTOR, "캐시와 DB의 역할은?"),
        turn(2, VoiceTutorTranscriptRole.USER, "캐시는 빠른 조회에 쓰고"),
        turn(3, VoiceTutorTranscriptRole.USER, "원본은 DB에 저장합니다."),
        turn(4, VoiceTutorTranscriptRole.TUTOR, "90점입니다. 역할을 잘 구분했어요."),
    )

    @Test
    fun `source bound complete exchange promotes only evidence and preserves original source`() {
        val evidence = evidence(source, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
        val projected = evidence.attestedTranscript("session", source.reversed(), focus)!!
        assertEquals(source.map { it.transcript }, projected.map { it.transcript })
        assertEquals(listOf(10L, 20L, 30L, 40L), projected.map { it.sequenceNumber })
        assertTrue(projected[0].isStudyQuestion)
        assertEquals(1L, projected[1].studyQuestionTurnId)
        assertEquals(1L, projected[2].studyQuestionTurnId)
        assertEquals(3L, projected[3].studyAnswerTurnId)
        assertTrue(source.all { !it.isStudyQuestion && it.studyQuestionTurnId == null && it.studyAnswerTurnId == null })
    }

    @Test
    fun `structured source comes from reserved identity while original text remains unchanged`() {
        val audio = source[1].copy(transcript = "[Structured input] is only quoted speech here")
        val selected = audio.copy(providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "selection")

        assertEquals(VoiceTutorTranscriptSource.AUDIO, audio.source)
        assertEquals(VoiceTutorTranscriptSource.STRUCTURED_INPUT, selected.source)
        assertEquals(audio.transcript, selected.transcript)
        assertEquals(audio.id, selected.id)
    }

    @Test
    fun `structured selection cannot be promoted to a spoken answer even when model cites it`() {
        val mixed = source.map { if (it.id == 2L) it.copy(
            providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "selection",
        ) else it }

        assertNull(evidence(mixed, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
            .attestedTranscript("session", mixed, focus))
        val setupOnly = VoiceTutorPostCallEvidence(
            VoiceTutorPostCallEvidence.sourceHash("session", mixed, focus), emptyList(),
        )
        assertEquals(mixed, setupOnly.attestedTranscript("session", mixed, focus))
        assertTrue(mixed.all { it.studyQuestionTurnId == null })

        val followup = source + listOf(
            turn(5, VoiceTutorTranscriptRole.USER, "Selected: cache invalidation").copy(
                providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "followup",
            ),
            turn(6, VoiceTutorTranscriptRole.TUTOR, "A selection acknowledgement"),
        )
        assertNull(evidence(followup, VoiceTutorPostCallExchange(1, listOf(2, 3), 4)).copy(
            learnerQuestions = listOf(VoiceTutorPostCallLearnerQuestion(5, listOf(6))),
        ).attestedTranscript("session", followup, focus))
    }

    @Test
    fun `partial reordered duplicated or foreign answer IDs are rejected`() {
        listOf(listOf(2L), listOf(3L), listOf(3L, 2L), listOf(2L, 2L), listOf(2L, 99L)).forEach {
            assertNull(evidence(source, VoiceTutorPostCallExchange(1, it, 4))
                .attestedTranscript("session", source, focus))
        }
    }

    @Test
    fun `changed source fields or focus invalidate a pending verdict`() {
        val evidence = evidence(source, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
        val changed = listOf(
            source.map { if (it.id == 2L) it.copy(transcript = "변경") else it },
            source.map { if (it.id == 2L) it.copy(providerItemId = "different") else it },
            source.map { if (it.id == 2L) it.copy(lessonRevision = 2) else it },
            source.map { if (it.id == 2L) it.copy(sequenceNumber = 31) else it },
            source.map { if (it.id == 4L) it.copy(interrupted = true) else it },
            source.dropLast(1),
        )
        changed.forEach { assertNull(evidence.attestedTranscript("session", it, focus)) }
        assertNull(evidence.attestedTranscript("session", source, listOf(VoiceTutorLessonFocus(99, 1))))
        assertNull(evidence.attestedTranscript("foreign", source, focus))
    }

    @Test
    fun `later focus cannot retroactively turn navigation into learning`() {
        val navigation = source.map { it.copy(lessonRevision = 0) }
        assertNull(evidence(navigation, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
            .attestedTranscript("session", navigation, focus))
    }

    @Test
    fun `new epoch cannot reassign an old question answer or feedback`() {
        listOf(2L, 3L, 4L).forEach { id ->
            val revised = source.map { if (it.id == id) it.copy(lessonRevision = 2) else it }
            assertNull(evidence(revised, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
                .attestedTranscript("session", revised, focus))
        }
    }

    @Test
    fun `legacy raw provenance and duplicate source identities cannot be promoted`() {
        val legacy = source.map { it.copy(postCallEvidence = false) }
        val duplicate = source + source.last().copy(id = 5, providerItemId = "different")
        val preAttested = source.map { if (it.id == 1L) it.copy(isStudyQuestion = true) else it }
        listOf(legacy, duplicate, preAttested).forEach {
            assertNull(evidence(it, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
                .attestedTranscript("session", it, focus))
        }
    }

    @Test
    fun `empty semantic verdict changes nothing and learner followup alone is not learning`() {
        val empty = evidence(source)
        assertEquals(source, empty.attestedTranscript("session", source, focus))
        assertNull(empty.copy(learnerQuestions = listOf(VoiceTutorPostCallLearnerQuestion(3, listOf(4))))
            .attestedTranscript("session", source, focus))
    }

    @Test
    fun `archive marker changes invalidate even an empty pending verdict`() {
        val pending = evidence(source)
        val changed = source.map { if (it.id == 4L) it.copy(interrupted = true) else it }

        assertNull(pending.attestedTranscript("session", changed, focus))
        assertEquals(changed, evidence(changed).attestedTranscript("session", changed, focus))
    }

    @Test
    fun `interrupted questions and feedback cannot become attested evidence even with a raw source flag`() {
        listOf(1L, 4L).forEach { interruptedId ->
            val archived = source.map { if (it.id == interruptedId) it.copy(interrupted = true) else it }
            assertNull(evidence(archived, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
                .attestedTranscript("session", archived, focus))
        }
    }

    @Test
    fun `interrupted tutor remains in ordered history and cannot be skipped in a learner answer run`() {
        val archived = turn(6, VoiceTutorTranscriptRole.TUTOR, "쓰기 뒤 캐시를").copy(
            interrupted = true, postCallEvidence = false,
        )
        val transcript = source + listOf(
            turn(5, VoiceTutorTranscriptRole.USER, "무효화는 어떻게 하나요?"),
            archived,
            turn(7, VoiceTutorTranscriptRole.TUTOR, "다른 주제를 선택할까요?"),
        )
        val base = evidence(transcript, VoiceTutorPostCallExchange(1, listOf(2, 3), 4))
        val projected = base.attestedTranscript("session", transcript, focus)!!
        assertEquals(archived, projected.single { it.id == archived.id })
        assertEquals(transcript.map { it.sequenceNumber }, projected.map { it.sequenceNumber })
        listOf(listOf(6L), listOf(7L), listOf(6L, 7L)).forEach { ids ->
            assertNull(base.copy(learnerQuestions = listOf(VoiceTutorPostCallLearnerQuestion(5, ids)))
                .attestedTranscript("session", transcript, focus))
        }
    }

    @Test
    fun `valid learner followup supplements actual Q and A without grading it`() {
        val transcript = source + listOf(
            turn(5, VoiceTutorTranscriptRole.USER, "무효화는 어떻게 하나요?"),
            turn(6, VoiceTutorTranscriptRole.TUTOR, "쓰기 뒤 캐시를 지울 수 있어요."),
        )
        val evidence = evidence(transcript, VoiceTutorPostCallExchange(1, listOf(2, 3), 4)).copy(
            learnerQuestions = listOf(VoiceTutorPostCallLearnerQuestion(5, listOf(6))),
        )
        val result = evidence.attestedTranscript("session", transcript, focus)!!
        assertTrue(result[4].askedStudyQuestion)
        assertNull(result[4].studyQuestionTurnId)
        assertNull(result[5].studyAnswerTurnId)
    }

    private fun evidence(source: List<VoiceTutorTranscriptTurn>, vararg exchanges: VoiceTutorPostCallExchange) =
        VoiceTutorPostCallEvidence(VoiceTutorPostCallEvidence.sourceHash("session", source, focus), exchanges.toList())

    private fun turn(id: Long, role: VoiceTutorTranscriptRole, text: String) = VoiceTutorTranscriptTurn(
        id, "session", "item-$id", role, text, id * 10, Instant.EPOCH.plusSeconds(id),
        lessonRevision = 1, postCallEvidence = true,
    )
}
