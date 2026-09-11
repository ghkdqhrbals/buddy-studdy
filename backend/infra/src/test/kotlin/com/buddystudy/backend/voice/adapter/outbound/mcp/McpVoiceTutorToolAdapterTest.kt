package com.buddystudy.backend.voice.adapter.outbound.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeLogger
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorizationPurpose
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorizationScope
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateTargetProof
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyDeletionAuthorization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningPhase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMutationConfirmationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearnerTurnAuthorization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistedDialogueBoundary
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.backend.voice.adapter.outbound.openai.voiceTutorLessonFocusEvent
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class McpVoiceTutorToolAdapterTest {
    @Test
    fun `native learning cancellation stops only the authorized call continuation without any study or question write`() = runBlocking<Unit> {
        val fixture = Fixture()
        val definition = fixture.adapter.realtimeDefinitions().single { it.name == "cancel_voice_learning" }
        assertThat(definition.parameters).containsEntry("additionalProperties", false)
        assertThat(definition.parameters["properties"]).isEqualTo(emptyMap<String, Any>())
        assertThat(fixture.adapter.definitions().map { it.name }).doesNotContain("cancel_voice_learning")
        val result = fixture.adapter.execute(nativeContext().copy(operationStillCurrent = { true }),
            "cancel_voice_learning", emptyMap())
        assertThat(result.isError).isFalse()
        assertThat(result.learningContinuationCancelled).isTrue()
        assertThat(result.lessonFocusCleared).isFalse()
        assertThat(result.lessonRevision).isNull()
        assertThat(result.questionChange).isNull()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(json(result).path("cancelled").asBoolean()).isTrue()
        assertThat(fixture.calls).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.persistedSession).isEqualTo(session())
    }

    @Test
    fun `learning cancellation rejects legacy calls extra arguments revoked sessions and superseded turns`() = runBlocking<Unit> {
        val fixture = Fixture()
        assertCode(fixture.adapter.execute(context(), "cancel_voice_learning", emptyMap()), "TOOL_NOT_ALLOWED")
        assertCode(fixture.adapter.execute(nativeContext(), "cancel_voice_learning", mapOf("study_id" to 101L)), "INVALID_ARGUMENTS")
        assertCode(fixture.adapter.execute(nativeContext().copy(operationStillCurrent = { false }),
            "cancel_voice_learning", emptyMap()), "STALE_TURN")
        fixture.authorized = false
        val denied = fixture.adapter.execute(nativeContext(), "cancel_voice_learning", emptyMap())
        assertCode(denied, "CALL_NOT_AUTHORIZED")
        assertThat(denied.learningContinuationCancelled).isFalse()
        assertThat(fixture.calls).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `native question arguments are rejected before any curriculum lookup or creation`() = runBlocking<Unit> {
        for (tool in listOf("request_question", "list_pending_questions")) {
            for (args in listOf(emptyMap(), mapOf("study_id" to 101.5), mapOf("study_id" to "101"),
                mapOf("study_id" to -1), mapOf("study_id" to 101L, "unexpected" to true))) {
                val fixture = Fixture(studyContexts = ContextStore())
                val result = fixture.adapter.execute(nativeContext().copy(userInputEnabled = true), tool, args)
                assertCode(result, "INVALID_ARGUMENTS")
                assertThat(fixture.calls).isEmpty()
                assertThat(fixture.focusSelections).isEmpty()
            }
        }
    }

    @Test
    fun `native discovery defaults to ten rows without changing explicit or HTTP limits`() = runBlocking<Unit> {
        val fixture = Fixture().apply { handler = { _, _ -> success(mapOf("studies" to emptyList<Any>(), "totalCount" to 0, "offset" to 0)) } }
        assertThat(fixture.adapter.execute(nativeContext(), "list_studies", emptyMap()).isError).isFalse()
        assertThat(fixture.calls.last().arguments).containsEntry("limit", 10).containsEntry("offset", 0)
        fixture.adapter.execute(nativeContext(), "list_studies", mapOf("limit" to 3, "offset" to 2))
        assertThat(fixture.calls.last().arguments).containsEntry("limit", 3).containsEntry("offset", 2)
        fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(fixture.calls.last().arguments).isEmpty()
    }

    @Test
    fun `native selected descendant prepares root level curriculum then exact durable GUI choice selects terminal topic`() = runBlocking<Unit> {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts)
        var created = false
        fixture.handler = { name, args -> when (name) {
            "get_study" -> when ((args.getValue("study_id") as Number).toLong()) {
                101L -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "MSA", "difficultyLevel" to 8))
                102L -> success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Service communication", "difficultyLevel" to 2))
                201L -> success(mapOf("id" to 201L, "parentStudyId" to 102L, "topic" to "Event delivery", "difficultyLevel" to 8, "curriculumTerminal" to true))
                else -> error("Unknown saved node")
            }
            "list_studies" -> {
                assertThat(args["parent_study_id"]).isEqualTo(102L)
                success(mapOf("studies" to (if (created) listOf(mapOf("id" to 201L, "parentStudyId" to 102L,
                    "topic" to "Event delivery", "difficultyLevel" to 8, "curriculumTerminal" to true)) else emptyList<Any>())))
            }
            "suggest_study_topics" -> success(mapOf("suggestions" to listOf("Event delivery"),
                "topicDetails" to listOf(mapOf("topic" to "Event delivery", "curriculumTerminal" to true))))
            "create_study_topics" -> {
                assertThat(args["parent_study_id"]).isEqualTo(102L)
                assertThat(args["difficulty_level"]).isEqualTo(8)
                assertThat(args[BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT]).isEqualTo(true)
                assertThat(args[BuddyStudyMcpPort.VOICE_CURRICULUM_TERMINALS_ARGUMENT]).isEqualTo(mapOf("Event delivery" to true))
                created = true
                success(mapOf("topics" to listOf(mapOf("id" to 201L, "created" to true))))
            }
            else -> error("No question tool belongs to curriculum preparation: $name")
        } }
        val context = nativeContext().copy(userInputEnabled = true, operationStillCurrent = { true })
        val prepared = fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 102L))
        assertThat(prepared.isError).isFalse()
        val card = requireNotNull(prepared.curriculumInput)
        assertThat(card.prompt).contains("MSA", "Service communication", "8")
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(prepared.changedStudyIds).containsExactly(201L)
        fixture.acceptedProviderItemId = "structured-curriculum-choice"
        fixture.learnerTurnId = 13
        fixture.focusResult = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(201, 1),
            VoiceTutorStudySnapshot(201, 102, "Event delivery", 8, 1))
        fixture.afterFocus = { contexts.revision = 1 }
        val submitted = context.copy(dialogueBoundary = context.dialogueBoundary!!.copy(
            latestAcceptedLearnerProviderItemId = fixture.acceptedProviderItemId))
        val selected = fixture.adapter.submitCurriculumUserInput(submitted, card.proposalId, 0, "")
        assertThat(selected.isError).isFalse()
        assertThat(selected.lessonFocus?.studyId).isEqualTo(201L)
        assertThat(selected.lessonRevision).isEqualTo(1)
        assertThat(fixture.focusSelections).containsExactly(201L)
        assertThat(fixture.calls.count { it.name == "create_study_topics" }).isEqualTo(1)
        assertThat(fixture.adapter.submitCurriculumUserInput(submitted, card.proposalId, 0, "")).isEqualTo(selected)
        assertThat(fixture.focusSelections).containsExactly(201L)
    }

    @Test
    fun `new learner speech during native suggestions at same lesson revision prevents obsolete child creation`() = runBlocking<Unit> {
        val fixture = Fixture(studyContexts = ContextStore())
        var originalSpeechIsCurrent = true
        fixture.handler = { name, _ -> when (name) {
            "get_study" -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Redis", "difficultyLevel" to 8))
            "list_studies" -> success(mapOf("studies" to emptyList<Any>()))
            "suggest_study_topics" -> {
                originalSpeechIsCurrent = false
                success(mapOf("suggestions" to listOf("Eviction"), "topicDetails" to listOf(mapOf("topic" to "Eviction", "curriculumTerminal" to true))))
            }
            else -> error("A superseded direction must not write: $name")
        } }
        val result = fixture.adapter.execute(nativeContext().copy(userInputEnabled = true,
            operationStillCurrent = { originalSpeechIsCurrent }), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isTrue()
        assertThat(result.curriculumInput).isNull()
        assertThat(fixture.calls.none { it.name == "create_study_topics" }).isTrue()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `GUI topic creation inherits original root eight despite selected parent two and legacy hint five`() = runBlocking<Unit> {
        val fixture = Fixture(studyContexts = ContextStore())
        fixture.handler = { name, args -> when (name) {
            "get_study" -> if ((args.getValue("study_id") as Number).toLong() == 101L)
                success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "MSA", "difficultyLevel" to 8))
            else success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Communication", "difficultyLevel" to 2))
            "create_study_topics" -> {
                assertThat(args["parent_study_id"]).isEqualTo(102L)
                assertThat(args["difficulty_level"]).isEqualTo(8)
                assertThat(args[BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT]).isEqualTo(true)
                success(mapOf("parentStudyId" to 102L, "topics" to listOf(mapOf("id" to 201L,
                    "parentStudyId" to 102L, "topic" to "Delivery", "difficultyLevel" to 8, "created" to true))))
            }
            else -> error("Unexpected tool $name")
        } }
        val context = nativeContext().copy(userInputEnabled = true)
        val proposal = requireNotNull(fixture.adapter.prepareStudyTopicUserInput(context, 102, listOf("Delivery"), 5))
        assertThat(proposal.prompt).contains("Communication", "8")
        assertThat(fixture.calls.none { it.name == "create_study_topics" }).isTrue()
        val saved = fixture.adapter.submitStudyTopicUserInput(context, proposal.proposalId, listOf(0))
        assertThat(saved.isError).isFalse()
        assertThat(saved.changedStudyIds).containsExactly(201L)
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `GUI topic proposal writes only explicitly selected immutable topics and replay never invokes batch again`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore())
        fixture.handler = { name, arguments -> when (name) {
            "get_study" -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Redis", "difficultyLevel" to 8))
            "create_study_topics" -> {
                assertThat(arguments["parent_study_id"]).isEqualTo(101L)
                assertThat(arguments["topics"]).isEqualTo(listOf("Streams", "Persistence"))
                assertThat(arguments["difficulty_level"]).isEqualTo(8)
                assertThat(arguments[BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT]).isEqualTo("Redis")
                assertThat(arguments[BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT]).isEqualTo(0L)
                success(mapOf("parentStudyId" to 101L, "topics" to listOf("Streams", "Persistence").mapIndexed { index, topic ->
                    mapOf("id" to 201L + index, "parentStudyId" to 101L, "topic" to topic, "difficultyLevel" to 8, "created" to true)
                }))
            }
            else -> error("Unexpected tool $name")
        } }
        val native = context().copy(realtimeModelTools = true)
        val proposal = requireNotNull(fixture.adapter.prepareStudyTopicUserInput(native, 101, listOf("Streams", "Cache", "Persistence"), 8))
        assertThat(proposal.prompt).contains("Redis", "8", "제출")
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study")
        assertThat(fixture.adapter.realtimeDefinitions().map { it.name }).doesNotContain("create_study_topics")
        assertThat(fixture.adapter.execute(native, "create_study_topics", mapOf("parent_study_id" to 101L,
            "topics" to listOf("Unapproved"), "difficulty_level" to 8)).isError).isTrue()
        val saved = fixture.adapter.submitStudyTopicUserInput(native, proposal.proposalId, listOf(0, 2))
        assertThat(saved.isError).isFalse()
        assertThat(saved.changedStudyIds).containsExactly(201L, 202L)
        assertThat(fixture.adapter.submitStudyTopicUserInput(native, proposal.proposalId, listOf(2, 0))).isEqualTo(saved)
        assertThat(fixture.adapter.submitStudyTopicUserInput(native, proposal.proposalId, listOf(1)).isError).isTrue()
        assertThat(fixture.calls.count { it.name == "create_study_topics" }).isEqualTo(1)
    }

    @Test
    fun `GUI topic proposal rejects changed parent revision call and revoked account without any batch write`(): Unit = runBlocking {
        for (change in listOf("parent", "revision", "call", "authorization")) {
            val store = ContextStore()
            val fixture = Fixture(studyContexts = store)
            var parentTitle = "Redis"
            fixture.handler = { name, _ ->
                assertThat(name).isEqualTo("get_study")
                success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to parentTitle, "difficultyLevel" to 5))
            }
            var native = context().copy(realtimeModelTools = true)
            val proposal = requireNotNull(fixture.adapter.prepareStudyTopicUserInput(native, 101, listOf("Streams"), 8))
            when (change) {
                "parent" -> parentTitle = "Changed Redis"
                "revision" -> store.revision += 1
                "call" -> native = native.copy(callId = "other-call")
                "authorization" -> fixture.authorized = false
            }
            assertThat(fixture.adapter.submitStudyTopicUserInput(native, proposal.proposalId, listOf(0)).isError).isTrue()
            assertThat(fixture.calls.none { it.name == "create_study_topics" }).isTrue()
        }
    }

    @Test
    fun `voice exchange logs the complete public result once when using the shared MCP handler`(): Unit = runBlocking {
        val useCase = proxy<BuddyStudyMcpUseCase> { method, arguments ->
            assertThat(method).isEqualTo("getStudy")
            assertThat(arguments[0]).isSameAs(principal)
            room(101)
        }
        val fixture = Fixture(BuddyStudyMcpAdapter(useCase, mapper))
        var result: VoiceTutorMcpToolResult? = null
        val exchanges = captureExchanges {
            result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L, "language" to "ko"))
        }

        assertThat(exchanges).hasSize(1)
        val exchange = exchanges.single()
        assertThat(exchange.path("method").asText()).isEqualTo("MCP")
        assertThat(exchange.path("transport").asText()).isEqualTo("voice")
        assertThat(exchange.path("operation").asText()).isEqualTo("tools/call")
        assertThat(exchange.path("toolName").asText()).isEqualTo("get_study")
        assertThat(exchange.path("userId").asLong()).isEqualTo(principal.userId)
        assertThat(exchange.path("sessionId").asText()).isEqualTo(session().id)
        assertThat(exchange.path("callId").asText()).isEqualTo(context().callId)
        assertThat(exchange.path("requestId").asText()).isNotBlank()
        assertThat(exchange.path("status").asInt()).isEqualTo(200)
        assertThat(exchange.path("durationMs").asText().toDouble()).isGreaterThanOrEqualTo(0.0)
        assertThat(Instant.parse(exchange.path("completedAt").asText()))
            .isAfterOrEqualTo(Instant.parse(exchange.path("startedAt").asText()))
        assertThat(exchangeBody(exchange, "requestBody").path("arguments").path("study_id").asLong()).isEqualTo(101)
        assertThat(exchangeBody(exchange, "responseBody")).isEqualTo(json(requireNotNull(result)))
        assertThat(exchange.toString()).doesNotContain(principal.deviceId, "Bearer", "Authorization")
    }

    @Test
    fun `voice exchanges include early validation authorization and shared business errors`(): Unit = runBlocking {
        val rejected = listOf(
            Triple("unavailable_tool", emptyMap<String, Any>(), "TOOL_NOT_ALLOWED"),
            Triple("get_study", mapOf("study_id" to "invalid"), "INVALID_ARGUMENTS"),
            Triple("list_studies", emptyMap<String, Any>(), "CALL_NOT_AUTHORIZED"),
            Triple("get_study", mapOf("study_id" to 101L), "STUDY_NOT_FOUND"),
        )
        for ((toolName, arguments, expectedCode) in rejected) {
            val fixture = Fixture().apply {
                if (expectedCode == "CALL_NOT_AUTHORIZED") authorized = false
                handler = { _, _ -> failure("STUDY_NOT_FOUND") }
            }
            val exchanges = captureExchanges {
                assertCode(fixture.adapter.execute(context(), toolName, arguments), expectedCode)
            }
            assertThat(exchanges).hasSize(1)
            val exchange = exchanges.single()
            assertThat(exchange.path("toolName").asText()).isEqualTo(toolName)
            assertThat(exchange.path("status").asInt()).isGreaterThanOrEqualTo(400)
            assertThat(exchangeBody(exchange, "responseBody").path("error").path("code").asText()).isEqualTo(expectedCode)
            if (expectedCode != "STUDY_NOT_FOUND") assertThat(fixture.calls).isEmpty()
        }
    }

    @Test
    fun `cancelled voice exchange records failure without swallowing or exposing cancellation details`(): Unit = runBlocking {
        val cancellation = CancellationException("private-cancellation-detail")
        val fixture = Fixture().apply { handler = { _, _ -> throw cancellation } }
        var propagated: CancellationException? = null
        val exchanges = captureExchanges {
            try {
                fixture.adapter.execute(context(), "list_studies", emptyMap())
            } catch (error: CancellationException) {
                propagated = error
            }
        }
        // Coroutine stack-trace recovery may copy the exception at awaitSingle;
        // cancellation must still propagate with the original failure as its cause.
        assertThat(propagated).isNotNull()
        assertThat(generateSequence(propagated as Throwable?) { it.cause }.take(8).toList())
            .contains(cancellation)
        assertThat(exchanges).hasSize(1)
        assertThat(exchanges.single().path("status").asInt()).isEqualTo(499)
        assertThat(exchanges.single().toString()).doesNotContain("private-cancellation-detail")
    }

    @Test
    fun `voice shared invocations explicitly suppress nested exchange rows`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(fixture.calls).hasSize(1)
        assertThat(fixture.calls.single().exchangeLoggingSuppressed).isTrue()
    }

    @Test
    fun `server-owned answer skip and progress operations log their own public validation result`(): Unit = runBlocking {
        val fixture = Fixture()
        val answer = VoiceTutorReviewedAnswer(
            answerId = "00000000-0000-0000-0000-000000000008", studyId = 101L, recordId = "91",
            lessonRevision = 0L, text = "An edited answer", precedingTutorProviderItemId = "private-provider-item",
            learnerProviderItemIds = listOf("private-learner-provider-item"),
        )
        val exchanges = captureExchanges {
            assertThat(fixture.adapter.submitReviewedAnswer(context(), answer).isError).isTrue()
            assertThat(fixture.adapter.skipReviewedQuestion(context(), answer.copy(text = "")).isError).isTrue()
            assertThat(fixture.adapter.pollLearningProgress(context(), VoiceTutorLearningProgress(
                VoiceTutorLearningPhase.GRADING, 101L, "91", "process-1",
            )).isError).isTrue()
        }
        assertThat(exchanges).hasSize(3)
        assertThat(exchanges.take(2).map { it.path("toolName").asText() }).containsExactly("submit_answer", "skip_question")
        assertThat(exchanges.last().path("operation").asText()).isEqualTo("voice/progress")
        assertThat(exchangeBody(exchanges[0], "requestBody").path("arguments").path("answer").asText()).isEqualTo(answer.text)
        assertThat(exchanges.map { it.path("requestId").asText() }.distinct()).hasSize(3)
        assertThat(exchanges.all { it.path("status").asInt() >= 400 }).isTrue()
        assertThat(exchanges.toString()).doesNotContain("private-provider-item", "private-learner-provider-item", principal.deviceId)
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `successful study read returns the immutable lesson level alongside current app metadata`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Cache", 3)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("id" to 102L, "topic" to "Cache", "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(9)
        val lessonTopic = json(result).path("voiceLessonTopics")[0]
        assertThat(lessonTopic.path("studyId").asLong()).isEqualTo(102)
        assertThat(lessonTopic.path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isTrue()
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedPathComplete").asBoolean()).isTrue()
        assertThat(tree.path("nodes").size()).isEqualTo(1)
        assertThat(tree.path("nodes")[0].path("studyId").asLong()).isEqualTo(102)
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("DESCENDANT")
        assertThat(contextStore.remembered).isEmpty()
        assertThat(contextStore.listReads).isEqualTo(1)
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `an explicitly read other root keeps its exact relation without silently changing the selected lesson tree`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[100L] = VoiceTutorStudySnapshot(100, null, "Systems", 9)
            saved[101L] = VoiceTutorStudySnapshot(101, 100, "Cache", 5)
            saved[200L] = VoiceTutorStudySnapshot(200, null, "Cache", 5)
            saved[201L] = VoiceTutorStudySnapshot(201, 200, "Cache", 2)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            // Live names and levels cannot supply ancestry or replace the frozen lesson level.
            handler = { _, _ -> success(mapOf("id" to 201L, "topic" to "Cache", "parentStudyId" to 101L, "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 201L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("voiceLessonTopics").size()).isEqualTo(1)
        assertThat(json(result).path("voiceLessonTopics")[0].path("parentStudyId").asLong()).isEqualTo(200)
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(2)
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(100)
        assertThat(tree.path("selectedPathStudyIds").map { it.asLong() }).containsExactly(100, 101)
        assertThat(tree.path("nodes").size()).isEqualTo(1)
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("OTHER_TREE")
        assertThat(tree.path("nodes")[0].path("rootStudyId").asLong()).isEqualTo(200)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `study pages enrich only already frozen validated IDs without capturing browsing candidates`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Cache", 3)
            saved[103L] = VoiceTutorStudySnapshot(103, 101, "Eviction", 4)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("studies" to listOf(
                mapOf("id" to 102L), mapOf("id" to 103L), mapOf("id" to -1),
                mapOf("id" to "104"), mapOf("id" to 1.5), mapOf("id" to 102L),
            ), "totalCount" to 6, "offset" to 0)) }
        }
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10))
        assertThat(result.isError).isFalse()
        assertThat(contextStore.remembered).isEmpty()
        assertThat(json(result).path("voiceLessonTopics").map { it.path("studyId").asLong() }).containsExactly(102, 103)
        assertThat(json(result).path("totalCount").asInt()).isEqualTo(6)
    }

    @Test
    fun `successful study reads attest exact IDs and validated query or parent pages`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, arguments ->
                when (name) {
                    "get_study" -> success(mapOf(
                        "id" to 202L,
                        "parentStudyId" to 201L,
                        "topic" to "Returned cache",
                    ))
                    "list_studies" -> if ("query" in arguments) {
                        success(mapOf(
                            "studies" to listOf(
                                mapOf("id" to 301L, "parentStudyId" to null, "topic" to "Databases"),
                                mapOf("id" to 302L, "parentStudyId" to 301L, "topic" to "Redis"),
                            ),
                            "totalCount" to 2,
                            "limit" to 10,
                            "offset" to 0,
                        ))
                    } else {
                        success(mapOf(
                            "studies" to listOf(
                                mapOf("id" to 302L, "parentStudyId" to 301L, "topic" to "Redis"),
                            ),
                            "totalCount" to 1,
                            "limit" to 10,
                            "offset" to 0,
                        ))
                    }
                    else -> failure("UNEXPECTED_TOOL")
                }
            }
        }

        val getResult = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 202L))
        val getDiscovery = requireNotNull(getResult.candidateDiscovery)
        assertThat(getDiscovery.source).isEqualTo(VoiceTutorCandidateReadKind.GET_STUDY)
        assertThat(getDiscovery.lessonRevision).isZero()
        assertThat(getDiscovery.currentFocusStudyId).isEqualTo(101L)
        assertThat(getDiscovery.scope).isEqualTo(VoiceTutorCandidateDiscoveryScope.ExactStudy(202L))
        assertThat(getDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(202L, 201L, "Returned cache"),
        )

        val listResult = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
        )
        val listDiscovery = requireNotNull(listResult.candidateDiscovery)
        assertThat(listDiscovery.source).isEqualTo(VoiceTutorCandidateReadKind.LIST_STUDIES)
        assertThat(listDiscovery.lessonRevision).isZero()
        assertThat(listDiscovery.currentFocusStudyId).isEqualTo(101L)
        assertThat(listDiscovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 10, 2),
        )
        assertThat(listDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(301L, null, "Databases"),
            VoiceTutorStudyTargetCandidate(302L, 301L, "Redis"),
        )

        val childrenResult = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 301L, "limit" to 10, "offset" to 0),
        )
        val childrenDiscovery = requireNotNull(childrenResult.candidateDiscovery)
        assertThat(childrenDiscovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(301, 0, 10, 1),
        )
        assertThat(childrenDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(302L, 301L, "Redis"),
        )
    }

    @Test
    fun `empty voice topic lookup retries without trailing sentence punctuation only`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, arguments ->
                check(name == "list_studies")
                val query = arguments["query"] as String
                val row = when (query) {
                    "스프링" -> mapOf("id" to 301L, "parentStudyId" to null, "topic" to "스프링")
                    "C#" -> mapOf("id" to 302L, "parentStudyId" to null, "topic" to "C#")
                    "Node.js." -> mapOf("id" to 303L, "parentStudyId" to null, "topic" to "Node.js.")
                    else -> null
                }
                success(completePage(
                    studies = listOfNotNull(row),
                    totalCount = if (row == null) 0 else 1,
                    limit = 10,
                    offset = 0,
                ))
            }
        }

        val naturalQuestion = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "스프링? ", "limit" to 10, "offset" to 0),
        )
        assertThat(naturalQuestion.isError).isFalse()
        assertThat(naturalQuestion.candidateDiscovery?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("스프링", 0, 10, 1),
        )
        assertThat(naturalQuestion.candidateDiscovery?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(301L, null, "스프링"),
        )

        for (query in listOf("C#", "Node.js.", "???")) {
            assertThat(fixture.adapter.execute(
                context(), "list_studies", mapOf("query" to query, "limit" to 10, "offset" to 0),
            ).isError).isFalse()
        }

        assertThat(fixture.calls.map { it.arguments["query"] }).containsExactly(
            "스프링? ", "스프링", "C#", "Node.js.", "???",
        )
    }

    @Test
    fun `validated paginated slices retain exact query and direct child scope`(): Unit = runBlocking {
        val queryCandidates = (201L..211L).map { candidate(it, null) }
        val childCandidates = (301L..311L).map { candidate(it, 101L) }
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { _, arguments ->
                val offset = (arguments.getValue("offset") as Number).toInt()
                val candidates = if ("query" in arguments) queryCandidates else childCandidates
                success(completePage(
                    studies = candidates.drop(offset).take(10),
                    totalCount = candidates.size,
                    limit = 10,
                    offset = offset,
                ))
            }
        }

        val queryFirst = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "cache", "limit" to 10, "offset" to 0),
        ).candidateDiscovery
        val queryLast = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "cache", "limit" to 10, "offset" to 10),
        ).candidateDiscovery
        val childFirst = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
        ).candidateDiscovery
        val childLast = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 10),
        ).candidateDiscovery

        assertThat(queryFirst?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("cache", 0, 10, 11),
        )
        assertThat(queryFirst?.candidates).hasSize(10)
        assertThat(queryLast?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("cache", 10, 10, 11),
        )
        assertThat(queryLast?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(211L, null, "Topic 211"),
        )
        assertThat(childFirst?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 11),
        )
        assertThat(childFirst?.candidates).hasSize(10)
        assertThat(childLast?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 10, 10, 11),
        )
        assertThat(childLast?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(311L, 101L, "Topic 311"),
        )
    }

    @Test
    fun `only exact validated list slices attest candidates while provider output remains available`(): Unit = runBlocking {
        suspend fun assertNoDiscovery(
            description: String,
            toolName: String,
            arguments: Map<String, Any>,
            payload: Map<String, Any?>,
        ) {
            val fixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(payload) }
            }
            val result = fixture.adapter.execute(context(), toolName, arguments)
            assertThat(result.isError).describedAs(description).isFalse()
            assertThat(result.candidateDiscovery).describedAs(description).isNull()
            assertThat(json(result).isObject).describedAs("$description provider output").isTrue()
        }

        assertNoDiscovery(
            "GET response ID differs from the requested ID",
            "get_study",
            mapOf("study_id" to 201L),
            mapOf("id" to 202L, "parentStudyId" to null, "topic" to "Redis"),
        )
        assertNoDiscovery(
            "LIST has neither query nor parent scope",
            "list_studies",
            mapOf("limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10),
        )
        assertNoDiscovery(
            "LIST mixes query and parent scope",
            "list_studies",
            mapOf("query" to "Redis", "parent_study_id" to 101L, "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, 101L)), totalCount = 1, limit = 10),
        )
        assertNoDiscovery(
            "truncated first page",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 2, limit = 10),
        )
        assertNoDiscovery(
            "nonzero page",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 1),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10, offset = 1),
        )
        assertNoDiscovery(
            "response offset differs from request",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10, offset = 1),
        )
        assertNoDiscovery(
            "response limit differs from request",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 11),
        )
        assertNoDiscovery(
            "complete page exceeds discovery bound",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 17, "offset" to 0),
            completePage((201L..217L).map { candidate(it, null) }, totalCount = 17, limit = 17),
        )
        assertNoDiscovery(
            "result set exceeds bounded discovery authority",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage((201L..210L).map { candidate(it, null) }, totalCount = 61, limit = 10),
        )
        assertNoDiscovery(
            "direct child belongs to another parent",
            "list_studies",
            mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, 999L)), totalCount = 1, limit = 10),
        )
    }

    @Test
    fun `an empty complete direct child page remains typed leaf evidence`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { _, _ -> success(completePage(emptyList(), totalCount = 0, limit = 10)) }
        }

        val result = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
        )

        val discovery = requireNotNull(result.candidateDiscovery)
        assertThat(discovery.source).isEqualTo(VoiceTutorCandidateReadKind.LIST_STUDIES)
        assertThat(discovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 0),
        )
        assertThat(discovery.candidates).isEmpty()
        assertThat(json(result).path("studies")).isEmpty()
    }

    @Test
    fun `failed malformed ambiguous oversized and revision raced reads cannot attest candidates`(): Unit = runBlocking {
        suspend fun assertNoDiscovery(
            description: String,
            toolName: String,
            arguments: Map<String, Any>,
            rawResult: McpSchema.CallToolResult,
        ) {
            val fixture = Fixture(studyContexts = ContextStore()).apply { handler = { _, _ -> rawResult } }
            val result = fixture.adapter.execute(context(), toolName, arguments)
            assertThat(result.candidateDiscovery).describedAs(description).isNull()
        }

        assertNoDiscovery(
            "error result",
            "get_study",
            mapOf("study_id" to 201L),
            failure("READ_FAILED"),
        )
        assertNoDiscovery(
            "invalid candidate identity",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 0L, "parentStudyId" to null, "topic" to "Invalid")),
        )
        assertNoDiscovery(
            "invalid blank topic",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 201L, "parentStudyId" to null, "topic" to " ")),
        )
        assertNoDiscovery(
            "missing parent field",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 201L, "topic" to "Missing parent")),
        )
        assertNoDiscovery(
            "duplicate list identity",
            "list_studies",
            emptyMap(),
            success(mapOf("studies" to listOf(
                mapOf("id" to 201L, "parentStudyId" to null, "topic" to "First"),
                mapOf("id" to 201L, "parentStudyId" to null, "topic" to "Duplicate"),
            ))),
        )
        assertNoDiscovery(
            "inconsistent list page bounds",
            "list_studies",
            emptyMap(),
            success(mapOf(
                "studies" to listOf(
                    mapOf("id" to 201L, "parentStudyId" to null, "topic" to "Redis"),
                ),
                "totalCount" to 1,
                "limit" to 1,
                "offset" to 1,
            )),
        )
        assertNoDiscovery(
            "seventeen candidates",
            "list_studies",
            emptyMap(),
            success(mapOf("studies" to (201L..217L).map { id ->
                mapOf("id" to id, "parentStudyId" to null, "topic" to "Topic $id")
            })),
        )

        val racingStore = ContextStore()
        val racingFixture = Fixture(studyContexts = racingStore).apply {
            handler = { _, _ -> success(mapOf(
                "id" to 202L,
                "parentStudyId" to 101L,
                "topic" to "Raced candidate",
            )) }
        }
        racingStore.afterList = { racingStore.revision = 1L }
        val raced = racingFixture.adapter.execute(context(), "get_study", mapOf("study_id" to 202L))
        assertThat(racingStore.listReads).isEqualTo(1)
        assertThat(racingStore.revision).isEqualTo(1L)
        assertThat(raced.candidateDiscovery).describedAs("lesson revision changed during read").isNull()
    }

    @Test
    fun `browsing many children and individual candidates after selection cannot exhaust the lesson cache`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, args ->
                if (name == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to 101L))
                else success(mapOf("studies" to (200L..229L).map { mapOf("id" to it, "parentStudyId" to 101L) }))
            }
        }
        for (id in 200L..279L) {
            val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to id))
            assertThat(result.isError).isFalse()
            assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        }
        val page = fixture.adapter.execute(context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 30))
        assertThat(page.isError).isFalse()
        assertThat(json(page).path("studies").size()).isEqualTo(30)
        assertThat(store.saved.keys).containsExactly(101L)
        assertThat(store.remembered).isEmpty()
        assertThat(store.revised).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `late study reads are suppressed when authorization expires during their handler`(): Unit = runBlocking {
        for (name in listOf("get_study", "list_studies")) {
            val store = ContextStore()
            val fixture = Fixture(studyContexts = store).apply {
                handler = { _, _ ->
                    authorized = false
                    success(mapOf("id" to 102L, "topic" to "private-late-topic",
                        "studies" to listOf(mapOf("id" to 102L, "topic" to "private-late-topic"))))
                }
            }
            val args = if (name == "get_study") mapOf("study_id" to 102L) else mapOf("limit" to 10)
            val result = fixture.adapter.execute(context(), name, args)
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(result.output).doesNotContain("private-late-topic")
            assertThat(store.listReads).isZero()
            assertThat(store.remembered).isEmpty()
        }
    }

    @Test
    fun `ending a call during read-only metadata enrichment suppresses the late response`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { _, _ -> success(mapOf("id" to 101L, "topic" to "private-late-topic")) }
        }
        store.afterList = { fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.COMPLETED) }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L))
        assertCode(result, "CALL_NOT_AUTHORIZED")
        assertThat(result.output).doesNotContain("private-late-topic")
        assertThat(store.listReads).isEqualTo(1)
        assertThat(store.remembered).isEmpty()
    }

    @Test
    fun `failed tools and failed authorization do not capture topic metadata`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply { handler = { _, _ -> failure("NOT_FOUND") } }
        assertCode(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L)), "NOT_FOUND")
        fixture.authorized = false
        assertCode(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L)), "CALL_NOT_AUTHORIZED")
        assertThat(contextStore.remembered).isEmpty()
        assertThat(contextStore.listReads).isZero()
    }

    @Test
    fun `metadata persistence failure cannot misreport an already committed child creation`(): Unit = runBlocking {
        val contextStore = ContextStore().apply { fail = true }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache", "difficultyLevel" to 5,
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Cache"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102)
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTopics")).isEmpty()
        assertThat(json(result).path("voiceLessonTree").path("selectedPathComplete").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
    }

    @Test
    fun `ancestry read failure preserves a completed creation and its captured level without inventing a root`(): Unit = runBlocking {
        val contextStore = ContextStore().apply { failList = true }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache", "difficultyLevel" to 5,
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Cache"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.createdStudyId).isEqualTo(102)
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isTrue()
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedRootStudyId").isNull).isTrue()
        assertThat(tree.path("selectedPathComplete").asBoolean()).isFalse()
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
    }

    @Test
    fun `a full snapshot cache preserves app metadata but explicitly withholds an unfrozen lesson level`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = UnavailableVoiceTutorStudyContextPort).apply {
            handler = { _, _ -> success(mapOf("id" to 102L, "topic" to "Cache", "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(9)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTopics")).isEmpty()
        assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `unscoped discovery pages stay readable without consuming the bounded lesson context cache`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("studies" to (102L..141L).map { mapOf("id" to it) })) }
        }
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("limit" to 50))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("studies").size()).isEqualTo(40)
        assertThat(json(result).path("voiceLessonTopics").size()).isZero()
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(contextStore.remembered).isEmpty()
    }

    @Test
    fun `lesson metadata obeys the output bound without losing a committed creation`(): Unit = runBlocking {
        for (name in listOf("get_study", "create_study_topic")) {
            val fixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(mapOf(
                    "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache",
                    "difficultyLevel" to 5, "customPrompt" to "x".repeat(16_300),
                )) }
            }
            val args = if (name == "get_study") mapOf("study_id" to 102L)
                else mapOf("parent_study_id" to 101L, "topic" to "Cache")
            val callContext = if (name == "create_study_topic") childContext(101L, "Cache") else context()
            val result = fixture.adapter.execute(callContext, name, args)
            assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
            if (name == "get_study") assertCode(result, "RESULT_TOO_LARGE") else {
                assertThat(result.isError).isFalse()
                assertThat(result.createdStudyId).isEqualTo(102)
                assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(3)
                assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("DESCENDANT")
            }
        }
    }

    @Test
    fun `advertises existing allowed schemas and the scoped voice focus tool while resolving MCP lazily`() {
        val fixture = Fixture()
        assertThat(fixture.catalogReads).isZero()

        val definitions = fixture.adapter.definitions()

        assertThat(definitions.map { it.name }).containsExactly(
            "list_studies", "get_study", "update_study", "create_root_study", "create_study_topic", "suggest_study_topics", "delete_study",
            "list_records", "get_record", "list_study_learning_records", "get_voice_learning_record",
            "get_topic_stats", "get_study_growth",
            "select_voice_study", "advance_voice_study",
        )
        for (definition in definitions.filterNot { it.name in setOf("select_voice_study", "advance_voice_study") }) {
            val original = fixture.catalog.single { it.tool().name() == definition.name }.tool()
            if (definition.name != "delete_study") {
                assertThat(definition.parameters).isEqualTo(original.inputSchema())
            }
            assertThat(definition.description).startsWith(original.description())
        }
        val rootCreation = mapper.valueToTree<JsonNode>(
            definitions.single { it.name == "create_root_study" }.parameters,
        )
        assertThat(rootCreation.path("required").map { it.asText() }).containsExactly("topic")
        assertThat(rootCreation.path("additionalProperties").asBoolean()).isFalse()
        assertThat(rootCreation.path("properties").fieldNames().asSequence().toList()).containsExactly(
            "topic", "difficulty_level",
        )
        assertThat(rootCreation.path("properties").has("interval_minutes")).isFalse()
        assertThat(definitions.single { it.name == "create_root_study" }.description)
            .contains(
                "server-owned", "never originate", "natural new-study choice",
                "Earlier persisted learner speech", "never itself selects a lesson or creates a question",
                "one confirmation question", "fresh natural agreement", "AUTO_FOCUS_PENDING", "do not ask again",
                "CREATED_ROOT_IMMEDIATE_START",
            )
        assertThat(definitions.single { it.name == "create_study_topic" }.description).contains(
            "server-owned", "never originate", "natural first-person choice", "verified owned parent",
            "one confirmation question", "fresh natural agreement",
            "Mere mentions", "ambiguous targets",
        )
        assertThat(definitions.single { it.name == "update_study" }.description).contains(
            "clear learner request", "exact owned topic", "topic just discussed",
            "one exact confirmation question", "fresh natural agreement",
            "Selecting a lesson or reading its children is not required", "confirmed outcome",
        )
        val deletion = mapper.valueToTree<JsonNode>(definitions.single { it.name == "delete_study" }.parameters)
        assertThat(deletion.path("properties").has("confirmation_token")).isFalse()
        assertThat(deletion.path("properties").has("expected_study_ids")).isFalse()
        assertThat(definitions.single { it.name == "delete_study" }.description).contains(
            "clear learner request", "one confirmation question", "fresh natural agreement",
            "Do not ask a second confirmation", "prior learning records are retained",
        )
        assertThat(definitions.filter { it.name in setOf("list_study_learning_records", "get_voice_learning_record") })
            .allSatisfy { assertThat(it.description).contains("verified study tree") }
        fixture.adapter.definitions()
        assertThat(fixture.catalogReads).isEqualTo(1)
        assertThat(fixture.calls).isEmpty()
        for (name in listOf("select_voice_study", "advance_voice_study")) {
            val focus = mapper.valueToTree<JsonNode>(definitions.single { it.name == name }.parameters)
            assertThat(focus.path("required").map { it.asText() }).containsExactly("study_id")
            assertThat(focus.path("additionalProperties").asBoolean()).isFalse()
            assertThat(focus.path("properties").fieldNames().asSequence().toList()).containsExactly("study_id")
        }
        assertThat(definitions.single { it.name == "select_voice_study" }.description)
            .contains("initial or explicitly changed")
        assertThat(definitions.single { it.name == "advance_voice_study" }.description)
            .contains("exactly one verified parent-child edge")
    }

    @Test
    fun `persisted direct root command writes immediately exactly once without preview or lesson focus`(): Unit =
        runBlocking {
            val store = ContextStore().apply {
                saved[501L] = VoiceTutorStudySnapshot(501, null, "운영체제", 6)
            }
            val fixture = Fixture(studyContexts = store).apply {
                handler = { name, _ ->
                    if (name == "create_root_study") success(linkedMapOf(
                        "created" to true,
                        "id" to 501L,
                        "parentStudyId" to null,
                        "topic" to "운영체제",
                        "difficultyLevel" to 6,
                        "enabled" to true,
                        "activeForQuestions" to true,
                    )) else failure("UNEXPECTED_TOOL")
                }
            }
            val command = context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "운영체제",
                rootDifficulty = 6,
            )
            val created = fixture.adapter.execute(
                command,
                "create_root_study",
                mapOf("topic" to "운영체제", "difficulty_level" to 6),
            )

            assertThat(created.isError).isFalse()
            assertThat(created.studyTreeChanged).isTrue()
            assertThat(created.createdStudyId).isEqualTo(501L)
            assertThat(created.changedStudyId).isEqualTo(501L)
            assertThat(created.changeKind).isEqualTo(VoiceTutorStudyChangeKind.CREATED)
            assertThat(created.rootStudyReadbackId).isEqualTo(501L)
            assertThat(created.lessonFocus).isNull()
            assertThat(json(created).path("parentStudyId").isNull).isTrue()
            assertThat(json(created).path("voiceLessonContextReady").asBoolean()).isFalse()
            assertThat(json(created).path("voiceLessonChangeApplies").asText()).isEqualTo("REQUIRES_SELECTION")
            assertThat(json(created).path("notice").asText()).contains(
                "not a lesson focus", "wait for a new learner agreement", "creation itself never starts teaching",
            )
            assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
            assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
                mapOf("topic" to "운영체제", "difficulty_level" to 6),
            )
            assertThat(fixture.calls.single().principal).isEqualTo(principal)
            assertThat(fixture.focusSelections).isEmpty()
            assertThat(store.remembered).containsExactly(listOf(501L))

            assertCode(
                fixture.adapter.execute(
                    command,
                    "create_root_study",
                    mapOf("topic" to "운영체제", "difficulty_level" to 6),
                ),
                "ROOT_CREATION_REQUEST_REQUIRED",
            )
            assertThat(fixture.calls).hasSize(1)
        }

    @Test
    fun `compound root creation tells provider to await server focus without a second confirmation`(): Unit =
        runBlocking {
            val store = ContextStore().apply {
                saved[502L] = VoiceTutorStudySnapshot(502, null, "스프링", 7)
            }
            val fixture = Fixture(studyContexts = store).apply {
                handler = { name, _ ->
                    if (name == "create_root_study") success(linkedMapOf(
                        "created" to true,
                        "id" to 502L,
                        "parentStudyId" to null,
                        "topic" to "스프링",
                        "difficultyLevel" to 7,
                        "enabled" to true,
                        "activeForQuestions" to true,
                    )) else failure("UNEXPECTED_TOOL")
                }
            }

            val result = fixture.adapter.execute(
                context(
                    VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                    rootTopic = "스프링",
                    rootDifficulty = 7,
                    rootStartLessonAfterCreate = true,
                ),
                "create_root_study",
                mapOf("topic" to "스프링", "difficulty_level" to 7),
            )

            assertThat(result.isError).isFalse()
            assertThat(result.lessonFocus).isNull()
            assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
            assertThat(json(result).path("voiceLessonChangeApplies").asText())
                .isEqualTo("AUTO_FOCUS_PENDING")
            assertThat(json(result).path("notice").asText())
                .contains(
                    "same persisted learner turn independently authorized starting it now",
                    "do not ask for agreement again",
                    "server-owned exact readback",
                    "CREATED_ROOT_IMMEDIATE_START",
                )
                .doesNotContain("wait for a NEW agreement", "speak that exact saved root")
            assertThat(fixture.focusSelections).isEmpty()
            assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
        }

    @Test
    fun `existing exact root is returned unchanged without a tree event or lesson focus`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to false,
                    "id" to 701L,
                    "parentStudyId" to null,
                    "topic" to "Redis Streams",
                    "difficultyLevel" to 3,
                    "enabled" to false,
                    "activeForQuestions" to false,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val result = fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "redis streams",
                rootDifficulty = 5,
            ),
            "create_root_study",
            mapOf("topic" to " redis streams "),
        )

        assertThat(result.isError).isFalse()
        assertThat(json(result).path("created").asBoolean()).isFalse()
        assertThat(json(result).path("topic").asText()).isEqualTo("Redis Streams")
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(3)
        assertThat(json(result).path("enabled").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonChangeApplies").asText())
            .isEqualTo("REQUIRES_SELECTION")
        assertThat(json(result).path("notice").asText())
            .contains("already existed", "returned unchanged")
            .doesNotContain("updated", "level 9")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.rootStudyReadbackId).isEqualTo(701L)
        assertThat(result.lessonFocus).isNull()
        assertThat(store.remembered).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.calls).hasSize(1)
        assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
            mapOf("topic" to "redis streams", "difficulty_level" to 5),
        )
    }

    @Test
    fun `compound start on an existing exact root also avoids duplicate confirmation`() = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to false,
                    "id" to 702L,
                    "parentStudyId" to null,
                    "topic" to "Spring",
                    "difficultyLevel" to 7,
                    "enabled" to true,
                    "activeForQuestions" to true,
                )) else failure("UNEXPECTED_TOOL")
            }
        }

        val result = fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "Spring",
                rootDifficulty = 7,
                rootStartLessonAfterCreate = true,
            ),
            "create_root_study",
            mapOf("topic" to "Spring", "difficulty_level" to 7),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.rootStudyReadbackId).isEqualTo(702L)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonChangeApplies").asText())
            .isEqualTo("AUTO_FOCUS_PENDING")
        assertThat(json(result).path("notice").asText())
            .contains("already existed unchanged", "do not ask for agreement again")
            .doesNotContain("wait for a NEW agreement")
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
    }

    @Test
    fun `a child name conflict is preserved without a root tree event or lesson focus`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") failure("STUDY_TOPIC_CONFLICT")
                else failure("UNEXPECTED_TOOL")
            }
        }

        val result = fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "Cache",
                rootDifficulty = 5,
            ),
            "create_root_study",
            mapOf("topic" to "Cache"),
        )

        assertCode(result, "STUDY_TOPIC_CONFLICT")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.rootStudyReadbackId).isNull()
        assertThat(result.lessonFocus).isNull()
        assertThat(fixture.calls).hasSize(1)
        assertThat(fixture.calls.single().principal).isEqualTo(principal)
        assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
            mapOf("topic" to "Cache", "difficulty_level" to 5),
        )
        assertThat(store.remembered).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `direct root authorization is exact tuple bound and a mismatched call cannot consume it`() = runBlocking {
        val store = ContextStore().apply {
            saved[801L] = VoiceTutorStudySnapshot(801, null, "Spring", 7)
        }
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to true,
                    "id" to 801L,
                    "parentStudyId" to null,
                    "topic" to "Spring",
                    "difficultyLevel" to 7,
                    "enabled" to true,
                    "activeForQuestions" to true,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val command = context(
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootTopic = "Spring",
            rootDifficulty = 7,
        )

        assertCode(
            fixture.adapter.execute(
                command,
                "create_root_study",
                mapOf("topic" to "Redis", "difficulty_level" to 9),
            ),
            "ROOT_CREATION_REQUEST_MISMATCH",
        )
        assertThat(fixture.calls).isEmpty()
        assertThat(command.dialogueBoundary!!.rootStudyCreationAuthorization!!.isActive()).isTrue()

        val created = fixture.adapter.execute(
            command,
            "create_root_study",
            mapOf("topic" to "Spring", "difficulty_level" to 7),
        )
        assertThat(created.isError).isFalse()
        assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
        assertThat(command.dialogueBoundary!!.rootStudyCreationAuthorization!!.isActive()).isFalse()
    }

    @Test
    fun `newer speech during persisted direct command lookup revokes root write before invocation`() = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore())
        val arguments = mapOf(
            "topic" to "운영체제",
            "difficulty_level" to 6,
        )
        val lease = VoiceTutorRootStudyCreationAuthorization("운영체제", 6)
        fixture.duringLearnerAuthorization = { lease.invalidate() }

        assertCode(
            fixture.adapter.execute(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY).copy(
                    dialogueBoundary = context(VoiceTutorInputIntent.CREATE_ROOT_STUDY).dialogueBoundary!!.copy(
                        rootStudyCreationAuthorization = lease,
                    ),
                ),
                "create_root_study",
                arguments,
            ),
            "ROOT_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `unverified root result fails closed and consumed direct command cannot replay the write`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to true,
                    "id" to 801L,
                    "parentStudyId" to null,
                    "topic" to "Different root",
                    "difficultyLevel" to 9,
                    "enabled" to true,
                    "activeForQuestions" to true,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val arguments = mapOf(
            "topic" to "운영체제",
            "difficulty_level" to 6,
        )
        val command = context(
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootTopic = "운영체제",
            rootDifficulty = 6,
        )

        val uncertain = fixture.adapter.execute(command, "create_root_study", arguments)

        assertCode(uncertain, "ROOT_CREATION_RESULT_UNCONFIRMED")
        assertThat(uncertain.studyTreeChanged).isFalse()
        assertThat(uncertain.createdStudyId).isNull()
        assertThat(uncertain.rootStudyReadbackId).isNull()
        assertThat(uncertain.output).doesNotContain("Different root")
        assertThat(fixture.calls).hasSize(1)
        assertCode(
            fixture.adapter.execute(command, "create_root_study", arguments),
            "ROOT_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `root creation rejects topic discovery and caller supplied settings before any write`(): Unit = runBlocking {
        for ((context, arguments, code) in listOf(
            Triple(
                context(VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC),
                mapOf<String, Any>("topic" to "운영체제"),
                "ROOT_CREATION_REQUEST_REQUIRED",
            ),
            Triple(
                context(VoiceTutorInputIntent.NONE),
                mapOf<String, Any>("topic" to "운영체제"),
                "ROOT_CREATION_REQUEST_REQUIRED",
            ),
            Triple(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY),
                mapOf<String, Any>("topic" to "운영체제", "enabled" to false),
                "INVALID_ARGUMENTS",
            ),
            Triple(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY),
                mapOf<String, Any>("topic" to "운영체제", "confirm" to true, "confirmation_token" to "forged"),
                "INVALID_ARGUMENTS",
            ),
        )) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context, "create_root_study", arguments), code)
            assertThat(fixture.calls).isEmpty()
        }
    }

    @Test
    fun `discovery browsing alone grants neither mutation authority nor lesson history access`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            persistedSession = discoverySession()
            handler = { name, _ -> if (name == "list_studies") success(mapOf("studies" to listOf(mapOf("id" to 101L))))
                else success(mapOf("id" to 101L, "topic" to "Redis", "parentStudyId" to null)) }
        }
        val context = context().copy(session = discoverySession())
        assertThat(fixture.adapter.execute(context, "list_studies", emptyMap()).isError).isFalse()
        val read = fixture.adapter.execute(context, "get_study", mapOf("study_id" to 101L))
        assertThat(read.isError).isFalse()
        assertThat(json(read).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(store.remembered).isEmpty()
        for ((name, args, code) in listOf(
            Triple(
                "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Cache"),
                "CHILD_CREATION_REQUEST_REQUIRED",
            ),
            Triple("update_study", mapOf("study_id" to 101L, "difficulty_level" to 2), "STUDY_UPDATE_REQUEST_REQUIRED"),
            Triple("delete_study", mapOf("study_id" to 101L, "confirm" to false), "DELETE_REQUEST_REQUIRED"),
            Triple("list_study_learning_records", mapOf("study_id" to 101L), "STUDY_SCOPE_DENIED"),
        )) assertCode(fixture.adapter.execute(context, name, args), code)
        assertThat(fixture.calls.map { it.name }).containsExactly("list_studies", "get_study")
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `spoken selection returns only persisted focus metadata and preserves the original nullable request`(): Unit = runBlocking {
        val store = ContextStore().apply {
            saved[101L] = VoiceTutorStudySnapshot(101, null, "Redis", 3)
        }
        val fixture = Fixture(studyContexts = store).apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101, 1)
        }
        val traversal = VoiceTutorStudyTargetTraversal(terminalLeafStudyId = 101)
        val context = context(targetTraversal = traversal).copy(session = discoverySession())
        val result = fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus).isEqualTo(fixture.focusResult)
        assertThat(json(result).path("voiceLessonFocus").path("topic").asText()).isEqualTo("Redis")
        assertThat(json(result).path("voiceLessonFocus").path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonTree").path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(fixture.persistedSession?.acceptedStudyId).isNull()
        assertThat(fixture.persistedSession?.studyId).isEqualTo(101)
        assertThat(fixture.focusSelections).containsExactly(101L)
        assertThat(fixture.lastExpectedTraversal).isEqualTo(traversal)
        assertThat(json(result).path("voiceLessonFocusChange").asText()).isEqualTo("EXPLICIT_SELECTION")
        assertThat(json(result).path("notice").asText())
            .contains(
                "learner's start agreement are confirmed",
                "ask the first substantive question now",
                "without requesting readiness, permission, or another confirmation",
            )
            .doesNotContain("wait for clear learner agreement")
        assertThat(fixture.calls).isEmpty() // no common study/question mutation
        assertThat(fixture.adapter.execute(context, "list_studies", emptyMap()).isError).isFalse()
    }

    @Test
    fun `server owned created root start focuses without a second learner selection`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(777, 1),
                VoiceTutorStudySnapshot(777, null, "스프링", 7, 1),
            )
        }
        val startAuthorization = VoiceTutorFocusAuthorization(
            VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START,
        ).also {
            check(it.bindToServerCall("direct-adapter-created-root-focus"))
            check(it.claimExecution("direct-adapter-created-root-focus"))
        }
        val base = context(
            inputIntent = VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            targetStudyId = 777,
            targetParentStudyId = null,
            targetTopic = "스프링",
            targetDifficulty = 7,
            targetTraversal = VoiceTutorStudyTargetTraversal(),
        ).copy(session = discoverySession())
        val compound = base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
            latestAcceptedLearnerTargetOfferId = null,
            focusAuthorization = startAuthorization,
            rootStudyCreationAuthorization = null,
        ))

        val result = fixture.adapter.execute(
            compound,
            "select_voice_study",
            mapOf("study_id" to 777L),
        )

        assertThat(result.isError).isFalse()
        assertThat(fixture.focusSelections).containsExactly(777L)
        assertThat(fixture.lastExpectedCandidate)
            .isEqualTo(VoiceTutorStudyTargetCandidate(777, null, "스프링", difficulty = 7))
        assertThat(fixture.lastExpectedTraversal).isEqualTo(VoiceTutorStudyTargetTraversal())
        assertThat(json(result).path("voiceLessonFocusChange").asText())
            .isEqualTo("CREATED_ROOT_IMMEDIATE_START")
        assertThat(json(result).path("notice").asText())
            .contains("immediate lesson start", "ask the first substantive question now")
            .doesNotContain("wait for clear learner agreement")
    }

    @Test
    fun `server owned updated study start uses original learner revision and exact post update fence`(): Unit =
        runBlocking {
            val contexts = ContextStore().apply {
                revision = 1
                saved[202L] = VoiceTutorStudySnapshot(202, 200, "Spring server", 7, revision = 1)
            }
            val fixture = Fixture(studyContexts = contexts).apply {
                persistedSession = discoverySession()
                acceptedLessonRevision = 0
                expectedFocusCurrentRevision = 1
                focusResult = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(202, 2),
                    VoiceTutorStudySnapshot(202, 200, "Spring server", 7, revision = 2),
                )
            }
            val startAuthorization = VoiceTutorFocusAuthorization(
                VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START,
            ).also {
                check(it.bindToServerCall("direct-adapter-updated-study-focus"))
                check(it.claimExecution("direct-adapter-updated-study-focus"))
            }
            val base = context(
                inputIntent = VoiceTutorInputIntent.UPDATE_STUDY,
                lessonRevision = 0,
                targetStudyId = 202,
                targetParentStudyId = 200,
                targetTopic = "Spring server",
                targetDifficulty = 7,
                targetTraversal = VoiceTutorStudyTargetTraversal(),
            ).copy(session = discoverySession())
            val compound = base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                latestAcceptedLearnerTargetOfferId = null,
                focusAuthorization = startAuthorization,
                studyUpdateAuthorization = null,
                focusExpectedCurrentLessonRevision = 1,
            ))

            val result = fixture.adapter.execute(
                compound,
                "select_voice_study",
                mapOf("study_id" to 202L),
            )

            assertThat(result.isError).isFalse()
            assertThat(fixture.focusSelections).containsExactly(202L)
            assertThat(fixture.lastExpectedCandidate)
                .isEqualTo(VoiceTutorStudyTargetCandidate(202, 200, "Spring server", difficulty = 7))
            assertThat(fixture.lastExpectedTraversal).isEqualTo(VoiceTutorStudyTargetTraversal())
            assertThat(json(result).path("voiceLessonFocusChange").asText())
                .isEqualTo("UPDATED_STUDY_IMMEDIATE_START")
            assertThat(json(result).path("notice").asText())
                .contains("exact revised readback", "ask the first substantive question now")
        }

    @Test
    fun `updated study immediate start rejects wrong intent target and post update revision`(): Unit = runBlocking {
        fun fixture() = Fixture(studyContexts = ContextStore().apply { revision = 1 }).apply {
            persistedSession = discoverySession()
            acceptedLessonRevision = 0
            expectedFocusCurrentRevision = 1
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(202, 2),
                VoiceTutorStudySnapshot(202, 200, "Spring server", 7, revision = 2),
            )
        }

        fun updatedContext(
            inputIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.UPDATE_STUDY,
            targetStudyId: Long = 202,
            expectedRevision: Long = 1,
        ): VoiceTutorWebRtcControlContext {
            val base = context(
                inputIntent = inputIntent,
                lessonRevision = 0,
                targetStudyId = targetStudyId,
                targetParentStudyId = 200,
                targetTopic = "Spring server",
                targetDifficulty = 7,
                targetTraversal = VoiceTutorStudyTargetTraversal(),
            ).copy(session = discoverySession())
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                latestAcceptedLearnerTargetOfferId = null,
                focusAuthorization = VoiceTutorFocusAuthorization(
                    VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START,
                ).also {
                    check(it.bindToServerCall("direct-adapter-updated-study-focus"))
                    check(it.claimExecution("direct-adapter-updated-study-focus"))
                },
                focusExpectedCurrentLessonRevision = expectedRevision,
            ))
        }

        val wrongIntent = fixture()
        assertCode(
            wrongIntent.adapter.execute(
                updatedContext(inputIntent = VoiceTutorInputIntent.SELECT_SAVED_TOPIC),
                "select_voice_study",
                mapOf("study_id" to 202L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertThat(wrongIntent.focusSelections).isEmpty()

        val wrongTarget = fixture()
        assertCode(
            wrongTarget.adapter.execute(
                updatedContext(targetStudyId = 203),
                "select_voice_study",
                mapOf("study_id" to 202L),
            ),
            "LEARNER_TARGET_MISMATCH",
        )
        assertThat(wrongTarget.focusSelections).isEmpty()

        val wrongRevision = fixture()
        assertCode(
            wrongRevision.adapter.execute(
                updatedContext(expectedRevision = 2),
                "select_voice_study",
                mapOf("study_id" to 202L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertThat(wrongRevision.focusSelections).isEmpty()
    }

    @Test
    fun `created root focus rejects a changed readback level without publishing revision or UI focus`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(777, 1),
                VoiceTutorStudySnapshot(777, null, "스프링", 8, 1),
            )
        }
        val base = context(
            inputIntent = VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            targetStudyId = 777,
            targetParentStudyId = null,
            targetTopic = "스프링",
            targetDifficulty = 7,
            targetTraversal = VoiceTutorStudyTargetTraversal(),
        ).copy(session = discoverySession())
        val compound = base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
            latestAcceptedLearnerTargetOfferId = null,
            focusAuthorization = VoiceTutorFocusAuthorization(
                VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START,
            ).also {
                check(it.bindToServerCall("direct-adapter-created-root-focus"))
                check(it.claimExecution("direct-adapter-created-root-focus"))
            },
            rootStudyCreationAuthorization = null,
        ))

        val result = fixture.adapter.execute(compound, "select_voice_study", mapOf("study_id" to 777L))

        assertCode(result, "LESSON_FOCUS_UNCONFIRMED")
        assertThat(result.lessonRevision).isNull()
        assertThat(result.lessonFocus).isNull()
        assertThat(voiceTutorLessonFocusEvent(result)).isNull()
        assertThat(fixture.lastExpectedCandidate)
            .isEqualTo(VoiceTutorStudyTargetCandidate(777, null, "스프링", difficulty = 7))
    }

    @Test
    fun `ordinary focus lease cannot turn a root creation into an implicit selection`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(777, 1),
                VoiceTutorStudySnapshot(777, null, "스프링", 7, 1),
            )
        }
        val forged = context(
            inputIntent = VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            targetStudyId = 777,
            targetParentStudyId = null,
            targetTopic = "스프링",
            targetTraversal = VoiceTutorStudyTargetTraversal(),
        ).copy(session = discoverySession())

        assertCode(
            fixture.adapter.execute(forged, "select_voice_study", mapOf("study_id" to 777L)),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `focus selection rejects invalid identities and any caller supplied metadata`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession(); focusResult = focusSelection(101, 1) }
        val context = context().copy(session = discoverySession())
        for (args in listOf(emptyMap(), mapOf("study_id" to 0), mapOf("study_id" to -1),
            mapOf("study_id" to "101"), mapOf("study_id" to 101.5), mapOf("study_id" to true),
            mapOf("study_id" to 101L, "topic" to "injected-title"), mapOf("study_id" to 101L, "revision" to 99))) {
            assertCode(fixture.adapter.execute(context, "select_voice_study", args), "INVALID_ARGUMENTS")
        }
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `focus tools reject a tool target different from the learner attested target without writing focus`(): Unit = runBlocking {
        val selection = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101L, 1L)
        }
        val selectResult = selection.adapter.execute(
            context(targetStudyId = 202L).copy(session = discoverySession()),
            "select_voice_study",
            mapOf("study_id" to 101L),
        )
        assertCode(selectResult, "LEARNER_TARGET_MISMATCH")
        assertThat(selection.focusSelections).isEmpty()

        val missingTraversal = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101L, 1L)
        }
        val missingTraversalResult = missingTraversal.adapter.execute(
            context(targetTraversal = null).copy(session = discoverySession()),
            "select_voice_study",
            mapOf("study_id" to 101L),
        )
        assertCode(missingTraversalResult, "LEARNER_TARGET_MISMATCH")
        assertThat(missingTraversal.focusSelections).isEmpty()

        val advance = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102L, 1L),
                VoiceTutorStudySnapshot(102L, 101L, "Child", 4, revision = 1L),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        val advanceResult = advance.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE, targetStudyId = 103L),
            "advance_voice_study",
            mapOf("study_id" to 102L),
        )
        assertCode(advanceResult, "LEARNER_TARGET_MISMATCH")
        // Reject the model-supplied mismatch before it can trigger an arbitrary
        // child lookup; only the learner-attested target may be re-read.
        assertThat(advance.calls).isEmpty()
        assertThat(advance.focusSelections).isEmpty()
    }

    @Test
    fun `guided progression advances exactly one verified direct child with frozen metadata`(): Unit = runBlocking {
        val store = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Eviction", 6, revision = 2)
        }
        val child = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(102, 2),
            VoiceTutorStudySnapshot(102, 101, "Eviction", 6, revision = 2),
        )
        val fixture = Fixture(studyContexts = store).apply {
            focusResult = child
            handler = { name, args ->
                if (name == "get_study" && args["study_id"] == 102L) {
                    success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Eviction"))
                } else {
                    failure("UNEXPECTED_TOOL")
                }
            }
        }

        val result = fixture.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Eviction"),
            "advance_voice_study",
            mapOf("study_id" to 102L),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.lessonFocus).isEqualTo(child)
        assertThat(result.lessonRevision).isEqualTo(2)
        assertThat(json(result).path("voiceLessonFocusChange").asText()).isEqualTo("GUIDED_DIRECT_CHILD")
        assertThat(json(result).path("voiceLessonFocus").path("parentStudyId").asLong()).isEqualTo(101)
        assertThat(json(result).path("voiceLessonFocus").path("difficulty").asInt()).isEqualTo(6)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        assertThat(fixture.focusSelections).containsExactly(102L)
        assertThat(fixture.lastExpectedTraversal).isEqualTo(VoiceTutorStudyTargetTraversal())
        assertThat(fixture.persistedSession?.studyId).isEqualTo(102)
    }

    @Test
    fun `guided progression consumes one accepted learner turn before another tree edge`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Cache", 4, revision = 2),
            )
            handler = { name, args -> if (name == "get_study") {
                val id = args["study_id"] as Long
                success(mapOf(
                    "id" to id,
                    "parentStudyId" to if (id == 102L) 101L else 102L,
                    "topic" to if (id == 102L) "Cache" else "Eviction",
                ))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertThat(fixture.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE), "advance_voice_study", mapOf("study_id" to 102L),
        ).isError)
            .isFalse()
        fixture.focusResult = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(103, 3),
            VoiceTutorStudySnapshot(103, 102, "Eviction", 5, revision = 3),
        )

        assertCode(
            fixture.adapter.execute(
                context(
                    VoiceTutorInputIntent.CONTINUE_TREE,
                    targetStudyId = 103L,
                    targetParentStudyId = 102L,
                    targetTopic = "Eviction",
                ),
                "advance_voice_study", mapOf("study_id" to 103L),
            ),
            "LESSON_FOCUS_UNAVAILABLE",
        )
        fixture.learnerTurnId = 12
        assertThat(fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CONTINUE_TREE,
                targetStudyId = 103L,
                targetParentStudyId = 102L,
                targetTopic = "Eviction",
            ),
            "advance_voice_study", mapOf("study_id" to 103L),
        ).isError)
            .isFalse()
        assertThat(fixture.focusSelections).containsExactly(102L, 103L)
    }

    @Test
    fun `guided progression rejects siblings deeper jumps other roots and missing continuation`(): Unit = runBlocking {
        for (parent in listOf<Long?>(null, 999L, 102L)) {
            val fixture = Fixture().apply {
                focusResult = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(103, 2),
                    VoiceTutorStudySnapshot(103, parent, "Wrong branch", 5, revision = 2),
                )
                handler = { name, _ -> if (name == "get_study") {
                    success(mapOf("id" to 103L, "parentStudyId" to parent, "topic" to "Wrong branch"))
                } else failure("UNEXPECTED_TOOL") }
            }
            assertCode(
                fixture.adapter.execute(
                    context(
                        VoiceTutorInputIntent.CONTINUE_TREE,
                        targetStudyId = 103L,
                        targetParentStudyId = parent,
                        targetTopic = "Wrong branch",
                    ),
                    "advance_voice_study", mapOf("study_id" to 103L),
                ),
                "GUIDED_DESCENT_OUT_OF_SCOPE",
            )
            assertThat(fixture.focusSelections).isEmpty()
        }

        val noContinuation = Fixture().apply {
            learnerTurnId = null
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 2),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            noContinuation.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child"),
                "advance_voice_study", mapOf("study_id" to 102L),
            ),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        assertThat(noContinuation.focusSelections).isEmpty()
    }

    @Test
    fun `guided progression requires an existing focus and rejects caller supplied metadata`(): Unit = runBlocking {
        val discovery = Fixture().apply { persistedSession = discoverySession() }
        assertCode(
            discovery.adapter.execute(
                context().copy(session = discoverySession()),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "GUIDED_FOCUS_REQUIRED",
        )
        assertCode(
            Fixture().adapter.execute(
                context(),
                "advance_voice_study",
                mapOf("study_id" to 102L, "parent_study_id" to 101L),
            ),
            "INVALID_ARGUMENTS",
        )
    }

    @Test
    fun `focus rejects revoked calls before commit but preserves the committed epoch when revoke follows it`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession(); focusResult = focusSelection(101, 1) }
        val context = context().copy(session = discoverySession())
        fixture.learnerTurnId = null
        val missingDurableChoice = fixture.adapter.execute(
            context,
            "select_voice_study",
            mapOf("study_id" to 101L),
        )
        assertCode(missingDurableChoice, "LEARNER_CHOICE_REQUIRED")
        assertThat(json(missingDurableChoice).path("error").path("message").asText())
            .contains("one newly persisted natural choice", "never prescribe special wording")
            .doesNotContain("more clearly", "explicit topic choice")
        fixture.learnerTurnId = 11
        fixture.authorized = false
        assertCode(fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L)), "CALL_NOT_AUTHORIZED")
        assertThat(fixture.focusSelections).isEmpty()
        fixture.authorized = true
        fixture.afterFocus = { fixture.authorized = false }
        val late = fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L))
        assertThat(late.isError).isFalse()
        assertThat(late.lessonFocus?.studyId).isEqualTo(101)
        assertThat(late.lessonRevision).isEqualTo(1)
        assertThat(json(late).path("voiceLessonFocus").path("revision").asLong()).isEqualTo(1)
    }

    @Test
    fun `focus tools require the server classified intent for their exact persisted turn`(): Unit = runBlocking {
        val selection = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101, 1)
        }
        assertCode(
            selection.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE).copy(session = discoverySession()),
                "select_voice_study",
                mapOf("study_id" to 101L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertCode(
            selection.adapter.execute(
                context(providerItemId = "different-user-item").copy(session = discoverySession()),
                "select_voice_study",
                mapOf("study_id" to 101L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertThat(selection.focusSelections).isEmpty()

        val advance = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 1),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 1),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            advance.adapter.execute(context(), "advance_voice_study", mapOf("study_id" to 102L)),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        advance.completedExchange = false
        assertCode(
            advance.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child"),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        advance.completedExchange = true
        val readinessOnly = context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child").let { base ->
            base.copy(dialogueBoundary = base.dialogueBoundary?.copy(
                precedingTutorFeedbackForStudyAnswer = false,
            ))
        }
        assertCode(
            advance.adapter.execute(readinessOnly, "advance_voice_study", mapOf("study_id" to 102L)),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        assertThat(advance.focusSelections).isEmpty()
    }

    @Test
    fun `focus tools reject stale lesson intent and continuation before spoken feedback drains`(): Unit = runBlocking {
        val store = ContextStore().apply { revision = 1 }
        val fixture = Fixture(studyContexts = store).apply {
            acceptedLessonRevision = 1
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 2),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            fixture.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, lessonRevision = 0, targetTopic = "Child"),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        val overlapping = context(
            VoiceTutorInputIntent.CONTINUE_TREE,
            lessonRevision = 1,
            targetTopic = "Child",
        ).let { base ->
            base.copy(dialogueBoundary = base.dialogueBoundary?.copy(
                latestAcceptedLearnerSpeechStartedOrder = 2,
                precedingTutorSpeechStoppedOrder = 2,
            ))
        }
        assertCode(
            fixture.adapter.execute(overlapping, "advance_voice_study", mapOf("study_id" to 102L)),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `failed or unverified focus never emits a successful focus or prepared level`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession() }
        assertCode(fixture.adapter.execute(
            context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L),
        ), "LESSON_FOCUS_UNAVAILABLE")
        for (invalid in listOf(focusSelection(101, 0), focusSelection(201, 1),
            focusSelection(101, 1).let { it.copy(snapshot = it.snapshot.copy(difficulty = 0)) })) {
            fixture.persistedSession = discoverySession()
            fixture.focusHistory.clear()
            fixture.focusResult = invalid
            val result = fixture.adapter.execute(
                context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L),
            )
            assertCode(result, "LESSON_FOCUS_UNCONFIRMED")
            assertThat(result.lessonFocus).isNull()
            assertThat(result.lessonRevision).isNull()
        }
    }

    @Test
    fun `focus enrichment failure cannot misreport a committed selection as a failed operation`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore().apply { failList = true }).apply {
            persistedSession = discoverySession(); focusResult = focusSelection(101, 1)
        }
        val result = fixture.adapter.execute(context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("voiceLessonFocus").path("studyId").asLong()).isEqualTo(101)
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(fixture.focusSelections).hasSize(1)
    }

    @Test
    fun `switching trees follows the persisted focus not the immutable initial request or a mere read`(): Unit = runBlocking {
        val fixture = mutationFixture().apply { focusResult = focusSelection(201, 1) }
        assertThat(fixture.adapter.execute(
            context(targetStudyId = 201L), "select_voice_study", mapOf("study_id" to 201L),
        ).isError).isFalse()
        assertThat(fixture.persistedSession?.acceptedStudyId).isEqualTo(101)
        val original = fixture.handler
        fixture.handler = { name, args -> if (name == "create_study_topic") success(mapOf(
            "created" to true, "id" to 202L, "parentStudyId" to 201L, "topic" to "Child", "difficultyLevel" to 5,
        ))
            else original(name, args) }
        assertThat(fixture.adapter.execute(
            childContext(201L, "Child"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Child"),
        ).isError).isFalse()
        assertThat(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L)).isError).isFalse()
        assertCode(fixture.adapter.execute(
            childContext(101L, "Wrong tree"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Wrong tree"),
        ), "STUDY_SCOPE_DENIED")
    }

    @Test
    fun `node history and voice detail preserve source evidence and allow verified same tree siblings`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply { persistedSession = session().copy(studyId = 201L, acceptedStudyId = 201L) }
        val page = mapOf(
            "items" to listOf(mapOf("id" to "voice:91", "source" to "VOICE_TUTOR", "studyId" to 202L,
                "voiceRecord" to learningRecordPayload(202L))),
            "nextCursor" to "same-scope-position", "hasMore" to true, "limit" to 3,
        )
        fixture.handler = { name, args ->
            when (name) {
                "get_study" -> {
                    val id = (args.getValue("study_id") as Number).toLong()
                    success(mapOf("id" to id, "parentStudyId" to if (id == 101L) null else 101L, "difficultyLevel" to 9))
                }
                "list_study_learning_records" -> success(page)
                "get_voice_learning_record" -> success(learningRecordPayload(202L))
                else -> error("Unexpected tool")
            }
        }
        val active = context().copy(session = fixture.persistedSession!!)

        val listResult = fixture.adapter.execute(active, "list_study_learning_records", mapOf(
            "study_id" to 202L, "scope" to "node", "limit" to 3, "cursor" to "prior-position", "view" to "original",
        ))
        val detailResult = fixture.adapter.execute(active, "get_voice_learning_record", mapOf("record_id" to 91L, "view" to "original"))

        assertThat(listResult.isError).isFalse()
        // Compare the complete wire-decoded trees: valueToTree keeps Kotlin Long
        // nodes, but JSON parsing uses IntNode for the same small integer values.
        assertThat(json(listResult)).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(page)))
        assertThat(detailResult.isError).isFalse()
        assertThat(json(detailResult)).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(learningRecordPayload(202L))))
        assertThat(json(detailResult).path("difficulty").asInt()).isEqualTo(3)
        assertThat(listResult.studyTreeChanged).isFalse()
        assertThat(detailResult.studyTreeChanged).isFalse()
        assertThat(detailResult.createdStudyId).isNull()
        assertThat(fixture.calls.single { it.name == "list_study_learning_records" }.arguments)
            .containsEntry("cursor", "prior-position").containsEntry("study_id", 202L)
        assertThat(fixture.calls.map { it.principal }).allMatch { it === principal }
        assertThat(contextStore.remembered).isEmpty()
    }

    @Test
    fun `new learning history cannot cross a root or guess missing foreign cyclic and deep ancestry`(): Unit = runBlocking {
        val rejectedNodes = listOf<(Long) -> McpSchema.CallToolResult>(
            { id -> success(mapOf("id" to id, "parentStudyId" to null)) },
            { _ -> failure("RECORD_NOT_FOUND") },
            { id -> success(mapOf("id" to id)) },
            { _ -> success(mapOf("id" to 999L, "parentStudyId" to 101L)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to 101.5)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to if (id == 201L) 202L else 201L)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to id + 1)) },
        )
        for (readNode in rejectedNodes) {
            val fixture = Fixture().apply {
                handler = { name, args ->
                    assertThat(name).isEqualTo("get_study")
                    val id = (args.getValue("study_id") as Number).toLong()
                    if (id == 101L) success(mapOf("id" to id, "parentStudyId" to null)) else readNode(id)
                }
            }
            assertCode(fixture.adapter.execute(context(), "list_study_learning_records", mapOf("study_id" to 201L)), "STUDY_SCOPE_DENIED")
            assertThat(fixture.calls).hasSizeLessThanOrEqualTo(33)
            assertThat(fixture.calls.map { it.name }).containsOnly("get_study")
        }
        val noStudy = Fixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = null) }
        assertCode(noStudy.adapter.execute(context().copy(session = noStudy.persistedSession!!), "list_study_learning_records", mapOf("study_id" to 101L)), "STUDY_SCOPE_DENIED")
        assertThat(noStudy.calls).isEmpty()
    }

    @Test
    fun `voice detail uses the returned owned record node and never exposes outside tree or mismatched identity`(): Unit = runBlocking {
        for ((record, expectedCode) in listOf(
            learningRecordPayload(201L) to "STUDY_SCOPE_DENIED",
            (learningRecordPayload(101L) + ("id" to "92")) to "INVALID_TOOL_RESULT",
            (learningRecordPayload(101L) + ("studyId" to "101")) to "INVALID_TOOL_RESULT",
        )) {
            val fixture = Fixture().apply {
                handler = { name, args ->
                    when (name) {
                        "get_voice_learning_record" -> success(record)
                        "get_study" -> success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                        else -> error("Unexpected tool")
                    }
                }
            }
            val result = fixture.adapter.execute(context(), "get_voice_learning_record", mapOf("record_id" to 91L))
            assertCode(result, expectedCode)
            assertThat(result.output).doesNotContain("private-prior-answer", "synthetic-prior-session")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(fixture.calls.map { it.name }).doesNotContain("get_record", "create_study_topic")
        }
    }

    @Test
    fun `new history rechecks active call and device authorization after suspended reads`(): Unit = runBlocking {
        for (logout in listOf(false, true)) {
            val fixture = Fixture()
            fixture.handler = { name, args ->
                if (name == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                else {
                    assertThat(name).isEqualTo("list_study_learning_records")
                    if (logout) fixture.authorized = false
                    else fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.ENDING)
                    success(mapOf("items" to listOf(learningRecordPayload(101L))))
                }
            }
            val result = fixture.adapter.execute(context(), "list_study_learning_records", mapOf("study_id" to 101L))
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(result.output).doesNotContain("private-prior-answer")
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "list_study_learning_records")
        }
        val revoked = Fixture().apply { authorized = false }
        assertCode(revoked.adapter.execute(context(), "get_voice_learning_record", mapOf("record_id" to 91L)), "CALL_NOT_AUTHORIZED")
        assertThat(revoked.calls).isEmpty()
    }

    @Test
    fun `oversized learning pages and voice detail fail explicitly without truncating original evidence`(): Unit = runBlocking {
        for (name in listOf("list_study_learning_records", "get_voice_learning_record")) {
            val fixture = Fixture().apply {
                handler = { tool, args ->
                    if (tool == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                    else success(learningRecordPayload(101L) + ("answer" to "private-prior-answer".repeat(2_000)))
                }
            }
            val args = if (name == "list_study_learning_records") mapOf("study_id" to 101L) else mapOf("record_id" to 91L)
            val result = fixture.adapter.execute(context(), name, args)
            assertCode(result, "RESULT_TOO_LARGE")
            assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
            assertThat(result.output).doesNotContain("private-prior-answer")
            assertThat(json(result).path("error").path("message").asText()).contains("not cut into partial JSON")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
    }

    @Test
    fun `unavailable legacy port has no tools and never grants implicit access`(): Unit = runBlocking {
        assertThat(UnavailableVoiceTutorMcpToolPort.definitions()).isEmpty()
        val result = UnavailableVoiceTutorMcpToolPort.execute(context(), "get_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isTrue()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(json(result).path("error").path("code").asText()).isEqualTo("MCP_UNAVAILABLE")
    }

    @Test
    fun `executes the actual MCP adapter handler with the authenticated principal and no bearer token`(): Unit = runBlocking {
        var calledPrincipal: Principal? = null
        var calledId: Long? = null
        val useCase = proxy<BuddyStudyMcpUseCase> { method, arguments ->
            assertThat(method).isEqualTo("getStudy")
            calledPrincipal = arguments[0] as Principal
            calledId = arguments[1] as Long
            room(101)
        }
        val fixture = Fixture(BuddyStudyMcpAdapter(useCase, mapper))

        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L, "language" to "ko"))

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(calledPrincipal).isSameAs(principal)
        assertThat(calledId).isEqualTo(101L)
        assertThat(json(result).path("topic").asText()).isEqualTo("Redis")
        assertThat(result.output).doesNotContain("Authorization", "Bearer", "sessionId", "deviceId")
        // Entry, handler completion and metadata completion each revalidate;
        // the enrichment also reads the current (not originally accepted) focus.
        assertThat(fixture.authorizationCalls).isEqualTo(5)
        assertThat(fixture.persistenceCalls).isEqualTo(7)
    }

    @Test
    fun `missing anonymous or foreign principals cannot invoke any MCP handler`(): Unit = runBlocking {
        for (caller in listOf(null, principal.copy(anonymous = true), principal.copy(userId = 99L))) {
            val fixture = Fixture()
            val result = fixture.adapter.execute(context().copy(principal = caller), "list_studies", emptyMap())
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `expired or mismatched original call contexts fail before session access`(): Unit = runBlocking {
        val invalidContexts = listOf(
            context().copy(callId = "rtc_other"),
            context().copy(session = session().copy(hardEndsAt = now)),
            context().copy(session = session().copy(status = VoiceTutorSessionStatus.COMPLETED)),
        )
        for (invalid in invalidContexts) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(invalid, "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `revoked device authorization is checked afresh for each tool`(): Unit = runBlocking {
        val fixture = Fixture()
        assertThat(fixture.adapter.execute(context(), "list_studies", emptyMap()).isError).isFalse()
        val authorizationCallsAfterSuccess = fixture.authorizationCalls
        val persistenceCallsAfterSuccess = fixture.persistenceCalls
        fixture.authorized = false

        assertCode(fixture.adapter.execute(context(), "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")

        // The revoked follow-up performs a fresh device check and stops before
        // another persistence read or MCP handler invocation. Exact successful
        // read recheck counts are deliberately not part of this contract.
        assertThat(fixture.authorizationCalls).isGreaterThan(authorizationCallsAfterSuccess)
        assertThat(fixture.persistenceCalls).isEqualTo(persistenceCallsAfterSuccess)
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `current persisted call must still be active owned unexpired and match the provider and study`(): Unit = runBlocking {
        val invalidSessions = listOf(
            null,
            session().copy(status = VoiceTutorSessionStatus.READY),
            session().copy(status = VoiceTutorSessionStatus.ENDING),
            session().copy(status = VoiceTutorSessionStatus.COMPLETED),
            session().copy(status = VoiceTutorSessionStatus.FAILED),
            session().copy(id = "different-session"),
            session().copy(userId = 99L),
            session().copy(studyId = 999L),
            session().copy(acceptedStudyId = 999L),
            session().copy(providerSessionId = "rtc_different"),
            session().copy(hardEndsAt = now),
        )
        for (persisted in invalidSessions) {
            val fixture = Fixture().apply { persistedSession = persisted }
            assertCode(fixture.adapter.execute(context(), "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isEqualTo(1)
            assertThat(fixture.persistenceCalls).isEqualTo(1)
        }
    }

    @Test
    fun `all other existing MCP mutations remain unavailable in voice calls`(): Unit = runBlocking {
        for (name in listOf("create_study", "request_question", "submit_answer", "update_my_learning_context", "unknown")) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context(), name, emptyMap()), "TOOL_NOT_ALLOWED")
            assertThat(fixture.catalogReads).isZero()
            assertThat(fixture.calls).isEmpty()
        }
    }

    @Test
    fun `SDK schema validation rejects fractional overflowing missing foreign and malformed arguments`(): Unit = runBlocking {
        val invalid = listOf(
            "get_study" to emptyMap<String, Any>(),
            "get_study" to mapOf("study_id" to "101"),
            "get_study" to mapOf("study_id" to 1.25),
            "get_study" to mapOf("study_id" to Double.NaN),
            "get_study" to mapOf("study_id" to 0),
            "get_study" to mapOf("study_id" to 1e40),
            "get_study" to mapOf("study_id" to 101L, "user_id" to 99),
            "get_study" to mapOf("study_id" to 101L, "language" to "invalid"),
            "list_studies" to mapOf("limit" to 501),
            "list_studies" to mapOf("offset" to -1),
            "list_studies" to mapOf("offset" to Long.MAX_VALUE),
            "list_records" to mapOf("limit" to 101),
            "list_study_learning_records" to emptyMap<String, Any>(),
            "list_study_learning_records" to mapOf("study_id" to 101L, "limit" to 31),
            "list_study_learning_records" to mapOf("study_id" to 101L, "scope" to "all"),
            "list_study_learning_records" to mapOf("study_id" to 101L, "offset" to 1),
            "list_study_learning_records" to mapOf("study_id" to 101L, "cursor" to ""),
            "list_study_learning_records" to mapOf("study_id" to 101L, "cursor" to "x".repeat(513)),
            "get_voice_learning_record" to mapOf("record_id" to "voice:91"),
            "get_voice_learning_record" to mapOf("record_id" to 91.5),
            "get_voice_learning_record" to mapOf("record_id" to 91L, "user_id" to 99L),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to ""),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "가".repeat(256)),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "Streams", "sort_order" to 10_001),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "Streams", "active_for_questions" to "true"),
        )
        for ((name, arguments) in invalid) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context(), name, arguments), "INVALID_ARGUMENTS")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `argument byte bound rejects large data without reflecting it in the result`(): Unit = runBlocking {
        val fixture = Fixture()
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("query" to "비공개".repeat(4_000)))
        assertCode(result, "INVALID_ARGUMENTS")
        assertThat(result.output).doesNotContain("비공개")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `creates a child of the current study through the original handler preserving short titles`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.handler = { _, _ -> success(mapOf(
            "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "2", "difficultyLevel" to 7,
        )) }

        val result = fixture.adapter.execute(
            childContext(101L, "2", 7), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "2", "difficulty_level" to 7),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102L)
        assertThat(result.savedMutationTarget).isEqualTo(VoiceTutorStudyTargetCandidate(102, 101, "2", 7))
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
        assertThat(fixture.calls.single().arguments)
            .isEqualTo(mapOf("parent_study_id" to 101L, "topic" to "2", "difficulty_level" to 7))
        assertThat(fixture.calls.single().principal).isSameAs(principal)
        assertThat(fixture.authorizationCalls).isEqualTo(2)
    }

    @Test
    fun `an idempotent existing child is available without announcing a new tree change or replaying`() = runBlocking {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts).apply {
            handler = { _, _ -> success(mapOf(
                "created" to false,
                "id" to 102L,
                "parentStudyId" to 101L,
                "topic" to "Redis Streams",
                "difficultyLevel" to 5,
            )) }
        }
        val context = childContext(101L, "redis streams", difficulty = 7)
        val arguments = mapOf<String, Any>(
            "parent_study_id" to 101L,
            "topic" to "redis streams",
            "difficulty_level" to 7,
        )

        val result = fixture.adapter.execute(context, "create_study_topic", arguments)

        assertThat(result.isError).isFalse()
        assertThat(json(result).path("created").asBoolean()).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(5)
        assertThat(json(result).path("notice").asText()).contains("already available", "level 5 was preserved")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.changeKind).isNull()
        assertThat(result.savedMutationTarget)
            .isEqualTo(VoiceTutorStudyTargetCandidate(102, 101, "Redis Streams", 5))
        assertThat(contexts.remembered).containsExactly(listOf(102L))
        assertCode(
            fixture.adapter.execute(context, "create_study_topic", arguments),
            "CHILD_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
    }

    @Test
    fun `walks owned ancestors before creating within a descendant`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.handler = { name, arguments ->
            when (name) {
                "get_study" -> when ((arguments.getValue("study_id") as Number).toLong()) {
                    301L -> success(mapOf("id" to 301L, "parentStudyId" to 201L))
                    201L -> success(mapOf("id" to 201L, "parentStudyId" to 101L))
                    else -> error("Unexpected ancestor")
                }
                "create_study_topic" -> success(mapOf(
                    "created" to true, "id" to 401L, "parentStudyId" to 301L, "topic" to "Streams", "difficultyLevel" to 5,
                ))
                else -> error("Unexpected tool")
            }
        }

        val result = fixture.adapter.execute(
            childContext(301L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 301L, "topic" to "Streams"),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study", "create_study_topic")
        assertThat(fixture.calls.map { it.principal }).allMatch { it === principal }
        assertThat(fixture.calls.take(2).map { it.arguments["language"] }).containsExactly("ko", "ko")
    }

    @Test
    fun `different roots failed ownership checks and malformed ancestor results never authorize creation`(): Unit = runBlocking {
        val invalidAncestors = listOf(
            success(mapOf("id" to 201L, "parentStudyId" to null)),
            success(mapOf("id" to 999L, "parentStudyId" to 101L)),
            success(mapOf("id" to 201L, "parentStudyId" to "101")),
            success(mapOf("id" to 201L, "parentStudyId" to 101.5)),
            failure("PERMISSION_DENIED"),
            McpSchema.CallToolResult.builder().addTextContent("unstructured result").isError(false).build(),
        )
        for (response in invalidAncestors) {
            val fixture = Fixture().apply { handler = { _, _ -> response } }
            val result = fixture.adapter.execute(
                childContext(201L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 201L, "topic" to "Streams"),
            )
            assertCode(result, "STUDY_SCOPE_DENIED")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `a call with no study cannot create topics`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = null) }
        val result = fixture.adapter.execute(
            childContext(101L, "Streams").copy(session = fixture.persistedSession!!),
            "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        )
        assertCode(result, "STUDY_SCOPE_DENIED")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `ancestor cycles and excessive depth are bounded and fail closed`(): Unit = runBlocking {
        val cyclic = Fixture().apply {
            handler = { _, arguments ->
                val id = (arguments.getValue("study_id") as Number).toLong()
                success(mapOf("id" to id, "parentStudyId" to if (id == 201L) 301L else 201L))
            }
        }
        assertCode(cyclic.adapter.execute(
            childContext(201L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Streams"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(cyclic.calls).hasSize(2)

        val deep = Fixture().apply {
            handler = { _, arguments ->
                val id = (arguments.getValue("study_id") as Number).toLong()
                success(mapOf("id" to id, "parentStudyId" to id + 1))
            }
        }
        assertCode(deep.adapter.execute(
            childContext(201L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Streams"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(deep.calls).hasSize(32)
        assertThat(deep.calls.map { it.name }).containsOnly("get_study")
    }

    @Test
    fun `ending or logout during ancestry is rechecked before a write`(): Unit = runBlocking {
        for (logout in listOf(false, true)) {
            val fixture = Fixture()
            fixture.handler = { _, _ ->
                if (logout) fixture.authorized = false
                else fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.ENDING)
                success(mapOf("id" to 201L, "parentStudyId" to 101L))
            }
            assertCode(fixture.adapter.execute(
                childContext(201L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 201L, "topic" to "Streams"),
            ), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `MCP permission and conflict failures remain errors and cannot announce a tree change`(): Unit = runBlocking {
        for (code in listOf("PERMISSION_DENIED", "VALIDATION_ERROR")) {
            val fixture = Fixture().apply { handler = { _, _ -> failure(code) } }
            val result = fixture.adapter.execute(
                childContext(101L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Streams"),
            )
            assertCode(result, code)
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
    }

    @Test
    fun `only positive integral bounded IDs from a successful creation become refresh targets`(): Unit = runBlocking {
        for (invalidId in listOf(-1, 0, 1.5, "102", 1e40)) {
            val fixture = Fixture().apply { handler = { _, _ -> success(mapOf(
                "created" to true, "id" to invalidId, "parentStudyId" to 101L, "topic" to "Streams", "difficultyLevel" to 5,
            )) } }
            val result = fixture.adapter.execute(
                childContext(101L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Streams"),
            )
            assertCode(result, "CHILD_CREATION_RESULT_UNCONFIRMED")
            assertThat(result.createdStudyId).isNull()
            assertThat(result.savedMutationTarget).isNull()
        }
        val failureWithId = Fixture().apply {
            handler = { _, _ -> McpSchema.CallToolResult.builder().structuredContent(mapOf("id" to 102L)).isError(true).build() }
        }
        assertThat(failureWithId.adapter.execute(
            childContext(101L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        ).createdStudyId).isNull()
    }

    @Test
    fun `oversized UTF8 read results return bounded valid JSON with explicit retry guidance`(): Unit = runBlocking {
        val fixture = Fixture().apply { handler = { _, _ -> success(mapOf("studies" to listOf("가".repeat(30_000)))) } }
        val result = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertCode(result, "RESULT_TOO_LARGE")
        assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
        assertThat(json(result).path("error").path("message").asText()).contains("smaller page")
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `read output accepts exactly sixteen KiB and rejects the next byte`(): Unit = runBlocking {
        val exact = Fixture().apply { handler = { _, _ -> success(mapOf("text" to "x".repeat(16 * 1_024 - 11))) } }
        val accepted = exact.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(accepted.isError).isFalse()
        assertThat(accepted.output.toByteArray(Charsets.UTF_8).size).isEqualTo(16 * 1_024)

        val oversized = Fixture().apply { handler = { _, _ -> success(mapOf("text" to "x".repeat(16 * 1_024 - 10))) } }
        assertCode(oversized.adapter.execute(context(), "list_studies", emptyMap()), "RESULT_TOO_LARGE")
    }

    @Test
    fun `oversized successful creation retains success and only bounded tree metadata`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Streams",
                "sortOrder" to 2, "difficultyLevel" to 5,
                "customPrompt" to "비공개".repeat(20_000),
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102L)
        assertThat(json(result).path("id").asLong()).isEqualTo(102L)
        assertThat(json(result).path("parentStudyId").asLong()).isEqualTo(101L)
        assertThat(json(result).path("truncated").asBoolean()).isTrue()
        assertThat(result.output).doesNotContain("비공개", "customPrompt")
        assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
    }

    @Test
    fun `unexpected handler failures are sanitized instead of leaking private exception details`(): Unit = runBlocking {
        val fixture = Fixture().apply { handler = { _, _ -> throw IllegalStateException("private-study-and-token-value") } }
        val result = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertCode(result, "MCP_UNAVAILABLE")
        assertThat(result.output).doesNotContain("private-study-and-token-value", "IllegalStateException")
    }

    @Test
    fun `call cancellation propagates instead of being converted to a successful tool result`() {
        val fixture = Fixture().apply { handler = { _, _ -> throw CancellationException("closed") } }
        assertThatThrownBy {
            runBlocking { fixture.adapter.execute(context(), "list_studies", emptyMap()) }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `explicit current-focus update keeps node identity and captures a new question level after the saved write`(): Unit = runBlocking {
        val contexts = ContextStore()
        val fixture = mutationFixture(contexts)
        val result = fixture.adapter.execute(
            updateContext(101L, difficulty = 6), "update_study",
            mapOf("study_id" to 101L, "difficulty_level" to 6),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.changeKind).isEqualTo(VoiceTutorStudyChangeKind.UPDATED)
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isEqualTo(101)
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus?.studyId).isEqualTo(101)
        assertThat(result.lessonFocus?.revision).isEqualTo(1)
        assertThat(result.lessonFocus?.topic).isEqualTo("Selected root")
        assertThat(result.lessonFocus?.difficulty).isEqualTo(6)
        assertThat(result.updatedStudySnapshot)
            .isEqualTo(VoiceTutorStudySnapshot(101, null, "Selected root", 6, revision = 1))
        assertThat(contexts.remembered).containsExactly(listOf(101L))
        assertThat(contexts.revised).containsExactly(101)
        assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("NEXT_QUESTION")
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(6)
        assertThat(fixture.calls.single { it.name == "update_study" }.arguments)
            .isEqualTo(mapOf<String, Any>(
                "study_id" to 101L,
                "difficulty_level" to 6,
                BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to "Selected root",
                BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to 5,
                BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to 0L,
            ))
    }

    @Test
    fun `off-focus update retains frozen selected lesson at the revised epoch without selecting edited child`(): Unit = runBlocking {
        val contexts = ContextStore()
        val fixture = mutationFixture(contexts)

        val result = fixture.adapter.execute(
            updateContext(102L, difficulty = 6), "update_study",
            mapOf("study_id" to 102L, "difficulty_level" to 6),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.changedStudyId).isEqualTo(102)
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus?.snapshot)
            .isEqualTo(VoiceTutorStudySnapshot(101, null, "Selected root", 5, revision = 1))
        assertThat(result.lessonFocus?.revision).isEqualTo(1)
        assertThat(result.updatedStudySnapshot)
            .isEqualTo(VoiceTutorStudySnapshot(102, 101, "Cache", 6, revision = 1))
        assertThat(fixture.persistedSession?.studyId).isEqualTo(101)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("REQUIRES_SELECTION")
        assertThat(json(result).path("voiceLessonFocus").path("studyId").asLong()).isEqualTo(101)
        assertThat(json(result).path("voiceLessonTree").path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(json(result).path("notice").asText()).contains("not the lesson focus", "existing selected lesson stays active")
        assertThat(contexts.saved.getValue(101).revision).isZero()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(contexts.remembered).containsExactly(listOf(102L))
        assertThat(contexts.revised).containsExactly(102L)
    }

    @Test
    fun `unrelated owner edit retains current lesson but compound start leaves focus to the owned pipeline`(): Unit = runBlocking {
        for (startAfterUpdate in listOf(false, true)) {
            val contexts = ContextStore().apply {
                live[202] = VoiceTutorStudySnapshot(202, null, "Spring", 5)
            }
            val fixture = mutationFixture(contexts)
            val request = updateContext(
                studyId = 202, difficulty = 7,
                scope = VoiceTutorStudyUpdateAuthorizationScope.OWNER_READ,
                targetProof = VoiceTutorStudyUpdateTargetProof(202, null, "Spring", 5),
                startLessonAfterUpdate = startAfterUpdate,
            )

            val result = fixture.adapter.execute(request, "update_study", mapOf("study_id" to 202L, "difficulty_level" to 7))

            assertThat(result.isError).isFalse()
            assertThat(result.updatedStudySnapshot).isEqualTo(VoiceTutorStudySnapshot(202, null, "Spring", 7, 1))
            assertThat(fixture.focusSelections).isEmpty()
            assertThat(contexts.saved.getValue(101)).isEqualTo(VoiceTutorStudySnapshot(101, null, "Selected root", 5))
            if (startAfterUpdate) {
                assertThat(result.lessonFocus).isNull()
                assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("AUTO_FOCUS_PENDING")
            } else {
                assertThat(result.lessonFocus?.snapshot)
                    .isEqualTo(VoiceTutorStudySnapshot(101, null, "Selected root", 5, 1))
                assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("REQUIRES_SELECTION")
                assertThat(json(result).path("notice").asText()).contains("applies only to studying that edited node")
            }
        }
    }

    @Test
    fun `owner read authorizes an exact level update without a selected focus or child reads`(): Unit = runBlocking {
        val contexts = ContextStore().apply {
            live[202] = VoiceTutorStudySnapshot(202, null, "스프링", 5)
        }
        val fixture = mutationFixture(contexts).apply { persistedSession = discoverySession() }
        val request = updateContext(
            studyId = 202,
            difficulty = 7,
            scope = VoiceTutorStudyUpdateAuthorizationScope.OWNER_READ,
            targetProof = VoiceTutorStudyUpdateTargetProof(202, null, "스프링", 5),
        ).copy(session = discoverySession())

        val result = fixture.adapter.execute(request, "update_study", mapOf("study_id" to 202L, "difficulty_level" to 7))

        assertThat(result.isError).isFalse()
        assertThat(result.updatedStudySnapshot).isEqualTo(VoiceTutorStudySnapshot(202, null, "스프링", 7, 1))
        assertThat(result.lessonFocus).isNull()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "update_study")
        assertThat(fixture.calls.single { it.name == "update_study" }.arguments)
            .containsEntry("difficulty_level", 7)
            .containsEntry(BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT, 5)
        assertCode(fixture.adapter.execute(request, "update_study", mapOf("study_id" to 202L, "difficulty_level" to 7)), "STUDY_UPDATE_REQUEST_REQUIRED")
        assertThat(fixture.calls.count { it.name == "update_study" }).isEqualTo(1)
    }

    @Test
    fun `exact spoken candidate can be renamed before selection with owner read and revised readback`(): Unit =
        runBlocking {
            val contexts = ContextStore().apply {
                live[202L] = VoiceTutorStudySnapshot(202, 200, "Spring backend", 7)
            }
            val fixture = mutationFixture(contexts).apply { persistedSession = discoverySession() }
            val proof = VoiceTutorStudyUpdateTargetProof(202, 200, "Spring backend", 7)
            val updateContext = updateContext(
                studyId = 202,
                topic = "Spring server",
                scope = VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
                targetProof = proof,
                startLessonAfterUpdate = true,
            ).copy(session = discoverySession())

            val result = fixture.adapter.execute(
                updateContext,
                "update_study",
                mapOf("study_id" to 202L, "topic" to "Spring server"),
            )

            assertThat(result.isError).isFalse()
            assertThat(result.changedStudyId).isEqualTo(202)
            assertThat(result.lessonRevision).isEqualTo(1)
            assertThat(result.lessonFocus).isNull()
            assertThat(result.updatedStudySnapshot)
                .isEqualTo(VoiceTutorStudySnapshot(202, 200, "Spring server", 7, revision = 1))
            assertThat(contexts.remembered).containsExactly(listOf(202L))
            assertThat(contexts.revised).containsExactly(202L)
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "update_study")
            assertThat(fixture.calls.single { it.name == "update_study" }.arguments)
                .isEqualTo(mapOf<String, Any>(
                    "study_id" to 202L,
                    "topic" to "Spring server",
                    BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to "Spring backend",
                    BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to 7,
                    BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to 200L,
                ))
            assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
            assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("AUTO_FOCUS_PENDING")
            assertThat(json(result).path("voiceLessonTopics")).hasSize(1)
            assertThat(json(result).path("voiceLessonTopics")[0].path("studyId").asLong()).isEqualTo(202)
            assertThat(json(result).path("voiceLessonTopics")[0].path("parentStudyId").asLong()).isEqualTo(200)
            assertThat(json(result).path("voiceLessonTopics")[0].path("topic").asText())
                .isEqualTo("Spring server")
            assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(7)
            assertThat(json(result).path("notice").asText())
                .contains("Do not speak", "UPDATED_STUDY_IMMEDIATE_START")
        }

    @Test
    fun `offered candidate update denies argument mismatch stale live identity stale baseline and replay`(): Unit =
        runBlocking {
            val proof = VoiceTutorStudyUpdateTargetProof(202, 200, "Spring backend", 7)

            val mismatchedContexts = ContextStore().apply {
                live[202L] = VoiceTutorStudySnapshot(202, 200, "Spring backend", 7)
            }
            val mismatched = mutationFixture(mismatchedContexts).apply { persistedSession = discoverySession() }
            val mismatchedContext = updateContext(
                202,
                topic = "Spring server",
                scope = VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
                targetProof = proof,
            ).copy(session = discoverySession())
            assertCode(
                mismatched.adapter.execute(
                    mismatchedContext,
                    "update_study",
                    mapOf("study_id" to 203L, "topic" to "Spring server"),
                ),
                "STUDY_UPDATE_REQUEST_MISMATCH",
            )
            assertThat(mismatched.calls).isEmpty()

            val staleLiveContexts = ContextStore().apply {
                live[202L] = VoiceTutorStudySnapshot(202, 200, "Changed elsewhere", 7)
            }
            val staleLive = mutationFixture(staleLiveContexts).apply { persistedSession = discoverySession() }
            val staleLiveContext = updateContext(
                202,
                topic = "Spring server",
                scope = VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
                targetProof = proof,
            ).copy(session = discoverySession())
            assertCode(
                staleLive.adapter.execute(
                    staleLiveContext,
                    "update_study",
                    mapOf("study_id" to 202L, "topic" to "Spring server"),
                ),
                "STUDY_UPDATE_TARGET_STALE",
            )
            assertThat(staleLive.calls.map { it.name }).containsExactly("get_study")
            assertThat(staleLiveContexts.remembered).isEmpty()

            val staleBaselineContexts = ContextStore().apply {
                live[202L] = VoiceTutorStudySnapshot(202, 200, "Spring backend", 7)
                saved[202L] = VoiceTutorStudySnapshot(202, 200, "Older captured name", 7)
            }
            val staleBaseline = mutationFixture(staleBaselineContexts).apply { persistedSession = discoverySession() }
            val staleBaselineContext = updateContext(
                202,
                topic = "Spring server",
                scope = VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
                targetProof = proof,
            ).copy(session = discoverySession())
            assertCode(
                staleBaseline.adapter.execute(
                    staleBaselineContext,
                    "update_study",
                    mapOf("study_id" to 202L, "topic" to "Spring server"),
                ),
                "STUDY_UPDATE_TARGET_STALE",
            )
            assertThat(staleBaseline.calls.map { it.name }).containsExactly("get_study")
            assertThat(staleBaselineContexts.remembered).containsExactly(listOf(202L))
            assertThat(staleBaselineContexts.revised).isEmpty()

            val replayContexts = ContextStore().apply {
                live[202L] = VoiceTutorStudySnapshot(202, 200, "Spring backend", 7)
            }
            val replay = mutationFixture(replayContexts).apply { persistedSession = discoverySession() }
            val replayContext = updateContext(
                202,
                topic = "Spring server",
                scope = VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
                targetProof = proof,
            ).copy(session = discoverySession())
            val arguments = mapOf<String, Any>("study_id" to 202L, "topic" to "Spring server")
            assertThat(replay.adapter.execute(replayContext, "update_study", arguments).isError).isFalse()
            assertCode(
                replay.adapter.execute(replayContext, "update_study", arguments),
                "STUDY_UPDATE_REQUEST_REQUIRED",
            )
            assertThat(replay.calls.count { it.name == "update_study" }).isEqualTo(1)
        }

    @Test
    fun `persisted child and update leases execute exact patches once and reject replay or field smuggling`(): Unit =
        runBlocking {
            val childFixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(mapOf(
                    "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Streams", "difficultyLevel" to 5,
                )) }
            }
            val childContext = childContext(101L, "Streams")
            val childArguments = mapOf<String, Any>("parent_study_id" to 101L, "topic" to "Streams")

            assertCode(
                childFixture.adapter.execute(
                    childContext,
                    "create_study_topic",
                    childArguments + ("difficulty_level" to 7),
                ),
                "CHILD_CREATION_REQUEST_MISMATCH",
            )
            assertThat(childFixture.calls).isEmpty()
            assertThat(childFixture.adapter.execute(childContext, "create_study_topic", childArguments).isError).isFalse()
            assertCode(
                childFixture.adapter.execute(childContext, "create_study_topic", childArguments),
                "CHILD_CREATION_REQUEST_REQUIRED",
            )
            assertThat(childFixture.calls.count { it.name == "create_study_topic" }).isEqualTo(1)

            val updateFixture = mutationFixture(ContextStore())
            val updateContext = updateContext(102L, topic = "Renamed cache", difficulty = 6)
            val updateArguments = mapOf<String, Any>(
                "study_id" to 102L,
                "topic" to "Renamed cache",
                "difficulty_level" to 6,
            )

            assertCode(
                updateFixture.adapter.execute(
                    updateContext,
                    "update_study",
                    mapOf("study_id" to 102L, "difficulty_level" to 6),
                ),
                "STUDY_UPDATE_REQUEST_MISMATCH",
            )
            assertThat(updateFixture.adapter.execute(updateContext, "update_study", updateArguments).isError).isFalse()
            assertCode(
                updateFixture.adapter.execute(updateContext, "update_study", updateArguments),
                "STUDY_UPDATE_REQUEST_REQUIRED",
            )
            assertCode(
                mutationFixture(ContextStore()).adapter.execute(
                    updateContext(102L, difficulty = 6),
                    "update_study",
                    mapOf("study_id" to 102L, "topic" to "   ", "difficulty_level" to 6),
                ),
                "INVALID_ARGUMENTS",
            )
            assertThat(updateFixture.calls.count { it.name == "update_study" }).isEqualTo(1)
            assertThat(updateFixture.calls.single { it.name == "update_study" }.arguments)
                .isEqualTo(updateArguments + mapOf(
                    BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to "Cache",
                    BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to 3,
                    BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to 101L,
                ))
        }

    @Test
    fun `metadata capture failure keeps a confirmed update distinct from an unprepared lesson`(): Unit = runBlocking {
        val contexts = ContextStore().apply { failRevision = true }
        val fixture = mutationFixture(contexts)
        val result = fixture.adapter.execute(
            updateContext(102L, topic = "Renamed cache"), "update_study",
            mapOf("study_id" to 102L, "topic" to "Renamed cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.changeKind).isEqualTo(VoiceTutorStudyChangeKind.UPDATED)
        assertThat(result.lessonRevision).isNull()
        assertThat(result.updatedStudySnapshot).isNull()
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("NOT_PREPARED")
        assertThat(result.output).doesNotContain("private-revision-detail")
        assertThat(fixture.calls.count { it.name == "update_study" }).isEqualTo(1)
    }

    @Test
    fun `unprepared and revision capped calls cannot change settings`(): Unit = runBlocking {
        for ((contexts, revision) in listOf(
            UnavailableVoiceTutorStudyContextPort to 0L,
            ContextStore().apply { revision = 32 } to 32L,
        )) {
            val fixture = mutationFixture(contexts)
            fixture.acceptedLessonRevision = revision
            assertCode(fixture.adapter.execute(
                updateContext(102L, difficulty = 6, lessonRevision = revision), "update_study",
                mapOf("study_id" to 102L, "difficulty_level" to 6),
            ), "LESSON_CONTEXT_UNAVAILABLE")
            assertThat(fixture.calls.none { it.name == "update_study" }).isTrue()
        }
    }

    @Test
    fun `a focus scoped update cannot escape its tree and an unassessed deletion is denied`(): Unit = runBlocking {
        val fixture = mutationFixture()
        assertCode(fixture.adapter.execute(
            updateContext(900L, difficulty = 6), "update_study",
            mapOf("study_id" to 900L, "difficulty_level" to 6),
        ), "STUDY_SCOPE_DENIED")
        assertCode(fixture.adapter.execute(
            context(), "delete_study", mapOf("study_id" to 900L, "confirm" to false),
        ), "DELETE_REQUEST_REQUIRED")
        assertThat(fixture.calls.none { it.name in listOf("update_study", "delete_study") }).isTrue()
    }

    @Test
    fun `direct assessed deletion resolves the exact subtree once and retains learning history`(): Unit = runBlocking {
        val contexts = ContextStore()
        val existingHistorySnapshots = contexts.saved.toMap()
        val fixture = mutationFixture(contexts)
        val request = deletionContext(101)
        val arguments = mapOf("study_id" to 101L, "confirm" to true)

        val deleted = fixture.adapter.execute(request, "delete_study", arguments)

        assertThat(deleted.isError).isFalse()
        assertThat(deleted.studyTreeChanged).isTrue()
        assertThat(deleted.changeKind).isEqualTo(VoiceTutorStudyChangeKind.DELETED)
        assertThat(deleted.deletedStudyIds).containsExactly(101, 102, 103)
        assertThat(deleted.changedStudyId).isEqualTo(101)
        assertThat(deleted.lessonFocusCleared).isTrue()
        assertThat(json(deleted).path("voiceLessonSelectionDeleted").asBoolean()).isTrue()
        assertThat(json(deleted).path("notice").asText()).contains("Existing learning records remain", "Stay connected")
        assertThat(json(deleted).has("confirmation_token")).isFalse()
        assertThat(fixture.calls.single { it.name == "delete_study" }.arguments)
            .isEqualTo(mapOf("study_id" to 101L, "confirm" to true, "expected_study_ids" to listOf(101L, 102L, 103L)))
        assertThat(fixture.calls.map { it.name })
            .containsExactly("get_study", "list_studies", "list_studies", "list_studies", "delete_study")
        assertThat(contexts.saved).isEqualTo(existingHistorySnapshots)
        assertThat(fixture.focusSelections).isEmpty()
        assertCode(fixture.adapter.execute(request, "delete_study", arguments), "DELETE_REQUEST_REQUIRED")
        assertThat(fixture.calls.count { it.name == "delete_study" }).isEqualTo(1)
    }

    @Test
    fun `direct deletion of an owned node does not require a selected lesson`(): Unit = runBlocking {
        val fixture = mutationFixture().apply { persistedSession = discoverySession() }
        val request = deletionContext(
            900,
            targetProof = VoiceTutorStudyUpdateTargetProof(900, null, "Cache", 3),
        ).copy(session = discoverySession())

        val result = fixture.adapter.execute(request, "delete_study", mapOf("study_id" to 900L, "confirm" to true))

        assertThat(result.isError).isFalse()
        assertThat(result.deletedStudyIds).containsExactly(900)
        assertThat(result.lessonFocusCleared).isFalse()
        assertThat(json(result).path("voiceLessonSelectionDeleted").asBoolean()).isFalse()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.calls.single { it.name == "delete_study" }.arguments)
            .containsEntry("expected_study_ids", listOf(900L))
    }

    @Test
    fun `deletion rejects missing revoked mismatched and unpersisted learner authority before reading targets`(): Unit = runBlocking {
        val arguments = mapOf("study_id" to 101L, "confirm" to true)
        val revoked = deletionContext(101, claimForExecution = false).also {
            val authorization = requireNotNull(it.dialogueBoundary?.studyDeletionAuthorization)
            authorization.invalidate()
            assertThat(authorization.isActive()).isFalse()
        }
        val wrongIntent = deletionContext(101).let {
            it.copy(dialogueBoundary = requireNotNull(it.dialogueBoundary).copy(latestAcceptedLearnerIntent = VoiceTutorInputIntent.NONE))
        }
        val wrongPersistedItem = deletionContext(101).let {
            it.copy(dialogueBoundary = requireNotNull(it.dialogueBoundary).copy(latestAcceptedLearnerProviderItemId = "not-persisted"))
        }
        for (request in listOf(context(), revoked, deletionContext(102), wrongIntent, wrongPersistedItem)) {
            val fixture = mutationFixture()
            assertCode(fixture.adapter.execute(request, "delete_study", arguments), "DELETE_REQUEST_REQUIRED")
            assertThat(fixture.calls).isEmpty()
        }
        val fixture = mutationFixture()
        assertCode(fixture.adapter.execute(deletionContext(101), "delete_study", arguments + ("confirm" to false)), "DELETE_REQUEST_REQUIRED")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `saved target metadata changed after assessment blocks deletion`(): Unit = runBlocking {
        for (changed in listOf(
            VoiceTutorStudySnapshot(101, null, "Renamed root", 5),
            VoiceTutorStudySnapshot(101, null, "Selected root", 7),
            VoiceTutorStudySnapshot(101, 900, "Selected root", 5),
        )) {
            val fixture = mutationFixture(ContextStore().apply { live[101] = changed })
            val result = fixture.adapter.execute(deletionContext(101), "delete_study", mapOf("study_id" to 101L, "confirm" to true))
            assertCode(result, "DELETE_TARGET_STALE")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.deletedStudyIds).isEmpty()
            assertThat(fixture.calls.none { it.name == "delete_study" }).isTrue()
        }
    }

    @Test
    fun `changed subtree membership consumes deletion authority without success or replay`(): Unit = runBlocking {
        val fixture = mutationFixture()
        val ordinary = fixture.handler
        fixture.handler = { name, args -> if (name == "delete_study") failure("STUDY_TREE_CHANGED") else ordinary(name, args) }
        val arguments = mapOf("study_id" to 101L, "confirm" to true)
        val request = deletionContext(101)
        val result = fixture.adapter.execute(request, "delete_study", arguments)
        assertCode(result, "STUDY_TREE_CHANGED")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.deletedStudyIds).isEmpty()
        assertThat(result.lessonFocusCleared).isFalse()
        assertCode(fixture.adapter.execute(request, "delete_study", arguments), "DELETE_REQUEST_REQUIRED")
        assertThat(fixture.calls.count { it.name == "delete_study" }).isEqualTo(1)
    }

    @Test
    fun `unconfirmed deletion result never reports success or reuses consumed authority`(): Unit = runBlocking {
        for (payload in listOf(
            mapOf("deleted" to false, "studyId" to 101L),
            mapOf("deleted" to "true", "studyId" to 101L),
            mapOf("deleted" to true, "studyId" to 102L),
            mapOf("studyId" to 101L),
        )) {
            val fixture = mutationFixture()
            val ordinary = fixture.handler
            fixture.handler = { name, args -> if (name == "delete_study") success(payload) else ordinary(name, args) }
            val request = deletionContext(101)
            val arguments = mapOf("study_id" to 101L, "confirm" to true)
            val result = fixture.adapter.execute(request, "delete_study", arguments)
            assertCode(result, "DELETE_RESULT_UNCONFIRMED")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.deletedStudyIds).isEmpty()
            assertCode(fixture.adapter.execute(request, "delete_study", arguments), "DELETE_REQUEST_REQUIRED")
            assertThat(fixture.calls.count { it.name == "delete_study" }).isEqualTo(1)
        }
    }

    @Test
    fun `incomplete cyclic malformed or oversized subtree cannot execute an authorized deletion`(): Unit = runBlocking {
        for (page in listOf(
            mapOf("studies" to emptyList<Any>(), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to listOf(mapOf("id" to 101L, "parentStudyId" to 101L)), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to listOf(mapOf("id" to 102L, "parentStudyId" to 999L)), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to (102L..230L).map { mapOf("id" to it, "parentStudyId" to 101L) }, "totalCount" to 129, "offset" to 0),
        )) {
            val fixture = mutationFixture()
            val ordinary = fixture.handler
            fixture.handler = { name, args -> if (name == "list_studies") success(page) else ordinary(name, args) }
            assertCode(fixture.adapter.execute(deletionContext(101), "delete_study", mapOf("study_id" to 101L, "confirm" to true)), "DELETE_PREVIEW_UNAVAILABLE")
            assertThat(fixture.calls.none { it.name == "delete_study" }).isTrue()
        }
    }

    @Test
    fun `provider supplied subtree manifests and confirmation tokens are rejected`(): Unit = runBlocking {
        val fixture = mutationFixture()
        for (extra in listOf(mapOf("expected_study_ids" to listOf(101L)), mapOf("confirmation_token" to "old-preview-token"), mapOf("confirmation_token" to 23))) {
            assertCode(fixture.adapter.execute(deletionContext(101), "delete_study", mapOf("study_id" to 101L, "confirm" to true) + extra), "INVALID_ARGUMENTS")
        }
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `deleting the selected live node preserves call authorization without granting a missing parent`(): Unit = runBlocking {
        val fixture = mutationFixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = 101L) }
        val read = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(read.isError).isFalse()
        assertCode(fixture.adapter.execute(
            childContext(101L, "New"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "New"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(fixture.calls.none { it.name == "create_study_topic" }).isTrue()
    }

    @Test
    fun `deletion confirmation is bound to identity target fresh dialogue and expiry`() {
        var instant = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = instant
        }
        val tickets = VoiceTutorDeletionConfirmations(clock)
        val ticket = tickets.prepare(context(), 101, listOf(101, 102), 11)!!
        assertThat(tickets.consume(confirmedContext(), 102, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext().copy(callId = "another-call"), 101, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 11)).isNull()
        assertThat(tickets.consume(context(), 101, ticket.token, 14)).isNull()
        instant = now.plusSeconds(120)
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 14)).isNull()
        instant = now
        val second = tickets.prepare(context(), 101, listOf(101), 11)!!
        assertThat(tickets.consume(confirmedContext(), 101, second.token, 14)).isNotNull()
        assertThat(tickets.consume(confirmedContext(), 101, second.token, 15)).isNull()
    }

    @Test
    fun `late accepted ASR from speech before the confirmation question is not new consent`() {
        val tickets = VoiceTutorDeletionConfirmations(Clock.fixed(now, ZoneOffset.UTC))
        val ticket = tickets.prepare(context(), 101, listOf(101), 11)!!
        val latePublication = confirmedContext().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
            responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 4,
            precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 3,
        ))
        // The database assigns U14 AFTER T13, but its real speech began BEFORE
        // the confirmation prompt finished. Later unapproved noise cannot change this order.
        assertThat(tickets.consume(latePublication, 101, ticket.token, 14)).isNull()
        val oldMixedAudio = confirmedContext().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
            responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 7,
            precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 2,
        ))
        assertThat(tickets.consume(oldMixedAudio, 101, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 15)).isNotNull()
    }

    private fun confirmedContext() = context().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
        responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 7,
        precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 3,
    ))

    private fun mutationFixture(studyContexts: VoiceTutorStudyContextPort = ContextStore()) = Fixture(studyContexts = studyContexts).apply {
        handler = { name, args ->
            when (name) {
                "get_study" -> {
                    val id = (args.getValue("study_id") as Number).toLong()
                    val stored = (studyContexts as? ContextStore)?.live?.get(id)
                        ?: (studyContexts as? ContextStore)?.saved?.get(id)
                    success(mapOf(
                        "id" to id,
                        "parentStudyId" to (stored?.parentStudyId ?: if (id in 102L..103L) 101L else null),
                        "topic" to (stored?.topic ?: "Cache"),
                        "difficultyLevel" to (stored?.difficulty ?: 3),
                    ))
                }
                "list_studies" -> {
                    val children = if (args["parent_study_id"] == 101L) listOf(102L, 103L) else emptyList()
                    success(mapOf("studies" to children.map { mapOf("id" to it, "parentStudyId" to 101L) }, "totalCount" to children.size, "offset" to 0))
                }
                "update_study" -> {
                    val id = (args.getValue("study_id") as Number).toLong()
                    val stored = (studyContexts as? ContextStore)?.live?.get(id)
                        ?: (studyContexts as? ContextStore)?.saved?.get(id)
                        ?: VoiceTutorStudySnapshot(id, 101, "Cache", 3)
                    val updated = stored.copy(
                        topic = args["topic"] as? String ?: stored.topic,
                        difficulty = (args["difficulty_level"] as? Number)?.toInt() ?: stored.difficulty,
                    )
                    (studyContexts as? ContextStore)?.live?.set(id, updated)
                    success(mapOf(
                        "id" to id,
                        "topic" to updated.topic,
                        "difficultyLevel" to updated.difficulty,
                        "parentStudyId" to updated.parentStudyId,
                    ))
                }
                "delete_study" -> success(mapOf("deleted" to true, "studyId" to args["study_id"]))
                else -> failure("UNEXPECTED_TOOL")
            }
        }
    }

    @Test
    fun `native catalog exposes model selection and immutable proposal tools but hides raw mutations`() = runBlocking<Unit> {
        val fixture = Fixture()
        val catalog = fixture.adapter.realtimeDefinitions()
        assertThat(catalog.map { it.name }).contains("prepare_voice_study_mutation", "confirm_voice_study_mutation", "select_voice_study")
            .doesNotContain("create_root_study", "create_study_topic", "update_study", "delete_study", "submit_answer")
        assertThat(catalog.joinToString { it.description }).doesNotContain("server-owned: never originate", "one-shot target attested")
        assertThat(catalog.single { it.name == "select_voice_study" }.description)
            .contains("curriculumTerminal", "original root", "form is pending", "final voiceLessonFocus.studyId", "list_pending_questions separately", "On cancellation", "does not roll back")
        assertThat(catalog.single { it.name == "advance_voice_study" }.description)
            .contains("curriculum gate", "wait for the app choice", "final voiceLessonFocus.studyId", "list_pending_questions separately", "cancellation or topic switch")
        assertThat(catalog.single { it.name == "list_studies" }.description).contains("No questions", "limit 10", "curriculumTerminal")
            .doesNotContain("including pending")
        assertThat(jacksonObjectMapper().valueToTree<JsonNode>(catalog.single { it.name == "list_studies" }.parameters)
            .path("properties").path("limit").path("default").asInt()).isEqualTo(10)
        assertThat(catalog.single { it.name == "prepare_voice_study_mutation" }.description)
            .contains("new root defaults to 5", "Child creation always inherits the original main root level")
        val denied = fixture.adapter.execute(nativeContext(), "create_root_study", mapOf("topic" to "Spring", "difficulty_level" to 7))
        assertThat(json(denied).path("error").path("code").asText()).isEqualTo("PROPOSAL_REQUIRED")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `native study discovery keeps a complete ten topic page without embedded question hints or custom prompts`() = runBlocking<Unit> {
        val studies = (201L..210L).map { id -> mapOf(
            "id" to id, "parentStudyId" to null, "topic" to "Redis $id", "difficultyLevel" to 8,
            "sortOrder" to (id - 201).toInt(), "enabled" to true, "activeForQuestions" to false,
            "customPrompt" to "PRIVATE_CUSTOM_PROMPT".repeat(2_000),
            "pendingQuestion" to mapOf("question" to mapOf("question" to "PRIVATE_SAVED_QUESTION",
                "expectedAnswerHint" to "PRIVATE_ANSWER_HINT"), "answer" to "PRIVATE_ANSWER", "likeCount" to 10),
            "latestQuestion" to mapOf("gradingResult" to "PRIVATE_GRADE"), "lastError" to "PRIVATE_ERROR",
        ) }
        val rawPage = completePage(studies, totalCount = 10, limit = 10)
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply { handler = { _, _ -> success(rawPage) } }
        lateinit var result: VoiceTutorMcpToolResult
        val exchanges = captureExchanges {
            result = fixture.adapter.execute(nativeContext(), "list_studies", mapOf("query" to "Redis", "limit" to 10, "offset" to 0))
        }
        assertThat(result.isError).isFalse()
        val body = json(result)
        assertThat(body.path("studies").map { it.path("id").asLong() }).containsExactlyElementsOf(201L..210L)
        assertThat(body.path("studies").first().fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("id", "parentStudyId", "topic", "difficultyLevel", "sortOrder", "enabled", "activeForQuestions")
        assertThat(body.path("studies").first().path("parentStudyId").isNull).isTrue()
        assertThat(body.path("studies").last().path("difficultyLevel").asInt()).isEqualTo(8)
        assertThat(body.path("totalCount").asLong()).isEqualTo(10)
        assertThat(body.path("limit").asInt()).isEqualTo(10)
        assertThat(body.path("offset").asInt()).isZero()
        assertThat(result.output).doesNotContain("PRIVATE_", "pendingQuestion", "latestQuestion", "customPrompt", "likeCount", "lastError")
        assertThat(exchanges.toString()).doesNotContain("PRIVATE_", "expectedAnswerHint")
        assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThan(16 * 1_024)
        assertThat(result.candidateDiscovery?.scope).isEqualTo(VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 10, 10))
        assertThat(result.candidateDiscovery?.candidates?.map { it.studyId }).containsExactlyElementsOf(201L..210L)
        assertThat(fixture.calls.map { it.name }).containsExactly("list_studies")
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(store.remembered).isEmpty()
        assertThat(store.revised).isEmpty()
        assertThat(rawPage.toString()).contains("PRIVATE_ANSWER_HINT")
    }

    @Test
    fun `native discovery preserves exact child paging while projection cannot repair invalid candidate evidence`() = runBlocking<Unit> {
        val child = candidate(201, 101) + mapOf("difficultyLevel" to 8, "curriculumTerminal" to true, "pendingQuestion" to "PRIVATE_QUESTION")
        val complete = completePage(listOf(child), totalCount = 1, limit = 10)
        val fixture = Fixture(studyContexts = ContextStore()).apply { handler = { _, _ -> success(complete) } }
        val arguments = mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0)
        val valid = fixture.adapter.execute(nativeContext(), "list_studies", arguments)
        assertThat(valid.candidateDiscovery?.scope).isEqualTo(VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 1))
        assertThat(valid.candidateDiscovery?.candidates).containsExactly(VoiceTutorStudyTargetCandidate(201, 101, "Topic 201"))
        assertThat(json(valid).path("studies").first().path("parentStudyId").asLong()).isEqualTo(101L)
        assertThat(json(valid).path("studies").first().path("difficultyLevel").asInt()).isEqualTo(8)
        assertThat(json(valid).path("studies").first().path("curriculumTerminal").asBoolean()).isTrue()

        val invalidPages = listOf(
            completePage(listOf(child), totalCount = 2, limit = 10),
            completePage(listOf(child), totalCount = 1, limit = 10, offset = 1),
            completePage(listOf(child, child), totalCount = 2, limit = 10),
            completePage(listOf(child + ("parentStudyId" to 999L)), totalCount = 1, limit = 10),
            completePage(listOf(child - "parentStudyId"), totalCount = 1, limit = 10),
        )
        for (page in invalidPages) {
            fixture.handler = { _, _ -> success(page) }
            val result = fixture.adapter.execute(nativeContext(), "list_studies", arguments)
            assertThat(result.isError).isFalse()
            assertThat(result.candidateDiscovery).isNull()
            assertThat(json(result).path("studies").size()).isEqualTo((page.getValue("studies") as List<*>).size)
            assertThat(result.output).doesNotContain("PRIVATE_QUESTION")
        }
    }

    @Test
    fun `native discovery retains errors and output bounds without returning a partial or malformed page`() = runBlocking<Unit> {
        val fixture = Fixture(studyContexts = ContextStore())
        fixture.handler = { _, _ -> failure("READ_FAILED") }
        assertCode(fixture.adapter.execute(nativeContext(), "list_studies", emptyMap()), "READ_FAILED")
        fixture.handler = { _, _ -> success(mapOf("studies" to listOf("PRIVATE_MALFORMED_ROW"))) }
        val malformed = fixture.adapter.execute(nativeContext(), "list_studies", emptyMap())
        assertCode(malformed, "INVALID_TOOL_RESULT")
        assertThat(malformed.output).doesNotContain("PRIVATE_MALFORMED_ROW")
        fixture.handler = { _, _ -> success(completePage(listOf(candidate(201, null) + ("topic" to "가".repeat(6_000))),
            totalCount = 1, limit = 10)) }
        val oversized = fixture.adapter.execute(nativeContext(), "list_studies", mapOf("query" to "Redis", "limit" to 10))
        assertCode(oversized, "RESULT_TOO_LARGE")
        assertThat(oversized.candidateDiscovery).isNull()
        assertThat(json(oversized).has("studies")).isFalse()
        assertThat(oversized.output.toByteArray(Charsets.UTF_8).size).isLessThan(16 * 1_024)
    }

    @Test
    fun `study discovery projection is limited to native voice and leaves ordinary voice metadata intact`() = runBlocking<Unit> {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { _, _ -> success(completePage(listOf(candidate(201, null) + mapOf(
                "customPrompt" to "original prompt", "pendingQuestion" to mapOf("id" to "301"))), totalCount = 1, limit = 10)) }
        }
        val legacy = fixture.adapter.execute(context(), "list_studies", mapOf("query" to "Redis", "limit" to 10))
        val native = fixture.adapter.execute(nativeContext(), "list_studies", mapOf("query" to "Redis", "limit" to 10))
        assertThat(legacy.isError).isFalse()
        assertThat(legacy.output).contains("original prompt", "pendingQuestion")
        assertThat(native.isError).isFalse()
        assertThat(native.output).doesNotContain("original prompt", "pendingQuestion")
        assertThat(native.candidateDiscovery).isEqualTo(legacy.candidateDiscovery)
    }

    @Test
    fun `native natural confirmation creates exact root once without any classifier intent or lease`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.handler = { name, args ->
            assertThat(name).isEqualTo("create_root_study")
            success(mapOf("created" to true, "id" to 202L, "parentStudyId" to null,
                "topic" to args["topic"], "difficultyLevel" to args["difficulty_level"], "enabled" to true, "activeForQuestions" to false))
        }
        val args = mapOf("action" to "create_root_study", "topic" to "Spring", "difficulty_level" to 7)
        val prepared = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", args)
        val repeated = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", args)
        val id = json(prepared).path("proposal_id").asText()
        assertThat(id).isNotBlank()
        assertThat(json(repeated).path("proposal_id").asText()).isEqualTo(id)
        assertThat(json(prepared).path("executed").asBoolean()).isFalse()
        assertThat(prepared.mutationConfirmationQuestion).isEqualTo("Spring 주제를 레벨 7로 만들까요?")
        assertThat(fixture.calls).isEmpty()
        fixture.learnerTurnId = 13; fixture.tutorTurnId = 12
        val confirmed = fixture.adapter.execute(nativeContext(confirming = true), "confirm_voice_study_mutation", mapOf("proposal_id" to id, "confirm" to true))
        assertThat(confirmed.isError).isFalse()
        assertThat(json(confirmed).path("notice").asText()).contains("without another confirmation")
            .doesNotContain("new learner agreement", "server-owned", "independently authorized")
        assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
        assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(mapOf("topic" to "Spring", "difficulty_level" to 7))
        val duplicate = fixture.adapter.execute(nativeContext(confirming = true), "confirm_voice_study_mutation", mapOf("proposal_id" to id, "confirm" to true))
        assertThat(duplicate.isError).isTrue()
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `native refusal cancels proposal and malformed confirmation cannot alter its patch`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.learnerTurnId = 50 // Database IDs are identities, not the native spoken-order authority.
        val prepared = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", mapOf("action" to "create_root_study", "topic" to "Spring"))
        val id = json(prepared).path("proposal_id").asText()
        fixture.learnerTurnId = 13; fixture.tutorTurnId = 12
        val changed = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", mapOf("proposal_id" to id, "confirm" to true, "topic" to "Different"))
        assertThat(changed.isError).isTrue()
        val cancelled = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", mapOf("proposal_id" to id, "confirm" to false))
        assertThat(json(cancelled).path("cancelled").asBoolean()).isTrue()
        val replay = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", mapOf("proposal_id" to id, "confirm" to true))
        assertThat(replay.isError).isTrue()
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `native confirmation waits for actual playout and persisted dialogue boundary without consuming proposal`() = runBlocking<Unit> {
        val fixture = Fixture()
        val prepared = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", mapOf("action" to "create_root_study", "topic" to "Spring"))
        val args = mapOf("proposal_id" to json(prepared).path("proposal_id").asText(), "confirm" to false)
        fixture.learnerTurnId = 13; fixture.tutorTurnId = 12
        val early = nativeContext(true).let { it.copy(dialogueBoundary = it.dialogueBoundary!!.copy(latestAcceptedLearnerSpeechStartedOrder = 3)) }
        assertThat(fixture.adapter.execute(early, "confirm_voice_study_mutation", args).isError).isTrue()
        fixture.dialogueBoundaryPersisted = false
        val pending = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", args)
        assertThat(json(pending).path("error").path("code").asText()).isEqualTo("INPUT_PERSISTENCE_PENDING")
        fixture.dialogueBoundaryPersisted = true
        assertThat(fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", args).isError).isFalse()
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `native update carries frozen owner baseline into canonical CAS and never old classifier fields`() = runBlocking<Unit> {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts)
        fixture.handler = { name, args -> when (name) {
            "get_study" -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Selected root", "difficultyLevel" to 5))
            "update_study" -> {
                contexts.live[101] = VoiceTutorStudySnapshot(101, null, "Selected root", 7)
                assertThat(args).containsEntry(BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT, "Selected root")
                    .containsEntry(BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT, 5)
                success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Selected root", "difficultyLevel" to 7))
            }
            else -> error(name)
        } }
        val prepared = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", mapOf("action" to "update_study", "study_id" to 101L, "difficulty_level" to 7))
        fixture.learnerTurnId = 13; fixture.tutorTurnId = 12
        val result = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", mapOf("proposal_id" to json(prepared).path("proposal_id").asText(), "confirm" to true))
        assertThat(result.isError).isFalse()
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(fixture.calls.count { it.name == "update_study" }).isEqualTo(1)
    }

    @Test
    fun `native deletion binds complete saved subtree and rejects changed target before any write`() = runBlocking<Unit> {
        val fixture = Fixture()
        var changed = false
        fixture.handler = { name, _ -> when (name) {
            "get_study" -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to if (changed) "Renamed" else "Spring", "difficultyLevel" to 5))
            "list_studies" -> success(mapOf("studies" to emptyList<Any>(), "totalCount" to 0, "offset" to 0))
            "delete_study" -> success(mapOf("deleted" to true, "studyId" to 101L))
            else -> error(name)
        } }
        val prepared = fixture.adapter.execute(nativeContext(), "prepare_voice_study_mutation", mapOf("action" to "delete_study", "study_id" to 101L))
        fixture.learnerTurnId = 13; fixture.tutorTurnId = 12
        changed = true
        val result = fixture.adapter.execute(nativeContext(true), "confirm_voice_study_mutation", mapOf("proposal_id" to json(prepared).path("proposal_id").asText(), "confirm" to true))
        assertThat(json(result).path("error").path("code").asText()).isEqualTo("MUTATION_TARGET_STALE")
        assertThat(fixture.calls.none { it.name == "delete_study" }).isTrue()
    }

    @Test
    fun `native focus accepts same learner source epoch after current mutation epoch advances`() = runBlocking<Unit> {
        val contexts = ContextStore().apply { revision = 1 }
        val fixture = Fixture(studyContexts = contexts)
        fixture.tutorTurnId = 12 // A model preamble after USER 11 must not revoke that learner's tool request.
        fixture.focusResult = focusSelection(101, 2)
        fixture.handler = { _, _ -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Redis", "difficultyLevel" to 3, "curriculumTerminal" to true)) }
        val result = fixture.adapter.execute(nativeContext(currentRevision = 1), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isFalse()
        assertThat(fixture.focusSelections).containsExactly(101L)
        assertThat(fixture.lastExpectedCandidate).isEqualTo(VoiceTutorStudyTargetCandidate(101, null, "Redis", 3))
    }

    @Test
    fun `native focus returns committed revision before a separate pending lookup preserves the saved question`() = runBlocking<Unit> {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts).apply {
            focusResult = focusSelection(101, 1)
            afterFocus = { contexts.revision = 1 }
            handler = { name, args -> when (name) {
                "get_study" -> success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Redis", "difficultyLevel" to 3, "curriculumTerminal" to true))
                "list_pending_questions" -> {
                    assertThat(args["study_id"]).isEqualTo(101L)
                    success(mapOf("totalCount" to 1, "records" to listOf(mapOf(
                        "id" to "301", "studyId" to 101L, "topic" to "Redis", "difficulty" to 2,
                        "questionStatus" to "UNGRADED", "answer" to null,
                        "question" to mapOf("question" to "Redis가 무엇인가요?", "createdAt" to now.toString()),
                    ))))
                }
                else -> error("Unexpected tool $name")
            } }
        }

        val selected = fixture.adapter.execute(nativeContext(), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(selected.isError).isFalse()
        assertThat(selected.lessonRevision).isEqualTo(1)
        assertThat(selected.lessonFocus).isEqualTo(fixture.focusResult)
        assertThat(selected.questionChange).isNull()
        assertThat(selected.questionReadback).isNull()
        assertThat(selected.learningProgress).isNull()
        assertThat(json(selected).path("voiceQuestion")).isEqualTo(mapper.readTree("""{"lookupRequired":true}"""))
        assertThat(json(selected).path("notice").asText()).contains("selection is complete", "No selection operation is running")
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study")

        val pending = fixture.adapter.execute(nativeContext(currentRevision = selected.lessonRevision!!),
            "list_pending_questions", mapOf("study_id" to 101L))
        assertThat(pending.isError).isFalse()
        assertThat(pending.questionReadback?.question).isEqualTo("Redis가 무엇인가요?")
        assertThat(pending.questionChange?.recordId).isEqualTo("301")
        assertThat(json(pending).path("pendingQuestion").path("difficulty").asInt()).isEqualTo(2)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study", "get_study", "list_pending_questions")
        assertThat(fixture.focusSelections).containsExactly(101L)
    }

    @Test
    fun `native committed selection survives cancelled question lookup and lets a newer learner switch topics`() = runBlocking<Unit> {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts).apply { focusResult = focusSelection(101, 1) }
        fixture.afterFocus = { contexts.revision = fixture.focusResult!!.revision }
        val lookupCancelled = CancellationException("synthetic question lookup cancellation")
        fixture.handler = { name, args -> when (name) {
            "get_study" -> success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null,
                "topic" to "Redis", "difficultyLevel" to 3, "curriculumTerminal" to true))
            "list_pending_questions" -> throw lookupCancelled
            else -> error("Unexpected tool $name")
        } }

        val selected = fixture.adapter.execute(nativeContext(), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(selected.isError).isFalse()
        assertThat(selected.lessonRevision).isEqualTo(1)
        assertThat(fixture.persistedSession?.studyId).isEqualTo(101L)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study")
        val caught = try {
            fixture.adapter.execute(nativeContext(currentRevision = selected.lessonRevision!!),
                "list_pending_questions", mapOf("study_id" to 101L))
            null
        } catch (cancelled: CancellationException) { cancelled }
        assertThat(caught).isInstanceOf(CancellationException::class.java).hasMessage(lookupCancelled.message)

        fixture.learnerTurnId = 13
        fixture.acceptedProviderItemId = "new-topic-request"
        fixture.acceptedLessonRevision = 1
        fixture.focusResult = focusSelection(202, 2)
        val newer = nativeContext(currentRevision = selected.lessonRevision!!).let {
            it.copy(dialogueBoundary = it.dialogueBoundary!!.copy(
                responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 6,
                latestAcceptedLearnerProviderItemId = fixture.acceptedProviderItemId,
                latestAcceptedLearnerLessonRevision = fixture.acceptedLessonRevision,
            ))
        }
        val switched = fixture.adapter.execute(newer, "select_voice_study", mapOf("study_id" to 202L))
        assertThat(switched.isError).isFalse()
        assertThat(switched.lessonRevision).isEqualTo(2)
        assertThat(switched.lessonFocus?.studyId).isEqualTo(202L)
        assertThat(fixture.persistedSession?.studyId).isEqualTo(202L)
        assertThat(fixture.focusSelections).containsExactly(101L, 202L)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study", "get_study", "list_pending_questions", "get_study", "get_study")
    }

    @Test
    fun `native child advance completes its committed focus without starting question work`() = runBlocking<Unit> {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts).apply {
            focusResult = VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(102, 1),
                VoiceTutorStudySnapshot(102, 101, "Redis Streams", 8, 1))
            afterFocus = { contexts.revision = 1 }
            handler = { name, args ->
                assertThat(name).isEqualTo("get_study")
                if ((args["study_id"] as Number).toLong() == 101L)
                    success(mapOf("id" to 101L, "parentStudyId" to null, "topic" to "Redis", "difficultyLevel" to 3))
                else success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Redis Streams", "difficultyLevel" to 8, "curriculumTerminal" to true))
            }
        }
        val result = fixture.adapter.execute(nativeContext(), "advance_voice_study", mapOf("study_id" to 102L))
        assertThat(result.isError).isFalse()
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus).isEqualTo(fixture.focusResult)
        assertThat(result.questionReadback).isNull()
        assertThat(result.learningProgress).isNull()
        assertThat(json(result).path("voiceQuestion").path("lookupRequired").asBoolean()).isTrue()
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study", "get_study")
        assertThat(fixture.focusSelections).containsExactly(102L)
    }

    private class Fixture(
        actualMcp: BuddyStudyMcpPort? = null,
        studyContexts: VoiceTutorStudyContextPort = UnavailableVoiceTutorStudyContextPort,
    ) {
        var authorized = true
        var persistedSession: VoiceTutorSession? = session()
        var authorizationCalls = 0
        var persistenceCalls = 0
        var catalogReads = 0
        var learnerTurnId: Long? = 11
        var tutorTurnId: Long? = 10
        var dialogueBoundaryPersisted = true
        var completedExchange = true
        var duringLearnerAuthorization: () -> Unit = {}
        var acceptedProviderItemId = "accepted-user-item"
        var acceptedLessonRevision = 0L
        var expectedFocusCurrentRevision: Long? = null
        var lastFocusLearnerTurnId = 0L
        var focusResult: VoiceTutorLessonFocusSelection? = null
        var afterFocus: () -> Unit = {}
        var lastExpectedCandidate: VoiceTutorStudyTargetCandidate? = null
        var lastExpectedTraversal: VoiceTutorStudyTargetTraversal? = null
        val focusSelections = mutableListOf<Long>()
        val focusHistory = mutableListOf<VoiceTutorLessonFocus>()
        val calls = mutableListOf<Call>()
        var handler: (String, Map<String, Any>) -> McpSchema.CallToolResult = { _, _ -> success(mapOf("ok" to true)) }
        val catalog = BuddyStudyMcpAdapter(proxy<BuddyStudyMcpUseCase> { method, _ -> error("Unexpected direct call: $method") }, mapper).tools()
        private val mcp = actualMcp ?: object : BuddyStudyMcpPort {
            override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> {
                catalogReads += 1
                return catalog.map { specification ->
                    McpStatelessServerFeatures.AsyncToolSpecification(specification.tool()) { context, request ->
                        Mono.fromCallable {
                            val caller = context.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal
                            val arguments = request.arguments().orEmpty()
                            calls += Call(request.name(), arguments, caller,
                                context.get(BuddyStudyMcpPort.SUPPRESS_EXCHANGE_LOG_CONTEXT_KEY) == true)
                            handler(request.name(), arguments)
                        }
                    }
                }
            }

            override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = emptyList()
        }
        val adapter = McpVoiceTutorToolAdapter(
            mcp = mcp,
            authorization = object : VoiceTutorRelayAuthorizationPort {
                override suspend fun isAuthorized(userId: Long, deviceId: String, authSessionId: Long, now: Instant): Boolean {
                    authorizationCalls += 1
                    assertThat(userId).isEqualTo(principal.userId)
                    assertThat(deviceId).isEqualTo(principal.deviceId)
                    assertThat(authSessionId).isEqualTo(principal.sessionId)
                    return authorized
                }
            },
            persistence = proxy<VoiceTutorPersistencePort> { method, arguments ->
                assertThat(method).isEqualTo("findSession")
                assertThat(arguments[0]).isEqualTo(principal.userId)
                assertThat(arguments[1]).isEqualTo(session().id)
                persistenceCalls += 1
                persistedSession
            },
            objectMapper = mapper,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            studyContexts = studyContexts,
            confirmations = object : VoiceTutorMutationConfirmationPort {
                override suspend fun latestLearnerTurnId(userId: Long, sessionId: String) = learnerTurnId
                override suspend fun latestTutorTurnId(userId: Long, sessionId: String) = tutorTurnId
                override suspend fun persistedLearnerTurnId(userId: Long, sessionId: String, providerItemId: String,
                    lessonRevision: Long): Long? = learnerTurnId?.takeIf {
                    providerItemId == acceptedProviderItemId && lessonRevision == acceptedLessonRevision
                }?.also { duringLearnerAuthorization() }
                override suspend fun persistedDialogueBoundary(userId: Long, sessionId: String, learnerProviderItemId: String,
                    tutorProviderItemId: String, lessonRevision: Long): VoiceTutorPersistedDialogueBoundary? =
                    if (dialogueBoundaryPersisted && learnerProviderItemId == acceptedProviderItemId &&
                        tutorProviderItemId == "native-confirmation-question" && lessonRevision == acceptedLessonRevision &&
                        learnerTurnId != null && tutorTurnId != null) VoiceTutorPersistedDialogueBoundary(learnerTurnId!!, tutorTurnId!!) else null
                override suspend fun learnerTurnAuthorization(
                    userId: Long,
                    sessionId: String,
                    providerItemId: String,
                    lessonRevision: Long,
                    expectedQuestionProviderItemId: String?,
                    expectedAnswerProviderItemId: String?,
                    expectedTutorFeedbackProviderItemId: String?,
                    expectedTutorNavigationOfferProviderItemId: String?,
                ): VoiceTutorLearnerTurnAuthorization? = learnerTurnId?.takeIf {
                    providerItemId == acceptedProviderItemId && lessonRevision == acceptedLessonRevision
                }?.let {
                    duringLearnerAuthorization()
                    VoiceTutorLearnerTurnAuthorization(it, completedExchange)
                }
            },
            lessonFocus = object : VoiceTutorLessonFocusPort {
                override suspend fun focusFromRealtimeModel(userId: Long, sessionId: String, studyId: Long,
                    learnerTurnId: Long, expectedCurrentRevision: Long, expectedCandidate: VoiceTutorStudyTargetCandidate,
                    commitAuthority: VoiceTutorFocusCommitAuthority, expectedParentStudyId: Long?): VoiceTutorLessonFocusSelection? {
                    assertThat(learnerTurnId).isEqualTo(this@Fixture.learnerTurnId)
                    lastExpectedCandidate = expectedCandidate
                    return selectFocus(userId, sessionId, studyId)
                }
                override suspend fun history(userId: Long, sessionId: String) = focusHistory.toList()
                override suspend fun focus(
                    userId: Long,
                    sessionId: String,
                    studyId: Long,
                    learnerTurnId: Long?,
                    expectedCurrentRevision: Long?,
                    authorization: VoiceTutorFocusAuthorization?,
                    expectedCandidate: VoiceTutorStudyTargetCandidate?,
                    commitAuthority: VoiceTutorFocusCommitAuthority?,
                    expectedTraversal: VoiceTutorStudyTargetTraversal?,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(learnerTurnId).isEqualTo(this@Fixture.learnerTurnId)
                    assertThat(expectedCurrentRevision)
                        .isEqualTo(expectedFocusCurrentRevision ?: acceptedLessonRevision)
                    assertThat(commitAuthority).isEqualTo(VoiceTutorFocusCommitAuthority(
                        principal.deviceId, principal.sessionId, session().providerSessionId!!,
                    ))
                    lastExpectedCandidate = expectedCandidate
                    lastExpectedTraversal = expectedTraversal
                    if (authorization?.consume() != true) return null
                    val selected = selectFocus(userId, sessionId, studyId)
                    if (selected != null && learnerTurnId != null) lastFocusLearnerTurnId = learnerTurnId
                    return selected
                }

                override suspend fun advance(
                    userId: Long,
                    sessionId: String,
                    currentStudyId: Long,
                    childStudyId: Long,
                    learnerTurnId: Long,
                    expectedCurrentRevision: Long?,
                    authorization: VoiceTutorFocusAuthorization?,
                    expectedCandidate: VoiceTutorStudyTargetCandidate?,
                    commitAuthority: VoiceTutorFocusCommitAuthority?,
                    expectedTraversal: VoiceTutorStudyTargetTraversal?,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(persistedSession?.studyId).isEqualTo(currentStudyId)
                    assertThat(learnerTurnId).isEqualTo(this@Fixture.learnerTurnId)
                    assertThat(expectedCurrentRevision).isEqualTo(acceptedLessonRevision)
                    assertThat(commitAuthority).isEqualTo(VoiceTutorFocusCommitAuthority(
                        principal.deviceId, principal.sessionId, session().providerSessionId!!,
                    ))
                    lastExpectedCandidate = expectedCandidate
                    lastExpectedTraversal = expectedTraversal
                    if (authorization?.consume() != true) return null
                    if (learnerTurnId <= lastFocusLearnerTurnId) return null
                    val selected = selectFocus(userId, sessionId, childStudyId)
                    if (selected != null) lastFocusLearnerTurnId = learnerTurnId
                    return selected
                }

                private fun selectFocus(
                    userId: Long,
                    sessionId: String,
                    studyId: Long,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(userId).isEqualTo(principal.userId)
                    assertThat(sessionId).isEqualTo(session().id)
                    focusSelections += studyId
                    val value = focusResult ?: return null
                    focusHistory += value.focus
                    persistedSession = persistedSession?.copy(studyId = value.studyId, topic = value.snapshot.topic, difficulty = value.snapshot.difficulty)
                    afterFocus()
                    return value
                }
            },
        )
    }

    private class ContextStore : VoiceTutorStudyContextPort {
        val remembered = mutableListOf<List<Long>>()
        val saved = linkedMapOf(101L to VoiceTutorStudySnapshot(101, null, "Selected root", 5))
        val live = linkedMapOf<Long, VoiceTutorStudySnapshot>()
        var fail = false
        var failList = false
        var listReads = 0
        var afterList: () -> Unit = {}
        var revision = 0L
        var failRevision = false
        val revised = mutableListOf<Long>()
        override suspend fun currentRevision(userId: Long, sessionId: String) = revision
        override suspend fun revise(userId: Long, sessionId: String, studyId: Long): List<VoiceTutorStudySnapshot> {
            if (failRevision) throw IllegalStateException("private-revision-detail")
            revised += studyId
            val next = (live[studyId] ?: saved.getValue(studyId)).copy(revision = ++revision)
            saved[studyId] = next
            return listOf(next)
        }
        override suspend fun prepare(session: VoiceTutorSession) = emptyList<VoiceTutorStudySnapshot>()
        override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> {
            assertThat(userId).isEqualTo(principal.userId)
            assertThat(sessionId).isEqualTo(session().id)
            listReads += 1
            if (failList) throw IllegalStateException("private-database-detail")
            afterList()
            return saved.values.toList()
        }
        override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot> {
            assertThat(userId).isEqualTo(principal.userId)
            assertThat(sessionId).isEqualTo(session().id)
            remembered += studyIds
            if (fail) throw IllegalStateException("private-database-detail")
            return studyIds.map { id ->
                saved.getOrPut(id) { live[id] ?: VoiceTutorStudySnapshot(id, 101, "Cache", 3) }
            }
        }
    }

    private data class Call(
        val name: String,
        val arguments: Map<String, Any>,
        val principal: Principal?,
        val exchangeLoggingSuppressed: Boolean = false,
    )

    private companion object {
        suspend fun captureExchanges(action: suspend () -> Unit): List<JsonNode> {
            val logger = LoggerFactory.getLogger(McpExchangeLogger::class.java) as Logger
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply {
                context = logger.loggerContext
                start()
            }
            logger.addAppender(appender)
            logger.level = Level.INFO
            try {
                action()
                return appender.list.map { it.formattedMessage }
                    .filter { it.startsWith("mcp_exchange ") }
                    .map { mapper.readTree(it.removePrefix("mcp_exchange ")) }
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        fun exchangeBody(exchange: JsonNode, field: String): JsonNode = exchange.path(field).let {
            if (it.isTextual) mapper.readTree(it.asText()) else it
        }

        fun nativeContext(confirming: Boolean = false, currentRevision: Long = 0) = VoiceTutorWebRtcControlContext(
            session(), "rtc_synthetic_call", principal, initialLessonRevision = currentRevision, realtimeModelTools = true,
            dialogueBoundary = VoiceTutorDialogueBoundary(responseGeneration = if (confirming) 4 else 2,
                latestAcceptedLearnerSpeechStartedOrder = if (confirming) 6 else 3,
                precedingTutorSpeechStoppedOrder = if (confirming) 5 else 2,
                precedingSpokenResponseGeneration = if (confirming) 3 else 1,
                precedingTutorProviderItemId = "native-confirmation-question",
                latestAcceptedLearnerProviderItemId = "accepted-user-item", latestAcceptedLearnerLessonRevision = 0),
        )
        fun candidate(id: Long, parentStudyId: Long?): Map<String, Any?> = mapOf(
            "id" to id,
            "parentStudyId" to parentStudyId,
            "topic" to "Topic $id",
        )

        fun completePage(
            studies: List<Map<String, Any?>>,
            totalCount: Int,
            limit: Int,
            offset: Int = 0,
        ): Map<String, Any?> = mapOf(
            "studies" to studies,
            "totalCount" to totalCount,
            "limit" to limit,
            "offset" to offset,
        )

        fun learningRecordPayload(studyId: Long): Map<String, Any?> = linkedMapOf(
            "id" to "91", "sessionId" to "synthetic-prior-session", "studyId" to studyId,
            "parentStudyId" to 101L, "topic" to "Redis", "difficulty" to 3, "kind" to "TUTOR_QUESTION",
            "question" to "어떤 키를 제거하나요?", "answer" to "  private-prior-answer\n", "score" to 85,
            "feedback" to "85점입니다.", "strengths" to listOf("최근 사용 시점을 짚음"), "improvements" to emptyList<String>(),
            "depthSummary" to "LRU를 살펴봄", "questionTurnId" to 11L,
            "answerTurnIds" to listOf(12L), "feedbackTurnIds" to listOf(13L),
            "sourceLanguage" to "ko", "requestedLanguage" to "ko", "displayLanguage" to "ko", "translationPending" to false,
        )

        val now: Instant = Instant.parse("2026-08-31T00:00:00Z")
        val principal = Principal(userId = 7L, deviceId = "synthetic-device", sessionId = 11L, anonymous = false)
        val mapper = jacksonObjectMapper().findAndRegisterModules()

        fun discoverySession() = session().copy(studyId = null, acceptedStudyId = null, topic = "", difficulty = 0)
        fun focusSelection(id: Long, revision: Long) = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(id, revision), VoiceTutorStudySnapshot(id, null, "Redis", 3, revision),
        )

        fun context(
            inputIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
            lessonRevision: Long = 0,
            providerItemId: String? = "accepted-user-item",
            targetStudyId: Long? = when (inputIntent) {
                VoiceTutorInputIntent.SELECT_SAVED_TOPIC -> 101L
                VoiceTutorInputIntent.CONTINUE_TREE -> 102L
                else -> null
            },
            targetParentStudyId: Long? = 101L.takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
            targetTopic: String = if (inputIntent == VoiceTutorInputIntent.CONTINUE_TREE) "Cache" else "Redis",
            targetDifficulty: Int? = null,
            targetTraversal: VoiceTutorStudyTargetTraversal? = targetStudyId?.let {
                VoiceTutorStudyTargetTraversal()
            },
            rootTopic: String = "운영체제",
            rootDifficulty: Int = 6,
            rootStartLessonAfterCreate: Boolean = false,
        ) = VoiceTutorWebRtcControlContext(
            session(), "rtc_synthetic_call", principal,
            dialogueBoundary = VoiceTutorDialogueBoundary(
                responseGeneration = 2, latestAcceptedLearnerSpeechStartedOrder = 3,
                precedingTutorSpeechStoppedOrder = 2, precedingSpokenResponseGeneration = 1,
                latestAcceptedLearnerProviderItemId = providerItemId,
                latestAcceptedLearnerLessonRevision = lessonRevision,
                latestAcceptedLearnerIntent = inputIntent,
                precedingTutorFeedbackForStudyAnswer = inputIntent == VoiceTutorInputIntent.CONTINUE_TREE,
                precedingQuestionProviderItemId = "accepted-question-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingAnswerProviderItemId = "accepted-answer-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingTutorFeedbackProviderItemId = "accepted-feedback-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingTutorNavigationOfferProviderItemId = "accepted-offer-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                latestAcceptedLearnerTargetStudyId = targetStudyId,
                latestAcceptedLearnerTargetOfferId = targetStudyId?.let { 1L },
                latestAcceptedLearnerTargetCandidate = targetStudyId?.let { id ->
                    VoiceTutorStudyTargetCandidate(
                        studyId = id,
                        parentStudyId = targetParentStudyId,
                        topic = targetTopic,
                        difficulty = targetDifficulty,
                    )
                },
                latestAcceptedLearnerTargetTraversal = targetTraversal,
                focusAuthorization = targetStudyId?.let { VoiceTutorFocusAuthorization() },
                rootStudyCreationAuthorization = if (inputIntent == VoiceTutorInputIntent.CREATE_ROOT_STUDY) {
                    VoiceTutorRootStudyCreationAuthorization(
                        rootTopic,
                        rootDifficulty,
                        startLessonAfterCreate = rootStartLessonAfterCreate,
                    ).also {
                        check(it.bindToServerCall("direct-adapter-root-create"))
                        check(it.claimExecution("direct-adapter-root-create"))
                    }
                } else {
                    null
                },
            ),
        )

        fun childContext(
            parentStudyId: Long,
            topic: String,
            difficulty: Int = 5,
            lessonRevision: Long = 0,
        ): VoiceTutorWebRtcControlContext {
            val base = context(
                inputIntent = VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
                lessonRevision = lessonRevision,
            )
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                childStudyCreationAuthorization = VoiceTutorChildStudyCreationAuthorization(
                    parentStudyId,
                    topic,
                    difficulty,
                ).also {
                    check(it.bindToServerCall("direct-adapter-child-create"))
                    check(it.claimExecution("direct-adapter-child-create"))
                },
            ))
        }

        fun deletionContext(
            studyId: Long,
            lessonRevision: Long = 0,
            claimForExecution: Boolean = true,
            targetProof: VoiceTutorStudyUpdateTargetProof = VoiceTutorStudyUpdateTargetProof(
                studyId,
                101L.takeIf { studyId != 101L },
                if (studyId == 101L) "Selected root" else "Cache",
                if (studyId == 101L) 5 else 3,
            ),
        ): VoiceTutorWebRtcControlContext {
            val base = context(inputIntent = VoiceTutorInputIntent.DELETE_STUDY, lessonRevision = lessonRevision)
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                studyDeletionAuthorization = VoiceTutorStudyDeletionAuthorization(targetProof).also {
                    check(it.bindToServerCall("direct-adapter-study-delete"))
                    if (claimForExecution) check(it.claimExecution("direct-adapter-study-delete"))
                },
            ))
        }

        fun updateContext(
            studyId: Long,
            topic: String? = null,
            difficulty: Int? = null,
            lessonRevision: Long = 0,
            scope: VoiceTutorStudyUpdateAuthorizationScope =
                VoiceTutorStudyUpdateAuthorizationScope.CONFIRMED_FOCUS_TREE,
            targetProof: VoiceTutorStudyUpdateTargetProof = VoiceTutorStudyUpdateTargetProof(
                studyId = studyId,
                parentStudyId = 101L.takeIf { studyId != 101L },
                topic = if (studyId == 101L) "Selected root" else "Cache",
                difficulty = if (studyId == 101L) 5 else 3,
            ),
            startLessonAfterUpdate: Boolean = false,
        ): VoiceTutorWebRtcControlContext {
            val base = context(
                inputIntent = VoiceTutorInputIntent.UPDATE_STUDY,
                lessonRevision = lessonRevision,
            )
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                studyUpdateAuthorization = VoiceTutorStudyUpdateAuthorization(
                    studyId,
                    topic,
                    difficulty,
                    scope,
                    targetProof,
                    startLessonAfterUpdate,
                )
                    .also {
                        check(it.bindToServerCall("direct-adapter-study-update"))
                        check(it.claimExecution("direct-adapter-study-update"))
                    },
            ))
        }

        fun session() = VoiceTutorSession(
            id = "00000000-0000-0000-0000-000000000007", userId = principal.userId, studyId = 101L,
            idempotencyKey = "synthetic-call", providerSessionId = "rtc_synthetic_call",
            status = VoiceTutorSessionStatus.ACTIVE, resultStatus = VoiceTutorResultStatus.PENDING,
            language = "ko", model = "gpt-realtime", voice = "marin", topic = "Redis", difficulty = 5,
            periodStartedAt = now.minusSeconds(60), periodEndsAt = now.plusSeconds(86_400),
            reservedSeconds = 3_600, chargedSeconds = 0, maxSessionSeconds = 3_600,
            hardEndsAt = now.plusSeconds(3_600), connectedAt = now.minusSeconds(5), relayHeartbeatAt = now,
            acceptedAudioBytes = 0, endedAt = null, finalizedAt = null, endReason = null,
            failureCode = null, failureMessage = null, createdAt = now.minusSeconds(10), updatedAt = now,
        )

        fun room(id: Long) = StudyRoomResponse(
            id = id, parentStudyId = null, sortOrder = 0, topic = "Redis", difficultyLevel = 5,
            intervalMinutes = 30, enabled = false, activeForQuestions = true, notificationSound = null,
            customPrompt = "", openaiModel = "gpt-5.4", maxHistoryCount = 100,
            nextDueAt = null, lastSentAt = null, lastError = null, pendingQuestion = null,
            createdAt = now, updatedAt = now,
        )

        fun success(payload: Any): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
            .structuredContent(payload).isError(false).build()

        fun failure(code: String): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
            .structuredContent(mapOf("error" to mapOf("code" to code))).isError(true).build()

        fun json(result: VoiceTutorMcpToolResult): JsonNode = mapper.readTree(result.output)

        fun assertCode(result: VoiceTutorMcpToolResult, code: String) {
            assertThat(result.isError).isTrue()
            assertThat(json(result).path("error").path("code").asText()).isEqualTo(code)
        }

        inline fun <reified T> proxy(noinline handler: (String, List<Any?>) -> Any?): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "Synthetic${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> handler(method.name, arguments?.toList().orEmpty())
            }
        } as T
    }
}
