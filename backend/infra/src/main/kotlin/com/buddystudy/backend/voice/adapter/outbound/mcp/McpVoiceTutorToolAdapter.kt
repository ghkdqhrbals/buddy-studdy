package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorLessonTreeContext
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
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
                CREATE_TOPIC -> " In a voice call, the parent must be the current confirmed lesson focus or one of its descendants; select a saved focus first."
                UPDATE_STUDY -> " In a voice call, update only the explicitly requested saved node in this call's verified tree; unspecified fields and past question levels are preserved."
                DELETE_STUDY -> " In a voice call, first call with confirm=false to preview the exact subtree; ask the learner to confirm its name and descendant count, then wait for a new affirmative spoken turn before calling with confirm=true and the returned confirmation_token. Never skip the preview or reuse a token."
                in LEARNING_HISTORY_TOOLS -> " In a voice call, read only nodes in the current call's verified study tree; history never changes the agreed lesson focus."
                else -> ""
            },
            parameters = if (tool.name() == DELETE_STUDY) voiceDeletionSchema(tool.inputSchema().toMap()) else tool.inputSchema().toMap(),
        )
    } + VoiceTutorMcpToolDefinition(
        name = SELECT_STUDY,
        description = "Set the current spoken lesson focus to the exact saved study the learner chose. " +
            "First find the owned node and its real parent path with list_studies/get_study; clarify ambiguous topics. " +
            "Use this before teaching a newly chosen root or child, never merely because a search returned it. " +
            "The returned voiceLessonFocus and frozen level apply to the next new question only. " +
            "This does not create/edit a study, start teaching, submit an answer, or consume question quota.",
        parameters = mapOf(
            "type" to "object", "additionalProperties" to false,
            "properties" to mapOf("study_id" to mapOf("type" to "integer", "minimum" to 1,
                "description" to "Exact owned saved study ID chosen in the conversation, not a title or list position.")),
            "required" to listOf("study_id"),
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
            val specification = specifications[toolName]
                ?: return failure("MCP_UNAVAILABLE", "This study tool is currently unavailable.")
            // Use the SDK's existing validator, not its logging wrapper: validation errors
            // can contain private argument values and must never enter application logs.
            if (toolName == DELETE_STUDY && (arguments.keys.any { it !in DELETE_ARGUMENTS } ||
                    ("confirmation_token" in arguments && (arguments["confirmation_token"] !is String ||
                        (arguments["confirmation_token"] as String).length !in 1..100)))
            ) return failure("INVALID_ARGUMENTS", "Deletion requires the exact preview's confirmation token.")
            val mcpArguments = if (toolName == DELETE_STUDY) arguments - "confirmation_token" else arguments
            if (!validator.validate(specification.tool().inputSchema(), mcpArguments).valid()) {
                return failure("INVALID_ARGUMENTS", "Arguments do not match this tool's input schema.")
            }
            if (!isAuthorized(context)) return inactiveCall()
            if (toolName == UPDATE_STUDY || toolName == DELETE_STUDY) {
                val studyId = (arguments.getValue("study_id") as Number).toLong()
                if (!studyIsWithinCallTree(context, studyId)) {
                    return if (!isAuthorized(context)) inactiveCall() else failure(
                        "STUDY_SCOPE_DENIED", "Change only an exact owned node in this call's verified study tree; no change was made.",
                    )
                }
                if (!isAuthorized(context)) return inactiveCall()
                if (toolName == DELETE_STUDY) return deleteStudy(context, specification, arguments, studyId)
                // Capture the pre-edit level first. Never overwrite the evidence for a
                // question that is already awaiting an answer, even when its ASR is late.
                val prepared = studyContexts.remember(context.session.userId, context.session.id, listOf(studyId))
                if (prepared.none { it.studyId == studyId } || studyContexts.currentRevision(context.session.userId, context.session.id) >= 32) {
                    return failure("LESSON_CONTEXT_UNAVAILABLE", "The change could not be safely prepared in this call. No study update was made; try in a later call or in study settings.")
                }
                if (!isAuthorized(context)) return inactiveCall()
            }
            if (toolName == LIST_LEARNING_RECORDS) {
                val studyId = (arguments.getValue("study_id") as Number).toLong()
                if (!studyIsWithinCallTree(context, studyId)) {
                    return if (!isAuthorized(context)) inactiveCall() else learningScopeDenied()
                }
                if (!isAuthorized(context)) return inactiveCall()
            }
            if (toolName == CREATE_TOPIC) {
                val parentStudyId = (arguments.getValue("parent_study_id") as Number).toLong()
                if (!parentIsWithinCallStudy(context, parentStudyId)) {
                    return failure(
                        "STUDY_SCOPE_DENIED",
                        "Choose the current call's study or one of its descendants as the parent. " +
                            "If the study is unavailable or its ancestry cannot be verified, no topic is created.",
                    )
                }
                // Ancestry may involve several suspended reads. Recheck immediately before
                // a mutation, including logout/end events during those reads.
                if (!isAuthorized(context)) return inactiveCall()
            }
            val result = invoke(context.principal!!, specification, arguments)
            val readOnly = toolName !in setOf(CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY)
            // A saved-study search is private too. Ending a call or revoking a device
            // while its handler is suspended must suppress the late read result.
            if (readOnly && !isAuthorized(context)) return inactiveCall()
            if (toolName == UPDATE_STUDY && result.isError() != true) {
                val payload = result.structuredContent()?.let { objectMapper.valueToTree<JsonNode>(it) }
                if (payload == null || positiveId(payload.path("id")) != (arguments["study_id"] as Number).toLong()) {
                    return failure("UPDATE_RESULT_UNCONFIRMED", "The saved change's identity cannot be verified. Read saved studies before any retry; do not repeat the write automatically.")
                }
            }
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
            return if (readOnly && !isAuthorized(context)) inactiveCall() else enriched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No arguments, account credentials, study contents, or provider payloads in logs.
            return failure("MCP_UNAVAILABLE", "The study tool could not complete. Do not assume the action succeeded.")
        }
    }

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
        val id = arguments["study_id"]?.let { positiveId(objectMapper.valueToTree(it)) }
        if (arguments.keys != setOf("study_id") || id == null) {
            return failure("INVALID_ARGUMENTS", "Choose one exact positive saved study_id.")
        }
        if (!isAuthorized(context)) return inactiveCall()
        if (confirmations.latestLearnerTurnId(context.session.userId, context.session.id) == null) {
            return failure("LEARNER_CHOICE_REQUIRED", "Ask what the learner wants to discuss and wait for their meaningful reply before selecting a study.")
        }
        if (!isAuthorized(context)) return inactiveCall()
        val selection = lessonFocus.focus(context.session.userId, context.session.id, id)
            ?: return failure("LESSON_FOCUS_UNAVAILABLE", "This owned saved topic and its complete parent path could not be prepared; no focus was selected. Read saved topics or ask one brief clarification instead of guessing or creating a replacement.")
        if (selection.studyId != id || selection.snapshot.studyId != id || selection.revision <= 0 ||
            selection.snapshot.topic.isBlank() || selection.snapshot.topic.length > 255 ||
            selection.snapshot.difficulty !in 1..10 || selection.snapshot.parentStudyId?.let { it > 0 } == false
        ) return failure("LESSON_FOCUS_UNCONFIRMED", "The saved focus result could not be verified; do not begin teaching or repeat a selection automatically.")
        if (!isAuthorized(context)) return inactiveCall()
        // Selection already committed. Optional tree enrichment must not turn it into an
        // uncertain failed selection or cause a duplicate focus epoch on retry.
        val saved = try {
            currentSnapshots(studyContexts.list(context.session.userId, context.session.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listOf(selection.snapshot)
        }
        if (!isAuthorized(context)) return inactiveCall()
        val focus = focusMetadata(selection)
        return VoiceTutorMcpToolResult(
            output = objectMapper.writeValueAsString(linkedMapOf(
                "selected" to true, "voiceLessonContextReady" to true,
                "voiceLessonFocus" to focus, "voiceLessonTopics" to listOf(focus),
                "voiceLessonTree" to VoiceTutorLessonTreeContext.metadata(id, saved, listOf(id)),
                "notice" to "The saved lesson focus is confirmed. Use its frozen level for the next new question, review only its node history first, and wait for clear learner agreement before teaching; prior questions and navigation turns keep their original context.",
            )),
            isError = false, lessonRevision = selection.revision, lessonFocus = selection,
        )
    }

    private fun focusMetadata(selection: VoiceTutorLessonFocusSelection): Map<String, Any?> = linkedMapOf(
        "studyId" to selection.studyId, "parentStudyId" to selection.snapshot.parentStudyId,
        "topic" to selection.snapshot.topic, "difficulty" to selection.snapshot.difficulty,
        "revision" to selection.revision,
    )

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
            output, isError, changed, createdStudyId = id.takeIf { toolName == CREATE_TOPIC }, changedStudyId = id,
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
        val changed = !isError && toolName in setOf(CREATE_TOPIC, UPDATE_STUDY)
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
        ).copy(studyTreeChanged = changed, createdStudyId = changedStudyId.takeIf { toolName == CREATE_TOPIC }, changedStudyId = changedStudyId)
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
        if (selectedId == null || (toolName == "list_studies" && "parent_study_id" !in arguments)) {
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
                CREATE_TOPIC -> studyContexts.remember(context.session.userId, context.session.id, ids)
                // Read-only browsing never reserves snapshot slots, even after a focus
                // has been selected. Only an explicit selection or saved edit freezes
                // new nodes; inspecting a large tree must not exhaust the lesson cache.
                else -> emptyList()
            }
            savedTree = currentSnapshots(studyContexts.list(context.session.userId, context.session.id))
            if (toolName != UPDATE_STUDY && toolName != CREATE_TOPIC) snapshots = savedTree.filter { it.studyId in ids }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The study write already committed. Never turn context-capture failure
            // into a false failed write or replay a possibly successful mutation.
            logger.warn("voice_tutor_study_context_capture_failed errorType={}", error.javaClass.simpleName)
        }
        val frozenIds = snapshots.mapTo(mutableSetOf()) { it.studyId }
        payload.put("voiceLessonContextReady", nodes.size <= 32 && ids.all { it in frozenIds })
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
        val updatedRevision = if (toolName == UPDATE_STUDY && ids.all { it in frozenIds })
            (savedTree + snapshots).maxOfOrNull { it.revision } else null
        val updatedFocus = if (updatedRevision != null) snapshots.singleOrNull { it.studyId == selectedId }
            ?.let { VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(it.studyId, updatedRevision), it) } else null
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
            return result.copy(output = objectMapper.writeValueAsString(mapOf(
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

    private companion object {
        const val SELECT_STUDY = "select_voice_study"
        const val CREATE_TOPIC = "create_study_topic"
        const val UPDATE_STUDY = "update_study"
        const val DELETE_STUDY = "delete_study"
        const val LIST_LEARNING_RECORDS = "list_study_learning_records"
        const val GET_VOICE_LEARNING_RECORD = "get_voice_learning_record"
        const val MAX_ARGUMENT_BYTES = 16 * 1_024
        // Function results are themselves JSON-escaped inside a provider event.
        const val MAX_OUTPUT_BYTES = 16 * 1_024
        const val MAX_ANCESTOR_READS = 32
        val ALLOWED_TOOLS = setOf(
            "list_studies", "get_study", CREATE_TOPIC, UPDATE_STUDY, DELETE_STUDY,
            "list_records", "get_record", LIST_LEARNING_RECORDS, GET_VOICE_LEARNING_RECORD,
            "get_topic_stats", "get_study_growth",
            SELECT_STUDY,
        )
        val LEARNING_HISTORY_TOOLS = setOf(LIST_LEARNING_RECORDS, GET_VOICE_LEARNING_RECORD)
        val STUDY_CONTEXT_TOOLS = setOf("list_studies", "get_study", CREATE_TOPIC, UPDATE_STUDY)
        val DELETE_ARGUMENTS = setOf("study_id", "confirm", "confirmation_token")
        val CREATED_TOPIC_FIELDS = listOf(
            "id", "parentStudyId", "topic", "sortOrder", "difficultyLevel", "activeForQuestions", "enabled",
        )
    }
}
