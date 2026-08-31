package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
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
) : VoiceTutorMcpToolPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val validator by lazy { McpJsonSchemaValidatorProvider.create() }
    private val specifications by lazy {
        mcp.tools().filter { it.tool().name() in ALLOWED_TOOLS }.associateBy { it.tool().name() }
    }

    override fun definitions(): List<VoiceTutorMcpToolDefinition> = specifications.values.map { specification ->
        val tool = specification.tool()
        VoiceTutorMcpToolDefinition(
            name = tool.name(),
            description = tool.description().orEmpty() + if (tool.name() == CREATE_TOPIC) {
                " In a voice call, the parent must be the current call's study or one of its descendants."
            } else {
                ""
            },
            parameters = tool.inputSchema().toMap(),
        )
    }

    override suspend fun execute(
        context: VoiceTutorWebRtcControlContext,
        toolName: String,
        arguments: Map<String, Any>,
    ): VoiceTutorMcpToolResult {
        if (toolName !in ALLOWED_TOOLS) return failure("TOOL_NOT_ALLOWED", "This tool is not available in voice calls.")
        try {
            val specification = specifications[toolName]
                ?: return failure("MCP_UNAVAILABLE", "This study tool is currently unavailable.")
            if (objectMapper.writeValueAsBytes(arguments).size > MAX_ARGUMENT_BYTES) {
                return failure("INVALID_ARGUMENTS", "Tool arguments are too large.")
            }
            // Use the SDK's existing validator, not its logging wrapper: validation errors
            // can contain private argument values and must never enter application logs.
            if (!validator.validate(specification.tool().inputSchema(), arguments).valid()) {
                return failure("INVALID_ARGUMENTS", "Arguments do not match this tool's input schema.")
            }
            if (!isAuthorized(context)) return inactiveCall()
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
                // the only permitted mutation, including logout/end events during those reads.
                if (!isAuthorized(context)) return inactiveCall()
            }
            val result = invoke(context.principal!!, specification, arguments)
            return withLessonContext(context, boundedResult(result, toolName), toolName)
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
        return current.id == snapshot.id && current.userId == principal.userId &&
            current.status == VoiceTutorSessionStatus.ACTIVE && current.studyId == snapshot.studyId &&
            current.providerSessionId == context.callId && clock.instant().isBefore(current.hardEndsAt)
    }

    private suspend fun parentIsWithinCallStudy(context: VoiceTutorWebRtcControlContext, parentStudyId: Long): Boolean {
        val callStudyId = context.session.studyId ?: return false
        if (callStudyId <= 0) return false
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

    private suspend fun invoke(
        principal: Principal,
        specification: McpStatelessServerFeatures.AsyncToolSpecification,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult {
        val transportContext = McpTransportContext.create(mapOf(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to principal))
        val request = McpSchema.CallToolRequest.builder(specification.tool().name()).arguments(arguments).build()
        return specification.callHandler().apply(transportContext, request).awaitSingle()
    }

    private fun boundedResult(result: McpSchema.CallToolResult, toolName: String): VoiceTutorMcpToolResult {
        val isError = result.isError() == true
        val changed = !isError && toolName == CREATE_TOPIC
        val payload = result.structuredContent() ?: mapOf("content" to result.content())
        val createdStudyId = if (changed) {
            objectMapper.valueToTree<JsonNode>(payload).path("id")
                .takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0 }
                ?.longValue()
        } else {
            null
        }
        val bytes = objectMapper.writeValueAsBytes(payload)
        if (bytes.size <= MAX_OUTPUT_BYTES) {
            return VoiceTutorMcpToolResult(String(bytes, Charsets.UTF_8), isError, changed, createdStudyId)
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
            compact.put("notice", "Topic creation succeeded. Only study-tree fields are included in this voice result.")
            val compactBytes = objectMapper.writeValueAsBytes(compact)
            if (compactBytes.size <= MAX_OUTPUT_BYTES) {
                return VoiceTutorMcpToolResult(String(compactBytes, Charsets.UTF_8), false, true, createdStudyId)
            }
        }
        return failure(
            "RESULT_TOO_LARGE",
            "The result exceeds the voice tool limit. Request a smaller page or a more specific study/topic filter. " +
                "The result was not cut into partial JSON.",
        ).copy(studyTreeChanged = changed, createdStudyId = createdStudyId)
    }

    private suspend fun withLessonContext(
        context: VoiceTutorWebRtcControlContext,
        result: VoiceTutorMcpToolResult,
        toolName: String,
    ): VoiceTutorMcpToolResult {
        if (result.isError || toolName !in STUDY_CONTEXT_TOOLS) return result
        val payload = objectMapper.readTree(result.output) as? ObjectNode ?: return result
        val nodes = if (toolName == "list_studies") payload.path("studies").toList() else listOf(payload)
        val ids = nodes.take(32).mapNotNull { node ->
            node.path("id").takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0 }
                ?.longValue()
        }.distinct()
        if (ids.isEmpty()) return result
        val snapshots = try {
            studyContexts.remember(context.session.userId, context.session.id, ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A study creation already committed. Do not turn metadata-cache failure into
            // a false failed write that encourages the model to create the topic twice.
            logger.warn("voice_tutor_study_context_capture_failed errorType={}", error.javaClass.simpleName)
            emptyList()
        }
        val frozenIds = snapshots.mapTo(mutableSetOf()) { it.studyId }
        payload.put("voiceLessonContextReady", nodes.size <= 32 && ids.all { it in frozenIds })
        payload.set<JsonNode>(
            "voiceLessonTopics",
            objectMapper.valueToTree(snapshots.map { snapshot ->
                linkedMapOf(
                    "studyId" to snapshot.studyId,
                    "parentStudyId" to snapshot.parentStudyId,
                    "topic" to snapshot.topic,
                    "difficulty" to snapshot.difficulty,
                )
            }),
        )
        val enriched = objectMapper.writeValueAsBytes(payload)
        if (enriched.size <= MAX_OUTPUT_BYTES) return result.copy(output = String(enriched, Charsets.UTF_8))
        if (result.studyTreeChanged) {
            val compact = objectMapper.createObjectNode()
            for (field in CREATED_TOPIC_FIELDS + listOf("voiceLessonTopics", "voiceLessonContextReady")) {
                payload.get(field)?.let { compact.set<JsonNode>(field, it) }
            }
            compact.put("truncated", true)
            compact.put("notice", "Topic creation succeeded. Only study-tree and lesson metadata are included.")
            val compactBytes = objectMapper.writeValueAsBytes(compact)
            if (compactBytes.size <= MAX_OUTPUT_BYTES) return result.copy(output = String(compactBytes, Charsets.UTF_8))
            // Even an unusually large metadata capture must not imply that a
            // successful creation failed or that mutable live levels are frozen.
            return result.copy(output = objectMapper.writeValueAsString(mapOf(
                "id" to result.createdStudyId,
                "voiceLessonTopics" to emptyList<Any>(),
                "voiceLessonContextReady" to false,
                "truncated" to true,
                "notice" to "Topic creation succeeded; lesson metadata is not ready. Do not repeat creation.",
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
        const val CREATE_TOPIC = "create_study_topic"
        const val MAX_ARGUMENT_BYTES = 16 * 1_024
        // Function results are themselves JSON-escaped inside a provider event.
        const val MAX_OUTPUT_BYTES = 16 * 1_024
        const val MAX_ANCESTOR_READS = 32
        val ALLOWED_TOOLS = setOf(
            "list_studies", "get_study", CREATE_TOPIC,
            "list_records", "get_record", "get_topic_stats", "get_study_growth",
        )
        val STUDY_CONTEXT_TOOLS = setOf("list_studies", "get_study", CREATE_TOPIC)
        val CREATED_TOPIC_FIELDS = listOf(
            "id", "parentStudyId", "topic", "sortOrder", "difficultyLevel", "activeForQuestions", "enabled",
        )
    }
}
