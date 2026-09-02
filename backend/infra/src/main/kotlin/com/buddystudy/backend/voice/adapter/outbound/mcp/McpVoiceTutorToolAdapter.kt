package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorLessonTreeContext
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscovery
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Clock

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
) : VoiceTutorMcpToolPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val validator by lazy { McpJsonSchemaValidatorProvider.create() }
    private val deletionConfirmations = VoiceTutorDeletionConfirmations(clock)
    private val specifications by lazy {
        mcp.tools().filter { it.tool().name() in ALLOWED_TOOLS }.associateBy { it.tool().name() }
    }

    override fun definitions(): List<VoiceTutorMcpToolDefinition> = specifications.values.map { specification ->
        val tool = specification.tool()
        VoiceTutorMcpToolDefinition(
            name = tool.name(),
            description = tool.description().orEmpty() + when (tool.name()) {
                CREATE_ROOT -> " In a voice call this mutation is server-owned: never originate the function call or ask the learner for special wording. The server inserts one exact call only after independently attesting a persisted direct learner choice, including natural first-person new-study intent and an unambiguous topic or level carried from bounded earlier persisted learner speech. Existing roots are returned unchanged, omitted difficulty defaults to 5, and this never selects a lesson or creates a question."
                CREATE_TOPIC -> " In a voice call this mutation is server-owned: never originate the function call or ask the learner for special wording. The server inserts one exact call only after independently attesting the current first-person choice of one exact child under the current confirmed focus or one verified descendant. Mere mentions, examples, recommendations, quoted or third-party wishes, and ambiguous targets are not permission."
                UPDATE_STUDY -> " In a voice call this mutation is server-owned: never originate the function call or ask the learner for special wording. The server inserts one exact update only after independently attesting the learner's current first-person choice of an exact saved node and new topic and/or level. Unspecified fields and past question levels are preserved, while mentions, examples, recommendations, quoted or third-party wishes, and ambiguous targets or outcomes are not permission."
                DELETE_STUDY -> " In a voice call, first call with confirm=false to preview the exact subtree; ask the learner to confirm its name and descendant count, then wait for a new affirmative spoken turn before calling with confirm=true and the returned confirmation_token. Never skip the preview or reuse a token."
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

    override suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        if (toolName !in ALLOWED_TOOLS) return failure("TOOL_NOT_ALLOWED", "This tool is not available in voice calls.")
        try {
            if (objectMapper.writeValueAsBytes(arguments).size > MAX_ARGUMENT_BYTES) {
                return failure("INVALID_ARGUMENTS", "Tool arguments are too large.")
            }
            if (toolName == SELECT_STUDY) return selectStudy(context, arguments)
            if (toolName == ADVANCE_STUDY) return advanceStudy(context, arguments)
            val specification = specifications[toolName]
                ?: return failure("MCP_UNAVAILABLE", "This study tool is currently unavailable.")
            // Use the SDK's existing validator, not its logging wrapper: validation errors
            // can contain private argument values and must never enter application logs.
            if (toolName == DELETE_STUDY && (arguments.keys.any { it !in DELETE_ARGUMENTS } ||
                    ("confirmation_token" in arguments && (arguments["confirmation_token"] !is String ||
                        (arguments["confirmation_token"] as String).length !in 1..100)))
            ) return failure("INVALID_ARGUMENTS", "Deletion requires the exact preview's confirmation token.")
            val mcpArguments = when (toolName) {
                DELETE_STUDY -> arguments - "confirmation_token"
                else -> arguments
            }
            if (!validator.validate(specification.tool().inputSchema(), mcpArguments).valid()) {
                return failure("INVALID_ARGUMENTS", "Arguments do not match this tool's input schema.")
            }
            val candidateReadRequest = candidateReadRequest(toolName, arguments)
            if (!isAuthorized(context)) return inactiveCall()
            if (toolName == CREATE_ROOT) return createRootStudy(context, specification, arguments)
            if (toolName == CREATE_TOPIC) return createStudyTopic(context, specification, arguments)
            if (toolName == UPDATE_STUDY) return updateStudy(context, specification, arguments)
            if (toolName == DELETE_STUDY) {
                val studyId = (arguments.getValue("study_id") as Number).toLong()
                if (!studyIsWithinCallTree(context, studyId)) {
                    return if (!isAuthorized(context)) inactiveCall() else failure(
                        "STUDY_SCOPE_DENIED", "Change only an exact owned node in this call's verified study tree; no change was made.",
                    )
                }
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
            val result = invoke(context.principal!!, specification, arguments)
            val readOnly = toolName !in setOf(CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)
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
            val enriched = withLessonContext(context, boundedResult(result, toolName), toolName, arguments)
            if (readOnly && !isAuthorized(context)) return inactiveCall()
            return attachCandidateDiscovery(
                context, enriched, result, candidateReadRequest, candidateRevisionBefore,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No arguments, account credentials, study contents, or provider payloads in logs.
            return failure("MCP_UNAVAILABLE", "The study tool could not complete. Do not assume the action succeeded.")
        }
    }

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
        val requiredIntent = if (guidedAdvance) {
            VoiceTutorInputIntent.CONTINUE_TREE
        } else {
            VoiceTutorInputIntent.SELECT_SAVED_TOPIC
        }
        val dialogue = context.dialogueBoundary
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
                    "Ask what the learner wants to discuss and wait for their newly persisted explicit topic choice."
                },
            )
        }
        val attestedTarget = dialogue.latestAcceptedLearnerTargetStudyId
        if (attestedTarget == null || dialogue.latestAcceptedLearnerTargetOfferId?.let { it > 0 } != true) {
            return failure(
                if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
                "Wait for the learner to confirm one exact saved topic that was actually offered in this call.",
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
        val providerItemId = requireNotNull(dialogue.latestAcceptedLearnerProviderItemId)
        val attestedCandidate = requireNotNull(dialogue.latestAcceptedLearnerTargetCandidate)
        val attestedTraversal = requireNotNull(dialogue.latestAcceptedLearnerTargetTraversal)
        val currentRevision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (currentRevision < 0 || dialogue.latestAcceptedLearnerLessonRevision != currentRevision) {
            return failure(
                if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
                "The learner's topic intent belongs to an older lesson focus. Wait for a fresh reply in the current topic.",
            )
        }
        val authorization = confirmations.learnerTurnAuthorization(
            context.session.userId,
            context.session.id,
            providerItemId,
            currentRevision,
            dialogue.precedingQuestionProviderItemId,
            dialogue.precedingAnswerProviderItemId,
            dialogue.precedingTutorFeedbackProviderItemId,
            dialogue.precedingTutorNavigationOfferProviderItemId,
        ) ?: return failure(
            if (guidedAdvance) "LEARNER_CONTINUATION_REQUIRED" else "LEARNER_CHOICE_REQUIRED",
            "The exact learner topic intent was not durably accepted. Wait for a fresh reply instead of guessing.",
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
            "The exact learner topic intent was not durably accepted. Wait for a fresh reply instead of guessing.",
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
                "voiceLessonFocusChange" to if (guidedAdvance) "GUIDED_DIRECT_CHILD" else "EXPLICIT_SELECTION",
                "notice" to if (guidedAdvance) {
                    "The next direct child focus is confirmed. Review only its node history, then ask one question at its frozen level; do not skip another edge or append a second question."
                } else {
                    "The saved lesson focus is confirmed. Use its frozen level for the next new question, review only its node history first, and wait for clear learner agreement before teaching; prior questions and navigation turns keep their original context."
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
        val transportContext = McpTransportContext.create(mapOf(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to principal))
        val request = McpSchema.CallToolRequest.builder(specification.tool().name()).arguments(arguments).build()
        return specification.callHandler().apply(transportContext, request).awaitSingle()
    }

    private fun voiceDeletionSchema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = original["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return original + mapOf(
            "properties" to linkedMapOf(
                "study_id" to properties["study_id"],
                "confirm" to mapOf("type" to "boolean", "description" to "false previews only; true requires the learner's new explicit confirmation after hearing the preview."),
                "confirmation_token" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 100, "description" to "Exact token returned by this call's delete preview; omit when confirm=false."),
            ),
        )
    }

    private suspend fun createStudyTopic(
        context: VoiceTutorWebRtcControlContext,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
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
        if (rootStudyLearnerTurnId(context, revision, VoiceTutorInputIntent.CREATE_STUDY_TOPIC) == null) {
            return failure(
                "CHILD_CREATION_REQUEST_REQUIRED",
                "Wait for one newly persisted direct learner choice of an exact child and parent; no write was started.",
            )
        }
        val lease = context.dialogueBoundary?.childStudyCreationAuthorization
        if (lease == null || lease.parentStudyId != parentStudyId || lease.topic != topic ||
            lease.difficulty != difficulty
        ) {
            return failure(
                "CHILD_CREATION_REQUEST_MISMATCH",
                "Use the exact child, parent, and level from the persisted learner choice; no write was started.",
            )
        }
        if (!parentIsWithinCallStudy(context, parentStudyId)) {
            return if (!isAuthorized(context)) inactiveCall() else failure(
                "STUDY_SCOPE_DENIED",
                "Choose the current call's study or one of its verified descendants as the parent; no write was started.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (!lease.consume()) {
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
        return withLessonContext(context, truthful, CREATE_TOPIC, exactArguments)
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
        if (rootStudyLearnerTurnId(context, revision, VoiceTutorInputIntent.UPDATE_STUDY) == null) {
            return failure(
                "STUDY_UPDATE_REQUEST_REQUIRED",
                "Wait for one newly persisted direct learner choice of an exact saved-node patch; no write was started.",
            )
        }
        val lease = context.dialogueBoundary?.studyUpdateAuthorization
        val authorizedPatchKeys = buildSet {
            if (lease?.topic != null) add("topic")
            if (lease?.difficulty != null) add("difficulty_level")
        }
        if (lease == null || lease.studyId != studyId || lease.topic != topic || lease.difficulty != difficulty ||
            suppliedPatchKeys != authorizedPatchKeys
        ) {
            return failure(
                "STUDY_UPDATE_REQUEST_MISMATCH",
                "Use the exact saved node and patch from the persisted learner choice; no write was started.",
            )
        }
        if (!studyIsWithinCallTree(context, studyId)) {
            return if (!isAuthorized(context)) inactiveCall() else failure(
                "STUDY_SCOPE_DENIED", "Change only an exact owned node in this call's verified study tree.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        val prepared = studyContexts.remember(context.session.userId, context.session.id, listOf(studyId))
        val baseline = prepared.singleOrNull { it.studyId == studyId }
        if (baseline == null || studyContexts.currentRevision(context.session.userId, context.session.id) >= 32) {
            return failure(
                "LESSON_CONTEXT_UNAVAILABLE",
                "The change could not be safely prepared in this call. No update was started.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (!lease.consume()) {
            return failure(
                "STUDY_UPDATE_REQUEST_REQUIRED",
                "This exact saved-node patch was already used or revoked; wait for a fresh direct learner choice.",
            )
        }
        val exactArguments = linkedMapOf<String, Any>("study_id" to studyId)
        topic?.let { exactArguments["topic"] = it }
        difficulty?.let { exactArguments["difficulty_level"] = it }
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
    ): VoiceTutorMcpToolResult {
        val topic = (arguments["topic"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 255 }
            ?: return failure("INVALID_ARGUMENTS", "Choose one root topic containing 1 to 255 characters.")
        val difficulty = (arguments["difficulty_level"] as? Number)?.toInt() ?: DEFAULT_ROOT_DIFFICULTY
        if (difficulty !in 1..10) return failure("INVALID_ARGUMENTS", "Choose a root difficulty from 1 to 10.")
        val revision = studyContexts.currentRevision(context.session.userId, context.session.id)
        if (rootStudyLearnerTurnId(
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
        if (creationAuthorization == null || creationAuthorization.topic != topic ||
            creationAuthorization.difficulty != difficulty
        ) {
            return failure(
                "ROOT_CREATION_REQUEST_MISMATCH",
                "Use the exact root topic and level from the persisted learner choice; no write was started.",
            )
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (!creationAuthorization.consume()) {
            return failure(
                "ROOT_CREATION_REQUEST_REQUIRED",
                "A newer learner turn revoked this root choice before the write began; no write was started. Wait for a fresh direct choice to begin a new saved root.",
            )
        }
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
        val cleanResult = VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "created" to created,
                "id" to id,
                "parentStudyId" to null,
                "topic" to returnedTopic,
                "difficultyLevel" to returnedDifficulty,
                "enabled" to enabled,
                "activeForQuestions" to activeForQuestions,
                "notice" to if (created) {
                    "The root was created but is not the lesson focus. Read it with get_study, speak that exact saved root, and wait for a NEW agreement to start before select_voice_study."
                } else {
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
    ): VoiceTutorMcpToolResult {
        if (arguments["confirm"] != true) {
            val preview = deletionPreview(context, studyId) ?: return failure(
                "DELETE_PREVIEW_UNAVAILABLE", "The complete owned subtree could not be verified within the 128-node voice limit. No deletion occurred; use the study-tree screen for this operation.",
            )
            if (!isAuthorized(context)) return inactiveCall()
            val learnerTurn = confirmations.latestLearnerTurnId(context.session.userId, context.session.id)
                ?: return failure("CONFIRMATION_REQUIRED", "Wait for the learner's explicit spoken deletion request before preparing a deletion.")
            val ticket = deletionConfirmations.prepare(context, studyId, preview.ids, learnerTurn)
                ?: return failure("CONFIRMATION_UNAVAILABLE", "Deletion confirmation is not available; no data was deleted.")
            return VoiceTutorMcpToolResult(objectMapper.writeValueAsString(linkedMapOf(
                "deleted" to false, "requiresConfirmation" to true, "studyId" to studyId,
                "topic" to preview.topic, "descendantCount" to preview.ids.size - 1,
                "totalStudyCount" to preview.ids.size, "confirmation_token" to ticket.token,
                "expiresInSeconds" to 120,
                "notice" to "Ask whether to delete this named study and ALL its counted descendant topics, then wait for a NEW explicit affirmative learner reply. Existing question/answer and voice session history are retained. This is only a preview; nothing was deleted. Never read the token aloud.",
            )), isError = false)
        }
        val token = arguments["confirmation_token"] as? String
            ?: return failure("CONFIRMATION_REQUIRED", "First preview with confirm=false, explain the exact subtree, and wait for a new explicit affirmative learner reply.")
        val ticket = deletionConfirmations.consume(
            context, studyId, token,
            confirmations.latestLearnerTurnId(context.session.userId, context.session.id),
        ) ?: return failure("CONFIRMATION_REQUIRED", "The preview is missing, expired, consumed, or lacks a new learner reply after the tutor's confirmation question. Do not delete; ask again with a fresh preview if needed.")
        if (!isAuthorized(context)) return inactiveCall()
        // The common MCP use case locks this owner and compares the complete set in
        // the SAME transaction as deletion. Added children require a new preview.
        val selectedId = currentStudyAnchor(context)
        val result = invoke(context.principal!!, specification, mapOf(
            "study_id" to studyId, "confirm" to true, "expected_study_ids" to ticket.studyIds,
        ))
        if (result.isError() == true) return boundedResult(result, DELETE_STUDY)
        val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
        if (payload?.path("deleted")?.asBoolean() != true || positiveId(payload.path("studyId")) != studyId) {
            return failure("DELETE_RESULT_UNCONFIRMED", "The delete result cannot be verified. Read saved studies before any retry; do not repeat the write automatically.")
        }
        return VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "deleted" to true, "studyId" to studyId, "deletedStudyIds" to ticket.studyIds,
                "voiceLessonDeletedStudyIds" to ticket.studyIds,
                "voiceLessonSelectionDeleted" to (selectedId in ticket.studyIds),
                "notice" to "The confirmed subtree was deleted. Keep existing transcripts and prior answers; stop asking or scoring new questions on deleted nodes. The call is still connected. Briefly acknowledge, then wait; only continue teaching after the learner chooses a verified surviving saved topic.",
            )),
            isError = false, studyTreeChanged = true, changedStudyId = studyId,
            changeKind = VoiceTutorStudyChangeKind.DELETED, deletedStudyIds = ticket.studyIds,
            lessonFocusCleared = selectedId in ticket.studyIds,
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
        return DeletionPreview(root.path("topic").asText().take(255), ids.toList())
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

    private data class DeletionPreview(val topic: String, val ids: List<Long>)

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
        if ((selectedId == null && toolName != CREATE_ROOT) ||
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
        payload.put(
            "voiceLessonContextReady",
            toolName != CREATE_ROOT && nodes.size <= 32 && ids.all { it in frozenIds },
        )
        if (toolName == CREATE_ROOT) {
            payload.put("voiceLessonChangeApplies", "REQUIRES_SELECTION")
            payload.put(
                "notice",
                "The root was saved but is not a lesson focus. Read this exact root, speak it, and wait for a new learner agreement before select_voice_study; creation itself never starts teaching.",
            )
        }
        if (toolName == UPDATE_STUDY) {
            payload.put("voiceLessonChangeApplies", if (ids.all { it in frozenIds }) "NEXT_QUESTION" else "NOT_PREPARED")
            payload.put("notice", "Study settings were saved; completed and pending questions keep their original title and level. If lesson context is not ready, do not ask another question on this node or repeat the write.")
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
        val currentView = currentSnapshots(savedTree + snapshots)
        val updatedRevision = if (toolName == UPDATE_STUDY && ids.all { it in frozenIds })
            currentView.maxOfOrNull { it.revision } else null
        val updatedFocus = if (updatedRevision != null) currentView.singleOrNull { it.studyId == selectedId }
            ?.let { snapshot ->
                VoiceTutorLessonFocusSelection(
                    focus = VoiceTutorLessonFocus(snapshot.studyId, updatedRevision),
                    snapshot = snapshot,
                    lessonRevision = updatedRevision,
                )
            } else null
        updatedFocus?.let { payload.set<JsonNode>("voiceLessonFocus", objectMapper.valueToTree(focusMetadata(it))) }
        val revisedResult = result.copy(lessonRevision = updatedRevision, lessonFocus = updatedFocus)
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
            "get_topic_stats", "get_study_growth",
            SELECT_STUDY, ADVANCE_STUDY,
        )
        val LEARNING_HISTORY_TOOLS = setOf(LIST_LEARNING_RECORDS, GET_VOICE_LEARNING_RECORD)
        val STUDY_CONTEXT_TOOLS = setOf("list_studies", "get_study", CREATE_ROOT, CREATE_TOPIC, UPDATE_STUDY)
        val CREATION_TOOLS = setOf(CREATE_ROOT, CREATE_TOPIC)
        val DELETE_ARGUMENTS = setOf("study_id", "confirm", "confirmation_token")
        val CREATED_TOPIC_FIELDS = listOf(
            "created", "id", "parentStudyId", "topic", "sortOrder", "difficultyLevel", "activeForQuestions", "enabled",
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
