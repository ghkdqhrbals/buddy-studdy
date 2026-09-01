package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.Disposable
import reactor.core.publisher.BaseSubscriber
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Synthetic events only: no provider, credential, microphone or network use. */
class VoiceTutorMeaningfulInputRelayTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `acoustic commit and partial ASR never create a response or publish learner input`() = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.commit("learner-1")
        assertThat(f.responses()).hasSize(1)
        assertThat(f.provider("conversation.item.input_audio_transcription.delta", "item_id" to "learner-1", "delta" to "어"))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.actions).isEmpty()
        f.transcript("learner-1", "어, 준비됐어.")
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        assertThat(f.publications()).isEmpty()
    }

    @Test
    fun `accepted short reply preserves exact original and responds only after persistence`() = fixture().use { f ->
        f.utterance(1, "yes", "응")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.publications()).hasSize(1)
        val published = f.publications().single()
        assertThat(mapper.readTree(published.rawEvent).path("transcript").asText()).isEqualTo("응")
        assertThat(f.responses()).hasSize(1)
        f.controller.confirmInputPublished(published.itemId)
        assertThat(f.responses()).hasSize(2)
        f.controller.confirmInputPublished(published.itemId)
        f.transcript("yes", "응")
        f.commit("yes")
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `only an exact final persisted learner item exposes its server classified focus intent`() {
        fixture().use { f ->
            f.offerCandidate(1, "browse-topic", 101, null, null)
            f.utterance(2, "selected-topic", "Redis로 할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            val publication = f.publications().last()
            assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerIntent)
                .isEqualTo(VoiceTutorInputIntent.NONE)

            f.confirm(publication, persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerProviderItemId).isEqualTo("selected-topic")
            assertThat(boundary.latestAcceptedLearnerLessonRevision).isZero()
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            assertThat(boundary.latestAcceptedLearnerTargetStudyId).isEqualTo(101)
            assertThat(boundary.latestAcceptedLearnerTargetOfferId).isPositive()
            assertThat(boundary.latestAcceptedLearnerTargetTraversal)
                .isEqualTo(VoiceTutorStudyTargetTraversal(terminalLeafStudyId = 101))

            f.start(3)
            val superseded = f.controller.mutationDialogueBoundary()
            assertThat(superseded.latestAcceptedLearnerProviderItemId).isNull()
            assertThat(superseded.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
            // Noise/new speech does not rewrite the prior accepted acoustic evidence used by
            // unrelated confirmation flows; it only revokes the one-shot focus authority.
            assertThat(superseded.latestAcceptedLearnerSpeechStartedOrder)
                .isEqualTo(boundary.latestAcceptedLearnerSpeechStartedOrder)
        }

        fixture().use { f ->
            f.offerCandidate(1, "browse-child", 102, 101, 101)
            f.utterance(2, "not-persisted", "더 내려가자")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            val publication = f.publications().last()
            f.confirm(publication, persisted = false)
            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerProviderItemId).isNull()
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
        }

        fixture().use { f ->
            f.offerCandidate(1, "browse-checkpoint", 102, 101, 101)
            f.start(2)
            f.controller.fireContinuousSpeechDeadline()
            f.commit("checkpoint")
            f.transcript("checkpoint", "더 깊게 가자")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            assertThat(f.publications().none { it.itemId == "checkpoint" }).isTrue()
            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerProviderItemId).isNull()
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
        }
    }

    @Test
    fun `a newer speech generation prevents a late persistence ack from resurrecting focus authority`() =
        fixture().use { f ->
            f.offerCandidate(1, "browse-topic", 101, null, null)
            f.utterance(2, "old-selection", "Redis로 할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            val oldPublication = f.publications().last()

            f.start(3)
            f.confirm(oldPublication, persisted = true)

            val whileSpeaking = f.controller.mutationDialogueBoundary()
            assertThat(whileSpeaking.latestAcceptedLearnerProviderItemId).isNull()
            assertThat(whileSpeaking.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
            assertThat(whileSpeaking.latestAcceptedLearnerTargetStudyId).isNull()
            assertThat(whileSpeaking.focusAuthorization).isNull()

            f.stop(3)
            f.commit("later-noise")
            f.transcript("later-noise", "음… 어…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("later-noise")

            val afterNoise = f.controller.mutationDialogueBoundary()
            assertThat(afterNoise.latestAcceptedLearnerProviderItemId).isNull()
            assertThat(afterNoise.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
            assertThat(afterNoise.latestAcceptedLearnerTargetStudyId).isNull()
            assertThat(afterNoise.focusAuthorization).isNull()
        }

    @Test
    fun `combined feedback and navigation offer carries one exact tutor item to the MCP boundary`() {
        fixture(finishOpening = false).use { f ->
            f.provider("output_audio_buffer.started", "response_id" to "opening")
            f.provider("response.output_audio.delta", "response_id" to "opening", "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to "opening",
                "item_id" to "study-question-item",
                "content_index" to 0,
                "transcript" to "Redis에서 메모리를 확보하는 이유는 무엇인가요?",
            )
            f.openingDone()
            f.openingStopped()

            f.utterance(1, "study-answer-item", "메모리 공간을 확보하기 위해서예요")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            val answer = f.publications().single()
            f.confirm(answer, persisted = true)
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis cache")
            f.finishCurrentResponseWithCandidateReads(
                listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                )),
                spokenTranscript = "핵심을 잘 설명했어요. 다음으로 Redis cache를 공부해 볼까요?",
                spokenProviderItemId = "tutor-feedback-item",
            )

            f.utterance(2, "continue-item", "그 하위 주제로 더 들어가자")
            assertThat(f.assessments().last().teacherContext)
                .isEqualTo("핵심을 잘 설명했어요. 다음으로 Redis cache를 공부해 볼까요?")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            val continuation = f.publications().last()
            f.confirm(continuation, persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.precedingTutorFeedbackForStudyAnswer).isTrue()
            assertThat(boundary.precedingQuestionProviderItemId).isEqualTo("study-question-item")
            assertThat(boundary.precedingAnswerProviderItemId).isEqualTo("study-answer-item")
            assertThat(boundary.precedingTutorFeedbackProviderItemId).isEqualTo("tutor-feedback-item")
            assertThat(boundary.precedingTutorNavigationOfferProviderItemId).isEqualTo("tutor-feedback-item")
        }
    }

    @Test
    fun `separate completed feedback and later navigation offer remain distinct for fresh consent`() {
        fixture(finishOpening = false).use { f ->
            f.provider("output_audio_buffer.started", "response_id" to "opening")
            f.provider("response.output_audio.delta", "response_id" to "opening", "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to "opening",
                "item_id" to "study-question-item",
                "content_index" to 0,
                "transcript" to "Redis에서 메모리를 확보하는 이유는 무엇인가요?",
            )
            f.openingDone()
            f.openingStopped()

            f.utterance(1, "study-answer-item", "메모리 공간을 확보하기 위해서예요")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirm(f.publications().single(), persisted = true)
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis cache")
            f.finishCurrentSpokenResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
                spokenTranscript = "핵심을 정확히 설명했어요.",
                spokenProviderItemId = "tutor-feedback-item",
            )
            f.finishCurrentSpokenOffer(
                spokenTranscript = "다음으로 Redis cache를 공부해 볼까요?",
                spokenProviderItemId = "tutor-offer-item",
            )

            f.utterance(2, "continue-item", "응, 그 하위 주제로 가자")
            val assessment = f.assessments().last()
            assertThat(assessment.teacherContext).contains(
                "[completed answer feedback]",
                "핵심을 정확히 설명했어요.",
                "[latest navigation offer]",
                "다음으로 Redis cache를 공부해 볼까요?",
            )
            assertThat(assessment.utterances.single().targetOffer?.tutorAudioTranscript)
                .isEqualTo("다음으로 Redis cache를 공부해 볼까요?")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            f.confirm(f.publications().last(), persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.precedingTutorFeedbackForStudyAnswer).isTrue()
            assertThat(boundary.precedingQuestionProviderItemId).isEqualTo("study-question-item")
            assertThat(boundary.precedingAnswerProviderItemId).isEqualTo("study-answer-item")
            assertThat(boundary.precedingTutorFeedbackProviderItemId).isEqualTo("tutor-feedback-item")
            assertThat(boundary.precedingTutorNavigationOfferProviderItemId).isEqualTo("tutor-offer-item")
        }
    }

    @Test
    fun `learner speech after output stop but before response done keeps exact offer and feedback boundary`() {
        fixture(finishOpening = false).use { f ->
            f.provider("output_audio_buffer.started", "response_id" to "opening")
            f.provider("response.output_audio.delta", "response_id" to "opening", "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to "opening",
                "item_id" to "study-question-item",
                "content_index" to 0,
                "transcript" to "Redis의 메모리 회수 정책을 설명해 보세요.",
            )
            f.openingDone()
            f.openingStopped()

            f.utterance(1, "study-answer-item", "LRU는 오래 사용하지 않은 키를 제거합니다")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirm(f.publications().single(), persisted = true)
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis eviction")
            f.finishCurrentSpokenResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
                spokenTranscript = "LRU 설명이 정확해요.",
                spokenProviderItemId = "tutor-feedback-item",
            )

            f.finishOfferAfterStopWithCompletedLearnerSpeech(
                sequence = 2,
                spokenTranscript = "이제 Redis eviction으로 더 내려갈까요?",
                spokenProviderItemId = "tutor-offer-item",
            )
            assertThat(f.inputCommits()).hasSize(2)
            f.commit("continue-item")
            f.transcript("continue-item", "응, 내려가자")

            val assessment = f.assessments().last()
            assertThat(assessment.utterances.single().targetOffer?.candidates).containsExactly(child)
            assertThat(assessment.utterances.single().targetOffer?.tutorAudioTranscript)
                .isEqualTo("이제 Redis eviction으로 더 내려갈까요?")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            f.confirm(f.publications().last(), persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.precedingTutorFeedbackForStudyAnswer).isTrue()
            assertThat(boundary.precedingQuestionProviderItemId).isEqualTo("study-question-item")
            assertThat(boundary.precedingAnswerProviderItemId).isEqualTo("study-answer-item")
            assertThat(boundary.precedingTutorFeedbackProviderItemId).isEqualTo("tutor-feedback-item")
            assertThat(boundary.precedingTutorNavigationOfferProviderItemId).isEqualTo("tutor-offer-item")
        }
    }

    @Test
    fun `pre stop delayed speech and post stop speech share one fail closed commit without leaking slots`() {
        fixture(finishOpening = false).use { f ->
            f.provider("output_audio_buffer.started", "response_id" to "opening")
            f.provider("response.output_audio.delta", "response_id" to "opening", "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to "opening",
                "item_id" to "study-question-item",
                "content_index" to 0,
                "transcript" to "Redis의 메모리 회수 정책을 설명해 보세요.",
            )
            f.openingDone()
            f.openingStopped()

            f.utterance(1, "study-answer-item", "LRU는 오래 사용하지 않은 키를 제거합니다")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirm(f.publications().single(), persisted = true)
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis eviction")
            f.finishCurrentSpokenResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
                spokenTranscript = "LRU 설명이 정확해요.",
                spokenProviderItemId = "tutor-feedback-item",
            )

            f.finishOfferWithSpeechAcrossTutorStop(
                firstSequence = 2,
                secondSequence = 3,
                activeSequenceAtDone = 4,
                spokenTranscript = "이제 Redis eviction으로 더 내려갈까요?",
                spokenProviderItemId = "tutor-offer-item",
            )
            // The answer commit plus exactly one merged commit. Before the fix,
            // response.done either emitted a second commit for the same buffer
            // or overwrote the first delayed slot while sequence 4 was active.
            assertThat(f.inputCommits()).hasSize(2)
            val responsesBeforeInput = f.responses().size
            f.commit("mixed-boundary-item")
            f.transcript("mixed-boundary-item", "잠깐, 아니, 응 내려가자")

            val assessment = f.assessments().last()
            assertThat(assessment.utterances.single().targetOffer).isNull()
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            val publication = f.publications().last()
            f.confirm(publication, persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
            assertThat(boundary.latestAcceptedLearnerTargetStudyId).isNull()
            assertThat(boundary.latestAcceptedLearnerTargetOfferId).isNull()
            assertThat(boundary.precedingTutorFeedbackForStudyAnswer).isFalse()
            assertThat(boundary.precedingQuestionProviderItemId).isNull()
            assertThat(boundary.precedingAnswerProviderItemId).isNull()
            assertThat(boundary.precedingTutorFeedbackProviderItemId).isNull()
            assertThat(boundary.precedingTutorNavigationOfferProviderItemId).isNull()
            // One ACK settles all three reserved speech slots, so the normal
            // response gate cannot remain stuck on a phantom pending slot.
            assertThat(f.responses()).hasSize(responsesBeforeInput + 1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `an intervening spoken tutor turn cannot carry old feedback into a later offer`() {
        fixture(finishOpening = false).use { f ->
            f.provider("output_audio_buffer.started", "response_id" to "opening")
            f.provider("response.output_audio.delta", "response_id" to "opening", "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to "opening",
                "item_id" to "study-question-item",
                "content_index" to 0,
                "transcript" to "Redis 질문입니다.",
            )
            f.openingDone()
            f.openingStopped()
            f.utterance(1, "study-answer-item", "제 답변입니다")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirm(f.publications().single(), persisted = true)

            f.finishCurrentSpokenResponseWithCandidateRead(
                discovery = null,
                spokenTranscript = "답변 피드백입니다.",
                spokenProviderItemId = "tutor-feedback-item",
            )
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis cache")
            f.finishCurrentSpokenResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
                spokenTranscript = "그 전에 새 질문에 답해 보세요.",
                spokenProviderItemId = "unanswered-tutor-item",
            )
            f.finishCurrentSpokenOffer(
                spokenTranscript = "Redis cache로 내려갈까요?",
                spokenProviderItemId = "tutor-offer-item",
            )

            f.utterance(2, "continue-item", "응")
            assertThat(f.assessments().last().teacherContext)
                .doesNotContain("[completed answer feedback]", "답변 피드백입니다.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.CONTINUE_TREE)
            f.confirm(f.publications().last(), persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerTargetStudyId).isEqualTo(102)
            assertThat(boundary.precedingTutorFeedbackForStudyAnswer).isFalse()
            assertThat(boundary.precedingTutorFeedbackProviderItemId).isNull()
            assertThat(boundary.precedingTutorNavigationOfferProviderItemId).isNull()
        }
    }

    @Test
    fun `initial discovery follows a complete single child chain and offers only its verified endpoint`() = fixture().use { f ->
        f.utterance(1, "browse-chain", "Redis 주제로 이야기해 보자")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
        f.confirm(f.publications().last(), persisted = true)

        val root = VoiceTutorStudyTargetCandidate(101, null, "Redis")
        val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis eviction")
        f.finishCurrentResponseWithCandidateReads(
            listOf(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(root),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 3, 1),
                ),
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, emptyList(),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(102, 0, 3, 0),
                ),
            ),
            spokenTranscript = "Redis eviction 주제로 이야기해 볼까요?",
        )

        f.utterance(2, "select-endpoint", "응, 그 주제로 할게")
        val assessed = f.assessments().last().utterances.single()
        assertThat(assessed.targetOffer!!.candidates).containsExactly(child)
        assertThat(assessed.targetOffer!!.candidateTraversals).containsExactlyEntriesOf(mapOf(
            child.studyId to VoiceTutorStudyTargetTraversal(
                singleChildEdges = listOf(VoiceTutorStudyTargetSingleChildEdge(root.studyId, child.studyId)),
                terminalLeafStudyId = child.studyId,
            ),
        ))
        assertThat(assessed.targetOffer!!.tutorAudioTranscript)
            .isEqualTo("Redis eviction 주제로 이야기해 볼까요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
        f.confirm(f.publications().last(), persisted = true)

        val boundary = f.controller.mutationDialogueBoundary()
        assertThat(boundary.latestAcceptedLearnerTargetStudyId).isEqualTo(102)
        assertThat(boundary.latestAcceptedLearnerTargetTraversal).isEqualTo(
            VoiceTutorStudyTargetTraversal(
                singleChildEdges = listOf(VoiceTutorStudyTargetSingleChildEdge(root.studyId, child.studyId)),
                terminalLeafStudyId = child.studyId,
            ),
        )
    }

    @Test
    fun `root plus five descendant discovery can use all seven dependent tool rounds and offer the leaf`() =
        fixture().use { f ->
            f.utterance(1, "browse-deep-chain", "저장된 시스템 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)

            val nodes = (101L..106L).mapIndexed { index, id ->
                VoiceTutorStudyTargetCandidate(
                    studyId = id,
                    parentStudyId = if (index == 0) null else id - 1,
                    topic = "Saved topic $id",
                )
            }
            val discoveries = buildList {
                add(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(nodes.first()),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("systems", 0, 10, 1),
                ))
                nodes.zipWithNext().forEach { (parent, child) ->
                    add(VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(child),
                        VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(
                            parent.studyId, 0, 10, 1,
                        ),
                    ))
                }
                add(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, emptyList(),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(
                        nodes.last().studyId, 0, 10, 0,
                    ),
                ))
            }

            assertThat(discoveries).hasSize(VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS)
            f.finishCurrentResponseWithCandidateReads(
                discoveries,
                spokenTranscript = "Saved topic 106 주제로 공부해 볼까요?",
            )
            f.utterance(2, "choose-deep-leaf", "응, 그 주제로 할게")

            val offer = requireNotNull(f.assessments().last().utterances.single().targetOffer)
            assertThat(offer.candidates).containsExactly(nodes.last())
            assertThat(offer.candidateTraversals).containsExactlyEntriesOf(mapOf(
                nodes.last().studyId to VoiceTutorStudyTargetTraversal(
                    singleChildEdges = nodes.zipWithNext().map { (parent, child) ->
                        VoiceTutorStudyTargetSingleChildEdge(parent.studyId, child.studyId)
                    },
                    terminalLeafStudyId = nodes.last().studyId,
                ),
            ))
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `deep branch offers its verified first page within seven dependent rounds`() = fixture().use { f ->
        f.utterance(1, "browse-deep-branch", "저장된 시스템 주제의 하위 항목을 찾아줘")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
        f.confirm(f.publications().last(), persisted = true)

        val chain = (101L..106L).mapIndexed { index, id ->
            VoiceTutorStudyTargetCandidate(
                studyId = id,
                parentStudyId = if (index == 0) null else id - 1,
                topic = "Saved topic $id",
            )
        }
        val branch = (201L..210L).map { id ->
            VoiceTutorStudyTargetCandidate(id, chain.last().studyId, "Branch topic $id")
        }
        val discoveries = buildList {
            add(VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(chain.first()),
                VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("systems", 0, 10, 1),
            ))
            chain.zipWithNext().forEach { (parent, child) ->
                add(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(
                        parent.studyId, 0, 10, 1,
                    ),
                ))
            }
            add(VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, branch,
                VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(
                    chain.last().studyId, 0, 10, 17,
                ),
            ))
        }

        assertThat(discoveries).hasSize(VoiceTutorMcpTurnCoordinator.MAX_TOOL_ROUNDS)
        f.finishCurrentResponseWithCandidateReads(
            discoveries,
            spokenTranscript = "Branch topic 201과 Branch topic 202 중에서 골라 주세요.",
        )
        f.utterance(2, "choose-deep-branch", "첫 번째 주제로 할게")

        val offer = requireNotNull(f.assessments().last().utterances.single().targetOffer)
        assertThat(offer.candidates).containsExactlyElementsOf(branch)
        val expectedTraversal = VoiceTutorStudyTargetTraversal(
            singleChildEdges = chain.zipWithNext().map { (parent, child) ->
                VoiceTutorStudyTargetSingleChildEdge(parent.studyId, child.studyId)
            },
        )
        assertThat(offer.candidateTraversals.keys).containsExactlyElementsOf(branch.map { it.studyId })
        assertThat(offer.candidateTraversals.values).containsOnly(expectedTraversal)
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `verified first branch page offers immediately and contiguous pages extend the bounded window`() {
        val queryCandidates = (201L..211L).map {
            VoiceTutorStudyTargetCandidate(it, null, "Query topic $it")
        }
        fixture().use { f ->
            f.utterance(1, "browse-partial-query", "저장된 데이터베이스 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                discoveries = listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, queryCandidates.take(10),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 0, 10, 11),
                )),
                spokenTranscript = "Query topic 201 주제로 공부해 볼까요?",
            )
            f.utterance(2, "choose-from-partial-query", "응, 그걸로 할게")
            val offer = requireNotNull(f.assessments().last().utterances.single().targetOffer)
            assertThat(offer.candidates).containsExactlyElementsOf(queryCandidates.take(10))
            assertThat(offer.candidateTraversals.values)
                .containsOnly(VoiceTutorStudyTargetTraversal())
        }

        fixture().use { f ->
            f.utterance(1, "browse-complete-query", "저장된 데이터베이스 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                discoveries = listOf(
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, queryCandidates.take(10),
                        VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 0, 10, 11),
                    ),
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, queryCandidates.drop(10),
                        VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 10, 10, 11),
                    ),
                ),
                spokenTranscript = "Query topic 211 주제로 공부해 볼까요?",
            )
            f.utterance(2, "choose-from-complete-query", "마지막 주제로 할게")
            assertThat(requireNotNull(f.assessments().last().utterances.single().targetOffer).candidates)
                .containsExactlyElementsOf(queryCandidates)
        }

        val wideChildren = (301L..320L).map {
            VoiceTutorStudyTargetCandidate(it, 101L, "Child topic $it")
        }
        fixture().use { f ->
            f.utterance(1, "browse-wide-children", "다음 저장 주제를 보여줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                discoveries = listOf(
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, wideChildren.take(10),
                        VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 20),
                    ),
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, wideChildren.drop(10),
                        VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 10, 10, 20),
                    ),
                ),
                spokenTranscript = "Child topic 301 주제로 더 들어갈까요?",
            )
            f.utterance(2, "choose-wide-child", "첫 번째 주제로 갈게")
            assertThat(requireNotNull(f.assessments().last().utterances.single().targetOffer).candidates)
                .containsExactlyElementsOf(wideChildren.take(16))
        }

        fixture().use { f ->
            val laterChild = wideChildren.last()
            f.utterance(1, "narrow-later-child", "Child topic 320을 정확히 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                discoveries = listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, 101, listOf(laterChild),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Child topic 320", 0, 10, 1),
                )),
                spokenTranscript = "Child topic 320 주제로 더 들어갈까요?",
            )
            f.utterance(2, "choose-narrowed-child", "응, 그 주제로 갈게")
            assertThat(requireNotNull(f.assessments().last().utterances.single().targetOffer).candidates)
                .containsExactly(laterChild)
        }
    }

    @Test
    fun `a nonzero terminal page never proves a singleton or a single-child edge`() {
        val terminalQueryCandidate = VoiceTutorStudyTargetCandidate(211, null, "Query topic 211")
        fixture().use { f ->
            f.utterance(1, "browse-terminal-query-page", "저장된 데이터베이스 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(terminalQueryCandidate),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 10, 10, 11),
                )),
                spokenTranscript = "Query topic 211 주제로 공부해 볼까요?",
            )
            f.utterance(2, "choose-terminal-query-page", "응")
            assertThat(f.assessments().last().utterances.single().targetOffer).isNull()
        }

        val root = VoiceTutorStudyTargetCandidate(101, null, "Database")
        val terminalChild = VoiceTutorStudyTargetCandidate(211, root.studyId, "Child topic 211")
        fixture().use { f ->
            f.utterance(1, "browse-terminal-child-page", "저장된 데이터베이스 하위 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                listOf(
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(root),
                        VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 0, 10, 1),
                    ),
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(terminalChild),
                        VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(root.studyId, 10, 10, 11),
                    ),
                ),
                spokenTranscript = "Child topic 211 주제로 공부해 볼까요?",
            )
            f.utterance(2, "choose-terminal-child-page", "응")
            assertThat(f.assessments().last().utterances.single().targetOffer).isNull()
        }
    }

    @Test
    fun `a read candidate omitted from the spoken offer cannot authorize focus`() = fixture().use { f ->
        f.utterance(1, "browse-branch", "저장된 데이터베이스 주제를 찾아줘")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
        f.confirm(f.publications().last(), persisted = true)
        val redis = VoiceTutorStudyTargetCandidate(101, null, "Redis")
        val postgres = VoiceTutorStudyTargetCandidate(202, null, "PostgreSQL")
        f.finishCurrentResponseWithCandidateReads(
            listOf(VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(redis, postgres),
                VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("database", 0, 3, 2),
            )),
            spokenTranscript = "Redis 주제로 이야기해 볼까요?",
        )

        f.utterance(2, "choose-unspoken", "PostgreSQL로 할게")
        assertThat(f.assessments().last().utterances.single().targetOffer!!.candidates)
            .containsExactly(redis, postgres)
        f.assessTarget(
            VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
            targetStudyId = 202,
            spokenCandidateStudyIds = listOf(101),
        )

        assertThat(f.publications().none { it.itemId == "choose-unspoken" }).isTrue()
        assertThat(f.deletions().map { it.path("item_id").asText() }).contains("choose-unspoken")
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerTargetStudyId).isNull()
    }

    @Test
    fun `unscoped or partial discovery cannot mint a spoken focus offer`() {
        val redis = VoiceTutorStudyTargetCandidate(101, null, "Redis")
        val invalidDiscoveries = listOf(
            VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(redis),
                VoiceTutorCandidateDiscoveryScope.Unscoped,
            ),
            VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(redis),
                VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 3, 2),
            ),
        )

        for ((index, discovery) in invalidDiscoveries.withIndex()) fixture().use { f ->
            f.utterance(1, "browse-invalid-$index", "Redis를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateReads(
                listOf(discovery),
                spokenTranscript = "Redis 주제로 이야기해 볼까요?",
            )
            f.utterance(2, "choose-invalid-$index", "응, 그걸로 할게")
            assertThat(f.assessments().last().utterances.single().targetOffer).isNull()
            f.assessTarget(
                VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
                targetStudyId = 101,
                spokenCandidateStudyIds = listOf(101),
            )

            assertThat(f.publications().none { it.itemId == "choose-invalid-$index" }).isTrue()
            assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerTargetStudyId).isNull()
        }
    }

    @Test
    fun `spoken lesson end emits one server lifecycle request only after exact durable learner publication`() {
        fixture().use { f ->
            f.utterance(1, "not-persisted", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = false)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
        }

        fixture().use { f ->
            f.utterance(1, "persisted-end", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).containsExactly(VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)
            assertThat(f.responses()).hasSize(1)
            assertThat(f.controller.acceptsInputEvents()).isFalse()
            f.assertNoAudioDisruption()
        }

        fixture().use { f ->
            f.start(1)
            f.controller.fireContinuousSpeechDeadline()
            f.commit("incomplete-checkpoint")
            f.transcript("incomplete-checkpoint", "학습 끝낼게라고 말하면")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            assertThat(publication.checkpoint).isTrue()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(1)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
        }
    }

    @Test
    fun `spoken lesson end waits for the active tutor sentence to drain completely`() =
        fixture(finishOpening = false).use { f ->
            f.utterance(1, "persisted-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(1)
            f.start(1) // Duplicate/stale speech generation cannot retract a valid END.
            f.provider("output_audio_buffer.stopped", "response_id" to "wrong-response")
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.openingStopped()
            assertThat(f.serverLifecycleTypes()).containsExactly(VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)
            assertThat(f.responses()).hasSize(1)
            f.assertNoAudioDisruption()
        }

    @Test
    fun `a newer learner generation retracts an in flight or awaiting persistence spoken end`() {
        fixture().use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.start(2)
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val oldPublication = f.publications().single()
            f.controller.confirmInputPublished(oldPublication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.stop(2)
            f.commit("continue")
            f.transcript("continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

        fixture().use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val oldPublication = f.publications().single()
            f.start(2)
            f.controller.confirmInputPublished(oldPublication.itemId, persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.stop(2)
            f.commit("continue")
            f.transcript("continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            f.controller.confirmInputPublished("old-end", persisted = true)
            assertThat(f.serverLifecycleTypes()).isEmpty()

            // The END is already persisted, but the active tutor sentence has
            // not drained. A newer learner generation must still retract it.
            f.utterance(2, "continue", "아니, 계속할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.controller.confirmInputPublished("continue", persisted = true)
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }

        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.openingDone()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            f.controller.confirmInputPublished("old-end", persisted = true)
            f.utterance(2, "noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("noise")
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }
    }

    @Test
    fun `a mixed batch cannot revive an older end when the newer publication is acknowledged first`() =
        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.utterance(2, "continue", "아니, 계속할게")
            f.openingDone()
            val batch = f.assessments().single()
            f.controller.completeInputAssessment(
                batch.token,
                Result.success(VoiceTutorInputAssessmentResult(listOf(
                    VoiceTutorInputItemAssessment(
                        "old-end", VoiceTutorInputDecision.MEANINGFUL,
                        VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
                    ),
                    VoiceTutorInputItemAssessment("continue", VoiceTutorInputDecision.MEANINGFUL),
                ))),
            )
            assertThat(f.publications().map { it.itemId }).containsExactly("old-end", "continue")
            f.controller.confirmInputPublished("continue", persisted = true)
            f.controller.confirmInputPublished("old-end", persisted = true)
            f.openingStopped()

            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.assertNoAudioDisruption()
        }

    @Test
    fun `filler is removed from provider context without transcript or tutor reply`() = fixture().use { f ->
        f.utterance(1, "hesitation", "어… 음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        assertThat(f.publications()).isEmpty()
        assertThat(f.deletions().map { it.path("item_id").asText() }).containsExactly("hesitation")
        assertThat(f.responses()).hasSize(1)
        f.deleted("not-this-item")
        assertThat(f.responses()).hasSize(1)
        f.deleted("hesitation")
        assertThat(f.responses()).hasSize(1)
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `later real reply cannot bypass an unacknowledged filler deletion`() = fixture().use { f ->
        f.utterance(1, "filler", "음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.utterance(2, "question", "주제 알려 줘")
        assertThat(f.assessments()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.deleted("filler")
        assertThat(f.assessments()).hasSize(2)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `ASR arriving before its commit ACK waits for exact authenticated commit correlation`(): Unit = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.transcript("early", "아니")
        assertThat(f.assessments()).isEmpty()
        f.commit("early")
        assertThat(f.assessments().single().utterances.single().transcript).isEqualTo("아니")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
    }

    @Test
    fun `overlap waits for teacher sentence context and existing provider playback gates`() = fixture(finishOpening = false).use { f ->
        f.utterance(1, "learner", "어 준비됐지")
        assertThat(f.assessments()).isEmpty()
        f.openingDone()
        assertThat(f.assessments().single().teacherContext).isEqualTo("학습을 시작할 준비가 됐나요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.openingStopped()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `provider stop before done still needs contextual assessment and persistence`() = fixture(finishOpening = false).use { f ->
        f.utterance(1, "learner", "준비됐어")
        f.openingStopped()
        assertThat(f.assessments()).isEmpty()
        f.openingDone()
        assertThat(f.responses()).hasSize(1)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `next acoustic turn is not consumed by previous semantic completion`() = fixture().use { f ->
        f.utterance(1, "first", "준비됐어")
        f.start(2)
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.stop(2)
        f.commit("second")
        f.transcript("second", "Redis를 배우고 싶어")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `long acoustic activity alone checkpoints and cannot interrupt or trigger teaching`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        assertThat(f.inputCommits()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.commit("long-filler")
        f.transcript("long-filler", "어… 음…")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.deleted("long-filler")
        assertThat(f.responses()).hasSize(1)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `meaningful long speech stays listening after persisted checkpoints until its natural stop`(): Unit = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.commit("long-idea")
        f.transcript("long-idea", "Redis 캐시를 사용할 때 데이터가 바뀌면 무효화를 어떻게 할지 생각 중인데")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.now.addAndGet(Duration.ofSeconds(12).toNanos())
        f.controller.fireContinuousSpeechDeadline()
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("more-of-the-same-idea")
        f.transcript("more-of-the-same-idea", "변경이 잦으면 무효화 방식의 비용도 비교해 보고 싶어요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.stop(1)
        f.commit("final-tail")
        f.transcript("final-tail", "어느 쪽이 더 적절한가요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        assertThat(f.responses()).hasSize(1)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.responses().last().path("response").path("metadata").has(VoiceTutorRealtimeContract.TURN_METADATA_KEY)).isFalse()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `stop racing a checkpoint flushes one tail rather than two empty commits`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.controller.observeClientEvent(f.speech(false, 1))
        assertThat(f.inputCommits()).hasSize(1)
        f.now.addAndGet(Duration.ofMillis(250).toNanos())
        f.controller.flushDelayedStopCommit(1)
        f.controller.flushDelayedStopCommit(1)
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("checkpoint")
        f.commit("tail")
        f.transcript("checkpoint", "질문이 있어요")
        f.transcript("tail", "")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(1)
        f.deleted("tail")
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `only exact owned empty commit error releases its slot without ending the call`() = fixture().use { f ->
        f.start(1)
        f.stop(1)
        val commitId = f.inputCommits().single().path("event_id").asText()
        val unknown = f.emptyCommit("not-owned")
        assertThat(f.controller.observeProviderEvent(unknown)).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
        assertThat(f.controller.observeProviderEvent(f.emptyCommit(commitId))).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.responses()).hasSize(1)
        f.utterance(2, "retry", "질문 있어")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `rapid mute unmute stops within a checkpoint tail settle every coalesced speech slot`() = fixture().use { f ->
        f.start(1)
        f.controller.fireContinuousSpeechDeadline()
        f.commit("checkpoint")
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.controller.observeClientEvent(f.speech(false, 1))
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.start(2)
        f.now.addAndGet(Duration.ofMillis(10).toNanos())
        f.controller.observeClientEvent(f.speech(false, 2))
        f.now.set(Duration.ofMillis(250).toNanos())
        f.controller.flushDelayedStopCommit(1)
        f.controller.flushDelayedStopCommit(2)
        assertThat(f.inputCommits()).hasSize(2)
        f.commit("combined-tail")
        f.transcript("checkpoint", "Redis에 질문이 있어요")
        f.transcript("combined-tail", "캐시 무효화에 관해서요")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `expired assessment and terminal cleanup failure cannot start late paid work`(): Unit = fixture().use { f ->
        f.utterance(1, "learner", "저는 준비됐어요")
        val batch = f.assessments().single()
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        assertThat(f.controller.canAssessInput(batch.token)).isFalse()
        assertThat(f.deletions()).hasSize(1)
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.controller.acceptsInputEvents()).isFalse()
        assertThat(f.controller.canAssessInput(batch.token)).isFalse()
        f.controller.completeInputAssessment(batch.token, f.result(batch, VoiceTutorInputDecision.MEANINGFUL))
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
        assertThat(f.errors).hasSize(1)
    }

    @Test
    fun `assessment failure requests repeat and deletes unapproved audio instead of treating it as filler`() = fixture().use { f ->
        f.utterance(1, "learner", "저는 준비됐어요")
        val batch = f.assessments().single()
        f.controller.completeInputAssessment(
            batch.token,
            Result.failure(VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.TIMEOUT)),
        )
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.deletions()).hasSize(1)
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
        f.deleted("learner")
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `missing ASR has finite retry and cannot approve a late transcript`(): Unit = fixture().use { f ->
        f.start(1)
        f.stop(1)
        f.commit("missing-asr")
        f.now.addAndGet(Duration.ofSeconds(8).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Retry>()).hasSize(1)
        assertThat(f.deletions()).hasSize(1)
        f.transcript("missing-asr", "늦게 왔어요")
        assertThat(f.assessments()).isEmpty()
        f.deleted("missing-asr")
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `missing provider delete ACK fails finitely rather than hanging a silent call`(): Unit = fixture().use { f ->
        f.utterance(1, "filler", "음")
        f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
        f.now.addAndGet(Duration.ofSeconds(5).toNanos())
        f.controller.expirePendingInput()
        assertThat(f.errors).singleElement().isInstanceOf(VoiceTutorInputTurnCoordinatorException::class.java)
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `cleanup failure reaches lifecycle even when outbound controls have no demand`(): Unit = fixture(controlDemand = 2).use { f ->
        val observedFailure = AtomicReference<Throwable>()
        val failureArrived = CountDownLatch(1)
        val failureSubscription = f.controller.inputFailure().subscribe({}, {
            observedFailure.set(it)
            failureArrived.countDown()
        })
        try {
            // Two requested controls = opening response + this input commit.
            // The later delete remains queued behind downstream backpressure.
            f.utterance(1, "filler", "음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            assertThat(f.deletions()).isEmpty()
            f.now.addAndGet(Duration.ofSeconds(5).toNanos())
            f.controller.expirePendingInput()
            assertThat(failureArrived.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(observedFailure.get()).isInstanceOf(VoiceTutorInputTurnCoordinatorException::class.java)
            assertThat(f.controller.acceptsInputEvents()).isFalse()
            assertThat(f.controller.canPublishInput("filler")).isFalse()
            assertThat(f.responses()).hasSize(1)
        } finally {
            failureSubscription.dispose()
        }
    }

    @Test
    fun `terminal fencing drops unassessed late transcript and assessment callback`(): Unit = fixture().use { f ->
        f.utterance(1, "learner", "준비됐어")
        val batch = f.assessments().single()
        f.controller.close()
        f.controller.completeInputAssessment(batch.token, f.result(batch, VoiceTutorInputDecision.MEANINGFUL))
        assertThat(f.transcript("learner", "준비됐어")).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(f.publications()).isEmpty()
        assertThat(f.responses()).hasSize(1)
    }

    @Test
    fun `stale incomplete response does not fail the current teacher response`() = fixture(finishOpening = false).use { f ->
        assertThat(f.controller.observeProviderEvent(f.response("response.done", "stale", "wrong-token", "failed")))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.openingDone()
        f.openingStopped()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `async assessment leaves provider receive free and uses server context with persistence acknowledgement`() {
        val classifierEntered = CountDownLatch(1)
        val publishEntered = CountDownLatch(1)
        val classifierRelease = CompletableDeferred<Unit>()
        val persistenceRelease = CompletableDeferred<Unit>()
        val capturedRequest = AtomicReference<VoiceTutorInputAssessmentRequest>()
        val workerErrors = CopyOnWriteArrayList<Throwable>()
        fixture(finishOpening = false, captureInputActions = false).use { f ->
            val worker = voiceTutorInputAssessmentRelay(
                f.controller,
                userId = 42,
                language = "ko",
                assessment = object : VoiceTutorInputAssessmentUseCase {
                    override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
                        capturedRequest.set(request)
                        classifierEntered.countDown()
                        classifierRelease.await()
                        return VoiceTutorInputAssessmentResult(request.utterances.map {
                            VoiceTutorInputItemAssessment(it.itemId, VoiceTutorInputDecision.MEANINGFUL)
                        })
                    }
                },
            ) { raw, persist, forward ->
                assertThat(mapper.readTree(raw).path("transcript").asText()).isEqualTo("응")
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                publishEntered.countDown()
                persistenceRelease.await()
                true
            }.subscribe({}, workerErrors::add)
            try {
                f.utterance(1, "learner", "응")
                f.openingDone()
                assertThat(classifierEntered.await(2, TimeUnit.SECONDS)).isTrue()
                // This provider boundary must be processed while GPT is waiting.
                f.openingStopped()
                assertThat(f.responses()).hasSize(1)
                assertThat(capturedRequest.get().userId).isEqualTo(42)
                assertThat(capturedRequest.get().language).isEqualTo("ko")
                assertThat(capturedRequest.get().teacherContext).isEqualTo("학습을 시작할 준비가 됐나요?")
                classifierRelease.complete(Unit)
                assertThat(publishEntered.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(1)
                persistenceRelease.complete(Unit)
                assertThat(f.nextResponse.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(2)
                assertThat(workerErrors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
                classifierRelease.cancel()
                persistenceRelease.cancel()
            }
        }
    }

    @Test
    fun `the production input worker consumes a false persistence receipt without promoting consent or replaying input`(): Unit {
        val publishedItems = CopyOnWriteArrayList<String>()
        val workerErrors = CopyOnWriteArrayList<Throwable>()
        fixture(captureInputActions = false).use { f ->
            val worker = voiceTutorInputAssessmentRelay(
                f.controller, userId = 42, language = "ko",
                assessment = object : VoiceTutorInputAssessmentUseCase {
                    override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
                        VoiceTutorInputAssessmentResult(request.utterances.map {
                            VoiceTutorInputItemAssessment(it.itemId, VoiceTutorInputDecision.MEANINGFUL)
                        })
                },
            ) { raw, persist, forward ->
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                publishedItems += mapper.readTree(raw).path("item_id").asText()
                false // Full or already stored: handled, but no new durable USER evidence.
            }.subscribe({}, workerErrors::add)
            try {
                f.utterance(1, "handled-once", "학습에 관한 의미 있는 설명이에요.")
                assertThat(f.nextResponse.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(f.responses()).hasSize(2)
                val boundary = f.controller.mutationDialogueBoundary()
                assertThat(boundary.latestAcceptedLearnerSpeechStartedOrder).isZero()
                assertThat(boundary.precedingTutorSpeechStoppedOrder).isZero()
                assertThat(boundary.precedingSpokenResponseGeneration).isZero()

                f.transcript("handled-once", "중복 전사")
                f.commit("handled-once")
                f.controller.confirmInputPublished("handled-once", persisted = true)
                assertThat(publishedItems).containsExactly("handled-once")
                assertThat(f.responses()).hasSize(2)
                assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isZero()
                assertThat(f.controller.acceptsInputEvents()).isTrue()
                assertThat(workerErrors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }
    }

    private fun fixture(finishOpening: Boolean = true, captureInputActions: Boolean = true, controlDemand: Long = Long.MAX_VALUE) =
        Fixture(finishOpening, captureInputActions, controlDemand)

    private inner class Fixture(finishOpening: Boolean, captureInputActions: Boolean, controlDemand: Long) : AutoCloseable {
        val now = AtomicLong()
        val controls = CopyOnWriteArrayList<String>()
        val serverLifecycle = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val nextResponse = CountDownLatch(1)
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
            toolsEnabled = true,
        )
        private var syntheticToolSequence = 0L
        private val controlSubscription: Disposable
        private val serverLifecycleSubscription: Disposable
        private val workSubscription: Disposable?
        private val openingToken: String

        init {
            controlSubscription = controller.providerEvents().subscribeWith(object : BaseSubscriber<String>() {
                override fun hookOnSubscribe(subscription: Subscription) { request(controlDemand) }
                override fun hookOnNext(raw: String) {
                    controls += raw
                    if (responses().size > 1) nextResponse.countDown()
                }
                override fun hookOnError(throwable: Throwable) { errors += throwable }
            })
            serverLifecycleSubscription = controller.serverLifecycleEvents().subscribe(serverLifecycle::add, errors::add)
            workSubscription = if (captureInputActions) controller.inputActions().subscribe(actions::add, errors::add) else null
            controller.startOpeningResponse()
            openingToken = responses().single().path("event_id").asText()
            controller.observeProviderEvent(response("response.created", "opening", openingToken))
            if (finishOpening) {
                openingDone()
                openingStopped()
            }
        }

        fun openingDone() { controller.observeProviderEvent(response("response.done", "opening", openingToken)) }
        fun openingStopped() { provider("output_audio_buffer.stopped", "response_id" to "opening") }
        fun start(sequence: Long) { controller.observeClientEvent(speech(true, sequence)) }
        fun stop(sequence: Long) {
            now.addAndGet(Duration.ofSeconds(1).toNanos())
            controller.observeClientEvent(speech(false, sequence))
        }
        fun commit(id: String) { provider("input_audio_buffer.committed", "item_id" to id) }
        fun deleted(id: String) { provider("conversation.item.deleted", "item_id" to id) }
        fun utterance(sequence: Long, id: String, text: String) {
            start(sequence)
            stop(sequence)
            commit(id)
            transcript(id, text)
        }
        fun transcript(id: String, text: String) = provider(
            "conversation.item.input_audio_transcription.completed", "item_id" to id, "transcript" to text,
        )
        fun assessments() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()
        fun publications() = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Publish>()
        fun assess(
            decision: VoiceTutorInputDecision,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        ) {
            val batch = assessments().last()
            controller.completeInputAssessment(batch.token, result(batch, decision, intent))
        }
        fun result(
            batch: VoiceTutorInputTurnCoordinator.Action.Assess,
            decision: VoiceTutorInputDecision,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
        ) = Result.success(
            VoiceTutorInputAssessmentResult(batch.utterances.map {
                val target = if (intent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                    intent == VoiceTutorInputIntent.CONTINUE_TREE
                ) it.targetOffer?.candidates?.singleOrNull()?.studyId else null
                VoiceTutorInputItemAssessment(
                    it.itemId, decision, intent, target,
                    spokenCandidateStudyIds = target?.let(::listOf).orEmpty(),
                )
            }),
        )
        fun assessTarget(
            intent: VoiceTutorInputIntent,
            targetStudyId: Long,
            spokenCandidateStudyIds: List<Long>,
        ) {
            val batch = assessments().last()
            controller.completeInputAssessment(
                batch.token,
                Result.success(VoiceTutorInputAssessmentResult(batch.utterances.map {
                    VoiceTutorInputItemAssessment(
                        it.itemId,
                        VoiceTutorInputDecision.MEANINGFUL,
                        intent,
                        targetStudyId,
                        spokenCandidateStudyIds,
                    )
                })),
            )
        }
        fun confirm(publication: VoiceTutorInputTurnCoordinator.Action.Publish, persisted: Boolean) {
            controller.confirmInputPublished(
                publication.itemId,
                persisted,
            )
        }
        fun offerCandidate(
            sequence: Long,
            itemId: String,
            studyId: Long,
            parentStudyId: Long?,
            currentFocusStudyId: Long?,
        ) {
            utterance(sequence, itemId, "저장된 주제를 찾아줘")
            assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            confirm(publications().last(), persisted = true)
            finishCurrentResponseWithCandidateOffer(studyId, parentStudyId, currentFocusStudyId)
        }
        fun finishCurrentResponseWithCandidateOffer(
            studyId: Long,
            parentStudyId: Long?,
            currentFocusStudyId: Long?,
            spokenProviderItemId: String? = null,
        ) {
            val candidate = VoiceTutorStudyTargetCandidate(studyId, parentStudyId, "Redis")
            val discoveries = if (currentFocusStudyId == null) {
                listOf(
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(candidate),
                        VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 3, 1),
                    ),
                    VoiceTutorCandidateDiscovery(
                        VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, emptyList(),
                        VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(studyId, 0, 3, 0),
                    ),
                )
            } else {
                listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, currentFocusStudyId, listOf(candidate),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(currentFocusStudyId, 0, 3, 1),
                ))
            }
            finishCurrentResponseWithCandidateReads(
                discoveries,
                "Redis 주제로 이야기해 볼까요?",
                spokenProviderItemId,
            )
        }
        fun finishCurrentResponseWithCandidateReads(
            discoveries: List<VoiceTutorCandidateDiscovery>,
            spokenTranscript: String,
            spokenProviderItemId: String? = null,
        ) {
            discoveries.forEach { discovery ->
                finishCurrentResponseWithCandidateRead(discovery)
            }
            finishCurrentSpokenOffer(spokenTranscript, spokenProviderItemId)
        }
        fun finishCurrentSpokenResponseWithCandidateRead(
            discovery: VoiceTutorCandidateDiscovery?,
            spokenTranscript: String,
            spokenProviderItemId: String,
        ) {
            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val responseToken = responses().last().path("event_id").asText()
            val responseId = "spoken-tool-$suffix"
            val callId = "spoken-tool-call-$suffix"
            controller.observeProviderEvent(response("response.created", responseId, responseToken))
            provider("output_audio_buffer.started", "response_id" to responseId)
            provider("response.output_audio.delta", "response_id" to responseId, "delta" to "AA==")
            provider(
                "response.output_audio_transcript.done",
                "response_id" to responseId,
                "item_id" to spokenProviderItemId,
                "content_index" to 0,
                "transcript" to spokenTranscript,
            )
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                responseId,
                responseToken,
                listOf(
                    mapOf(
                        "type" to "message",
                        "role" to "assistant",
                        "content" to listOf(mapOf("transcript" to spokenTranscript)),
                    ),
                    mapOf(
                        "type" to "function_call",
                        "status" to "completed",
                        "call_id" to callId,
                        "name" to "list_studies",
                        "arguments" to "{\"limit\":3}",
                    ),
                ),
            ))
            provider("output_audio_buffer.stopped", "response_id" to responseId)
            assertThat(controller.beginToolExecution(callId)).isTrue()
            assertThat(controller.completeToolExecution(
                callId,
                VoiceTutorMcpToolResult(
                    output = "{\"studies\":[]}",
                    isError = false,
                    candidateDiscovery = discovery,
                ),
            )).isTrue()
            acknowledgeLatestToolOutput()
        }
        fun finishCurrentSpokenOffer(
            spokenTranscript: String,
            spokenProviderItemId: String? = null,
        ) {
            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val offerToken = responses().last().path("event_id").asText()
            val offerResponseId = "offer-$suffix"
            controller.observeProviderEvent(response("response.created", offerResponseId, offerToken))
            provider("output_audio_buffer.started", "response_id" to offerResponseId)
            provider("response.output_audio.delta", "response_id" to offerResponseId, "delta" to "AA==")
            provider(
                "response.output_audio_transcript.done",
                "response_id" to offerResponseId,
                "item_id" to (spokenProviderItemId ?: "offer-message-$suffix"),
                "content_index" to 0,
                "transcript" to spokenTranscript,
            )
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                offerResponseId,
                offerToken,
                listOf(mapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "content" to listOf(mapOf("transcript" to spokenTranscript)),
                )),
            ))
            provider("output_audio_buffer.stopped", "response_id" to offerResponseId)
        }
        fun finishOfferAfterStopWithCompletedLearnerSpeech(
            sequence: Long,
            spokenTranscript: String,
            spokenProviderItemId: String,
        ) {
            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val offerToken = responses().last().path("event_id").asText()
            val offerResponseId = "early-stop-offer-$suffix"
            controller.observeProviderEvent(response("response.created", offerResponseId, offerToken))
            provider("output_audio_buffer.started", "response_id" to offerResponseId)
            provider("response.output_audio.delta", "response_id" to offerResponseId, "delta" to "AA==")
            provider(
                "response.output_audio_transcript.done",
                "response_id" to offerResponseId,
                "item_id" to spokenProviderItemId,
                "content_index" to 0,
                "transcript" to spokenTranscript,
            )
            provider("output_audio_buffer.stopped", "response_id" to offerResponseId)
            start(sequence)
            stop(sequence)
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                offerResponseId,
                offerToken,
                listOf(mapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "content" to listOf(mapOf("transcript" to spokenTranscript)),
                )),
            ))
        }
        fun finishOfferWithSpeechAcrossTutorStop(
            firstSequence: Long,
            secondSequence: Long,
            activeSequenceAtDone: Long,
            spokenTranscript: String,
            spokenProviderItemId: String,
        ) {
            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val offerToken = responses().last().path("event_id").asText()
            val offerResponseId = "mixed-boundary-offer-$suffix"
            controller.observeProviderEvent(response("response.created", offerResponseId, offerToken))
            provider("output_audio_buffer.started", "response_id" to offerResponseId)
            provider("response.output_audio.delta", "response_id" to offerResponseId, "delta" to "AA==")
            provider(
                "response.output_audio_transcript.done",
                "response_id" to offerResponseId,
                "item_id" to spokenProviderItemId,
                "content_index" to 0,
                "transcript" to spokenTranscript,
            )

            // No fake time advance: the first stop remains within the real
            // commit-spacing window and is held in delayedStopCommit.
            start(firstSequence)
            controller.observeClientEvent(speech(false, firstSequence))
            provider("output_audio_buffer.stopped", "response_id" to offerResponseId)
            start(secondSequence)
            controller.observeClientEvent(speech(false, secondSequence))
            controller.flushDelayedStopCommit(firstSequence)
            assertThat(inputCommits()).hasSize(1)
            start(activeSequenceAtDone)
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                offerResponseId,
                offerToken,
                listOf(mapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "content" to listOf(mapOf("transcript" to spokenTranscript)),
                )),
            ))
            controller.flushDelayedStopCommit(firstSequence)
            assertThat(inputCommits()).hasSize(1)
            controller.observeClientEvent(speech(false, activeSequenceAtDone))
            now.addAndGet(Duration.ofMillis(250).toNanos())
            controller.flushDelayedStopCommit(firstSequence)
        }
        private fun finishCurrentResponseWithCandidateRead(discovery: VoiceTutorCandidateDiscovery) {
            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val toolResponseToken = responses().last().path("event_id").asText()
            val toolResponseId = "browse-$suffix"
            val callId = "browse-call-$suffix"
            controller.observeProviderEvent(response("response.created", toolResponseId, toolResponseToken))
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                toolResponseId,
                toolResponseToken,
                listOf(mapOf(
                    "type" to "function_call",
                    "status" to "completed",
                    "call_id" to callId,
                    "name" to "list_studies",
                    "arguments" to "{\"limit\":3}",
                )),
            ))
            assertThat(controller.beginToolExecution(callId)).isTrue()
            assertThat(controller.completeToolExecution(
                callId,
                VoiceTutorMcpToolResult(
                    output = "{\"studies\":[]}",
                    isError = false,
                    candidateDiscovery = discovery,
                ),
            )).isTrue()
            acknowledgeLatestToolOutput()
        }
        private fun acknowledgeLatestToolOutput() {
            val outputItem = controls.map(mapper::readTree)
                .last { it.path("type").asText() == "conversation.item.create" }
                .path("item").deepCopy<ObjectNode>()
            outputItem.put("status", "completed")
            provider("conversation.item.added", "item" to outputItem)
        }
        fun publishAll() { publications().forEach { controller.confirmInputPublished(it.itemId) } }
        fun responses() = controls.map(mapper::readTree).filter { it.path("type").asText() == "response.create" }
        fun serverLifecycleTypes() = serverLifecycle.map { mapper.readTree(it).path("type").asText() }
        fun deletions() = controls.map(mapper::readTree).filter { it.path("type").asText() == "conversation.item.delete" }
        fun inputCommits() = controls.map(mapper::readTree).filter { it.path("type").asText() == "input_audio_buffer.commit" }
        fun assertNoAudioDisruption() {
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .doesNotContain("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.clear", "conversation.item.truncate")
        }
        fun provider(type: String, vararg fields: Pair<String, Any>): VoiceTutorProviderRelayDisposition =
            controller.observeProviderEvent(mapper.writeValueAsString(linkedMapOf("type" to type, *fields)))
        fun speech(start: Boolean, sequence: Long): String = mapper.writeValueAsString(
            mapOf("type" to if (start) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
                "sequence" to sequence),
        )
        fun response(type: String, id: String, token: String, status: String = "completed"): String = mapper.writeValueAsString(
            mapOf("type" to type, "response" to mapOf(
                "id" to id, "status" to status,
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
                "output" to listOf(mapOf("role" to "assistant", "content" to listOf(mapOf("transcript" to "학습을 시작할 준비가 됐나요?")))),
            )),
        )
        fun responseWithOutput(
            type: String,
            id: String,
            token: String,
            output: List<Map<String, Any?>>,
        ): String = mapper.writeValueAsString(mapOf(
            "type" to type,
            "response" to mapOf(
                "id" to id,
                "status" to "completed",
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
                "output" to output,
            ),
        ))
        fun emptyCommit(eventId: String): String = mapper.writeValueAsString(mapOf(
            "type" to "error", "error" to mapOf("code" to "input_audio_buffer_commit_empty", "event_id" to eventId),
        ))
        override fun close() {
            controller.close()
            workSubscription?.dispose()
            controlSubscription.dispose()
            serverLifecycleSubscription.dispose()
        }
    }
}
