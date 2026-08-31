package com.buddystudy.backend.voice.adapter.outbound.openai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Pure protocol tests: no provider API, network, Docker, or database. */
class VoiceTutorWebRtcSessionHandshakeTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `initial dispatch is one GA update with explicit null turn detection before readiness`() {
        val handshake = newHandshake()
        val order = mutableListOf<String>()
        val updates = mutableListOf<String>()
        val ready = AtomicBoolean()

        StepVerifier.create(
            handshake.initialProviderEvents()
                .doOnNext { raw ->
                    order += "update"
                    updates += raw
                    assertThat(ready.get()).isFalse()
                    assertThat(handshake.observeProviderEvent(validUpdated())).isTrue()
                }
                .then(handshake.awaitConfirmation())
                .doOnSuccess { order += "ready"; ready.set(true) },
        ).expectComplete().verify(VERIFY_TIMEOUT)

        assertThat(order).containsExactly("update", "ready")
        assertThat(updates).hasSize(1)
        val event = mapper.readTree(updates.single())
        assertThat(event.path("type").asText()).isEqualTo("session.update")
        val session = event.path("session")
        assertThat(session.path("type").asText()).isEqualTo("realtime")
        assertThat(session.has("turn_detection")).isFalse()
        val detection = session.path("audio").path("input").path("turn_detection")
        assertThat(detection.isNull).isTrue()
        assertThat(session.path("audio").path("input").has("turn_detection")).isTrue()
        val transcription = session.path("audio").path("input").path("transcription")
        assertThat(transcription.path("model").asText()).isEqualTo("gpt-4o-mini-transcribe")
        assertThat(transcription.path("language").asText()).isEqualTo("ko")
        assertThat(updates.single()).doesNotContain("response.create", "response.cancel", "output_audio_buffer.clear")
    }

    @Test
    fun `created events never acknowledge configuration even with explicit null turn detection`() {
        lateinit var handshake: VoiceTutorWebRtcSessionHandshake
        StepVerifier.withVirtualTime {
            handshake = newHandshake()
            dispatch(handshake)
            handshake.awaitConfirmation()
        }
            .expectSubscription()
            .then {
                assertThat(handshake.observeProviderEvent(validUpdated().replace("session.updated", "session.created"))).isTrue()
                assertThat(handshake.observeProviderEvent(
                    automaticUpdated().replace("session.updated", "session.created"),
                )).isTrue()
            }
            .expectNoEvent(CONFIRMATION_TIMEOUT.minusMillis(1))
            .thenAwait(Duration.ofMillis(1))
            .expectError(VoiceTutorWebRtcSessionConfigurationTimeoutException::class.java)
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun `missing acknowledgement fails at the configured timeout`() {
        StepVerifier.withVirtualTime {
            val handshake = newHandshake()
            dispatch(handshake)
            handshake.awaitConfirmation()
        }
            .expectSubscription()
            .expectNoEvent(CONFIRMATION_TIMEOUT.minusMillis(1))
            .thenAwait(Duration.ofMillis(1))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(VoiceTutorWebRtcSessionConfigurationTimeoutException::class.java)
                assertThat(error.message).doesNotContain(CALL_ID)
            }
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun `an update observed before initial dispatch cannot release the confirmation gate`() {
        lateinit var handshake: VoiceTutorWebRtcSessionHandshake
        val snapshots = CopyOnWriteArrayList<VoiceTutorWebRtcConfigurationSnapshot>()
        StepVerifier.withVirtualTime {
            handshake = newHandshake(snapshots)
            handshake.awaitConfirmation()
        }
            .expectSubscription()
            .then {
                // Merely obtaining the cold Flux is not dispatching its update.
                handshake.initialProviderEvents()
                assertThat(handshake.observeProviderEvent(validUpdated())).isTrue()
                assertThat(snapshots.last().updateRequested).isFalse()
                assertThat(snapshots.last().verified).isFalse()
                dispatch(handshake)
            }
            .expectNoEvent(Duration.ofSeconds(1))
            .then { assertThat(handshake.observeProviderEvent(validUpdated())).isTrue() }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun `valid GA acknowledgement releases readiness once and is replayable to a later waiter`() {
        val snapshots = CopyOnWriteArrayList<VoiceTutorWebRtcConfigurationSnapshot>()
        val handshake = newHandshake(snapshots)
        val readyCount = AtomicInteger()
        dispatch(handshake)

        StepVerifier.create(handshake.awaitConfirmation().doOnSuccess { readyCount.incrementAndGet() })
            .then {
                assertThat(handshake.observeProviderEvent(validUpdated())).isTrue()
                assertThat(handshake.observeProviderEvent(validUpdated())).isTrue()
            }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)

        assertThat(readyCount.get()).isEqualTo(1)
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)
        val snapshot = snapshots.last()
        assertThat(snapshot.eventType).isEqualTo("session.updated")
        assertThat(snapshot.sessionType).isEqualTo("realtime")
        assertThat(snapshot.schema).isEqualTo(VoiceTutorWebRtcConfigurationSchema.GA)
        assertThat(snapshot.turnDetectionType).isEqualTo("manual")
        assertThat(snapshot.createResponse).isNull()
        assertThat(snapshot.interruptResponse).isNull()
        assertThat(snapshot.updateRequested).isTrue()
        assertThat(snapshot.verified).isTrue()
        assertThat(snapshot.expectedTranscriptionLanguage).isEqualTo("ko")
        assertThat(snapshot.effectiveTranscriptionLanguage).isEqualTo("ko")
        assertThat(snapshot.transcriptionLanguageVerified).isTrue()
    }

    @TestFactory
    fun `missing or changed transcription language cannot acknowledge an otherwise safe call`() =
        listOf<Pair<String, (ObjectNode) -> Unit>>(
            "missing transcription" to { input -> input.remove("transcription"); Unit },
            "disabled transcription" to { input -> input.putNull("transcription"); Unit },
            "missing language" to { input -> (input.path("transcription") as ObjectNode).remove("language"); Unit },
            "automatic language" to { input -> (input.path("transcription") as ObjectNode).put("language", ""); Unit },
            "null language" to { input -> (input.path("transcription") as ObjectNode).putNull("language"); Unit },
            "other supported language" to { input -> (input.path("transcription") as ObjectNode).put("language", "en"); Unit },
            "unrequested language" to { input -> (input.path("transcription") as ObjectNode).put("language", "pt"); Unit },
            "nontext language" to { input -> (input.path("transcription") as ObjectNode).put("language", 1); Unit },
        ).map { (description, change) ->
            dynamicTest(description) {
                val snapshots = mutableListOf<VoiceTutorWebRtcConfigurationSnapshot>()
                val handshake = newHandshake(snapshots)
                dispatch(handshake)
                val invalid = mapper.readTree(validUpdated()) as ObjectNode
                change(invalid.path("session").path("audio").path("input") as ObjectNode)

                assertThatThrownBy { handshake.observeProviderEvent(mapper.writeValueAsString(invalid)) }
                    .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
                assertThat(snapshots.single().toolsVerified).isTrue()
                assertThat(snapshots.single().transcriptionLanguageVerified).isFalse()
                assertThat(snapshots.single().verified).isFalse()
            }
        }

    @Test
    fun `later transcription language removal is detected after a verified acknowledgement`() {
        val handshake = newHandshake()
        dispatch(handshake)
        handshake.observeProviderEvent(validUpdated())
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)

        val withoutLanguage = (mapper.readTree(validUpdated()) as ObjectNode).apply {
            (path("session").path("audio").path("input").path("transcription") as ObjectNode).remove("language")
        }
        assertThatThrownBy { handshake.observeProviderEvent(mapper.writeValueAsString(withoutLanguage)) }
            .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
    }

    @TestFactory
    fun `post-dispatch updated must contain explicit null turn detection in the GA nested schema`() =
        invalidUpdates().map { (description, raw) ->
            dynamicTest(description) {
                val snapshots = CopyOnWriteArrayList<VoiceTutorWebRtcConfigurationSnapshot>()
                val handshake = newHandshake(snapshots)
                dispatch(handshake)

                assertThatThrownBy { handshake.observeProviderEvent(raw) }
                    .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
                    .hasMessageNotContaining(CALL_ID)
                    .hasMessageNotContaining(PRIVATE_PAYLOAD)

                assertThat(snapshots.last().verified).isFalse()
            }
        }

    @Test
    fun `later unsafe configuration updates fail even after an earlier verified acknowledgement`() {
        val handshake = newHandshake()
        dispatch(handshake)
        handshake.observeProviderEvent(validUpdated())
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)

        assertThatThrownBy {
            handshake.observeProviderEvent(automaticUpdated())
        }.isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
    }

    @Test
    fun `non-configuration events stay available to the ordinary provider event pipeline`() {
        val snapshots = CopyOnWriteArrayList<VoiceTutorWebRtcConfigurationSnapshot>()
        val handshake = newHandshake(snapshots)
        for (raw in listOf(
            """{"type":"response.created","response":{"id":"$PRIVATE_PAYLOAD"}}""",
            """{"type":"response.output_audio.delta","delta":"$PRIVATE_PAYLOAD"}""",
            """{"type":"error","error":{"message":"$PRIVATE_PAYLOAD"}}""",
        )) {
            assertThat(handshake.observeProviderEvent(raw)).isFalse()
        }
        assertThat(snapshots).isEmpty()
    }

    @Test
    fun `terminal cancellation stops the confirmation waiter without invoking readiness`() {
        lateinit var handshake: VoiceTutorWebRtcSessionHandshake
        val cancelled = AtomicBoolean()
        val completed = AtomicInteger()
        StepVerifier.withVirtualTime {
            handshake = newHandshake()
            dispatch(handshake)
            handshake.awaitConfirmation()
                .doOnCancel { cancelled.set(true) }
                .doOnSuccess { completed.incrementAndGet() }
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1))
            .thenCancel()
            .verify(VERIFY_TIMEOUT)

        assertThat(cancelled.get()).isTrue()
        assertThat(completed.get()).isZero()
        // A late provider ACK cannot invoke the cancelled readiness callback.
        handshake.observeProviderEvent(validUpdated())
        assertThat(completed.get()).isZero()
        StepVerifier.create(handshake.awaitConfirmation()).expectComplete().verify(VERIFY_TIMEOUT)
    }

    @Test
    fun `a receive-side configuration rejection cancels the competing ready timeout`() {
        val handshake = newHandshake()
        val readyCancelled = AtomicBoolean()
        dispatch(handshake)
        StepVerifier.create(
            Mono.firstWithSignal(
                handshake.awaitConfirmation().doOnCancel { readyCancelled.set(true) },
                Mono.fromRunnable<Void> {
                    handshake.observeProviderEvent(automaticUpdated())
                },
            ),
        )
            .expectError(VoiceTutorWebRtcSessionConfigurationException::class.java)
            .verify(VERIFY_TIMEOUT)

        assertThat(readyCancelled.get()).isTrue()
    }

    @Test
    fun `configuration snapshots and default logs never retain provider payloads or raw call IDs`() {
        val snapshots = CopyOnWriteArrayList<VoiceTutorWebRtcConfigurationSnapshot>()
        val custom = newHandshake(snapshots)
        dispatch(custom)
        val unsafe = sensitiveUpdated()
        assertThatThrownBy { custom.observeProviderEvent(unsafe) }
            .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)
        assertThat(snapshots).hasSize(1)
        val snapshot = snapshots.single()
        assertThat(snapshot.callRef).isEqualTo(voiceTutorCallReference(CALL_ID)).matches("[0-9a-f]{16}")
        assertThat(snapshot.toString()).doesNotContain(CALL_ID, PRIVATE_PAYLOAD, PRIVATE_TYPE, PRIVATE_SECRET)

        val logger = LoggerFactory.getLogger(
            "com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorWebRtcSessionHandshake",
        ) as Logger
        val previousLevel = logger.level
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(logs)
        try {
            val handshake = VoiceTutorWebRtcSessionHandshake(CALL_ID, CONFIRMATION_TIMEOUT, transcriptionLanguage = "ko")
            dispatch(handshake)
            assertThatThrownBy { handshake.observeProviderEvent(unsafe) }
                .isInstanceOf(VoiceTutorWebRtcSessionConfigurationException::class.java)

            assertThat(logs.list).isNotEmpty()
            val loggedValues = logs.list.joinToString("\n") { event ->
                assertThat(event.throwableProxy).isNull()
                listOf(
                    event.formattedMessage,
                    event.argumentArray?.joinToString().orEmpty(),
                    event.keyValuePairs?.joinToString().orEmpty(),
                ).joinToString(" ")
            }
            assertThat(loggedValues).contains(voiceTutorCallReference(CALL_ID))
                .contains("expectedTranscriptionLanguage=ko", "effectiveTranscriptionLanguage=other", "transcriptionLanguageVerified=false")
                .doesNotContain(CALL_ID, PRIVATE_PAYLOAD, PRIVATE_TYPE, PRIVATE_SECRET)
        } finally {
            logger.detachAppender(logs)
            logger.level = previousLevel
            logs.stop()
        }
    }

    private fun newHandshake(snapshots: MutableList<VoiceTutorWebRtcConfigurationSnapshot> = mutableListOf()) =
        VoiceTutorWebRtcSessionHandshake(
            CALL_ID, CONFIRMATION_TIMEOUT, onConfiguration = { snapshots += it }, transcriptionLanguage = "ko",
        )

    private fun dispatch(handshake: VoiceTutorWebRtcSessionHandshake) {
        StepVerifier.create(handshake.initialProviderEvents()).expectNextCount(1).expectComplete().verify(VERIFY_TIMEOUT)
    }

    private fun validUpdated() =
        """{"type":"session.updated","session":{"type":"realtime","audio":{"input":{"turn_detection":null,"transcription":{"model":"gpt-4o-mini-transcribe","language":"ko"}}}}}"""

    private fun automaticUpdated() = validUpdated().replace(
        "null", """{"type":"server_vad","create_response":false,"interrupt_response":false}""",
    )

    private fun invalidUpdates(): List<Pair<String, String>> {
        val valid = validUpdated()
        val automatic = automaticUpdated()
        return listOf(
            "missing session" to """{"type":"session.updated"}""",
            "missing session type" to valid.replace("\"type\":\"realtime\",", ""),
            "wrong session type" to valid.replace("\"realtime\"", "\"transcription\""),
            "missing audio input" to """{"type":"session.updated","session":{"type":"realtime","audio":{}}}""",
            "missing turn detection" to """{"type":"session.updated","session":{"type":"realtime","audio":{"input":{}}}}""",
            "server VAD still enabled despite false flags" to automatic,
            "missing VAD type" to automatic.replace("\"type\":\"server_vad\",", ""),
            "semantic VAD" to automatic.replace("server_vad", "semantic_vad"),
            "missing create_response" to automatic.replace("\"create_response\":false,", ""),
            "missing interrupt_response" to automatic.replace(",\"interrupt_response\":false", ""),
            "automatic response enabled" to automatic.replace("\"create_response\":false", "\"create_response\":true"),
            "automatic interruption enabled" to automatic.replace("\"interrupt_response\":false", "\"interrupt_response\":true"),
            "string create_response" to automatic.replace("\"create_response\":false", "\"create_response\":\"false\""),
            "string interrupt_response" to automatic.replace("\"interrupt_response\":false", "\"interrupt_response\":\"false\""),
            "numeric create_response" to automatic.replace("\"create_response\":false", "\"create_response\":0"),
            "numeric interrupt_response" to automatic.replace("\"interrupt_response\":false", "\"interrupt_response\":0"),
            "null create_response" to automatic.replace("\"create_response\":false", "\"create_response\":null"),
            "null interrupt_response" to automatic.replace("\"interrupt_response\":false", "\"interrupt_response\":null"),
            "string null is not disabled detection" to valid.replace("null", "\"null\""),
            "empty detection object is not disabled detection" to valid.replace("null", "{}"),
            "legacy detection is not a GA confirmation" to
                """{"type":"session.updated","session":{"type":"realtime","turn_detection":null}}""",
        )
    }

    private fun sensitiveUpdated(): String = mapper.writeValueAsString(
        mapOf(
            "type" to "session.updated",
            "transcript" to PRIVATE_PAYLOAD,
            "session" to mapOf(
                "id" to CALL_ID,
                "type" to PRIVATE_TYPE,
                "instructions" to PRIVATE_PAYLOAD,
                "client_secret" to mapOf("value" to PRIVATE_SECRET),
                "audio" to mapOf("input" to mapOf(
                    "turn_detection" to mapOf(
                        "type" to PRIVATE_TYPE,
                        "create_response" to PRIVATE_PAYLOAD,
                        "interrupt_response" to PRIVATE_PAYLOAD,
                    ),
                    "transcription" to mapOf("model" to PRIVATE_TYPE, "language" to PRIVATE_SECRET),
                )),
            ),
        ),
    )

    companion object {
        private val CONFIRMATION_TIMEOUT = Duration.ofSeconds(5)
        private val VERIFY_TIMEOUT = Duration.ofSeconds(2)
        private const val CALL_ID = "rtc_private-config-call"
        private const val PRIVATE_PAYLOAD = "PRIVATE_TRANSCRIPT_AND_INSTRUCTIONS"
        private const val PRIVATE_TYPE = "PRIVATE_UNRECOGNIZED_CONFIG_TYPE"
        private const val PRIVATE_SECRET = "PRIVATE_CLIENT_SECRET"
    }
}
