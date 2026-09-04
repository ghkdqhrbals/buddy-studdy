package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyEvidenceSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationContextSource
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyDeletionRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorizationScope
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateEvidence
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Synthetic relay events reproduce the saved-root readback without manufacturing child traversal evidence. */
class VoiceTutorOwnedMutationContextTest {
    private val mapper = JsonMapperProvider.mapper
    private val savedRoot = VoiceTutorStudyTargetCandidate(84, null, "스프링")
    private val rootReadbackSpeech = "저장된 루트 주제는 스프링, 수준은 5입니다. 이걸 지금 학습으로 시작할까요?"

    @Test
    fun `exact owner read after greeting supports a spoken level change without child discovery or focus`() =
        Fixture().use { f ->
            f.readSavedRootAfterGreeting()
            val command = "아니, 그걸 레벨 칠로 바꿔 줘."
            f.utterance(2, "change-level", command)

            val utterance = f.assessments().last().utterances.single()
            val context = requireNotNull(utterance.studyMutationContext)
            assertThat(utterance.targetOffer).isNull()
            assertThat(context.source).isEqualTo(VoiceTutorStudyMutationContextSource.OWNER_READ)
            assertThat(context.currentFocusStudyId).isNull()
            assertThat(context.candidates).contains(savedRoot)
            assertThat(context.referentTranscript).isEqualTo(rootReadbackSpeech)

            f.assessUpdate(command, 7, "칠")
            assertThat(f.serverUpdates()).isEmpty()
            f.persistLatestInput()
            assertThat(f.serverUpdates()).isEmpty()
            f.respondToProposal(3, "change-level-confirm", "스프링을 레벨 7로 바꿀까요?")
            val update = f.awaitServerUpdate(1)
            assertThat(mapper.readTree(update.path("item").path("arguments").asText()))
                .isEqualTo(mapper.readTree("""{"study_id":84,"difficulty_level":7}"""))
            val callId = f.startServerCall(update)
            val boundary = f.controller.mutationDialogueBoundary(callId)
            assertThat(boundary.latestAcceptedLearnerProviderItemId).isEqualTo("change-level-confirm")
            assertThat(boundary.studyUpdateAuthorization?.scope)
                .isEqualTo(VoiceTutorStudyUpdateAuthorizationScope.OWNER_READ)
            assertThat(boundary.studyUpdateAuthorization?.targetProof?.studyId).isEqualTo(84)
            assertThat(boundary.studyUpdateAuthorization?.consume()).isTrue()
            assertThat(boundary.studyUpdateAuthorization?.consume()).isFalse()
            assertThat(boundary.focusAuthorization).isNull()

            f.persistLatestInput()
            assertThat(f.serverUpdates()).hasSize(1)
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `successful owner update refreshes the next mutation context after another conversational turn`() =
        Fixture().use { f ->
            f.readSavedRootAfterGreeting()
            val firstCommand = "그걸 레벨 칠로 바꿔 줘."
            f.utterance(2, "first-level-change", firstCommand)
            f.assessUpdate(firstCommand, 7, "칠")
            f.persistLatestInput()
            f.respondToProposal(3, "first-change-confirm", "스프링을 레벨 7로 바꿀까요?")
            val firstCall = f.awaitServerUpdate(1)
            val firstCallId = f.startServerCall(firstCall)
            assertThat(f.controller.mutationDialogueBoundary(firstCallId).studyUpdateAuthorization?.consume()).isTrue()
            f.completeTool(
                firstCallId,
                VoiceTutorMcpToolResult(
                    output = """{"id":84,"parentStudyId":null,"topic":"스프링","difficultyLevel":7}""",
                    isError = false,
                    studyTreeChanged = true,
                    changedStudyId = 84,
                    changeKind = VoiceTutorStudyChangeKind.UPDATED,
                    lessonRevision = 1,
                    updatedStudySnapshot = VoiceTutorStudySnapshot(84, null, "스프링", 7, 1),
                ),
            )
            f.finishSpeech("스프링의 수준을 7로 변경했어요. 이 주제로 학습을 시작할까요?")

            f.utterance(4, "thanks", "고마워.")
            f.assess(VoiceTutorInputIntent.NONE)
            f.persistLatestInput()
            f.finishSpeech("원하는 설정이 있으면 말씀해 주세요.")

            val secondCommand = "스프링을 레벨 8로 바꿔 줘."
            f.utterance(5, "second-level-change", secondCommand)
            val utterance = f.assessments().last().utterances.single()
            val context = requireNotNull(utterance.studyMutationContext)
            assertThat(context.source).isEqualTo(VoiceTutorStudyMutationContextSource.OWNER_READ)
            assertThat(context.lessonRevision).isEqualTo(1)
            assertThat(context.candidates.single { it.studyId == 84L }.difficulty).isEqualTo(7)
            assertThat(context.referentTranscript).isEqualTo("원하는 설정이 있으면 말씀해 주세요.")
            assertThat(context.currentFocusStudyId).isNull()

            f.assessUpdate(secondCommand, 8, "8", explicitTarget = true)
            f.persistLatestInput()
            f.respondToProposal(6, "second-change-confirm", "스프링을 레벨 8로 바꿀까요?")
            val secondCall = f.awaitServerUpdate(2)
            assertThat(mapper.readTree(secondCall.path("item").path("arguments").asText()))
                .isEqualTo(mapper.readTree("""{"study_id":84,"difficulty_level":8}"""))
            val secondCallId = f.startServerCall(secondCall)
            val secondBoundary = f.controller.mutationDialogueBoundary(secondCallId)
            assertThat(secondBoundary.studyUpdateAuthorization?.targetProof?.difficulty).isEqualTo(7)
            assertThat(secondBoundary.studyUpdateAuthorization?.consume()).isTrue()
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `deletion waits for one spoken confirmation then inserts exactly one server call`() =
        Fixture().use { f ->
            f.readSavedRootAfterGreeting()
            val command = "그 스프링 주제를 삭제해 줘."
            f.utterance(2, "delete-saved-root", command)
            f.assess(
                intent = VoiceTutorInputIntent.DELETE_STUDY,
                deletion = VoiceTutorStudyDeletionRequest(
                    studyId = 84,
                    evidence = VoiceTutorStudyUpdateEvidence(
                        source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
                        command = command,
                        targetTopic = "스프링",
                        topic = null,
                        difficulty = null,
                        targetImplicitCurrentFocus = false,
                    ),
                ),
            )
            assertThat(f.serverCalls("delete_study")).isEmpty()
            f.persistLatestInput()
            assertThat(f.serverCalls("delete_study")).isEmpty()
            f.respondToProposal(3, "delete-confirm", "스프링과 하위 주제를 삭제할까요?")
            val deletion = f.awaitServerCall("delete_study", 1)
            assertThat(mapper.readTree(deletion.path("item").path("arguments").asText()))
                .isEqualTo(mapper.readTree("""{"study_id":84,"confirm":true}"""))
            val callId = f.startServerCall(deletion)
            val boundary = f.controller.mutationDialogueBoundary(callId)
            assertThat(boundary.latestAcceptedLearnerIntent).isEqualTo(VoiceTutorInputIntent.DELETE_STUDY)
            assertThat(boundary.studyDeletionAuthorization?.studyId).isEqualTo(84)
            assertThat(boundary.studyDeletionAuthorization?.consume()).isTrue()
            assertThat(boundary.studyDeletionAuthorization?.consume()).isFalse()
            assertThat(boundary.studyUpdateAuthorization).isNull()
            f.persistLatestInput()
            assertThat(f.serverCalls("delete_study")).hasSize(1)
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `declining a proposed update never dispatches a write`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        f.respondToProposal(3, "reject", "스프링을 레벨 7로 바꿀까요?", reject = true)
        assertThat(f.serverUpdates()).isEmpty()
        assertThat(f.latestResponse().path("response").path("tool_choice").asText()).isEqualTo("none")
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `a meaningful unrelated reply cannot approve a pending change`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        f.finishSpeech("스프링을 레벨 7로 바꿀까요?")
        f.utterance(3, "unrelated", "지금 몇 시지?")
        f.assess(VoiceTutorInputIntent.NONE)
        f.persistLatestInput()
        f.finishSpeech("시간이 궁금하신가요?")
        f.utterance(4, "yes-to-other-question", "응")
        assertThat(f.assessments().last().utterances.single().mutationProposal).isNull()
        f.assess(VoiceTutorInputIntent.NONE)
        f.persistLatestInput()
        assertThat(f.serverUpdates()).isEmpty()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `a confirmation whose persistence fails grants no mutation authority`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        f.respondToProposal(3, "unstored-yes", "스프링을 레벨 7로 바꿀까요?", persisted = false)
        assertThat(f.serverUpdates()).isEmpty()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `yes spoken before confirmation audio starts cannot become approval after the question finishes`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        val question = f.beginSpeech()
        val assessmentsBeforeReply = f.assessments().size

        // response.create/created is not evidence that the learner has heard the question.
        f.utterance(3, "pre-audio-yes", "응")
        assertThat(f.assessments()).hasSize(assessmentsBeforeReply)
        assertThat(f.serverUpdates()).isEmpty()
        f.startSpeechPlayback(question)
        f.finishSpeechGeneration(question, "스프링을 레벨 7로 바꿀까요?")
        f.stopSpeechPlayback(question)

        val input = f.assessments().last().utterances.single()
        assertThat(input.itemId).isEqualTo("pre-audio-yes")
        assertThat(input.mutationProposal).isNull()
        assertThat(input.studyMutationContext?.candidates).contains(savedRoot)
        f.assess(VoiceTutorInputIntent.NONE)
        f.persistLatestInput()
        assertThat(f.serverUpdates()).isEmpty()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `natural reply during confirmation audio tail waits for completed question then confirms once`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        val question = f.beginSpeech()
        f.startSpeechPlayback(question)
        f.finishSpeechGeneration(question, "스프링을 레벨 7로 바꿀까요?")
        val assessmentsBeforeReply = f.assessments().size

        // The exact question is generated but its audio tail has not drained yet.
        f.utterance(3, "near-tail-yes", "응, 그렇게 해")
        assertThat(f.assessments()).hasSize(assessmentsBeforeReply)
        assertThat(f.serverUpdates()).isEmpty()
        f.stopSpeechPlayback(question)

        val input = f.assessments().last().utterances.single()
        assertThat(input.itemId).isEqualTo("near-tail-yes")
        val proposal = requireNotNull(input.mutationProposal)
        assertThat(proposal.tutorAudioTranscript).isEqualTo("스프링을 레벨 7로 바꿀까요?")
        assertThat(proposal.targetStudyId).isEqualTo(84)
        assertThat(proposal.difficulty).isEqualTo(7)
        f.assess(VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION, proposalId = proposal.proposalId)
        assertThat(f.serverUpdates()).isEmpty()
        f.persistLatestInput()
        val call = f.awaitServerUpdate(1)
        assertThat(mapper.readTree(call.path("item").path("arguments").asText()))
            .isEqualTo(mapper.readTree("""{"study_id":84,"difficulty_level":7}"""))
        val boundary = f.controller.mutationDialogueBoundary(f.startServerCall(call))
        assertThat(boundary.latestAcceptedLearnerProviderItemId).isEqualTo("near-tail-yes")
        assertThat(boundary.studyUpdateAuthorization?.consume()).isTrue()
        assertThat(boundary.studyUpdateAuthorization?.consume()).isFalse()
        assertThat(boundary.focusAuthorization).isNull()
        f.persistLatestInput()
        assertThat(f.serverUpdates()).hasSize(1)
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `failed unrelated USER persistence retires the old proposal while retaining owner read metadata`() = Fixture().use { f ->
        f.readSavedRootAfterGreeting()
        val command = "그걸 레벨 칠로 바꿔 줘."
        f.utterance(2, "change", command)
        f.assessUpdate(command, 7, "칠")
        f.persistLatestInput()
        f.finishSpeech("스프링을 레벨 7로 바꿀까요?")

        f.utterance(3, "unstored-unrelated", "지금 몇 시야?")
        assertThat(f.assessments().last().utterances.single().mutationProposal).isNotNull()
        f.assess(VoiceTutorInputIntent.NONE)
        f.persistLatestInput(persisted = false)
        assertThat(f.serverUpdates()).isEmpty()
        f.finishSpeech("시간이 궁금하신가요?")
        f.utterance(4, "yes-to-new-question", "응")
        val input = f.assessments().last().utterances.single()
        assertThat(input.itemId).isEqualTo("yes-to-new-question")
        assertThat(input.mutationProposal).isNull()
        assertThat(input.studyMutationContext?.source).isEqualTo(VoiceTutorStudyMutationContextSource.OWNER_READ)
        assertThat(input.studyMutationContext?.candidates).contains(savedRoot)
        f.assess(VoiceTutorInputIntent.NONE)
        f.persistLatestInput()
        assertThat(f.serverUpdates()).isEmpty()
        assertThat(f.errors).isEmpty()
    }

    private inner class Fixture : AutoCloseable {
        private val now = AtomicLong()
        private val controls = CopyOnWriteArrayList<String>()
        private val inputActions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        private val controlArrived = Semaphore(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
            toolsEnabled = true,
            // A different initial node proves Spring comes from the later exact owner read.
            initialStudyMutationSnapshot = VoiceTutorInitialStudyMutationSnapshot(
                listOf(VoiceTutorStudyTargetCandidate(11, null, "Python", 3)),
            ),
        )
        private val controlSubscription = controller.providerEvents().subscribe({
            controls += it
            controlArrived.release()
        }, errors::add)
        private val inputSubscription = controller.inputActions().subscribe(inputActions::add, errors::add)
        private var responseSequence = 0

        init {
            controller.startOpeningResponse()
            finishSpeech("어떤 주제를 공부할까요?")
        }

        fun readSavedRootAfterGreeting() {
            utterance(1, "hello", "안녕.")
            assess(VoiceTutorInputIntent.NONE)
            persistLatestInput()

            val token = latestResponseToken()
            val responseId = "exact-read-response"
            val callId = "exact-root-read"
            response("response.created", responseId, token)
            response(
                "response.done", responseId, token,
                listOf(mapOf(
                    "type" to "function_call",
                    "status" to "completed",
                    "call_id" to callId,
                    "name" to "get_study",
                    "arguments" to """{"study_id":84}""",
                )),
            )
            assertThat(controller.beginToolExecution(callId)).isTrue()
            completeTool(
                callId,
                VoiceTutorMcpToolResult(
                    output = """{"id":84,"parentStudyId":null,"topic":"스프링","difficultyLevel":5}""",
                    isError = false,
                    candidateDiscovery = VoiceTutorCandidateDiscovery(
                        source = VoiceTutorCandidateReadKind.GET_STUDY,
                        lessonRevision = 0,
                        currentFocusStudyId = null,
                        candidates = listOf(savedRoot),
                        scope = VoiceTutorCandidateDiscoveryScope.ExactStudy(84),
                    ),
                ),
            )
            finishSpeech(rootReadbackSpeech)
            assertThat(serverUpdates()).isEmpty()
        }

        fun utterance(sequence: Long, itemId: String, transcript: String) {
            controller.observeClientEvent(mapper.writeValueAsString(mapOf(
                "type" to VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, "sequence" to sequence,
            )))
            now.addAndGet(Duration.ofSeconds(1).toNanos())
            controller.observeClientEvent(mapper.writeValueAsString(mapOf(
                "type" to VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, "sequence" to sequence,
            )))
            provider("input_audio_buffer.committed", "item_id" to itemId)
            provider("conversation.item.input_audio_transcription.completed", "item_id" to itemId, "transcript" to transcript)
        }

        fun assessments() = inputActions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()

        fun assessUpdate(command: String, difficulty: Int, evidence: String, explicitTarget: Boolean = false) = assess(
            VoiceTutorInputIntent.UPDATE_STUDY,
            VoiceTutorStudyUpdateRequest(
                studyId = 84,
                topic = null,
                difficulty = difficulty,
                evidence = VoiceTutorStudyUpdateEvidence(
                    source = VoiceTutorRootStudyEvidenceSource.TRANSCRIPT,
                    command = command,
                    targetTopic = "스프링".takeIf { explicitTarget },
                    topic = null,
                    difficulty = evidence,
                    targetImplicitCurrentFocus = false,
                    targetImplicitSpokenOffer = !explicitTarget,
                ),
            ),
        )

        fun assess(
            intent: VoiceTutorInputIntent,
            update: VoiceTutorStudyUpdateRequest? = null,
            deletion: VoiceTutorStudyDeletionRequest? = null,
            proposalId: String? = null,
        ) {
            val batch = assessments().last()
            controller.completeInputAssessment(batch.token, Result.success(VoiceTutorInputAssessmentResult(
                batch.utterances.map {
                    VoiceTutorInputItemAssessment(
                        itemId = it.itemId,
                        decision = VoiceTutorInputDecision.MEANINGFUL,
                        intent = intent,
                        studyUpdateRequest = update,
                        deleteStudyRequest = deletion,
                        mutationProposalId = proposalId,
                    )
                },
            )))
        }

        fun persistLatestInput(persisted: Boolean = true) {
            val publication = inputActions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Publish>().last()
            controller.confirmInputPublished(publication.itemId, persisted = persisted)
        }

        fun respondToProposal(
            sequence: Long, itemId: String, question: String, reject: Boolean = false, persisted: Boolean = true,
        ) {
            assertThat(latestResponse().path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(latestResponse().path("response").path("instructions").asText())
                .contains("ONE short natural confirmation")
            finishSpeech(question)
            utterance(sequence, itemId, if (reject) "아니, 하지 마" else "응, 해줘")
            val proposal = requireNotNull(assessments().last().utterances.single().mutationProposal)
            assertThat(proposal.tutorAudioTranscript).isEqualTo(question)
            assess(
                if (reject) VoiceTutorInputIntent.REJECT_STUDY_MUTATION else VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION,
                proposalId = proposal.proposalId,
            )
            persistLatestInput(persisted)
        }

        fun finishSpeech(transcript: String) {
            val speech = beginSpeech()
            startSpeechPlayback(speech)
            finishSpeechGeneration(speech, transcript)
            stopSpeechPlayback(speech)
        }

        fun beginSpeech(): Pair<String, String> {
            val token = latestResponseToken()
            val id = "spoken-${++responseSequence}"
            response("response.created", id, token)
            return id to token
        }

        fun startSpeechPlayback(speech: Pair<String, String>) {
            val (id, _) = speech
            provider("output_audio_buffer.started", "response_id" to id)
            provider("response.output_audio.delta", "response_id" to id, "delta" to "AA==")
        }

        fun finishSpeechGeneration(speech: Pair<String, String>, transcript: String) {
            val (id, token) = speech
            provider(
                "response.output_audio_transcript.done", "response_id" to id,
                "item_id" to "$id-item", "content_index" to 0, "transcript" to transcript,
            )
            response("response.done", id, token, listOf(mapOf(
                "type" to "message", "role" to "assistant",
                "content" to listOf(mapOf("type" to "output_audio", "transcript" to transcript)),
            )))
        }

        fun stopSpeechPlayback(speech: Pair<String, String>) {
            val (id, _) = speech
            provider("output_audio_buffer.stopped", "response_id" to id)
        }

        fun serverUpdates() = serverCalls("update_study")

        fun serverCalls(name: String) = conversationItems().filter {
            it.path("item").path("type").asText() == "function_call" &&
                it.path("item").path("name").asText() == name
        }

        fun awaitServerUpdate(count: Int) = awaitServerCall("update_study", count)

        fun awaitServerCall(name: String, count: Int): JsonNode = awaitControl {
            serverCalls(name).takeIf { it.size >= count }?.last()
        }

        fun startServerCall(event: JsonNode): String {
            acknowledge(event)
            val id = event.path("item").path("call_id").asText()
            assertThat(controller.beginToolExecution(id)).isTrue()
            return id
        }

        fun completeTool(callId: String, result: VoiceTutorMcpToolResult) {
            val previousResponseToken = latestResponseToken()
            assertThat(controller.completeToolExecution(callId, result)).isTrue()
            val output = awaitControl {
                conversationItems().lastOrNull {
                    it.path("item").path("type").asText() == "function_call_output" &&
                        it.path("item").path("call_id").asText() == callId
                }
            }
            acknowledge(output)
            awaitControl {
                controls.map(mapper::readTree).lastOrNull {
                    it.path("type").asText() == "response.create" &&
                        it.path("event_id").asText() != previousResponseToken
                }
            }
        }

        private fun acknowledge(event: JsonNode) {
            val item = event.path("item").deepCopy<ObjectNode>()
            provider("conversation.item.created", "item" to item)
        }

        fun latestResponse() = controls.map(mapper::readTree).last { it.path("type").asText() == "response.create" }
        private fun latestResponseToken() = latestResponse().path("event_id").asText()

        private fun conversationItems() = controls.map(mapper::readTree)
            .filter { it.path("type").asText() == "conversation.item.create" }

        private fun awaitControl(find: () -> JsonNode?): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                find()?.let { return it }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && controlArrived.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    "Expected relay control was not emitted; errors=$errors"
                }
            }
        }

        private fun response(type: String, id: String, token: String, output: List<Map<String, Any?>> = emptyList()) =
            provider(type, "response" to mapOf(
                "id" to id, "status" to "completed",
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
                "output" to output,
            ))

        private fun provider(type: String, vararg fields: Pair<String, Any>) =
            controller.observeProviderEvent(mapper.writeValueAsString(linkedMapOf("type" to type, *fields)))

        override fun close() {
            controller.close()
            inputSubscription.dispose()
            controlSubscription.dispose()
        }
    }
}
