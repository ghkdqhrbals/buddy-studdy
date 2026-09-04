package com.buddystudy.backend.voice

import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyEvidenceSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateEvidence
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorStudyUpdateRequestTest {
    private val focus = VoiceTutorStudyTargetCandidate(101, null, "Redis", difficulty = 5)
    private val offered = VoiceTutorStudyTargetCandidate(202, null, "Spring", difficulty = 7)
    private val context = VoiceTutorStudyMutationContext(
        lessonRevision = 4,
        currentFocusStudyId = focus.studyId,
        candidates = listOf(focus, offered),
    )

    @Test
    fun `owner read permits independently assessed ASR aliases without lesson traversal or formal offers`() {
        val command = "스프링 레벨을 7로 바꿔 줘"
        val request = updateRequest(offered).copy(
            topic = null, difficulty = 7,
            evidence = VoiceTutorStudyUpdateEvidence(
                VoiceTutorRootStudyEvidenceSource.TRANSCRIPT, command,
                targetTopic = "스프링", topic = null, difficulty = "7", targetImplicitCurrentFocus = false,
            ),
        )
        val input = utterance(null, command).copy(studyMutationContext = context.copy(
            currentFocusStudyId = null,
            source = VoiceTutorStudyMutationContextSource.OWNER_READ,
        ))

        assertThat(request.isValidFor(input)).isTrue()
        assertThat(request.copy(studyId = 999).isValidFor(input)).isFalse()
    }

    @Test
    fun `owner read resolves natural reference with actual speech without exposing a formal lesson offer`() {
        val command = "그걸 레벨 칠로 바꿔 줘"
        val request = updateRequest(offered).copy(
            topic = null, difficulty = 7,
            evidence = VoiceTutorStudyUpdateEvidence(
                VoiceTutorRootStudyEvidenceSource.TRANSCRIPT, command,
                targetTopic = null, topic = null, difficulty = "칠", targetImplicitCurrentFocus = false,
                targetImplicitSpokenOffer = true,
            ),
        )
        val readContext = context.copy(
            currentFocusStudyId = null,
            source = VoiceTutorStudyMutationContextSource.OWNER_READ,
        )
        val input = utterance(null, command).copy(studyMutationContext = readContext)
        assertThat(request.isValidFor(input)).isFalse()
        assertThat(request.isValidFor(input.copy(studyMutationContext = readContext.copy(
            referentTranscript = "저장된 스프링 주제는 레벨 5입니다.",
        )))).isTrue()
    }

    @Test
    fun `off-focus explicit update requires the exact spoken offer even while another study is focused`() {
        val request = updateRequest(offered)
        val exactOffer = targetOffer()

        assertThat(request.isValidFor(utterance(targetOffer = null))).isFalse()
        assertThat(request.isValidFor(utterance(targetOffer = exactOffer.copy(lessonRevision = 3)))).isFalse()
        assertThat(request.isValidFor(utterance(targetOffer = exactOffer.copy(currentFocusStudyId = null)))).isFalse()
        assertThat(request.isValidFor(utterance(targetOffer = exactOffer.copy(
            candidates = listOf(offered.copy(parentStudyId = 999)),
        )))).isFalse()
        assertThat(request.isValidFor(utterance(targetOffer = exactOffer.copy(
            tutorAudioTranscript = "다른 저장 주제를 바꿀 수 있어요.",
        )))).isFalse()
        assertThat(request.isValidFor(utterance(targetOffer = exactOffer))).isTrue()
    }

    @Test
    fun `explicit and implicit updates of the current focus preserve existing authorization without an offer`() {
        val explicit = updateRequest(focus)
        val implicit = explicit.copy(evidence = explicit.evidence.copy(
            targetTopic = null,
            targetImplicitCurrentFocus = true,
        ))

        assertThat(explicit.isValidFor(utterance(targetOffer = null, transcript = explicit.evidence.command))).isTrue()
        assertThat(implicit.isValidFor(utterance(targetOffer = null, transcript = implicit.evidence.command))).isTrue()
    }

    @Test
    fun `complete initial owner snapshot authorizes one exact explicitly named target only`() {
        val initial = context.copy(
            currentFocusStudyId = null,
            source = VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT,
        )
        val request = updateRequest(offered)
        val exact = utterance(targetOffer = null, transcript = request.evidence.command).copy(
            studyMutationContext = initial,
        )

        assertThat(request.isValidFor(exact)).isTrue()
        assertThat(request.copy(studyId = focus.studyId).isValidFor(exact)).isFalse()
        assertThat(request.copy(evidence = request.evidence.copy(
            targetTopic = null,
            targetImplicitSpokenOffer = true,
        )).isValidFor(exact)).isFalse()
        assertThat(request.isValidFor(exact.copy(
            studyMutationContext = initial.copy(candidates = initial.candidates + offered.copy(studyId = 303)),
        ))).isFalse()
    }

    @Test
    fun `natural anaphora can update only the single exact candidate just spoken by tutor`() {
        val command = "그 이름을 스프링 심화로 바꿔 줘"
        val request = updateRequest(offered).copy(
            topic = "스프링 심화",
            evidence = VoiceTutorStudyUpdateEvidence(
                source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
                command = command,
                targetTopic = null,
                topic = "스프링 심화",
                difficulty = null,
                targetImplicitCurrentFocus = false,
                targetImplicitSpokenOffer = true,
            ),
        )
        val exactOffer = targetOffer()

        assertThat(request.isValidFor(utterance(exactOffer, command))).isTrue()
        assertThat(request.isValidFor(utterance(exactOffer.copy(
            candidates = listOf(offered, focus),
        ), command))).isFalse()
        assertThat(request.isValidFor(utterance(exactOffer.copy(
            tutorAudioTranscript = "저장된 다른 주제 이름을 바꿀 수 있어요.",
        ), command))).isFalse()
    }

    private fun updateRequest(target: VoiceTutorStudyTargetCandidate): VoiceTutorStudyUpdateRequest {
        val command = "${target.topic} 주제 이름을 ${target.topic} 심화로 바꿔 줘"
        return VoiceTutorStudyUpdateRequest(
            studyId = target.studyId,
            topic = "${target.topic} 심화",
            difficulty = null,
            evidence = VoiceTutorStudyUpdateEvidence(
                source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
                command = command,
                targetTopic = target.topic,
                topic = "${target.topic} 심화",
                difficulty = null,
                targetImplicitCurrentFocus = false,
            ),
        )
    }

    private fun utterance(
        targetOffer: VoiceTutorStudyTargetOffer?,
        transcript: String = updateRequest(offered).evidence.command,
    ) = VoiceTutorInputUtterance(
        itemId = "item-1",
        transcript = transcript,
        targetOffer = targetOffer,
        studyMutationContext = context,
    )

    private fun targetOffer() = VoiceTutorStudyTargetOffer(
        offerId = 11,
        lessonRevision = context.lessonRevision,
        tutorResponseGeneration = 9,
        tutorSpeechStoppedOrder = 13,
        currentFocusStudyId = context.currentFocusStudyId,
        candidates = listOf(offered),
        tutorAudioTranscript = "저장된 Spring 주제도 이름을 바꿀 수 있어요.",
        candidateTraversals = mapOf(offered.studyId to VoiceTutorStudyTargetTraversal()),
    )
}
