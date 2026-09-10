package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract as Contract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata as Metadata
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningPhase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionReadback
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyTopicUserInput
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory WebSocket frames and suspended coroutines only: no provider, audio, database or classifier. */
class VoiceTutorNativeSessionRelayTest {
    @Test
    fun `malformed or mixed preference arguments never open a form or prepare topics`() {
        val proposal = mapOf("studyTopicProposal" to mapOf("parentStudyId" to 7, "topics" to listOf("Redis")))
        for (arguments in listOf(emptyMap(), mapOf("title" to "Incomplete"), ordinaryInputArguments("single") + proposal)) {
            Fixture(userInputEnabled = true).use { f ->
                f.opening(); f.learner(1, "invalid-form"); f.transcript("invalid-form")
                f.toolResponse("invalid-choices", "invalid-call", "request_user_input", json(arguments))
                f.await("malformed request returns a final error") { f.outputs().size == 1 }
                assertThat(f.outputs().single().path("item").path("output").asText()).contains("INVALID_ARGUMENTS")
                assertThat(f.inputRequests()).isEmpty()
                assertThat(f.tools.topicPreparations).isEmpty()
                assertThat(f.tools.invocations).isEmpty()
                assertThat(f.errors).isEmpty()
            }
        }
    }

    @Test
    fun `silent saved question readback retries and a verified refresh recovers exhausted playback into manual answer capture`() {
        for (exhaustRetry in listOf(false, true)) {
            val question = VoiceTutorQuestionReadback(74, "122", "Redis의 장점과 한계를 단계별로 설명해 주세요.")
            val results = ArrayDeque(listOf(
                VoiceTutorMcpToolResult("{\"selected\":true}", false, lessonRevision = 1,
                    lessonFocus = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(74, 1),
                        VoiceTutorStudySnapshot(74, null, "Redis", 10, 1))),
                VoiceTutorMcpToolResult("{\"pendingQuestion\":{\"id\":\"122\"}}", false, lessonRevision = 1,
                    questionReadback = question),
                VoiceTutorMcpToolResult("{\"pendingQuestion\":{\"id\":\"122\"}}", false, lessonRevision = 1,
                    questionReadbackRecovery = question,
                    learningProgress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.CONVERSATION, 74)),
            ))
            val tools = FakeTools { results.removeFirst() }
            Fixture(tools = tools).use { f ->
                f.opening(); f.learner(1, "start-study"); f.transcript("start-study", "Redis 공부 시작하자.")
                f.toolResponse("select", "select-call", "select_voice_study", "{\"study_id\":74}")
                f.await("selection result") { f.outputs().size == 1 }
                f.ack(f.outputs().last())
                f.await("separate saved question lookup") { f.responses().size == 3 }
                f.toolResponse("lookup", "lookup-call", "list_pending_questions", "{\"study_id\":74}")
                f.await("saved question result") { f.outputs().size == 2 }
                f.ack(f.outputs().last())
                f.await("mandatory readback") { f.responses().size == 4 }
                f.created("empty-readback")
                f.provider("response.done", "response" to mapOf("id" to "empty-readback", "status" to "completed", "output" to emptyList<Any>()))
                f.await("silent readback gets one bounded retry") { f.responses().size == 5 }
                assertThat(f.responses().last().path("response").path("instructions").asText()).contains(question.question)
                assertThat(f.answerStates()).isEmpty()
                if (exhaustRetry) {
                    f.created("empty-retry")
                    f.provider("response.done", "response" to mapOf("id" to "empty-retry", "status" to "completed", "output" to emptyList<Any>()))
                    f.await("exhaustion leaves reading and exposes input recovery") {
                        f.ui.any { it.path("type").asText() == Contract.INPUT_RETRY_EVENT } &&
                            f.ui.any { it.path("type").asText() == Contract.SESSION_STATE_EVENT && it.path("phase").asText() == "question_failed" }
                    }
                    assertThat(f.answerStates()).isEmpty()
                    f.learner(2, "retry-question"); f.transcript("retry-question", "질문 다시 읽어줘.")
                    assertThat(f.responses().last().path("response").path("instructions").asText()).doesNotContain(question.question)
                    f.toolResponse("refresh", "refresh-call", "list_pending_questions", "{\"study_id\":74}")
                    f.await("fresh canonical query returns the same saved identity") { f.outputs().size == 3 }
                    f.ack(f.outputs().last())
                    f.await("failed readback recovers after exact refreshed result acknowledgement") { f.responses().size == 7 }
                    assertThat(f.responses().last().path("response").path("instructions").asText()).contains(question.question)
                }
                f.completeAudioResponse("actual-question", "actual-question-item", question.question)
                f.await("actual question playback opens manual answer capture") {
                    f.answerStates().lastOrNull()?.path("phase")?.asText() == "listening"
                }
                val capture = f.answerStates().last()
                assertThat(capture.path("recordId").asText()).isEqualTo("122")
                assertThat(capture.path("studyId").asLong()).isEqualTo(74)
                val beforeAnswer = f.responses().size
                f.capturedLearner(if (exhaustRetry) 3 else 2, "answer-fragment")
                f.transcript("answer-fragment", "일단 메모리에 저장하니까")
                f.await("answer fragment reaches capture") { f.stored.any { it.path("item_id").asText() == "answer-fragment" } }
                assertThat(f.responses()).hasSize(beforeAnswer)
                assertThat(tools.reviewed).isEmpty()
                assertThat(f.errors).isEmpty()
            }
        }
    }

    @Test
    fun `learner can abandon an in flight selection and start a different topic after its saved result is reconciled`() {
        val previous = CompletableDeferred<VoiceTutorMcpToolResult>()
        val newQuestion = "스프링의 의존성 주입을 설명해 주세요."
        var invocation = 0
        val tools = FakeTools {
            when (invocation++) {
                0 -> previous.await()
                1 -> VoiceTutorMcpToolResult("{\"selected\":true,\"voiceQuestion\":{\"lookupRequired\":true}}", false,
                    lessonRevision = 2, lessonFocus = VoiceTutorLessonFocusSelection(
                        VoiceTutorLessonFocus(84, 2), VoiceTutorStudySnapshot(84, null, "스프링", 7, 2)))
                else -> VoiceTutorMcpToolResult("{\"recordId\":\"43\"}", false, lessonRevision = 2,
                    questionReadback = VoiceTutorQuestionReadback(84, "43", newQuestion))
            }
        }
        Fixture(tools = tools).use { f ->
            f.opening(); f.learner(1, "old-choice"); f.transcript("old-choice", "메모리 관리로 시작하자.")
            f.toolResponse("old-selection", "select-old", "select_voice_study", "{\"study_id\":75}")
            f.await("old selection is executing") { tools.invocations.size == 1 }
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(json(mapOf("type" to type, "sequence" to 2))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("cancellation and replacement speech is committed") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            f.provider("input_audio_buffer.committed", "item_id" to "new-choice")
            f.transcript("new-choice", "그거 취소하고 스프링으로 먼저 시작하자.")
            f.await("new choice is durable while the old operation is still in flight") {
                f.stored.any { it.path("item_id").asText() == "new-choice" }
            }
            previous.complete(VoiceTutorMcpToolResult("{\"selected\":true,\"notice\":\"Read the old question now\"}", false,
                lessonRevision = 1, lessonFocus = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(75, 1), VoiceTutorStudySnapshot(75, 74, "메모리 관리", 8, 1)),
                questionReadback = VoiceTutorQuestionReadback(75, "42", "이전 주제의 문제")))
            f.await("saved result is reconciled without starting its lesson") { f.outputs().size == 1 }
            val oldResult = mapper.readTree(f.outputs().single().path("item").path("output").asText())
            assertThat(oldResult.path("selected").asBoolean()).isTrue()
            assertThat(oldResult.path("followupCancelled").asBoolean()).isTrue()
            assertThat(f.ui.none { it.path("type").asText() == Contract.SESSION_STATE_EVENT &&
                it.path("phase").asText() in setOf("question_ready", "question_reading") }).isTrue()
            assertThat(f.answerStates()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.ack(f.outputs().single())
            f.await("latest request receives a fresh response") { f.responses().size == 3 }
            assertThat(f.responses().last().path("response").path("instructions").asText()).doesNotContain("이전 주제의 문제")
            f.toolResponse("new-selection", "select-new", "select_voice_study", "{\"study_id\":84}")
            f.await("different topic selection succeeds") { f.outputs().size == 2 }
            val newContext = tools.invocations[1].context
            assertThat(newContext.initialLessonRevision).isEqualTo(1)
            assertThat(newContext.dialogueBoundary?.latestAcceptedLearnerProviderItemId).isEqualTo("new-choice")
            f.ack(f.outputs().last())
            f.await("new selection returns before its separate question lookup") { f.responses().size == 4 }
            f.toolResponse("new-question-lookup", "lookup-new", "list_pending_questions", "{\"study_id\":84}")
            f.await("new topic question arrives") { f.outputs().size == 3 }
            f.ack(f.outputs().last())
            f.await("only the new topic gets a readback") { f.responses().size == 5 }
            assertThat(f.responses().last().path("response").path("instructions").asText()).contains(newQuestion)
            f.completeAudioResponse("new-question", "new-question-item", newQuestion)
            f.await("new topic answer capture is available") {
                f.answerStates().lastOrNull()?.path("phase")?.asText() == "listening"
            }
            assertThat(f.answerStates().last().path("studyId").asLong()).isEqualTo(84)
            assertThat(tools.invocations.map { it.name }).containsExactly(
                "select_voice_study", "select_voice_study", "list_pending_questions")
            assertThat(tools.invocations.map { it.arguments["study_id"] }).containsExactly(75, 84, 84)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `agreement after a level change selects the updated topic and reaches saved question readback without repeating the write`() {
        val topic = "메모리 관리와 만료 정책"
        val question = "메모리 만료 정책의 동작을 설명해 주세요."
        val results = ArrayDeque(listOf(
            VoiceTutorMcpToolResult("{\"prepared\":true}", false,
                mutationConfirmationQuestion = "$topic 주제를 레벨 8로 바꿀까요?"),
            VoiceTutorMcpToolResult("{\"id\":75,\"difficultyLevel\":8,\"voiceLessonContextReady\":false}", false,
                lessonRevision = 1),
            VoiceTutorMcpToolResult("{\"selected\":true,\"voiceQuestion\":{\"lookupRequired\":true}}", false, lessonRevision = 2,
                lessonFocus = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(75, 2),
                    VoiceTutorStudySnapshot(75, 74, topic, 8, 2))),
            VoiceTutorMcpToolResult("{\"recordId\":\"42\"}", false, lessonRevision = 2,
                questionReadback = VoiceTutorQuestionReadback(75, "42", question)),
        ))
        val tools = FakeTools { results.removeFirst() }
        Fixture(tools = tools).use { f ->
            f.opening()
            f.learner(1, "level-request"); f.transcript("level-request", "레벨 8 정도 적당할 듯.")
            f.toolResponse("prepare-response", "prepare", "prepare_voice_study_mutation")
            f.await("proposal result") { f.outputs().size == 1 }
            f.ack(f.outputs().last())
            f.await("one exact confirmation") { f.responses().size == 3 }
            f.completeAudioResponse("confirmation", "confirmation-item", "$topic 주제를 레벨 8로 바꿀까요?")
            f.learner(2, "change-agreement"); f.transcript("change-agreement", "어 그거 바꿔줘.")
            f.toolResponse("confirm-response", "confirm", "confirm_voice_study_mutation")
            f.await("write result") { f.outputs().size == 2 }
            f.ack(f.outputs().last())
            f.await("updated level response") { f.responses().size == 5 }
            f.completeAudioResponse("changed", "changed-item", "레벨 8로 바꿨어요. 바로 이 주제로 공부할까요?")
            f.learner(3, "start-agreement"); f.transcript("start-agreement", "그렇게 하자.")
            f.toolResponse("selection-response", "select", "select_voice_study", "{\"study_id\":75}")
            f.await("selection finishes") { f.outputs().size == 3 }
            f.ack(f.outputs().last())
            f.await("selection completes before question lookup") { f.responses().size == 7 }
            f.toolResponse("question-lookup", "lookup", "list_pending_questions", "{\"study_id\":75}")
            f.await("saved question is returned separately") { f.outputs().size == 4 }
            f.ack(f.outputs().last())
            f.await("saved question is ready for audio") { f.responses().size == 8 }
            assertThat(f.responses().last().path("response").path("instructions").asText()).contains(question)
            f.completeAudioResponse("question", "question-item", question)
            f.await("answer capture starts instead of staying in response preparation") {
                f.answerStates().lastOrNull()?.path("phase")?.asText() == "listening"
            }
            assertThat(tools.invocations.map { it.name }).containsExactly(
                "prepare_voice_study_mutation", "confirm_voice_study_mutation", "select_voice_study", "list_pending_questions")
            val selection = tools.invocations[2].context
            assertThat(selection.initialLessonRevision).isEqualTo(1)
            assertThat(selection.dialogueBoundary?.latestAcceptedLearnerProviderItemId).isEqualTo("start-agreement")
            assertThat(selection.dialogueBoundary?.precedingTutorProviderItemId).isEqualTo("changed-item")
            assertThat(f.answerStates().last().path("studyId").asLong()).isEqualTo(75)
            assertThat(f.answerStates().last().path("recordId").asText()).isEqualTo("42")
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `old native clients never receive structured interaction tools or wait for unsupported input`() {
        assertThat(nativeVoiceTutorDefinitions(FakeTools()).map { it.name }).doesNotContain("request_user_input")
        assertThat(nativeVoiceTutorDefinitions(FakeTools(), true).map { it.name }).contains("request_user_input")
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1")
            f.toolResponse("unsupported", "form", "request_user_input")
            f.await("unsupported tool returns without waiting for a UI") { f.outputs().size == 1 }
            assertThat(f.outputs().single().path("item").path("output").asText()).contains("USER_INPUT_UNSUPPORTED")
            assertThat(f.ui.none { it.path("type").asText() == Contract.USER_INPUT_REQUEST_EVENT }).isTrue()
            f.ack(f.outputs().single())
            f.await("ordinary voice continues") { f.responses().size == 3 }
        }
    }

    @Test
    fun `ordinary single multiple and text cards hold the native reply until exact submitted answers are durable and acknowledged`() {
        for ((mode, selected) in listOf(
            "single" to listOf("redis"), "multiple" to listOf("redis", "msa"), "text" to emptyList(),
        )) {
            val storedChoice = CompletableDeferred<Unit>()
            Fixture(userInputEnabled = true, store = { event ->
                if (event.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT) storedChoice.await()
            }).use { f ->
                f.opening(); f.learner(1, "choose-direction"); f.transcript("choose-direction", "다음 공부 방향을 추천해줘.")
                val arguments = ordinaryInputArguments(mode)
                val definition = f.outgoing.first { it.path("type").asText() == "session.update" }
                    .path("session").path("tools").single { it.path("name").asText() == "request_user_input" }
                val questionSchema = definition.path("parameters").path("properties").path("questions").path("items")
                val alternatives = questionSchema.path("anyOf")
                alternatives.forEach { schema -> assertThat(schema.path("required").map { it.asText() })
                    .contains("id", "prompt", "selectionMode", "options", "allowFreeText") }
                assertThat(alternatives.map { it.path("properties").path("selectionMode").path("enum")[0].asText() })
                    .containsExactly("single", "multiple", "text")
                f.toolResponse("ordinary-$mode", "form-$mode", "request_user_input", json(arguments))
                f.await("ordinary $mode form is shown") { f.inputRequests().size == 1 }
                val request = f.inputRequests().single()
                assertThat(request.path("title").asText()).isEqualTo(arguments["title"])
                assertThat(request.path("questions")).isEqualTo(mapper.valueToTree<JsonNode>(arguments["questions"]))
                assertThat(request.path("sessionId").asText()).isEqualTo(context().session.id)
                assertThat(request.path("requestId").asText()).matches("[0-9a-f-]{36}")
                assertThat(request.path("attemptId").asText()).matches("[0-9a-f-]{36}")
                assertThat(request.path("sequence").asLong()).isPositive()
                assertThat(f.outputs()).isEmpty()
                assertThat(f.responses()).hasSize(2)
                assertThat(f.tools.invocations).isEmpty()
                assertThat(f.tools.topicSubmissions).isEmpty()

                val answers = listOf(mapOf("questionId" to "direction", "selectedOptionIds" to selected,
                    "text" to "  직접 입력한 방향\n실전 예제부터  "))
                val submit = userInputControl(Contract.USER_INPUT_SUBMIT_EVENT, request, answers)
                for (key in listOf("requestId", "sessionId", "attemptId")) {
                    f.control(submit + (key to "00000000-0000-4000-8000-000000000099"))
                }
                // These microphone edges must be ignored while the card owns the response.
                for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                    f.control(mapOf("type" to type, "sequence" to 2))
                }
                f.control(userInputControl(Contract.USER_INPUT_SUBMIT_EVENT, request,
                    listOf(mapOf("questionId" to "direction", "selectedOptionIds" to listOf("unknown"), "text" to ""))))
                f.await("invalid ordinary answer leaves the exact card pending") {
                    f.inputStates().lastOrNull()?.path("errorCode")?.asText() == "INVALID_ANSWERS"
                }
                assertThat(f.inputStates().last().path("phase").asText()).isEqualTo("pending")
                assertThat(f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" }).isEqualTo(1)
                assertThat(f.outgoing.none { it.path("type").asText() == "input_audio_buffer.clear" }).isTrue()
                assertThat(f.outputs()).isEmpty()
                assertThat(f.responses()).hasSize(2)

                f.control(submit)
                f.await("ordinary submission clears the muted microphone tail") {
                    f.outgoing.count { it.path("type").asText() == "input_audio_buffer.clear" } == 1
                }
                assertThat(f.outputs()).isEmpty()
                assertThat(f.stored.none { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }).isTrue()
                f.provider("input_audio_buffer.cleared", "event_id" to "ordinary-$mode-clear")
                f.await("ordinary choice is being durably stored") {
                    f.stored.any { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }
                }
                val evidence = f.stored.single { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }
                assertThat(evidence.path("item_id").asText()).startsWith(Metadata.STRUCTURED_ITEM_PREFIX)
                assertThat(evidence.path("transcript").asText()).contains("Text:   직접 입력한 방향\n실전 예제부터  \n[End structured input]")
                val expectedLabels = selected.map { if (it == "redis") "Redis" else "MSA" }
                expectedLabels.forEach { assertThat(evidence.path("transcript").asText()).contains("Selected: $it") }
                if (mode == "text") assertThat(evidence.path("transcript").asText()).doesNotContain("Selected:")
                assertThat(evidence.path("transcript").asText()).doesNotContain("Selected: Kafka")
                assertThat(f.outputs()).isEmpty()
                assertThat(f.responses()).hasSize(2)
                assertThat(f.inputStates().none { it.path("phase").asText() == "submitted" }).isTrue()
                storedChoice.complete(Unit)
                f.await("durable ordinary submission completes the exact held call") {
                    f.outputs().size == 1 && f.inputStates().lastOrNull()?.path("phase")?.asText() == "submitted"
                }
                val output = f.outputs().single()
                assertThat(output.path("item").path("call_id").asText()).isEqualTo("form-$mode")
                val result = mapper.readTree(output.path("item").path("output").asText())
                assertThat(result.path("requestId").asText()).isEqualTo(request.path("requestId").asText())
                assertThat(result.path("cancelled").booleanValue()).isFalse()
                assertThat(result.path("answers")).isEqualTo(mapper.valueToTree<JsonNode>(answers))
                assertThat(f.responses()).hasSize(2)
                assertThat(f.tools.invocations).isEmpty()
                assertThat(f.tools.topicSubmissions).isEmpty()

                f.provider("conversation.item.created", "item" to mapOf("type" to "function_call_output",
                    "call_id" to "other-form", "output" to output.path("item").path("output").asText()))
                f.provider("conversation.item.created", "item" to mapOf("id" to "after-wrong-ordinary-ack", "type" to "message"))
                f.await("unmatched result acknowledgement does not resume the card") {
                    f.ui.any { it.path("item").path("id").asText() == "after-wrong-ordinary-ack" }
                }
                assertThat(f.responses()).hasSize(2)
                f.ack(output)
                f.await("exact ordinary result acknowledgement resumes the reply") { f.responses().size == 3 }
                f.completeAudioResponse("ordinary-resumed", "ordinary-resumed-item", "선택한 방향으로 이어갈게요.")
                f.await("ordinary card resumes audible conversation") {
                    f.stored.any { it.path("item_id").asText() == "ordinary-resumed-item" }
                }
                val receipts = f.inputStates().size
                f.control(submit)
                f.await("duplicate ordinary submit is only reacknowledged") { f.inputStates().size == receipts + 1 }
                assertThat(f.inputStates().last().path("phase").asText()).isEqualTo("submitted")
                assertThat(f.outputs()).hasSize(1)
                assertThat(f.stored.count { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }).isEqualTo(1)
                assertThat(f.errors).isEmpty()
            }
        }
    }

    @Test
    fun `ordinary card cancellation sends an actual cancelled result and resumes without an answer or mutation`() {
        Fixture(userInputEnabled = true).use { f ->
            f.opening(); f.learner(1, "choose-direction"); f.transcript("choose-direction", "다음 공부 방향을 추천해줘.")
            f.toolResponse("ordinary-cancel", "cancel-form", "request_user_input", json(ordinaryInputArguments("multiple")))
            f.await("ordinary cancellable card is visible") { f.inputRequests().size == 1 }
            val request = f.inputRequests().single()
            val cancel = userInputControl(Contract.USER_INPUT_CANCEL_EVENT, request)
            f.control(cancel + ("requestId" to "00000000-0000-4000-8000-000000000099"))
            f.control(userInputControl(Contract.USER_INPUT_SUBMIT_EVENT, request, emptyList()))
            f.await("wrong cancellation correlation cannot consume the pending card") {
                f.inputStates().lastOrNull()?.path("errorCode")?.asText() == "INVALID_ANSWERS"
            }
            assertThat(f.outputs()).isEmpty()
            assertThat(f.responses()).hasSize(2)
            f.control(cancel)
            f.await("cancellation clears the muted microphone tail") {
                f.outgoing.any { it.path("type").asText() == "input_audio_buffer.clear" }
            }
            assertThat(f.outputs()).isEmpty()
            f.provider("input_audio_buffer.cleared", "event_id" to "ordinary-cancel-clear")
            f.await("cancelled result and client receipt complete the same form") {
                f.outputs().size == 1 && f.inputStates().lastOrNull()?.path("phase")?.asText() == "cancelled"
            }
            val output = f.outputs().single()
            val result = mapper.readTree(output.path("item").path("output").asText())
            assertThat(output.path("item").path("call_id").asText()).isEqualTo("cancel-form")
            assertThat(result.path("requestId").asText()).isEqualTo(request.path("requestId").asText())
            assertThat(result.path("cancelled").booleanValue()).isTrue()
            assertThat(result.path("answers").isArray).isTrue()
            assertThat(result.path("answers").size()).isZero()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.stored.none { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }).isTrue()
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.tools.topicSubmissions).isEmpty()
            f.ack(output)
            f.await("cancelled form acknowledgement resumes ordinary conversation") { f.responses().size == 3 }
            f.completeAudioResponse("cancel-resumed", "cancel-resumed-item", "다른 방향으로 이야기해 봐요.")
            f.await("cancellation resumes audible conversation") {
                f.stored.any { it.path("item_id").asText() == "cancel-resumed-item" }
            }
            val receipts = f.inputStates().size
            f.control(userInputControl(Contract.USER_INPUT_SUBMIT_EVENT, request,
                listOf(mapOf("questionId" to "direction", "selectedOptionIds" to listOf("redis"), "text" to ""))))
            f.await("late submit cannot reverse cancellation") { f.inputStates().size == receipts + 1 }
            assertThat(f.inputStates().last().path("phase").asText()).isEqualTo("cancelled")
            assertThat(f.outputs()).hasSize(1)
            assertThat(f.stored.none { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }).isTrue()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `spoken root focus then selected immutable topics wait for durable GUI evidence before the next child focus`() {
        val save = CompletableDeferred<VoiceTutorMcpToolResult>()
        val storedChoice = CompletableDeferred<Unit>()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false, lessonRevision = 1) }.apply { topicResult = { save.await() } }
        Fixture(tools = tools, userInputEnabled = true, store = { event ->
            if (event.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT) storedChoice.await()
        }).use { f ->
            f.opening(); f.learner(1, "learner-1"); f.transcript("learner-1")
            f.toolResponse("root-focus", "select-root", "select_voice_study")
            f.await("spoken root is focused") { f.outputs().size == 1 }
            f.ack(f.outputs().single())
            f.await("root focus continuation") { f.responses().size == 3 }
            f.toolResponse("choices", "form", "request_user_input", json(mapOf("studyTopicProposal" to mapOf(
                "parentStudyId" to 7, "topics" to listOf("Redis", "Kafka", "MSA")))))
            f.await("verified topic form arrives") { f.ui.any { it.path("type").asText() == Contract.USER_INPUT_REQUEST_EVENT } }
            val request = f.ui.single { it.path("type").asText() == Contract.USER_INPUT_REQUEST_EVENT }
            assertThat(tools.topicSubmissions).isEmpty()
            val submit = json(mapOf("type" to Contract.USER_INPUT_SUBMIT_EVENT,
                "requestId" to request.path("requestId").asText(), "sessionId" to request.path("sessionId").asText(),
                "attemptId" to request.path("attemptId").asText(), "answers" to listOf(mapOf("questionId" to "study_topics",
                    "selectedOptionIds" to listOf("topic_0", "topic_2"), "text" to ""))))
            assertThat(f.controls.tryEmitNext(submit)).isEqualTo(Sinks.EmitResult.OK)
            f.await("microphone tail clear is requested") { f.outgoing.any { it.path("type").asText() == "input_audio_buffer.clear" } }
            f.provider("input_audio_buffer.cleared", "event_id" to "selection-clear")
            f.await("only selected indices reach the write port") { tools.topicSubmissions.size == 1 }
            assertThat(tools.topicSubmissions.single()).containsExactly(0, 2)
            assertThat(f.outputs()).hasSize(1)
            assertThat(f.responses()).hasSize(3)
            save.complete(VoiceTutorMcpToolResult("{\"topics\":[{\"id\":11},{\"id\":13}]}", false,
                studyTreeChanged = true, changedStudyIds = listOf(11, 13)))
            f.await("exact GUI evidence is being saved") { f.stored.any { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT } }
            val evidence = f.stored.last { it.path("type").asText() == Metadata.STRUCTURED_USER_INPUT_EVENT }
            assertThat(evidence.path("transcript").asText()).contains("Selected: Redis", "Selected: MSA").doesNotContain("Kafka")
            assertThat(f.outputs()).hasSize(1)
            assertThat(f.ui.none { it.path("type").asText() == Contract.USER_INPUT_STATE_EVENT }).isTrue()
            storedChoice.complete(Unit)
            f.await("durable GUI evidence releases both the output and submitted form state") {
                f.outputs().size == 2 && f.ui.any { it.path("type").asText() == Contract.USER_INPUT_STATE_EVENT && it.path("phase").asText() == "submitted" }
            }
            assertThat(f.outputs().last().path("item").path("call_id").asText()).isEqualTo("form")
            f.ack(f.outputs().last())
            f.await("exact output acknowledgement resumes tutor") { f.responses().size == 4 }
            assertThat(f.controls.tryEmitNext(submit)).isEqualTo(Sinks.EmitResult.OK)
            f.await("duplicate submit is reacknowledged without another write") {
                f.ui.count { it.path("type").asText() == Contract.USER_INPUT_STATE_EVENT } == 2
            }
            assertThat(tools.topicSubmissions).hasSize(1)
            f.toolResponse("child-focus", "select-child", "select_voice_study")
            f.await("child focus uses the new persisted GUI learner source") { tools.invocations.size == 2 }
            assertThat(tools.invocations.first().context.dialogueBoundary?.latestAcceptedLearnerProviderItemId).isEqualTo("learner-1")
            assertThat(tools.invocations.last().context.dialogueBoundary?.latestAcceptedLearnerProviderItemId).isEqualTo(evidence.path("item_id").asText())
            assertThat(tools.invocations.last().context.dialogueBoundary?.precedingTutorProviderItemId).isNull()
            assertThat(tools.invocations.last().context.initialLessonRevision).isEqualTo(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `empty acoustic interruption settles UI waiting after successful preparation without reviving or replaying the proposal`() {
        val release = CompletableDeferred<VoiceTutorMcpToolResult>()
        val tools = FakeTools { release.await() }
        Fixture(tools = tools).use { f ->
            f.opening(); f.learner(1, "learner-1"); f.transcript("learner-1")
            f.toolResponse("prepare-response", "prepare-1", "prepare_voice_study_mutation")
            f.await("preparation began") { tools.invocations.size == 1 }
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(json(mapOf("type" to type, "sequence" to 2))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("new acoustic input committed") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            val emptyCommit = f.outgoing.last { it.path("type").asText() == "input_audio_buffer.commit" }
            release.complete(VoiceTutorMcpToolResult("{\"prepared\":true}", false,
                mutationConfirmationQuestion = "MSA 주제를 레벨 8로 만들까요?"))
            f.await("accepted preparation finishes once") { f.outputs().size == 1 }
            f.ack(f.outputs().single())
            f.provider("error", "error" to mapOf("code" to "input_audio_buffer_commit_empty",
                "event_id" to emptyCommit.path("event_id").asText()))
            f.await("latest empty input stops indefinite response waiting") {
                f.ui.any { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT && it.path("sequence").asLong() == 2L }
            }
            assertThat(f.responses()).hasSize(2)
            assertThat(tools.invocations.map { it.name }).containsExactly("prepare_voice_study_mutation")
            assertThat(f.ui.any { it.path("type").asText() == Contract.INPUT_RETRY_EVENT }).isFalse()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
            f.learner(3, "learner-3")
            assertThat(f.responses()).hasSize(3)
            assertThat(f.responses().last().path("response").has("instructions")).isFalse()
            assertThat(tools.invocations).hasSize(1)
        }
    }

    @Test
    fun `barge in clears live provider output before replying to the correction and never relays stale speech`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1"); f.created("response-1")
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.await("old response began playing") { f.ui.any { it.path("response_id").asText() == "response-1" } }
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(JsonMapperProvider.mapper.writeValueAsString(mapOf("type" to type, "sequence" to 2))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("barge in cancellation and new commit reached the provider") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            assertThat(f.outgoing.map { it.path("type").asText() })
                .containsSubsequence("response.cancel", "output_audio_buffer.clear", "input_audio_buffer.commit")
            f.provider("input_audio_buffer.committed", "item_id" to "learner-2")
            f.provider("response.done", "response" to mapOf("id" to "response-1", "status" to "cancelled", "output" to emptyList<Any>()))
            f.await("intentional interruption relayed") { f.ui.any { it.path("type").asText() == Contract.RESPONSE_INTERRUPTED_EVENT } }
            assertThat(f.responses()).hasSize(2)
            f.provider("output_audio_buffer.cleared", "response_id" to "response-1")
            f.await("latest learner reply begins after clear") { f.responses().size == 3 }
            f.provider("response.output_audio_transcript.done", "response_id" to "response-1", "item_id" to "stale-tutor", "transcript" to "stale")
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.provider("conversation.item.created", "item" to mapOf("id" to "after-stale", "type" to "message"))
            f.await("late events processed") { f.ui.any { it.path("item").path("id").asText() == "after-stale" } }
            assertThat(f.ui.any { it.path("item_id").asText() == "stale-tutor" }).isFalse()
            assertThat(f.stored.any { it.path("item_id").asText() == "stale-tutor" }).isFalse()
            assertThat(f.ui.count { it.path("type").asText() == "output_audio_buffer.started" && it.path("response_id").asText() == "response-1" }).isEqualTo(1)
            assertThat(f.ui.any { it.path("type").asText() == Contract.INPUT_RETRY_EVENT }).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `one clear start selects requests and reads a completed event without another agreement or polling tool`() {
        val progress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.QUESTION_GENERATING, 7, correlationId = "generation-event")
        val updates = Sinks.many().unicast().onBackpressureBuffer<VoiceTutorMcpToolResult>()
        var step = 0
        val tools = FakeTools {
            step++
            VoiceTutorMcpToolResult("{}", false, learningProgress = progress.takeIf { step == 3 })
        }.apply { progressEvents = updates.asFlux().asFlow() }
        Fixture(tools = tools).use { f ->
            f.opening(); f.learner(1, "clear-start"); f.transcript("clear-start", "그럼 이걸로 시작해보자.")
            for ((index, name) in listOf("select_voice_study", "list_pending_questions", "request_question").withIndex()) {
                f.toolResponse("step-$index", "call-$index", name)
                f.await("one accepted step") { f.outputs().size == index + 1 }
                f.ack(f.outputs().last())
                if (index < 2) f.await("next real tool response") { f.responses().size == index + 3 }
            }
            val beforeReady = f.responses().size
            repeat(4) { updates.tryEmitNext(VoiceTutorMcpToolResult("{}", false, learningProgress = progress)) }
            updates.tryEmitNext(VoiceTutorMcpToolResult("{}", false,
                learningProgress = progress.copy(phase = VoiceTutorLearningPhase.QUESTION_READY, recordId = "42"),
                questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")))
            f.await("completion event starts exact readback") { f.responses().size == beforeReady + 1 }
            assertThat(f.responses().last().path("response").path("instructions").asText()).contains("저장된 문제를 설명하세요.")
            f.completeAudioResponse("generated-readback", "generated-question")
            f.await("canonical answer card opens") { f.answerStates().lastOrNull()?.path("phase")?.asText() == "listening" }
            assertThat(tools.invocations.map { it.name }).containsExactly("select_voice_study", "list_pending_questions", "request_question")
            assertThat(tools.polled).isEmpty()
            val generation = f.ui.filter { it.path("type").asText() == Contract.OPERATION_EVENT && it.path("name").asText() == "question_generation" }
            assertThat(generation.map { it.path("phase").asText() }).containsExactly("started", "completed")
            assertThat(generation.map { it.path("operationId").asText() }.distinct()).hasSize(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `server observer reports saved grading completion while learner speaks without another model poll`() {
        val release = CompletableDeferred<Unit>()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }.apply {
            reviewedProgress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.GRADING, 7, "42", "grade-42")
            pollResponse = { progress -> release.await(); VoiceTutorMcpToolResult("{}", false,
                learningProgress = progress.copy(phase = VoiceTutorLearningPhase.GRADED)) }
        }
        Fixture(tools = tools).use { f ->
            val answer = f.manualQuestion()
            f.answerControl(Contract.ANSWER_FINISH_EVENT, answer)
            f.await("empty audio capture is reviewable") { f.answerStates().last().path("phase").asText() == "review" }
            f.answerControl(Contract.ANSWER_SUBMIT_EVENT, answer, "완성된 수정 답변")
            f.await("explicit submit creates its server call") { f.serverCalls().size == 1 }
            f.ack(f.serverCalls().single())
            f.await("server observation starts independently") { tools.polled.size == 1 }
            f.await("one accepted grading operation is visible while suspended") {
                f.ui.any { it.path("type").asText() == Contract.OPERATION_EVENT &&
                    it.path("name").asText() == "answer_grading" && it.path("phase").asText() == "started" }
            }
            assertThat(f.ui.any { it.path("type").asText() == Contract.OPERATION_EVENT &&
                it.path("name").asText() == "answer_grading" && it.path("phase").asText() == "completed" }).isFalse()
            f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STARTED_EVENT, "sequence" to 2)))
            val responseCount = f.responses().size
            release.complete(Unit)
            f.await("saved grade is displayed during newer speech") {
                f.ui.lastOrNull { it.path("type").asText() == Contract.SESSION_STATE_EVENT }?.path("phase")?.asText() == "graded"
            }
            f.await("the same background operation completes") { f.ui.any {
                it.path("type").asText() == Contract.OPERATION_EVENT && it.path("name").asText() == "answer_grading" &&
                    it.path("phase").asText() == "completed" } }
            val operationEvents = f.ui.filter { it.path("type").asText() == Contract.OPERATION_EVENT }
            val lookup = operationEvents.filter { it.path("name").asText() == "answer_grading" }
            assertThat(lookup.map { it.path("phase").asText() }).containsExactly("started", "completed")
            assertThat(lookup.map { it.path("operationId").asText() }.distinct()).hasSize(1)
            val submissionId = f.serverCalls().single().path("item").path("call_id").asText()
            val contexts = f.ui.filter { it.path("type").asText() == Contract.OPERATION_CONTEXT_EVENT }
            val submissionContext = contexts.single { it.path("operationId").asText() == submissionId }
            val lookupContext = contexts.single { it.path("operationId").asText() == lookup.first().path("operationId").asText() }
            for (field in listOf("responseId", "learnerItemId", "tutorItemId", "answerId")) {
                assertThat(lookupContext.path(field)).isEqualTo(submissionContext.path(field))
            }
            assertThat(lookupContext.path("answerId")).isEqualTo(answer.path("answerId"))
            assertThat(f.ui.indexOf(lookupContext)).isLessThan(f.ui.indexOf(lookup.first()))
            assertThat(operationEvents.map { it.path("sequence").asLong() }).isSorted().doesNotHaveDuplicates()
            assertThat(operationEvents.joinToString()).doesNotContain("완성된 수정 답변", "grade-42", "recordId")
            assertThat(f.responses()).hasSize(responseCount)
            assertThat(tools.invocations.map { it.name }).containsExactly("list_studies")
            assertThat(tools.polled).hasSize(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `server observation is cancelled with the control session and cannot publish a late completion`() {
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            learningProgress = VoiceTutorLearningProgress(VoiceTutorLearningPhase.QUESTION_GENERATING, 7, correlationId = "generation-1")) }.apply {
            pollResponse = { progress ->
                entered.set(true)
                try { CompletableDeferred<Unit>().await(); VoiceTutorMcpToolResult("{}", false, learningProgress = progress) }
                finally { cancelled.set(true) }
            }
        }
        val f = Fixture(tools = tools)
        try {
            f.opening(); f.learner(1, "learner-1"); f.transcript("learner-1")
            f.toolResponse("generate", "generate-call", "request_question")
            f.await("independent generation poll started") { entered.get() }
            f.close()
            f.await("session disposal cancelled the suspended read") { cancelled.get() }
            assertThat(f.ui.none { it.path("phase").asText() == "question_ready" }).isTrue()
        } finally { f.close() }
    }

    @Test
    fun `manual finish waits for its audit write then explicit edited submission uses only the reviewed port`() {
        val writeEntered = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }
        Fixture(tools = tools, store = { row ->
            if (row.path("item_id").asText() == "answer-1") { writeEntered.set(true); release.await() }
        }).use { f ->
            val answer = f.manualQuestion()
            f.capturedLearner(2, "answer-1")
            f.provider("conversation.item.input_audio_transcription.completed", "item_id" to "answer-1", "transcript" to "인식된 초안")
            f.answerControl(Contract.ANSWER_FINISH_EVENT, answer)
            f.await("answer write remains suspended in finalizing") {
                writeEntered.get() && f.answerStates().last().path("phase").asText() == "finalizing"
            }
            assertThat(f.responses()).hasSize(3)
            assertThat(tools.reviewed).isEmpty()
            release.complete(Unit)
            f.await("durable source releases review") { f.answerStates().last().path("phase").asText() == "review" }
            f.answerControl(Contract.ANSWER_SUBMIT_EVENT, answer, "사용자가 직접 수정한 답변")
            f.await("explicit submit emits a synthetic call") { f.serverCalls().size == 1 }
            assertThat(tools.reviewed).isEmpty()
            val request = f.serverCalls().single()
            assertThat(request.toString()).doesNotContain("사용자가 직접 수정한 답변")
            f.ack(request)
            f.await("reviewed port returned canonical output") { f.outputs().size == 2 }
            assertThat(tools.reviewed.single().second.text).isEqualTo("사용자가 직접 수정한 답변")
            assertThat(tools.reviewed.single().second.learnerProviderItemIds).containsExactly("answer-1")
            assertThat(tools.invocations.map { it.name }).containsExactly("list_studies")
            assertThat(f.responses()).hasSize(3)
            f.ack(f.outputs().last())
            f.await("canonical output acknowledgement releases feedback continuation") { f.responses().size == 4 }
            assertThat(f.answerStates().last().path("phase").asText()).isEqualTo("submitted")
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `cancelling a captured answer delivers a real app form and returns to dialogue without any answer writer`() {
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }
        Fixture(tools = tools, userInputEnabled = true).use { f ->
            val answer = f.manualQuestion()
            f.capturedLearner(2, "partial-answer")
            f.transcript("partial-answer", "아직 끝내지 않은 답변")
            f.await("private canonical answer source retained") {
                f.stored.any { it.path("item_id").asText() == "partial-answer" }
            }
            assertThat(f.stored.single { it.path("item_id").asText() == "partial-answer" }
                .path(Metadata.POST_CALL_EVIDENCE).asBoolean()).isFalse()
            f.answerControl(Contract.ANSWER_CANCEL_EVENT, answer)
            f.await("authenticated cancellation schedules exact form call") { f.serverCalls().size == 1 }
            f.ack(f.serverCalls().single())
            f.await("native tool worker publishes the next-action form") { f.inputRequests().size == 1 }
            val form = f.inputRequests().single()
            assertThat(f.answerStates().last().path("code").asText()).isEqualTo("ANSWER_CANCELLED")
            assertThat(form.path("questions")[0].path("options").map { it.path("id").asText() })
                .containsExactly("another_topic", "free_conversation", "later")
            assertThat(tools.reviewed).isEmpty()
            assertThat(tools.skipped).isEmpty()
            assertThat(tools.invocations.map { it.name }).containsExactly("list_studies")
            assertThat(f.responses()).hasSize(3)
            f.control(mapOf("type" to Contract.USER_INPUT_CANCEL_EVENT,
                "requestId" to form.path("requestId").asText(), "sessionId" to form.path("sessionId").asText(),
                "attemptId" to form.path("attemptId").asText()))
            f.await("cancelled choice requests clean input buffer") {
                f.outgoing.any { it.path("type").asText() == "input_audio_buffer.clear" }
            }
            f.provider("input_audio_buffer.cleared", "event_id" to "choice-clear")
            f.await("cancelled form result is final") { f.outputs().size == 2 }
            f.ack(f.outputs().last())
            f.await("one safe cancellation acknowledgement") { f.responses().size == 4 }
            assertThat(f.responses().last().path("response").path("tool_choice").asText()).isEqualTo("none")
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `model authored submit is rejected without invoking any canonical answer writer`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1")
            f.toolResponse("model-submit", "model-submit-call", "submit_answer")
            f.await("model submit receives explicit confirmation requirement") { f.outputs().size == 1 }
            val output = mapper.readTree(f.outputs().single().path("item").path("output").asText())
            assertThat(output.path("error").path("code").asText()).isEqualTo("USER_CONFIRMATION_REQUIRED")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.tools.reviewed).isEmpty()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `explicit capture skip uses the reviewed skip port and reopens ordinary dialogue only after result acknowledgement`() {
        val tools = FakeTools { VoiceTutorMcpToolResult("{}", false,
            questionReadback = VoiceTutorQuestionReadback(7, "42", "저장된 문제를 설명하세요.")) }
        Fixture(tools = tools).use { f ->
            val answer = f.manualQuestion()
            f.answerControl(Contract.ANSWER_SKIP_EVENT, answer)
            f.await("skip is scheduled only by explicit control") { f.serverCalls().size == 1 }
            f.ack(f.serverCalls().single())
            f.await("canonical skip returned") { f.outputs().size == 2 }
            assertThat(tools.skipped.single().second.recordId).isEqualTo("42")
            assertThat(tools.skipped.single().second.text).isEmpty()
            assertThat(tools.reviewed).isEmpty()
            f.await("canonical skip state reaches the independent client output stream") {
                f.answerStates().last().path("phase").asText() == "cancelled"
            }
            assertThat(f.responses()).hasSize(3)
            f.ack(f.outputs().last())
            f.await("skip continuation released") { f.responses().size == 4 }
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `renewed speech cancels an unstarted reply and the relay tick answers only the latest committed input`() {
        Fixture().use { f ->
            f.opening(); f.learner(1, "learner-1"); f.created("superseded")
            assertThat(f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STARTED_EVENT, "sequence" to 2))))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("exact unstarted provider response cancelled") {
                f.outgoing.any { it.path("type").asText() == "response.cancel" &&
                    it.path("response_id").asText() == "superseded" }
            }
            assertThat(f.controls.tryEmitNext(json(mapOf("type" to Contract.SPEECH_STOPPED_EVENT, "sequence" to 2))))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("newest learner buffer committed") {
                f.outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == 2
            }
            f.provider("input_audio_buffer.committed", "item_id" to "learner-2")
            f.provider("response.output_audio_transcript.done", "response_id" to "superseded",
                "item_id" to "stale-tutor", "transcript" to "Synthetic discarded response")
            f.provider("response.done", "response" to mapOf("id" to "superseded", "status" to "cancelled",
                "output" to listOf(mapOf("id" to "stale-tutor", "type" to "message", "content" to listOf(
                    mapOf("type" to "audio", "transcript" to ""))))))
            // No more inbound events: the relay clock must release the quiet deadline itself.
            f.await("quiet deadline releases newest native response without ASR") { f.responses().size == 3 }
            f.created("latest")
            f.provider("response.done", "response" to mapOf("id" to "latest", "status" to "completed",
                "output" to emptyList<Any>()))
            f.await("only newest acoustic input is settled") {
                f.ui.any { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT && it.path("sequence").asLong() == 2L }
            }
            assertThat(f.ui.filter { it.path("type").asText() == Contract.INPUT_SETTLED_EVENT }
                .map { it.path("sequence").asLong() }).containsExactly(2L)
            assertThat(f.ui.map { it.path("type").asText() }).doesNotContain(Contract.INPUT_RETRY_EVENT)
            assertThat(f.ui.map { it.path("item_id").asText() }).doesNotContain("stale-tutor")
            assertThat(f.stored.map { it.path("item_id").asText() }).doesNotContain("stale-tutor")
            assertThat(f.outgoing.count { it.path("type").asText() == "response.cancel" }).isEqualTo(1)
            assertThat(f.outgoing.map { it.path("type").asText() }).doesNotContain("output_audio_buffer.clear")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `a provider rejected opening recovers on the live relay and subsequent learner speech gets a reply`() {
        Fixture().use { f ->
            f.connect()
            val first = f.responses().single()
            f.provider("error", "error" to mapOf("type" to "invalid_request_error", "code" to "invalid_type",
                "event_id" to first.path("event_id").asText(), "param" to "response.metadata.buddystudy_quota_notice"))
            f.await("rejected create releases a bounded opening retry") { f.responses().size == 2 }
            f.completeAudioResponse("recovered-opening", "tutor-0")
            f.learner(1, "learner-1")
            f.completeAudioResponse("learner-reply", "tutor-1")
            f.await("audible learner reply is forwarded") {
                f.ui.any { it.path("type").asText() == "output_audio_buffer.stopped" &&
                    it.path("response_id").asText() == "learner-reply" }
            }

            assertThat(f.responses()).hasSize(3)
            assertThat(f.ui.map { it.path("type").asText() }).doesNotContain("error", Contract.INPUT_RETRY_EVENT)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `explicit user end waits for the private interrupted tutor archive before releasing relay workers`() {
        val saveArchive = CompletableDeferred<Unit>()
        val archiveSaved = AtomicBoolean()
        val archiveCancelled = AtomicBoolean()
        Fixture(store = { event ->
            if (event.path("type").asText() == Metadata.INTERRUPTED_TUTOR_EVENT) {
                try {
                    saveArchive.await()
                    archiveSaved.set(true)
                } catch (error: CancellationException) {
                    archiveCancelled.set(true)
                    throw error
                }
            }
        }).use { f ->
            f.opening(); f.learner(1, "learner-before-end"); f.transcript("learner-before-end", "Redis를 설명해줘.")
            f.created("ending-response")
            f.provider("response.output_item.added", "response_id" to "ending-response",
                "item" to mapOf("id" to "unfinished-tutor", "type" to "message"))
            f.provider("output_audio_buffer.started", "response_id" to "ending-response")
            f.provider("response.output_audio_transcript.delta", "response_id" to "ending-response",
                "item_id" to "unfinished-tutor", "delta" to "  Redis는 메모리에\n")
            f.await("the partial tutor caption has actually reached the client") {
                f.ui.any { it.path("item_id").asText() == "unfinished-tutor" && it.path("delta").asText() == "  Redis는 메모리에\n" }
            }
            // This is the terminal contract produced only by an explicit USER_ENDED action.
            f.terminate(VoiceTutorRelayTermination(cancelActiveResponse = true, preserveInterruptedTutor = true))
            f.await("exact private archive starts saving before terminal release") {
                f.stored.any { it.path("type").asText() == Metadata.INTERRUPTED_TUTOR_EVENT } &&
                    f.outgoing.any { it.path("type").asText() == "response.cancel" && it.path("response_id").asText() == "ending-response" }
            }
            val archive = f.stored.single { it.path("type").asText() == Metadata.INTERRUPTED_TUTOR_EVENT }
            assertThat(archive.path("item_id").asText()).isEqualTo("unfinished-tutor")
            assertThat(archive.path("transcript").asText()).isEqualTo("  Redis는 메모리에\n")
            assertThat(archive.path(Metadata.POST_CALL_EVIDENCE).isBoolean).isTrue()
            assertThat(archive.path(Metadata.POST_CALL_EVIDENCE).booleanValue()).isFalse()
            assertThat(archive.path(Metadata.CONVERSATION_SEQUENCE).asLong()).isEqualTo(3)
            assertThat(archive.path(Metadata.LESSON_REVISION).asLong()).isZero()
            assertThat(archive.path(Metadata.ACCEPTED_AT_EPOCH_MILLIS).asLong()).isPositive()
            assertThat(archive.has(Metadata.IS_STUDY_QUESTION)).isFalse()
            assertThat(archive.has(Metadata.STUDY_QUESTION_PROVIDER_ITEM_ID)).isFalse()
            assertThat(archive.has(Metadata.STUDY_ANSWER_PROVIDER_ITEM_ID)).isFalse()
            f.provider("response.output_audio_transcript.delta", "response_id" to "ending-response",
                "item_id" to "unfinished-tutor", "delta" to "LATE_GENERATED_TEXT")
            f.provider("response.output_audio_transcript.done", "response_id" to "ending-response",
                "item_id" to "unfinished-tutor", "transcript" to "LATE_GENERATED_TEXT")
            f.provider("response.done", "response" to mapOf("id" to "ending-response", "status" to "cancelled",
                "output" to listOf(mapOf("id" to "unfinished-tutor", "type" to "message", "content" to listOf(
                    mapOf("type" to "audio", "transcript" to "LATE_GENERATED_TEXT"))))))
            f.provider("output_audio_buffer.stopped", "response_id" to "ending-response")
            f.provider("conversation.item.created", "item" to mapOf("id" to "after-terminal-drain", "type" to "message"))
            f.await("provider cancellation and audio drain were processed while persistence is held") {
                f.ui.any { it.path("item").path("id").asText() == "after-terminal-drain" }
            }
            // The normal idle drain threshold is 200ms. A blocked archive must outlive it.
            f.assertRunningFor(Duration.ofMillis(350))
            assertThat(archiveSaved.get()).isFalse()
            assertThat(archiveCancelled.get()).isFalse()
            assertThat(f.receiveCancelled.get()).isFalse()
            assertThat(f.stored.map { it.path("item_id").asText() })
                .containsExactly("tutor-0", "learner-before-end", "unfinished-tutor")
            assertThat(f.stored.map { it.path(Metadata.CONVERSATION_SEQUENCE).asLong() }).containsExactly(1, 2, 3)
            assertThat(f.ui.none { it.path("type").asText() == Metadata.INTERRUPTED_TUTOR_EVENT }).isTrue()
            assertThat(f.ui.joinToString { it.toString() }).doesNotContain("LATE_GENERATED_TEXT")
            assertThat(f.responses()).hasSize(2)
            saveArchive.complete(Unit)
            f.await("successful archive persistence releases the terminal relay") { f.completed.get() }
            assertThat(archiveSaved.get()).isTrue()
            assertThat(archiveCancelled.get()).isFalse()
            assertThat(f.receiveCancelled.get()).isTrue()
            assertThat(f.stored.count { it.path("item_id").asText() == "unfinished-tutor" }).isEqualTo(1)
            assertThat(f.stored.last().path("transcript").asText()).isEqualTo("  Redis는 메모리에\n")
            assertThat(f.integrityMarkers).isEmpty()
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `ordinary native reply proceeds while tutor audit persistence is suspended and learner ASR is absent`() {
        val release = CompletableDeferred<Unit>()
        val entered = AtomicBoolean()
        Fixture(store = {
            entered.set(true)
            release.await()
        }).use { f ->
            f.opening()
            f.await("opening audit started") { entered.get() }

            f.learner(1, "learner-1")

            assertThat(f.responses()).hasSize(2)
            assertThat(release.isCompleted).isFalse()
            assertThat(f.stored.map { it.path("item_id").asText() }).containsExactly("tutor-0")
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.ui.any { it.path("type").asText() == Contract.SIDEBAND_READY_EVENT }).isTrue()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `read tools bypass transcript IO but a continuation requires the exact tool output acknowledgement`() {
        val auditRelease = CompletableDeferred<Unit>()
        Fixture(store = { auditRelease.await() }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.toolResponse("response-1", "read-1", "list_studies")
            f.await("read tool executed without persisted transcript") { f.outputs().size == 1 }

            val invocation = f.tools.invocations.single()
            assertThat(invocation.name).isEqualTo("list_studies")
            assertThat(invocation.context.realtimeModelTools).isTrue()
            assertThat(invocation.context.dialogueBoundary?.latestAcceptedLearnerProviderItemId)
                .isEqualTo("learner-1")
            assertThat(auditRelease.isCompleted).isFalse()
            assertThat(f.responses()).hasSize(2)

            val output = f.outputs().single()
            val wrongItem = output.path("item").deepCopy<ObjectNode>().put("output", "{\"wrong\":true}")
            f.provider("conversation.item.created", "item" to wrongItem)
            f.await("unmatched output acknowledgement processed") {
                f.ui.any { it.path("item").path("output").asText() == "{\"wrong\":true}" }
            }
            assertThat(f.responses()).hasSize(2)

            f.ack(output)
            f.await("exact tool result released one continuation") { f.responses().size == 3 }
            f.ack(output)
            f.await("duplicate acknowledgement processed") {
                f.ui.any { it.path("item") == output.path("item") }
            }
            assertThat(f.responses()).hasSize(3)
            assertThat(f.tools.invocations).hasSize(1)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `mutation tools wait for both tutor and learner transcript IO before executing`() {
        val tutorRelease = CompletableDeferred<Unit>()
        val learnerRelease = CompletableDeferred<Unit>()
        val tutorStored = AtomicBoolean()
        val learnerStored = AtomicBoolean()
        val storedAtExecution = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val tools = FakeTools {
            storedAtExecution.add(tutorStored.get() to learnerStored.get())
            success()
        }
        Fixture(tools = tools, store = { row ->
            when (row.path("item_id").asText()) {
                "tutor-0" -> { tutorRelease.await(); tutorStored.set(true) }
                "learner-1" -> { learnerRelease.await(); learnerStored.set(true) }
            }
        }).use { f ->
            f.opening()
            f.await("tutor audit started") { f.stored.size == 1 }
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.toolResponse("response-1", "prepare-1", "prepare_voice_study_mutation")
            // Tool-only response events are deliberately hidden from the UI.
            // A later provider frame is a FIFO processing barrier, not an audible response.
            f.provider("conversation.item.created", "item" to mapOf("id" to "mutation-io-barrier", "type" to "message"))
            f.await("provider processed the mutation response before the barrier") {
                f.ui.any { it.path("item").path("id").asText() == "mutation-io-barrier" }
            }
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.ui.none { it.path("type").asText() == "response.done" && it.path("response").path("id").asText() == "response-1" }).isTrue()

            tutorRelease.complete(Unit)
            f.await("learner audit started after tutor write") { f.stored.size == 2 }
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.outputs()).isEmpty()

            learnerRelease.complete(Unit)
            f.await("mutation runs only after its two audit writes settle") { f.outputs().size == 1 }
            assertThat(f.tools.invocations.map { it.name }).containsExactly("prepare_voice_study_mutation")
            assertThat(storedAtExecution).containsExactly(true to true)
            assertThat(f.responses()).hasSize(2)
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `normal completion of the control worker does not cancel an in flight tool or close provider IO`() {
        val result = CompletableDeferred<VoiceTutorMcpToolResult>()
        val cancelled = AtomicBoolean()
        val tools = FakeTools {
            try { result.await() }
            catch (error: CancellationException) { cancelled.set(true); throw error }
        }
        Fixture(tools = tools).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.toolResponse("response-1", "read-1", "list_studies")
            f.await("tool began") { tools.invocations.size == 1 }
            assertThat(f.controls.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK)

            // A real provider item event proves the receive/client workers are
            // still processing after the unrelated control source completed.
            f.provider("conversation.item.created", "item" to mapOf("id" to "worker-barrier", "type" to "message"))
            f.await("provider remained connected after control completion") {
                f.ui.any { it.path("item").path("id").asText() == "worker-barrier" }
            }
            assertThat(cancelled.get()).isFalse()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.receiveCancelled.get()).isFalse()

            result.complete(success())
            f.await("in flight tool output survived control completion") { f.outputs().size == 1 }
            f.ack(f.outputs().single())
            f.await("tool continuation survived control completion") { f.responses().size == 3 }
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `provider failure cancels suspended persistence but forces a durable integrity fence before finalization`() {
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        Fixture(store = {
            entered.set(true)
            try { CompletableDeferred<Unit>().await() }
            catch (error: CancellationException) { cancelled.set(true); throw error }
        }).use { f ->
            f.opening()
            f.await("audit write is suspended") { entered.get() }
            val failure = IllegalStateException("synthetic provider failure")
            assertThat(f.incoming.tryEmitError(failure)).isEqualTo(Sinks.EmitResult.OK)

            f.await("provider failure escaped while persistence was blocked") { f.errors.isNotEmpty() }
            f.await("blocked persistence was cancelled") { cancelled.get() }
            assertThat(f.errors).hasSize(1)
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `provider error after a committed learner turn with absent ASR cannot grade an earlier saved prefix`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            assertThat(f.incoming.tryEmitError(IllegalStateException("synthetic receive failure")))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("missing learner source is fenced on receive failure") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `client output worker failure also fences unresolved learner source without waiting for ASR`() {
        Fixture(onUi = { event ->
            if (event.path("item").path("id").asText() == "fatal-ui-event") {
                throw IllegalStateException("synthetic client output failure")
            }
        }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.provider("conversation.item.created", "item" to mapOf("id" to "fatal-ui-event", "type" to "message"))
            f.await("client output failure fences source") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `unacknowledged learner commit is incomplete evidence even before provider assigns an item id`() {
        Fixture().use { f ->
            f.opening()
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(f.controls.tryEmitNext(json(mapOf("type" to type, "sequence" to 1))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            f.await("learner commit sent without acknowledgement") {
                f.outgoing.any { it.path("type").asText() == "input_audio_buffer.commit" }
            }
            assertThat(f.incoming.tryEmitError(IllegalStateException("synthetic commit failure")))
                .isEqualTo(Sinks.EmitResult.OK)
            f.await("unacknowledged commit is fenced") { f.errors.isNotEmpty() }
            assertThat(f.errors.single()).isInstanceOf(VoiceTutorTranscriptIntegrityException::class.java)
        }
    }

    @Test
    fun `a provider error with only fully stored source preserves the original failure`() {
        val stored = AtomicBoolean()
        Fixture(store = { stored.set(true) }).use { f ->
            f.opening()
            f.await("opening source stored") { stored.get() }
            // FIFO persistence completion is synchronous after this non-suspending store callback.
            f.provider("conversation.item.created", "item" to mapOf("id" to "clean-source-barrier", "type" to "message"))
            f.await("provider remained idle with clean source") {
                f.ui.any { it.path("item").path("id").asText() == "clean-source-barrier" }
            }
            val failure = IllegalStateException("synthetic idle receive failure")
            assertThat(f.incoming.tryEmitError(failure)).isEqualTo(Sinks.EmitResult.OK)
            f.await("original error preserved") { f.errors.isNotEmpty() }
            assertThat(f.errors).containsExactly(failure)
        }
    }

    @Test
    fun `completed audible tutor output missing its transcript is fenced rather than treated as a silent turn`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.created("response-1")
            f.provider("response.output_item.added", "response_id" to "response-1",
                "item" to mapOf("id" to "missing-tutor-source", "type" to "message"))
            f.provider("output_audio_buffer.started", "response_id" to "response-1")
            f.provider("response.done", "response" to mapOf("id" to "response-1", "status" to "completed",
                "output" to listOf(mapOf("id" to "missing-tutor-source", "type" to "message",
                    "content" to listOf(mapOf("type" to "audio"))))))
            f.provider("output_audio_buffer.stopped", "response_id" to "response-1")
            f.await("missing completed tutor source fenced") { f.integrityMarkers.size == 1 }
            assertThat(f.errors).isEmpty()
            assertThat(f.completed.get()).isFalse()
        }
    }

    @Test
    fun `audit write failures do not terminate ordinary voice or cause automatic tool execution`() {
        Fixture(store = { throw IllegalStateException("synthetic audit failure") }).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.transcript("learner-1")
            f.await("both audit writes were attempted") { f.stored.size == 2 }
            f.created("response-1")
            f.provider("response.done", "response" to mapOf(
                "id" to "response-1", "status" to "completed", "output" to emptyList<Any>(),
            ))
            f.learner(2, "learner-2")
            f.await("failed audit writes permanently mark incomplete evidence") { f.integrityMarkers.size == 1 }

            assertThat(f.responses()).hasSize(3)
            assertThat(f.tools.invocations).isEmpty()
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `a rejected transcript write marks incomplete evidence without blocking ordinary voice`() {
        Fixture(persistAccepted = false).use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.await("rejected write records incomplete evidence") { f.integrityMarkers.size == 1 }
            assertThat(f.stored).hasSize(1)
            assertThat(f.responses()).hasSize(2)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `failed ASR marks incomplete evidence without blocking the native audio response`() {
        Fixture().use { f ->
            f.opening()
            f.learner(1, "learner-1")
            f.provider("conversation.item.input_audio_transcription.failed", "item_id" to "learner-1")
            f.await("failed ASR records incomplete evidence") { f.integrityMarkers.size == 1 }
            assertThat(f.responses()).hasSize(2)
            assertThat(f.completed.get()).isFalse()
            assertThat(f.errors).isEmpty()
        }
    }

    @Test
    fun `unconfirmed integrity marker fails the relay instead of allowing partial learning evidence`() {
        val markerResult = CompletableDeferred<Boolean>()
        Fixture(store = { throw IllegalStateException("synthetic audit failure") }, markIncomplete = { markerResult.await() }).use { f ->
            f.opening()
            f.await("incomplete marker was attempted") { f.integrityMarkers.size == 1 }
            markerResult.complete(false)
            f.await("unconfirmed marker fails the call") { f.errors.isNotEmpty() }
            assertThat(f.completed.get()).isFalse()
            assertThat(f.tools.invocations).isEmpty()
        }
    }

    private class Fixture(
        val tools: FakeTools = FakeTools(),
        store: suspend (JsonNode) -> Unit = {},
        persistAccepted: Boolean = true,
        markIncomplete: suspend () -> Boolean = { true },
        onUi: suspend (JsonNode) -> Unit = {},
        userInputEnabled: Boolean = false,
    ) : AutoCloseable {
        val incoming = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
        val controls = Sinks.many().unicast().onBackpressureBuffer<String>()
        private val terminal = Sinks.many().unicast().onBackpressureBuffer<VoiceTutorRelayTermination>()
        val outgoing = CopyOnWriteArrayList<JsonNode>()
        val stored = CopyOnWriteArrayList<JsonNode>()
        val ui = CopyOnWriteArrayList<JsonNode>()
        val integrityMarkers = CopyOnWriteArrayList<JsonNode>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val completed = AtomicBoolean()
        val receiveCancelled = AtomicBoolean()
        private val changed = Semaphore(0)
        private val buffers = DefaultDataBufferFactory.sharedInstance
        private val subscription: Disposable

        init {
            val socket = Proxy.newProxyInstance(WebSocketSession::class.java.classLoader,
                arrayOf(WebSocketSession::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getId" -> "native-relay-fixture"
                    "receive" -> incoming.asFlux().doOnCancel { receiveCancelled.set(true) }
                    "send" -> Flux.from(arguments!![0] as Publisher<*>)
                        .cast(WebSocketMessage::class.java)
                        .doOnNext {
                            val event = mapper.readTree(it.payloadAsText)
                            if (event.path("type").asText() == "response.create") {
                                // Emulate the provider's metadata schema, not an unchecked echo fixture.
                                assertThat(event.path("response").path("metadata").all { value -> value.isTextual })
                                    .withFailMessage("Realtime response metadata values must be strings").isTrue()
                            }
                            outgoing.add(event); changed.release()
                        }.then()
                    "textMessage" -> message(arguments!![0] as String)
                    "bufferFactory" -> buffers
                    "isOpen" -> true
                    "close", "closeStatus" -> Mono.empty<Void>()
                    "toString" -> "NativeVoiceRelayFixture"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> error("Unexpected WebSocketSession call: ${method.name}")
                }
            } as WebSocketSession
            subscription = relayVoiceTutorNativeSession(
                socket, context().copy(userInputEnabled = userInputEnabled), controls.asFlux().asFlow(), terminal.asFlux().asFlow(), tools,
                connectTimeout = Duration.ofSeconds(3), responseTimeout = Duration.ofSeconds(15),
            ) { raw, persist, forward ->
                val event = mapper.readTree(raw)
                if (event.path("type").asText() == Metadata.INCOMPLETE_EVENT) {
                    assertThat(persist).isFalse()
                    assertThat(forward).isFalse()
                    integrityMarkers.add(event)
                    changed.release()
                    return@relayVoiceTutorNativeSession markIncomplete()
                }
                if (persist) {
                    stored.add(event)
                    changed.release()
                    store(event)
                }
                if (forward) { ui.add(event); changed.release(); onUi(event) }
                persist && persistAccepted
            }.subscribe({}, { errors += it; changed.release() }, { completed.set(true); changed.release() })
        }

        fun opening() {
            connect()
            completeAudioResponse("response-0", "tutor-0")
        }

        fun connect() {
            await("server-owned session update") { outgoing.any { it.path("type").asText() == "session.update" } }
            val update = outgoing.first { it.path("type").asText() == "session.update" }
            assertThat(update.path("session").path("audio").path("input").path("turn_detection").isNull).isTrue()
            provider("session.updated", "session" to update.path("session"))
            await("opening native response") { responses().size == 1 }
        }

        fun completeAudioResponse(responseId: String, itemId: String, text: String = "어떤 주제로 이야기할까요?") {
            created(responseId)
            provider("response.output_item.added", "response_id" to responseId,
                "item" to mapOf("id" to itemId, "type" to "message"))
            provider("output_audio_buffer.started", "response_id" to responseId)
            provider("response.output_audio_transcript.done", "response_id" to responseId,
                "item_id" to itemId, "transcript" to text)
            provider("response.done", "response" to mapOf("id" to responseId, "status" to "completed",
                "output" to listOf(mapOf("id" to itemId, "type" to "message", "content" to listOf(
                    mapOf("type" to "audio", "transcript" to text))))))
            provider("output_audio_buffer.stopped", "response_id" to responseId)
        }

        fun learner(sequence: Long, id: String) {
            val previousResponses = responses().size
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(controls.tryEmitNext(json(mapOf("type" to type, "sequence" to sequence))))
                    .isEqualTo(Sinks.EmitResult.OK)
            }
            await("acoustic stop committed by the server") {
                outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == sequence.toInt()
            }
            provider("input_audio_buffer.committed", "item_id" to id)
            await("native reply released without a text assessor") { responses().size == previousResponses + 1 }
        }

        fun transcript(id: String, text: String = "Redis를 공부하고 싶어요.") = provider("conversation.item.input_audio_transcription.completed",
            "item_id" to id, "transcript" to text)

        fun manualQuestion(): JsonNode {
            opening(); learner(1, "learner-1"); transcript("learner-1")
            toolResponse("question-tool", "question-call", "list_studies")
            await("saved question tool result") { outputs().size == 1 }
            ack(outputs().single())
            await("saved question readback response") { responses().size == 3 }
            completeAudioResponse("readback", "saved-question")
            await("readback drained and manual capture open") { answerStates().lastOrNull()?.path("phase")?.asText() == "listening" }
            return answerStates().last()
        }

        fun capturedLearner(sequence: Long, id: String) {
            for (type in listOf(Contract.SPEECH_STARTED_EVENT, Contract.SPEECH_STOPPED_EVENT)) {
                assertThat(controls.tryEmitNext(json(mapOf("type" to type, "sequence" to sequence)))).isEqualTo(Sinks.EmitResult.OK)
            }
            await("captured speech committed without response") {
                outgoing.count { it.path("type").asText() == "input_audio_buffer.commit" } == sequence.toInt()
            }
            provider("input_audio_buffer.committed", "item_id" to id)
        }

        fun answerControl(type: String, answer: JsonNode, text: String? = null) {
            val event = linkedMapOf<String, Any>("type" to type, "answerId" to answer.path("answerId").asText(),
                "recordId" to answer.path("recordId").asText())
            text?.let { event["text"] = it }
            assertThat(controls.tryEmitNext(json(event))).isEqualTo(Sinks.EmitResult.OK)
        }

        fun answerStates() = ui.filter { it.path("type").asText() == Contract.ANSWER_STATE_EVENT }
        fun inputRequests() = ui.filter { it.path("type").asText() == Contract.USER_INPUT_REQUEST_EVENT }
        fun inputStates() = ui.filter { it.path("type").asText() == Contract.USER_INPUT_STATE_EVENT }
        fun control(event: Map<String, Any>) {
            assertThat(controls.tryEmitNext(json(event))).isEqualTo(Sinks.EmitResult.OK)
        }
        fun terminate(event: VoiceTutorRelayTermination) {
            assertThat(terminal.tryEmitNext(event)).isEqualTo(Sinks.EmitResult.OK)
        }
        fun assertRunningFor(duration: Duration) {
            val deadline = System.nanoTime() + duration.toNanos()
            while (System.nanoTime() < deadline) {
                assertThat(completed.get()).withFailMessage("Relay released a pending transcript write").isFalse()
                assertThat(errors).isEmpty()
                changed.tryAcquire(20, TimeUnit.MILLISECONDS)
            }
        }
        fun serverCalls() = outgoing.filter { it.path("item").path("type").asText() == "function_call" }

        fun created(id: String) = provider("response.created", "response" to mapOf("id" to id,
            "metadata" to responses().last().path("response").path("metadata")))

        fun toolResponse(responseId: String, callId: String, name: String, arguments: String = "{}") {
            created(responseId)
            provider("response.done", "response" to mapOf("id" to responseId, "status" to "completed",
                "output" to listOf(mapOf("id" to "item-$callId", "type" to "function_call",
                    "status" to "completed", "call_id" to callId, "name" to name, "arguments" to arguments))))
        }

        fun provider(type: String, vararg fields: Pair<String, Any>) {
            assertThat(incoming.tryEmitNext(message(json(mapOf("type" to type) + fields))))
                .isEqualTo(Sinks.EmitResult.OK)
        }

        fun ack(output: JsonNode) = provider("conversation.item.created", "item" to output.path("item"))
        fun responses() = outgoing.filter { it.path("type").asText() == "response.create" }
        fun outputs() = outgoing.filter { it.path("item").path("type").asText() == "function_call_output" }

        fun await(reason: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while (!condition() && System.nanoTime() < deadline) changed.tryAcquire(20, TimeUnit.MILLISECONDS)
            assertThat(condition()).withFailMessage("Timed out: %s; completed=%s, errors=%s", reason,
                completed.get(), errors.map { it.javaClass.simpleName }).isTrue()
        }

        private fun message(raw: String) = WebSocketMessage(WebSocketMessage.Type.TEXT, buffers.wrap(raw.toByteArray()))
        override fun close() { subscription.dispose() }
    }

    private data class Invocation(val context: VoiceTutorWebRtcControlContext, val name: String, val arguments: Map<String, Any>)
    private class FakeTools(private val result: suspend () -> VoiceTutorMcpToolResult = { success() }) : VoiceTutorMcpToolPort {
        val topicPreparations = CopyOnWriteArrayList<Long>()
        val topicSubmissions = CopyOnWriteArrayList<List<Int>>()
        var topicResult: suspend () -> VoiceTutorMcpToolResult = { success() }
        override suspend fun prepareStudyTopicUserInput(context: VoiceTutorWebRtcControlContext, parentStudyId: Long,
            topics: List<String>, difficultyLevel: Int) = VoiceTutorStudyTopicUserInput("prepared-topics", "주제 추가",
            "제출하면 선택한 주제를 추가합니다.", topics).also { topicPreparations += parentStudyId }
        override suspend fun submitStudyTopicUserInput(context: VoiceTutorWebRtcControlContext, proposalId: String,
            selectedIndices: List<Int>): VoiceTutorMcpToolResult {
            assertThat(proposalId).isEqualTo("prepared-topics")
            topicSubmissions += selectedIndices
            return topicResult()
        }
        val invocations = CopyOnWriteArrayList<Invocation>()
        val polled = CopyOnWriteArrayList<VoiceTutorLearningProgress>()
        var progressEvents: Flow<VoiceTutorMcpToolResult>? = null
        override fun observeLearningProgress(context: VoiceTutorWebRtcControlContext, progress: VoiceTutorLearningProgress): Flow<VoiceTutorMcpToolResult> =
            progressEvents ?: super.observeLearningProgress(context, progress)
        var reviewedProgress: VoiceTutorLearningProgress? = null
        var pollResponse: suspend (VoiceTutorLearningProgress) -> VoiceTutorMcpToolResult = { progress -> VoiceTutorMcpToolResult("{}", false, learningProgress = progress) }
        override suspend fun pollLearningProgress(context: VoiceTutorWebRtcControlContext, progress: VoiceTutorLearningProgress): VoiceTutorMcpToolResult {
            polled += progress
            return pollResponse(progress)
        }
        val reviewed = CopyOnWriteArrayList<Pair<VoiceTutorWebRtcControlContext, VoiceTutorReviewedAnswer>>()
        val skipped = CopyOnWriteArrayList<Pair<VoiceTutorWebRtcControlContext, VoiceTutorReviewedAnswer>>()
        override fun definitions(): List<VoiceTutorMcpToolDefinition> = error("The legacy classified tool catalog must not be used")
        override fun realtimeDefinitions() = listOf("list_studies", "prepare_voice_study_mutation", "confirm_voice_study_mutation", "request_question", "select_voice_study", "list_pending_questions").map { name ->
            VoiceTutorMcpToolDefinition(name, "Synthetic native tool", mapOf("type" to "object",
                "properties" to emptyMap<String, Any>(), "additionalProperties" to false))
        }
        override suspend fun execute(context: VoiceTutorWebRtcControlContext, toolName: String, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
            invocations += Invocation(context, toolName, arguments)
            return result()
        }
        override suspend fun submitReviewedAnswer(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
            reviewed += context to answer
            return VoiceTutorMcpToolResult("""{"queued":true,"gradingRequestId":"grade-42"}""", false, learningProgress = reviewedProgress)
        }
        override suspend fun skipReviewedQuestion(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
            skipped += context to answer
            return VoiceTutorMcpToolResult("""{"skipped":true,"recordId":"42"}""", false)
        }
    }

    private companion object {
        val mapper = JsonMapperProvider.mapper
        fun json(value: Any): String = mapper.writeValueAsString(value)
        fun success() = VoiceTutorMcpToolResult("{\"studies\":[]}", false)
        fun ordinaryInputArguments(mode: String): Map<String, Any> = mapOf<String, Any>(
            "title" to "다음 공부 방향", "questions" to listOf(mapOf<String, Any>(
                "id" to "direction", "prompt" to "어떤 방향으로 공부할까요?", "selectionMode" to mode,
                "options" to (if (mode == "text") emptyList<Map<String, String>>() else listOf(
                    mapOf("id" to "redis", "label" to "Redis"), mapOf("id" to "kafka", "label" to "Kafka"),
                    mapOf("id" to "msa", "label" to "MSA"))),
                "allowFreeText" to true,
            )),
        )
        fun userInputControl(type: String, request: JsonNode, answers: List<Map<String, Any>>? = null): Map<String, Any> =
            linkedMapOf<String, Any>("type" to type, "requestId" to request.path("requestId").asText(),
                "sessionId" to request.path("sessionId").asText(), "attemptId" to request.path("attemptId").asText())
                .apply { answers?.let { put("answers", it) } }
        fun context(): VoiceTutorWebRtcControlContext {
            val now = Instant.now()
            return VoiceTutorWebRtcControlContext(VoiceTutorSession(
                id = "00000000-0000-4000-8000-000000000007", userId = 7, studyId = null, idempotencyKey = "native-test-call",
                providerSessionId = "rtc_native_test", status = VoiceTutorSessionStatus.ACTIVE,
                resultStatus = VoiceTutorResultStatus.PENDING, language = "ko", model = "gpt-realtime",
                voice = "marin", topic = "", difficulty = 5, periodStartedAt = now,
                periodEndsAt = now.plusSeconds(86_400), reservedSeconds = 3_600, chargedSeconds = 0,
                maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600), connectedAt = now,
                relayHeartbeatAt = now, acceptedAudioBytes = 0, endedAt = null, finalizedAt = null,
                endReason = null, failureCode = null, failureMessage = null, createdAt = now, updatedAt = now,
            ), "rtc_native_test")
        }
    }
}
