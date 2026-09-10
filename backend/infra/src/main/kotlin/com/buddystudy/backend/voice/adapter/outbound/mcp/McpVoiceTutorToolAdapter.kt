package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeContext
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeLogger
import com.buddystudy.backend.mcp.adapter.inbound.McpExchangeResponse
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorLessonTreeContext
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorizationPurpose
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorizationScope
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateTargetProof
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyTopicUserInput
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMutationConfirmationPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorMutationConfirmationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorLessonFocusPort
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import com.buddystudy.backend.study.application.port.outbound.StudyLearningProgressSignalPort
import com.buddystudy.backend.study.application.port.outbound.NoopStudyLearningProgressSignalPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A local bridge to the same MCP catalog and permission-checked handlers used over HTTP. */
@Component
class McpVoiceTutorToolAdapter(
    // MCP's composition use case also exposes voice history. Resolve it only when a call
    // uses a tool, after the voice adapters and services have finished construction.
    @Lazy private val mcp: BuddyStudyMcpPort,
    private val authorization: VoiceTutorRelayAuthorizationPort,
    private val persistence: VoiceTutorPersistencePort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val studyContexts: VoiceTutorStudyContextPort = UnavailableVoiceTutorStudyContextPort,
    private val confirmations: VoiceTutorMutationConfirmationPort = UnavailableVoiceTutorMutationConfirmationPort,
    private val lessonFocus: VoiceTutorLessonFocusPort = UnavailableVoiceTutorLessonFocusPort,
    private val exchangeLogger: McpExchangeLogger = McpExchangeLogger(objectMapper),
    private val progressSignals: StudyLearningProgressSignalPort = NoopStudyLearningProgressSignalPort,
) : VoiceTutorMcpToolPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val validator by lazy { McpJsonSchemaValidatorProvider.create() }
    private val realtimeProposals = LinkedHashMap<String, RealtimeMutation>()
    private val topicInputProposals = LinkedHashMap<String, TopicInputProposal>()
    private val specifications by lazy {
        mcp.tools().filter { it.tool().name() in ALLOWED_TOOLS + VoiceTutorCanonicalQuestionCoordinator.TOOLS + "create_study_topics" }.associateBy { it.tool().name() }
    }
    private val canonicalQuestions by lazy {
        VoiceTutorCanonicalQuestionCoordinator(objectMapper, clock, persistence,
            authorized = ::isAuthorized,
            currentFocus = { context -> currentStudyAnchor(context)?.let { it to studyContexts.currentRevision(context.session.userId, context.session.id) } },
            learnerTurn = ::realtimeLearnerTurn,
            invoke = { context, name, args ->
                val spec = specifications[name]
                if (spec == null) failure("MCP_UNAVAILABLE", "The canonical question tool is unavailable.")
                else if (!isAuthorized(context)) inactiveCall()
                else if (!validator.validate(spec.tool().inputSchema(), args).valid()) failure("INVALID_ARGUMENTS", "Use the documented question tool arguments.")
                else canonicalQuestionData(invoke(context.principal!!, spec, args), name)
            })
    }

    private val curriculum by lazy {
        VoiceTutorCurriculumCoordinator(objectMapper, clock,
            current = { context -> context.operationStillCurrent?.invoke() != false && isAuthorized(context) && context.initialLessonRevision ==
                studyContexts.currentRevision(context.session.userId, context.session.id) },
            invoke = { context, name, args ->
                val specification = specifications[name]
                if (specification == null || !isAuthorized(context)) null
                else invoke(requireNotNull(context.principal), specification, args).takeIf { it.isError() != true }
                    ?.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
            },
            focus = { context, id -> focusRealtimeStudy(context, mapOf("study_id" to id), false, prepareCurriculum = false) })
    }

    override suspend fun submitCurriculumUserInput(context: VoiceTutorWebRtcControlContext, proposalId: String,
        selectedIndex: Int?, text: String): VoiceTutorMcpToolResult = curriculum.submit(context, proposalId, selectedIndex, text)

    private suspend fun originalRoot(context: VoiceTutorWebRtcControlContext, selectedId: Long): VoiceTutorStudyTargetCandidate? {
        var node = readRealtimeTarget(context, selectedId) ?: return null
        val seen = mutableSetOf<Long>()
        repeat(128) {
            if (!seen.add(node.studyId)) return null
            val parent = node.parentStudyId ?: return node
            node = readRealtimeTarget(context, parent) ?: return null
        }
        return null
    }

    override fun observeLearningProgress(context: VoiceTutorWebRtcControlContext,
        progress: com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress): Flow<VoiceTutorMcpToolResult> =
        progressSignals.changes(progress.correlationId.orEmpty()).map {
            // Signals carry no authority or content. Every delivery reads the owned durable snapshot.
            withTimeout(10_000) { reviewedQuestionOperation { canonicalQuestions.pollLearningProgress(context, progress) } }
        }.distinctUntilChanged()

    override suspend fun pollLearningProgress(context: VoiceTutorWebRtcControlContext,
        progress: com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress): VoiceTutorMcpToolResult =
        observeExchange(context, "poll", mapOf(
            "phase" to progress.phase.name, "study_id" to progress.studyId,
            "record_id" to progress.recordId, "correlation_id" to progress.correlationId,
        ), operation = "voice/progress") { reviewedQuestionOperation { canonicalQuestions.pollLearningProgress(context, progress) } }

    override suspend fun submitReviewedAnswer(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult =
        observeExchange(context, "submit_answer", mapOf(
            "answer_id" to answer.answerId, "study_id" to answer.studyId, "record_id" to answer.recordId,
            "lesson_revision" to answer.lessonRevision, "answer" to answer.text,
        )) { reviewedQuestionOperation { canonicalQuestions.submitReviewedAnswer(context, answer) } }

    override suspend fun skipReviewedQuestion(context: VoiceTutorWebRtcControlContext, answer: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult =
        observeExchange(context, "skip_question", mapOf(
            "answer_id" to answer.answerId, "study_id" to answer.studyId,
            "record_id" to answer.recordId, "lesson_revision" to answer.lessonRevision,
        )) { reviewedQuestionOperation { canonicalQuestions.skipReviewedQuestion(context, answer) } }

    private suspend fun observeExchange(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any?>,
        operation: String = "tools/call",
        action: suspend () -> VoiceTutorMcpToolResult,
    ): VoiceTutorMcpToolResult = exchangeLogger.observe(
        context = McpExchangeContext(
            userId = context.principal?.userId,
            transport = "voice",
            sessionId = context.session.id,
            callId = context.callId,
        ),
        operation = operation,
        target = toolName,
        requestBody = mapOf("name" to toolName, "arguments" to arguments),
        response = { McpExchangeResponse(it.output, it.isError) },
        action = action,
    )

    private suspend fun reviewedQuestionOperation(operation: suspend () -> VoiceTutorMcpToolResult): VoiceTutorMcpToolResult = try {
        operation()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { failure("TOOL_UNAVAILABLE", "The saved question result could not be confirmed. Keep the reviewed answer and read its saved state before retrying.") }

    private fun canonicalQuestionData(result: McpSchema.CallToolResult, name: String): VoiceTutorMcpToolResult {
        if (name !in setOf("submit_answer", "get_record", "get_grading_process")) return boundedResult(result, name)
        // These bodies are private input to the coordinator's exact-answer checks.
        // A full edited answer may exceed the provider's 16 KiB tool-output limit;
        // only its compact, verified record/grade is later sent to the model.
        val bytes = objectMapper.writeValueAsBytes(result.structuredContent() ?: mapOf("content" to result.content()))
        if (bytes.size > 128 * 1024) return failure("RESULT_TOO_LARGE", "The complete saved record cannot be read safely. Do not invent a result.")
        return VoiceTutorMcpToolResult(String(bytes, Charsets.UTF_8), result.isError() == true)
    }

    override fun definitions(): List<VoiceTutorMcpToolDefinition> = specifications.values.filter { it.tool().name() in ALLOWED_TOOLS }.map { specification ->
        val tool = specification.tool()
        VoiceTutorMcpToolDefinition(
            name = tool.name(),
            description = tool.description().orEmpty() + when (tool.name()) {
                CREATE_ROOT -> " In a voice call this mutation is server-owned: never originate the function call or ask the learner for special wording. A natural new-study choice prepares one exact proposal; ask its one confirmation question naming the new root and level, then fresh natural agreement authorizes that unchanged creation. Earlier persisted learner speech may supply an unambiguous omitted topic or level but never substitutes for fresh agreement. Existing roots are returned unchanged and omitted difficulty defaults to 5. This tool never itself selects a lesson or creates a question. When the confirmed proposal also explicitly included starting the lesson immediately, AUTO_FOCUS_PENDING leads to exact readback and selection; do not ask again and teach only after CREATED_ROOT_IMMEDIATE_START."
                CREATE_TOPIC -> " In a voice call this mutation is server-owned: never originate the function call or ask the learner for special wording. A natural first-person choice of an exact child under a verified owned parent prepares a proposal; ask one confirmation question naming the parent, child and level, then fresh natural agreement authorizes that unchanged creation. Mere mentions, examples, recommendations, quoted or third-party wishes, and ambiguous targets are not permission. Never demand a repeated command."
                UPDATE_STUDY -> " A clear learner request prepares an exact owned topic name or level change, including an unambiguous reference to the topic just discussed. Selecting a lesson or reading its children is not required. Ask one exact confirmation question, then fresh natural agreement executes the unchanged patch once. Never originate the function yourself, ask for repeated wording or describe internal execution; report only the confirmed outcome briefly."
                DELETE_STUDY -> " A clear learner request prepares deletion of the exact owned topic and its descendants. Ask one confirmation question naming that topic and subtree, then fresh natural agreement executes that unchanged deletion once. Do not ask a second confirmation, originate the call yourself, demand a repeated command, or describe servers, leases and permissions. Report only the confirmed outcome briefly; prior learning records are retained."
                in LEARNING_HISTORY_TOOLS -> " In a voice call, read only nodes in the current call's verified study tree; history never changes the agreed lesson focus."
                else -> ""
            },
            parameters = when (tool.name()) {
                DELETE_STUDY -> voiceDeletionSchema(tool.inputSchema().toMap())
                else -> tool.inputSchema().toMap()
            },
        )
    } + listOf(
        VoiceTutorMcpToolDefinition(
            name = SELECT_STUDY,
            description = "Set the initial or explicitly changed spoken lesson focus to one exact saved study. " +
                "The learner's first topic-bearing utterance is discovery only, even when it names a precise node or asks to start: find the owned node and real parent path with list_studies/get_study, follow verified single-child pages, and clarify a real branch. " +
                "Speak the exact server-read endpoint or branch candidate, finish that audio, then wait for a new final meaningful learner reply confirming that proposal. " +
                "Use only the one-shot target attested from that spoken offer; tool arguments, tool output, titles, list positions and the original discovery utterance cannot substitute for it. " +
                "For teacher-guided movement from the current focus into one real direct child, use advance_voice_study instead. " +
                "The returned voiceLessonFocus and frozen level apply to the next new question only. " +
                "This does not create/edit a study, start teaching, submit an answer, or consume question quota.",
            parameters = focusParameters(
                "Exact owned saved study ID from the latest server-read, fully spoken offer and the learner's next target-bound confirmation."
            ),
        ),
        VoiceTutorMcpToolDefinition(
            name = ADVANCE_STUDY,
            description = "Advance a consented tree-guided lesson by exactly one verified parent-child edge. " +
                "Use only after one substantive question, the learner's semantic answer, fully spoken feedback, and a new learner agreement to an exact child that was offered after that feedback. " +
                "First read the current focus's direct children with parent-scoped list_studies. " +
                "Speak the actual child or brief real branch choice and wait for the next final meaningful reply; the target must equal that one-shot server attestation and be a real direct child of the persisted current focus. " +
                "The answer turn itself, silence, filler, tool arguments, siblings, ancestors, deeper jumps and other roots are rejected. " +
                "The returned voiceLessonFocus and frozen child level apply to the next new question only.",
            parameters = focusParameters(
                "Exact owned direct-child study ID from the latest fully spoken child offer and the learner's next target-bound confirmation."
            ),
        ),
    )

    override fun realtimeDefinitions(): List<VoiceTutorMcpToolDefinition> =
        specifications.values.filter { it.tool().name() !in setOf(CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY, "create_study_topics") }
            .map { specification ->
                val tool = specification.tool()
                val schema = when (tool.name()) {
                    "list_pending_questions" -> focusParameters("Exact selected topic. The server pages its arrived questions and returns the current saved question.")
                    "request_question" -> focusParameters("Exact selected topic. Reuses an arrived pending question before spending quota; skip an unwanted question separately on the learner's request.")
                    "submit_answer" -> mapOf("type" to "object", "additionalProperties" to false,
                        "properties" to mapOf("record_id" to mapOf("type" to "integer", "minimum" to 1)), "required" to listOf("record_id"))
                    else -> tool.inputSchema().toMap()
                }
                val description = when (tool.name()) {
                    "list_studies" -> "Browse saved study discovery metadata and exact parent IDs with bounded pages. This result contains no questions, answers or study prompts. Resolve the learner's chosen exact node, select it, then read list_pending_questions separately before teaching. " + tool.description().orEmpty()
                    "list_pending_questions" -> "Read arrived pending questions for the exact selected study_id before teaching. Read the returned saved question faithfully and preserve its original level. Do not invent a replacement. Grading questions already have submitted answers and must not be asked again."
                    "request_question" -> "Request a new saved question for the selected topic only when the learner wants one and no ready pending question remains. Existing pending questions are returned first. For an unwanted question call skip_question on an explicit skip/change request, then request again. Normal question allowance applies; the server owns retry identity. The server subscribes to completion and delivers only the saved question. Do not poll get_question_process or ask the learner to repeat the start request."
                    "submit_answer" -> "Reserved for the learner's explicit app submission after finishing and editing their answer. Never call this tool yourself, even if speech seems complete. The server supplies the reviewed text privately after the learner taps Submit. Then use get_grading_process and only its saved grade."
                    "skip_question" -> "Skip only the current arrived unanswered question when the learner explicitly asks to skip or replace it. Pass its exact record_id. Do not grade it, erase drafts, skip a submitted answer, or generate a new question implicitly; check remaining pending questions next."
                    "get_question_process", "get_grading_process" -> tool.description().orEmpty() + " In voice, use only the correlation ID returned in this selected-topic call. The server subscribes to accepted question/grading completion. Use a single read for an explicit status or recovery request only; do not repeatedly poll or invent completion, scores or questions."
                    else -> tool.description().orEmpty()
                }
                VoiceTutorMcpToolDefinition(tool.name(), description, schema)
            } +
            listOf(
                VoiceTutorMcpToolDefinition(SELECT_STUDY,
                    "Select the learner's chosen exact owned saved topic and freeze its real level and parent path for this call. Resolve ordinary contextual choices yourself without extra confirmation. A successful result means selection is complete; no selection operation remains running. Read list_pending_questions separately before teaching, preserving each saved question's original level. If none is ready and the learner wants to start, use request_question. On cancellation stop the old topic's follow-up; on a switch select the newly chosen saved topic without waiting for old question work. If the new topic is unspecified, ask one brief choice. Cancellation does not roll back an already committed selection. Never invent an instant quiz or choose another topic.",
                    focusParameters("Exact owned saved study ID chosen in the conversation.")),
                VoiceTutorMcpToolDefinition(ADVANCE_STUDY,
                    "Move the lesson to one real direct child of the current focus after feedback and the learner's conversational choice to continue there. Read actual children first; never invent edges, skip a level or switch because of silence. This changes only call focus and requires no mutation confirmation. A successful result completes selection; read list_pending_questions separately before teaching. Honour a later cancellation or topic switch immediately by stopping the old follow-up, without claiming to roll back a committed selection.",
                    focusParameters("Exact saved direct-child ID chosen for the next lesson.")),
                VoiceTutorMcpToolDefinition(PREPARE_MUTATION,
                    "Prepare, but DO NOT execute, the learner's requested new root, child, name/level change or deletion. Understand natural references and intent from the conversation without requiring a repeated command. Read exact owned target/parent IDs first. Returns one immutable proposal_id and confirmation_question; ask that short question once, then wait for the learner. For deletion, the question includes the whole subtree; prior records are preserved. Use difficulty_level 1-10, default 5 for new nodes. A changed target or patch needs a new proposal. Only genuinely missing target or values need clarification.",
                    mapOf("type" to "object", "additionalProperties" to false,
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string", "enum" to listOf(CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)),
                            "study_id" to mapOf("type" to "integer", "minimum" to 1),
                            "parent_study_id" to mapOf("type" to "integer", "minimum" to 1),
                            "topic" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 255),
                            "difficulty_level" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10)),
                        "required" to listOf("action"))),
                VoiceTutorMcpToolDefinition(CONFIRM_MUTATION,
                    "After the prepared confirmation question has finished playing and the learner replies, interpret natural agreement yourself (응, 네, 그렇게 해, yes) and execute the exact proposal with confirm=true. Refusal or cancellation uses false. Never execute on silence, filler, unrelated speech, quoted wishes or changed details; prepare a new proposal for changed details. Never ask for special wording or a second confirmation. This takes no editable patch and cannot change the prepared action. Report only the actual result briefly without internal/server jargon.",
                    mapOf("type" to "object", "additionalProperties" to false,
                        "properties" to mapOf("proposal_id" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 191),
                            "confirm" to mapOf("type" to "boolean")), "required" to listOf("proposal_id", "confirm"))),
            )

    /** Canonical pending data, never an old classifier authorization or provider-controlled lease. */
    private data class RealtimeMutation(
        val id: String, val sessionId: String, val userId: Long, val deviceId: String, val authSessionId: Long,
        val callId: String, val revision: Long, val learnerTurnId: Long, val responseGeneration: Long,
        val action: String, val arguments: Map<String, Any>, val target: VoiceTutorStudyTargetCandidate?,
        val deletedIds: Set<Long>, val question: String, val expiresAt: Instant,
    ) {
        fun matches(snapshot: VoiceTutorStudySnapshot): Boolean = target?.let {
            it.studyId == snapshot.studyId && it.parentStudyId == snapshot.parentStudyId &&
                it.topic == snapshot.topic && it.difficulty == snapshot.difficulty
        } == true
        fun matches(context: VoiceTutorWebRtcControlContext, currentRevision: Long): Boolean =
            sessionId == context.session.id && userId == context.principal?.userId &&
                deviceId == context.principal?.deviceId && authSessionId == context.principal?.sessionId &&
                callId == context.callId && revision == currentRevision
    }

    private suspend fun realtimeLearnerTurn(context: VoiceTutorWebRtcControlContext, revision: Long): Long? {
        val dialogue = context.dialogueBoundary ?: return null
        val itemId = dialogue.latestAcceptedLearnerProviderItemId?.takeIf { it.isNotBlank() && it.length <= 191 }
            ?: return null
        val sourceRevision = dialogue.latestAcceptedLearnerLessonRevision
        if (dialogue.responseGeneration <= 0 || sourceRevision < 0 || sourceRevision > revision ||
            context.initialLessonRevision != revision) return null
        return confirmations.persistedLearnerTurnId(context.session.userId, context.session.id, itemId, sourceRevision)
            ?.takeIf { it > 0 }
    }

    private suspend fun readRealtimeTarget(context: VoiceTutorWebRtcControlContext, id: Long): VoiceTutorStudyTargetCandidate? {
        val spec = specifications["get_study"] ?: return null
        if (!isAuthorized(context)) return null
        val result = invoke(context.principal!!, spec, mapOf("study_id" to id, "language" to context.session.language))
        if (result.isError() == true || !isAuthorized(context)) return null
        return result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
            ?.let(::verifiedUpdateTarget)?.takeIf { it.studyId == id && it.difficulty != null }
    }

    private data class TopicInputProposal(
        val value: VoiceTutorStudyTopicUserInput, val parent: VoiceTutorStudyTargetCandidate, val difficulty: Int,
        val sessionId: String, val userId: Long, val deviceId: String, val authSessionId: Long,
        val callId: String, val revision: Long, val expiresAt: Instant,
        var selected: List<Int>? = null, var submitting: Boolean = false, var result: VoiceTutorMcpToolResult? = null,
    ) {
        fun matches(context: VoiceTutorWebRtcControlContext, currentRevision: Long) =
            sessionId == context.session.id && userId == context.principal?.userId && deviceId == context.principal?.deviceId &&
                authSessionId == context.principal?.sessionId && callId == context.callId && revision == currentRevision &&
                context.initialLessonRevision == revision
    }

    override suspend fun prepareStudyTopicUserInput(context: VoiceTutorWebRtcControlContext,
        parentStudyId: Long, topics: List<String>, difficultyLevel: Int): VoiceTutorStudyTopicUserInput? {
        if (!context.realtimeModelTools || !isAuthorized(context) || parentStudyId <= 0 || difficultyLevel !in 1..10 ||
            topics.size !in 1..8 || topics.any { it.isBlank() || it != it.trim() || it.length > 200 } ||
            topics.map(::normalizedStudyTopicIdentity).distinct().size != topics.size) return null
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (context.initialLessonRevision != revision) return null
        val parent = readRealtimeTarget(context, parentStudyId) ?: return null
        val rootDifficulty = originalRoot(context, parentStudyId)?.difficulty ?: return null
        if (!isAuthorized(context) || studyContexts.currentRevision(context.session.userId, context.session.id) != revision) return null
        val value = VoiceTutorStudyTopicUserInput(UUID.randomUUID().toString(),
            when (context.session.language) { "en" -> "Add subtopics"; "ja" -> "サブトピックを追加"; else -> "하위 주제 추가" },
            when (context.session.language) {
                "en" -> "Select subtopics under ${parent.topic}. Submit adds only your selections at level $rootDifficulty."
                "ja" -> "${parent.topic} の下に追加する項目を選択してください。送信すると選択した項目をレベル $rootDifficulty で追加します。"
                else -> "${parent.topic} 아래에 추가할 주제를 선택하세요. 제출하면 선택한 주제만 레벨 $rootDifficulty 으로 추가합니다."
            }, topics.toList())
        val principal = context.principal ?: return null
        val now = clock.instant()
        val pending = TopicInputProposal(value, parent, rootDifficulty, context.session.id, principal.userId,
            principal.deviceId, principal.sessionId, context.callId, revision, minOf(now.plusSeconds(600), context.session.hardEndsAt))
        synchronized(topicInputProposals) {
            topicInputProposals.entries.removeIf { !now.isBefore(it.value.expiresAt) && !it.value.submitting }
            if (topicInputProposals.size >= 256) return null
            topicInputProposals[value.proposalId] = pending
        }
        return value
    }

    override suspend fun submitStudyTopicUserInput(context: VoiceTutorWebRtcControlContext,
        proposalId: String, selectedIndices: List<Int>): VoiceTutorMcpToolResult {
        if (!context.realtimeModelTools || !isAuthorized(context)) return inactiveCall()
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val pending = synchronized(topicInputProposals) { topicInputProposals[proposalId] }
            ?.takeIf { it.matches(context, revision) && clock.instant().isBefore(it.expiresAt) }
            ?: return failure("PROPOSAL_EXPIRED", "This exact topic proposal is no longer available.")
        val indices = selectedIndices.sorted()
        if (indices.isEmpty() || indices.distinct().size != indices.size || indices.any { it !in pending.value.topics.indices })
            return failure("INVALID_ARGUMENTS", "Select only topics in the exact prepared proposal.")
        synchronized(topicInputProposals) {
            if (pending.submitting || (pending.selected != null && pending.selected != indices))
                return failure("PROPOSAL_ALREADY_SUBMITTED", "Only the original submitted selection can be retried.")
            pending.result?.let { return it }
            pending.selected = indices
            pending.submitting = true
        }
        try {
            if (readRealtimeTarget(context, pending.parent.studyId) != pending.parent ||
                originalRoot(context, pending.parent.studyId)?.difficulty != pending.difficulty || !isAuthorized(context) ||
                studyContexts.currentRevision(context.session.userId, context.session.id) != revision)
                return failure("MUTATION_TARGET_STALE", "The exact parent changed; no new write was started.")
            val spec = specifications["create_study_topics"] ?: return failure("MCP_UNAVAILABLE", "Topic creation is unavailable.")
            val selectedTopics = indices.map { pending.value.topics[it] }
            val args = mapOf("parent_study_id" to pending.parent.studyId, "topics" to selectedTopics,
                "difficulty_level" to pending.difficulty,
                BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT to true,
                BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to pending.parent.topic,
                BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to requireNotNull(pending.parent.difficulty),
                BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to (pending.parent.parentStudyId ?: 0L))
            val result = invoke(requireNotNull(context.principal), spec, args)
            if (result.isError() == true) return boundedResult(result, "create_study_topics")
            val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
            val children = payload?.path("topics")
            if (payload == null || positiveId(payload.path("parentStudyId")) != pending.parent.studyId ||
                children == null || !children.isArray || children.size() != selectedTopics.size)
                return failure("RESULT_UNCONFIRMED", "Read the saved tree before retrying the same selection.")
            val compact = children.mapIndexed { index, child ->
                val id = positiveId(child.path("id"))
                val level = child.path("difficultyLevel")
                val created = child.path("created")
                if (id == null || positiveId(child.path("parentStudyId")) != pending.parent.studyId || !created.isBoolean ||
                    !child.path("topic").isTextual || normalizedStudyTopicIdentity(child.path("topic").asText()) != normalizedStudyTopicIdentity(selectedTopics[index]) ||
                    !level.isIntegralNumber || level.asInt() !in 1..10 || (created.booleanValue() && level.asInt() != pending.difficulty))
                    return failure("RESULT_UNCONFIRMED", "Read the saved tree before retrying the same selection.")
                mapOf("id" to id, "parentStudyId" to pending.parent.studyId, "topic" to child.path("topic").asText(),
                    "difficultyLevel" to level.asInt(), "created" to created.booleanValue())
            }
            if (compact.map { it["id"] }.distinct().size != compact.size)
                return failure("RESULT_UNCONFIRMED", "Read the saved tree before retrying the same selection.")
            val createdIds = compact.filter { it["created"] == true }.map { it["id"] as Long }
            val confirmed = VoiceTutorMcpToolResult(objectMapper.writeValueAsString(mapOf("parentStudyId" to pending.parent.studyId,
                "topics" to compact)), false, studyTreeChanged = createdIds.isNotEmpty(),
                changeKind = VoiceTutorStudyChangeKind.CREATED, changedStudyIds = createdIds)
            synchronized(topicInputProposals) { pending.result = confirmed }
            return confirmed
        } finally {
            synchronized(topicInputProposals) { pending.submitting = false }
        }
    }

    private suspend fun prepareRealtimeMutation(context: VoiceTutorWebRtcControlContext, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
        if (!isAuthorized(context)) return inactiveCall()
        val action = arguments["action"] as? String
        val exact = arguments.filterKeys { it != "action" }.toMutableMap()
        if (action !in setOf(CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)) return failure("INVALID_ARGUMENTS", "Choose one supported mutation action.")
        val allowed = when (action) {
            CREATE_ROOT -> setOf("topic", "difficulty_level")
            CREATE_TOPIC -> setOf("parent_study_id", "topic", "difficulty_level")
            UPDATE_STUDY -> setOf("study_id", "topic", "difficulty_level")
            else -> setOf("study_id")
        }
        if (exact.keys.any { it !in allowed }) return failure("INVALID_ARGUMENTS", "Use only the fields belonging to this action.")
        fun integer(key: String): Long? = exact[key]?.let { value ->
            objectMapper.valueToTree<JsonNode>(value).let(::positiveId)
        }
        val id = if (action == CREATE_TOPIC) integer("parent_study_id") else integer("study_id")
        if (action != CREATE_ROOT && id == null) return failure("INVALID_ARGUMENTS", "Choose one exact owned target or parent ID.")
        if ("topic" in exact) {
            val topic = (exact["topic"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 }
                ?: return failure("INVALID_ARGUMENTS", "Provide a topic containing 1 to 255 characters.")
            exact["topic"] = topic
        }
        if (action in CREATION_TOOLS && "topic" !in exact) return failure("INVALID_ARGUMENTS", "The new topic is missing.")
        if (action in CREATION_TOOLS && "difficulty_level" !in exact) exact["difficulty_level"] = DEFAULT_ROOT_DIFFICULTY
        if ("difficulty_level" in exact) {
            val level = integer("difficulty_level")?.takeIf { it in 1..10 }?.toInt()
                ?: return failure("INVALID_ARGUMENTS", "Choose a level from 1 to 10.")
            exact["difficulty_level"] = level
        }
        if (action == UPDATE_STUDY && exact.keys == setOf("study_id")) return failure("INVALID_ARGUMENTS", "Specify the new name and/or level.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val learnerId = realtimeLearnerTurn(context, revision) ?: return persistencePending()
        val deletion = if (action == DELETE_STUDY) deletionPreview(context, id!!) ?: return failure("DELETE_PREVIEW_UNAVAILABLE", "This subtree could not be checked; nothing was deleted.") else null
        val target = deletion?.target ?: id?.let { readRealtimeTarget(context, it) ?: return failure("STUDY_NOT_FOUND", "That exact saved topic was not available.") }
        if (action == CREATE_TOPIC) exact["difficulty_level"] = originalRoot(context, requireNotNull(id))?.difficulty
            ?: return failure("STUDY_TREE_CHANGED", "The original main study could not be resolved.")
        if (!isAuthorized(context)) return inactiveCall()
        if (studyContexts.currentRevision(context.session.userId, context.session.id) != revision || realtimeLearnerTurn(context, revision) != learnerId) return persistencePending()
        val korean = context.session.language.startsWith("ko")
        val question = when (action) {
            CREATE_ROOT -> if (korean) "${exact["topic"]} 주제를 레벨 ${exact["difficulty_level"]}로 만들까요?" else "Create ${exact["topic"]} at level ${exact["difficulty_level"]}?"
            CREATE_TOPIC -> if (korean) "${target!!.topic} 아래에 ${exact["topic"]} 주제를 레벨 ${exact["difficulty_level"]}로 만들까요?" else "Create ${exact["topic"]} under ${target!!.topic} at level ${exact["difficulty_level"]}?"
            UPDATE_STUDY -> if (korean) "${target!!.topic} 주제를 ${listOfNotNull((exact["topic"] as? String)?.let { "이름 $it" }, exact["difficulty_level"]?.let { "레벨 $it" }).joinToString(", ")}로 바꿀까요?" else "Change ${target!!.topic} to ${listOfNotNull(exact["topic"]?.let { "name $it" }, exact["difficulty_level"]?.let { "level $it" }).joinToString(" and ")}?"
            else -> if (korean) "${target!!.topic} 주제와 하위 주제를 삭제할까요?" else "Delete ${target!!.topic} and its descendants?"
        }
        val principal = context.principal!!
        val now = clock.instant()
        val proposed = RealtimeMutation(UUID.randomUUID().toString(), context.session.id, principal.userId, principal.deviceId,
            principal.sessionId, context.callId, revision, learnerId, context.dialogueBoundary!!.responseGeneration,
            action!!, exact.toMap(), target, deletion?.ids?.toSet().orEmpty(), question, now.plusSeconds(120))
        val pending = synchronized(realtimeProposals) {
            realtimeProposals.entries.removeIf { !now.isBefore(it.value.expiresAt) }
            val old = realtimeProposals[context.session.id]
            if (old != null && old.matches(context, revision) && old.learnerTurnId == learnerId &&
                old.action == proposed.action && old.arguments == proposed.arguments && old.target == target && old.deletedIds == proposed.deletedIds) old
            else {
                if (realtimeProposals.size >= 256) realtimeProposals.remove(realtimeProposals.keys.first())
                realtimeProposals[context.session.id] = proposed
                proposed
            }
        }
        return VoiceTutorMcpToolResult(objectMapper.writeValueAsString(mapOf("prepared" to true, "executed" to false,
            "proposal_id" to pending.id, "confirmation_question" to pending.question,
            "notice" to "Ask this question once, wait for the learner, then use confirm_voice_study_mutation; do not repeat the original command.")), false,
            mutationConfirmationQuestion = pending.question)
    }

    private suspend fun confirmRealtimeMutation(context: VoiceTutorWebRtcControlContext, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
        if (arguments.keys != setOf("proposal_id", "confirm") || arguments["confirm"] !is Boolean) return failure("INVALID_ARGUMENTS", "Use only proposal_id and a boolean confirm.")
        if (!isAuthorized(context)) return inactiveCall()
        val id = (arguments["proposal_id"] as? String)?.takeIf { it.isNotBlank() && it.length <= 191 }
            ?: return failure("INVALID_ARGUMENTS", "Use the prepared proposal ID.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val pending = synchronized(realtimeProposals) { realtimeProposals[context.session.id] }
            ?.takeIf { it.id == id && it.matches(context, revision) && clock.instant().isBefore(it.expiresAt) }
            ?: return failure("PROPOSAL_EXPIRED", "This proposal is no longer pending; no write was started.")
        val dialogue = context.dialogueBoundary ?: return persistencePending()
        val learnerId = dialogue.latestAcceptedLearnerProviderItemId ?: return persistencePending()
        val tutorId = dialogue.precedingTutorProviderItemId ?: return persistencePending()
        val sourceRevision = dialogue.latestAcceptedLearnerLessonRevision
        if (sourceRevision < 0 || sourceRevision > revision || context.initialLessonRevision != revision ||
            dialogue.precedingSpokenResponseGeneration <= pending.responseGeneration ||
            dialogue.precedingTutorSpeechStoppedOrder <= 0 ||
            dialogue.latestAcceptedLearnerSpeechStartedOrder <= dialogue.precedingTutorSpeechStoppedOrder) {
            return failure("CONFIRMATION_REPLY_REQUIRED", "Wait for the learner's reply after the prepared question has finished playing; do not ask it twice.")
        }
        val boundary = confirmations.persistedDialogueBoundary(context.session.userId, context.session.id, learnerId, tutorId, sourceRevision)
            // Row IDs reflect asynchronous persistence arrival, not spoken order. The native
            // generation/playout fence and the port's sequence-ordered question<USER proof
            // establish freshness; identities only distinguish the new reply from its source.
            ?.takeIf { it.learnerTurnId > 0 && it.tutorTurnId > 0 &&
                it.learnerTurnId != pending.learnerTurnId && it.tutorTurnId != pending.learnerTurnId &&
                it.learnerTurnId != it.tutorTurnId }
            ?: return persistencePending()
        if (arguments["confirm"] == true && pending.target != null) {
            if (readRealtimeTarget(context, pending.target.studyId) != pending.target) return failure("MUTATION_TARGET_STALE", "That saved topic changed; no write was started. Read it before preparing a new proposal.")
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (studyContexts.currentRevision(context.session.userId, context.session.id) != revision ||
            confirmations.persistedDialogueBoundary(context.session.userId, context.session.id, learnerId, tutorId, sourceRevision) != boundary) return persistencePending()
        // Linearization: remove the exact ticket before the first suspension that can perform a write.
        val consumed = synchronized(realtimeProposals) {
            if (realtimeProposals[context.session.id] === pending && clock.instant().isBefore(pending.expiresAt)) {
                realtimeProposals.remove(context.session.id); true
            } else false
        }
        if (!consumed) return failure("PROPOSAL_ALREADY_PROCESSED", "This proposal was already handled; never repeat the write.")
        if (arguments["confirm"] == false) return VoiceTutorMcpToolResult("{\"cancelled\":true,\"executed\":false}", false)
        val spec = specifications[pending.action] ?: return failure("MCP_UNAVAILABLE", "This change could not be completed.")
        val execution = pending.copy(learnerTurnId = boundary.learnerTurnId)
        return when (pending.action) {
            CREATE_ROOT -> createRootStudy(context, spec, pending.arguments, execution)
            CREATE_TOPIC -> createStudyTopic(context, spec, pending.arguments, execution)
            UPDATE_STUDY -> updateStudy(context, spec, pending.arguments, execution)
            else -> deleteStudy(context, spec, pending.arguments + ("confirm" to true), pending.target!!.studyId, execution)
        }
    }

    private suspend fun focusRealtimeStudy(context: VoiceTutorWebRtcControlContext, arguments: Map<String, Any>, advance: Boolean, prepareCurriculum: Boolean = true): VoiceTutorMcpToolResult {
        val id = focusStudyId(arguments) ?: return failure("INVALID_ARGUMENTS", "Choose one exact owned study_id.")
        if (!isAuthorized(context)) return inactiveCall()
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val learnerTurn = realtimeLearnerTurn(context, revision) ?: return persistencePending()
        val candidate = readRealtimeTarget(context, id) ?: return failure("STUDY_NOT_FOUND", "The chosen saved topic is unavailable.")
        val parent = if (advance) currentStudyAnchor(context) ?: return failure("GUIDED_FOCUS_REQUIRED", "Select a topic before moving to its child.") else null
        if (advance && (candidate.parentStudyId != parent || candidate.studyId == parent)) return failure("GUIDED_CHILD_REQUIRED", "Choose one real direct child of the current topic.")
        if (prepareCurriculum) curriculum.prepare(context, id)?.let { return it }
        if (!isAuthorized(context)) return inactiveCall()
        if (context.operationStillCurrent?.invoke() == false) return failure("STALE_TURN", "The learner chose a newer direction; no focus was started.")
        val principal = context.principal!!
        val selected = lessonFocus.focusFromRealtimeModel(context.session.userId, context.session.id, id, learnerTurn,
            revision, candidate, VoiceTutorFocusCommitAuthority(principal.deviceId, principal.sessionId, context.callId), parent)
            ?: return failure("LESSON_FOCUS_UNAVAILABLE", "The chosen topic or its path changed; read the saved topic again without creating a replacement.")
        if (selected.studyId != candidate.studyId || selected.snapshot.parentStudyId != candidate.parentStudyId ||
            selected.topic != candidate.topic || selected.difficulty != candidate.difficulty) return failure("LESSON_FOCUS_UNCONFIRMED", "The exact saved focus result could not be verified.")
        val focus = focusMetadata(selected)
        // Focus is already committed. Do not suspend on question work here: a timeout or
        // cancellation after commit would lose the revision the controller must synchronize.
        return VoiceTutorMcpToolResult(objectMapper.writeValueAsString(mapOf("selected" to true,
            "voiceLessonContextReady" to true, "voiceLessonFocus" to focus, "voiceLessonTopics" to listOf(focus),
            "voiceQuestion" to mapOf("lookupRequired" to true),
            "notice" to "This exact topic is selected and selection is complete. No selection operation is running. Call list_pending_questions separately for this exact study_id before teaching; preserve the saved question's original difficulty. If none is ready, use request_question when the learner wants a question. On cancellation stop this topic's follow-up; on a switch follow the latest chosen saved topic without waiting for old question work. Cancellation does not undo this committed selection. Never invent a question or a grade. No further selection confirmation is needed.")),
            false, lessonRevision = selected.revision, lessonFocus = selected)
    }

    private fun persistencePending() = failure("INPUT_PERSISTENCE_PENDING", "The current dialogue boundary is still being saved; retry this same tool internally, without asking the learner to repeat anything.")

    private suspend fun realtimeWriteStillCurrent(context: VoiceTutorWebRtcControlContext, mutation: RealtimeMutation): Boolean =
        isAuthorized(context) && studyContexts.currentRevision(context.session.userId, context.session.id) == mutation.revision &&
            realtimeLearnerTurn(context, mutation.revision) == mutation.learnerTurnId

    override suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult = observeExchange(context, toolName, arguments) {
        executeTool(context, toolName, arguments)
    }

    private suspend fun executeTool(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        if (toolName !in ALLOWED_TOOLS && !(context.realtimeModelTools && toolName in REALTIME_MUTATION_TOOLS + VoiceTutorCanonicalQuestionCoordinator.TOOLS)) {
            return failure("TOOL_NOT_ALLOWED", "This tool is not available in voice calls.")
        }
        try {
            if (objectMapper.writeValueAsBytes(arguments).size > MAX_ARGUMENT_BYTES) {
                return failure("INVALID_ARGUMENTS", "Tool arguments are too large.")
            }
            if (context.realtimeModelTools) {
                if (toolName in VoiceTutorCanonicalQuestionCoordinator.TOOLS) {
                    if (toolName in setOf("request_question", "list_pending_questions")) {
                        val requested = (arguments["study_id"] as? Number)?.toLong()
                        val selected = currentStudyAnchor(context)
                        if (requested != null && requested == selected) curriculum.prepare(context, requested)?.let { return it }
                    }
                    return canonicalQuestions.execute(context, toolName, arguments)
                }
                if (toolName == PREPARE_MUTATION) return prepareRealtimeMutation(context, arguments)
                if (toolName == CONFIRM_MUTATION) return confirmRealtimeMutation(context, arguments)
                if (toolName in setOf(CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)) {
                    return failure("PROPOSAL_REQUIRED", "Use prepare_voice_study_mutation, ask its question once, then confirm_voice_study_mutation after the learner replies.")
                }
                if (toolName == SELECT_STUDY || toolName == ADVANCE_STUDY) {
                    return focusRealtimeStudy(context, arguments, toolName == ADVANCE_STUDY)
                }
            }
            if (toolName == SELECT_STUDY) return selectStudy(context, arguments)
            if (toolName == ADVANCE_STUDY) return advanceStudy(context, arguments)
            val specification = specifications[toolName]
                ?: return failure("MCP_UNAVAILABLE", "This study tool is currently unavailable.")
            // Use the SDK's existing validator, not its logging wrapper: validation errors
            // can contain private argument values and must never enter application logs.
            if (toolName == DELETE_STUDY && arguments.keys.any { it !in DELETE_ARGUMENTS }) {
                return failure("INVALID_ARGUMENTS", "Use only the authorized target and deletion flag.")
            }
            val mcpArguments = arguments
            if (!validator.validate(specification.tool().inputSchema(), mcpArguments).valid()) {
                return failure("INVALID_ARGUMENTS", "Arguments do not match this tool's input schema.")
            }
            var effectiveArguments = arguments
            var candidateReadRequest = candidateReadRequest(toolName, effectiveArguments)
            if (!isAuthorized(context)) return inactiveCall()
            if (toolName == CREATE_ROOT) return createRootStudy(context, specification, arguments)
            if (toolName == CREATE_TOPIC) return createStudyTopic(context, specification, arguments)
            if (toolName == UPDATE_STUDY) return updateStudy(context, specification, arguments)
            if (toolName == DELETE_STUDY) {
                val studyId = (arguments.getValue("study_id") as Number).toLong()
                if (!isAuthorized(context)) return inactiveCall()
                return deleteStudy(context, specification, arguments, studyId)
            }
            if (toolName == LIST_LEARNING_RECORDS) {
                val studyId = (arguments.getValue("study_id") as Number).toLong()
                if (!studyIsWithinCallTree(context, studyId)) {
                    return if (!isAuthorized(context)) inactiveCall() else learningScopeDenied()
                }
                if (!isAuthorized(context)) return inactiveCall()
            }
            val candidateRevisionBefore = candidateReadRequest?.let {
                runCatching { studyContexts.currentRevision(context.session.userId, context.session.id) }.getOrNull()
            }
            var result = invoke(context.principal!!, specification, effectiveArguments)
            val readOnly = toolName !in setOf(CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)
            val queryFallback = voiceStudyQueryFallbackArguments(toolName, effectiveArguments)
            if (queryFallback != null && emptyStudyQueryResult(result)) {
                // Keep the original query authoritative when it names a saved topic that
                // genuinely ends in punctuation. Only an empty, read-only lookup may retry
                // with sentence punctuation added by conversational transcription removed.
                if (!isAuthorized(context)) return inactiveCall()
                effectiveArguments = queryFallback
                candidateReadRequest = candidateReadRequest(toolName, effectiveArguments)
                result = invoke(context.principal!!, specification, effectiveArguments)
            }
            // A saved-study search is private too. Ending a call or revoking a device
            // while its handler is suspended must suppress the late read result.
            if (readOnly && !isAuthorized(context)) return inactiveCall()
            if (toolName in LEARNING_HISTORY_TOOLS) {
                // Reads can suspend too: never return private history after the call or
                // device authorization expired while the existing use case was running.
                if (!isAuthorized(context)) return inactiveCall()
                if (toolName == GET_VOICE_LEARNING_RECORD && result.isError() != true) {
                    val node = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
                    val expectedId = (arguments.getValue("record_id") as Number).toLong()
                    val studyId = node?.path("studyId")
                    if (node?.path("id")?.asText() != expectedId.toString() || studyId == null ||
                        !studyId.isIntegralNumber || !studyId.canConvertToLong() || studyId.longValue() <= 0
                    ) return failure("INVALID_TOOL_RESULT", "The voice record's identity could not be verified.")
                    // The owner-checked detail supplies its real node ID; no model-supplied
                    // study ID or title can authorize a record from a different tree.
                    if (!studyIsWithinCallTree(context, studyId.longValue())) {
                        return if (!isAuthorized(context)) inactiveCall() else learningScopeDenied()
                    }
                    if (!isAuthorized(context)) return inactiveCall()
                }
            }
            val voiceResult = if (context.realtimeModelTools && toolName == "list_studies") {
                boundedNativeStudyDiscovery(result)
            } else boundedResult(result, toolName)
            val enriched = withLessonContext(context, voiceResult, toolName, effectiveArguments)
            if (readOnly && !isAuthorized(context)) return inactiveCall()
            return attachCandidateDiscovery(
                context, enriched, result, candidateReadRequest, candidateRevisionBefore,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep private exception details out of both the tool response and exchange log.
            return failure("MCP_UNAVAILABLE", "The study tool could not complete. Do not assume the action succeeded.")
        }
    }

    private fun voiceStudyQueryFallbackArguments(
        toolName: String,
        arguments: Map<String, Any>,
    ): Map<String, Any>? {
        if (toolName != "list_studies" || "parent_study_id" in arguments) return null
        val query = arguments["query"] as? String ?: return null
        var end = query.length
        while (end > 0) {
            val codePoint = query.codePointBefore(end)
            if (!Character.isWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        var sawSentenceTerminator = false
        while (end > 0) {
            val codePoint = query.codePointBefore(end)
            when {
                isVoiceSentenceTerminator(codePoint) -> sawSentenceTerminator = true
                isVoiceClosingPunctuation(codePoint) -> Unit
                else -> break
            }
            end -= Character.charCount(codePoint)
        }
        if (!sawSentenceTerminator) return null
        val normalized = query.substring(0, end).trimEnd()
        if (normalized.isBlank() || normalized == query) return null
        return arguments + ("query" to normalized)
    }

    private fun emptyStudyQueryResult(result: McpSchema.CallToolResult): Boolean {
        if (result.isError() == true) return false
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
            ?: return false
        val totalCount = payload.path("totalCount")
        val studies = payload.path("studies")
        return totalCount.isIntegralNumber && totalCount.canConvertToLong() &&
            totalCount.longValue() == 0L && studies.isArray && studies.isEmpty
    }

    private fun isVoiceSentenceTerminator(codePoint: Int): Boolean =
        codePoint in VOICE_SENTENCE_TERMINATORS

    private fun isVoiceClosingPunctuation(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.END_PUNCTUATION.toInt() ||
            Character.getType(codePoint) == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            codePoint == '"'.code || codePoint == '\''.code

    private fun candidateReadRequest(toolName: String, arguments: Map<String, Any>): CandidateReadRequest? {
        return when (toolName) {
            "get_study" -> positiveId(objectMapper.valueToTree(arguments["study_id"]))
                ?.let(CandidateReadRequest::ExactStudy)
            "list_studies" -> {
                val parent = arguments["parent_study_id"]?.let { positiveId(objectMapper.valueToTree(it)) }
                val query = (arguments["query"] as? String)?.takeIf { it.isNotBlank() && it.length <= 200 }
                if ((parent == null) == (query == null)) return null
                val offset = arguments["offset"]?.let { nonNegativeLong(objectMapper.valueToTree(it)) } ?: 0L
                val limit = arguments["limit"]?.let { positiveId(objectMapper.valueToTree(it)) } ?: DEFAULT_STUDY_PAGE_LIMIT
                if (offset > Int.MAX_VALUE || limit > MAX_STUDY_PAGE_LIMIT) return null
                if (parent != null) CandidateReadRequest.DirectChildren(parent, offset, limit)
                else CandidateReadRequest.Query(requireNotNull(query), offset, limit)
            }
            else -> null
        }
    }

    private suspend fun attachCandidateDiscovery(
        context: VoiceTutorWebRtcControlContext,
        result: VoiceTutorMcpToolResult,
        rawResult: McpSchema.CallToolResult,
        request: CandidateReadRequest?,
        revisionBefore: Long?,
    ): VoiceTutorMcpToolResult {
        if (result.isError || request == null || revisionBefore == null || revisionBefore < 0 ||
            rawResult.isError() == true || !isAuthorized(context)
        ) return result
        val revisionAfter = runCatching {
            studyContexts.currentRevision(context.session.userId, context.session.id)
        }.getOrNull() ?: return result
        if (revisionAfter != revisionBefore) return result
        val payload = rawResult.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) } ?: return result
        val verified = verifiedTargetCandidates(request, payload) ?: return result
        val currentFocusStudyId = currentStudyAnchor(context)
        if (!isAuthorized(context) ||
            studyContexts.currentRevision(context.session.userId, context.session.id) != revisionBefore
        ) return result
        return result.copy(candidateDiscovery = VoiceTutorCandidateDiscovery(
            source = request.kind,
            lessonRevision = revisionBefore,
            currentFocusStudyId = currentFocusStudyId,
            candidates = verified.candidates,
            scope = verified.scope,
        ))
    }

    private fun verifiedTargetCandidates(
        request: CandidateReadRequest,
        payload: JsonNode,
    ): VerifiedCandidateDiscovery? {
        return when (request) {
            is CandidateReadRequest.ExactStudy -> {
                val candidate = payload.takeIf { it.isObject }?.let(::verifiedTargetCandidate) ?: return null
                if (candidate.studyId != request.studyId) return null
                VerifiedCandidateDiscovery(
                    VoiceTutorCandidateDiscoveryScope.ExactStudy(request.studyId),
                    listOf(candidate),
                )
            }
            is CandidateReadRequest.Query -> {
                val page = verifiedCompleteCandidatePage(payload, request.offset, request.limit) ?: return null
                val candidates = verifiedTargetCandidateList(page.nodes) ?: return null
                VerifiedCandidateDiscovery(
                    VoiceTutorCandidateDiscoveryScope.CompleteQueryPage(
                        request.query, request.offset, request.limit, page.totalCount,
                    ),
                    candidates,
                )
            }
            is CandidateReadRequest.DirectChildren -> {
                val page = verifiedCompleteCandidatePage(payload, request.offset, request.limit) ?: return null
                val candidates = verifiedTargetCandidateList(page.nodes) ?: return null
                if (candidates.any { it.parentStudyId != request.parentStudyId }) return null
                VerifiedCandidateDiscovery(
                    VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(
                        request.parentStudyId, request.offset, request.limit, page.totalCount,
                    ),
                    candidates,
                )
            }
        }
    }

    private fun verifiedTargetCandidateList(nodes: List<JsonNode>): List<VoiceTutorStudyTargetCandidate>? {
        val candidates = nodes.map { verifiedTargetCandidate(it) ?: return null }
        return candidates.takeIf { values -> values.map { it.studyId }.distinct().size == values.size }
    }

    private fun verifiedTargetCandidate(node: JsonNode): VoiceTutorStudyTargetCandidate? {
        val id = positiveId(node.path("id")) ?: return null
        val parent = node.path("parentStudyId").let { parentNode ->
            when {
                parentNode.isNull -> null
                parentNode.isIntegralNumber && parentNode.canConvertToLong() && parentNode.longValue() > 0 ->
                    parentNode.longValue()
                else -> return null
            }
        }
        val topic = node.path("topic").takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return null
        return VoiceTutorStudyTargetCandidate(id, parent, topic)
    }

    /** Stricter live identity used only immediately before a saved-node write. */
    private fun verifiedUpdateTarget(node: JsonNode): VoiceTutorStudyTargetCandidate? {
        val candidate = verifiedTargetCandidate(node) ?: return null
        val difficultyNode = node.path("difficultyLevel")
        val difficulty = when {
            difficultyNode.isMissingNode || difficultyNode.isNull -> null
            difficultyNode.isIntegralNumber && difficultyNode.canConvertToInt() &&
                difficultyNode.intValue() in 1..10 -> difficultyNode.intValue()
            else -> return null
        }
        return candidate.copy(difficulty = difficulty)
    }

    private fun VoiceTutorStudyUpdateTargetProof.matches(candidate: VoiceTutorStudyTargetCandidate): Boolean =
        studyId == candidate.studyId && parentStudyId == candidate.parentStudyId && topic == candidate.topic &&
            difficulty?.let { it == candidate.difficulty } != false

    private fun VoiceTutorStudyUpdateTargetProof.matches(snapshot: VoiceTutorStudySnapshot): Boolean =
        studyId == snapshot.studyId && parentStudyId == snapshot.parentStudyId && topic == snapshot.topic &&
            difficulty?.let { it == snapshot.difficulty } != false

    private fun verifiedCompleteCandidatePage(
        payload: JsonNode,
        requestedOffset: Long,
        requestedLimit: Long,
    ): VerifiedCandidatePage? {
        if (!payload.isObject) return null
        val studies = payload.path("studies").takeIf {
            it.isArray && it.size() in 0..MAX_TARGET_CANDIDATES
        } ?: return null
        val totalCount = nonNegativeLong(payload.path("totalCount")) ?: return null
        val offset = nonNegativeLong(payload.path("offset")) ?: return null
        val limit = positiveId(payload.path("limit"))?.takeIf { it <= MAX_STUDY_PAGE_LIMIT } ?: return null
        val size = studies.size().toLong()
        // Each slice must itself be the exact full server page. The realtime
        // graph may use an exact offset-zero multi-result slice to prove a real
        // branch, but zero/one results prove a leaf/single edge only at offset 0;
        // later pages merely extend the stable contiguous offer prefix.
        if (offset != requestedOffset || limit != requestedLimit ||
            totalCount > MAX_DISCOVERY_RESULT_CANDIDATES || requestedOffset % requestedLimit != 0L ||
            (totalCount == 0L && requestedOffset != 0L) ||
            (totalCount > 0L && requestedOffset >= totalCount)
        ) return null
        val expectedSize = minOf(requestedLimit, totalCount - requestedOffset)
        if (size != expectedSize || size > limit) return null
        return VerifiedCandidatePage(studies.toList(), totalCount)
    }

    private fun nonNegativeLong(node: JsonNode): Long? = node.takeIf {
        it.isIntegralNumber && it.canConvertToLong() && it.longValue() >= 0
    }?.longValue()

    private suspend fun isAuthorized(context: VoiceTutorWebRtcControlContext): Boolean {
        val principal = context.principal ?: return false
        val snapshot = context.session
        if (principal.anonymous || principal.userId != snapshot.userId ||
            snapshot.status != VoiceTutorSessionStatus.ACTIVE ||
            snapshot.providerSessionId != context.callId ||
            !clock.instant().isBefore(snapshot.hardEndsAt)
        ) return false
        if (!authorization.isAuthorized(
                userId = principal.userId,
                deviceId = principal.deviceId,
                authSessionId = principal.sessionId,
                now = clock.instant(),
            )
        ) return false
        val current = persistence.findSession(principal.userId, snapshot.id) ?: return false
        val sameCall = current.id == snapshot.id && current.userId == principal.userId &&
            current.status == VoiceTutorSessionStatus.ACTIVE && current.acceptedStudyId == snapshot.acceptedStudyId &&
            current.providerSessionId == context.callId && clock.instant().isBefore(current.hardEndsAt)
        if (!sameCall) return false
        // A focus can move during this call, but only the persisted focus ledger can authorize
        // a live pointer different from the immutable original create request.
        return current.studyId == null || current.studyId == snapshot.acceptedStudyId ||
            lessonFocus.history(principal.userId, snapshot.id).maxByOrNull { it.revision }?.studyId == current.studyId
    }

    private suspend fun currentStudyAnchor(context: VoiceTutorWebRtcControlContext): Long? {
        val current = persistence.findSession(context.session.userId, context.session.id) ?: return null
        return current.studyId ?: lessonFocus.history(context.session.userId, context.session.id)
            .maxByOrNull { it.revision }?.studyId ?: current.acceptedStudyId
    }

    private suspend fun selectStudy(
        context: VoiceTutorWebRtcControlContext,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        val id = focusStudyId(arguments)
            ?: return failure("INVALID_ARGUMENTS", "Choose one exact positive saved study_id.")
        return focusStudy(context, id, guidedAdvance = false)
    }

    private suspend fun advanceStudy(
        context: VoiceTutorWebRtcControlContext,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        val id = focusStudyId(arguments)
            ?: return failure("INVALID_ARGUMENTS", "Choose one exact positive direct-child study_id.")
        if (!isAuthorized(context)) return inactiveCall()
        val currentId = currentStudyAnchor(context)
            ?: return failure(
                "GUIDED_FOCUS_REQUIRED",
                "Select the learner's initial saved topic before trying to move down its tree.",
            )
        if (id == currentId) {
            return failure("GUIDED_CHILD_REQUIRED", "The next guided focus must be a direct child of the current focus.")
        }
        focusRequestFailure(context, id, guidedAdvance = true, expectedParentStudyId = currentId)?.let {
            return it
        }
        val readStudy = specifications["get_study"]
            ?: return failure("MCP_UNAVAILABLE", "The child study could not be verified.")
        val result = invoke(
            context.principal!!,
            readStudy,
            mapOf("study_id" to id, "language" to context.session.language),
        )
        if (result.isError() == true || result.structuredContent() == null) {
            return failure("GUIDED_CHILD_UNAVAILABLE", "The requested saved child could not be verified.")
        }
        val child = objectMapper.valueToTree<JsonNode>(result.structuredContent())
        val verifiedChild = verifiedTargetCandidate(child)
        val attestedChild = context.dialogueBoundary?.latestAcceptedLearnerTargetCandidate
        if (verifiedChild == null || verifiedChild.studyId != id || verifiedChild.parentStudyId != currentId ||
            attestedChild == null || verifiedChild != attestedChild
        ) {
            return failure(
                "GUIDED_DESCENT_OUT_OF_SCOPE",
                "Teacher-guided progression can move only to the unchanged direct child the learner just confirmed.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        return focusStudy(context, id, guidedAdvance = true, expectedParentStudyId = currentId)
    }

    private fun focusRequestFailure(
        context: VoiceTutorWebRtcControlContext,
        id: Long,
        guidedAdvance: Boolean,
        expectedParentStudyId: Long?,
    ): VoiceTutorMcpToolResult? {
        val dialogue = context.dialogueBoundary
        val createdRootImmediateStart = !guidedAdvance &&
            dialogue?.focusAuthorization?.purpose ==
            VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START
        val updatedStudyImmediateStart = !guidedAdvance &&
            dialogue?.focusAuthorization?.purpose ==
            VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START
        val requiredIntent = when {
            guidedAdvance -> VoiceTutorInputIntent.CONTINUE_TREE
            createdRootImmediateStart -> VoiceTutorInputIntent.CREATE_ROOT_STUDY
            updatedStudyImmediateStart -> VoiceTutorInputIntent.UPDATE_STUDY
            else -> VoiceTutorInputIntent.SELECT_SAVED_TOPIC
        }
        val providerItemId = dialogue?.latestAcceptedLearnerProviderItemId
        if (dialogue == null || providerItemId.isNullOrBlank() || providerItemId.length > 191 ||
            dialogue.latestAcceptedLearnerIntent != requiredIntent ||
            dialogue.focusAuthorization?.isActive() != true
        ) {
            return failure(
                if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
                if (guidedAdvance) {
                    "Wait for a newly persisted learner request or contextual agreement to continue down the saved tree."
                } else {
                    "Speak one exact server-read saved candidate as a natural offer, then wait for one newly persisted natural choice. A named start, referential start, or contextual agreement to one candidate is sufficient; never prescribe special wording or demand a second restatement."
                },
            )
        }
        val attestedTarget = dialogue.latestAcceptedLearnerTargetStudyId
        if (createdRootImmediateStart || updatedStudyImmediateStart) {
            if (attestedTarget != id) {
                return failure(
                    "LEARNER_TARGET_MISMATCH",
                    "The server-read node is not the exact study authorized for this immediate lesson start.",
                )
            }
            val candidate = dialogue.latestAcceptedLearnerTargetCandidate?.takeIf {
                it.studyId == id && (!createdRootImmediateStart || it.parentStudyId == null) &&
                    it.parentStudyId?.let { parent -> parent > 0 && parent != id } != false &&
                    it.topic.isNotBlank() && it.topic == it.topic.trim() && it.topic.length <= 255 &&
                    it.difficulty?.let { difficulty -> difficulty in 1..10 } == true
            } ?: return failure(
                "LEARNER_TARGET_MISMATCH",
                "The exact server-read saved-node metadata is missing or stale; do not start teaching.",
            )
            dialogue.latestAcceptedLearnerTargetTraversal?.takeIf { it.isValidFor(candidate) }
                ?: return failure(
                    "LEARNER_TARGET_MISMATCH",
                    "The exact server-read saved-node path is missing or stale; do not start teaching.",
                )
            return null
        }
        if (attestedTarget == null || dialogue.latestAcceptedLearnerTargetOfferId?.let { it > 0 } != true) {
            return failure(
                if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
                "Speak the exact server-read saved topic as one natural offer, then accept one natural named, referential, or contextual choice; never ask for a special phrase or second restatement.",
            )
        }
        if (attestedTarget != id) {
            return failure(
                "LEARNER_TARGET_MISMATCH",
                "The requested study_id is not the exact saved topic confirmed by the learner. Read and offer the branch again.",
            )
        }
        val candidate = dialogue.latestAcceptedLearnerTargetCandidate?.takeIf {
            it.studyId == id && it.studyId == attestedTarget &&
                it.parentStudyId?.let { parent -> parent > 0 } != false &&
                it.topic.isNotBlank() && it.topic.length <= 255
        } ?: return failure(
            "LEARNER_TARGET_MISMATCH",
            "The confirmed saved topic metadata is missing or stale. Read it, speak the exact candidate again, and wait for a fresh reply.",
        )
        dialogue.latestAcceptedLearnerTargetTraversal?.takeIf { it.isValidFor(candidate) }
            ?: return failure(
                "LEARNER_TARGET_MISMATCH",
                "The confirmed saved topic path is missing or stale. Read it, speak the exact candidate again, and wait for a fresh reply.",
            )
        if (guidedAdvance && candidate.parentStudyId != expectedParentStudyId) {
            return failure(
                "GUIDED_DESCENT_OUT_OF_SCOPE",
                "Teacher-guided progression can move only to one direct child of the current focus.",
            )
        }
        return null
    }

    private suspend fun focusStudy(
        context: VoiceTutorWebRtcControlContext,
        id: Long,
        guidedAdvance: Boolean,
        expectedParentStudyId: Long? = null,
    ): VoiceTutorMcpToolResult {
        if (!isAuthorized(context)) return inactiveCall()
        focusRequestFailure(context, id, guidedAdvance, expectedParentStudyId)?.let { return it }
        val dialogue = requireNotNull(context.dialogueBoundary)
        val createdRootImmediateStart = !guidedAdvance &&
            dialogue.focusAuthorization?.purpose ==
            VoiceTutorFocusAuthorizationPurpose.CREATED_ROOT_IMMEDIATE_START
        val updatedStudyImmediateStart = !guidedAdvance &&
            dialogue.focusAuthorization?.purpose ==
            VoiceTutorFocusAuthorizationPurpose.UPDATED_STUDY_IMMEDIATE_START
        val providerItemId = requireNotNull(dialogue.latestAcceptedLearnerProviderItemId)
        val attestedCandidate = requireNotNull(dialogue.latestAcceptedLearnerTargetCandidate)
        val attestedTraversal = requireNotNull(dialogue.latestAcceptedLearnerTargetTraversal)
        val currentRevision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val learnerRevision = dialogue.latestAcceptedLearnerLessonRevision
        val expectedFocusRevision = dialogue.focusExpectedCurrentLessonRevision
        val revisionValid = if (updatedStudyImmediateStart) {
            learnerRevision >= 0 && expectedFocusRevision != null &&
                expectedFocusRevision > learnerRevision && currentRevision == expectedFocusRevision
        } else {
            currentRevision >= 0 && learnerRevision == currentRevision
        }
        if (!revisionValid) {
            return failure(
                if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
                if (updatedStudyImmediateStart) {
                    "The exact post-update lesson revision does not match the revised saved-node snapshot. Do not start teaching."
                } else {
                    "The learner's topic intent belongs to an older lesson focus. Wait for a fresh reply in the current topic."
                },
            )
        }
        val authorization = confirmations.learnerTurnAuthorization(
            context.session.userId,
            context.session.id,
            providerItemId,
            learnerRevision,
            dialogue.precedingQuestionProviderItemId,
            dialogue.precedingAnswerProviderItemId,
            dialogue.precedingTutorFeedbackProviderItemId,
            dialogue.precedingTutorNavigationOfferProviderItemId,
        ) ?: return failure(
            if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
            if (guidedAdvance) {
                "The learner's continuation was not durably accepted. Wait for a fresh reply instead of guessing."
            } else {
                "The natural topic choice was not durably accepted. Speak the exact candidate as one natural offer and wait for one newly persisted natural choice; never prescribe special wording or demand a second restatement."
            },
        )
        if (guidedAdvance && (!authorization.completedExchange ||
                !dialogue.precedingTutorFeedbackForStudyAnswer ||
                dialogue.precedingSpokenResponseGeneration <= 0 ||
                dialogue.responseGeneration <= dialogue.precedingSpokenResponseGeneration ||
                dialogue.precedingTutorSpeechStoppedOrder <= 0 ||
                dialogue.latestAcceptedLearnerSpeechStartedOrder <= dialogue.precedingTutorSpeechStoppedOrder
            )
        ) {
            return failure(
                "CURRENT_EXCHANGE_INCOMPLETE",
                "Finish the current saved-topic question, learner answer and spoken feedback before offering one deeper child.",
            )
        }
        val learnerTurnId = authorization.turnId.takeIf { it > 0 } ?: return failure(
            if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
            if (guidedAdvance) {
                "The learner's continuation was not durably accepted. Wait for a fresh reply instead of guessing."
            } else {
                "The natural topic choice was not durably accepted. Speak the exact candidate as one natural offer and wait for one newly persisted natural choice; never prescribe special wording or demand a second restatement."
            },
        )
        if (!isAuthorized(context)) return inactiveCall()
        val principal = requireNotNull(context.principal)
        val commitAuthority = VoiceTutorFocusCommitAuthority(
            deviceId = principal.deviceId,
            authSessionId = principal.sessionId,
            providerCallId = context.callId,
        )
        val selection = if (guidedAdvance) {
            lessonFocus.advance(
                context.session.userId,
                context.session.id,
                requireNotNull(expectedParentStudyId),
                id,
                learnerTurnId,
                expectedCurrentRevision = currentRevision,
                authorization = dialogue.focusAuthorization,
                expectedCandidate = attestedCandidate,
                commitAuthority = commitAuthority,
                expectedTraversal = attestedTraversal,
            )
        } else {
            lessonFocus.focus(
                context.session.userId,
                context.session.id,
                id,
                learnerTurnId = learnerTurnId,
                expectedCurrentRevision = currentRevision,
                authorization = dialogue.focusAuthorization,
                expectedCandidate = attestedCandidate,
                commitAuthority = commitAuthority,
                expectedTraversal = attestedTraversal,
            )
        } ?: return failure(
            "LESSON_FOCUS_UNAVAILABLE",
            if (guidedAdvance) {
                "This learner turn was already used or the saved child is no longer the current focus's direct child. Wait for the learner's next meaningful continuation or read the current branch again."
            } else {
                "This owned saved topic and its complete parent path could not be prepared; no focus was selected. Read saved topics or ask one brief clarification instead of guessing or creating a replacement."
            },
        )
        if (selection.studyId != id || selection.snapshot.studyId != id || selection.revision <= 0 ||
            selection.snapshot.topic.isBlank() || selection.snapshot.topic.length > 255 ||
            selection.snapshot.difficulty !in 1..10 || selection.snapshot.parentStudyId?.let { it > 0 } == false
        ) return failure("LESSON_FOCUS_UNCONFIRMED", "The saved focus result could not be verified; do not begin teaching or repeat a selection automatically.")
        if ((createdRootImmediateStart || updatedStudyImmediateStart) && (
                (createdRootImmediateStart && selection.snapshot.parentStudyId != null) ||
                    selection.snapshot.parentStudyId != attestedCandidate.parentStudyId ||
                    selection.snapshot.topic != attestedCandidate.topic ||
                    selection.snapshot.difficulty != attestedCandidate.difficulty
            )
        ) return failure(
            "LESSON_FOCUS_UNCONFIRMED",
            "The saved node changed before its exact revised metadata could be focused; no lesson was started.",
        )
        // Selection already committed. Optional tree enrichment must not turn it into an
        // uncertain failed selection or cause a duplicate focus epoch on retry. In particular,
        // logout/revocation after the atomic commit must not hide the new realtime lesson epoch.
        val saved = if (isAuthorized(context)) {
            try {
                currentSnapshots(studyContexts.list(context.session.userId, context.session.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                listOf(selection.snapshot)
            }
        } else {
            listOf(selection.snapshot)
        }
        val focus = focusMetadata(selection)
        return VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "selected" to true, "voiceLessonContextReady" to true,
                "voiceLessonFocus" to focus, "voiceLessonTopics" to listOf(focus),
                "voiceLessonTree" to VoiceTutorLessonTreeContext.metadata(id, saved, listOf(id)),
                "voiceLessonFocusChange" to when {
                    guidedAdvance -> "GUIDED_DIRECT_CHILD"
                    createdRootImmediateStart -> "CREATED_ROOT_IMMEDIATE_START"
                    updatedStudyImmediateStart -> "UPDATED_STUDY_IMMEDIATE_START"
                    else -> "EXPLICIT_SELECTION"
                },
                "notice" to when {
                    guidedAdvance ->
                        "The next direct child focus is confirmed. Review only its node history, then ask one question at its frozen level; do not skip another edge or append a second question."
                    createdRootImmediateStart ->
                        "The newly created root and the learner's immediate lesson start are both confirmed. Use its frozen level and ask the first substantive question now without requesting readiness or permission again."
                    updatedStudyImmediateStart ->
                        "The saved-node update, exact revised readback and immediate lesson focus are confirmed. Use the revised frozen name and level and ask the first substantive question now without requesting readiness or permission again."
                    else ->
                        "The saved lesson focus and the learner's start agreement are confirmed. Review only its node history, then ask the first substantive question now at its frozen level without requesting readiness, permission, or another confirmation; prior questions and navigation turns keep their original context."
                },
            )),
            isError = false, lessonRevision = selection.revision, lessonFocus = selection,
        )
    }

    private fun focusMetadata(selection: VoiceTutorLessonFocusSelection): Map<String, Any?> = linkedMapOf(
        "studyId" to selection.studyId, "parentStudyId" to selection.snapshot.parentStudyId,
        "topic" to selection.snapshot.topic, "difficulty" to selection.snapshot.difficulty,
        "revision" to selection.revision,
    )

    private fun focusStudyId(arguments: Map<String, Any>): Long? = arguments["study_id"]
        ?.let { positiveId(objectMapper.valueToTree(it)) }
        ?.takeIf { arguments.keys == setOf("study_id") }

    private suspend fun parentIsWithinCallStudy(context: VoiceTutorWebRtcControlContext, parentStudyId: Long): Boolean {
        val callStudyId = currentStudyAnchor(context) ?: return false
        if (callStudyId <= 0) return false
        // A selected study deleted during this call keeps an immutable lesson anchor,
        // but that anchor must never authorize a new child under a nonexistent parent.
        if (persistence.findSession(context.session.userId, context.session.id)?.studyId == null) return false
        val readStudy = specifications["get_study"] ?: return false
        var candidate = parentStudyId
        val visited = mutableSetOf<Long>()
        repeat(MAX_ANCESTOR_READS) {
            if (candidate == callStudyId) return true
            if (candidate <= 0 || !visited.add(candidate) || !isAuthorized(context)) return false
            val result = invoke(
                context.principal!!,
                readStudy,
                mapOf("study_id" to candidate, "language" to context.session.language),
            )
            if (result.isError() == true || result.structuredContent() == null) return false
            val node = objectMapper.valueToTree<JsonNode>(result.structuredContent())
            val returnedId = node.path("id")
            val parentId = node.path("parentStudyId")
            if (!returnedId.isIntegralNumber || !returnedId.canConvertToLong() || returnedId.longValue() != candidate ||
                !parentId.isIntegralNumber || !parentId.canConvertToLong()
            ) return false
            candidate = parentId.longValue()
        }
        return candidate == callStudyId
    }

    private suspend fun studyIsWithinCallTree(context: VoiceTutorWebRtcControlContext, studyId: Long): Boolean {
        val selectedId = currentStudyAnchor(context)?.takeIf { it > 0 } ?: return false
        val readStudy = specifications["get_study"] ?: return false
        // Per-operation only: every node comes from an owned read in this authorization
        // generation. Shared ancestors are not fetched twice, and no session snapshot is
        // mutated just to browse history. Missing ancestry is never treated as a root.
        val parents = mutableMapOf<Long, Long?>()
        suspend fun rootOf(start: Long): Long? {
            var candidate = start
            val visited = mutableSetOf<Long>()
            repeat(MAX_ANCESTOR_READS) {
                if (candidate <= 0 || !visited.add(candidate) || !isAuthorized(context)) return null
                if (!parents.containsKey(candidate)) {
                    val result = invoke(
                        context.principal!!,
                        readStudy,
                        mapOf("study_id" to candidate, "language" to context.session.language),
                    )
                    if (result.isError() == true || result.structuredContent() == null) return null
                    val node = objectMapper.valueToTree<JsonNode>(result.structuredContent())
                    val returnedId = node.path("id")
                    val parentId = node.path("parentStudyId")
                    if (!returnedId.isIntegralNumber || !returnedId.canConvertToLong() ||
                        returnedId.longValue() != candidate || parentId.isMissingNode ||
                        (!parentId.isNull && (!parentId.isIntegralNumber || !parentId.canConvertToLong() || parentId.longValue() <= 0))
                    ) return null
                    parents[candidate] = if (parentId.isNull) null else parentId.longValue()
                }
                val parent = parents[candidate] ?: return candidate
                candidate = parent
            }
            return null
        }
        var selectedRoot = rootOf(selectedId)
        if (selectedRoot == null && persistence.findSession(context.session.userId, context.session.id)?.studyId == null) {
            // Deleting the live selection must not revoke the call or grant another
            // tree. Its captured parent path may identify a still-owned surviving root.
            val saved = studyContexts.list(context.session.userId, context.session.id)
            val tree = VoiceTutorLessonTreeContext.metadata(selectedId, currentSnapshots(saved))
            val rootId = (tree["selectedRootStudyId"] as? Number)?.toLong()
            if (tree["selectedPathComplete"] == true && rootId != null && rootOf(rootId) == rootId) selectedRoot = rootId
        }
        selectedRoot ?: return false
        return rootOf(studyId) == selectedRoot
    }

    private fun learningScopeDenied() = failure(
        "STUDY_SCOPE_DENIED",
        "Learning history is available only for verified owned nodes in this call's study tree. " +
            "An unavailable node or unresolved ancestry cannot change the lesson scope.",
    )

    private suspend fun invoke(
        principal: Principal,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val transportContext = McpTransportContext.create(mapOf(
            BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to principal,
            BuddyStudyMcpPort.SUPPRESS_EXCHANGE_LOG_CONTEXT_KEY to true,
        ))
        val request = McpSchema.CallToolRequest.builder(specification.tool().name()).arguments(arguments).build()
        return specification.callHandler().apply(transportContext, request).awaitSingle()
    }

    private fun voiceDeletionSchema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = original["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return original + mapOf(
            "properties" to linkedMapOf(
                "study_id" to properties["study_id"],
                "confirm" to mapOf("type" to "boolean", "description" to "The server sets true only after fresh natural agreement to the exact completed deletion question; never request another confirmation or originate this call yourself."),
            ),
        )
    }

    private suspend fun createStudyTopic(
        context: VoiceTutorWebRtcControlContext,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
        realtime: RealtimeMutation? = null,
    ): VoiceTutorMcpToolResult {
        val parentStudyId = (arguments["parent_study_id"] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: return failure("INVALID_ARGUMENTS", "Choose one exact positive parent_study_id.")
        val topic = (arguments["topic"] as? String)?.takeIf {
            it.isNotBlank() && it == it.trim() && it.length <= 255
        } ?: return failure("INVALID_ARGUMENTS", "Choose one exact child topic containing 1 to 255 characters.")
        val difficulty = (arguments["difficulty_level"] as? Number)?.toInt() ?: DEFAULT_ROOT_DIFFICULTY
        if (difficulty !in 1..10 || arguments.keys.any {
                it !in setOf("parent_study_id", "topic", "difficulty_level")
            }
        ) return failure("INVALID_ARGUMENTS", "Use only the exact assessed parent, topic, and level.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (realtime == null && rootStudyLearnerTurnId(context, revision, VoiceTutorInputIntent.CREATE_STUDY_TOPIC) == null) {
            return failure(
                "CHILD_CREATION_REQUEST_REQUIRED",
                "Wait for one newly persisted direct learner choice of an exact child and parent; no write was started.",
            )
        }
        val lease = context.dialogueBoundary?.childStudyCreationAuthorization
        if (realtime == null && (lease == null || lease.parentStudyId != parentStudyId || lease.topic != topic ||
            lease.difficulty != difficulty)
        ) {
            return failure(
                "CHILD_CREATION_REQUEST_MISMATCH",
                "Use the exact child, parent, and level from the persisted learner choice; no write was started.",
            )
        }
        if (realtime == null && !parentIsWithinCallStudy(context, parentStudyId)) {
            return if (!isAuthorized(context)) inactiveCall() else failure(
                "STUDY_SCOPE_DENIED",
                "Choose the current call's study or one of its verified descendants as the parent; no write was started.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (realtime == null && lease?.consume() != true) {
            return failure(
                "CHILD_CREATION_REQUEST_REQUIRED",
                "This exact child choice was already used or revoked; wait for a fresh direct learner choice.",
            )
        }
        val exactArguments = linkedMapOf<String, Any>(
            "parent_study_id" to parentStudyId,
            "topic" to topic,
            "difficulty_level" to difficulty,
        )
        if (realtime != null && (!realtimeWriteStillCurrent(context, realtime) ||
                readRealtimeTarget(context, parentStudyId) != realtime.target)) return failure("MUTATION_TARGET_STALE", "The parent or current request changed; no child was created.")
        if (realtime != null) {
            if (originalRoot(context, parentStudyId)?.difficulty != difficulty)
                return failure("MUTATION_TARGET_STALE", "The original main study level changed; prepare the topic again.")
            exactArguments[BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT] = true
        }
        val result = invoke(requireNotNull(context.principal), specification, exactArguments)
        if (result.isError() == true) return boundedResult(result, CREATE_TOPIC)
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        val createdId = payload?.path("id")?.let(::positiveId)
        val created = payload?.path("created")?.takeIf { it.isBoolean }?.booleanValue()
        val returnedTopic = payload?.path("topic")?.takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 255 }
        val returnedDifficulty = payload?.path("difficultyLevel")?.takeIf {
            it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 1..10
        }?.intValue()
        val exactCreatedTuple = created == true && returnedTopic == topic && returnedDifficulty == difficulty
        val exactExistingIdentity = created == false && returnedTopic != null && returnedDifficulty != null &&
            normalizedStudyTopicIdentity(returnedTopic) == normalizedStudyTopicIdentity(topic)
        if (payload == null || createdId == null || positiveId(payload.path("parentStudyId")) != parentStudyId ||
            created == null || (!exactCreatedTuple && !exactExistingIdentity)
        ) {
            return failure(
                "CHILD_CREATION_RESULT_UNCONFIRMED",
                "The child write result did not confirm the exact parent, topic, and level. Inspect the study tree; never retry this write automatically.",
            )
        }
        val bounded = boundedResult(result, CREATE_TOPIC)
        val truthful = if (created) bounded else bounded.copy(
            output = existingChildAvailableOutput(bounded.output, returnedDifficulty!!),
            studyTreeChanged = false,
            createdStudyId = null,
            changedStudyId = null,
            changeKind = null,
        )
        val verifiedChild = truthful.copy(savedMutationTarget = VoiceTutorStudyTargetCandidate(
            studyId = createdId,
            parentStudyId = parentStudyId,
            topic = requireNotNull(returnedTopic),
            difficulty = requireNotNull(returnedDifficulty),
        ).takeUnless { truthful.isError })
        return withLessonContext(context, verifiedChild, CREATE_TOPIC, exactArguments)
    }

    private fun existingChildAvailableOutput(output: String, difficulty: Int): String {
        val payload = runCatching { objectMapper.readTree(output) as? ObjectNode }.getOrNull() ?: return output
        payload.put(
            "notice",
            "This child was already available; its saved level $difficulty was preserved. Do not claim that a new topic was created.",
        )
        val bytes = objectMapper.writeValueAsBytes(payload)
        return if (bytes.size <= MAX_OUTPUT_BYTES) String(bytes, Charsets.UTF_8) else output
    }

    private fun normalizedStudyTopicIdentity(value: String): String = buildString(value.length) {
        var pendingSpace = false
        for (character in value.trim().lowercase()) {
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }

    private suspend fun updateStudy(
        context: VoiceTutorWebRtcControlContext,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
        realtime: RealtimeMutation? = null,
    ): VoiceTutorMcpToolResult {
        val studyId = (arguments["study_id"] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: return failure("INVALID_ARGUMENTS", "Choose one exact positive study_id.")
        val topic = (arguments["topic"] as? String)?.takeIf {
            it.isNotBlank() && it == it.trim() && it.length <= 255
        }
        val difficulty = (arguments["difficulty_level"] as? Number)?.toInt()
        val suppliedPatchKeys = arguments.keys - "study_id"
        if (topic == null && difficulty == null ||
            ("topic" in arguments && topic == null) ||
            ("difficulty_level" in arguments && difficulty == null) ||
            difficulty?.let { it !in 1..10 } == true ||
            suppliedPatchKeys != buildSet {
                if (topic != null) add("topic")
                if (difficulty != null) add("difficulty_level")
            }
        ) return failure("INVALID_ARGUMENTS", "Use only the exact assessed study name and/or level patch.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (realtime == null && rootStudyLearnerTurnId(context, revision, VoiceTutorInputIntent.UPDATE_STUDY) == null) {
            return failure(
                "STUDY_UPDATE_REQUEST_REQUIRED",
                "Wait for one newly persisted direct learner choice of an exact saved-node patch; no write was started.",
            )
        }
        val lease = context.dialogueBoundary?.studyUpdateAuthorization
        val targetProof = lease?.targetProof
        val authorizedPatchKeys = buildSet {
            if (lease?.topic != null) add("topic")
            if (lease?.difficulty != null) add("difficulty_level")
        }
        if (realtime == null && (lease == null || lease.studyId != studyId || lease.topic != topic || lease.difficulty != difficulty ||
            suppliedPatchKeys != authorizedPatchKeys || lease.scope == null || targetProof == null ||
            targetProof.studyId != studyId)
        ) {
            return failure(
                "STUDY_UPDATE_REQUEST_MISMATCH",
                "Use the exact saved node and patch from the persisted learner choice; no write was started.",
            )
        }
        if (realtime == null && lease?.isActive() != true) {
            return failure(
                "STUDY_UPDATE_REQUEST_REQUIRED",
                "This exact saved-node patch was already used or revoked; wait for a fresh direct learner choice.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        val authorizedScope = realtime != null || when (lease?.scope) {
            null -> false
            VoiceTutorStudyUpdateAuthorizationScope.CONFIRMED_FOCUS_TREE ->
                studyIsWithinCallTree(context, studyId)
            VoiceTutorStudyUpdateAuthorizationScope.OFFERED_CANDIDATE,
            VoiceTutorStudyUpdateAuthorizationScope.OWNER_READ,
            VoiceTutorStudyUpdateAuthorizationScope.INITIAL_OWNER_SNAPSHOT -> true
        }
        if (!authorizedScope) {
            return if (!isAuthorized(context)) inactiveCall() else failure(
                "STUDY_SCOPE_DENIED", "Change only the exact frozen owned node in this call's verified study tree.",
            )
        }
        val readStudy = specifications["get_study"] ?: return failure(
            "LESSON_CONTEXT_UNAVAILABLE",
            "The saved node could not be safely read before this update. No update was started.",
        )
        val readResult = invoke(
            requireNotNull(context.principal),
            readStudy,
            mapOf("study_id" to studyId, "language" to context.session.language),
        )
        if (!isAuthorized(context)) return inactiveCall()
        val liveTarget = readResult.takeIf { it.isError() != true }?.structuredContent()
            ?.let { objectMapper.valueToTree<JsonNode>(it) }
            ?.let(::verifiedUpdateTarget)
        val liveDifficulty = liveTarget?.difficulty
        if (liveTarget == null || liveDifficulty == null ||
            (if (realtime != null) realtime.target != liveTarget else targetProof?.matches(liveTarget) != true)
        ) {
            return failure(
                "STUDY_UPDATE_TARGET_STALE",
                "The exact saved node changed after it was offered or assessed. Read and speak it again before a fresh update.",
            )
        }
        val prepared = studyContexts.remember(context.session.userId, context.session.id, listOf(studyId))
        val baseline = prepared.singleOrNull { it.studyId == studyId }
        val revisionAfterBaseline = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (baseline == null || revisionAfterBaseline >= 32 || revisionAfterBaseline != revision) {
            return failure(
                "LESSON_CONTEXT_UNAVAILABLE",
                "The change could not be safely prepared in this call. No update was started.",
            )
        }
        if (if (realtime != null) !realtime.matches(baseline) else targetProof?.matches(baseline) != true) {
            return failure(
                "STUDY_UPDATE_TARGET_STALE",
                "The lesson's saved-node baseline no longer matches the exact offered identity. Read and speak it again before a fresh update.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (realtime == null && lease?.consume() != true) {
            return failure(
                "STUDY_UPDATE_REQUEST_REQUIRED",
                "This exact saved-node patch was already used or revoked; wait for a fresh direct learner choice.",
            )
        }
        val exactArguments = linkedMapOf<String, Any>("study_id" to studyId)
        topic?.let { exactArguments["topic"] = it }
        difficulty?.let { exactArguments["difficulty_level"] = it }
        // The public provider schema never exposes these fields. The local MCP
        // handler carries the last owner-read identity into the same owner-lock
        // transaction as the write, closing the read/write race.
        exactArguments[BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT] = liveTarget.topic
        exactArguments[BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT] = liveDifficulty
        exactArguments[BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT] = liveTarget.parentStudyId ?: 0L
        if (realtime != null && !realtimeWriteStillCurrent(context, realtime)) return failure("MUTATION_TARGET_STALE", "The current request changed; no update was started.")
        val result = invoke(requireNotNull(context.principal), specification, exactArguments)
        if (result.isError() == true) return boundedResult(result, UPDATE_STUDY)
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        val confirmedTopic = payload?.path("topic")?.takeIf { it.isTextual }?.textValue()
        val confirmedDifficulty = payload?.path("difficultyLevel")
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        val confirmedParent = payload?.path("parentStudyId")?.let { node ->
            when {
                node.isNull -> null
                else -> positiveId(node) ?: Long.MIN_VALUE
            }
        }
        if (payload == null || positiveId(payload.path("id")) != studyId ||
            confirmedTopic != (topic ?: baseline.topic) ||
            confirmedDifficulty != (difficulty ?: baseline.difficulty) ||
            confirmedParent != baseline.parentStudyId
        ) {
            return failure(
                "UPDATE_RESULT_UNCONFIRMED",
                "The saved write result did not confirm the exact node and patch. Inspect saved studies; never retry this write automatically.",
            )
        }
        return withLessonContext(context, boundedResult(result, UPDATE_STUDY), UPDATE_STUDY, exactArguments)
    }

    private suspend fun createRootStudy(
        context: VoiceTutorWebRtcControlContext,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
        realtime: RealtimeMutation? = null,
    ): VoiceTutorMcpToolResult {
        val topic = (arguments["topic"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 255 }
            ?: return failure("INVALID_ARGUMENTS", "Choose one root topic containing 1 to 255 characters.")
        val difficulty = (arguments["difficulty_level"] as? Number)?.toInt() ?: DEFAULT_ROOT_DIFFICULTY
        if (difficulty !in 1..10) return failure("INVALID_ARGUMENTS", "Choose a root difficulty from 1 to 10.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (realtime == null && rootStudyLearnerTurnId(
            context,
            revision,
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
        ) == null) {
            return failure(
                "ROOT_CREATION_REQUEST_REQUIRED",
                "Wait for a newly persisted direct learner choice to begin this new saved root; imperative grammar is not required, but a topic mention or tool text is not permission.",
            )
        }
        val creationAuthorization = context.dialogueBoundary?.rootStudyCreationAuthorization
        if (realtime == null && (creationAuthorization == null || creationAuthorization.topic != topic ||
            creationAuthorization.difficulty != difficulty)
        ) {
            return failure(
                "ROOT_CREATION_REQUEST_MISMATCH",
                "Use the exact root topic and level from the persisted learner choice; no write was started.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (realtime == null && creationAuthorization?.consume() != true) {
            return failure(
                "ROOT_CREATION_REQUEST_REQUIRED",
                "A newer learner turn revoked this root choice before the write began; no write was started. Wait for a fresh direct choice to begin a new saved root.",
            )
        }
        if (realtime != null && !realtimeWriteStillCurrent(context, realtime)) return failure("MUTATION_TARGET_STALE", "The current request changed; no root was created.")
        val result = invoke(
            requireNotNull(context.principal),
            specification,
            mapOf("topic" to topic, "difficulty_level" to difficulty),
        )
        if (result.isError() == true) return boundedResult(result, CREATE_ROOT)
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        val id = payload?.path("id")?.let(::positiveId)
        val created = payload?.path("created")?.takeIf { it.isBoolean }?.booleanValue()
        val parent = payload?.path("parentStudyId")
        val returnedTopic = payload?.path("topic")?.takeIf { it.isTextual }?.textValue()
        val returnedDifficulty = payload?.path("difficultyLevel")?.takeIf {
            it.isIntegralNumber && it.canConvertToInt()
        }?.intValue()
        val enabled = payload?.path("enabled")?.takeIf { it.isBoolean }?.booleanValue()
        val activeForQuestions = payload?.path("activeForQuestions")?.takeIf { it.isBoolean }?.booleanValue()
        if (created == null) return failure(
            "ROOT_CREATION_RESULT_UNCONFIRMED",
            "The root write result could not be verified. Read saved studies before any retry; do not repeat this write automatically.",
        )
        val metadataMatchesCreateOnlyContract = if (created) {
            returnedTopic == topic && returnedDifficulty == difficulty
        } else {
            returnedTopic != null &&
                normalizedRootTopicKey(returnedTopic) == normalizedRootTopicKey(topic) &&
                returnedDifficulty != null && returnedDifficulty in 1..10
        }
        if (id == null || parent == null || !parent.isNull || !metadataMatchesCreateOnlyContract ||
            enabled == null || activeForQuestions == null
        ) return failure(
            "ROOT_CREATION_RESULT_UNCONFIRMED",
            "The root write result could not be verified. Read saved studies before any retry; do not repeat this write automatically.",
        )
        val compoundStartPending = realtime == null && creationAuthorization?.startLessonAfterCreate == true
        val cleanResult = VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "created" to created,
                "id" to id,
                "parentStudyId" to null,
                "topic" to returnedTopic,
                "difficultyLevel" to returnedDifficulty,
                "enabled" to enabled,
                "activeForQuestions" to activeForQuestions,
                "voiceLessonContextReady" to false,
                "voiceLessonChangeApplies" to if (compoundStartPending) {
                    "AUTO_FOCUS_PENDING"
                } else {
                    "REQUIRES_SELECTION"
                },
                "notice" to when {
                    realtime != null -> "The saved root is available. If the learner already chose to study it, call select_voice_study with this exact id and begin at the returned saved level; do not ask another confirmation. Creation alone does not change focus."
                    compoundStartPending && created ->
                        "The root was created and the same persisted learner turn independently authorized starting it now. This result is not yet a lesson focus: do not ask for agreement again, do not originate or retry a pipeline tool, and wait for the server-owned exact readback and CREATED_ROOT_IMMEDIATE_START selection before asking the first question."
                    compoundStartPending ->
                        "The exact root already existed unchanged and the same persisted learner turn independently authorized starting it now. This result is not yet a lesson focus: do not ask for agreement again, do not originate or retry a pipeline tool, and wait for the server-owned exact readback and CREATED_ROOT_IMMEDIATE_START selection before asking the first question."
                    created ->
                        "The root was created but is not the lesson focus. Read it with get_study, speak that exact saved root, and wait for a NEW agreement to start before select_voice_study."
                    else ->
                        "An exact root already existed and was returned unchanged. Read it with get_study, speak that saved root, and wait for a NEW agreement before select_voice_study."
                },
            )),
            isError = false,
            studyTreeChanged = created,
            createdStudyId = id.takeIf { created },
            changedStudyId = id.takeIf { created },
            changeKind = VoiceTutorStudyChangeKind.CREATED.takeIf { created },
            rootStudyReadbackId = id,
        )
        return if (created) withLessonContext(context, cleanResult, CREATE_ROOT, arguments) else cleanResult
    }

    private suspend fun rootStudyLearnerTurnId(
        context: VoiceTutorWebRtcControlContext,
        currentRevision: Long,
        expectedIntent: VoiceTutorInputIntent,
    ): Long? {
        val dialogue = context.dialogueBoundary ?: return null
        val providerItemId = dialogue.latestAcceptedLearnerProviderItemId
            ?.takeIf { it.isNotBlank() && it.length <= 191 } ?: return null
        if (currentRevision < 0 || dialogue.latestAcceptedLearnerLessonRevision != currentRevision ||
            dialogue.latestAcceptedLearnerIntent != expectedIntent
        ) return null
        return confirmations.learnerTurnAuthorization(
            context.session.userId,
            context.session.id,
            providerItemId,
            currentRevision,
            dialogue.precedingQuestionProviderItemId,
            dialogue.precedingAnswerProviderItemId,
            dialogue.precedingTutorFeedbackProviderItemId,
            dialogue.precedingTutorNavigationOfferProviderItemId,
        )?.turnId?.takeIf { it > 0 }
    }

    private fun normalizedRootTopicKey(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")

    private suspend fun deleteStudy(
        context: VoiceTutorWebRtcControlContext,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
        studyId: Long,
        realtime: RealtimeMutation? = null,
    ): VoiceTutorMcpToolResult {
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        val lease = context.dialogueBoundary?.studyDeletionAuthorization
        if (realtime == null && (arguments["confirm"] != true || lease == null || lease.studyId != studyId || !lease.isActive() ||
            rootStudyLearnerTurnId(context, revision, VoiceTutorInputIntent.DELETE_STUDY) == null)
        ) return failure("DELETE_REQUEST_REQUIRED", "No authorized deletion was executed. Do not claim success or ask for special wording.")
        val preview = deletionPreview(context, studyId) ?: return failure(
            "DELETE_PREVIEW_UNAVAILABLE", "This subtree could not be checked completely; nothing was deleted.",
        )
        if (if (realtime != null) realtime.target != preview.target || realtime.deletedIds != preview.ids.toSet()
            else lease?.targetProof?.matches(preview.target) != true) {
            return failure("DELETE_TARGET_STALE", "The saved target changed; nothing was deleted.")
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (studyContexts.currentRevision(context.session.userId, context.session.id) != revision ||
            (realtime == null && lease?.consume() != true)) {
            return failure("DELETE_REQUEST_REQUIRED", "This deletion was superseded or already processed; do not repeat it.")
        }
        // The controller issues this lease only after fresh agreement to its frozen proposal. Resolve the
        // whole owned subtree internally; compare its membership in the deletion transaction.
        val selectedId = currentStudyAnchor(context)
        if (realtime != null && !realtimeWriteStillCurrent(context, realtime)) return failure("MUTATION_TARGET_STALE", "The current request changed; nothing was deleted.")
        val result = invoke(context.principal!!, specification, mapOf(
            "study_id" to studyId, "confirm" to true, "expected_study_ids" to preview.ids,
        ))
        if (result.isError() == true) return boundedResult(result, DELETE_STUDY)
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        if (payload?.path("deleted")?.takeIf { it.isBoolean }?.booleanValue() != true ||
            positiveId(payload.path("studyId")) != studyId
        ) {
            return failure("DELETE_RESULT_UNCONFIRMED", "The deletion result is uncertain. Read saved studies before any retry; never repeat the write automatically.")
        }
        return VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "deleted" to true, "studyId" to studyId, "topic" to preview.target.topic,
                "deletedStudyIds" to preview.ids, "voiceLessonDeletedStudyIds" to preview.ids,
                "voiceLessonSelectionDeleted" to (selectedId in preview.ids),
                "notice" to "Briefly acknowledge that this named topic was deleted. Do not mention servers, permissions, tokens or internal checks. Existing learning records remain. Stay connected; do not teach on deleted nodes.",
            )),
            isError = false, studyTreeChanged = true, changedStudyId = studyId,
            changeKind = VoiceTutorStudyChangeKind.DELETED, deletedStudyIds = preview.ids,
            lessonFocusCleared = selectedId in preview.ids,
        )
    }

    private suspend fun deletionPreview(context: VoiceTutorWebRtcControlContext, studyId: Long): DeletionPreview? {
        val readStudy = specifications["get_study"] ?: return null
        val listStudies = specifications["list_studies"] ?: return null
        val rootResult = invoke(context.principal!!, readStudy, mapOf("study_id" to studyId, "language" to context.session.language))
        if (rootResult.isError() == true) return null
        val root = rootResult.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) } ?: return null
        if (positiveId(root.path("id")) != studyId || !root.path("topic").isTextual) return null
        val ids = linkedSetOf(studyId)
        val queue = ArrayDeque<Long>().apply { add(studyId) }
        while (queue.isNotEmpty()) {
            if (!isAuthorized(context)) return null
            val parent = queue.removeFirst()
            val result = invoke(context.principal!!, listStudies, mapOf(
                "parent_study_id" to parent, "limit" to 129, "offset" to 0, "language" to context.session.language,
            ))
            if (result.isError() == true) return null
            val page = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) } ?: return null
            val children = page.path("studies")
            val count = page.path("totalCount")
            if (!children.isArray || !count.isIntegralNumber || !count.canConvertToLong() ||
                count.longValue() != children.size().toLong() || children.size() + ids.size > 128 ||
                !page.path("offset").isIntegralNumber || page.path("offset").asLong() != 0L
            ) return null
            for (child in children) {
                val id = positiveId(child.path("id")) ?: return null
                if (positiveId(child.path("parentStudyId")) != parent || !ids.add(id)) return null
                queue.add(id)
            }
        }
        return DeletionPreview(verifiedUpdateTarget(root) ?: return null, ids.toList())
    }

    private fun mutationResult(output: String, isError: Boolean, changed: Boolean, id: Long?, toolName: String) =
        VoiceTutorMcpToolResult(
            output, isError, changed, createdStudyId = id.takeIf { toolName in CREATION_TOOLS }, changedStudyId = id,
            changeKind = if (!changed || id == null) null else if (toolName == UPDATE_STUDY) VoiceTutorStudyChangeKind.UPDATED else VoiceTutorStudyChangeKind.CREATED,
        )

    private fun currentSnapshots(snapshots: List<VoiceTutorStudySnapshot>) = snapshots.groupBy { it.studyId }
        .values.map { versions -> versions.maxBy { it.revision } }

    private fun positiveId(node: JsonNode): Long? = node.takeIf {
        it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0
    }?.longValue()

    private data class DeletionPreview(val target: VoiceTutorStudyTargetCandidate, val ids: List<Long>)

    private fun boundedNativeStudyDiscovery(result: McpSchema.CallToolResult): VoiceTutorMcpToolResult {
        if (result.isError() == true) return boundedResult(result, "list_studies")
        val source = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        val studies = source?.path("studies")
        if (source?.isObject != true || studies?.isArray != true || studies.any { !it.isObject }) {
            return failure("INVALID_TOOL_RESULT", "The saved study page is unavailable. Read the exact owned topic again before selecting it.")
        }
        // Browse complete rows using only discovery columns. Do not let embedded
        // questions, answer hints or long custom prompts crowd out saved topics.
        // Candidate authorization still validates the original result independently.
        val compact = objectMapper.createObjectNode()
        val nodes = compact.putArray("studies")
        studies.forEach { study ->
            val node = nodes.addObject()
            STUDY_DISCOVERY_FIELDS.forEach { field -> study.get(field)?.let { node.set<JsonNode>(field, it) } }
        }
        listOf("totalCount", "limit", "offset").forEach { field ->
            source.get(field)?.let { compact.set<JsonNode>(field, it) }
        }
        val bytes = objectMapper.writeValueAsBytes(compact)
        if (bytes.size > MAX_OUTPUT_BYTES) {
            return failure("RESULT_TOO_LARGE", "The complete study discovery page exceeds the voice limit. Request a smaller page or a more specific study/topic filter; no rows were omitted.")
        }
        return VoiceTutorMcpToolResult(String(bytes, Charsets.UTF_8), false)
    }

    private fun boundedResult(result: McpSchema.CallToolResult, toolName: String): VoiceTutorMcpToolResult {
        val isError = result.isError() == true
        val changed = !isError && toolName in (CREATION_TOOLS + UPDATE_STUDY)
        val payload = result.structuredContent() ?: mapOf("content" to result.content())
        val changedStudyId = if (changed) {
            objectMapper.valueToTree<JsonNode>(payload).path("id")
                .takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0 }
                ?.longValue()
        } else {
            null
        }
        val bytes = objectMapper.writeValueAsBytes(payload)
        if (bytes.size <= MAX_OUTPUT_BYTES) {
            return mutationResult(String(bytes, Charsets.UTF_8), isError, changed, changedStudyId, toolName)
        }
        if (changed) {
            // An existing child can carry a large inherited prompt. Keep the verified
            // mutation result, not an error that falsely implies nothing was created.
            val source = objectMapper.valueToTree<JsonNode>(payload)
            val compact = objectMapper.createObjectNode()
            for (field in CREATED_TOPIC_FIELDS) {
                source.get(field)?.let { compact.set<JsonNode>(field, it) }
            }
            compact.put("truncated", true)
            compact.put("notice", "The study change succeeded. Only study-tree fields are included in this voice result.")
            val compactBytes = objectMapper.writeValueAsBytes(compact)
            if (compactBytes.size <= MAX_OUTPUT_BYTES) {
                return mutationResult(String(compactBytes, Charsets.UTF_8), false, true, changedStudyId, toolName)
            }
        }
        return failure(
            "RESULT_TOO_LARGE",
            "The result exceeds the voice tool limit. Request a smaller page or a more specific study/topic filter. " +
                "The result was not cut into partial JSON.",
        ).copy(studyTreeChanged = changed, createdStudyId = changedStudyId.takeIf { toolName in CREATION_TOOLS }, changedStudyId = changedStudyId)
    }

    private suspend fun withLessonContext(
        context: VoiceTutorWebRtcControlContext,
        result: VoiceTutorMcpToolResult,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        if (result.isError || toolName !in STUDY_CONTEXT_TOOLS) return result
        val payload = objectMapper.readTree(result.output) as? ObjectNode ?: return result
        val nodes = if (toolName == "list_studies") payload.path("studies").toList() else listOf(payload)
        val ids = nodes.take(32).mapNotNull { node ->
            node.path("id").takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0 }
                ?.longValue()
        }.distinct()
        if (ids.isEmpty()) return result
        val selectedId = currentStudyAnchor(context)
        if ((selectedId == null && toolName != CREATE_ROOT && toolName != UPDATE_STUDY) ||
            (toolName == "list_studies" && "parent_study_id" !in arguments)
        ) {
            // Discovery pages are browsing, not lesson consent or metadata reservations.
            // Searching a large catalog must not exhaust the bounded lesson snapshot cache.
            payload.put("voiceLessonContextReady", false)
            payload.set<JsonNode>("voiceLessonTopics", objectMapper.createArrayNode())
            payload.put("notice", "These are saved browsing results, not a selected lesson. Resolve the learner's chosen exact node and call select_voice_study before teaching it; do not guess a level or create a duplicate.")
            val bytes = objectMapper.writeValueAsBytes(payload)
            return if (bytes.size <= MAX_OUTPUT_BYTES) result.copy(output = String(bytes, Charsets.UTF_8))
                else failure("RESULT_TOO_LARGE", "Request a smaller saved-study page.")
        }
        var snapshots = emptyList<VoiceTutorStudySnapshot>()
        var savedTree = emptyList<VoiceTutorStudySnapshot>()
        try {
            snapshots = when (toolName) {
                UPDATE_STUDY -> studyContexts.revise(context.session.userId, context.session.id, ids.single()).filter { it.studyId in ids }
                CREATE_ROOT, CREATE_TOPIC -> studyContexts.remember(context.session.userId, context.session.id, ids)
                // Read-only browsing never reserves snapshot slots, even after a focus
                // has been selected. Only an explicit selection or saved edit freezes
                // new nodes; inspecting a large tree must not exhaust the lesson cache.
                else -> emptyList()
            }
            savedTree = currentSnapshots(studyContexts.list(context.session.userId, context.session.id))
            if (toolName != UPDATE_STUDY && toolName !in CREATION_TOOLS) snapshots = savedTree.filter { it.studyId in ids }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The study write already committed. Never turn context-capture failure
            // into a false failed write or replay a possibly successful mutation.
            logger.warn("voice_tutor_study_context_capture_failed errorType={}", error.javaClass.simpleName)
        }
        val frozenIds = snapshots.mapTo(mutableSetOf()) { it.studyId }
        val updatedUnselectedCandidate = toolName == UPDATE_STUDY &&
            (selectedId == null || ids.singleOrNull() != selectedId)
        val updatedReadback = if (toolName == UPDATE_STUDY) {
            nodes.singleOrNull()?.let(::verifiedUpdateTarget)
        } else {
            null
        }
        val updatedStudySnapshot = if (toolName == UPDATE_STUDY && ids.size == 1) {
            snapshots.singleOrNull { it.studyId == ids.single() }?.takeIf { snapshot ->
                val readback = updatedReadback ?: return@takeIf false
                readback.difficulty != null && readback.studyId == snapshot.studyId &&
                    readback.parentStudyId == snapshot.parentStudyId && readback.topic == snapshot.topic &&
                    readback.difficulty == snapshot.difficulty
            }
        } else {
            null
        }
        val acceptedLearnerRevision = context.dialogueBoundary?.latestAcceptedLearnerLessonRevision ?: -1
        val updateAutoFocusPending = updatedStudySnapshot != null &&
            context.dialogueBoundary?.studyUpdateAuthorization?.startLessonAfterUpdate == true &&
            acceptedLearnerRevision >= 0 && updatedStudySnapshot.revision > acceptedLearnerRevision
        val currentView = currentSnapshots(savedTree + snapshots)
        val updatedRevision = updatedStudySnapshot?.revision
        val updatedFocus = if (updatedRevision != null &&
            (!updatedUnselectedCandidate || selectedId != null && !updateAutoFocusPending)
        ) {
            currentView.singleOrNull { it.studyId == selectedId }
                ?.takeIf { it.revision <= updatedRevision }
                ?.let { snapshot ->
                    // Revising a different node advances the call's metadata epoch,
                    // not its selected lesson. Keep that lesson's frozen name/level;
                    // do not select the edited node or rewrite historical snapshots.
                    VoiceTutorLessonFocusSelection(
                        focus = VoiceTutorLessonFocus(snapshot.studyId, updatedRevision),
                        snapshot = snapshot.copy(revision = updatedRevision),
                        lessonRevision = updatedRevision,
                    )
                }
        } else null
        payload.put(
            "voiceLessonContextReady",
            toolName != CREATE_ROOT && !updatedUnselectedCandidate &&
                nodes.size <= 32 && ids.all { it in frozenIds },
        )
        if (toolName == CREATE_ROOT) {
            val compoundStartPending = context.dialogueBoundary
                ?.rootStudyCreationAuthorization?.startLessonAfterCreate == true
            payload.put(
                "voiceLessonChangeApplies",
                if (compoundStartPending) "AUTO_FOCUS_PENDING" else "REQUIRES_SELECTION",
            )
            payload.put(
                "notice",
                if (context.realtimeModelTools) {
                    "The root was saved. If the learner already chose to study it, call select_voice_study with this exact returned id and begin at its verified saved level without another confirmation. Creation alone does not change the current focus."
                } else if (compoundStartPending) {
                    "The root was saved and the same persisted learner turn independently authorized starting it now. This result is not yet a lesson focus: do not ask for agreement again, do not originate or retry a pipeline tool, and wait for the server-owned exact readback and CREATED_ROOT_IMMEDIATE_START selection before asking the first question."
                } else {
                    "The root was saved but is not a lesson focus. Read this exact root, speak it, and wait for a new learner agreement before select_voice_study; creation itself never starts teaching."
                },
            )
        }
        if (toolName == UPDATE_STUDY) {
            payload.put(
                "voiceLessonChangeApplies",
                when {
                    updatedStudySnapshot == null -> "NOT_PREPARED"
                    updateAutoFocusPending -> "AUTO_FOCUS_PENDING"
                    updatedUnselectedCandidate -> "REQUIRES_SELECTION"
                    else -> "NEXT_QUESTION"
                },
            )
            payload.put(
                "notice",
                when {
                    context.realtimeModelTools && updatedStudySnapshot == null ->
                        "The settings were saved, but the new lesson metadata is not ready. Do not repeat the write or teach from stale settings; read the exact saved topic before selecting it."
                    context.realtimeModelTools && updatedUnselectedCandidate ->
                        "The exact saved node was updated. Keep the existing lesson unchanged unless the learner chose to study this node; in that case call select_voice_study on its exact returned id without another confirmation. Never repeat the write."
                    context.realtimeModelTools ->
                        "The saved topic was updated. Use this exact new level and name for subsequent questions only; pending and completed questions retain their original metadata. No new selection or confirmation is needed."
                    updateAutoFocusPending ->
                        "The exact saved node was updated and its revised metadata was captured. Do not speak, ask for agreement, or originate or retry another tool; wait for the server-owned UPDATED_STUDY_IMMEDIATE_START selection before asking the first question."
                    updatedUnselectedCandidate && updatedFocus != null ->
                        "The exact saved node was updated but is not the lesson focus. REQUIRES_SELECTION applies only to studying that edited node; the existing selected lesson stays active with its unchanged name and level. Briefly acknowledge the edit without asking to switch or start again; never repeat the write."
                    updatedUnselectedCandidate ->
                        "The exact offered saved node was updated and its revised metadata was read back, but it is not the lesson focus. Do not ask a study question until the learner freshly selects it; never repeat the write."
                    else ->
                        "Study settings were saved; completed and pending questions keep their original title and level. If lesson context is not ready, do not ask another question on this node or repeat the write."
                },
            )
        }
        payload.set<JsonNode>(
            "voiceLessonTopics",
            objectMapper.valueToTree(snapshots.map { snapshot ->
                linkedMapOf(
                    "studyId" to snapshot.studyId,
                    "parentStudyId" to snapshot.parentStudyId,
                    "topic" to snapshot.topic,
                    "difficulty" to snapshot.difficulty,
                    "revision" to snapshot.revision,
                )
            }),
        )
        payload.set<JsonNode>(
            "voiceLessonTree",
            objectMapper.valueToTree(VoiceTutorLessonTreeContext.metadata(
                selectedId, currentSnapshots(savedTree + snapshots), ids,
            )),
        )
        updatedFocus?.let { payload.set<JsonNode>("voiceLessonFocus", objectMapper.valueToTree(focusMetadata(it))) }
        val revisedResult = result.copy(
            lessonRevision = updatedRevision,
            lessonFocus = updatedFocus,
            updatedStudySnapshot = updatedStudySnapshot,
        )
        val enriched = objectMapper.writeValueAsBytes(payload)
        if (enriched.size <= MAX_OUTPUT_BYTES) return revisedResult.copy(output = String(enriched, Charsets.UTF_8))
        if (result.studyTreeChanged) {
            val compact = objectMapper.createObjectNode()
            for (field in CREATED_TOPIC_FIELDS + listOf("voiceLessonTopics", "voiceLessonContextReady", "voiceLessonTree", "voiceLessonChangeApplies", "voiceLessonFocus")) {
                payload.get(field)?.let { compact.set<JsonNode>(field, it) }
            }
            compact.put("truncated", true)
            compact.put("notice", "Study change succeeded; prior questions keep their original level. Only study-tree and lesson metadata are included.")
            val compactBytes = objectMapper.writeValueAsBytes(compact)
            if (compactBytes.size <= MAX_OUTPUT_BYTES) return revisedResult.copy(output = String(compactBytes, Charsets.UTF_8))
            // Even an unusually large metadata capture must not imply that a
            // successful creation failed or that mutable live levels are frozen.
            return revisedResult.copy(output = objectMapper.writeValueAsString(mapOf(
                "id" to result.changedStudyId,
                "voiceLessonTopics" to emptyList<Any>(),
                "voiceLessonContextReady" to false,
                "voiceLessonTree" to VoiceTutorLessonTreeContext.metadata(selectedId, emptyList()),
                "truncated" to true,
                "notice" to "Study change succeeded; lesson metadata is not ready. Do not repeat the write or start a new question on this node.",
            )))
        }
        return failure("RESULT_TOO_LARGE", "Request a smaller study page to receive its lesson topic and difficulty metadata.")
    }

    private fun inactiveCall() = failure("CALL_NOT_AUTHORIZED", "This call is no longer authorized to use study tools.")

    private fun failure(code: String, message: String) = VoiceTutorMcpToolResult(
        output = objectMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
        isError = true,
    )

    private sealed interface CandidateReadRequest {
        val kind: VoiceTutorCandidateReadKind

        data class ExactStudy(val studyId: Long) : CandidateReadRequest {
            override val kind = VoiceTutorCandidateReadKind.GET_STUDY
        }

        data class Query(val query: String, val offset: Long, val limit: Long) : CandidateReadRequest {
            override val kind = VoiceTutorCandidateReadKind.LIST_STUDIES
        }

        data class DirectChildren(val parentStudyId: Long, val offset: Long, val limit: Long) : CandidateReadRequest {
            override val kind = VoiceTutorCandidateReadKind.LIST_STUDIES
        }
    }

    private data class VerifiedCandidateDiscovery(
        val scope: VoiceTutorCandidateDiscoveryScope,
        val candidates: List<VoiceTutorStudyTargetCandidate>,
    )

    private data class VerifiedCandidatePage(val nodes: List<JsonNode>, val totalCount: Long)

    private companion object {
        const val SELECT_STUDY = "select_voice_study"
        const val ADVANCE_STUDY = "advance_voice_study"
        const val CREATE_ROOT = "create_root_study"
        const val CREATE_TOPIC = "create_study_topic"
        const val UPDATE_STUDY = "update_study"
        const val DELETE_STUDY = "delete_study"
        const val PREPARE_MUTATION = "prepare_voice_study_mutation"
        const val CONFIRM_MUTATION = "confirm_voice_study_mutation"
        val REALTIME_MUTATION_TOOLS = setOf(PREPARE_MUTATION, CONFIRM_MUTATION)
        const val LIST_LEARNING_RECORDS = "list_study_learning_records"
        const val GET_VOICE_LEARNING_RECORD = "get_voice_learning_record"
        const val MAX_ARGUMENT_BYTES = 16 * 1_024
        // Function results are themselves JSON-escaped inside a provider event.
        const val MAX_OUTPUT_BYTES = 16 * 1_024
        const val MAX_ANCESTOR_READS = 32
        const val MAX_TARGET_CANDIDATES = 16
        // Six normal ten-row child pages plus the initial query fit in the
        // seven-round learner-turn budget. Wider trees must be narrowed with a
        // fresh exact query/read before they can be offered.
        const val MAX_DISCOVERY_RESULT_CANDIDATES = 60L
        const val MAX_STUDY_PAGE_LIMIT = 500L
        const val DEFAULT_STUDY_PAGE_LIMIT = 100L
        val ALLOWED_TOOLS = setOf(
            "list_studies", "get_study", CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY,
            "list_records", "get_record", LIST_LEARNING_RECORDS, GET_VOICE_LEARNING_RECORD,
            "get_topic_stats", "get_study_growth", "suggest_study_topics",
            SELECT_STUDY, ADVANCE_STUDY,
        )
        val LEARNING_HISTORY_TOOLS = setOf(LIST_LEARNING_RECORDS, GET_VOICE_LEARNING_RECORD)
        val STUDY_CONTEXT_TOOLS = setOf("list_studies", "get_study", CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY)
        val STUDY_DISCOVERY_FIELDS = listOf("id", "parentStudyId", "topic", "difficultyLevel", "sortOrder", "enabled", "activeForQuestions", "curriculumTerminal")
        val CREATION_TOOLS = setOf(CREATE_ROOT, CREATE_TOPIC)
        val DELETE_ARGUMENTS = setOf("study_id", "confirm")
        val CREATED_TOPIC_FIELDS = listOf(
            "created", "id", "parentStudyId", "topic", "sortOrder", "difficultyLevel", "activeForQuestions", "enabled",
        )
        val VOICE_SENTENCE_TERMINATORS = setOf(
            '.'.code, '?'.code, '!'.code, '\u2026'.code, '\u3002'.code, '\uFF01'.code, '\uFF0E'.code,
            '\uFF1F'.code,
        )
        const val DEFAULT_ROOT_DIFFICULTY = 5
        fun focusParameters(description: String): Map<String, Any?> = mapOf(
            "type" to "object", "additionalProperties" to false,
            "properties" to mapOf(
                "study_id" to mapOf(
                    "type" to "integer", "minimum" to 1, "description" to description,
                ),
            ),
            "required" to listOf("study_id"),
        )
    }
}
