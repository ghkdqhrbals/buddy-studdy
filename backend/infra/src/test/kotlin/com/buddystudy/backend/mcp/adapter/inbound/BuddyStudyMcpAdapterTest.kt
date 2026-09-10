package com.buddystudy.backend.mcp.adapter.inbound

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.mcp.application.model.McpDeletionResponse
import com.buddystudy.backend.study.application.model.RootStudyCreationResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.QuestionItemResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.port.inbound.CreateRootStudyCommand
import com.buddystudy.backend.study.application.port.inbound.ExpectedStudyMetadata
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.study.domain.entity.QuestionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import java.lang.reflect.Proxy
import java.time.Instant

class BuddyStudyMcpAdapterTest {
    @Test
    fun `publishes voice tutor session and quota resources`() {
        assertThat(adapter().resources().map { it.resource().uri() })
            .contains(
                "buddystudy://voice-tutor/sessions/recent",
                "buddystudy://voice-tutor/quota",
            )
    }

    @Test
    fun `publishes exactly the supported tools with their schemas and safety hints`() {
        val tools = adapter().tools()
        val expected = expectedToolContracts()

        assertThat(tools.map { it.tool().name() })
            .containsExactlyElementsOf(expected.map(ToolContract::name))
        assertThat(tools).hasSize(23)

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
    fun `pending question handler dispatches exact topic overload only when a topic is supplied`() {
        val calls = mutableListOf<List<Any?>>()
        val adapter = adapter(proxyUseCase { name, arguments ->
            assertThat(name).isEqualTo("listPendingQuestions")
            calls += arguments.dropLast(1)
            RecordsPageResponse(emptyList(), 0, 30, 0)
        })

        assertThat(call(adapter, "list_pending_questions", emptyMap(), authenticatedContext).isError()).isFalse()
        assertThat(call(adapter, "list_pending_questions", mapOf("study_id" to 42L, "limit" to 3, "offset" to 1), authenticatedContext).isError()).isFalse()

        assertThat(calls).containsExactly(listOf(principal, 30, 0), listOf(principal, 3, 1, 42L))
    }

    @Test
    fun `skip question returns the exact canonical skipped record without invoking generation`() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val record = StudyRecordResponse(
            id = "91", question = QuestionItemResponse("의존성 주입은 무엇인가요?", createdAt = Instant.EPOCH),
            answer = null, gradingResult = null, topic = "DI", difficulty = 3, answeredAt = null,
            isPublic = false, studyId = 42L, questionStatus = QuestionStatus.SKIPPED,
        )
        val adapter = adapter(proxyUseCase { name, arguments ->
            calls += name to arguments.dropLast(1)
            record
        })

        val result = call(adapter, "skip_question", mapOf("record_id" to 91L), authenticatedContext)

        assertThat(result.isError()).isFalse()
        assertThat(calls).containsExactly("skipQuestion" to listOf(principal, 91L))
        val payload = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(result.structuredContent())
        assertThat(payload.path("id").asText()).isEqualTo("91")
        assertThat(payload.path("studyId").asLong()).isEqualTo(42L)
        assertThat(payload.path("questionStatus").asText().lowercase()).isEqualTo("skipped")
    }

    @Test
    fun `question lifecycle rejects fractional and overflowing ids or pagination without rounding to another record`() {
        var called = false
        val adapter = adapter(proxyUseCase { _, _ -> called = true; error("Invalid arguments must not reach a use case") })
        val invalidCalls = listOf(
            "skip_question" to emptyMap<String, Any>(),
            "skip_question" to mapOf("record_id" to 91.5),
            "skip_question" to mapOf("record_id" to java.math.BigInteger("9223372036854775808")),
            "skip_question" to mapOf("record_id" to "91"),
            "list_pending_questions" to mapOf("study_id" to 42.5),
            "list_pending_questions" to mapOf("study_id" to Double.NaN),
            "list_pending_questions" to mapOf("limit" to 2.5),
            "list_pending_questions" to mapOf("offset" to 4_294_967_296L),
        )
        for ((name, arguments) in invalidCalls) {
            val result = call(adapter, name, arguments, authenticatedContext)
            assertThat(result.isError()).isTrue()
            assertThat(errorDetails(result)["code"]).isEqualTo("VALIDATION_ERROR")
        }
        assertThat(called).isFalse()
    }

    @Test
    fun `skip preserves canonical status conflict and requires an authenticated caller`() {
        var calls = 0
        val adapter = adapter(proxyUseCase { name, _ ->
            assertThat(name).isEqualTo("skipQuestion")
            calls++
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR,
                "Only an unanswered, ungraded question can be skipped.")
        })

        val missingPrincipal = call(adapter, "skip_question", mapOf("record_id" to 91L), McpTransportContext.EMPTY)
        assertThat(errorDetails(missingPrincipal)["code"]).isEqualTo("PERMISSION_DENIED")
        assertThat(calls).isZero()
        val conflict = call(adapter, "skip_question", mapOf("record_id" to 91L), authenticatedContext)
        assertThat(errorDetails(conflict)["code"]).isEqualTo("VALIDATION_ERROR")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `external catalog exposes only the create-only contract for new root creation`() {
        val tools = adapter().tools().map { it.tool() }
        val names = tools.map { it.name() }

        assertThat(names).contains("create_root_study")
        assertThat(names).doesNotContain("create_study")
        assertThat(names.filter { it.contains("root") && it.contains("create") })
            .containsExactly("create_root_study")

        val rootCreation = tools.single { it.name() == "create_root_study" }
        assertThat(rootCreation.title()).isEqualTo("Create a new root study")
        assertThat(rootCreation.description())
            .contains("without replacing any existing study settings")
        val properties = rootCreation.inputSchema()["properties"] as Map<*, *>
        assertThat(properties.keys).containsExactlyInAnyOrder("topic", "difficulty_level")
        assertThat(properties.keys)
            .doesNotContain("interval_minutes", "enabled", "notification_sound", "custom_prompt")
    }

    @Test
    fun `node and voice history tools default to original content without changing ordinary record defaults`() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val adapter = adapter(proxyUseCase { method, arguments ->
            calls += method to arguments.dropLast(1)
            when (method) {
                "listStudyLearningRecords" -> StudyLearningRecordsPageResponse(emptyList(), null, false, 5)
                "getVoiceLearningRecord" -> voiceRecord()
                else -> error("Unexpected operation")
            }
        })

        val page = call(adapter, "list_study_learning_records", mapOf("study_id" to 42L), authenticatedContext)
        val detail = call(adapter, "get_voice_learning_record", mapOf("record_id" to 91L), authenticatedContext)

        assertThat(page.isError()).isFalse()
        assertThat(detail.isError()).isFalse()
        assertThat(calls.map { it.first }).containsExactly("listStudyLearningRecords", "getVoiceLearningRecord")
        assertThat(calls[0].second).containsExactly(principal, 42L, "node", 5, null, "ko", "original")
        assertThat(calls[1].second).containsExactly(principal, 91L, "ko", "original")
        val payload = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(detail.structuredContent())
        assertThat(payload.path("answer").asText()).isEqualTo("  오래전에 쓴 키부터요.\n")
        assertThat(payload.path("score").asInt()).isEqualTo(85)
        assertThat(payload.path("questionTurnId").asLong()).isEqualTo(11)
        assertThat(payload.path("sessionId").asText()).isEqualTo("synthetic-prior-session")
    }

    @Test
    fun `node history forwards its opaque cursor and exact subtree scope without converting to an offset`() {
        var forwarded = emptyList<Any?>()
        val adapter = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("listStudyLearningRecords")
            forwarded = arguments.dropLast(1)
            StudyLearningRecordsPageResponse(emptyList(), "next-position", true, 3)
        })

        val result = call(adapter, "list_study_learning_records", mapOf(
            "study_id" to 42L, "scope" to "subtree", "limit" to 3,
            "cursor" to "prior-position", "language" to "ja", "view" to "original",
        ), authenticatedContext)

        assertThat(result.isError()).isFalse()
        assertThat(forwarded).containsExactly(principal, 42L, "subtree", 3, "prior-position", "ja", "original")
        val payload = result.structuredContent() as Map<*, *>
        assertThat(payload["nextCursor"]).isEqualTo("next-position")
        assertThat(payload["hasMore"]).isEqualTo(true)
    }

    @Test
    fun `new private history tools cannot invoke use cases without a principal and preserve owner rejection`() {
        for ((name, arguments) in listOf(
            "list_study_learning_records" to mapOf("study_id" to 42L),
            "get_voice_learning_record" to mapOf("record_id" to 91L),
        )) {
            var invoked = false
            val adapter = adapter(proxyUseCase { _, _ ->
                invoked = true
                throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
            })
            assertThat(errorDetails(call(adapter, name, arguments, McpTransportContext.EMPTY)))
                .containsEntry("code", "PERMISSION_DENIED")
            assertThat(invoked).isFalse()
            assertThat(errorDetails(call(adapter, name, arguments, authenticatedContext)))
                .containsEntry("code", "RECORD_NOT_FOUND").containsEntry("status", 404)
            assertThat(invoked).isTrue()
        }
    }

    @Test
    fun `list studies forwards an optional parent to the owned child page overload`() {
        var forwarded: List<Any?> = emptyList()
        val adapter = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("listStudies")
            forwarded = arguments.dropLast(1)
            StudyPageResponse(emptyList(), 0, 3, 1, Instant.EPOCH)
        })

        val result = call(
            adapter,
            "list_studies",
            mapOf("parent_study_id" to 42L, "limit" to 3, "offset" to 1, "query" to "Redis", "language" to "en"),
            authenticatedContext,
        )

        assertThat(result.isError()).isFalse()
        assertThat(forwarded).containsExactly(principal, 3, 1, "Redis", "en", 42L)
    }

    @Test
    fun `list studies without a parent keeps the original unfiltered overload`() {
        var forwarded: List<Any?> = emptyList()
        val adapter = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("listStudies")
            forwarded = arguments.dropLast(1)
            StudyPageResponse(emptyList(), 0, 100, 0, Instant.EPOCH)
        })

        val result = call(adapter, "list_studies", emptyMap(), authenticatedContext)

        assertThat(result.isError()).isFalse()
        assertThat(forwarded).containsExactly(principal, 100, 0, null, "ko")
    }

    @Test
    fun `foreign or missing list parent remains a not found tool result`() {
        val adapter = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("listStudies")
            assertThat(arguments[5]).isEqualTo(42L)
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        })

        val result = call(adapter, "list_studies", mapOf("parent_study_id" to 42L), authenticatedContext)

        assertThat(result.isError()).isTrue()
        assertThat(errorDetails(result))
            .containsEntry("code", "STUDY_SETTINGS_MISSING")
            .containsEntry("status", 404)
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
    fun `metadata handler preserves omitted fields without applying creation defaults`(): Unit {
        val calls = mutableListOf<List<Any?>>()
        val adapter = adapter(proxyUseCase { name, arguments ->
            assertThat(name).isEqualTo("updateStudy")
            calls += arguments.dropLast(1)
            studyRoom()
        })
        listOf(
            mapOf("study_id" to 42L, "topic" to "Redis Streams"),
            mapOf("study_id" to 42L, "difficulty_level" to 1),
            mapOf("study_id" to 42L, "topic" to "Redis", "difficulty_level" to 10),
        ).forEach { arguments ->
            assertThat(call(adapter, "update_study", arguments, authenticatedContext).isError()).isFalse()
        }
        assertThat(calls).containsExactly(
            listOf(principal, 42L, UpdateStudyCommand(topic = "Redis Streams")),
            listOf(principal, 42L, UpdateStudyCommand(difficultyLevel = 1)),
            listOf(principal, 42L, UpdateStudyCommand(topic = "Redis", difficultyLevel = 10)),
        )
    }

    @Test
    fun `voice metadata fence stays private and reaches the transactional update command`() {
        var forwarded: UpdateStudyCommand? = null
        val adapter = adapter(proxyUseCase { name, arguments ->
            assertThat(name).isEqualTo("updateStudy")
            forwarded = arguments[2] as UpdateStudyCommand
            studyRoom()
        })
        val arguments = mapOf<String, Any>(
            "study_id" to 42L,
            "topic" to "Redis Streams",
            BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to "Redis",
            BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to 4,
            BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to 0L,
        )

        assertThat(call(adapter, "update_study", arguments, authenticatedContext).isError()).isFalse()
        assertThat(forwarded).isEqualTo(
            UpdateStudyCommand(
                topic = "Redis Streams",
                expectedCurrent = ExpectedStudyMetadata(null, "Redis", 4),
            ),
        )
        val publicProperties = adapter.tools().single { it.tool().name() == "update_study" }
            .tool().inputSchema()["properties"] as Map<*, *>
        assertThat(publicProperties.keys).doesNotContain(
            BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT,
            BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT,
            BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT,
        )

        val incomplete = call(
            adapter,
            "update_study",
            arguments - BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT,
            authenticatedContext,
        )
        assertThat(incomplete.isError()).isTrue()
        assertThat(errorDetails(incomplete)["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `create-only root handler applies only its difficulty default and returns bounded metadata`(): Unit {
        val forwarded = mutableListOf<List<Any?>>()
        val adapter = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("createRootStudy")
            forwarded += arguments.dropLast(1)
            val command = arguments[1] as CreateRootStudyCommand
            RootStudyCreationResponse(
                created = true,
                id = 42L,
                parentStudyId = null,
                topic = command.topic,
                difficultyLevel = command.difficultyLevel,
                enabled = true,
                activeForQuestions = true,
            )
        })

        val defaulted = call(
            adapter,
            "create_root_study",
            mapOf("topic" to "Operating Systems"),
            authenticatedContext,
        )
        val explicit = call(
            adapter,
            "create_root_study",
            mapOf("topic" to "Distributed Systems", "difficulty_level" to 8),
            authenticatedContext,
        )

        assertThat(defaulted.isError()).isFalse()
        assertThat(explicit.isError()).isFalse()
        assertThat(forwarded).containsExactly(
            listOf(principal, CreateRootStudyCommand("Operating Systems", 5)),
            listOf(principal, CreateRootStudyCommand("Distributed Systems", 8)),
        )
        val payload = explicit.structuredContent() as Map<*, *>
        assertThat(payload.keys).containsExactlyInAnyOrder(
            "created",
            "id",
            "parentStudyId",
            "topic",
            "difficultyLevel",
            "enabled",
            "activeForQuestions",
        )
        assertThat(payload["parentStudyId"]).isNull()
        assertThat(payload["difficultyLevel"]).isEqualTo(8)
    }

    @Test
    fun `create-only root schema rejects model-controlled settings and invalid metadata`(): Unit {
        val schema = adapter().tools().single { it.tool().name() == "create_root_study" }.tool().inputSchema()
        val validator = McpJsonSchemaValidatorProvider.create()
        val valid = listOf(
            mapOf("topic" to "응"),
            mapOf("topic" to "x".repeat(255), "difficulty_level" to 1),
            mapOf("topic" to "Redis", "difficulty_level" to 10),
        )
        val invalid = listOf(
            emptyMap(),
            mapOf("topic" to null),
            mapOf("topic" to ""),
            mapOf("topic" to "x".repeat(256)),
            mapOf("topic" to "Redis", "difficulty_level" to null),
            mapOf("topic" to "Redis", "difficulty_level" to 3.5),
            mapOf("topic" to "Redis", "difficulty_level" to 0),
            mapOf("topic" to "Redis", "difficulty_level" to 11),
            mapOf("topic" to "Redis", "enabled" to false),
            mapOf("topic" to "Redis", "parent_study_id" to 42L),
            mapOf("topic" to "Redis", "custom_prompt" to "Replace it"),
        )

        valid.forEach { assertThat(validator.validate(schema, it).valid()).isTrue() }
        invalid.forEach { assertThat(validator.validate(schema, it).valid()).isFalse() }
        assertThat(errorDetails(call(adapter(), "create_root_study", mapOf("topic" to "Redis"), McpTransportContext.EMPTY)))
            .containsEntry("code", "PERMISSION_DENIED")
    }

    @Test
    fun `SDK metadata schema rejects absent null unknown fractional and out of range patches`(): Unit {
        val schema = adapter().tools().single { it.tool().name() == "update_study" }.tool().inputSchema()
        val validator = McpJsonSchemaValidatorProvider.create()
        val valid = listOf(
            mapOf("study_id" to 42L, "topic" to "응"),
            mapOf("study_id" to 42L, "difficulty_level" to 1),
            mapOf("study_id" to 42L, "topic" to "x".repeat(255), "difficulty_level" to 10),
        )
        val invalid = listOf(
            mapOf("study_id" to 42L), mapOf("topic" to "Name"),
            mapOf("study_id" to 42L, "topic" to null), mapOf("study_id" to 42L, "difficulty_level" to null),
            mapOf("study_id" to 42L, "topic" to ""), mapOf("study_id" to 42L, "topic" to "x".repeat(256)),
            mapOf("study_id" to 42L, "difficulty_level" to 3.5), mapOf("study_id" to 42L, "difficulty_level" to 0),
            mapOf("study_id" to 42L, "difficulty_level" to 11), mapOf("study_id" to 42L, "topic" to "Name", "enabled" to false),
        )
        valid.forEach { assertThat(validator.validate(schema, it).valid()).isTrue() }
        invalid.forEach { assertThat(validator.validate(schema, it).valid()).isFalse() }
        val fractional = call(adapter(), "update_study", mapOf("study_id" to 42L, "difficulty_level" to 3.5), authenticatedContext)
        assertThat(errorDetails(fractional)).containsEntry("code", "VALIDATION_ERROR")
    }

    @Test
    fun `guarded deletion forwards exact IDs and reports a changed subtree without false success`(): Unit {
        var forwarded = emptyList<Any?>()
        val expected = listOf(44L, 42L, 43L)
        val guarded = adapter(proxyUseCase { method, arguments ->
            assertThat(method).isEqualTo("deleteStudy")
            forwarded = arguments.dropLast(1)
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.STUDY_TREE_CHANGED, "Confirm the current subtree again.")
        })
        val result = call(guarded, "delete_study", mapOf("study_id" to 42L, "confirm" to true, "expected_study_ids" to expected), authenticatedContext)
        assertThat(forwarded).containsExactly(principal, 42L, true, expected)
        assertThat(result.isError()).isTrue()
        assertThat(errorDetails(result)).containsEntry("code", "STUDY_TREE_CHANGED").containsEntry("status", 409)

        val legacy = adapter(proxyUseCase { _, arguments ->
            assertThat(arguments.dropLast(1)).containsExactly(principal, 42L, true, null)
            McpDeletionResponse(true, 42L)
        })
        assertThat(call(legacy, "delete_study", mapOf("study_id" to 42L, "confirm" to true), authenticatedContext).isError()).isFalse()
    }

    @Test
    fun `guarded delete schema bounds unique positive IDs and mutation handlers require a principal`(): Unit {
        val adapter = adapter()
        val schema = adapter.tools().single { it.tool().name() == "delete_study" }.tool().inputSchema()
        val validator = McpJsonSchemaValidatorProvider.create()
        assertThat(validator.validate(schema, mapOf("study_id" to 42L, "confirm" to true, "expected_study_ids" to listOf(42L))).valid()).isTrue()
        for (ids in listOf(emptyList(), listOf(42L, 42L), listOf(0L, 42L), listOf(42.5), (1L..129L).toList())) {
            assertThat(validator.validate(schema, mapOf("study_id" to 42L, "confirm" to true, "expected_study_ids" to ids)).valid()).isFalse()
        }
        for ((name, arguments) in listOf(
            "update_study" to mapOf("study_id" to 42L, "topic" to "New name"),
            "delete_study" to mapOf("study_id" to 42L, "confirm" to true),
        )) {
            assertThat(errorDetails(call(adapter, name, arguments, McpTransportContext.EMPTY))).containsEntry("code", "PERMISSION_DENIED")
        }
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
                    "parent_study_id" to idProperty("Optional owned parent study ID. Returns direct children only, not the parent or deeper descendants."),
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
            name = "update_study",
            schema = objectSchema(
                properties = linkedMapOf(
                    "study_id" to idProperty("Exact owned study node ID to update; never identify a target by name alone."),
                    "topic" to stringProperty("New study topic. Omit to preserve the current name.", minLength = 1, maxLength = 255),
                    "difficulty_level" to integerProperty("New configured difficulty from 1 to 10. Omit to preserve the current level.", 1, 10),
                ),
                required = listOf("study_id"),
            ).toMutableMap().apply { put("minProperties", 2) },
            readOnly = false,
            destructive = true,
            idempotent = true,
        ),
        ToolContract(
            name = "create_root_study",
            schema = objectSchema(
                properties = linkedMapOf(
                    "topic" to stringProperty("Root study topic.", minLength = 1, maxLength = 255),
                    "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                ),
                required = listOf("topic"),
            ),
            readOnly = false,
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
                    "expected_study_ids" to arrayProperty(
                        "Exact confirmed subtree IDs including study_id. A mismatch prevents deletion.",
                        idProperty("Confirmed study node ID."),
                        maxItems = 128,
                    ).toMutableMap().apply {
                        put("minItems", 1)
                        put("uniqueItems", true)
                    },
                ),
                required = listOf("study_id", "confirm"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = true,
        ),
        ToolContract(
            name = "list_pending_questions",
            schema = pagedSchema(
                additional = linkedMapOf("study_id" to idProperty("Optional exact owned study topic filter, excluding descendants.")),
                maximum = 100, defaultLimit = 30,
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "skip_question",
            schema = objectSchema(
                properties = linkedMapOf("record_id" to idProperty("Exact owned unanswered question record ID to skip.")),
                required = listOf("record_id"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = true,
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
                    "record_id" to idProperty("Owned canonical record ID for either record type."),
                    "language" to languageProperty(),
                    "view" to viewProperty(),
                ),
                required = listOf("record_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "list_study_learning_records",
            schema = objectSchema(
                properties = linkedMapOf(
                    "study_id" to idProperty("Owned study node ID whose learning history to read."),
                    "scope" to stringProperty("node reads only this node; subtree includes saved descendants.", values = listOf("node", "subtree"), default = "node"),
                    "limit" to integerProperty("Maximum learning records to return. Prefer a small page for voice calls.", 1, 30, 5),
                    "cursor" to stringProperty("Opaque nextCursor from the same study and scope; omit on the first page.", minLength = 1, maxLength = 512),
                    "language" to languageProperty(),
                    "view" to viewProperty(default = "original"),
                ),
                required = listOf("study_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "get_voice_learning_record",
            schema = objectSchema(
                properties = linkedMapOf(
                    "record_id" to idProperty("Owned voiceRecord.id, not a question record ID or the voice: prefixed envelope ID."),
                    "language" to languageProperty(),
                    "view" to viewProperty(default = "original"),
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
        ToolContract(
            name = "list_voice_tutor_sessions",
            schema = objectSchema(
                properties = linkedMapOf(
                    "limit" to integerProperty("Maximum sessions to return.", 1, 100, 30),
                    "cursor" to stringProperty("Opaque cursor returned by the previous page.", maxLength = 512),
                ),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "get_voice_tutor_session",
            schema = objectSchema(
                properties = linkedMapOf(
                    "session_id" to stringProperty("Owned Voice Tutor session UUID.", minLength = 36, maxLength = 36),
                ),
                required = listOf("session_id"),
            ),
            readOnly = true,
        ),
        ToolContract(
            name = "get_voice_tutor_quota",
            schema = objectSchema(),
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
        fun studyRoom() = StudyRoomResponse(
            id = 42L, parentStudyId = 40L, sortOrder = 2, topic = "Redis Streams", difficultyLevel = 3,
            intervalMinutes = 30, enabled = false, activeForQuestions = true, notificationSound = "bell.caf",
            customPrompt = "Preserved", openaiModel = "fixture-model", maxHistoryCount = 100,
            nextDueAt = Instant.EPOCH, lastSentAt = null, lastError = null, pendingQuestion = null,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )

        fun voiceRecord() = VoiceStudyLearningRecordResponse(
            id = "91", sessionId = "synthetic-prior-session", studyId = 42L, parentStudyId = 40L,
            topic = "Redis", difficulty = 3, createdAt = Instant.EPOCH, kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
            question = "어떤 키를 제거하나요?", answer = "  오래전에 쓴 키부터요.\n", score = 85,
            strengths = listOf("최근 사용 시점을 짚음"), improvements = emptyList(), depthSummary = "LRU를 살펴봄",
            feedback = "85점입니다.", questionTurnId = 11L, answerTurnIds = listOf(12L), feedbackTurnIds = listOf(13L),
            sourceLanguage = "ko", requestedLanguage = "ko", displayLanguage = "ko", translationPending = false,
        )

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

        fun viewProperty(default: String = "localized"): Map<String, Any> =
            stringProperty(
                "Localized or author-original content view.",
                values = listOf("localized", "original"),
                default = default,
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
