package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class McpVoiceTutorToolAdapterTest {
    @Test
    fun `advertises only existing allowed schemas and resolves the MCP catalog lazily`() {
        val fixture = Fixture()
        assertThat(fixture.catalogReads).isZero()

        val definitions = fixture.adapter.definitions()

        assertThat(definitions.map { it.name }).containsExactly(
            "list_studies", "get_study", "create_study_topic",
            "list_records", "get_record", "get_topic_stats", "get_study_growth",
        )
        for (definition in definitions) {
            val original = fixture.catalog.single { it.tool().name() == definition.name }.tool()
            assertThat(definition.parameters).isEqualTo(original.inputSchema())
            assertThat(definition.description).startsWith(original.description())
        }
        assertThat(definitions.single { it.name == "create_study_topic" }.description).contains("descendants")
        fixture.adapter.definitions()
        assertThat(fixture.catalogReads).isEqualTo(1)
        assertThat(fixture.calls).isEmpty()
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
        assertThat(fixture.authorizationCalls).isEqualTo(1)
        assertThat(fixture.persistenceCalls).isEqualTo(1)
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
        fixture.authorized = false

        assertCode(fixture.adapter.execute(context(), "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")

        assertThat(fixture.authorizationCalls).isEqualTo(2)
        assertThat(fixture.persistenceCalls).isEqualTo(1)
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
        for (name in listOf("create_study", "delete_study", "request_question", "submit_answer", "update_my_learning_context", "unknown")) {
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
        fixture.handler = { _, _ -> success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "2")) }

        val result = fixture.adapter.execute(
            context(), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "2", "difficulty_level" to 7, "active_for_questions" to false),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102L)
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
        assertThat(fixture.calls.single().arguments).containsEntry("topic", "2").containsEntry("active_for_questions", false)
        assertThat(fixture.calls.single().principal).isSameAs(principal)
        assertThat(fixture.authorizationCalls).isEqualTo(2)
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
                "create_study_topic" -> success(mapOf("id" to 401L, "parentStudyId" to 301L, "topic" to "Streams"))
                else -> error("Unexpected tool")
            }
        }

        val result = fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 301L, "topic" to "Streams"))

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
            val result = fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 201L, "topic" to "Streams"))
            assertCode(result, "STUDY_SCOPE_DENIED")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `a call with no study cannot create topics`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = session().copy(studyId = null) }
        val result = fixture.adapter.execute(context().copy(session = fixture.persistedSession!!), "create_study_topic", mapOf("parent_study_id" to 101L, "topic" to "Streams"))
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
        assertCode(cyclic.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 201L, "topic" to "Streams")), "STUDY_SCOPE_DENIED")
        assertThat(cyclic.calls).hasSize(2)

        val deep = Fixture().apply {
            handler = { _, arguments ->
                val id = (arguments.getValue("study_id") as Number).toLong()
                success(mapOf("id" to id, "parentStudyId" to id + 1))
            }
        }
        assertCode(deep.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 201L, "topic" to "Streams")), "STUDY_SCOPE_DENIED")
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
            assertCode(fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 201L, "topic" to "Streams")), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `MCP permission and conflict failures remain errors and cannot announce a tree change`(): Unit = runBlocking {
        for (code in listOf("PERMISSION_DENIED", "VALIDATION_ERROR")) {
            val fixture = Fixture().apply { handler = { _, _ -> failure(code) } }
            val result = fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 101L, "topic" to "Streams"))
            assertCode(result, code)
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
    }

    @Test
    fun `only positive integral bounded IDs from a successful creation become refresh targets`(): Unit = runBlocking {
        for (invalidId in listOf(-1, 0, 1.5, "102", 1e40)) {
            val fixture = Fixture().apply { handler = { _, _ -> success(mapOf("id" to invalidId, "parentStudyId" to 101L)) } }
            val result = fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 101L, "topic" to "Streams"))
            assertThat(result.isError).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
        val failureWithId = Fixture().apply {
            handler = { _, _ -> McpSchema.CallToolResult.builder().structuredContent(mapOf("id" to 102L)).isError(true).build() }
        }
        assertThat(failureWithId.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 101L, "topic" to "Streams")).createdStudyId).isNull()
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
                "id" to 102L, "parentStudyId" to 101L, "topic" to "Streams",
                "sortOrder" to 2, "difficultyLevel" to 5,
                "customPrompt" to "비공개".repeat(20_000),
            )) }
        }
        val result = fixture.adapter.execute(context(), "create_study_topic", mapOf("parent_study_id" to 101L, "topic" to "Streams"))
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

    private class Fixture(actualMcp: BuddyStudyMcpPort? = null) {
        var authorized = true
        var persistedSession: VoiceTutorSession? = session()
        var authorizationCalls = 0
        var persistenceCalls = 0
        var catalogReads = 0
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
                            calls += Call(request.name(), arguments, caller)
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
        )
    }

    private data class Call(val name: String, val arguments: Map<String, Any>, val principal: Principal?)

    private companion object {
        val now: Instant = Instant.parse("2026-08-31T00:00:00Z")
        val principal = Principal(userId = 7L, deviceId = "synthetic-device", sessionId = 11L, anonymous = false)
        val mapper = jacksonObjectMapper().findAndRegisterModules()

        fun context() = VoiceTutorWebRtcControlContext(session(), "rtc_synthetic_call", principal)

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
