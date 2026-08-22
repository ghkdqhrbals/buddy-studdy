package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Instant

@Component
class BuddyStudyMcpAdapter(
    private val buddyStudy: BuddyStudyMcpUseCase,
    private val objectMapper: ObjectMapper,
) : BuddyStudyMcpPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val toolSpecifications by lazy {
        listOf(
            tool(
                name = "get_my_context",
                title = "Get my BuddyStudy context",
                description = "Return the authenticated user's private profile, resume Markdown, and interests.",
                schema = objectSchema(),
                readOnly = true,
            ) { principal, _ ->
                buddyStudy.getMyContext(principal)
            },
            tool(
                name = "update_my_learning_context",
                title = "Update my resume and interests",
                description = "Patch the authenticated user's private learning context. Omit a field to preserve it; use an empty string or empty list to clear it.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "resume_markdown" to stringProperty(
                            description = "Resume or career context in Markdown. Empty text clears it.",
                            maxLength = 50_000,
                        ),
                        "interests" to arrayProperty(
                            description = "Learning or career interests. Empty list clears them.",
                            item = stringProperty(maxLength = 100),
                            maxItems = 50,
                        ),
                    ),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.updateMyLearningContext(
                    principal,
                    LearningContextPatchCommand(
                        resumeMarkdown = args.optionalString("resume_markdown"),
                        interests = args.optionalStringList("interests"),
                    ),
                )
            },
            tool(
                name = "list_studies",
                title = "List my studies",
                description = "Return a bounded page of the authenticated user's study tree nodes and pending questions.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "query" to stringProperty("Optional topic search.", maxLength = 200),
                        "language" to languageProperty(),
                    ),
                    maximum = 500,
                    defaultLimit = 100,
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listStudies(
                    principal,
                    args.int("limit", 100),
                    args.int("offset", 0),
                    args.optionalString("query"),
                    args.string("language", "ko"),
                )
            },
            tool(
                name = "get_study",
                title = "Get one study",
                description = "Return one owned study tree node with its current pending and latest completed question.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Owned study node ID."),
                        "language" to languageProperty(),
                    ),
                    required = listOf("study_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getStudy(principal, args.long("study_id"), args.string("language", "ko"))
            },
            tool(
                name = "create_study",
                title = "Create a root study",
                description = "Create or update a root study. This never creates a question and never consumes question quota.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "topic" to stringProperty("Root study topic.", minLength = 1, maxLength = 255),
                        "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                        "interval_minutes" to integerProperty("Schedule interval from 1 to 1440 minutes.", 1, 1440, 15),
                        "enabled" to booleanProperty("Whether scheduled question delivery is enabled.", true),
                        "notification_sound" to stringProperty("Optional APNs sound name.", maxLength = 100),
                        "custom_prompt" to stringProperty("Optional custom question-generation guidance.", maxLength = 4_000),
                    ),
                    required = listOf("topic"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.createStudy(
                    principal,
                    CreateStudyCommand(
                        topic = args.string("topic"),
                        difficultyLevel = args.int("difficulty_level", 5),
                        intervalMinutes = args.int("interval_minutes", 15),
                        enabled = args.boolean("enabled", true),
                        notificationSound = args.optionalString("notification_sound"),
                        customPrompt = args.string("custom_prompt", ""),
                    ),
                )
            },
            tool(
                name = "create_study_topic",
                title = "Create a child study topic",
                description = "Add a child topic under an owned study node. This is separate from root-study creation and question generation.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "parent_study_id" to idProperty("Parent study node ID."),
                        "topic" to stringProperty("Child topic.", minLength = 1, maxLength = 255),
                        "sort_order" to integerProperty("Sibling sort order.", 0, 10_000, 0),
                        "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                        "active_for_questions" to booleanProperty("Whether this topic participates in question generation.", true),
                    ),
                    required = listOf("parent_study_id", "topic"),
                ),
                readOnly = false,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.createStudyTopic(
                    principal,
                    args.long("parent_study_id"),
                    CreateStudyTopicCommand(
                        topic = args.string("topic"),
                        sortOrder = args.int("sort_order", 0),
                        difficultyLevel = args.int("difficulty_level", 5),
                        activeForQuestions = args.boolean("active_for_questions", true),
                    ),
                )
            },
            tool(
                name = "delete_study",
                title = "Delete a study subtree",
                description = "Permanently delete an owned study and every descendant topic. Existing question records are retained without a study link. Set confirm=true only after explicit user confirmation.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Root of the subtree to delete."),
                        "confirm" to booleanProperty("Must be true after explicit user confirmation."),
                    ),
                    required = listOf("study_id", "confirm"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.deleteStudy(principal, args.long("study_id"), args.boolean("confirm"))
            },
            tool(
                name = "list_pending_questions",
                title = "List pending questions",
                description = "Return a bounded page of the authenticated user's active unanswered or grading questions.",
                schema = pagedSchema(maximum = 100, defaultLimit = 30),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listPendingQuestions(principal, args.int("limit", 30), args.int("offset", 0))
            },
            tool(
                name = "request_question",
                title = "Request a study question",
                description = "Queue question generation for one owned study topic. Returns a correlation ID immediately; poll get_question_process until terminal=true.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Study topic ID."),
                        "idempotency_key" to stringProperty(
                            "Stable caller-generated key reused when retrying the same request.",
                            minLength = 1,
                            maxLength = 100,
                        ),
                    ),
                    required = listOf("study_id", "idempotency_key"),
                ),
                readOnly = false,
                idempotent = true,
                openWorld = true,
            ) { principal, args ->
                buddyStudy.requestQuestion(principal, args.long("study_id"), args.string("idempotency_key"))
            },
            tool(
                name = "get_question_process",
                title = "Get question generation status",
                description = "Get the authenticated user's asynchronous question-generation state and generated question when complete.",
                schema = correlationSchema(),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getQuestionProcess(principal, args.string("correlation_id"))
            },
            tool(
                name = "submit_answer",
                title = "Submit an answer for grading",
                description = "Persist an answer and queue asynchronous grading. Returns a grading correlation ID; poll get_grading_process and then get_record for score and feedback.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "record_id" to idProperty("Pending question record ID."),
                        "answer" to stringProperty("The user's answer. Do not invent or rewrite it.", minLength = 1, maxLength = 50_000),
                        "source_language" to languageProperty("Optional language of the answer."),
                    ),
                    required = listOf("record_id", "answer"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = false,
                openWorld = true,
            ) { principal, args ->
                buddyStudy.submitAnswer(
                    principal,
                    args.long("record_id"),
                    args.string("answer"),
                    args.optionalString("source_language"),
                )
            },
            tool(
                name = "get_grading_process",
                title = "Get answer grading status",
                description = "Return grading state and durable progress events. Poll after pollAfterMs until terminal=true.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "correlation_id" to stringProperty("Grading correlation ID.", minLength = 1, maxLength = 100),
                        "after_event_id" to integerProperty("Return events after this cursor.", 0, Long.MAX_VALUE, 0),
                    ),
                    required = listOf("correlation_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getGradingProcess(
                    principal,
                    args.string("correlation_id"),
                    args.long("after_event_id", 0),
                )
            },
            tool(
                name = "list_records",
                title = "List study records",
                description = "Return a bounded page of completed study records, including scores and grading feedback when available.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "query" to stringProperty("Optional question, answer, or topic search.", maxLength = 200),
                        "study_id" to idProperty("Optional study topic filter."),
                        "language" to languageProperty(),
                        "view" to viewProperty(),
                    ),
                    maximum = 100,
                    defaultLimit = 30,
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listRecords(
                    principal,
                    args.int("limit", 30),
                    args.int("offset", 0),
                    args.optionalString("query"),
                    args.optionalLong("study_id"),
                    args.string("language", "ko"),
                    args.string("view", "localized"),
                )
            },
            tool(
                name = "get_record",
                title = "Get score and feedback",
                description = "Return one owned question record with its answer, score, correctness, feedback, explanation, and rubric details.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "record_id" to idProperty("Owned question record ID."),
                        "language" to languageProperty(),
                        "view" to viewProperty(),
                    ),
                    required = listOf("record_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getRecord(
                    principal,
                    args.long("record_id"),
                    args.string("language", "ko"),
                    args.string("view", "localized"),
                )
            },
            tool(
                name = "get_topic_stats",
                title = "Get topic-level statistics",
                description = "Return paginated topic-first score, correctness, and level-range statistics. Do not infer a global average across unrelated topics.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "query" to stringProperty("Optional topic search.", maxLength = 200),
                        "period" to stringProperty(
                            "Optional preset period.",
                            values = listOf("all", "today", "last7", "last30", "last90"),
                        ),
                        "start_at" to instantProperty("Optional inclusive UTC start timestamp."),
                        "end_at" to instantProperty("Optional exclusive UTC end timestamp."),
                    ),
                    maximum = 50,
                    defaultLimit = 20,
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getTopicStats(
                    principal,
                    args.int("limit", 20),
                    args.int("offset", 0),
                    args.optionalString("query"),
                    args.optionalString("period"),
                    args.optionalInstant("start_at"),
                    args.optionalInstant("end_at"),
                )
            },
            tool(
                name = "get_study_growth",
                title = "Get study-tree growth",
                description = "Return root and topic-node growth, trends, and learning profile values for an optional UTC interval.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "start_at" to instantProperty("Optional inclusive UTC start timestamp."),
                        "end_at" to instantProperty("Optional exclusive UTC end timestamp."),
                    ),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getStudyGrowth(principal, args.optionalInstant("start_at"), args.optionalInstant("end_at"))
            },
        )
    }

    private val resourceSpecifications by lazy {
        listOf(
            resource(
                uri = "buddystudy://me/context",
                name = "my-buddystudy-context",
                title = "My BuddyStudy context",
                description = "Private profile, resume Markdown, and interests for the authenticated user.",
            ) { principal -> buddyStudy.getMyContext(principal) },
            resource(
                uri = "buddystudy://studies",
                name = "my-studies",
                title = "My study tree",
                description = "The first 200 owned study tree nodes and their current question state.",
            ) { principal -> buddyStudy.listStudies(principal, 200, 0, null, "ko") },
            resource(
                uri = "buddystudy://records/recent",
                name = "my-recent-records",
                title = "My recent study records",
                description = "The 30 most recent completed records with grading feedback and scores.",
            ) { principal -> buddyStudy.listRecords(principal, 30, 0, null, null, "ko", "localized") },
        )
    }

    override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> = toolSpecifications

    override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = resourceSpecifications

    private fun tool(
        name: String,
        title: String,
        description: String,
        schema: Map<String, Any>,
        readOnly: Boolean,
        destructive: Boolean = false,
        idempotent: Boolean = readOnly,
        openWorld: Boolean = false,
        handler: suspend (Principal, Arguments) -> Any,
    ): McpStatelessServerFeatures.AsyncToolSpecification {
        val definition = McpSchema.Tool.builder(name, schema)
            .title(title)
            .description(description)
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .title(title)
                    .readOnlyHint(readOnly)
                    .destructiveHint(destructive)
                    .idempotentHint(idempotent)
                    .openWorldHint(openWorld)
                    .build(),
            )
            .build()
        return McpStatelessServerFeatures.AsyncToolSpecification(definition) { context, request ->
            mono { handler(principal(context), Arguments(request.arguments().orEmpty())) }
                .map(::successResult)
                .onErrorResume { error -> Mono.just(errorResult(name, error)) }
        }
    }

    private fun resource(
        uri: String,
        name: String,
        title: String,
        description: String,
        handler: suspend (Principal) -> Any,
    ): McpStatelessServerFeatures.AsyncResourceSpecification {
        val definition = McpSchema.Resource.builder(uri, name)
            .title(title)
            .description(description)
            .mimeType(APPLICATION_JSON)
            .build()
        return McpStatelessServerFeatures.AsyncResourceSpecification(definition) { context, _ ->
            mono { handler(principal(context)) }
                .map { value -> resourceResult(uri, value) }
                .onErrorResume { error -> Mono.just(resourceErrorResult(uri, error)) }
        }
    }

    private fun principal(context: McpTransportContext): Principal =
        context.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal
            ?: throw AccessDeniedException("Authenticated MCP principal is missing.")

    private fun successResult(value: Any): McpSchema.CallToolResult {
        val structured = objectMapper.convertValue(value, Any::class.java)
        return McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(value))
            .structuredContent(structured)
            .isError(false)
            .build()
    }

    private fun errorResult(toolName: String, error: Throwable): McpSchema.CallToolResult {
        val payload = errorPayload(toolName, error)
        return McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(payload))
            .structuredContent(payload)
            .isError(true)
            .build()
    }

    private fun resourceResult(uri: String, value: Any): McpSchema.ReadResourceResult =
        textResource(uri, objectMapper.writeValueAsString(value))

    private fun resourceErrorResult(uri: String, error: Throwable): McpSchema.ReadResourceResult =
        textResource(uri, objectMapper.writeValueAsString(errorPayload("resources/read", error)))

    private fun textResource(uri: String, text: String): McpSchema.ReadResourceResult =
        McpSchema.ReadResourceResult.builder(
            listOf(
                McpSchema.TextResourceContents.builder(uri, text)
                    .mimeType(APPLICATION_JSON)
                    .build(),
            ),
        ).build()

    private fun errorPayload(operation: String, error: Throwable): Map<String, Any> {
        val details = when (error) {
            is ApiRuntimeException -> Triple(error.errorCode.name, error.status.value(), error.message)
            is McpArgumentException -> Triple("VALIDATION_ERROR", 422, error.message ?: "Invalid tool arguments.")
            is AccessDeniedException -> Triple("PERMISSION_DENIED", 403, "Permission is denied.")
            else -> {
                log.warn("mcp_operation_failed operation={} errorType={}", operation, error.javaClass.name)
                Triple("INTERNAL_SERVER_ERROR", 500, "The MCP operation could not be completed.")
            }
        }
        return linkedMapOf(
            "error" to linkedMapOf(
                "code" to details.first,
                "status" to details.second,
                "message" to details.third,
            ),
        )
    }

    private class Arguments(private val values: Map<String, Any>) {
        fun string(name: String): String =
            optionalString(name) ?: throw McpArgumentException("$name is required.")

        fun string(name: String, default: String): String = optionalString(name) ?: default

        fun optionalString(name: String): String? = when (val value = values[name]) {
            null -> null
            is String -> value
            else -> throw McpArgumentException("$name must be a string.")
        }

        fun int(name: String, default: Int): Int = optionalNumber(name)?.toInt() ?: default

        fun long(name: String): Long =
            optionalNumber(name)?.toLong() ?: throw McpArgumentException("$name is required.")

        fun long(name: String, default: Long): Long = optionalNumber(name)?.toLong() ?: default

        fun optionalLong(name: String): Long? = optionalNumber(name)?.toLong()

        fun boolean(name: String): Boolean =
            optionalBoolean(name) ?: throw McpArgumentException("$name is required.")

        fun boolean(name: String, default: Boolean): Boolean = optionalBoolean(name) ?: default

        fun optionalStringList(name: String): List<String>? = when (val value = values[name]) {
            null -> null
            is List<*> -> value.mapIndexed { index, item ->
                item as? String ?: throw McpArgumentException("$name[$index] must be a string.")
            }
            else -> throw McpArgumentException("$name must be an array of strings.")
        }

        fun optionalInstant(name: String): Instant? = optionalString(name)?.let { value ->
            runCatching { Instant.parse(value) }
                .getOrElse { throw McpArgumentException("$name must be an ISO-8601 UTC timestamp.") }
        }

        private fun optionalNumber(name: String): Number? = when (val value = values[name]) {
            null -> null
            is Number -> value
            else -> throw McpArgumentException("$name must be an integer.")
        }

        private fun optionalBoolean(name: String): Boolean? = when (val value = values[name]) {
            null -> null
            is Boolean -> value
            else -> throw McpArgumentException("$name must be a boolean.")
        }
    }

    private class McpArgumentException(message: String) : RuntimeException(message)

    private companion object {
        const val APPLICATION_JSON = "application/json"

        fun objectSchema(
            properties: Map<String, Map<String, Any>> = emptyMap(),
            required: List<String> = emptyList(),
        ): Map<String, Any> = linkedMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false,
        ).apply {
            if (required.isNotEmpty()) put("required", required)
        }

        fun pagedSchema(
            additional: Map<String, Map<String, Any>> = emptyMap(),
            maximum: Int,
            defaultLimit: Int,
        ): Map<String, Any> = objectSchema(
            properties = linkedMapOf(
                "limit" to integerProperty("Maximum items to return.", 1, maximum.toLong(), defaultLimit),
                "offset" to integerProperty("Zero-based pagination offset.", 0, Int.MAX_VALUE.toLong(), 0),
            ).apply { putAll(additional) },
        )

        fun correlationSchema(): Map<String, Any> = objectSchema(
            properties = linkedMapOf(
                "correlation_id" to stringProperty("Asynchronous operation correlation ID.", minLength = 1, maxLength = 100),
            ),
            required = listOf("correlation_id"),
        )

        fun idProperty(description: String): Map<String, Any> =
            integerProperty(description, minimum = 1, maximum = Long.MAX_VALUE)

        fun languageProperty(description: String = "Response language code."): Map<String, Any> =
            stringProperty(description, values = listOf("ko", "en", "ja"), default = "ko")

        fun viewProperty(): Map<String, Any> =
            stringProperty("Localized or author-original content view.", values = listOf("localized", "original"), default = "localized")

        fun instantProperty(description: String): Map<String, Any> =
            stringProperty(description).toMutableMap().apply { put("format", "date-time") }

        fun stringProperty(
            description: String? = null,
            minLength: Int? = null,
            maxLength: Int? = null,
            values: List<String>? = null,
            default: String? = null,
        ): Map<String, Any> = linkedMapOf<String, Any>("type" to "string").apply {
            description?.let { put("description", it) }
            minLength?.let { put("minLength", it) }
            maxLength?.let { put("maxLength", it) }
            values?.let { put("enum", it) }
            default?.let { put("default", it) }
        }

        fun integerProperty(
            description: String,
            minimum: Long,
            maximum: Long,
            default: Int? = null,
        ): Map<String, Any> = linkedMapOf<String, Any>(
            "type" to "integer",
            "description" to description,
            "minimum" to minimum,
            "maximum" to maximum,
        ).apply { default?.let { put("default", it) } }

        fun booleanProperty(description: String, default: Boolean? = null): Map<String, Any> =
            linkedMapOf<String, Any>(
                "type" to "boolean",
                "description" to description,
            ).apply { default?.let { put("default", it) } }

        fun arrayProperty(
            description: String,
            item: Map<String, Any>,
            maxItems: Int,
        ): Map<String, Any> = linkedMapOf(
            "type" to "array",
            "description" to description,
            "items" to item,
            "maxItems" to maxItems,
        )
    }
}
