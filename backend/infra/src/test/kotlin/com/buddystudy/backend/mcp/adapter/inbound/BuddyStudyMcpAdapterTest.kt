package com.buddystudy.backend.mcp.adapter.inbound

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import java.lang.reflect.Proxy

class BuddyStudyMcpAdapterTest {
    @Test
    fun `publishes exactly the supported tools with their schemas and safety hints`() {
        val tools = adapter().tools()
        val expected = expectedToolContracts()

        assertThat(tools.map { it.tool().name() })
            .containsExactlyElementsOf(expected.map(ToolContract::name))
        assertThat(tools).hasSize(16)

        tools.zip(expected).forEach { (specification, contract) ->
            val tool = specification.tool()
            val annotations = tool.annotations()

            assertThat(tool.inputSchema())
                .describedAs("input schema for ${contract.name}")
                .isEqualTo(contract.schema)
            assertThat(annotations.readOnlyHint())
                .describedAs("readOnlyHint for ${contract.name}")
                .isEqualTo(contract.readOnly)
            assertThat(annotations.destructiveHint())
                .describedAs("destructiveHint for ${contract.name}")
                .isEqualTo(contract.destructive)
            assertThat(annotations.idempotentHint())
                .describedAs("idempotentHint for ${contract.name}")
                .isEqualTo(contract.idempotent)
            assertThat(annotations.openWorldHint())
                .describedAs("openWorldHint for ${contract.name}")
                .isEqualTo(contract.openWorld)
        }
    }

    @Test
    fun `returns a structured permission error when the authenticated principal is missing`() {
        var useCaseInvoked = false
        val adapter = adapter(
            proxyUseCase { method, _ ->
                useCaseInvoked = true
                error("Unexpected use-case call: $method")
            },
        )

        val result = call(adapter, "get_my_context", emptyMap(), McpTransportContext.EMPTY)

        assertThat(result.isError()).isTrue()
        assertThat(errorDetails(result)).containsExactly(
            org.assertj.core.data.MapEntry.entry("code", "PERMISSION_DENIED"),
            org.assertj.core.data.MapEntry.entry("status", 403),
            org.assertj.core.data.MapEntry.entry("message", "Permission is denied."),
        )
        assertThat(useCaseInvoked).isFalse()
    }

    @Test
    fun `forwards false delete confirmation and structures the validation error`() {
        var forwardedPrincipal: Principal? = null
        var forwardedStudyId: Long? = null
        var forwardedConfirmation: Boolean? = null
        val adapter = adapter(
            proxyUseCase { method, arguments ->
                assertThat(method).isEqualTo("deleteStudy")
                forwardedPrincipal = arguments[0] as Principal
                forwardedStudyId = arguments[1] as Long
                forwardedConfirmation = arguments[2] as Boolean
                throw ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    ApiErrorCode.VALIDATION_ERROR,
                    "confirm must be true.",
                )
            },
        )

        val result = call(
            adapter = adapter,
            toolName = "delete_study",
            arguments = mapOf("study_id" to 42L, "confirm" to false),
            context = authenticatedContext,
        )

        assertThat(forwardedPrincipal).isEqualTo(principal)
        assertThat(forwardedStudyId).isEqualTo(42L)
        assertThat(forwardedConfirmation).isFalse()
        assertThat(result.isError()).isTrue()
        assertThat(errorDetails(result)).containsExactly(
            org.assertj.core.data.MapEntry.entry("code", "VALIDATION_ERROR"),
            org.assertj.core.data.MapEntry.entry("status", 422),
            org.assertj.core.data.MapEntry.entry("message", "confirm must be true."),
        )
    }

    @Test
    fun `does not write sensitive tool arguments to logs when a call fails`() {
        val secretAnswer = "private-answer-do-not-log-7f871d1a"
        val adapter = adapter(
            proxyUseCase { method, _ ->
                assertThat(method).isEqualTo("submitAnswer")
                throw IllegalStateException("Failure while handling $secretAnswer")
            },
        )
        val logger = LoggerFactory.getLogger(BuddyStudyMcpAdapter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val result = call(
                adapter = adapter,
                toolName = "submit_answer",
                arguments = mapOf("record_id" to 91L, "answer" to secretAnswer),
                context = authenticatedContext,
            )

            assertThat(result.isError()).isTrue()
            assertThat(errorDetails(result)).containsEntry("code", "INTERNAL_SERVER_ERROR")
            assertThat(appender.list.map(ILoggingEvent::getFormattedMessage))
                .anyMatch { it.contains("operation=submit_answer") }
                .noneMatch { it.contains(secretAnswer) }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun adapter(useCase: BuddyStudyMcpUseCase = proxyUseCase { method, _ ->
        error("Unexpected use-case call: $method")
    }): BuddyStudyMcpAdapter = BuddyStudyMcpAdapter(
        buddyStudy = useCase,
        objectMapper = jacksonObjectMapper().findAndRegisterModules(),
    )

    private fun call(
        adapter: BuddyStudyMcpAdapter,
        toolName: String,
        arguments: Map<String, Any>,
        context: McpTransportContext,
    ): McpSchema.CallToolResult {
        val specification = adapter.tools().single { it.tool().name() == toolName }
        val request = McpSchema.CallToolRequest.builder(toolName)
            .arguments(arguments)
            .build()
        return specification.callHandler().apply(context, request).block()
            ?: error("Tool handler returned no result for $toolName")
    }

    @Suppress("UNCHECKED_CAST")
    private fun errorDetails(result: McpSchema.CallToolResult): Map<String, Any> {
        val payload = result.structuredContent() as Map<String, Any>
        return payload["error"] as Map<String, Any>
    }

    private fun proxyUseCase(handler: (String, List<Any?>) -> Any?): BuddyStudyMcpUseCase =
        Proxy.newProxyInstance(
            BuddyStudyMcpUseCase::class.java.classLoader,
            arrayOf(BuddyStudyMcpUseCase::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "BuddyStudyMcpUseCaseTestProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> handler(method.name, arguments?.toList().orEmpty())
            }
        } as BuddyStudyMcpUseCase

    private fun expectedToolContracts(): List<ToolContract> = listOf(
        ToolContract(
            name = "get_my_context",
            schema = objectSchema(),
            readOnly = true,
        ),
        ToolContract(
            name = "update_my_learning_context",
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
        ),
        ToolContract(
            name = "list_studies",
            schema = pagedSchema(
                additional = linkedMapOf(
                    "query" to stringProperty("Optional topic search.", maxLength = 200),
                    "language" to languageProperty(),
                ),
                maximum = 500,
                defaultLimit = 100,
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "get_study",
            schema = objectSchema(
                properties = linkedMapOf(
                    "study_id" to idProperty("Owned study node ID."),
                    "language" to languageProperty(),
                ),
                required = listOf("study_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "create_study",
            schema = objectSchema(
                properties = linkedMapOf(
                    "topic" to stringProperty("Root study topic.", minLength = 1, maxLength = 255),
                    "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                    "interval_minutes" to integerProperty("Schedule interval from 1 to 1440 minutes.", 1, 1_440, 15),
                    "enabled" to booleanProperty("Whether scheduled question delivery is enabled.", true),
                    "notification_sound" to stringProperty("Optional APNs sound name.", maxLength = 100),
                    "custom_prompt" to stringProperty("Optional custom question-generation guidance.", maxLength = 4_000),
                ),
                required = listOf("topic"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = true,
        ),
        ToolContract(
            name = "create_study_topic",
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
        ),
        ToolContract(
            name = "delete_study",
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
        ),
        ToolContract(
            name = "list_pending_questions",
            schema = pagedSchema(maximum = 100, defaultLimit = 30),
            readOnly = true,
        ),
        ToolContract(
            name = "request_question",
            schema = objectSchema(
                properties = linkedMapOf(
                    "study_id" to idProperty("Study topic ID."),
                    "idempotency_key" to stringProperty(
                        description = "Stable caller-generated key reused when retrying the same request.",
                        minLength = 1,
                        maxLength = 100,
                    ),
                ),
                required = listOf("study_id", "idempotency_key"),
            ),
            readOnly = false,
            idempotent = true,
            openWorld = true,
        ),
        ToolContract(
            name = "get_question_process",
            schema = correlationSchema(),
            readOnly = true,
        ),
        ToolContract(
            name = "submit_answer",
            schema = objectSchema(
                properties = linkedMapOf(
                    "record_id" to idProperty("Pending question record ID."),
                    "answer" to stringProperty(
                        "The user's answer. Do not invent or rewrite it.",
                        minLength = 1,
                        maxLength = 50_000,
                    ),
                    "source_language" to languageProperty("Optional language of the answer."),
                ),
                required = listOf("record_id", "answer"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = false,
            openWorld = true,
        ),
        ToolContract(
            name = "get_grading_process",
            schema = objectSchema(
                properties = linkedMapOf(
                    "correlation_id" to stringProperty("Grading correlation ID.", minLength = 1, maxLength = 100),
                    "after_event_id" to integerProperty("Return events after this cursor.", 0, Long.MAX_VALUE, 0),
                ),
                required = listOf("correlation_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "list_records",
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
        ),
        ToolContract(
            name = "get_record",
            schema = objectSchema(
                properties = linkedMapOf(
                    "record_id" to idProperty("Owned question record ID."),
                    "language" to languageProperty(),
                    "view" to viewProperty(),
                ),
                required = listOf("record_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "get_topic_stats",
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
        ),
        ToolContract(
            name = "get_study_growth",
            schema = objectSchema(
                properties = linkedMapOf(
                    "start_at" to instantProperty("Optional inclusive UTC start timestamp."),
                    "end_at" to instantProperty("Optional exclusive UTC end timestamp."),
                ),
            ),
            readOnly = true,
        ),
    )

    private data class ToolContract(
        val name: String,
        val schema: Map<String, Any>,
        val readOnly: Boolean,
        val destructive: Boolean = false,
        val idempotent: Boolean = readOnly,
        val openWorld: Boolean = false,
    )

    private companion object {
        val principal = Principal(
            userId = 7,
            deviceId = "mcp-test-device",
            sessionId = 11,
            anonymous = false,
        )

        val authenticatedContext: McpTransportContext = McpTransportContext.create(
            mapOf(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to principal),
        )

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
                "correlation_id" to stringProperty(
                    "Asynchronous operation correlation ID.",
                    minLength = 1,
                    maxLength = 100,
                ),
            ),
            required = listOf("correlation_id"),
        )

        fun idProperty(description: String): Map<String, Any> =
            integerProperty(description, minimum = 1, maximum = Long.MAX_VALUE)

        fun languageProperty(description: String = "Response language code."): Map<String, Any> =
            stringProperty(description, values = listOf("ko", "en", "ja"), default = "ko")

        fun viewProperty(): Map<String, Any> =
            stringProperty(
                "Localized or author-original content view.",
                values = listOf("localized", "original"),
                default = "localized",
            )

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
