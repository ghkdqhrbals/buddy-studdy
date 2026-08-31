package com.buddystudy.backend.voice.adapter.outbound.openai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Configuration-only fixtures. No provider, credentials, database, or microphone. */
class VoiceTutorWebRtcMcpConfigurationTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `function translation retains MCP schemas and descriptions without credentials or a second catalog`() {
        val tools = voiceTutorRealtimeFunctionTools(definitions)
        assertThat(tools).hasSize(definitions.size)
        tools.zip(definitions).forEach { (tool, definition) ->
            assertThat(tool.keys).containsExactly("type", "name", "description", "parameters")
            assertThat(tool["type"]).isEqualTo("function")
            assertThat(tool["name"]).isEqualTo(definition.name)
            assertThat(tool["description"]).isEqualTo(definition.description)
            assertThat(tool["parameters"]).isSameAs(definition.parameters)
        }
        assertThat(mapper.writeValueAsString(tools)).doesNotContain("Bearer", "access_token", "server_url", "Authorization")
    }

    @Test
    fun `SDP and sideband register identical function definitions and automatic tool choice`() {
        var definitionReads = 0
        val toolPort = object : VoiceTutorMcpToolPort {
            override fun definitions(): List<VoiceTutorMcpToolDefinition> {
                definitionReads += 1
                return definitions
            }
            override suspend fun execute(context: VoiceTutorWebRtcControlContext, toolName: String, arguments: Map<String, Any>): VoiceTutorMcpToolResult =
                error("Configuration must not execute tools.")
        }
        val adapter = OpenAIVoiceTutorWebRtcAdapter(
            properties = BuddyStudyProperties(),
            inputAssessment = object : VoiceTutorInputAssessmentUseCase {
                override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
                    error("Configuration must not assess input.")
            },
            mcpTools = toolPort,
        )
        assertThat(definitionReads).isZero()
        val sdpConfiguration = mapper.readTree(adapter.webRtcSessionConfiguration(
            VoiceTutorRealtimeRequest(userId = 7L, model = "gpt-realtime-test", voice = "marin", instructions = "Finish the sentence."),
        ))
        val handshake = newHandshake()
        val sideband = dispatch(handshake).path("session")

        assertThat(sdpConfiguration.path("tools")).isEqualTo(sideband.path("tools"))
        assertThat(sdpConfiguration.path("tools").size()).isEqualTo(2)
        assertThat(sdpConfiguration.path("tool_choice").asText()).isEqualTo("auto")
        assertThat(sideband.path("tool_choice").asText()).isEqualTo("auto")
        assertThat(sdpConfiguration.path("audio").path("input").path("turn_detection").isNull).isTrue()
        assertThat(sideband.path("audio").path("input").path("turn_detection").isNull).isTrue()
        assertThat(definitionReads).isEqualTo(1)
    }

    @Test
    fun `tools do not release readiness on session created but exact updated acknowledgement does`() {
        lateinit var handshake: VoiceTutorWebRtcSessionHandshake
        val readyCount = AtomicInteger()
        StepVerifier.withVirtualTime {
            handshake = newHandshake()
            dispatch(handshake)
            handshake.awaitConfirmation().doOnSuccess { readyCount.incrementAndGet() }
        }
            .expectSubscription()
            .then { handshake.observeProviderEvent(updated(validSession(), "session.created")) }
            .expectNoEvent(Duration.ofSeconds(1))
            .then {
                assertThat(readyCount.get()).isZero()
                handshake.observeProviderEvent(updated(validSession()))
            }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
        assertThat(readyCount.get()).isEqualTo(1)
    }

    @Test
    fun `tool and object key order plus equivalent JSON numbers do not change a schema`() {
        val snapshots = mutableListOf<VoiceTutorWebRtcConfigurationSnapshot>()
        val handshake = newHandshake(snapshots)
        dispatch(handshake)
        val session = validSession()
        val effective = session.path("tools") as ArrayNode
        val first = effective.remove(0) as ObjectNode
        // The catalog uses Long 1; providers may serialize an equivalent JSON 1.0.
        ((first.path("parameters").path("properties").path("study_id")) as ObjectNode).put("minimum", 1.0)
        val reordered = mapper.createObjectNode().apply {
            set<JsonNode>("parameters", first.path("parameters"))
            set<JsonNode>("description", first.path("description"))
            set<JsonNode>("name", first.path("name"))
            set<JsonNode>("type", first.path("type"))
        }
        effective.add(reordered)

        assertThat(handshake.observeProviderEvent(updated(session))).isTrue()
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)
        assertThat(snapshots.last().verified).isTrue()
        assertThat(snapshots.last().toolsVerified).isTrue()
        assertThat(snapshots.last().expectedToolCount).isEqualTo(2)
        assertThat(snapshots.last().effectiveToolCount).isEqualTo(2)
    }

    @TestFactory
    fun `missing changed duplicate extra or disabled effective tools fail closed before readiness`() =
        invalidToolConfigurations().map { (name, session) ->
            dynamicTest(name) {
                val snapshots = mutableListOf<VoiceTutorWebRtcConfigurationSnapshot>()
                val handshake = newHandshake(snapshots)
                dispatch(handshake)
                assertThatThrownBy { handshake.observeProviderEvent(updated(session)) }
                    .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
                    .hasMessageNotContaining(CALL_ID)
                assertThat(snapshots.last().verified).isFalse()
                assertThat(snapshots.last().toolsVerified).isFalse()
            }
        }

    @Test
    fun `valid tools never excuse automatic provider voice interruption`() {
        val handshake = newHandshake()
        dispatch(handshake)
        val session = validSession()
        (session.path("audio").path("input") as ObjectNode).set<JsonNode>("turn_detection", mapper.valueToTree(
            mapOf("type" to "server_vad", "create_response" to false, "interrupt_response" to false),
        ))
        assertThatThrownBy { handshake.observeProviderEvent(updated(session)) }
            .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
    }

    @Test
    fun `later removal of tools fails even after a successful initial acknowledgement`() {
        val handshake = newHandshake()
        dispatch(handshake)
        handshake.observeProviderEvent(updated(validSession()))
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)

        val removed = validSession().apply { set<ArrayNode>("tools", mapper.createArrayNode()) }
        assertThatThrownBy { handshake.observeProviderEvent(updated(removed)) }
            .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
    }

    @Test
    fun `empty legacy configuration remains compatible but does not permit extra provider tools`() {
        val handshake = VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, {})
        val sessionUpdate = dispatch(handshake).path("session")
        assertThat(sessionUpdate.path("tools").isArray).isTrue()
        assertThat(sessionUpdate.path("tools").size()).isZero()
        assertThat(sessionUpdate.path("tool_choice").asText()).isEqualTo("auto")

        val legacy = validSession().apply { remove("tools"); remove("tool_choice") }
        handshake.observeProviderEvent(updated(legacy))
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)
        assertThatThrownBy { handshake.observeProviderEvent(updated(validSession())) }
            .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
    }

    @Test
    fun `expected definitions are snapshotted and duplicate or nonfunction definitions cannot initialize`() {
        val mutableTool = voiceTutorRealtimeFunctionTools(definitions).first().toMutableMap()
        val handshake = VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, {}, listOf(mutableTool))
        mutableTool["name"] = "changed_after_initialization"
        assertThat(dispatch(handshake).path("session").path("tools").path(0).path("name").asText()).isEqualTo("get_study")

        val original = voiceTutorRealtimeFunctionTools(definitions).first()
        assertThatThrownBy { VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, {}, listOf(original, original)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, {}, listOf(original + ("type" to "mcp"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tool configuration logs contain counts and booleans but never tool payloads or credentials`() {
        val logger = LoggerFactory.getLogger(VoiceTutorWebRtcSessionHandshake::class.java) as Logger
        val oldLevel = logger.level
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(logs)
        try {
            val handshake = VoiceTutorWebRtcSessionHandshake(
                CALL_ID, CONFIRMATION_TIMEOUT, expectedTools = voiceTutorRealtimeFunctionTools(definitions),
            )
            dispatch(handshake)
            val session = validSession().apply {
                firstTool(this).put("description", "PRIVATE_TOOL_ARGUMENTS_AND_RESULTS")
                put("client_secret", "PRIVATE_BEARER_CREDENTIAL")
            }
            assertThatThrownBy { handshake.observeProviderEvent(updated(session)) }
                .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
            assertThat(logs.list).isNotEmpty()
            val emitted = logs.list.joinToString("\n") { event ->
                assertThat(event.throwableProxy).isNull()
                event.formattedMessage + " " + event.argumentArray?.joinToString().orEmpty()
            }
            assertThat(emitted).contains("expectedToolCount=2", "toolsVerified=false")
                .doesNotContain(CALL_ID, "PRIVATE_TOOL_ARGUMENTS_AND_RESULTS", "PRIVATE_BEARER_CREDENTIAL", "study_id", "parameters")
        } finally {
            logger.detachAppender(logs)
            logger.level = oldLevel
            logs.stop()
        }
    }

    private fun newHandshake(snapshots: MutableList<VoiceTutorWebRtcConfigurationSnapshot> = mutableListOf()) =
        VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, { snapshots += it }, voiceTutorRealtimeFunctionTools(definitions))

    private fun dispatch(handshake: VoiceTutorWebRtcSessionHandshake): JsonNode {
        var event: JsonNode? = null
        StepVerifier.create(handshake.initialProviderEvents()).assertNext { event = mapper.readTree(it) }
            .expectComplete().verify(VERIFY_TIMEOUT)
        return event!!
    }

    private fun validSession(): ObjectNode = mapper.valueToTree(
        linkedMapOf(
            "type" to "realtime", "audio" to mapOf("input" to mapOf("turn_detection" to voiceTutorManualWebRtcTurnDetection())),
            "tools" to voiceTutorRealtimeFunctionTools(definitions), "tool_choice" to "auto",
        ),
    )

    private fun updated(session: ObjectNode, eventType: String = "session.updated"): String =
        mapper.writeValueAsString(mapOf("type" to eventType, "session" to session))

    private fun firstTool(session: ObjectNode) = session.path("tools").path(0) as ObjectNode

    private fun invalidToolConfigurations(): List<Pair<String, ObjectNode>> {
        fun variant(name: String, mutate: (ObjectNode) -> Unit) = name to validSession().also(mutate)
        fun parameters(session: ObjectNode) = firstTool(session).path("parameters") as ObjectNode
        return listOf(
            variant("missing tools") { it.remove("tools") },
            variant("null tools") { it.putNull("tools") },
            variant("object instead of tools array") { it.set<ObjectNode>("tools", mapper.createObjectNode()) },
            variant("empty tools") { (it.path("tools") as ArrayNode).removeAll() },
            variant("missing expected tool") { (it.path("tools") as ArrayNode).remove(1) },
            variant("extra unknown tool") { (it.path("tools") as ArrayNode).add(firstTool(it).deepCopy().put("name", "delete_study")) },
            variant("duplicate tool names") { (it.path("tools") as ArrayNode).set(1, firstTool(it).deepCopy()) },
            variant("remote MCP instead of local function") { firstTool(it).put("type", "mcp") },
            variant("missing function type") { firstTool(it).remove("type") },
            variant("numeric function name") { firstTool(it).put("name", 1) },
            variant("changed function name") { firstTool(it).put("name", "delete_study") },
            variant("missing description") { firstTool(it).remove("description") },
            variant("changed description") { firstTool(it).put("description", "Different instructions") },
            variant("missing parameters") { firstTool(it).remove("parameters") },
            variant("additional properties enabled") { parameters(it).put("additionalProperties", true) },
            variant("required field removed") { (parameters(it).path("required") as ArrayNode).removeAll() },
            variant("changed argument type") { (parameters(it).path("properties").path("study_id") as ObjectNode).put("type", "number") },
            variant("weaker minimum") { (parameters(it).path("properties").path("study_id") as ObjectNode).put("minimum", 0) },
            variant("extra untrusted argument") { (parameters(it).path("properties") as ObjectNode).set<JsonNode>("user_id", mapper.valueToTree(mapOf("type" to "integer"))) },
            variant("missing tool choice") { it.remove("tool_choice") },
            variant("disabled tool choice") { it.put("tool_choice", "none") },
            variant("forced tool choice") { it.put("tool_choice", "required") },
            variant("null tool choice") { it.putNull("tool_choice") },
            variant("object tool choice") { it.set<JsonNode>("tool_choice", mapper.valueToTree(mapOf("type" to "function", "name" to "get_study"))) },
        )
    }

    private companion object {
        val CONFIRMATION_TIMEOUT: Duration = Duration.ofSeconds(5)
        val VERIFY_TIMEOUT: Duration = Duration.ofSeconds(2)
        const val CALL_ID = "rtc_private_mcp_configuration"
        val definitions = listOf(
            VoiceTutorMcpToolDefinition(
                name = "get_study", description = "Read one owned study.",
                parameters = linkedMapOf(
                    "type" to "object", "additionalProperties" to false,
                    "properties" to mapOf("study_id" to linkedMapOf("type" to "integer", "minimum" to 1L, "maximum" to Long.MAX_VALUE)),
                    "required" to listOf("study_id"),
                ),
            ),
            VoiceTutorMcpToolDefinition(
                name = "create_study_topic", description = "Create a child of the current study or its descendants.",
                parameters = linkedMapOf(
                    "type" to "object", "additionalProperties" to false,
                    "properties" to linkedMapOf(
                        "parent_study_id" to mapOf("type" to "integer", "minimum" to 1),
                        "topic" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 255),
                    ),
                    "required" to listOf("parent_study_id", "topic"),
                ),
            ),
        )
    }
}
