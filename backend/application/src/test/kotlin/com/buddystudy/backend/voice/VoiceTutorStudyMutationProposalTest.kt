package com.buddystudy.backend.voice

import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationProposal
import com.buddystudy.backend.voice.application.model.correlatedTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorStudyMutationProposalTest {
    private val proposal = VoiceTutorStudyMutationProposal(
        "proposal-1", VoiceTutorInputIntent.UPDATE_STUDY,
        targetStudyId = 101, targetTopic = "스프링", difficulty = 7,
        tutorAudioTranscript = "스프링을 레벨 7로 바꿀까요?",
    )

    @Test
    fun `one proposal freezes an exact valid action with bounded identity and actual spoken question`() {
        assertThat(proposal.isValid()).isTrue()
        val invalid = listOf(
            proposal.copy(proposalId = ""), proposal.copy(proposalId = "x".repeat(192)),
            proposal.copy(proposalId = "bad\nidentity"), proposal.copy(targetStudyId = 0),
            proposal.copy(targetTopic = ""), proposal.copy(difficulty = 11), proposal.copy(difficulty = null),
            proposal.copy(tutorAudioTranscript = ""), proposal.copy(tutorAudioTranscript = "x".repeat(4_001)),
            proposal.copy(intent = VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION),
            proposal.copy(intent = VoiceTutorInputIntent.DELETE_STUDY),
        )
        invalid.forEach { assertThat(it.isValid()).describedAs(it.toString()).isFalse() }
    }

    @Test
    fun `creation and deletion proposals keep exact structure and restrict compound lesson start`() {
        val root = VoiceTutorStudyMutationProposal("root", VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            topic = "스프링", difficulty = 7, tutorAudioTranscript = "스프링을 레벨 7 주제로 만들까요?",
            startLessonAfterMutation = true)
        val child = VoiceTutorStudyMutationProposal("child", VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
            parentStudyId = 101, parentTopic = "스프링", topic = "DI", difficulty = 5,
            tutorAudioTranscript = "스프링 아래 DI를 레벨 5로 만들까요?")
        val deletion = proposal.copy(intent = VoiceTutorInputIntent.DELETE_STUDY, difficulty = null,
            tutorAudioTranscript = "스프링 주제와 하위 주제를 삭제할까요?")
        assertThat(root.isValid()).isTrue()
        assertThat(child.isValid()).isTrue()
        assertThat(deletion.isValid()).isTrue()
        assertThat(root.copy(targetStudyId = 101).isValid()).isFalse()
        assertThat(child.copy(parentStudyId = null).isValid()).isFalse()
        assertThat(child.copy(startLessonAfterMutation = true).isValid()).isFalse()
        assertThat(deletion.copy(startLessonAfterMutation = true).isValid()).isFalse()
        assertThat(deletion.copy(topic = "새 이름").isValid()).isFalse()
    }

    @Test
    fun `confirmation and rejection correlate exact identity without recreating mutation fields`() {
        listOf(VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION, VoiceTutorInputIntent.REJECT_STUDY_MUTATION).forEach { intent ->
            val utterance = VoiceTutorInputUtterance("answer", "응", mutationProposal = proposal)
            val decision = VoiceTutorInputItemAssessment("answer", VoiceTutorInputDecision.MEANINGFUL,
                intent, mutationProposalId = proposal.proposalId)
            assertThat(VoiceTutorInputAssessmentResult(listOf(decision)).correlatedTo(listOf(utterance)).decisions)
                .containsExactly(decision)
            listOf(
                utterance.copy(checkpoint = true) to decision,
                utterance.copy(mutationProposal = null) to decision,
                utterance.copy(mutationProposal = proposal.copy(proposalId = "other")) to decision,
                utterance to decision.copy(mutationProposalId = null),
                utterance to decision.copy(intent = VoiceTutorInputIntent.NONE),
                utterance to decision.copy(decision = VoiceTutorInputDecision.NON_COMMUNICATIVE),
                utterance to decision.copy(currentTranscriptAnswersStudyQuestion = true),
                utterance to decision.copy(targetStudyId = 101),
            ).forEach { (input, outcome) ->
                assertThat(runCatching {
                    VoiceTutorInputAssessmentResult(listOf(outcome)).correlatedTo(listOf(input))
                }.exceptionOrNull()).isInstanceOf(VoiceTutorInputAssessmentException::class.java)
            }
        }
    }
}
