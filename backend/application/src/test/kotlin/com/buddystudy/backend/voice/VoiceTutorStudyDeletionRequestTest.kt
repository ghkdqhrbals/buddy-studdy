package com.buddystudy.backend.voice

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyEvidenceSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyDeletionRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContext
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateEvidence
import com.buddystudy.backend.voice.application.model.correlatedTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorStudyDeletionRequestTest {
    private val target = VoiceTutorStudyTargetCandidate(101, null, "Spring", difficulty = 5)
    private val context = VoiceTutorStudyMutationContext(
        0, null, listOf(target), VoiceTutorStudyMutationContextSource.OWNER_READ,
    )
    private val evidence = VoiceTutorStudyUpdateEvidence(
        VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
        command = "스프링 삭제해 줘",
        targetTopic = "스프링",
        topic = null,
        difficulty = null,
        targetImplicitCurrentFocus = false,
    )
    private val request = VoiceTutorStudyDeletionRequest(target.studyId, evidence)
    private val utterance = VoiceTutorInputUtterance(
        "item-1", evidence.command, studyMutationContext = context,
    )

    @Test
    fun `direct request retains exact learner evidence without requiring saved spelling or prior lesson selection`() {
        assertThat(request.isValidFor(utterance)).isTrue()
        assertThat(request.copy(studyId = 999).isValidFor(utterance)).isFalse()
        assertThat(request.isValidFor(utterance.copy(studyMutationContext = null))).isFalse()
        assertThat(request.copy(evidence = evidence.copy(command = "다른 주제 삭제해 줘"))
            .isValidFor(utterance)).isFalse()
    }

    @Test
    fun `deletion carries neither update patch nor implicit permission from metadata`() {
        assertThat(request.copy(evidence = evidence.copy(topic = "스프링")).isValidFor(utterance)).isFalse()
        assertThat(request.copy(evidence = evidence.copy(difficulty = "7")).isValidFor(utterance)).isFalse()
        assertThat(request.copy(evidence = evidence.copy(targetTopic = null)).isValidFor(utterance)).isFalse()
    }

    @Test
    fun `referential deletion requires an actual tutor referent or the current focus`() {
        val command = "그거 지워 줘"
        val referenced = request.copy(evidence = evidence.copy(
            command = command, targetTopic = null, targetImplicitSpokenOffer = true,
        ))
        val input = utterance.copy(transcript = command)
        assertThat(referenced.isValidFor(input)).isFalse()
        assertThat(referenced.isValidFor(input.copy(studyMutationContext = context.copy(
            referentTranscript = "스프링 주제가 저장돼 있어요.",
        )))).isTrue()

        val current = referenced.copy(evidence = referenced.evidence.copy(
            targetImplicitSpokenOffer = false, targetImplicitCurrentFocus = true,
        ))
        assertThat(current.isValidFor(input)).isFalse()
        assertThat(current.isValidFor(input.copy(studyMutationContext = context.copy(
            currentFocusStudyId = target.studyId,
        )))).isTrue()
    }

    @Test
    fun `deletion is a completed meaningful operation never an answer checkpoint or detached request`() {
        val decision = VoiceTutorInputItemAssessment(
            utterance.itemId, VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.DELETE_STUDY, deleteStudyRequest = request,
        )
        assertThat(VoiceTutorInputAssessmentResult(listOf(decision)).correlatedTo(listOf(utterance))
            .decisions.single().deleteStudyRequest).isEqualTo(request)
        listOf(
            decision.copy(intent = VoiceTutorInputIntent.NONE) to utterance,
            decision.copy(deleteStudyRequest = null) to utterance,
            decision.copy(decision = VoiceTutorInputDecision.NON_COMMUNICATIVE) to utterance,
            decision.copy(currentTranscriptAnswersStudyQuestion = true) to utterance,
            decision to utterance.copy(checkpoint = true),
        ).forEach { (invalidDecision, input) ->
            assertThat(runCatching {
                VoiceTutorInputAssessmentResult(listOf(invalidDecision)).correlatedTo(listOf(input))
            }.isFailure).isTrue()
        }
    }
}
