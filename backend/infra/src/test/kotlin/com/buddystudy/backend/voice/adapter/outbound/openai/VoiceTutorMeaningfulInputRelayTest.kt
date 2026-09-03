package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorizationPurpose
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationEvidence
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyEvidenceSource
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationEvidence
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateEvidence
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorizationScope
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateTargetProof
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.Disposable
import reactor.core.publisher.BaseSubscriber
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
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
            val focusLease = requireNotNull(boundary.focusAuthorization)

            f.start(3)
            val duringRawSpeech = f.controller.mutationDialogueBoundary()
            assertThat(duringRawSpeech.latestAcceptedLearnerProviderItemId).isEqualTo("selected-topic")
            assertThat(duringRawSpeech.latestAcceptedLearnerIntent)
                .isEqualTo(VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            assertThat(duringRawSpeech.focusAuthorization).isSameAs(focusLease)
            assertThat(focusLease.isActive()).isTrue()
            // Noise/new speech does not rewrite prior persisted intent or acoustic evidence.
            assertThat(duringRawSpeech.latestAcceptedLearnerSpeechStartedOrder)
                .isEqualTo(boundary.latestAcceptedLearnerSpeechStartedOrder)

            f.stop(3)
            f.commit("handled-but-not-persisted")
            f.transcript("handled-but-not-persisted", "아니, 잠깐 기다려 봐.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().last(), persisted = false)

            // A handled-but-not-durable final item cannot revoke the prior exact
            // mutation binding or its one-shot lease.
            val afterRejectedPersistence = f.controller.mutationDialogueBoundary()
            assertThat(afterRejectedPersistence.latestAcceptedLearnerProviderItemId)
                .isEqualTo("selected-topic")
            assertThat(afterRejectedPersistence.latestAcceptedLearnerIntent)
                .isEqualTo(VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            assertThat(afterRejectedPersistence.focusAuthorization).isSameAs(focusLease)
            assertThat(focusLease.isActive()).isTrue()
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
    fun `persisted root creation intent reaches mutation boundary without inventing a study target`() =
        fixture().use { f ->
            f.utterance(1, "create-root", "운영체제를 새 루트로 만들어 줘")
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("운영체제", 5, "운영체제를 새 루트로 만들어 줘", omitted = true),
            )
            val publication = f.publications().single()

            assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerIntent)
                .isEqualTo(VoiceTutorInputIntent.NONE)
            f.confirm(publication, persisted = true)

            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerProviderItemId).isEqualTo("create-root")
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.CREATE_ROOT_STUDY)
            assertThat(boundary.latestAcceptedLearnerTargetStudyId).isNull()
            assertThat(boundary.latestAcceptedLearnerTargetOfferId).isNull()
            assertThat(boundary.latestAcceptedLearnerTargetCandidate).isNull()
            assertThat(boundary.focusAuthorization).isNull()
            assertThat(boundary.rootStudyCreationAuthorization?.topic).isEqualTo("운영체제")
            assertThat(boundary.rootStudyCreationAuthorization?.difficulty).isEqualTo(5)
            assertThat(boundary.rootStudyCreationAuthorization?.isActive()).isTrue()
            val rootLease = requireNotNull(boundary.rootStudyCreationAuthorization)
            val createCall = f.awaitServerToolCall("create_root_study")
            val createCallId = createCall.path("item").path("call_id").asText()

            // ACK dispatches the exact call before any acoustic activity.
            f.acknowledgeConversationItem(createCall)
            f.start(2)
            val duringRawSpeech = f.controller.mutationDialogueBoundary()
            assertThat(duringRawSpeech.latestAcceptedLearnerProviderItemId).isEqualTo("create-root")
            assertThat(duringRawSpeech.latestAcceptedLearnerIntent)
                .isEqualTo(VoiceTutorInputIntent.CREATE_ROOT_STUDY)
            assertThat(duringRawSpeech.rootStudyCreationAuthorization).isSameAs(rootLease)
            assertThat(rootLease.isActive()).isTrue()

            // begin is the synchronized semantic fence: speech that arrived
            // after ACK puts the already-dispatched action back on hold.
            assertThat(f.controller.beginToolExecution(createCallId)).isFalse()
            f.stop(2)
            f.commit("root-lease-noise")
            f.transcript("root-lease-noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("root-lease-noise")

            assertThat(f.controller.beginToolExecution(createCallId)).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(createCallId).rootStudyCreationAuthorization)
                .isSameAs(rootLease)
            assertThat(rootLease.isActive()).isTrue()
            assertThat(rootLease.consume()).isTrue()
            assertThat(rootLease.consume()).isFalse()
        }

    @Test
    fun `model focus execution claim is ordered against semantic learner input`() {
        fun prepare(
            f: Fixture,
            callId: String,
        ): Pair<VoiceTutorFocusAuthorization, VoiceTutorStudyTargetOffer> {
            f.offerCandidate(1, "browse-$callId", 101, null, null)
            f.utterance(2, "select-$callId", "Redis로 할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            val lease = requireNotNull(f.controller.mutationDialogueBoundary().focusAuthorization)
            val offer = f.latestFocusTargetOfferForRace()
            f.dispatchModelToolCall(callId, "select_voice_study", "{\"study_id\":101}")
            return lease to offer
        }

        fixture().use { f ->
            val callId = "model-focus-after-noise"
            val (lease, _) = prepare(f, callId)

            // The call was already dispatched. A later raw VAD edge moves it
            // back to the semantic hold before coordinator begin can claim it.
            f.start(3)
            assertThat(f.controller.beginToolExecution(callId)).isFalse()
            f.stop(3)
            f.commit("model-focus-noise")
            f.transcript("model-focus-noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("model-focus-noise")

            assertThat(f.controller.beginToolExecution(callId)).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(callId).focusAuthorization).isSameAs(lease)
            assertThat(lease.consume()).isTrue()
            assertThat(lease.consume()).isFalse()
            assertThat(f.controller.completeToolExecution(
                callId,
                VoiceTutorMcpToolResult("{\"error\":{\"code\":\"TEST_DONE\"}}", isError = true),
            )).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(callId).focusAuthorization).isNull()
        }

        fixture().use { f ->
            val callId = "stale-model-focus-same-target"
            val (oldLease, exactOffer) = prepare(f, callId)

            f.start(3)
            assertThat(f.controller.beginToolExecution(callId)).isFalse()
            // Reuse the exact same saved target to prove target mismatch is not
            // what protects the new ordinary lease from this stale call.
            f.restoreTargetOfferToActiveSpeechForRace(exactOffer)
            f.stop(3)
            f.commit("newer-same-target-choice")
            f.transcript("newer-same-target-choice", "Redis로 할게")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            val newLease = requireNotNull(f.controller.mutationDialogueBoundary().focusAuthorization)
            assertThat(newLease).isNotSameAs(oldLease)
            assertThat(oldLease.isActive()).isFalse()

            // The stale action still completes with an error output, but its
            // dispatch snapshot cannot dynamically acquire the newer lease.
            assertThat(f.controller.beginToolExecution(callId)).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(callId).focusAuthorization).isNull()
            assertThat(newLease.isActive()).isTrue()
            assertThat(f.controller.completeToolExecution(
                callId,
                VoiceTutorMcpToolResult("{\"error\":{\"code\":\"LEARNER_CHOICE_REQUIRED\"}}", isError = true),
            )).isTrue()
            assertThat(newLease.isActive()).isTrue()
        }

        fixture().use { f ->
            val callId = "model-focus-claim-wins"
            val (lease, _) = prepare(f, callId)
            assertThat(f.controller.beginToolExecution(callId)).isTrue()
            val claimedBoundary = f.controller.mutationDialogueBoundary(callId)

            f.start(3)
            f.stop(3)
            f.commit("meaningful-after-focus-claim")
            f.transcript("meaningful-after-focus-claim", "잠깐, 다른 얘기부터 할게.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().last(), persisted = true)

            assertThat(f.controller.mutationDialogueBoundary().focusAuthorization).isNull()
            assertThat(f.controller.mutationDialogueBoundary(callId)).isSameAs(claimedBoundary)
            assertThat(claimedBoundary.focusAuthorization).isSameAs(lease)
            assertThat(lease.isActive()).isTrue()
            assertThat(lease.consume()).isTrue()
            assertThat(lease.consume()).isFalse()
            assertThat(f.controller.completeToolExecution(
                callId,
                VoiceTutorMcpToolResult("{\"error\":{\"code\":\"TEST_DONE\"}}", isError = true),
            )).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(callId).focusAuthorization).isNull()
        }
    }

    @Test
    fun `natural new study intent executes one exact server root call before readback and spoken start consent`() =
        fixture().use { f ->
            val invocations = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
            val relayEvents = CopyOnWriteArrayList<String>()
            val relayFinished = CountDownLatch(1)
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName to arguments
                    return when (toolName) {
                        "create_root_study" -> VoiceTutorMcpToolResult(
                            output = """{"created":true,"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                            isError = false,
                            studyTreeChanged = true,
                            createdStudyId = 777,
                            changedStudyId = 777,
                            changeKind = VoiceTutorStudyChangeKind.CREATED,
                            rootStudyReadbackId = 777,
                        )
                        "get_study" -> VoiceTutorMcpToolResult(
                            output = """{"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                            isError = false,
                            candidateDiscovery = exactRootReadback(777, "스프링"),
                        )
                        else -> error("unexpected tool: $toolName")
                    }
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller,
                controlContext(),
                tools,
                { raw, _, _ ->
                    relayEvents += raw
                    relayFinished.countDown()
                },
            ).subscribe({}, f.errors::add)
            try {
                val naturalRequest = "스프링으로 새롭게 공부하고 싶다고"
                f.utterance(1, "create-spring", naturalRequest)
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                    rootRequest("스프링", 5, naturalRequest, omitted = true),
                )
                val publication = f.publications().single()
                f.confirm(publication, persisted = true)
                f.confirm(publication, persisted = true)

                // The model has not received a response turn in which it could
                // ask "만들까요?". Exact assessed arguments are already frozen
                // in a server-owned provider conversation item.
                assertThat(f.awaitResponseCount(1)).hasSize(1)
                val createCallItem = f.conversationItems().single {
                    it.path("item").path("type").asText() == "function_call"
                }
                assertThat(createCallItem.path("item").path("name").asText())
                    .isEqualTo("create_root_study")
                assertThat(mapper.readTree(createCallItem.path("item").path("arguments").asText()))
                    .isEqualTo(mapper.readTree("""{"topic":"스프링","difficulty_level":5}"""))

                // A forged ACK cannot change the tuple or release execution.
                val forgedCallItem = createCallItem.path("item").deepCopy<ObjectNode>()
                forgedCallItem.put("arguments", """{"topic":"스프링","difficulty_level":7}""")
                f.provider("conversation.item.created", "item" to forgedCallItem)
                assertThat(invocations).isEmpty()

                f.acknowledgeConversationItem(createCallItem)
                f.acknowledgeConversationItem(createCallItem) // replay
                assertThat(relayFinished.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(invocations).containsExactly(
                    "create_root_study" to mapOf("topic" to "스프링", "difficulty_level" to 5),
                )
                assertThat(relayEvents.map { mapper.readTree(it).path("type").asText() })
                    .containsExactly(VoiceTutorRealtimeContract.STUDY_TREE_CHANGED_EVENT)
                assertThat(f.responses()).hasSize(1)

                val createOutput = f.awaitToolOutput(
                    createCallItem.path("item").path("call_id").asText(),
                )
                val readbackCall = f.awaitServerToolCall("get_study")
                assertThat(mapper.readTree(readbackCall.path("item").path("arguments").asText()))
                    .isEqualTo(mapper.readTree("""{"study_id":777}"""))

                // Reordered and replayed ACKs cannot open an intermediate
                // response or execute either call twice.
                f.acknowledgeConversationItem(readbackCall)
                f.acknowledgeConversationItem(readbackCall)
                val readOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
                assertThat(invocations).containsExactly(
                    "create_root_study" to mapOf("topic" to "스프링", "difficulty_level" to 5),
                    "get_study" to mapOf("study_id" to 777),
                )
                f.acknowledgeConversationItem(readOutput)
                f.acknowledgeConversationItem(readOutput)
                assertThat(f.responses()).hasSize(1)
                f.acknowledgeConversationItem(createOutput)
                val responses = f.awaitResponseCount(2)
                assertThat(responses).hasSize(2)
                assertThat(responses.last().path("response").path("tool_choice").asText()).isEqualTo("none")
                assertThat(responses.last().path("response").path("instructions").asText())
                    .contains("exact get_study readback", "Never call any tool", "lesson-start consent")
                    .doesNotContain("call get_study with")

                val spokenToken = responses.last().path("event_id").asText()
                f.controller.observeProviderEvent(f.response("response.created", "spoken-create-ack", spokenToken))
                f.provider("output_audio_buffer.started", "response_id" to "spoken-create-ack")
                f.provider(
                    "response.output_audio.delta",
                    "response_id" to "spoken-create-ack",
                    "delta" to "AA==",
                )
                val transcriptDisposition = f.provider(
                    "response.output_audio_transcript.done",
                    "response_id" to "spoken-create-ack",
                    "item_id" to "spoken-create-ack-item",
                    "content_index" to 0,
                    "transcript" to "스프링 레벨 5 루트를 저장했습니다. 이 주제로 학습을 시작할까요?",
                )
                assertThat(transcriptDisposition).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_ONLY)
                f.controller.observeProviderEvent(f.responseWithOutput(
                    "response.done",
                    "spoken-create-ack",
                    spokenToken,
                    listOf(mapOf(
                        "type" to "message",
                        "role" to "assistant",
                        "content" to listOf(mapOf(
                            "type" to "output_audio",
                            "transcript" to "스프링 레벨 5 루트를 저장했습니다. 이 주제로 학습을 시작할까요?",
                        )),
                    )),
                ))
                f.provider("output_audio_buffer.stopped", "response_id" to "spoken-create-ack")
                assertThat(invocations.count { it.first == "create_root_study" }).isEqualTo(1)
                assertThat(f.errors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }

    @Test
    fun `compound root creation and lesson start reads back then focuses and asks at the requested level`() =
        fixture().use { f ->
            val invocations = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName to arguments
                    return when (toolName) {
                        "create_root_study" -> {
                            val authorization = requireNotNull(
                                context.dialogueBoundary?.rootStudyCreationAuthorization,
                            )
                            assertThat(authorization.startLessonAfterCreate).isTrue()
                            assertThat(authorization.consume()).isTrue()
                            assertThat(authorization.consume()).isFalse()
                            VoiceTutorMcpToolResult(
                                output = """{"created":true,"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                                isError = false,
                                studyTreeChanged = true,
                                createdStudyId = 777,
                                changedStudyId = 777,
                                changeKind = VoiceTutorStudyChangeKind.CREATED,
                                rootStudyReadbackId = 777,
                            )
                        }
                        "get_study" -> VoiceTutorMcpToolResult(
                            output = """{"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                            isError = false,
                            candidateDiscovery = exactRootReadback(777, "스프링"),
                        )
                        "select_voice_study" -> {
                            assertThat(context.dialogueBoundary?.latestAcceptedLearnerIntent)
                                .isEqualTo(VoiceTutorInputIntent.CREATE_ROOT_STUDY)
                            assertThat(context.dialogueBoundary?.latestAcceptedLearnerTargetStudyId).isEqualTo(777)
                            assertThat(context.dialogueBoundary?.latestAcceptedLearnerTargetCandidate)
                                .isEqualTo(VoiceTutorStudyTargetCandidate(777, null, "스프링", difficulty = 7))
                            assertThat(context.dialogueBoundary?.focusAuthorization?.purpose)
                                .isEqualTo(VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START)
                            val authorization = requireNotNull(context.dialogueBoundary?.focusAuthorization)
                            assertThat(authorization.consume()).isTrue()
                            assertThat(authorization.consume()).isFalse()
                            VoiceTutorMcpToolResult(
                                output = """{"selected":true}""",
                                isError = false,
                                lessonRevision = 1,
                                lessonFocus = VoiceTutorLessonFocusSelection(
                                    VoiceTutorLessonFocus(777, 1),
                                    VoiceTutorStudySnapshot(777, null, "스프링", 7, 1),
                                ),
                            )
                        }
                        else -> error("unexpected tool: $toolName")
                    }
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller,
                controlContext(),
                tools,
                { _, _, _ -> },
            ).subscribe({}, f.errors::add)
            try {
                val command = "스프링 레벨 7 정도로 새롭게 주제 생성해서 학습해보자."
                f.utterance(1, "create-and-start-spring", command)
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                    rootRequest("스프링", 7, command).copy(startLessonAfterCreate = true),
                )
                f.confirm(f.publications().single(), persisted = true)

                val createCall = f.awaitServerToolCall("create_root_study")
                f.acknowledgeConversationItem(createCall)
                val createOutput = f.awaitToolOutput(createCall.path("item").path("call_id").asText())
                val readbackCall = f.awaitServerToolCall("get_study")
                f.acknowledgeConversationItem(readbackCall)
                val readbackOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
                val focusCall = f.awaitServerToolCall("select_voice_study")
                assertThat(mapper.readTree(focusCall.path("item").path("arguments").asText()))
                    .isEqualTo(mapper.readTree("""{"study_id":777}"""))
                assertThat(f.controller.mutationDialogueBoundary("forged-call-id").focusAuthorization).isNull()
                assertThat(f.responses()).hasSize(1)

                f.acknowledgeConversationItem(focusCall)
                val focusOutput = f.awaitToolOutput(focusCall.path("item").path("call_id").asText())
                f.acknowledgeConversationItem(createOutput)
                f.acknowledgeConversationItem(readbackOutput)
                f.acknowledgeConversationItem(focusOutput)

                val question = f.awaitResponseCount(2).last()
                assertThat(question.path("response").path("tool_choice").asText()).isEqualTo("auto")
                assertThat(question.path("response").path("instructions").asText())
                    .contains("current confirmed saved focus and level", "Ask that one", "Do not greet")
                    .doesNotContain("lesson-start consent")
                assertThat(invocations).containsExactly(
                    "create_root_study" to mapOf("topic" to "스프링", "difficulty_level" to 7),
                    "get_study" to mapOf("study_id" to 777),
                    "select_voice_study" to mapOf("study_id" to 777),
                )
                assertThat(f.errors).isEmpty()
            } finally {
                worker.dispose()
            }
        }

    @Test
    fun `compound offered study update and start focuses exact revised node and asks once`() =
        fixture().use { f ->
            f.offerCandidate(1, "browse-topic", 101, null, null)
            val responseCountBeforeUpdate = f.responses().size
            val command = "Redis 이름을 Redis 기초로 바꾸고 레벨 7로 바로 시작하자."
            f.utterance(2, "rename-and-start", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    difficulty = 7,
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                    startLessonAfterUpdate = true,
                ),
            )
            f.confirm(f.publications().last(), persisted = true)

            val updateCall = f.awaitServerToolCall("update_study")
            assertThat(mapper.readTree(updateCall.path("item").path("arguments").asText())).isEqualTo(
                mapper.readTree("""{"study_id":101,"topic":"Redis 기초","difficulty_level":7}"""),
            )
            val updateId = f.startServerToolCall(updateCall)
            val updateBoundary = f.controller.mutationDialogueBoundary(updateId)
            assertThat(updateBoundary.studyUpdateAuthorization?.scope)
                .isEqualTo(VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE)
            assertThat(updateBoundary.studyUpdateAuthorization?.targetProof)
                .isEqualTo(VoiceTutorStudyUpdateTargetProof(101, null, "Redis"))
            assertThat(updateBoundary.studyUpdateAuthorization?.startLessonAfterUpdate).isTrue()
            assertThat(updateBoundary.studyUpdateAuthorization?.consume()).isTrue()
            val updateOutput = f.completeStartedServerToolCall(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":7}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 101,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    lessonRevision = 1,
                    updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 7, 1),
                ),
            )

            val focusCall = f.awaitServerToolCall("select_voice_study")
            assertThat(mapper.readTree(focusCall.path("item").path("arguments").asText()))
                .isEqualTo(mapper.readTree("""{"study_id":101}"""))
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "get_study"
            }).isEmpty()
            val focusId = f.startServerToolCall(focusCall)
            val focusBoundary = f.controller.mutationDialogueBoundary(focusId)
            assertThat(focusBoundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.UPDATE_STUDY)
            assertThat(focusBoundary.latestAcceptedLearnerProviderItemId).isEqualTo("rename-and-start")
            assertThat(focusBoundary.latestAcceptedLearnerLessonRevision).isZero()
            assertThat(focusBoundary.focusExpectedCurrentLessonRevision).isEqualTo(1)
            assertThat(focusBoundary.latestAcceptedLearnerTargetCandidate)
                .isEqualTo(VoiceTutorStudyTargetCandidate(101, null, "Redis 기초", 7))
            assertThat(focusBoundary.latestAcceptedLearnerTargetTraversal)
                .isEqualTo(VoiceTutorStudyTargetTraversal(terminalLeafStudyId = 101))
            assertThat(focusBoundary.focusAuthorization?.purpose)
                .isEqualTo(VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START)
            assertThat(focusBoundary.studyUpdateAuthorization).isNull()
            assertThat(focusBoundary.focusAuthorization?.consume()).isTrue()

            val focusOutput = f.completeStartedServerToolCall(
                focusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true,"voiceLessonFocusChange":"UPDATED_STUDY_IMMEDIATE_START"}""",
                    isError = false,
                    lessonRevision = 2,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(101, 2),
                        VoiceTutorStudySnapshot(101, null, "Redis 기초", 7, 2),
                    ),
                ),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(focusId)).isTrue()
            f.acknowledgeConversationItem(updateOutput)
            f.acknowledgeConversationItem(focusOutput)

            val question = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
            assertThat(question.path("response").path("tool_choice").asText()).isEqualTo("auto")
            assertThat(question.path("response").path("instructions").asText())
                .contains("current confirmed saved focus and level", "Ask that one")
                .doesNotContain("lesson-start consent", "whether the learner wants to start")
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "update_study"
            }).isEqualTo(1)
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "select_voice_study"
            }).isEqualTo(1)
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `fresh owner snapshot survives noncommunicative noise and executes one exact first turn rename`() {
        val initialSnapshot = VoiceTutorInitialStudyMutationSnapshot(
            listOf(
                VoiceTutorStudyTargetCandidate(101, null, "Redis", 5),
                VoiceTutorStudyTargetCandidate(202, null, "Spring", 7),
            ),
        )
        val invocations = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
        fixture(initialStudyMutationSnapshot = initialSnapshot).use { f ->
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName to arguments
                    assertThat(toolName).isEqualTo("update_study")
                    val authorization = requireNotNull(context.dialogueBoundary?.studyUpdateAuthorization)
                    assertThat(authorization.scope)
                        .isEqualTo(VoiceTutorStudyUpdateAuthorizationScope.INITIAL_OWNER_SNAPSHOT)
                    assertThat(authorization.targetProof)
                        .isEqualTo(VoiceTutorStudyUpdateTargetProof(101, null, "Redis", 5))
                    assertThat(authorization.consume()).isTrue()
                    return VoiceTutorMcpToolResult(
                        output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":5}""",
                        isError = false,
                        studyTreeChanged = true,
                        changedStudyId = 101,
                        changeKind = VoiceTutorStudyChangeKind.UPDATED,
                        lessonRevision = 1,
                        updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 5, 1),
                    )
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller,
                controlContext(),
                tools,
                { _, _, _ -> },
            ).subscribe({}, f.errors::add)
            try {
                f.utterance(1, "initial-snapshot-noise", "어… 음…")
                val noiseAssessment = f.assessments().last().utterances.single()
                assertThat(noiseAssessment.studyMutationContext?.source)
                    .isEqualTo(VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT)
                assertThat(noiseAssessment.studyMutationContext?.candidates)
                    .containsExactlyElementsOf(initialSnapshot.candidates)
                f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
                f.deleted("initial-snapshot-noise")
                assertThat(f.publications()).isEmpty()

                val command = "Redis 이름을 Redis 기초로 바꿔 줘."
                f.utterance(2, "initial-snapshot-rename", command)
                val renameAssessment = f.assessments().last().utterances.single()
                assertThat(renameAssessment.studyMutationContext?.source)
                    .isEqualTo(VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT)
                assertThat(renameAssessment.targetOffer).isNull()
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.UPDATE_STUDY,
                    studyUpdateRequest = updateRequest(
                        studyId = 101,
                        command = command,
                        topic = "Redis 기초",
                        targetTopic = "Redis",
                        targetImplicitCurrentFocus = false,
                    ),
                )
                f.confirm(f.publications().single(), persisted = true)

                val updateCall = f.awaitServerToolCall("update_study")
                f.acknowledgeConversationItem(updateCall)
                val updateOutput = f.awaitToolOutput(updateCall.path("item").path("call_id").asText())
                f.acknowledgeConversationItem(updateOutput)

                assertThat(invocations).containsExactly(
                    "update_study" to mapOf("study_id" to 101, "topic" to "Redis 기초"),
                )
                assertThat(f.conversationItems().count {
                    it.path("item").path("name").asText() == "update_study"
                }).isEqualTo(1)
                assertThat(f.errors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }
    }

    @Test
    fun `first persisted meaningful turn spends owner snapshot so a later rename cannot reuse it`() =
        fixture(
            initialStudyMutationSnapshot = VoiceTutorInitialStudyMutationSnapshot(
                listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis", 5)),
            ),
        ).use { f ->
            f.utterance(1, "initial-snapshot-first-meaningful", "저장된 공부를 먼저 둘러볼게.")
            assertThat(f.assessments().last().utterances.single().studyMutationContext?.source)
                .isEqualTo(VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT)
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().single(), persisted = true)
            f.finishCurrentSpokenOffer("어떤 내용을 바꾸고 싶나요?")

            val command = "Redis 이름을 Redis 기초로 바꿔 줘."
            f.utterance(2, "initial-snapshot-second-rename", command)
            assertThat(f.assessments().last().utterances.single().studyMutationContext).isNull()
            val publicationCount = f.publications().size
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                ),
            )

            assertThat(f.publications()).hasSize(publicationCount)
            assertThat(f.deletions().last().path("item_id").asText())
                .isEqualTo("initial-snapshot-second-rename")
            f.deleted("initial-snapshot-second-rename")
            assertThat(f.conversationItems().none {
                it.path("item").path("name").asText() == "update_study"
            }).isTrue()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `first semantic publication revokes initial snapshot copies already frozen on queued commits`() =
        fixture(
            initialStudyMutationSnapshot = VoiceTutorInitialStudyMutationSnapshot(
                listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis", 5)),
            ),
        ).use { f ->
            f.utterance(1, "initial-snapshot-winner", "저장된 공부를 먼저 둘러볼게.")
            val winnerAssessment = f.assessments().single()
            assertThat(winnerAssessment.utterances.single().studyMutationContext?.source)
                .isEqualTo(VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT)

            // Provider commit/ASR for the next turn can race ahead while the first semantic
            // assessment is outstanding. Both bindings initially freeze the same snapshot.
            f.utterance(2, "initial-snapshot-loser", "Redis 이름을 Redis 기초로 바꿔 줘.")
            assertThat(f.assessments()).hasSize(1)

            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            val winnerPublication = f.publications().single()
            f.confirm(winnerPublication, persisted = true)
            val loserAssessment = f.assessments().last()
            assertThat(loserAssessment.utterances.single().itemId).isEqualTo("initial-snapshot-loser")
            assertThat(loserAssessment.utterances.single().studyMutationContext).isNull()

            val loserCommand = "Redis 이름을 Redis 기초로 바꿔 줘."
            val publicationCount = f.publications().size
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = loserCommand,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                ),
            )
            assertThat(f.publications()).hasSize(publicationCount)
            assertThat(f.deletions().last().path("item_id").asText())
                .isEqualTo("initial-snapshot-loser")
            f.deleted("initial-snapshot-loser")

            assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()
            assertThat(f.conversationItems().none {
                it.path("item").path("name").asText() == "update_study"
            }).isTrue()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `failed persistence of first semantic publication still prevents initial snapshot reuse`() =
        fixture(
            initialStudyMutationSnapshot = VoiceTutorInitialStudyMutationSnapshot(
                listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis", 5)),
            ),
        ).use { f ->
            f.utterance(1, "initial-snapshot-not-persisted", "저장된 공부를 먼저 둘러볼게.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().single(), persisted = false)
            f.finishCurrentSpokenOffer("어떤 내용을 바꾸고 싶나요?")

            val command = "Redis 이름을 Redis 기초로 바꿔 줘."
            f.utterance(2, "initial-snapshot-after-failed-persist", command)
            assertThat(f.assessments().last().utterances.single().studyMutationContext).isNull()
            val publicationCount = f.publications().size
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                ),
            )

            assertThat(f.publications()).hasSize(publicationCount)
            assertThat(f.deletions().last().path("item_id").asText())
                .isEqualTo("initial-snapshot-after-failed-persist")
            f.deleted("initial-snapshot-after-failed-persist")
            assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()
            assertThat(f.conversationItems().none {
                it.path("item").path("name").asText() == "update_study"
            }).isTrue()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `meaningful checkpoint does not consume initial snapshot before the same speech final turn`() =
        fixture(
            initialStudyMutationSnapshot = VoiceTutorInitialStudyMutationSnapshot(
                listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis", 5)),
            ),
        ).use { f ->
            f.start(1)
            f.controller.fireContinuousSpeechDeadline()
            assertThat(f.inputCommits()).hasSize(1)
            f.commit("initial-snapshot-checkpoint")
            f.transcript("initial-snapshot-checkpoint", "Redis 이름을 바꾸는 걸 생각 중인데")
            val checkpointAssessment = f.assessments().single()
            assertThat(checkpointAssessment.utterances.single().checkpoint).isTrue()
            assertThat(checkpointAssessment.utterances.single().studyMutationContext).isNull()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            val checkpointPublication = f.publications().single()
            assertThat(checkpointPublication.checkpoint).isTrue()
            f.confirm(checkpointPublication, persisted = true)

            f.stop(1)
            assertThat(f.inputCommits()).hasSize(2)
            f.commit("initial-snapshot-final-tail")
            f.transcript("initial-snapshot-final-tail", "Redis 기초로 바꿔 줘.")

            val finalAssessment = f.assessments().last().utterances.single()
            assertThat(finalAssessment.itemId).isEqualTo("initial-snapshot-final-tail")
            assertThat(finalAssessment.checkpoint).isFalse()
            assertThat(finalAssessment.studyMutationContext?.source)
                .isEqualTo(VoiceTutorStudyMutationContextSource.INITIAL_OWNER_SNAPSHOT)
            assertThat(finalAssessment.studyMutationContext?.candidates)
                .containsExactly(VoiceTutorStudyTargetCandidate(101, null, "Redis", 5))
            assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `update only then next meaningful start selects refreshed target without replaying write`() =
        fixture().use { f ->
            val invocations = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName to arguments
                    return when (toolName) {
                        "update_study" -> {
                            val authorization = requireNotNull(
                                context.dialogueBoundary?.studyUpdateAuthorization,
                            )
                            assertThat(authorization.consume()).isTrue()
                            VoiceTutorMcpToolResult(
                                output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":7}""",
                                isError = false,
                                studyTreeChanged = true,
                                changedStudyId = 101,
                                changeKind = VoiceTutorStudyChangeKind.UPDATED,
                                lessonRevision = 1,
                                updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 7, 1),
                            )
                        }
                        "select_voice_study" -> {
                            assertThat(context.dialogueBoundary?.latestAcceptedLearnerIntent)
                                .isEqualTo(VoiceTutorInputIntent.SELECT_SAVED_TOPIC)
                            assertThat(context.dialogueBoundary?.latestAcceptedLearnerTargetCandidate)
                                .isEqualTo(VoiceTutorStudyTargetCandidate(101, null, "Redis 기초", 7))
                            val authorization = requireNotNull(context.dialogueBoundary?.focusAuthorization)
                            assertThat(authorization.consume()).isTrue()
                            VoiceTutorMcpToolResult(
                                output = """{"selected":true}""",
                                isError = false,
                                lessonRevision = 2,
                                lessonFocus = VoiceTutorLessonFocusSelection(
                                    VoiceTutorLessonFocus(101, 2),
                                    VoiceTutorStudySnapshot(101, null, "Redis 기초", 7, 2),
                                ),
                            )
                        }
                        else -> error("unexpected tool: $toolName")
                    }
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller,
                controlContext(),
                tools,
                { _, _, _ -> },
            ).subscribe({}, f.errors::add)
            try {
                f.offerCandidate(1, "browse-before-split-update", 101, null, null)
                val responseCountBeforeUpdate = f.responses().size
                val updateCommand = "Redis 이름을 Redis 기초로 바꾸고 레벨 7로 해 줘."
                f.utterance(2, "split-update", updateCommand)
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.UPDATE_STUDY,
                    studyUpdateRequest = updateRequest(
                        studyId = 101,
                        command = updateCommand,
                        topic = "Redis 기초",
                        difficulty = 7,
                        targetTopic = "Redis",
                        targetImplicitCurrentFocus = false,
                    ),
                )
                f.confirm(f.publications().last(), persisted = true)

                val updateCall = f.awaitServerToolCall("update_study")
                f.acknowledgeConversationItem(updateCall)
                val updateOutput = f.awaitToolOutput(updateCall.path("item").path("call_id").asText())
                f.acknowledgeConversationItem(updateOutput)

                val followup = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
                assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
                assertThat(followup.path("response").path("instructions").asText())
                    .contains(
                        "already committed once",
                        "Never call or retry update_study",
                        "exact revised saved-topic name",
                        "start that topic now",
                    )
                f.finishCurrentSpokenOffer("Redis 기초로 변경했어요. 지금 이 주제로 시작할까요?")

                // Acoustic filler is deleted and must not consume the one
                // refreshed offer. The next actual semantic item receives it
                // from the controller; the test never injects that context.
                f.utterance(3, "split-update-noise", "어… 음…")
                f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
                f.deleted("split-update-noise")

                f.utterance(4, "split-update-start", "시작하자")
                val startAssessment = f.assessments().last()
                assertThat(startAssessment.utterances.single().targetOffer?.candidates)
                    .containsExactly(VoiceTutorStudyTargetCandidate(101, null, "Redis 기초", 7))
                assertThat(startAssessment.utterances.single().targetOffer?.lessonRevision).isEqualTo(1)
                assertThat(startAssessment.utterances.single().studyMutationContext?.lessonRevision).isEqualTo(1)
                f.assessTarget(VoiceTutorInputIntent.SELECT_SAVED_TOPIC, 101, listOf(101))
                f.confirm(f.publications().last(), persisted = true)
                f.dispatchModelToolCall("split-update-focus", "select_voice_study", "{\"study_id\":101}")
                val focusOutput = f.awaitToolOutput("split-update-focus")
                f.acknowledgeConversationItem(focusOutput)

                assertThat(invocations.map { it.first }).containsExactly("update_study", "select_voice_study")
                assertThat(f.conversationItems().count {
                    it.path("item").path("name").asText() == "update_study"
                }).isEqualTo(1)
                assertThat(f.errors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }

    @Test
    fun `compound update start without exact typed revised snapshot never focuses or asks`() =
        fixture().use { f ->
            f.offerCandidate(1, "browse-topic-unprepared", 101, null, null)
            val responseCountBeforeUpdate = f.responses().size
            val command = "Redis 이름을 Redis 기초로 바꾸고 바로 시작하자."
            f.utterance(2, "rename-start-unprepared", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                    startLessonAfterUpdate = true,
                ),
            )
            f.confirm(f.publications().last(), persisted = true)

            val updateCall = f.awaitServerToolCall("update_study")
            val updateId = f.startServerToolCall(updateCall)
            assertThat(f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization?.consume()).isTrue()
            val updateOutput = f.completeStartedServerToolCall(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":5,"voiceLessonChangeApplies":"NOT_PREPARED"}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 101,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    updatedStudySnapshot = null,
                ),
            )
            f.acknowledgeConversationItem(updateOutput)

            val followup = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "select_voice_study"
            }).isEmpty()
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("change was saved", "could not safely enter")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "update_study"
            }).isEqualTo(1)
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `newer persisted turn retires claimed update start without leaving auto focus pending`() =
        fixture().use { f ->
            f.offerCandidate(1, "browse-stale-update", 101, null, null)
            val responseCountBeforeUpdate = f.responses().size
            val command = "Redis 이름을 Redis 기초로 바꾸고 바로 시작하자."
            f.utterance(2, "stale-update-start", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                    startLessonAfterUpdate = true,
                ),
            )
            f.confirm(f.publications().last(), persisted = true)

            val updateCall = f.awaitServerToolCall("update_study")
            val updateId = f.startServerToolCall(updateCall)
            val updateAuthorization = requireNotNull(
                f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization,
            )
            assertThat(updateAuthorization.consume()).isTrue()

            // This durable turn owns the eventual single response. It retires
            // the old update-and-start conversational owner while the already
            // claimed write is still in flight.
            f.utterance(3, "newer-persisted-turn", "그 전에 방금 바꾼 이름부터 알려 줘.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().last(), persisted = true)
            assertThat(updateAuthorization.isActive()).isFalse()

            val updateOutput = f.completeStartedServerToolCall(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":5,"voiceLessonChangeApplies":"AUTO_FOCUS_PENDING","voiceLessonContextReady":true,"voiceLessonFocus":{"studyId":101,"revision":1},"notice":"wait for server focus"}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 101,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(101, 1),
                        VoiceTutorStudySnapshot(101, null, "Redis 기초", 5, 1),
                    ),
                    updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 5, 1),
                ),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(updateId)).isFalse()
            val normalizedOutput = mapper.readTree(updateOutput.path("item").path("output").asText())
            assertThat(normalizedOutput.path("voiceLessonChangeApplies").asText())
                .isEqualTo("REQUIRES_SELECTION")
            assertThat(normalizedOutput.path("voiceLessonContextReady").asBoolean()).isFalse()
            assertThat(normalizedOutput.has("voiceLessonFocus")).isFalse()
            assertThat(normalizedOutput.path("notice").asText())
                .contains("superseded immediate-start", "fresh topic choice")
                .doesNotContain("wait for server focus")
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "select_voice_study"
            }).isEmpty()

            f.acknowledgeConversationItem(updateOutput)
            val followup = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
            assertThat(f.responses()).hasSize(responseCountBeforeUpdate + 1)
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("exact saved-study update committed", "could not safely enter")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "update_study"
            }).isEqualTo(1)
            f.acknowledgeConversationItem(updateOutput)
            assertThat(f.responses()).hasSize(responseCountBeforeUpdate + 1)
            assertThat(f.controller.completeToolExecution(updateId, VoiceTutorMcpToolResult("{}", false)))
                .isFalse()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `compound current focus update accepts same focus at the exact revised revision`() =
        fixture().use { f ->
            f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
            val responseCountBeforeUpdate = f.responses().size
            val command = "이 주제 레벨을 6으로 바꾸고 계속 공부하자."
            f.utterance(2, "current-focus-update-start", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    difficulty = 6,
                    startLessonAfterUpdate = true,
                ),
            )
            f.confirm(f.publications().last(), persisted = true)

            val updateId = f.startServerToolCall(f.awaitServerToolCall("update_study"))
            assertThat(f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization?.scope)
                .isEqualTo(VoiceTutorStudyUpdateAuthorizationScope.CONFIRMED_FOCUS_TREE)
            assertThat(f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization?.consume()).isTrue()
            val updateOutput = f.completeStartedServerToolCall(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"id":101,"parentStudyId":null,"topic":"Redis","difficultyLevel":6}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 101,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    lessonRevision = 2,
                    updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis", 6, 2),
                ),
            )
            val focusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))
            assertThat(f.controller.mutationDialogueBoundary(focusId).focusExpectedCurrentLessonRevision)
                .isEqualTo(2)
            assertThat(f.controller.mutationDialogueBoundary(focusId).focusAuthorization?.consume()).isTrue()
            val focusOutput = f.completeStartedServerToolCall(
                focusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true,"voiceLessonFocusChange":"UPDATED_STUDY_IMMEDIATE_START"}""",
                    isError = false,
                    lessonRevision = 2,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(101, 1),
                        VoiceTutorStudySnapshot(101, null, "Redis", 6, 2),
                        lessonRevision = 2,
                    ),
                ),
            )
            f.acknowledgeConversationItem(updateOutput)
            f.acknowledgeConversationItem(focusOutput)

            val question = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
            assertThat(question.path("response").path("tool_choice").asText()).isEqualTo("auto")
            assertThat(question.path("response").path("instructions").asText())
                .contains("current confirmed saved focus and level", "Ask that one")
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `newer persisted turn keeps claimed update focus but supersedes its automatic question`() =
        fixture().use { f ->
            f.offerCandidate(1, "browse-stale-update-focus", 101, null, null)
            val responseCountBeforeUpdate = f.responses().size
            val command = "Redis 이름을 Redis 기초로 바꾸고 바로 시작하자."
            f.utterance(2, "update-before-stale-focus", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(
                    studyId = 101,
                    command = command,
                    topic = "Redis 기초",
                    targetTopic = "Redis",
                    targetImplicitCurrentFocus = false,
                    startLessonAfterUpdate = true,
                ),
            )
            f.confirm(f.publications().last(), persisted = true)

            val updateId = f.startServerToolCall(f.awaitServerToolCall("update_study"))
            assertThat(f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization?.consume()).isTrue()
            val updateOutput = f.completeStartedServerToolCall(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":5,"voiceLessonChangeApplies":"AUTO_FOCUS_PENDING"}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 101,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    lessonRevision = 1,
                    updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 5, 1),
                ),
            )
            val focusCall = f.awaitServerToolCall("select_voice_study")
            val focusId = f.startServerToolCall(focusCall)
            val focusAuthorization = requireNotNull(
                f.controller.mutationDialogueBoundary(focusId).focusAuthorization,
            )
            assertThat(focusAuthorization.consume()).isTrue()

            // The DB focus is already in flight, so newer speech may supersede
            // its conversational question authority but cannot undo the commit.
            f.utterance(3, "newer-turn-after-focus-claim", "잠깐, 그 전에 변경된 내용부터 알려 줘.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            f.confirm(f.publications().last(), persisted = true)
            assertThat(focusAuthorization.isActive()).isFalse()

            val focusOutput = f.completeStartedServerToolCall(
                focusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true,"voiceLessonContextReady":true,"voiceLessonFocus":{"studyId":101,"parentStudyId":null,"topic":"Redis 기초","difficulty":5,"revision":2},"voiceLessonFocusChange":"UPDATED_STUDY_IMMEDIATE_START","notice":"Use the revised frozen name and level and ask the first substantive question now."}""",
                    isError = false,
                    lessonRevision = 2,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(101, 2),
                        VoiceTutorStudySnapshot(101, null, "Redis 기초", 5, 2),
                    ),
                ),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(focusId)).isTrue()
            val normalizedOutput = mapper.readTree(focusOutput.path("item").path("output").asText())
            assertThat(normalizedOutput.path("voiceLessonFocusChange").asText())
                .isEqualTo("FOCUS_COMMITTED_QUESTION_SUPERSEDED")
            assertThat(normalizedOutput.path("voiceLessonContextReady").asBoolean()).isTrue()
            assertThat(normalizedOutput.path("voiceLessonFocus").path("studyId").asLong()).isEqualTo(101)
            assertThat(normalizedOutput.path("notice").asText())
                .contains("focus committed", "automatic first question", "newer learner speech")
                .doesNotContain("ask the first substantive question now", "UPDATED_STUDY_IMMEDIATE_START")

            f.acknowledgeConversationItem(updateOutput)
            f.acknowledgeConversationItem(focusOutput)
            val followup = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
            assertThat(f.responses()).hasSize(responseCountBeforeUpdate + 1)
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("focus committed", "authoritative", "automatic first question was paused")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "update_study"
            }).isEqualTo(1)
            assertThat(f.conversationItems().count {
                it.path("item").path("name").asText() == "select_voice_study"
            }).isEqualTo(1)
            f.acknowledgeConversationItem(focusOutput)
            assertThat(f.responses()).hasSize(responseCountBeforeUpdate + 1)
            assertThat(f.controller.completeToolExecution(focusId, VoiceTutorMcpToolResult("{}", false)))
                .isFalse()
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `compound start does not focus an existing root at a different requested level`() = fixture().use { f ->
        val invocations = CopyOnWriteArrayList<String>()
        val tools = object : VoiceTutorMcpToolPort {
            override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

            override suspend fun execute(
                context: VoiceTutorWebRtcControlContext,
                toolName: String,
                arguments: Map<String, Any>,
            ): VoiceTutorMcpToolResult {
                invocations += toolName
                return when (toolName) {
                    "create_root_study" -> VoiceTutorMcpToolResult(
                        output = """{"created":false,"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                        isError = false,
                        rootStudyReadbackId = 777,
                    )
                    "get_study" -> VoiceTutorMcpToolResult(
                        output = """{"id":777,"parentStudyId":null,"topic":"스프링","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                        isError = false,
                        candidateDiscovery = exactRootReadback(777, "스프링"),
                    )
                    else -> error("unexpected tool: $toolName")
                }
            }
        }
        val worker = voiceTutorMcpToolRelay(
            f.controller,
            controlContext(),
            tools,
            { _, _, _ -> },
        ).subscribe({}, f.errors::add)
        try {
            val command = "스프링 레벨 7로 새 루트를 만들고 바로 공부하자."
            f.utterance(1, "existing-root-different-level", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("스프링", 7, command).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().single(), persisted = true)

            val createCall = f.awaitServerToolCall("create_root_study")
            f.acknowledgeConversationItem(createCall)
            val createOutput = f.awaitToolOutput(createCall.path("item").path("call_id").asText())
            val readbackCall = f.awaitServerToolCall("get_study")
            f.acknowledgeConversationItem(readbackCall)
            val readbackOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
            f.acknowledgeConversationItem(createOutput)
            f.acknowledgeConversationItem(readbackOutput)

            val followup = f.awaitResponseCount(2).last()
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("saved root exists", "could not enter it")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(invocations).containsExactly("create_root_study", "get_study")
            assertThat(f.errors).isEmpty()
        } finally {
            worker.dispose()
        }
    }

    @Test
    fun `exact rejected server root envelope keeps the call alive and never retries the write`() =
        fixture().use { f ->
            val command = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "rejected-root-envelope", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, command).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().single(), persisted = true)

            val createCall = f.awaitServerToolCall("create_root_study")
            val callId = createCall.path("item").path("call_id").asText()
            assertThat(f.rejectConversationItem(createCall))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(f.controller.beginToolExecution(callId)).isFalse()

            // The same provider rejection and a late item ACK are idempotent. Neither can
            // resurrect the exact-once write or enqueue a replacement call.
            assertThat(f.rejectConversationItem(createCall))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            f.acknowledgeConversationItem(createCall)
            assertThat(f.controller.beginToolExecution(callId)).isFalse()

            val followup = f.awaitResponseCount(2).last()
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("never retry create_root_study", "saved result could not be verified")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "create_root_study"
            }).hasSize(1)
            assertThat(f.diagnostics).hasSize(1)
            assertThat(f.diagnostics.single().action)
                .isEqualTo(VoiceTutorProviderTurnFailureAction.SERVER_CALL_REJECTED)
            assertThat(f.diagnostics.single().causedEventRef).matches("[0-9a-f]{16}")
            assertThat(f.diagnostics.toString())
                .doesNotContain("private provider payload", createCall.path("event_id").asText())
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `rejected exact root readback waits for create output ACK and never retries or focuses`(): Unit =
        fixture().use { f ->
            val command = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "rejected-root-readback", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, command).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().single(), persisted = true)

            val createId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val createOutput = f.completeStartedServerToolCall(
                createId,
                VoiceTutorMcpToolResult(
                    output = """{"created":true,"id":721,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    studyTreeChanged = true,
                    createdStudyId = 721,
                    changedStudyId = 721,
                    changeKind = VoiceTutorStudyChangeKind.CREATED,
                    rootStudyReadbackId = 721,
                ),
            )
            val responseCountBeforeRejection = f.responses().size
            val readbackCall = f.awaitServerToolCall("get_study")
            val readbackId = readbackCall.path("item").path("call_id").asText()

            assertThat(f.rejectConversationItem(readbackCall))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(f.responses()).hasSize(responseCountBeforeRejection)
            assertThat(f.controller.beginToolExecution(readbackId)).isFalse()

            // A late provider ACK cannot resurrect the rejected exact read or schedule focus.
            f.acknowledgeConversationItem(readbackCall)
            assertThat(f.controller.beginToolExecution(readbackId)).isFalse()
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "create_root_study"
            }).hasSize(1)
            assertThat(f.conversationItems().none {
                it.path("item").path("name").asText() == "select_voice_study"
            }).isTrue()
            assertThat(f.conversationItems().none {
                it.path("item").path("type").asText() == "function_call_output" &&
                    it.path("item").path("call_id").asText() == readbackId
            }).isTrue()
            assertThat(f.responses()).hasSize(responseCountBeforeRejection)

            f.acknowledgeConversationItem(createOutput)
            val followup = f.awaitResponseCount(responseCountBeforeRejection + 1).last()
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("never retry create_root_study or get_study", "saved result could not be verified")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `stale root envelope rejection cannot poison newer successful focus or question`(): Unit =
        fixture().use { f ->
            fun createResult(id: Long, topic: String, difficulty: Int) = VoiceTutorMcpToolResult(
                output = """{"created":true,"id":$id,"parentStudyId":null,"topic":"$topic","difficultyLevel":$difficulty,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = id,
                changedStudyId = id,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = id,
            )

            val firstCommand = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "rejected-stale-owner-a", firstCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, firstCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val firstCreateCall = f.awaitServerToolCall("create_root_study")
            val firstCreateId = firstCreateCall.path("item").path("call_id").asText()

            val secondCommand = "Kotlin 레벨 6 루트를 만들고 바로 공부하자."
            f.utterance(2, "successful-owner-b", secondCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Kotlin", 6, secondCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val secondCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val secondCreateOutput =
                f.completeStartedServerToolCall(secondCreateId, createResult(722, "Kotlin", 6))
            val secondReadId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
            val secondReadOutput = f.completeStartedServerToolCall(
                secondReadId,
                VoiceTutorMcpToolResult(
                    output = """{"id":722,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    candidateDiscovery = exactRootReadback(722, "Kotlin"),
                ),
            )
            val secondFocusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))
            val secondFocusOutput = f.completeStartedServerToolCall(
                secondFocusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true}""",
                    isError = false,
                    lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(722, 1),
                        VoiceTutorStudySnapshot(722, null, "Kotlin", 6, 1),
                    ),
                ),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(secondFocusId)).isTrue()
            val responseCountBeforeStaleRejection = f.responses().size

            assertThat(f.rejectConversationItem(firstCreateCall))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            f.acknowledgeConversationItem(firstCreateCall)
            assertThat(f.controller.beginToolExecution(firstCreateId)).isFalse()
            assertThat(f.responses()).hasSize(responseCountBeforeStaleRejection)

            listOf(secondCreateOutput, secondReadOutput, secondFocusOutput)
                .forEach(f::acknowledgeConversationItem)
            val question = f.awaitResponseCount(responseCountBeforeStaleRejection + 1).last()
            assertThat(question.path("response").path("tool_choice").asText()).isEqualTo("auto")
            assertThat(question.path("response").path("instructions").asText())
                .contains("current confirmed saved focus and level", "Ask that one")
                .doesNotContain("saved result could not be verified", "could not safely confirm")
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "create_root_study"
            }).hasSize(2)
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "get_study"
            }).hasSize(1)
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "select_voice_study"
            }).hasSize(1)
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `late stale readback cannot replace a newer persisted learner turn with root status`() =
        fixture().use { f ->
            val firstCommand = "Spring을 새 루트로 만들어 줘."
            f.utterance(1, "root-before-new-turn", firstCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 5, firstCommand, omitted = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val createId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val createOutput = f.completeStartedServerToolCall(
                createId,
                VoiceTutorMcpToolResult(
                    output = """{"created":true,"id":731,"parentStudyId":null,"topic":"Spring","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    studyTreeChanged = true,
                    createdStudyId = 731,
                    changedStudyId = 731,
                    changeKind = VoiceTutorStudyChangeKind.CREATED,
                    rootStudyReadbackId = 731,
                ),
            )
            val readId = f.startServerToolCall(f.awaitServerToolCall("get_study"))

            f.utterance(2, "newer-ordinary-turn", "잠깐, 다른 얘기부터 할게.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL)
            f.confirm(f.publications().last(), persisted = true)
            val responseCountBeforeReadback = f.responses().size

            val readOutput = f.completeStartedServerToolCall(
                readId,
                VoiceTutorMcpToolResult(
                    output = """{"id":731,"parentStudyId":null,"topic":"Spring","difficultyLevel":5,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    candidateDiscovery = exactRootReadback(731, "Spring"),
                ),
            )
            listOf(createOutput, readOutput).forEach(f::acknowledgeConversationItem)

            val response = f.awaitResponseCount(responseCountBeforeReadback + 1).last()
            assertThat(response.path("response").path("instructions").asText())
                .doesNotContain(
                    "completed an exact get_study readback",
                    "saved result could not be verified",
                    "saved root exists",
                )
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `focus commit during a new root utterance fails the stale write closed without disconnecting`() =
        fixture().use { f ->
            val firstCommand = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "focus-race-owner", firstCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, firstCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val createId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val createOutput = f.completeStartedServerToolCall(
                createId,
                VoiceTutorMcpToolResult(
                    output = """{"created":true,"id":741,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    studyTreeChanged = true,
                    createdStudyId = 741,
                    changedStudyId = 741,
                    changeKind = VoiceTutorStudyChangeKind.CREATED,
                    rootStudyReadbackId = 741,
                ),
            )
            val readId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
            val readOutput = f.completeStartedServerToolCall(
                readId,
                VoiceTutorMcpToolResult(
                    output = """{"id":741,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                    isError = false,
                    candidateDiscovery = exactRootReadback(741, "Spring"),
                ),
            )
            val focusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))

            // This utterance freezes revision 0. The older focus then commits revision 1 before
            // the new USER item is assessed and persisted.
            f.start(2)
            val focusOutput = f.completeStartedServerToolCall(
                focusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true}""",
                    isError = false,
                    lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(741, 1),
                        VoiceTutorStudySnapshot(741, null, "Spring", 7, 1),
                    ),
                ),
            )
            f.stop(2)
            f.commit("root-at-stale-revision")
            f.transcript("root-at-stale-revision", "Kotlin 레벨 6 루트를 만들고 바로 공부하자.")
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Kotlin", 6, "Kotlin 레벨 6 루트를 만들고 바로 공부하자.")
                    .copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val responseCountBeforeAcks = f.responses().size

            listOf(createOutput, readOutput, focusOutput).forEach(f::acknowledgeConversationItem)
            val recovery = f.awaitResponseCount(responseCountBeforeAcks + 1).last()
            assertThat(recovery.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(recovery.path("response").path("instructions").asText())
                .contains("could not safely execute", "was not retried")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "create_root_study"
            }).hasSize(1)
            assertThat(f.errors).isEmpty()
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `rejected immediate focus keeps the saved root and emits no study question`() = fixture().use { f ->
        val command = "Spring 레벨 7 루트를 만들고 바로 공부하자."
        f.utterance(1, "rejected-root-focus", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootRequest("Spring", 7, command).copy(startLessonAfterCreate = true),
        )
        f.confirm(f.publications().single(), persisted = true)

        val createId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
        val createOutput = f.completeStartedServerToolCall(
            createId,
            VoiceTutorMcpToolResult(
                output = """{"created":true,"id":711,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = 711,
                changedStudyId = 711,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = 711,
            ),
        )
        val readId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
        val readOutput = f.completeStartedServerToolCall(
            readId,
            VoiceTutorMcpToolResult(
                output = """{"id":711,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                candidateDiscovery = exactRootReadback(711, "Spring"),
            ),
        )
        val focusCall = f.awaitServerToolCall("select_voice_study")
        val focusCallId = focusCall.path("item").path("call_id").asText()

        assertThat(f.rejectConversationItem(focusCall))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.acknowledgeConversationItem(focusCall)
        assertThat(f.controller.beginToolExecution(focusCallId)).isFalse()
        listOf(createOutput, readOutput).forEach(f::acknowledgeConversationItem)

        val followup = f.awaitResponseCount(2).last()
        assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(followup.path("response").path("instructions").asText())
            .contains("saved root exists", "could not enter it", "do not claim that teaching started")
            .doesNotContain("authorized exactly one substantive study question")
        assertThat(f.conversationItems().none {
            it.path("item").path("type").asText() == "function_call_output" &&
                it.path("item").path("call_id").asText() == focusCallId
        }).isTrue()
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `rejected current update envelope does not retry or turn setup into learning`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        val command = "이 주제 레벨을 7로 바꿔 줘."
        f.utterance(2, "rejected-update-envelope", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.UPDATE_STUDY,
            studyUpdateRequest = updateRequest(101, command, difficulty = 7),
        )
        f.confirm(f.publications().last(), persisted = true)

        val responseCountBeforeRejection = f.responses().size
        val updateCall = f.awaitServerToolCall("update_study")
        val callId = updateCall.path("item").path("call_id").asText()
        assertThat(f.rejectConversationItem(updateCall))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.acknowledgeConversationItem(updateCall)
        assertThat(f.controller.beginToolExecution(callId)).isFalse()
        assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()

        val followup = f.awaitResponseCount(responseCountBeforeRejection + 1).last()
        assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(followup.path("response").path("instructions").asText())
            .contains("never retry", "do not ask the learner to repeat the same command more clearly")
            .doesNotContain("authorized exactly one substantive study question")
        assertThat(f.conversationItems().filter {
            it.path("item").path("name").asText() == "update_study"
        }).hasSize(1)
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `rejected current child creation envelope revokes its one shot lease`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        val command = "여기에 Streams를 새 하위 주제로 만들어 줘."
        f.utterance(2, "rejected-child-envelope", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
            childStudyCreationRequest = childRequest(101, "Streams", command),
        )
        f.confirm(f.publications().last(), persisted = true)

        val responseCountBeforeRejection = f.responses().size
        val childCall = f.awaitServerToolCall("create_study_topic")
        val callId = childCall.path("item").path("call_id").asText()
        assertThat(f.rejectConversationItem(childCall))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.acknowledgeConversationItem(childCall)
        assertThat(f.controller.beginToolExecution(callId)).isFalse()
        assertThat(f.controller.mutationDialogueBoundary().childStudyCreationAuthorization).isNull()

        val followup = f.awaitResponseCount(responseCountBeforeRejection + 1).last()
        assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(followup.path("response").path("instructions").asText())
            .contains("never retry", "study-tree change could not be processed")
            .doesNotContain("authorized exactly one substantive study question")
        assertThat(f.conversationItems().filter {
            it.path("item").path("name").asText() == "create_study_topic"
        }).hasSize(1)
        assertThat(f.errors).isEmpty()
        f.assertNoAudioDisruption()
    }

    @Test
    fun `noncommunicative speech before root envelope rejection preserves bounded failure followup`(): Unit =
        fixture().use { f ->
            val command = "Spring 레벨 7 루트를 만들어 줘."
            f.utterance(1, "root-before-acoustic-noise", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, command),
            )
            f.confirm(f.publications().last(), persisted = true)
            val createCall = f.awaitServerToolCall("create_root_study")
            val createId = createCall.path("item").path("call_id").asText()
            val responseCountBeforeNoise = f.responses().size

            // Raw acoustic activity is not a semantic replacement turn. Preserve
            // both the owner and its lease until the assessment verdict is durable.
            f.start(2)
            assertThat(f.rejectConversationItem(createCall))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(f.controller.beginToolExecution(createId)).isFalse()
            assertThat(f.responses()).hasSize(responseCountBeforeNoise)

            f.stop(2)
            f.commit("root-rejection-noise")
            f.transcript("root-rejection-noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            assertThat(f.responses()).hasSize(responseCountBeforeNoise)
            f.deleted("root-rejection-noise")

            val followup = f.awaitResponseCount(responseCountBeforeNoise + 1).last()
            assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(followup.path("response").path("instructions").asText())
                .contains("never retry create_root_study", "saved result could not be verified")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.conversationItems().filter {
                it.path("item").path("name").asText() == "create_root_study"
            }).hasSize(1)
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `meaningful persisted speech retires update lease before releasing held action`(): Unit =
        fixture().use { f ->
            f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
            val command = "이 주제 레벨을 7로 바꿔 줘."
            f.utterance(2, "update-before-acoustic-noise", command)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.UPDATE_STUDY,
                studyUpdateRequest = updateRequest(101, command, difficulty = 7),
            )
            f.confirm(f.publications().last(), persisted = true)
            val updateCall = f.awaitServerToolCall("update_study")
            val updateId = updateCall.path("item").path("call_id").asText()
            val updateLease = requireNotNull(
                f.controller.mutationDialogueBoundary().studyUpdateAuthorization,
            )

            f.acknowledgeConversationItem(updateCall)
            f.start(3)
            // The provider ACK is accepted, but execution cannot observe the old
            // write lease until this acoustic turn receives a semantic verdict.
            assertThat(f.controller.beginToolExecution(updateId)).isFalse()
            assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization)
                .isSameAs(updateLease)
            assertThat(updateLease.isActive()).isTrue()

            f.stop(3)
            f.commit("meaningful-update-correction")
            f.transcript("meaningful-update-correction", "아니, 수정하지 말자.")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
            val correction = f.publications().last()
            f.confirm(correction, persisted = true)

            // Publication retires the previous owner before releasing the held
            // action. The worker can consume it, but the exact mutation boundary
            // is now fail-closed and cannot perform the superseded write.
            assertThat(updateLease.isActive()).isFalse()
            assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()
            assertThat(f.controller.beginToolExecution(updateId)).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(updateId).studyUpdateAuthorization).isNull()
            assertThat(f.controller.completeToolExecution(
                updateId,
                VoiceTutorMcpToolResult(
                    output = """{"error":{"code":"UNAUTHORIZED","message":"superseded learner intent"}}""",
                    isError = true,
                ),
            )).isTrue()
            assertThat(f.awaitToolOutput(updateId).path("item").path("output").asText())
                .contains("UNAUTHORIZED")
            assertThat(f.errors).isEmpty()
            f.assertNoAudioDisruption()
        }

    @Test
    fun `late superseded root focus ACK cannot replace a newer successful focus or question purpose`() =
        fixture().use { f ->
            fun createResult(id: Long, topic: String, difficulty: Int) = VoiceTutorMcpToolResult(
                output = """{"created":true,"id":$id,"parentStudyId":null,"topic":"$topic","difficultyLevel":$difficulty,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = id,
                changedStudyId = id,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = id,
            )
            fun readbackResult(id: Long, topic: String, difficulty: Int) = VoiceTutorMcpToolResult(
                output = """{"id":$id,"parentStudyId":null,"topic":"$topic","difficultyLevel":$difficulty,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                candidateDiscovery = exactRootReadback(id, topic),
            )
            fun focusResult(id: Long, topic: String, difficulty: Int, revision: Long) = VoiceTutorMcpToolResult(
                output = """{"selected":true}""",
                isError = false,
                lessonRevision = revision,
                lessonFocus = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(id, revision),
                    VoiceTutorStudySnapshot(id, null, topic, difficulty, revision),
                ),
            )

            val firstCommand = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "root-owner-a", firstCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, firstCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val firstCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val firstCreateOutput = f.completeStartedServerToolCall(firstCreateId, createResult(701, "Spring", 7))
            val firstReadId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
            val firstReadOutput = f.completeStartedServerToolCall(firstReadId, readbackResult(701, "Spring", 7))
            val firstFocusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))

            val secondCommand = "Kotlin 레벨 6 루트를 만들고 바로 공부하자."
            f.utterance(2, "root-owner-b", secondCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Kotlin", 6, secondCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val secondCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val secondCreateOutput = f.completeStartedServerToolCall(secondCreateId, createResult(702, "Kotlin", 6))
            val secondReadId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
            val secondReadOutput = f.completeStartedServerToolCall(secondReadId, readbackResult(702, "Kotlin", 6))
            val secondFocusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))
            val secondFocusOutput = f.completeStartedServerToolCall(
                secondFocusId,
                focusResult(702, "Kotlin", 6, revision = 2),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(secondFocusId)).isTrue()

            val firstFocusOutput = f.completeStartedServerToolCall(
                firstFocusId,
                focusResult(701, "Spring", 7, revision = 1),
            )
            assertThat(f.controller.shouldRelayLessonFocusEvent(firstFocusId)).isFalse()

            listOf(
                firstCreateOutput,
                firstReadOutput,
                secondCreateOutput,
                secondReadOutput,
                secondFocusOutput,
                firstFocusOutput,
            ).forEach(f::acknowledgeConversationItem)

            val question = f.awaitResponseCount(2).last()
            assertThat(question.path("response").path("tool_choice").asText()).isEqualTo("auto")
            assertThat(question.path("response").path("instructions").asText())
                .contains("current confirmed saved focus and level", "Ask that one")
                .doesNotContain("saved result could not be verified")
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `late superseded committed focus reconciles state and revokes the newer stale owner`(): Unit =
        fixture().use { f ->
            fun createResult(id: Long, topic: String, difficulty: Int) = VoiceTutorMcpToolResult(
                output = """{"created":true,"id":$id,"parentStudyId":null,"topic":"$topic","difficultyLevel":$difficulty,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = id,
                changedStudyId = id,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = id,
            )
            fun readbackResult(id: Long, topic: String, difficulty: Int) = VoiceTutorMcpToolResult(
                output = """{"id":$id,"parentStudyId":null,"topic":"$topic","difficultyLevel":$difficulty,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                candidateDiscovery = exactRootReadback(id, topic),
            )

            val firstCommand = "Spring 레벨 7 루트를 만들고 바로 공부하자."
            f.utterance(1, "committed-owner-a", firstCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Spring", 7, firstCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val firstCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
            val firstCreateOutput =
                f.completeStartedServerToolCall(firstCreateId, createResult(711, "Spring", 7))
            val firstReadId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
            val firstReadOutput =
                f.completeStartedServerToolCall(firstReadId, readbackResult(711, "Spring", 7))
            val firstFocusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))

            val secondCommand = "Kotlin 레벨 6 루트를 만들고 바로 공부하자."
            f.utterance(2, "newer-owner-b", secondCommand)
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Kotlin", 6, secondCommand).copy(startLessonAfterCreate = true),
            )
            f.confirm(f.publications().last(), persisted = true)
            val secondCreateCall = f.awaitServerToolCall("create_root_study")
            f.acknowledgeConversationItem(secondCreateCall)
            val secondCreateId = secondCreateCall.path("item").path("call_id").asText()

            assertThat(f.controller.completeToolExecution(
                firstFocusId,
                VoiceTutorMcpToolResult(
                    output = """{"selected":true}""",
                    isError = false,
                    lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(711, 1),
                        VoiceTutorStudySnapshot(711, null, "Spring", 7, 1),
                    ),
                ),
            )).isTrue()

            val firstFocusOutput = f.awaitToolOutput(firstFocusId)
            assertThat(f.controller.shouldRelayLessonFocusEvent(firstFocusId)).isTrue()
            assertThat(f.controller.beginToolExecution(secondCreateId)).isTrue()
            assertThat(f.controller.mutationDialogueBoundary(secondCreateId).rootStudyCreationAuthorization).isNull()
            assertThat(f.controller.completeToolExecution(
                secondCreateId,
                VoiceTutorMcpToolResult(
                    output = """{"error":{"code":"UNAUTHORIZED","message":"stale lesson revision"}}""",
                    isError = true,
                ),
            )).isTrue()
            val secondCreateOutput = f.awaitToolOutput(secondCreateId)

            listOf(firstCreateOutput, firstReadOutput, firstFocusOutput, secondCreateOutput)
                .forEach(f::acknowledgeConversationItem)
            val recovery = f.awaitResponseCount(2).last()
            assertThat(recovery.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(recovery.path("response").path("instructions").asText())
                .contains("focus committed", "do not claim that focus failed", "automatic first question was paused")
                .doesNotContain("authorized exactly one substantive study question")
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `late create-only failure cannot poison a newer root readback followup`() = fixture().use { f ->
        val firstCommand = "Spring을 새 루트로 만들어 줘."
        f.utterance(1, "create-only-owner-a", firstCommand)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootRequest("Spring", 5, firstCommand, omitted = true),
        )
        f.confirm(f.publications().last(), persisted = true)
        val firstCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))

        val secondCommand = "Kotlin 레벨 6 루트를 만들어 줘."
        f.utterance(2, "create-only-owner-b", secondCommand)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootRequest("Kotlin", 6, secondCommand),
        )
        f.confirm(f.publications().last(), persisted = true)
        val secondCreateId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
        val secondCreateOutput = f.completeStartedServerToolCall(
            secondCreateId,
            VoiceTutorMcpToolResult(
                output = """{"created":true,"id":802,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = 802,
                changedStudyId = 802,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = 802,
            ),
        )
        val secondReadId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
        val secondReadOutput = f.completeStartedServerToolCall(
            secondReadId,
            VoiceTutorMcpToolResult(
                output = """{"id":802,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                candidateDiscovery = exactRootReadback(802, "Kotlin"),
            ),
        )
        val firstFailureOutput = f.completeStartedServerToolCall(
            firstCreateId,
            VoiceTutorMcpToolResult(
                output = """{"error":{"code":"TOOL_UNAVAILABLE","message":"unconfirmed"}}""",
                isError = true,
            ),
        )

        listOf(secondCreateOutput, secondReadOutput, firstFailureOutput)
            .forEach(f::acknowledgeConversationItem)

        val followup = f.awaitResponseCount(2).last()
        assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(followup.path("response").path("instructions").asText())
            .contains("completed an exact get_study readback", "ask only whether")
            .doesNotContain("not confirmed", "could not be verified")
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `current root focus metadata mismatch publishes no revision UI focus or study question`() = fixture().use { f ->
        val command = "Spring 레벨 7 루트를 만들고 바로 공부하자."
        f.utterance(1, "root-level-race", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootRequest("Spring", 7, command).copy(startLessonAfterCreate = true),
        )
        f.confirm(f.publications().last(), persisted = true)
        val createId = f.startServerToolCall(f.awaitServerToolCall("create_root_study"))
        val createOutput = f.completeStartedServerToolCall(
            createId,
            VoiceTutorMcpToolResult(
                output = """{"created":true,"id":901,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                studyTreeChanged = true,
                createdStudyId = 901,
                changedStudyId = 901,
                changeKind = VoiceTutorStudyChangeKind.CREATED,
                rootStudyReadbackId = 901,
            ),
        )
        val readId = f.startServerToolCall(f.awaitServerToolCall("get_study"))
        val readOutput = f.completeStartedServerToolCall(
            readId,
            VoiceTutorMcpToolResult(
                output = """{"id":901,"parentStudyId":null,"topic":"Spring","difficultyLevel":7,"enabled":true,"activeForQuestions":true}""",
                isError = false,
                candidateDiscovery = exactRootReadback(901, "Spring"),
            ),
        )
        val focusId = f.startServerToolCall(f.awaitServerToolCall("select_voice_study"))
        val focusOutput = f.completeStartedServerToolCall(
            focusId,
            VoiceTutorMcpToolResult(
                output = """{"selected":true}""",
                isError = false,
                lessonRevision = 1,
                lessonFocus = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(901, 1),
                    VoiceTutorStudySnapshot(901, null, "Spring", 8, 1),
                ),
            ),
        )

        assertThat(f.controller.shouldRelayLessonFocusEvent(focusId)).isFalse()
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerLessonRevision).isZero()
        listOf(createOutput, readOutput, focusOutput).forEach(f::acknowledgeConversationItem)

        val followup = f.awaitResponseCount(2).last()
        assertThat(followup.path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(followup.path("response").path("instructions").asText())
            .contains("could not safely confirm", "do not claim that teaching started")
            .doesNotContain("authorized exactly one substantive study question")
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `only the newest exact server child call receives its persisted one shot lease`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        val command = "여기서 Streams를 새 하위 주제로 공부하고 싶어"

        f.utterance(2, "child-a", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
            childStudyCreationRequest = childRequest(101L, "Streams", command),
        )
        f.confirm(f.publications().last(), persisted = true)
        val firstCall = f.awaitServerToolCall("create_study_topic")

        f.utterance(3, "child-b", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
            childStudyCreationRequest = childRequest(101L, "Streams", command),
        )
        f.confirm(f.publications().last(), persisted = true)
        val calls = f.conversationItems().filter {
            it.path("item").path("name").asText() == "create_study_topic"
        }
        assertThat(calls).hasSize(2)
        val secondCall = calls.last()
        assertThat(mapper.readTree(secondCall.path("item").path("arguments").asText())).isEqualTo(
            mapper.readTree("""{"parent_study_id":101,"topic":"Streams","difficulty_level":5}"""),
        )
        val newestLease = f.controller.mutationDialogueBoundary().childStudyCreationAuthorization
        assertThat(newestLease).isNotNull

        f.acknowledgeConversationItem(firstCall)
        val firstCallId = firstCall.path("item").path("call_id").asText()
        assertThat(f.controller.beginToolExecution(firstCallId)).isTrue()
        assertThat(f.controller.mutationDialogueBoundary(firstCallId).childStudyCreationAuthorization).isNull()

        f.acknowledgeConversationItem(secondCall)
        val secondCallId = secondCall.path("item").path("call_id").asText()
        assertThat(f.controller.beginToolExecution(secondCallId)).isTrue()
        assertThat(f.controller.mutationDialogueBoundary(secondCallId).childStudyCreationAuthorization)
            .isSameAs(newestLease)
        assertThat(newestLease?.consume()).isTrue()
        assertThat(newestLease?.consume()).isFalse()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `persisted natural update schedules only its exact patch and binds the lease to that call`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        val command = "이 주제 레벨을 6으로 바꾸고 싶어"
        f.utterance(2, "update-focus", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.UPDATE_STUDY,
            studyUpdateRequest = updateRequest(101L, command, difficulty = 6),
        )
        f.confirm(f.publications().last(), persisted = true)

        val call = f.awaitServerToolCall("update_study")
        assertThat(mapper.readTree(call.path("item").path("arguments").asText())).isEqualTo(
            mapper.readTree("""{"study_id":101,"difficulty_level":6}"""),
        )
        f.acknowledgeConversationItem(call)
        val callId = call.path("item").path("call_id").asText()
        assertThat(f.controller.beginToolExecution(callId)).isTrue()
        val claimedLease = requireNotNull(
            f.controller.mutationDialogueBoundary(callId).studyUpdateAuthorization,
        )
        assertThat(claimedLease).isSameAs(f.controller.mutationDialogueBoundary().studyUpdateAuthorization)

        // The controller claim wins before this new meaningful turn. Its frozen
        // boundary and adapter permit survive global owner retirement, exactly once.
        f.start(3)
        f.stop(3)
        f.commit("after-update-claim")
        f.transcript("after-update-claim", "아니, 그 수정은 그대로 진행해 줘.")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE)
        f.confirm(f.publications().last(), persisted = true)
        assertThat(f.controller.mutationDialogueBoundary().studyUpdateAuthorization).isNull()
        assertThat(f.controller.mutationDialogueBoundary(callId).studyUpdateAuthorization)
            .isSameAs(claimedLease)
        assertThat(claimedLease.consume()).isTrue()
        assertThat(claimedLease.consume()).isFalse()
        assertThat(f.controller.beginToolExecution(callId)).isFalse()
        assertThat(f.controller.completeToolExecution(
            callId,
            VoiceTutorMcpToolResult(
                output = "{}",
                isError = false,
                studyTreeChanged = true,
                changedStudyId = 101,
                changeKind = VoiceTutorStudyChangeKind.UPDATED,
                lessonRevision = 2,
                lessonFocus = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(101, 2),
                    VoiceTutorStudySnapshot(101, null, "Redis", 6, 2),
                ),
            ),
        )).isTrue()
        assertThat(f.controller.mutationDialogueBoundary(callId).studyUpdateAuthorization).isNull()
        f.utterance(4, "after-update", "이 주제 이름을 Redis 2로 바꾸고 싶어")
        val refreshedMutationContext = f.assessments().last().utterances.single().studyMutationContext
        assertThat(refreshedMutationContext?.lessonRevision).isEqualTo(2)
        assertThat(refreshedMutationContext?.currentFocusStudyId).isEqualTo(101)
        assertThat(refreshedMutationContext?.candidates?.single()?.topic).isEqualTo("Redis")
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `spoken readback of current focus keeps richer identity for a name and level update`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        f.utterance(2, "read-current-focus", "현재 선택된 주제를 확인해 줘")
        f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
        f.confirm(f.publications().last(), persisted = true)
        f.finishCurrentResponseWithCandidateReads(
            discoveries = listOf(VoiceTutorCandidateDiscovery(
                VoiceTutorCandidateReadKind.LIST_STUDIES,
                lessonRevision = 1,
                currentFocusStudyId = 101,
                candidates = listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis")),
                scope = VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 3, 1),
            )),
            spokenTranscript = "현재 선택된 Redis 주제는 레벨 5입니다.",
        )

        val command = "Redis 이름을 Redis 기초로 바꾸고 레벨을 6으로 수정해 줘"
        f.utterance(3, "rename-current-focus", command)
        val mutationContext = f.assessments().last().utterances.single().studyMutationContext
        assertThat(mutationContext?.currentFocusStudyId).isEqualTo(101)
        assertThat(mutationContext?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(101, null, "Redis", difficulty = 5),
        )
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.UPDATE_STUDY,
            studyUpdateRequest = updateRequest(
                101,
                command,
                topic = "Redis 기초",
                difficulty = 6,
                targetTopic = "Redis",
                targetImplicitCurrentFocus = false,
            ),
        )
        f.confirm(f.publications().last(), persisted = true)

        val updateCall = f.awaitServerToolCall("update_study")
        assertThat(mapper.readTree(updateCall.path("item").path("arguments").asText())).isEqualTo(
            mapper.readTree("""{"study_id":101,"topic":"Redis 기초","difficulty_level":6}"""),
        )
        val authorization = f.controller.mutationDialogueBoundary().studyUpdateAuthorization
        assertThat(authorization?.targetProof)
            .isEqualTo(VoiceTutorStudyUpdateTargetProof(101, null, "Redis", difficulty = 5))

        val responseCountBeforeUpdate = f.responses().size
        val callId = f.startServerToolCall(updateCall)
        val output = f.completeStartedServerToolCall(
            callId,
            VoiceTutorMcpToolResult(
                output = """{"id":101,"parentStudyId":null,"topic":"Redis 기초","difficultyLevel":6,"voiceLessonChangeApplies":"NEXT_QUESTION"}""",
                isError = false,
                studyTreeChanged = true,
                changedStudyId = 101,
                changeKind = VoiceTutorStudyChangeKind.UPDATED,
                lessonRevision = 2,
                lessonFocus = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(101, 2),
                    VoiceTutorStudySnapshot(101, null, "Redis 기초", 6, 2),
                ),
                updatedStudySnapshot = VoiceTutorStudySnapshot(101, null, "Redis 기초", 6, 2),
            ),
        )
        f.acknowledgeConversationItem(output)

        val followup = f.awaitResponseCount(responseCountBeforeUpdate + 1).last()
        assertThat(followup.path("response").path("instructions").asText())
            .doesNotContain("start that topic now", "ask only whether")
        f.finishCurrentSpokenOffer("Redis 기초로 변경했어요.")
        f.utterance(4, "after-current-focus-update", "계속하자")
        assertThat(f.assessments().last().utterances.single().targetOffer).isNull()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `a committed update without a frozen new revision clears stale lesson focus`() = fixture().use { f ->
        f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
        val command = "이 주제 레벨을 6으로 바꾸고 싶어"
        f.utterance(2, "update-without-context", command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            VoiceTutorInputIntent.UPDATE_STUDY,
            studyUpdateRequest = updateRequest(101L, command, difficulty = 6),
        )
        f.confirm(f.publications().last(), persisted = true)

        val call = f.awaitServerToolCall("update_study")
        f.acknowledgeConversationItem(call)
        val callId = call.path("item").path("call_id").asText()
        assertThat(f.controller.beginToolExecution(callId)).isTrue()
        assertThat(f.controller.completeToolExecution(
            callId,
            VoiceTutorMcpToolResult(
                output = "{}",
                isError = false,
                studyTreeChanged = true,
                changedStudyId = 101,
                changeKind = VoiceTutorStudyChangeKind.UPDATED,
                lessonRevision = null,
                lessonFocus = null,
            ),
        )).isTrue()

        f.utterance(3, "after-unfrozen-update", "이어서 공부하자")
        assertThat(f.assessments().last().utterances.single().studyMutationContext).isNull()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `a newer overlapping speech edge prevents older root child and update turns from minting leases`() {
        assertOverlappingNewerSpeechCannotMintMutation(
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            "Kotlin 레벨 6 루트로 만들어줘",
            rootRequest = rootRequest("Kotlin", 6, "Kotlin 레벨 6 루트로 만들어줘"),
        )
        assertOverlappingNewerSpeechCannotMintMutation(
            VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
            "여기서 Streams를 하위 주제로 공부하고 싶어",
            childRequest = childRequest(101L, "Streams", "여기서 Streams를 하위 주제로 공부하고 싶어"),
        )
        assertOverlappingNewerSpeechCannotMintMutation(
            VoiceTutorInputIntent.UPDATE_STUDY,
            "이 주제 레벨을 6으로 바꾸고 싶어",
            updateRequest = updateRequest(101L, "이 주제 레벨을 6으로 바꾸고 싶어", difficulty = 6),
        )
    }

    @Test
    fun `direct root server call queues behind an existing read and duplicate ACK never replays it`() =
        fixture().use { f ->
            val readStarted = CountDownLatch(1)
            val rootStarted = CountDownLatch(1)
            val readResult = CompletableDeferred<VoiceTutorMcpToolResult>()
            val invocations = CopyOnWriteArrayList<String>()
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName
                    return when (toolName) {
                        "get_study" -> {
                            when ((arguments["study_id"] as Number).toLong()) {
                                101L -> {
                                    readStarted.countDown()
                                    readResult.await()
                                }
                                888L -> VoiceTutorMcpToolResult(
                                    """{"id":888,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6}""",
                                    false,
                                    candidateDiscovery = exactRootReadback(888, "Kotlin"),
                                )
                                else -> error("unexpected get_study arguments: $arguments")
                            }
                        }
                        "create_root_study" -> {
                            rootStarted.countDown()
                            VoiceTutorMcpToolResult(
                                output = """{"created":true,"id":888,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                                isError = false,
                                studyTreeChanged = true,
                                createdStudyId = 888,
                                changedStudyId = 888,
                                changeKind = VoiceTutorStudyChangeKind.CREATED,
                                rootStudyReadbackId = 888,
                            )
                        }
                        else -> error("unexpected tool: $toolName")
                    }
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller,
                controlContext(),
                tools,
                { _, _, _ -> },
            ).subscribe({}, f.errors::add)
            try {
                f.utterance(1, "browse-before-create", "Redis를 찾아줘")
                f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
                f.confirm(f.publications().last(), persisted = true)
                val browseToken = f.responses().last().path("event_id").asText()
                f.controller.observeProviderEvent(f.response("response.created", "pending-read", browseToken))
                f.controller.observeProviderEvent(f.responseWithOutput(
                    "response.done",
                    "pending-read",
                    browseToken,
                    listOf(mapOf(
                        "type" to "function_call",
                        "status" to "completed",
                        "call_id" to "pending-read-call",
                        "name" to "get_study",
                        "arguments" to "{\"study_id\":101}",
                    )),
                ))
                assertThat(readStarted.await(3, TimeUnit.SECONDS)).isTrue()

                f.utterance(2, "create-while-read", "Kotlin 레벨 6 루트로 만들어줘")
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                    rootRequest("Kotlin", 6, "Kotlin 레벨 6 루트로 만들어줘"),
                )
                val rootPublication = f.publications().last()
                f.confirm(rootPublication, persisted = true)
                f.confirm(rootPublication, persisted = true)
                val rootCallItem = f.conversationItems().single {
                    it.path("item").path("type").asText() == "function_call" &&
                        it.path("item").path("name").asText() == "create_root_study"
                }
                f.acknowledgeConversationItem(rootCallItem)
                f.acknowledgeConversationItem(rootCallItem)
                assertThat(rootStarted.await(100, TimeUnit.MILLISECONDS)).isFalse()
                assertThat(invocations).containsExactly("get_study")

                readResult.complete(VoiceTutorMcpToolResult("""{"id":101,"topic":"Redis"}""", false))
                assertThat(rootStarted.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(invocations).containsExactly("get_study", "create_root_study")
                assertThat(invocations.count { it == "create_root_study" }).isEqualTo(1)

                val readOutput = f.awaitToolOutput("pending-read-call")
                val rootOutput = f.awaitToolOutput(rootCallItem.path("item").path("call_id").asText())
                val rootReadbackCall = f.awaitServerToolCall("get_study").takeIf {
                    mapper.readTree(it.path("item").path("arguments").asText()).path("study_id").asLong() == 888L
                } ?: error("missing exact root readback")
                assertThat(f.responses()).hasSize(2)
                f.acknowledgeConversationItem(rootReadbackCall)
                f.acknowledgeConversationItem(rootReadbackCall)
                val rootReadbackOutput = f.awaitToolOutput(
                    rootReadbackCall.path("item").path("call_id").asText(),
                )
                assertThat(invocations).containsExactly("get_study", "create_root_study", "get_study")
                f.acknowledgeConversationItem(rootReadbackOutput)
                f.acknowledgeConversationItem(readOutput)
                assertThat(f.responses()).hasSize(2)
                f.acknowledgeConversationItem(rootOutput)
                val responses = f.awaitResponseCount(3)
                assertThat(responses).hasSize(3)
                assertThat(responses.last().path("response").path("tool_choice").asText()).isEqualTo("none")
                assertThat(responses.last().path("response").path("instructions").asText())
                    .contains("Never call any tool", "exact get_study readback")
                assertThat(f.errors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }

    @Test
    fun `existing exact root uses server readback and the final audio response rejects mixed tool output`() =
        fixture().use { f ->
            val invocations = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
            val tools = object : VoiceTutorMcpToolPort {
                override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                override suspend fun execute(
                    context: VoiceTutorWebRtcControlContext,
                    toolName: String,
                    arguments: Map<String, Any>,
                ): VoiceTutorMcpToolResult {
                    invocations += toolName to arguments
                    return when (toolName) {
                        "create_root_study" -> VoiceTutorMcpToolResult(
                            output = """{"created":false,"id":779,"parentStudyId":null,"topic":"Spring Framework","difficultyLevel":4,"enabled":true,"activeForQuestions":true}""",
                            isError = false,
                            rootStudyReadbackId = 779,
                        )
                        "get_study" -> VoiceTutorMcpToolResult(
                            output = """{"id":779,"parentStudyId":null,"topic":"Spring Framework","difficultyLevel":4,"enabled":true,"activeForQuestions":true}""",
                            isError = false,
                            candidateDiscovery = exactRootReadback(779, "Spring Framework"),
                        )
                        else -> error("unexpected tool: $toolName")
                    }
                }
            }
            val worker = voiceTutorMcpToolRelay(
                f.controller, controlContext(), tools, { _, _, _ -> },
            ).subscribe({}, f.errors::add)
            try {
                f.utterance(1, "existing-spring", "spring framework 레벨 7 루트로 만들어줘")
                f.assess(
                    VoiceTutorInputDecision.MEANINGFUL,
                    VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                    rootRequest("spring framework", 7, "spring framework 레벨 7 루트로 만들어줘"),
                )
                f.confirm(f.publications().single(), persisted = true)

                val createCall = f.awaitServerToolCall("create_root_study")
                f.acknowledgeConversationItem(createCall)
                val createOutput = f.awaitToolOutput(createCall.path("item").path("call_id").asText())
                val readbackCall = f.awaitServerToolCall("get_study")
                assertThat(mapper.readTree(readbackCall.path("item").path("arguments").asText()))
                    .isEqualTo(mapper.readTree("""{"study_id":779}"""))
                f.acknowledgeConversationItem(readbackCall)
                val readbackOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
                f.acknowledgeConversationItem(createOutput)
                assertThat(f.responses()).hasSize(1)
                f.acknowledgeConversationItem(readbackOutput)

                val response = f.awaitResponseCount(2).last()
                assertThat(response.path("response").path("tool_choice").asText()).isEqualTo("none")
                assertThat(response.path("response").path("instructions").asText())
                    .contains("exact get_study readback", "Never call any tool")
                assertThat(invocations).containsExactly(
                    "create_root_study" to mapOf("topic" to "spring framework", "difficulty_level" to 7),
                    "get_study" to mapOf("study_id" to 779),
                )

                val responseToken = response.path("event_id").asText()
                f.controller.observeProviderEvent(f.response("response.created", "mixed-root-followup", responseToken))
                f.provider("output_audio_buffer.started", "response_id" to "mixed-root-followup")
                f.provider("response.output_audio.delta", "response_id" to "mixed-root-followup", "delta" to "AA==")
                assertThatThrownBy {
                    f.controller.observeProviderEvent(f.responseWithOutput(
                        "response.done",
                        "mixed-root-followup",
                        responseToken,
                        listOf(
                            mapOf(
                                "type" to "message",
                                "role" to "assistant",
                                "content" to listOf(mapOf("type" to "output_audio", "transcript" to "저장됐습니다.")),
                            ),
                            mapOf(
                                "type" to "function_call",
                                "status" to "completed",
                                "call_id" to "forbidden-followup-tool",
                                "name" to "get_study",
                                "arguments" to "{\"study_id\":779}",
                            ),
                        ),
                    ))
                }.isInstanceOf(VoiceTutorMcpProtocolException::class.java)
                assertThat(invocations).hasSize(2)
                assertThat(f.errors).isEmpty()
            } finally {
                worker.dispose()
            }
        }

    @Test
    fun `failed root readback never claims confirmation or retries an uncertain create`() = fixture().use { f ->
        val invocations = CopyOnWriteArrayList<String>()
        val tools = object : VoiceTutorMcpToolPort {
            override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

            override suspend fun execute(
                context: VoiceTutorWebRtcControlContext,
                toolName: String,
                arguments: Map<String, Any>,
            ): VoiceTutorMcpToolResult {
                invocations += toolName
                return when (toolName) {
                    "create_root_study" -> VoiceTutorMcpToolResult(
                        output = """{"created":true,"id":780,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                        isError = false,
                        studyTreeChanged = true,
                        createdStudyId = 780,
                        changedStudyId = 780,
                        changeKind = VoiceTutorStudyChangeKind.CREATED,
                        rootStudyReadbackId = 780,
                    )
                    "get_study" -> VoiceTutorMcpToolResult(
                        output = """{"error":{"code":"READ_UNAVAILABLE","message":"unavailable"}}""",
                        isError = true,
                    )
                    else -> error("unexpected tool: $toolName")
                }
            }
        }
        val worker = voiceTutorMcpToolRelay(
            f.controller, controlContext(), tools, { _, _, _ -> },
        ).subscribe({}, f.errors::add)
        try {
            f.utterance(1, "create-kotlin", "Kotlin 레벨 6 루트로 만들어줘")
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootRequest("Kotlin", 6, "Kotlin 레벨 6 루트로 만들어줘"),
            )
            f.confirm(f.publications().single(), persisted = true)

            val createCall = f.awaitServerToolCall("create_root_study")
            f.acknowledgeConversationItem(createCall)
            val createOutput = f.awaitToolOutput(createCall.path("item").path("call_id").asText())
            val readbackCall = f.awaitServerToolCall("get_study")
            f.acknowledgeConversationItem(readbackCall)
            val readbackOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
            f.acknowledgeConversationItem(readbackOutput)
            assertThat(f.responses()).hasSize(1)
            f.acknowledgeConversationItem(createOutput)

            val response = f.awaitResponseCount(2).last()
            assertThat(response.path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(response.path("response").path("instructions").asText())
                .contains("was not confirmed", "Never call any tool", "never retry")
                .doesNotContain("speak the exact saved root topic and level")
            assertThat(invocations).containsExactly("create_root_study", "get_study")
            assertThat(invocations.count { it == "create_root_study" }).isEqualTo(1)
            assertThat(f.errors).isEmpty()
        } finally {
            worker.dispose()
        }
    }

    @Test
    fun `renamed or relevelled root readback cannot confirm a different persisted snapshot`() {
        val mismatches = listOf(
            "Kotlin renamed" to 6,
            "Kotlin" to 7,
        )
        for ((readbackTopic, readbackDifficulty) in mismatches) {
            fixture().use { f ->
                val invocations = CopyOnWriteArrayList<String>()
                val tools = object : VoiceTutorMcpToolPort {
                    override fun definitions(): List<VoiceTutorMcpToolDefinition> = emptyList()

                    override suspend fun execute(
                        context: VoiceTutorWebRtcControlContext,
                        toolName: String,
                        arguments: Map<String, Any>,
                    ): VoiceTutorMcpToolResult {
                        invocations += toolName
                        return when (toolName) {
                            "create_root_study" -> VoiceTutorMcpToolResult(
                                output = """{"created":true,"id":781,"parentStudyId":null,"topic":"Kotlin","difficultyLevel":6,"enabled":true,"activeForQuestions":true}""",
                                isError = false,
                                studyTreeChanged = true,
                                createdStudyId = 781,
                                changedStudyId = 781,
                                changeKind = VoiceTutorStudyChangeKind.CREATED,
                                rootStudyReadbackId = 781,
                            )
                            "get_study" -> VoiceTutorMcpToolResult(
                                output = mapper.writeValueAsString(mapOf(
                                    "id" to 781,
                                    "parentStudyId" to null,
                                    "topic" to readbackTopic,
                                    "difficultyLevel" to readbackDifficulty,
                                )),
                                isError = false,
                                candidateDiscovery = exactRootReadback(781, readbackTopic),
                            )
                            else -> error("unexpected tool: $toolName")
                        }
                    }
                }
                val worker = voiceTutorMcpToolRelay(
                    f.controller, controlContext(), tools, { _, _, _ -> },
                ).subscribe({}, f.errors::add)
                try {
                    val command = "Kotlin 레벨 6 루트로 만들어줘"
                    f.utterance(1, "create-kotlin-mismatch", command)
                    f.assess(
                        VoiceTutorInputDecision.MEANINGFUL,
                        VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                        rootRequest("Kotlin", 6, command),
                    )
                    f.confirm(f.publications().single(), persisted = true)
                    val createCall = f.awaitServerToolCall("create_root_study")
                    f.acknowledgeConversationItem(createCall)
                    val createOutput = f.awaitToolOutput(createCall.path("item").path("call_id").asText())
                    val readbackCall = f.awaitServerToolCall("get_study")
                    f.acknowledgeConversationItem(readbackCall)
                    val readbackOutput = f.awaitToolOutput(readbackCall.path("item").path("call_id").asText())
                    f.acknowledgeConversationItem(readbackOutput)
                    f.acknowledgeConversationItem(createOutput)

                    val response = f.awaitResponseCount(2).last()
                    assertThat(response.path("response").path("tool_choice").asText()).isEqualTo("none")
                    assertThat(response.path("response").path("instructions").asText())
                        .contains("was not confirmed", "never retry")
                        .doesNotContain("speak the exact saved root topic and level")
                    assertThat(invocations).containsExactly("create_root_study", "get_study")
                    assertThat(invocations.count { it == "create_root_study" }).isEqualTo(1)
                    assertThat(f.errors).isEmpty()
                } finally {
                    worker.dispose()
                }
            }
        }
    }

    @Test
    fun `generic affirmative never mints direct root creation authority`() =
        fixture().use { f ->
            f.utterance(1, "generic-yes", "네")
            // Even a faulty semantic-provider result cannot turn the affirmative
            // itself into both the root topic and an operative create command.
            f.assess(
                VoiceTutorInputDecision.MEANINGFUL,
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                VoiceTutorRootStudyCreationRequest(
                    "네",
                    5,
                    VoiceTutorRootStudyCreationEvidence(
                        VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
                        "네",
                        "네",
                        null,
                        true,
                    ),
                ),
            )
            assertThat(f.publications()).isEmpty()
            val boundary = f.controller.mutationDialogueBoundary()
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.NONE)
            assertThat(boundary.rootStudyCreationAuthorization).isNull()
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
        fixture().use { f ->
            f.establishVerifiedStudyQuestion(
                question = "Redis에서 메모리를 확보하는 이유는 무엇인가요?",
            )

            f.utterance(2, "study-answer-item", "메모리 공간을 확보하기 위해서예요")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            val answer = f.publications().last()
            f.confirmStudyAnswer(answer)
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis cache")
            f.finishCurrentResponseWithCandidateReads(
                listOf(VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 1, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                )),
                spokenTranscript = "핵심을 잘 설명했어요. 다음으로 Redis cache를 공부해 볼까요?",
                spokenProviderItemId = "tutor-feedback-item",
            )
            assertThat(f.feedbackAssessments.last().allowsNavigationOffer).isTrue()
            f.approveLatestFeedback()

            f.utterance(3, "continue-item", "그 하위 주제로 더 들어가자")
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
        fixture().use { f ->
            f.establishVerifiedStudyQuestion(
                question = "Redis에서 메모리를 확보하는 이유는 무엇인가요?",
            )

            f.utterance(2, "study-answer-item", "메모리 공간을 확보하기 위해서예요")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirmStudyAnswer(f.publications().last())
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis cache")
            f.finishCurrentSpokenOffer(
                spokenTranscript = "핵심을 정확히 설명했어요.",
                spokenProviderItemId = "tutor-feedback-item",
            )
            assertThat(f.feedbackAssessments.last().allowsNavigationOffer).isFalse()
            f.approveLatestFeedback()
            f.utterance(3, "browse-child", "Redis 하위 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 1, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
            )
            f.finishCurrentSpokenOffer(
                spokenTranscript = "다음으로 Redis cache를 공부해 볼까요?",
                spokenProviderItemId = "tutor-offer-item",
            )

            f.utterance(4, "continue-item", "응, 그 하위 주제로 가자")
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
        fixture().use { f ->
            f.establishVerifiedStudyQuestion(
                question = "Redis의 메모리 회수 정책을 설명해 보세요.",
            )

            f.utterance(2, "study-answer-item", "LRU는 오래 사용하지 않은 키를 제거합니다")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.confirmStudyAnswer(f.publications().last())
            val child = VoiceTutorStudyTargetCandidate(102, 101, "Redis eviction")
            f.finishCurrentSpokenOffer(
                spokenTranscript = "LRU 설명이 정확해요.",
                spokenProviderItemId = "tutor-feedback-item",
            )
            f.approveLatestFeedback()
            f.utterance(3, "browse-child", "Redis 하위 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)
            f.finishCurrentResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 1, 101, listOf(child),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 1),
                ),
            )

            f.finishOfferAfterStopWithCompletedLearnerSpeech(
                sequence = 4,
                spokenTranscript = "이제 Redis eviction으로 더 내려갈까요?",
                spokenProviderItemId = "tutor-offer-item",
            )
            assertThat(f.inputCommits()).hasSize(4)
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
    fun `stopped then done target offer uses content index order instead of transcript arrival order`() =
        fixture().use { f ->
            f.utterance(1, "browse-ordered-offer", "Redis 주제를 찾아줘")
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC)
            f.confirm(f.publications().last(), persisted = true)

            val root = VoiceTutorStudyTargetCandidate(101, null, "Redis")
            f.finishCurrentResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, listOf(root),
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 3, 1),
                ),
            )
            f.finishCurrentResponseWithCandidateRead(
                VoiceTutorCandidateDiscovery(
                    VoiceTutorCandidateReadKind.LIST_STUDIES, 0, null, emptyList(),
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 3, 0),
                ),
            )

            val token = f.responses().last().path("event_id").asText()
            val responseId = "reversed-parts-offer"
            f.controller.observeProviderEvent(f.response("response.created", responseId, token))
            f.provider("output_audio_buffer.started", "response_id" to responseId)
            f.provider("response.output_audio.delta", "response_id" to responseId, "delta" to "AA==")
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to responseId,
                "item_id" to "ordered-offer-item",
                "content_index" to 1,
                "transcript" to "part one",
            )
            f.provider(
                "response.output_audio_transcript.done",
                "response_id" to responseId,
                "item_id" to "ordered-offer-item",
                "content_index" to 0,
                "transcript" to "part zero",
            )
            f.provider("output_audio_buffer.stopped", "response_id" to responseId)
            f.controller.observeProviderEvent(
                f.responseWithOutput(
                    "response.done",
                    responseId,
                    token,
                    listOf(mapOf(
                        "type" to "message",
                        "role" to "assistant",
                        "content" to listOf(mapOf("transcript" to "fallback must not win")),
                    )),
                ),
            )

            f.utterance(2, "select-ordered-offer", "응, 그 주제로 가자")
            val assessment = f.assessments().last()
            assertThat(assessment.teacherContext).isEqualTo("part zero\npart one")
            assertThat(assessment.utterances.single().targetOffer?.tutorAudioTranscript)
                .isEqualTo("part zero\npart one")
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
    fun `spoken lesson end assessment and lifecycle wait for the active tutor sentence boundary`() =
        fixture(finishOpening = false).use { f ->
            f.utterance(1, "persisted-end", "학습 끝낼게")
            f.openingDone()
            assertThat(f.assessments()).isEmpty()
            assertThat(f.serverLifecycleTypes()).isEmpty()
            assertThat(f.responses()).hasSize(1)
            f.start(1) // Duplicate/stale speech generation cannot retract a valid END.
            f.provider("output_audio_buffer.stopped", "response_id" to "wrong-response")
            assertThat(f.assessments()).isEmpty()
            assertThat(f.serverLifecycleTypes()).isEmpty()

            f.openingStopped()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val publication = f.publications().single()
            f.controller.confirmInputPublished(publication.itemId, persisted = true)
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
            assertThat(f.assessments()).isEmpty()
            f.openingStopped()
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
            assertThat(f.controller.acceptsInputEvents()).isTrue()
            f.assertNoAudioDisruption()
        }

        fixture(finishOpening = false).use { f ->
            f.utterance(1, "old-end", "학습 끝낼게")
            f.openingDone()
            assertThat(f.assessments()).isEmpty()
            f.openingStopped()
            f.assess(VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON)
            val oldPublication = f.publications().single()
            f.start(2)
            f.controller.confirmInputPublished(oldPublication.itemId, persisted = true)
            f.stop(2)
            f.commit("noise")
            f.transcript("noise", "어… 음…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("noise")

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
            assertThat(f.assessments()).isEmpty()
            f.openingStopped()
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
        assertThat(f.assessments()).isEmpty()
        f.openingStopped()
        assertThat(f.assessments().single().teacherContext).isEqualTo("학습을 시작할 준비가 됐나요?")
        f.assess(VoiceTutorInputDecision.MEANINGFUL)
        f.publishAll()
        assertThat(f.responses()).hasSize(2)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `provider failure during learner overlap abandons old retry and keeps failed tutor context unassessed`() =
        fixture(finishOpening = false).use { f ->
            val clientEvents = CopyOnWriteArrayList<String>()
            val client = f.controller.clientEvents().subscribe(clientEvents::add, f.errors::add)
            try {
                f.utterance(1, "before-failure", "첫 문장")
                f.openingDone()
                assertThat(f.assessments()).isEmpty()
                assertThat(f.publications()).isEmpty()

                f.start(2)
                assertThat(f.provider("output_audio_buffer.cleared", "response_id" to "opening"))
                    .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
                assertThat(f.responses()).hasSize(1)
                assertThat(f.responses().map { it.path("event_id").asText() })
                    .noneMatch { it.contains("turn-retry") }
                assertThat(clientEvents).isEmpty()
                assertThat(f.publications()).isEmpty()

                f.stop(2)
                f.commit("after-failure")
                f.transcript("after-failure", "새 문장")

                val restoredAssessment = f.assessments().first()
                assertThat(restoredAssessment.utterances.map { it.itemId }).containsExactly("before-failure")
                f.controller.completeInputAssessment(
                    restoredAssessment.token,
                    f.result(restoredAssessment, VoiceTutorInputDecision.MEANINGFUL),
                )
                val restoredPublication = f.publications().single()
                f.confirm(restoredPublication, persisted = true)

                val freshAssessment = f.assessments().last()
                assertThat(freshAssessment.token).isNotEqualTo(restoredAssessment.token)
                assertThat(freshAssessment.utterances.map { it.itemId }).containsExactly("after-failure")
                f.controller.completeInputAssessment(
                    freshAssessment.token,
                    f.result(freshAssessment, VoiceTutorInputDecision.MEANINGFUL),
                )
                f.confirm(f.publications().last(), persisted = true)

                assertThat(f.responses()).hasSize(2)
                assertThat(f.responses().last().path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-turn-response-")
                    .doesNotContain("turn-retry")
                assertThat(f.errors).isEmpty()
                assertThat(f.controller.acceptsInputEvents()).isTrue()
                f.assertNoAudioDisruption()
            } finally {
                client.dispose()
            }
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
        // The checkpoint is durable context only; deleting the empty final
        // tail must not fabricate a completed learner turn or tutor response.
        assertThat(f.responses()).hasSize(1)
        f.assertNoAudioDisruption()
    }

    @Test
    fun `only exact owned empty commit error releases its slot without ending the call`() = fixture().use { f ->
        f.start(1)
        f.stop(1)
        val commitId = f.inputCommits().single().path("event_id").asText()
        val unknown = f.emptyCommit("not-owned")
        assertThatThrownBy { f.controller.observeProviderEvent(unknown) }
            .isInstanceOf(VoiceTutorProviderProtocolException::class.java)
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
        // The coordinator owns the same full lifetime as the use case:
        // bounded admission (1.5 s) followed by provider work (10 s).
        f.now.addAndGet(Duration.ofMillis(11_500).toNanos())
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
                assertThat(classifierEntered.await(100, TimeUnit.MILLISECONDS)).isFalse()
                f.openingStopped()
                assertThat(classifierEntered.await(2, TimeUnit.SECONDS)).isTrue()
                // A later provider frame must still be processed while the
                // classifier is waiting outside the ordered receive path.
                assertThat(f.provider("output_audio_buffer.stopped", "response_id" to "stale-response"))
                    .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
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

    @Test
    fun `the production input worker stamps only a final assessed learner study question`() {
        val published = AtomicReference<com.fasterxml.jackson.databind.JsonNode>()
        val workerErrors = CopyOnWriteArrayList<Throwable>()
        fixture(captureInputActions = false).use { f ->
            val worker = voiceTutorInputAssessmentRelay(
                f.controller, userId = 42, language = "ko",
                assessment = object : VoiceTutorInputAssessmentUseCase {
                    override suspend fun assess(request: VoiceTutorInputAssessmentRequest) =
                        VoiceTutorInputAssessmentResult(request.utterances.map {
                            VoiceTutorInputItemAssessment(
                                it.itemId,
                                VoiceTutorInputDecision.MEANINGFUL,
                                VoiceTutorInputIntent.ASK_STUDY_QUESTION,
                            )
                        })
                },
            ) { raw, persist, forward ->
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                published.set(mapper.readTree(raw))
                true
            }.subscribe({}, workerErrors::add)
            try {
                f.utterance(1, "learner-question", "LFU는 빈도를 어떻게 추적하나요?")

                assertThat(f.nextResponse.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(VoiceTutorTranscriptMetadata.askedStudyQuestion(published.get())).isTrue()
                assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(published.get())).isNull()
                assertThat(workerErrors).isEmpty()
                f.assertNoAudioDisruption()
            } finally {
                worker.dispose()
            }
        }
    }

    private fun fixture(
        finishOpening: Boolean = true,
        captureInputActions: Boolean = true,
        controlDemand: Long = Long.MAX_VALUE,
        initialStudyMutationSnapshot: VoiceTutorInitialStudyMutationSnapshot? = null,
    ) = Fixture(finishOpening, captureInputActions, controlDemand, initialStudyMutationSnapshot)

    private fun assertOverlappingNewerSpeechCannotMintMutation(
        intent: VoiceTutorInputIntent,
        command: String,
        rootRequest: VoiceTutorRootStudyCreationRequest? = null,
        childRequest: VoiceTutorChildStudyCreationRequest? = null,
        updateRequest: VoiceTutorStudyUpdateRequest? = null,
    ) = fixture().use { f ->
        val sequence = if (intent == VoiceTutorInputIntent.CREATE_ROOT_STUDY) 1L else {
            f.establishVerifiedStudyQuestion(question = "Redis의 eviction 정책을 설명해 보세요.")
            2L
        }
        f.start(sequence)
        f.start(sequence + 1) // newer acoustic evidence arrives while the older buffer is active
        f.stop(sequence)
        val itemId = "overlapped-${intent.name.lowercase()}"
        f.commit(itemId)
        f.transcript(itemId, command)
        f.assess(
            VoiceTutorInputDecision.MEANINGFUL,
            intent,
            rootStudyCreationRequest = rootRequest,
            childStudyCreationRequest = childRequest,
            studyUpdateRequest = updateRequest,
        )
        f.confirm(f.publications().last(), persisted = true)

        val boundary = f.controller.mutationDialogueBoundary()
        assertThat(boundary.rootStudyCreationAuthorization).isNull()
        assertThat(boundary.childStudyCreationAuthorization).isNull()
        assertThat(boundary.studyUpdateAuthorization).isNull()
        assertThat(f.conversationItems().filter {
            it.path("item").path("type").asText() == "function_call" &&
                it.path("item").path("name").asText() in
                setOf("create_root_study", "create_study_topic", "update_study")
        }).isEmpty()
        assertThat(f.errors).isEmpty()
    }

    private fun rootRequest(
        topic: String,
        difficulty: Int,
        command: String,
        omitted: Boolean = false,
    ) = VoiceTutorRootStudyCreationRequest(
        topic,
        difficulty,
        VoiceTutorRootStudyCreationEvidence(
            VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
            command,
            topic,
            difficulty.takeUnless { omitted }?.toString(),
            omitted,
        ),
    )

    private fun childRequest(
        parentStudyId: Long,
        topic: String,
        command: String,
        difficulty: Int = 5,
        omitted: Boolean = true,
    ) = VoiceTutorChildStudyCreationRequest(
        parentStudyId,
        topic,
        difficulty,
        VoiceTutorChildStudyCreationEvidence(
            source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
            command = command,
            parentTopic = null,
            topic = topic,
            difficulty = difficulty.takeUnless { omitted }?.toString(),
            difficultyOmitted = omitted,
            parentImplicitCurrentFocus = true,
        ),
    )

    private fun updateRequest(
        studyId: Long,
        command: String,
        topic: String? = null,
        difficulty: Int? = null,
        targetTopic: String? = null,
        targetImplicitCurrentFocus: Boolean = true,
        startLessonAfterUpdate: Boolean = false,
    ) = VoiceTutorStudyUpdateRequest(
        studyId,
        topic,
        difficulty,
        VoiceTutorStudyUpdateEvidence(
            source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
            command = command,
            targetTopic = targetTopic,
            topic = topic,
            difficulty = difficulty?.toString(),
            targetImplicitCurrentFocus = targetImplicitCurrentFocus,
        ),
        startLessonAfterUpdate = startLessonAfterUpdate,
    )

    private fun exactRootReadback(id: Long, topic: String) = VoiceTutorCandidateDiscovery(
        source = VoiceTutorCandidateReadKind.GET_STUDY,
        lessonRevision = 0,
        currentFocusStudyId = null,
        candidates = listOf(VoiceTutorStudyTargetCandidate(id, null, topic)),
        scope = VoiceTutorCandidateDiscoveryScope.ExactStudy(id),
    )

    private fun controlContext(): VoiceTutorWebRtcControlContext {
        val now = Instant.parse("2026-08-31T00:00:00Z")
        return VoiceTutorWebRtcControlContext(
            session = VoiceTutorSession(
                id = "synthetic-root-session",
                userId = 7,
                studyId = null,
                idempotencyKey = "synthetic-root-call",
                providerSessionId = "rtc_synthetic_root",
                status = VoiceTutorSessionStatus.ACTIVE,
                resultStatus = VoiceTutorResultStatus.PENDING,
                language = "ko",
                model = "gpt-realtime",
                voice = "marin",
                topic = "AI 음성 튜터",
                difficulty = 5,
                periodStartedAt = now,
                periodEndsAt = now.plusSeconds(86_400),
                reservedSeconds = 3_600,
                chargedSeconds = 0,
                maxSessionSeconds = 3_600,
                hardEndsAt = now.plusSeconds(3_600),
                connectedAt = now,
                relayHeartbeatAt = now,
                acceptedAudioBytes = 0,
                endedAt = null,
                finalizedAt = null,
                endReason = null,
                failureCode = null,
                failureMessage = null,
                createdAt = now,
                updatedAt = now,
            ),
            callId = "rtc_synthetic_root",
        )
    }

    private inner class Fixture(
        finishOpening: Boolean,
        captureInputActions: Boolean,
        controlDemand: Long,
        initialStudyMutationSnapshot: VoiceTutorInitialStudyMutationSnapshot?,
    ) : AutoCloseable {
        val now = AtomicLong()
        val controls = CopyOnWriteArrayList<String>()
        val serverLifecycle = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val questionAssessments = CopyOnWriteArrayList<VoiceTutorSpokenQuestionAssessmentAction>()
        val feedbackAssessments = CopyOnWriteArrayList<VoiceTutorSpokenFeedbackAssessmentAction>()
        val diagnostics = CopyOnWriteArrayList<VoiceTutorProviderTurnFailureDiagnostic>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val nextResponse = CountDownLatch(1)
        private val controlArrived = Semaphore(0)
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
            toolsEnabled = true,
            initialStudyMutationSnapshot = initialStudyMutationSnapshot,
            onProviderTurnFailure = diagnostics::add,
        )
        private var syntheticToolSequence = 0L
        private val controlSubscription: Disposable
        private val serverLifecycleSubscription: Disposable
        private val workSubscription: Disposable?
        private val questionAssessmentSubscription: Disposable
        private val feedbackAssessmentSubscription: Disposable
        private val openingToken: String

        init {
            controlSubscription = controller.providerEvents().subscribeWith(object : BaseSubscriber<String>() {
                override fun hookOnSubscribe(subscription: Subscription) { request(controlDemand) }
                override fun hookOnNext(raw: String) {
                    controls += raw
                    controlArrived.release()
                    if (responses().size > 1) nextResponse.countDown()
                }
                override fun hookOnError(throwable: Throwable) { errors += throwable }
            })
            serverLifecycleSubscription = controller.serverLifecycleEvents().subscribe(serverLifecycle::add, errors::add)
            workSubscription = if (captureInputActions) controller.inputActions().subscribe(actions::add, errors::add) else null
            questionAssessmentSubscription =
                controller.spokenQuestionAssessmentActions().subscribe(questionAssessments::add, errors::add)
            feedbackAssessmentSubscription =
                controller.spokenFeedbackAssessmentActions().subscribe(feedbackAssessments::add, errors::add)
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
            rootStudyCreationRequest: VoiceTutorRootStudyCreationRequest? = null,
            childStudyCreationRequest: VoiceTutorChildStudyCreationRequest? = null,
            studyUpdateRequest: VoiceTutorStudyUpdateRequest? = null,
        ) {
            val batch = assessments().last()
            controller.completeInputAssessment(
                batch.token,
                result(
                    batch,
                    decision,
                    intent,
                    rootStudyCreationRequest,
                    childStudyCreationRequest,
                    studyUpdateRequest,
                ),
            )
        }
        fun result(
            batch: VoiceTutorInputTurnCoordinator.Action.Assess,
            decision: VoiceTutorInputDecision,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
            rootStudyCreationRequest: VoiceTutorRootStudyCreationRequest? = null,
            childStudyCreationRequest: VoiceTutorChildStudyCreationRequest? = null,
            studyUpdateRequest: VoiceTutorStudyUpdateRequest? = null,
        ) = Result.success(
            VoiceTutorInputAssessmentResult(batch.utterances.map {
                val target = if (intent == VoiceTutorInputIntent.SELECT_SAVED_TOPIC ||
                    intent == VoiceTutorInputIntent.CONTINUE_TREE
                ) it.targetOffer?.candidates?.singleOrNull()?.studyId else null
                VoiceTutorInputItemAssessment(
                    it.itemId, decision, intent, target,
                    spokenCandidateStudyIds = target?.let(::listOf).orEmpty(),
                    rootStudyCreationRequest = rootStudyCreationRequest,
                    currentTranscriptAnswersStudyQuestion =
                        intent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
                    childStudyCreationRequest = childStudyCreationRequest,
                    studyUpdateRequest = studyUpdateRequest,
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
        fun confirmStudyAnswer(publication: VoiceTutorInputTurnCoordinator.Action.Publish) {
            controller.providerEventForRelay(
                publication.rawEvent,
                verifiedStudyAnswer = true,
                speechSequence = publication.sequence,
                checkpoint = publication.checkpoint,
                currentTranscriptAnswersStudyQuestion =
                    publication.currentTranscriptAnswersStudyQuestion,
            )
            confirm(publication, persisted = true)
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
        fun dispatchModelToolCall(callId: String, name: String, arguments: String) {
            syntheticToolSequence += 1
            val responseId = "model-tool-$syntheticToolSequence"
            val token = responses().last().path("event_id").asText()
            controller.observeProviderEvent(response("response.created", responseId, token))
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                responseId,
                token,
                listOf(mapOf(
                    "type" to "function_call",
                    "status" to "completed",
                    "call_id" to callId,
                    "name" to name,
                    "arguments" to arguments,
                )),
            ))
        }
        fun latestFocusTargetOfferForRace(): VoiceTutorStudyTargetOffer {
            val bindingField = controller.javaClass.getDeclaredField("latestFocusIntentBinding")
                .also { it.isAccessible = true }
            val binding = requireNotNull(bindingField.get(controller))
            val offerField = binding.javaClass.getDeclaredField("targetOffer")
                .also { it.isAccessible = true }
            return requireNotNull(offerField.get(binding) as? VoiceTutorStudyTargetOffer)
        }
        fun restoreTargetOfferToActiveSpeechForRace(offer: VoiceTutorStudyTargetOffer) {
            controller.javaClass.getDeclaredField("activeSpeechTargetOffer")
                .also { it.isAccessible = true }
                .set(controller, offer)
        }
        fun establishVerifiedStudyQuestion(
            sequence: Long = 1,
            question: String,
            providerItemId: String = "study-question-item",
        ) {
            utterance(sequence, "focus-request-$sequence", "Redis 주제로 학습을 시작해 줘")
            assess(VoiceTutorInputDecision.MEANINGFUL)
            confirm(publications().last(), persisted = true)

            syntheticToolSequence += 1
            val suffix = syntheticToolSequence
            val focusToken = responses().last().path("event_id").asText()
            val focusResponseId = "focus-$suffix"
            val focusCallId = "focus-call-$suffix"
            controller.observeProviderEvent(response("response.created", focusResponseId, focusToken))
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                focusResponseId,
                focusToken,
                listOf(mapOf(
                    "type" to "function_call",
                    "status" to "completed",
                    "call_id" to focusCallId,
                    "name" to "select_voice_study",
                    "arguments" to "{\"study_id\":101}",
                )),
            ))
            assertThat(controller.beginToolExecution(focusCallId)).isTrue()
            assertThat(controller.completeToolExecution(
                focusCallId,
                VoiceTutorMcpToolResult(
                    output = "{}",
                    isError = false,
                    lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(101, 1),
                        VoiceTutorStudySnapshot(101, null, "Redis", 5, 1),
                    ),
                ),
            )).isTrue()
            acknowledgeLatestToolOutput()

            val questionToken = responses().last().path("event_id").asText()
            val questionResponseId = "study-question-$suffix"
            controller.observeProviderEvent(response("response.created", questionResponseId, questionToken))
            provider("output_audio_buffer.started", "response_id" to questionResponseId)
            provider("response.output_audio.delta", "response_id" to questionResponseId, "delta" to "AA==")
            provider(
                "response.output_audio_transcript.done",
                "response_id" to questionResponseId,
                "item_id" to providerItemId,
                "content_index" to 0,
                "transcript" to question,
            )
            controller.observeProviderEvent(responseWithOutput(
                "response.done",
                questionResponseId,
                questionToken,
                listOf(mapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "content" to listOf(mapOf("transcript" to question)),
                )),
            ))
            provider("output_audio_buffer.stopped", "response_id" to questionResponseId)
            val assessment = questionAssessments.last()
            val boundary = requireNotNull(
                controller.completeSpokenQuestionAssessment(assessment.token, Result.success(true)),
            )
            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
        }
        fun approveLatestFeedback() {
            val assessment = feedbackAssessments.last()
            val boundary = requireNotNull(
                controller.completeSpokenFeedbackAssessment(assessment.token, Result.success(true)),
            )
            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
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
        fun finishCurrentResponseWithCandidateRead(discovery: VoiceTutorCandidateDiscovery) {
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
        fun acknowledgeLatestToolOutput() {
            val outputItem = controls.map(mapper::readTree)
                .last { it.path("type").asText() == "conversation.item.create" }
                .path("item").deepCopy<ObjectNode>()
            outputItem.put("status", "completed")
            provider("conversation.item.added", "item" to outputItem)
        }
        fun publishAll() { publications().forEach { controller.confirmInputPublished(it.itemId) } }
        fun responses() = controls.map(mapper::readTree).filter { it.path("type").asText() == "response.create" }
        fun conversationItems() = controls.map(mapper::readTree)
            .filter { it.path("type").asText() == "conversation.item.create" }
        fun acknowledgeConversationItem(event: com.fasterxml.jackson.databind.JsonNode) {
            val item = event.path("item").deepCopy<ObjectNode>()
            fProviderConversationItem(item)
        }
        fun rejectConversationItem(event: com.fasterxml.jackson.databind.JsonNode): VoiceTutorProviderRelayDisposition =
            provider(
                "error",
                "error" to mapOf(
                    "type" to "invalid_request_error",
                    "code" to "string_above_max_length",
                    "param" to "item.call_id",
                    "event_id" to event.path("event_id").asText(),
                    "message" to "private provider payload that must not be retained",
                ),
            )
        fun startServerToolCall(event: com.fasterxml.jackson.databind.JsonNode): String {
            acknowledgeConversationItem(event)
            val callId = event.path("item").path("call_id").asText()
            assertThat(controller.beginToolExecution(callId)).isTrue()
            return callId
        }
        fun completeStartedServerToolCall(
            callId: String,
            result: VoiceTutorMcpToolResult,
        ): com.fasterxml.jackson.databind.JsonNode {
            assertThat(controller.completeToolExecution(callId, result)).isTrue()
            return awaitToolOutput(callId)
        }
        fun awaitConversationItem(callId: String): com.fasterxml.jackson.databind.JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                conversationItems().lastOrNull { it.path("item").path("call_id").asText() == callId }
                    ?.let { return it }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && controlArrived.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    "Timed out waiting for provider conversation item."
                }
            }
        }
        fun awaitToolOutput(callId: String): com.fasterxml.jackson.databind.JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                conversationItems().lastOrNull {
                    it.path("item").path("type").asText() == "function_call_output" &&
                        it.path("item").path("call_id").asText() == callId
                }?.let { return it }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && controlArrived.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    "Timed out waiting for provider function output."
                }
            }
        }
        fun awaitServerToolCall(name: String): com.fasterxml.jackson.databind.JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                conversationItems().lastOrNull {
                    it.path("item").path("type").asText() == "function_call" &&
                        it.path("item").path("name").asText() == name
                }?.let { return it }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && controlArrived.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    "Timed out waiting for server-owned tool call."
                }
            }
        }
        fun awaitResponseCount(expected: Int): List<com.fasterxml.jackson.databind.JsonNode> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                responses().takeIf { it.size >= expected }?.let { return it }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && controlArrived.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    "Timed out waiting for $expected provider responses."
                }
            }
        }
        private fun fProviderConversationItem(item: ObjectNode) {
            provider("conversation.item.created", "item" to item)
        }
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
            feedbackAssessmentSubscription.dispose()
            questionAssessmentSubscription.dispose()
            workSubscription?.dispose()
            controlSubscription.dispose()
            serverLifecycleSubscription.dispose()
        }
    }
}
