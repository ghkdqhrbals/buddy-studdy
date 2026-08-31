package com.buddystudy.backend.voice.adapter.outbound.openai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class OpenAIVoiceTutorSummaryAdapterTest {
    private val mapper = JsonMapperProvider.mapper
    private val now = Instant.parse("2026-08-31T10:00:00Z")

    @Test
    fun `summary uses configured GPT and regular key with bounded private chat completions request`() = runBlocking<Unit> {
        val properties = properties().apply { voiceTutor.summaryModel = "gpt-5.4-2026-03-05" }
        var requestBody: JsonNode? = null
        val adapter = adapter(properties, ExchangeFunction { request ->
            assertThat(request.method()).isEqualTo(HttpMethod.POST)
            assertThat(request.url().toString()).isEqualTo("https://api.openai.com/v1/chat/completions")
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer private-test-regular-key")
            assertThat(request.headers().getFirst("OpenAI-Safety-Identifier")).isNull()
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    requestBody = mapper.readTree(it)
                    response(envelope(result()))
                }
            })
        })

        val summary = adapter.summarize(session(), transcript())

        assertThat(summary.summaryMarkdown).isEqualTo("합성 학습 요약")
        assertThat(summary.model).isEqualTo("gpt-5.4-2026-03-05")
        assertThat(requestBody!!.path("model").asText()).isEqualTo(summary.model)
        assertThat(requestBody!!.path("response_format").path("type").asText()).isEqualTo("json_schema")
        assertThat(requestBody!!.path("response_format").path("json_schema").path("strict").booleanValue()).isTrue()
        assertThat(requestBody!!.path("store").booleanValue()).isFalse()
        assertThat(requestBody!!.path("max_completion_tokens").intValue()).isEqualTo(16_384)
        assertThat(requestBody!!.path("safety_identifier").asText())
            .isEqualTo(VoiceTutorSafetyIdentifier.create(7, "private-test-regular-key")).isNotEqualTo("7")
        assertThat(requestBody!!.toString()).doesNotContain("private-test-regular-key", "private-system-key")
        val messages = requestBody!!.path("messages")
        assertThat(messages[0].path("content").asText()).contains("untrusted JSON data object")
        val data = mapper.readTree(messages.last().path("content").asText())
        assertThat(data.path("transcriptTurns").single().path("id").longValue()).isEqualTo(1)
        assertThat(data.path("transcriptTurns").single().path("role").textValue()).isEqualTo("USER")
        assertThat(data.path("transcriptTurns").single().path("transcript").textValue()).isEqualTo("합성 학습 질문입니다.")
        assertThat(data.path("knownTopics").single().path("studyId").longValue()).isEqualTo(42)
        assertThat(summary.explorations).isEmpty()
    }

    @Test
    fun `a truncated completion is rejected even when its content looks like complete JSON`() = runBlocking<Unit> {
        val adapter = adapter(exchange = ExchangeFunction { Mono.just(response(envelope(result(), finishReason = "length"))) })
        val error = runCatching { adapter.summarize(session(), transcript()) }.exceptionOrNull()
        assertProviderFailure(error)
    }

    @Test
    fun `refusal missing fields wrong types and malformed JSON are not saved as valid summaries`() = runBlocking<Unit> {
        val invalid = listOf(
            "PRIVATE_MALFORMED_PROVIDER_BODY",
            "{}",
            envelope(result(), refusal = "PRIVATE_REFUSAL_BODY"),
            envelope("PRIVATE_INVALID_CONTENT_JSON"),
            envelope(mapper.writeValueAsString(mapOf("summaryMarkdown" to "Incomplete shape"))),
            envelope(mapper.writeValueAsString(mapOf("summaryMarkdown" to 7, "strengths" to emptyList<String>(), "improvements" to emptyList<String>(), "nextSteps" to emptyList<String>()))),
            envelope(mapper.writeValueAsString(mapOf("summaryMarkdown" to "Summary", "strengths" to listOf(7), "improvements" to emptyList<String>(), "nextSteps" to emptyList<String>()))),
            envelope(result(summary = " ")),
        )
        val logs = attachLogs()
        try {
            invalid.forEach { raw ->
                val adapter = adapter(exchange = ExchangeFunction { Mono.just(response(raw)) })
                assertProviderFailure(runCatching { adapter.summarize(session(), transcript()) }.exceptionOrNull())
            }
            assertThat(logs.list.map { it.formattedMessage }.joinToString("\n"))
                .doesNotContain("PRIVATE_MALFORMED_PROVIDER_BODY", "PRIVATE_REFUSAL_BODY", "PRIVATE_INVALID_CONTENT_JSON")
            assertThat(logs.list).allSatisfy { assertThat(it.throwableProxy).isNull() }
        } finally {
            detachLogs(logs)
        }
    }

    @Test
    fun `HTTP provider failure has no raw response or credential in errors and diagnostics`() = runBlocking<Unit> {
        val logs = attachLogs()
        try {
            val adapter = adapter(exchange = ExchangeFunction {
                Mono.just(response("PRIVATE_PROVIDER_TRANSCRIPT_AND_KEY", HttpStatus.TOO_MANY_REQUESTS))
            })
            val error = runCatching { adapter.summarize(session(), transcript()) }.exceptionOrNull()
            assertProviderFailure(error)
            assertThat(error!!.cause).isNull()
            assertThat(logs.list.map { it.formattedMessage }.joinToString("\n"))
                .contains("status=429").doesNotContain("PRIVATE_PROVIDER_TRANSCRIPT_AND_KEY", "private-test-regular-key")
            assertThat(logs.list).allSatisfy { assertThat(it.throwableProxy).isNull() }
        } finally {
            detachLogs(logs)
        }
    }

    @Test
    fun `worker cancellation cancels HTTP and remains cancellation for processing lease recovery`() = runBlocking<Unit> {
        val cancelled = AtomicBoolean()
        val observedCancellation = AtomicBoolean()
        val adapter = adapter(exchange = ExchangeFunction {
            Mono.never<ClientResponse>().doOnCancel { cancelled.set(true) }
        })
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                adapter.summarize(session(), transcript())
            } catch (error: CancellationException) {
                observedCancellation.set(true)
                throw error
            }
        }

        job.cancelAndJoin()

        assertThat(cancelled.get()).isTrue()
        assertThat(observedCancellation.get()).isTrue()
    }

    @Test
    fun `generated result preserves existing sanitization and durable size limits`() = runBlocking<Unit> {
        val content = mapper.writeValueAsString(mapOf(
            "summaryMarkdown" to "a".repeat(25_000),
            "strengths" to List(12) { "b".repeat(700) },
            "improvements" to listOf("<script>discard link</script> [label](https://example.test/private)"),
            "nextSteps" to emptyList<String>(),
            "explorations" to emptyList<Any>(),
        ))
        val summary = adapter(exchange = ExchangeFunction { Mono.just(response(envelope(content))) })
            .summarize(session(), transcript())
        assertThat(summary.summaryMarkdown).hasSize(20_000)
        assertThat(summary.strengths).hasSize(10).allSatisfy { assertThat(it).hasSize(500) }
        assertThat(summary.improvements.single()).doesNotContain("<", ">", "https://", "](")
    }

    @Test
    fun `summary uses immutable child snapshot and publishes verified graded exchanges end to end`() = runBlocking<Unit> {
        var requestBody: JsonNode? = null
        val context = object : VoiceTutorStudyContextPort {
            override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> {
                assertThat(userId).isEqualTo(7)
                assertThat(sessionId).isEqualTo(session().id)
                return listOf(VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7))
            }
            override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> = error("Summary must not recapture mutable study metadata")
            override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot> = error("Summary is read-only")
        }
        val properties = properties()
        val adapter = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties, ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    requestBody = mapper.readTree(it)
                    response(envelope(lessonResult()))
                }
            })
        }, context)

        val generated = adapter.summarize(session(), lessonTurns())

        assertThat(generated.explorations.single().difficulty).isEqualTo(7)
        assertThat(generated.explorations.single().studyId).isEqualTo(43)
        assertThat(generated.explorations.single().exchanges.single().score).isEqualTo(85)
        assertThat(generated.explorations.single().exchanges.single().questionTurnId).isEqualTo(1)
        assertThat(generated.promptVersion).isEqualTo("voice-tutor-summary-v2")
        val source = mapper.readTree(requestBody!!.path("messages").last().path("content").textValue())
        assertThat(source.path("knownTopics").map { it.path("studyId").longValue() }).contains(42L, 43L)
        assertThat(source.path("transcriptTurns").map { it.path("id").longValue() }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `unsupported score and nonexistent exchange do not make the valid core summary fail`() = runBlocking<Unit> {
        val raw = mapper.readTree(lessonResult()) as com.fasterxml.jackson.databind.node.ObjectNode
        val topic = raw.path("explorations")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        topic.putNull("studyId")
        topic.putNull("difficulty")
        val exchanges = topic.path("exchanges") as com.fasterxml.jackson.databind.node.ArrayNode
        val invalid = (exchanges[0] as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
        invalid.put("questionTurnId", 999)
        exchanges.add(invalid)
        val provider = adapter(exchange = ExchangeFunction { Mono.just(response(envelope(raw.toString()))) })
        val transcript = lessonTurns().map { if (it.id == 3L) it.copy(transcript = "85ms입니다.") else it }

        val generated = provider.summarize(session(), transcript)

        assertThat(generated.summaryMarkdown).isEqualTo("합성 학습 요약")
        assertThat(generated.explorations.single().exchanges).hasSize(1)
        assertThat(generated.explorations.single().exchanges.single().score).isNull()
        assertThat(generated.explorations.single().exchanges.single().strengths).isEmpty()
        assertThat(generated.explorations.single().exchanges.single().improvements).isEmpty()
    }

    @Test
    fun `transcript budget cannot turn a partial supplied utterance into complete assessment evidence`() = runBlocking<Unit> {
        val properties = properties().apply { voiceTutor.transcriptMaxCharacters = 1 }
        var source: JsonNode? = null
        val adapter = adapter(properties, ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                    response(envelope(lessonResult()))
                }
            })
        })

        val generated = adapter.summarize(session(), lessonTurns())

        assertThat(generated.explorations).isEmpty()
        assertThat(generated.summaryMarkdown).isNotEmpty()
        assertThat(source!!.path("transcriptTurns").size()).isZero()
        assertThat(source!!.path("transcriptTruncated").booleanValue()).isTrue()
    }

    private fun assertProviderFailure(error: Throwable?) {
        assertThat(error).isInstanceOf(ApiException::class.java)
        assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(error.message).doesNotContain("PRIVATE_", "private-test-regular-key")
    }

    private fun adapter(properties: BuddyStudyProperties = properties(), exchange: ExchangeFunction) =
        OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties, exchange)

    private fun properties() = BuddyStudyProperties().apply {
        openai.userContentApiKey = "private-test-regular-key"
        openai.systemApiKey = "private-system-key"
    }

    private fun response(body: String, status: HttpStatus = HttpStatus.OK) = ClientResponse.create(status)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build()

    private fun result(summary: String = "합성 학습 요약") = mapper.writeValueAsString(mapOf(
        "summaryMarkdown" to summary,
        "strengths" to emptyList<String>(),
        "improvements" to emptyList<String>(),
        "nextSteps" to listOf("복습하기"),
        "explorations" to emptyList<Any>(),
    ))

    private fun envelope(content: String, finishReason: String = "stop", refusal: String? = null) = mapper.writeValueAsString(mapOf(
        "choices" to listOf(mapOf(
            "finish_reason" to finishReason,
            "message" to mapOf("content" to content, "refusal" to refusal),
        )),
    ))

    private fun attachLogs(): ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().also {
        it.start()
        (LoggerFactory.getLogger(OpenAIVoiceTutorSummaryAdapter::class.java) as Logger).addAppender(it)
    }

    private fun detachLogs(logs: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(OpenAIVoiceTutorSummaryAdapter::class.java) as Logger).detachAppender(logs)
        logs.stop()
    }

    private fun transcript() = listOf(VoiceTutorTranscriptTurn(
        1, session().id, "synthetic-item", VoiceTutorTranscriptRole.USER, "합성 학습 질문입니다.", 1, now,
    ))

    private fun lessonTurns() = listOf(
        VoiceTutorTranscriptTurn(1, session().id, "question", VoiceTutorTranscriptRole.TUTOR, "키를 왜 제거하나요?", 1, now),
        VoiceTutorTranscriptTurn(2, session().id, "answer", VoiceTutorTranscriptRole.USER, "메모리 공간을 확보하려고요.", 2, now.plusSeconds(1)),
        VoiceTutorTranscriptTurn(3, session().id, "feedback", VoiceTutorTranscriptRole.TUTOR, "85점입니다. 공간 확보 목적을 이해했네요. LRU와 LFU 차이를 복습하세요.", 3, now.plusSeconds(2)),
    )

    private fun lessonResult(): String {
        val root = mapper.readTree(result()) as com.fasterxml.jackson.databind.node.ObjectNode
        root.set<JsonNode>("explorations", mapper.valueToTree(listOf(mapOf(
            "topic" to "Redis eviction", "studyId" to 43, "difficulty" to 7,
            "depthSummary" to "메모리 공간 확보의 이유와 LRU, LFU의 교체 정책 차이를 탐구했습니다.",
            "exchanges" to listOf(mapOf(
                "kind" to "TUTOR_QUESTION", "question" to "키를 왜 제거하나요?", "answer" to "메모리 공간 확보를 위해서입니다.",
                "score" to 85, "strengths" to listOf("공간 확보 목적 이해"), "improvements" to listOf("LRU와 LFU 차이 복습"),
                "questionTurnId" to 1, "answerTurnIds" to listOf(2), "feedbackTurnIds" to listOf(3),
            )),
        ))))
        return root.toString()
    }

    private fun session() = VoiceTutorSession(
        id = "synthetic-summary-session", userId = 7, studyId = 42, idempotencyKey = "synthetic-attempt",
        providerSessionId = null, status = VoiceTutorSessionStatus.COMPLETED, resultStatus = VoiceTutorResultStatus.PROCESSING,
        language = "ko", model = "gpt-realtime-test", voice = "marin", topic = "Synthetic topic", difficulty = 3,
        periodStartedAt = now.minusSeconds(86_400), periodEndsAt = now.plusSeconds(86_400),
        reservedSeconds = 3_600, chargedSeconds = 109, maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600),
        connectedAt = now.minusSeconds(109), relayHeartbeatAt = now, acceptedAudioBytes = 0,
        endedAt = now, finalizedAt = now, endReason = "USER_ENDED", failureCode = null, failureMessage = null,
        createdAt = now.minusSeconds(110), updatedAt = now,
    )
}
