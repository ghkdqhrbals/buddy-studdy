package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class VoiceTutorRealtimeEventPolicyTest {
    private val mapper = JsonMapperProvider.mapper
    private val policy = VoiceTutorRealtimeEventPolicy(mapper)

    @Test
    fun `structured input is bounded session scoped and strips provider extras while client replies stay local`() {
        val session = "00000000-0000-4000-8000-000000000001"
        val base = mapOf("requestId" to "00000000-0000-4000-8000-000000000002", "sessionId" to session,
            "attemptId" to "00000000-0000-4000-8000-000000000003", "sequence" to 1)
        val form = base + mapOf("type" to VoiceTutorRealtimeContract.USER_INPUT_REQUEST_EVENT, "title" to "선택",
            "private" to "not forwarded", "questions" to listOf(mapOf("id" to "one", "prompt" to "목표",
                "selectionMode" to "text", "allowFreeText" to true, "options" to emptyList<Any>())))
        val payload = mapper.readTree(policy.providerDecision(mapper.writeValueAsString(form), session, Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload)
        assertThat(payload.has("private")).isFalse()
        assertThat(payload.path("questions").size()).isEqualTo(1)
        assertThat(policy.providerDecision(mapper.writeValueAsString(form), "wrong-session", Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        assertThat(policy.providerDecision(mapper.writeValueAsString(form), session, Instant.EPOCH).payload).isNull()
        for (type in listOf(VoiceTutorRealtimeContract.USER_INPUT_SUBMIT_EVENT, VoiceTutorRealtimeContract.USER_INPUT_CANCEL_EVENT)) {
            assertThat(policy.shouldForwardClientEvent(mapper.writeValueAsString(base + mapOf("type" to type)))).isFalse()
        }
        assertThatThrownBy { policy.shouldForwardClientEvent(mapper.writeValueAsString(form)) }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)
        assertThat(policy.providerDecision(mapper.writeValueAsString(form + mapOf("questions" to emptyList<Any>())),
            session, Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
    }

    @Test
    fun `operation context exposes only bounded correlation ids and is separate from legacy status`() {
        val fields = mapOf("type" to VoiceTutorRealtimeContract.OPERATION_CONTEXT_EVENT, "operationId" to "call_1",
            "responseId" to "r1", "learnerItemId" to "u1", "tutorItemId" to "t0",
            "answerId" to "00112233-4455-6677-8899-aabbccddeeff",
            "arguments" to "private", "transcript" to "secret")
        val raw = mapper.writeValueAsString(fields)
        val result = policy.providerDecision(raw, "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        assertThat(result.terminate).isFalse()
        val payload = mapper.readTree(result.payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "type", "operationId", "responseId", "learnerItemId", "tutorItemId", "answerId")
        assertThat(payload.path("operationId").asText()).isEqualTo("call_1")
        assertThat(payload.path("responseId").asText()).isEqualTo("r1")
        assertThat(payload.path("learnerItemId").asText()).isEqualTo("u1")
        assertThat(payload.path("tutorItemId").asText()).isEqualTo("t0")
        assertThat(payload.path("answerId").asText()).isEqualTo(fields["answerId"])
        assertThat(policy.providerDecision(raw, "s1", Instant.EPOCH).payload).isNull()
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        // No audio/message may exist for a server-owned startup operation.
        val anonymous = mapper.writeValueAsString(fields - "responseId" - "learnerItemId" - "tutorItemId" - "answerId")
        assertThat(mapper.readTree(policy.providerDecision(anonymous, "s1", Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("type", "operationId")
        for (field in listOf("operationId", "responseId", "learnerItemId", "tutorItemId", "answerId")) {
            for (invalid in listOf<Any?>(null, "", "../private", "x".repeat(192), 1, true)) {
                assertThat(policy.providerDecision(mapper.writeValueAsString(fields + mapOf(field to invalid)),
                    "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload)
                    .describedAs("%s must be a valid supplied provider identifier", field).isNull()
            }
        }
        for (invalid in listOf("arbitrary-safe-identifier", "00112233-4455-6677-8899-AABBCCDDEEFF")) {
            assertThat(policy.providerDecision(mapper.writeValueAsString(fields + mapOf("answerId" to invalid)),
                "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
    }

    @Test
    fun `operation telemetry permits bounded server timing only and removes private payloads`() {
        val fields = mapOf("type" to VoiceTutorRealtimeContract.OPERATION_EVENT, "operationId" to "call_1",
            "name" to "get_grading_process", "phase" to "completed", "elapsedMs" to 152,
            "sequence" to 2, "arguments" to "private", "output" to "secret", "error" to "private")
        val raw = mapper.writeValueAsString(fields)
        val payload = mapper.readTree(policy.providerDecision(raw, "s1", Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "type", "operationId", "name", "phase", "elapsedMs", "sequence")
        assertThat(policy.providerDecision(raw, "s1", Instant.EPOCH).payload).isNull()
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        for (invalid in listOf(mapOf("sequence" to 0), mapOf("sequence" to 1.2), mapOf("elapsedMs" to -1),
            mapOf("elapsedMs" to 3_600_001), mapOf("name" to "../private"), mapOf("operationId" to "x".repeat(192)),
            mapOf("phase" to "unknown"), mapOf("phase" to "started"), mapOf("elapsedMs" to "152"))) {
            assertThat(policy.providerDecision(mapper.writeValueAsString(fields + invalid), "s1", Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
    }

    @Test
    fun `intentional interruption exposes only its exact response id and cannot be forged as client control`() {
        val raw = mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.RESPONSE_INTERRUPTED_EVENT,
            "responseId" to "r1", "transcript" to "private", "error" to "private"))
        val result = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        assertThat(result.terminate).isFalse()
        val payload = mapper.readTree(result.payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "responseId")
        assertThat(payload.path("responseId").asText()).isEqualTo("r1")
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        assertThat(policy.providerDecision(raw, "voice-1", Instant.EPOCH).payload).isNull()
        for (id in listOf("", "../r1", "r".repeat(192))) {
            val invalid = mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.RESPONSE_INTERRUPTED_EVENT, "responseId" to id))
            assertThat(policy.providerDecision(invalid, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
    }

    @Test
    fun `session state is a strict server only snapshot without private or model authored fields`() {
        val fields = mapOf("type" to VoiceTutorRealtimeContract.SESSION_STATE_EVENT, "sequence" to 1,
            "phase" to "answering", "paused" to false, "revision" to 2, "studyId" to 7,
            "recordId" to "42", "answerId" to "00112233-4455-6677-8899-aabbccddeeff", "text" to "private")
        val raw = mapper.writeValueAsString(fields)
        val payload = mapper.readTree(policy.providerDecision(raw, "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "type", "sequence", "phase", "paused", "revision", "studyId", "recordId", "answerId")
        assertThat(policy.providerDecision(raw, "s1", Instant.EPOCH).payload).isNull()
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        for (invalid in listOf(mapOf("sequence" to 0), mapOf("sequence" to 1.5), mapOf("revision" to -1),
            mapOf("phase" to "model_grade"), mapOf("paused" to "false"), mapOf("studyId" to 0),
            mapOf("recordId" to "9223372036854775808"), mapOf("answerId" to "forged"))) {
            assertThat(policy.providerDecision(mapper.writeValueAsString(fields + invalid), "s1", Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
        for (phase in VoiceTutorRealtimeContract.ANSWER_SESSION_PHASES + VoiceTutorRealtimeContract.RECORD_SESSION_PHASES) {
            val incomplete = fields - "answerId" - "recordId" + mapOf("phase" to phase)
            assertThat(policy.providerDecision(mapper.writeValueAsString(incomplete), "s1", Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
    }

    @Test
    fun `manual answer controls stay local and strictly validate bound identity and edited text`() {
        val identity = mapOf("answerId" to "00112233-4455-6677-8899-aabbccddeeff", "recordId" to "42")
        for (type in listOf(VoiceTutorRealtimeContract.ANSWER_FINISH_EVENT, VoiceTutorRealtimeContract.ANSWER_SKIP_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT)) {
            assertThat(policy.shouldForwardClientEvent(mapper.writeValueAsString(identity + mapOf("type" to type, "text" to "수정본")))).isFalse()
        }
        for (fields in listOf(identity + mapOf("answerId" to "forged"), identity + mapOf("recordId" to 42),
            identity + mapOf("recordId" to "9223372036854775808"), identity + mapOf("text" to true))) {
            assertThatThrownBy { policy.shouldForwardClientEvent(mapper.writeValueAsString(
                mapOf("type" to VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT, "text" to "수정본") + fields)) }
                .isInstanceOf(VoiceTutorClientProtocolException::class.java)
        }
        for (text in listOf(" ", "가".repeat(8_001))) {
            assertThat(policy.shouldForwardClientEvent(mapper.writeValueAsString(identity +
                mapOf("type" to VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT, "text" to text)))).isFalse()
        }
    }

    @Test
    fun `answer state exposes only bounded server owned fields and rejects client forgery`() {
        val raw = mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.ANSWER_STATE_EVENT,
            "answerId" to "00112233-4455-6677-8899-aabbccddeeff", "recordId" to "42", "studyId" to 7,
            "revision" to 0, "phase" to "review", "text" to "검토 답변", "code" to "ANSWER_TRANSCRIPT_INCOMPLETE", "private" to "discard"))
        val decision = policy.providerDecision(raw, "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        val payload = mapper.readTree(decision.payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "answerId", "recordId", "studyId", "revision", "phase", "text", "code")
        assertThat(payload.path("text").asText()).isEqualTo("검토 답변")
        assertThat(policy.providerDecision(raw, "s1", Instant.EPOCH).payload).isNull()
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    @Test
    fun `answer transcript preserves exact bounded item sequence and drops invalid segment identities`() {
        val fields = mapOf("type" to VoiceTutorRealtimeContract.ANSWER_TRANSCRIPT_EVENT,
            "answerId" to "00112233-4455-6677-8899-aabbccddeeff", "recordId" to "42", "itemId" to "input-1",
            "sequence" to 1, "text" to "원문", "private" to "discard")
        val payload = mapper.readTree(policy.providerDecision(mapper.writeValueAsString(fields), "s1", Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "answerId", "recordId", "itemId", "sequence", "text")
        for (invalid in listOf(mapOf("sequence" to 0), mapOf("sequence" to 33), mapOf("sequence" to 1.5),
            mapOf("itemId" to "bad id"), mapOf("text" to "가".repeat(8_001)))) {
            assertThat(policy.providerDecision(mapper.writeValueAsString(fields + invalid), "s1", Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
        }
    }

    @Test
    fun `question changes expose only exact record identity through the server sideband`() {
        val raw = """{"type":"${VoiceTutorRealtimeContract.QUESTION_CHANGED_EVENT}","studyId":42,"recordId":"101","answer":"private","question":"private","score":70}"""
        val decision = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        val payload = mapper.readTree(decision.payload)
        assertThat(decision.terminate).isFalse()
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "studyId", "recordId")
        assertThat(payload.path("studyId").asLong()).isEqualTo(42)
        assertThat(payload.path("recordId").asText()).isEqualTo("101")
        assertThat(policy.providerDecision(raw, "voice-1", Instant.EPOCH).payload).isNull()
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    @Test
    fun `malformed question identities are dropped without ending the call`() {
        val invalid = listOf(
            """"studyId":42,"recordId":101""",
            """"studyId":42,"recordId":"0"""",
            """"studyId":42,"recordId":"01"""",
            """"studyId":42,"recordId":"9223372036854775808"""",
            """"studyId":42,"recordId":"1.5"""",
            """"studyId":42,"recordId":null""",
            """"studyId":0,"recordId":"101"""",
            """"studyId":1.5,"recordId":"101"""",
            """"studyId":"42","recordId":"101"""",
        )
        for (identity in invalid) {
            val decision = policy.providerDecision("""{"type":"${VoiceTutorRealtimeContract.QUESTION_CHANGED_EVENT}",$identity}""",
                "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.payload).isNull()
            assertThat(decision.terminate).isFalse()
        }
    }

    @Test
    fun `successful MCP metadata event contains only a positive exact id and cannot be forged by the client`() {
        val raw = """{"type":"${VoiceTutorRealtimeContract.STUDY_TREE_CHANGED_EVENT}","studyId":42,"arguments":{"secret":"discard"},"output":"discard"}"""
        val result = policy.providerDecision(raw, "synthetic-session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        assertThat(result.terminate).isFalse()
        val payload = mapper.readTree(result.payload)
        assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "studyId")
        assertThat(payload.path("studyId").asLong()).isEqualTo(42)
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        assertThat(policy.providerDecision(raw, "synthetic-session", Instant.EPOCH).payload).isNull()
    }

    @Test
    fun `invalid MCP metadata targets are not forwarded`() {
        for (id in listOf("null", "true", "0", "-1", "1.5", "\"42\"", "[]", "{}", "9223372036854775808")) {
            val raw = """{"type":"${VoiceTutorRealtimeContract.STUDY_TREE_CHANGED_EVENT}","studyId":$id}"""
            val result = policy.providerDecision(raw, "synthetic-session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(result.payload).isNull()
            assertThat(result.terminate).isFalse()
        }
    }

    @Test
    fun `input retry is a compact nonterminal server hint and cannot be forged as client control`() {
        val raw = """{"type":"${VoiceTutorRealtimeContract.INPUT_RETRY_EVENT}","message":"untrusted extra body"}"""
        val decision = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        assertThat(decision.terminate).isFalse()
        assertThat(mapper.readTree(decision.payload).fieldNames().asSequence().toList()).containsExactly("type")
        assertThat(mapper.readTree(decision.payload).path("type").asText()).isEqualTo(VoiceTutorRealtimeContract.INPUT_RETRY_EVENT)
        assertThatThrownBy { policy.shouldForwardClientEvent(raw) }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    @Test
    fun `retry hint preserves an optional exact acoustic sequence without exposing provider payloads`() {
        for (sequence in listOf(0L, 7L, Long.MAX_VALUE)) {
            val raw = mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT,
                "abandonedResponseId" to "unannounced-response", "sequence" to sequence,
                "text" to "private", "error" to mapOf("message" to "private")))
            val decision = policy.providerDecision(raw, "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.terminate).isFalse()
            val value = mapper.readTree(decision.payload)
            assertThat(value.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "abandonedResponseId", "sequence")
            assertThat(value.path("sequence").longValue()).isEqualTo(sequence)
            assertThat(value.path("abandonedResponseId").asText()).isEqualTo("unannounced-response")
            assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
            assertThat(policy.providerDecision(raw, "s1", Instant.EPOCH).payload).isNull()
        }
        for (sequence in listOf("null", "-1", "1.0", "true", "\"1\"", "[]", "{}", "9223372036854775808")) {
            val raw = """{"type":"${VoiceTutorRealtimeContract.INPUT_RETRY_EVENT}","abandonedResponseId":"r1","sequence":$sequence}"""
            val decision = policy.providerDecision(raw, "s1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.payload).isNull()
            assertThat(decision.terminate).isFalse()
        }
    }

    @Test
    fun `silent input settlement exposes only its exact sequence and cannot be forged by a client`() {
        for (sequence in listOf(0L, 1L, Long.MAX_VALUE)) {
            val raw = """{"type":"${VoiceTutorRealtimeContract.INPUT_SETTLED_EVENT}","sequence":$sequence,"transcript":"private","response":{"metadata":"private"}}"""
            val decision = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.terminate).isFalse()
            val payload = mapper.readTree(decision.payload)
            assertThat(payload.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("type", "sequence")
            assertThat(payload.path("sequence").longValue()).isEqualTo(sequence)
            assertThatThrownBy { policy.shouldForwardClientEvent(raw) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
            assertThat(policy.providerDecision(raw, "voice-1", Instant.EPOCH).payload).isNull()
        }
    }

    @Test
    fun `invalid silent settlement sequences are dropped without failing the call`() {
        for (sequence in listOf("null", "-1", "1.0", "true", "\"1\"", "[]", "{}", "9223372036854775808")) {
            val raw = """{"type":"${VoiceTutorRealtimeContract.INPUT_SETTLED_EVENT}","sequence":$sequence}"""
            val decision = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            assertThat(decision.payload).isNull()
            assertThat(decision.terminate).isFalse()
        }
        assertThat(policy.providerDecision("""{"type":"${VoiceTutorRealtimeContract.INPUT_SETTLED_EVENT}"}""",
            "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
    }

    @Test
    fun `turn abandon retry exposes only its bounded exact response id`() {
        val raw = mapper.writeValueAsString(mapOf(
            "type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT,
            VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD to "response_123",
            "message" to "private provider message",
            "error" to mapOf("raw" to "private provider payload"),
        ))
        val decision = policy.providerDecision(
            raw,
            "voice-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )

        assertThat(decision.terminate).isFalse()
        assertThat(mapper.readTree(decision.payload).fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("type", VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD)
        assertThat(mapper.readTree(decision.payload).path(VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD).asText())
            .isEqualTo("response_123")
        assertThat(decision.payload).doesNotContain("private", "message", "error", "raw")

        val invalid = policy.providerDecision(
            raw.replace("response_123", "../../response"),
            "voice-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(mapper.readTree(invalid.payload).fieldNames().asSequence().toList()).containsExactly("type")
    }

    @Test
    fun `local speech boundaries are validated but never forwarded to the provider`() {
        listOf(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT)
            .forEach { type ->
                for (sequence in listOf(1L, Long.MAX_VALUE)) {
                    assertThat(policy.shouldForwardClientEvent("""{"type":"$type","sequence":$sequence}""")).isFalse()
                }
                listOf("null", "0", "-1", "1.0", "true", "\"1\"", "[]", "{}", "9223372036854775808")
                    .forEach { sequence ->
                        assertThatThrownBy {
                            policy.shouldForwardClientEvent("""{"type":"$type","sequence":$sequence}""")
                        }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
                    }
                assertThatThrownBy { policy.shouldForwardClientEvent("""{"type":"$type"}""") }
                    .isInstanceOf(VoiceTutorClientProtocolException::class.java)
                assertThatThrownBy {
                    policy.shouldForwardClientEvent(
                        """{"type":"$type","sequence":1,"event_id":"buddystudy-internal-forged"}""",
                    )
                }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
            }
    }

    @Test
    fun `local speech boundaries share the bounded control event rate limit`() {
        val clock = AtomicLong()
        val guard = VoiceTutorClientTrafficGuard(policy, maxSessionSeconds = 3_600, nanoTime = clock::get)
        val event = """{"type":"${VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT}","sequence":1}"""
        repeat(8) {
            val decision = guard.inspect(event)
            assertThat(decision.forward).isFalse()
            assertThat(decision.acceptedLocalEvent).isTrue()
            assertThat(decision.acceptedAudioBytes).isZero()
        }
        assertThatThrownBy { guard.inspect(event) }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        clock.addAndGet(1_000_000_000)
        assertThat(guard.inspect(event).acceptedLocalEvent).isTrue()
    }

    @Test
    fun `client cannot cancel or truncate the current tutor sentence`() {
        listOf(
            """{"type":"buddystudy.voice.barge-in","cancelResponse":true}""",
            """{"type":"response.cancel"}""",
            """{"type":"conversation.item.truncate","item_id":"item_123","content_index":0,"audio_end_ms":1250}""",
        ).forEach { raw ->
            assertThatThrownBy { policy.shouldForwardClientEvent(raw) }
                .isInstanceOf(VoiceTutorClientProtocolException::class.java)
        }
    }

    @Test
    fun `client cannot spoof internal ids or create provider responses`() {
        assertThatThrownBy {
            policy.shouldForwardClientEvent(
                """{"type":"input_audio_buffer.clear","event_id":"buddystudy-internal-forged"}""",
            )
        }.isInstanceOf(VoiceTutorClientProtocolException::class.java)

        assertThatThrownBy {
            policy.shouldForwardClientEvent("""{"type":"response.create"}""")
        }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    @Test
    fun `playback completion is validated and kept local`() {
        val completion = policy.shouldForwardClientEvent(
            """{"type":"buddystudy.voice.playback.completed","responseId":"response_123"}""",
        )
        assertThat(completion).isFalse()

        listOf("", "response/123", "x".repeat(192)).forEach { responseId ->
            assertThatThrownBy {
                policy.shouldForwardClientEvent(
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "buddystudy.voice.playback.completed",
                            "responseId" to responseId,
                        ),
                    ),
                )
            }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        }
    }

    @Test
    fun `webrtc playout drain is validated and kept local`() {
        assertThat(
            policy.shouldForwardClientEvent(
                """{"type":"buddystudy.voice.playout.drained","responseId":"response_123"}""",
            ),
        ).isFalse()

        listOf("", "response/123", "x".repeat(192)).forEach { responseId ->
            assertThatThrownBy {
                policy.shouldForwardClientEvent(
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "buddystudy.voice.playout.drained",
                            "responseId" to responseId,
                        ),
                    ),
                )
            }.isInstanceOf(VoiceTutorClientProtocolException::class.java)
        }
    }

    @Test
    fun `recoverable provider errors stay live while fatal and malformed errors terminate`() {
        listOf(
            "buddystudy-internal-drain-1",
            "buddystudy-internal-relay-terminal-1",
            "buddystudy-internal-duplex-turn-response-1",
            "client-event-1",
        ).forEach { eventId ->
            val recoverable = policy.providerDecision(
                """{"type":"error","error":{"type":"server_error","code":"server_error","event_id":"$eventId","message":"private"}}""",
                "session-1",
                Instant.EPOCH,
            )
            assertThat(recoverable.terminate).isFalse()
            assertThat(recoverable.payload).isNull()
        }

        val fatal = policy.providerDecision(
            """{"type":"error","error":{"type":"authentication_error","code":"invalid_api_key","message":"private"}}""",
            "session-1",
            Instant.EPOCH,
        )
        assertThat(fatal.terminate).isTrue()
        assertThat(mapper.readTree(fatal.payload).path("code").asText())
            .isEqualTo("VOICE_TUTOR_PROVIDER_ERROR")
        assertThat(fatal.payload).doesNotContain("private")

        val malformed = policy.providerDecision(
            """{"type":"error","error":{"event_id":"missing-error-type","message":"private"}}""",
            "session-1",
            Instant.EPOCH,
        )
        assertThat(malformed.terminate).isTrue()
        assertThat(mapper.readTree(malformed.payload).path("code").asText())
            .isEqualTo("VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        assertThat(malformed.payload).doesNotContain("private")
    }

    @Test
    fun `provider audio and transcript payloads are bounded before relay`() {
        val validAudio = Base64.getEncoder().encodeToString(ByteArray(32_768))
        assertThat(
            policy.providerDecision(
                """{"type":"response.output_audio.delta","response_id":"response-1","delta":"$validAudio"}""",
                "session-1",
                Instant.EPOCH,
            ).terminate,
        ).isFalse()

        val oversizedAudio = Base64.getEncoder().encodeToString(ByteArray(32_769))
        val oversizedDecision = policy.providerDecision(
            """{"type":"response.output_audio.delta","response_id":"response-1","delta":"$oversizedAudio"}""",
            "session-1",
            Instant.EPOCH,
        )
        assertThat(oversizedDecision.terminate).isTrue()
        assertThat(mapper.readTree(oversizedDecision.payload).path("code").asText())
            .isEqualTo("VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")

        assertThat(
            policy.providerDecision(
                """{"type":"response.output_audio.delta","response_id":"response-1","delta":"%%%"}""",
                "session-1",
                Instant.EPOCH,
            ).terminate,
        ).isTrue()

        val oversizedDelta = "x".repeat(4_097)
        assertThat(
            policy.providerDecision(
                mapper.writeValueAsString(
                    mapOf(
                        "type" to "response.output_audio_transcript.delta",
                        "response_id" to "response-1",
                        "delta" to oversizedDelta,
                    ),
                ),
                "session-1",
                Instant.EPOCH,
            ).terminate,
        ).isTrue()

        val oversizedTranscript = "x".repeat(32_001)
        assertThat(
            policy.providerDecision(
                mapper.writeValueAsString(
                    mapOf(
                        "type" to "response.output_audio_transcript.done",
                        "response_id" to "response-1",
                        "transcript" to oversizedTranscript,
                    ),
                ),
                "session-1",
                Instant.EPOCH,
            ).terminate,
        ).isTrue()
    }

    @Test
    fun `webrtc sideband forwards only sanitized buffer boundaries and suppresses audio delta`() {
        val audio = policy.providerDecision(
            """{"type":"response.output_audio.delta","response_id":"response-1","delta":"AA=="}""",
            "session-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(audio.terminate).isFalse()
        assertThat(audio.payload).isNull()

        listOf("output_audio_buffer.started", "output_audio_buffer.stopped").forEach { type ->
            val decision = policy.providerDecision(
                """{"type":"$type","event_id":"discard-me","response_id":"response-1","private":"discard-me"}""",
                "session-1",
                Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
            )
            val payload = mapper.readTree(decision.payload)
            assertThat(decision.terminate).isFalse()
            assertThat(payload.path("type").asText()).isEqualTo(type)
            assertThat(payload.path("response_id").asText()).isEqualTo("response-1")
            assertThat(payload.toString()).doesNotContain("event_id", "private")
        }

        val malformed = policy.providerDecision(
            """{"type":"output_audio_buffer.stopped","response_id":"../../response"}""",
            "session-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(malformed.terminate).isTrue()
        assertThat(mapper.readTree(malformed.payload).path("code").asText())
            .isEqualTo("VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
    }

    @Test
    fun `webrtc cleared and non-completed responses stay turn local`() {
        val cleared = policy.providerDecision(
            """{"type":"output_audio_buffer.cleared","response_id":"response-1"}""",
            "session-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(cleared.terminate).isFalse()
        assertThat(cleared.payload).isNull()

        listOf("cancelled", "incomplete", "failed").forEach { status ->
            val decision = policy.providerDecision(
                """{"type":"response.done","response":{"id":"response-1","status":"$status"}}""",
                "session-1",
                Instant.EPOCH,
                VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
            )
            assertThat(decision.terminate).isFalse()
            assertThat(decision.payload).isNull()
        }
    }

    @Test
    fun `sideband ready is exposed only on the webrtc control path`() {
        val ready = policy.providerDecision(
            """{"type":"buddystudy.provider.sideband.ready","private":"discard-me"}""",
            "session-1",
            Instant.EPOCH,
            VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(mapper.readTree(ready.payload).toString())
            .isEqualTo("""{"type":"buddystudy.provider.sideband.ready"}""")

        val legacy = policy.providerDecision(
            """{"type":"buddystudy.provider.sideband.ready"}""",
            "session-1",
            Instant.EPOCH,
        )
        assertThat(legacy.payload).isNull()
    }

    @Test
    fun `provider response correlated events require a valid response id`() {
        listOf(
            """{"type":"response.output_audio.delta","delta":"AA=="}""",
            """{"type":"response.output_audio.done"}""",
            """{"type":"response.output_audio_transcript.delta","delta":"hello"}""",
            """{"type":"response.output_audio_transcript.done","transcript":"hello"}""",
        ).forEach { raw ->
            val decision = policy.providerDecision(raw, "session-1", Instant.EPOCH)
            assertThat(decision.terminate).isTrue()
            assertThat(mapper.readTree(decision.payload).path("code").asText())
                .isEqualTo("VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        }
    }

    @Test
    fun `failed provider response terminates without being relayed`() {
        val decision = policy.providerDecision(
            """{"type":"response.done","response":{"id":"response-1","status":"failed"}}""",
            "session-1",
            Instant.EPOCH,
        )

        assertThat(decision.terminate).isTrue()
        assertThat(mapper.readTree(decision.payload).path("code").asText())
            .isEqualTo("VOICE_TUTOR_PROVIDER_ERROR")
    }

    @Test
    fun `only the server continuous turn metadata becomes an intervention marker`() {
        val intervention = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-1","status":"in_progress","metadata":{"buddystudy_turn":"continuous_intervention","private":"discard-me"}}}""",
            "session-1",
            Instant.EPOCH,
        )
        val normal = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-2","status":"in_progress","metadata":{"buddystudy_turn":"untrusted-value"}}}""",
            "session-1",
            Instant.EPOCH,
        )

        val interventionPayload = mapper.readTree(intervention.payload)
        assertThat(interventionPayload.path("buddystudyTutorIntervention").asBoolean()).isTrue()
        assertThat(interventionPayload.toString()).doesNotContain("private", "buddystudy_turn")
        assertThat(mapper.readTree(normal.payload).path("buddystudyTutorIntervention").asBoolean()).isFalse()
    }

    @Test
    fun `response created exposes only a boolean quota notice marker and never raw metadata`() {
        val marked = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-quota","status":"in_progress","metadata":{"buddystudy_quota_notice":true,"buddystudy_response_token":"private-token","private":"discard-me"}}}""",
            "session-1",
            Instant.EPOCH,
        )
        val normal = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-normal","status":"in_progress","metadata":{"buddystudy_response_token":"private-token"}}}""",
            "session-1",
            Instant.EPOCH,
        )
        val stringSpoof = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-string","status":"in_progress","metadata":{"buddystudy_quota_notice":"true"}}}""",
            "session-1",
            Instant.EPOCH,
        )
        val numericSpoof = policy.providerDecision(
            """{"type":"response.created","response":{"id":"response-number","status":"in_progress","metadata":{"buddystudy_quota_notice":1}}}""",
            "session-1",
            Instant.EPOCH,
        )

        val markedPayload = mapper.readTree(marked.payload)
        val normalPayload = mapper.readTree(normal.payload)
        assertThat(markedPayload.path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).asBoolean()).isTrue()
        assertThat(normalPayload.path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).asBoolean()).isFalse()
        assertThat(mapper.readTree(stringSpoof.payload)
            .path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).asBoolean()).isFalse()
        assertThat(mapper.readTree(numericSpoof.payload)
            .path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).asBoolean()).isFalse()
        assertThat(markedPayload.path("response").has("metadata")).isFalse()
        assertThat(markedPayload.toString()).doesNotContain("private-token", "discard-me", "buddystudy_quota_notice")
    }

    @Test
    fun `native response announcement retains the controller owned quota boolean across sanitization`() {
        for (notice in listOf(true, false)) {
            val raw = mapper.writeValueAsString(mapOf(
                "type" to "response.created", VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD to notice,
                "response" to mapOf("id" to "native-response", "status" to "in_progress", "metadata" to mapOf(
                    VoiceTutorRealtimeContract.QUOTA_NOTICE_METADATA_KEY to notice.toString(),
                    VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to "private-token",
                )),
            ))
            val decision = policy.providerDecision(raw, "voice-1", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            val payload = mapper.readTree(decision.payload)
            assertThat(payload.path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).isBoolean).isTrue()
            assertThat(payload.path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD).booleanValue()).isEqualTo(notice)
            assertThat(payload.path("response").has("metadata")).isFalse()
            assertThat(decision.payload).doesNotContain("private-token")
        }
    }

    @Test
    fun `traffic guard counts decoded pcm bytes and rejects malformed base64`() {
        val now = AtomicLong(0)
        val guard = VoiceTutorClientTrafficGuard(policy, maxSessionSeconds = 3_600, nanoTime = now::get)

        val threeBytes = guard.inspect("""{"type":"input_audio_buffer.append","audio":"AAAA"}""")
        assertThat(threeBytes.acceptedAudioBytes).isEqualTo(3)

        assertThatThrownBy {
            guard.inspect("""{"type":"input_audio_buffer.append","audio":"%%%"}""")
        }.isInstanceOf(VoiceTutorClientProtocolException::class.java)

        val remainingBurst = ByteArray(11_997)
        assertThat(guard.inspect(audioEvent(remainingBurst)).acceptedAudioBytes).isEqualTo(11_997)
        assertThatThrownBy { guard.inspect(audioEvent(ByteArray(1))) }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)

        now.addAndGet(250_000_000)
        assertThat(guard.inspect(audioEvent(ByteArray(12_000))).acceptedAudioBytes).isEqualTo(12_000)
    }

    @Test
    fun `heartbeat is validated and coalesced before persistence`() {
        val now = AtomicLong(0)
        val guard = VoiceTutorClientTrafficGuard(policy, maxSessionSeconds = 60, nanoTime = now::get)

        assertThat(guard.inspect("""{"type":"buddystudy.voice.heartbeat"}""").acceptedLocalEvent).isTrue()
        now.addAndGet(1_000_000_000)
        assertThat(guard.inspect("""{"type":"buddystudy.voice.heartbeat"}""").acceptedLocalEvent).isFalse()
        now.addAndGet(7_000_000_000)
        assertThat(guard.inspect("""{"type":"buddystudy.voice.heartbeat"}""").acceptedLocalEvent).isTrue()

        val oversized = """{"type":"buddystudy.voice.heartbeat","padding":"${"x".repeat(65_536)}"}"""
        assertThatThrownBy { guard.inspect(oversized) }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    @Test
    fun `small provider control events have a separate burst limit`() {
        val guard = VoiceTutorClientTrafficGuard(policy, maxSessionSeconds = 60, nanoTime = { 0 })

        repeat(8) {
            assertThat(guard.inspect("""{"type":"input_audio_buffer.commit"}""").forward).isTrue()
        }
        assertThatThrownBy { guard.inspect("""{"type":"input_audio_buffer.commit"}""") }
            .isInstanceOf(VoiceTutorClientProtocolException::class.java)
    }

    private fun audioEvent(bytes: ByteArray): String = mapper.writeValueAsString(
        mapOf(
            "type" to "input_audio_buffer.append",
            "audio" to Base64.getEncoder().encodeToString(bytes),
        ),
    )
}
