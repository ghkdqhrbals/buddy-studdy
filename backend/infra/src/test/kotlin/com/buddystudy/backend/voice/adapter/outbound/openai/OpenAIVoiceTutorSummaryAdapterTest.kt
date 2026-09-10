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
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusPort
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
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

        assertThat(summary.summaryMarkdown).isEmpty()
        assertThat(summary.model).isEqualTo("system")
        assertThat(requestBody!!.path("model").asText()).isEqualTo("gpt-5.4-2026-03-05")
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
        assertThat(data.path("transcriptTurns").map { it.path("id").longValue() }).containsExactly(1, 2, 3)
        assertThat(data.path("transcriptTurns")[0].path("role").textValue()).isEqualTo("TUTOR")
        assertThat(data.path("transcriptTurns")[1].path("transcript").textValue()).isEqualTo("메모리 공간을 확보하려고요.")
        assertThat(data.path("transcriptTurns")[1].path("studyQuestionTurnId").longValue()).isEqualTo(1)
        assertThat(data.path("transcriptTurns")[2].path("studyAnswerTurnId").longValue()).isEqualTo(2)
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
            envelope(lessonResult(summary = " ")),
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
        val content = mapper.readTree(lessonResult()) as com.fasterxml.jackson.databind.node.ObjectNode
        content.put("summaryMarkdown", "a".repeat(25_000))
        content.set<JsonNode>("strengths", mapper.valueToTree(List(12) { "b".repeat(700) }))
        content.set<JsonNode>(
            "improvements",
            mapper.valueToTree(listOf("<script>discard link</script> [label](https://example.test/private)")),
        )
        val summary = adapter(exchange = ExchangeFunction { Mono.just(response(envelope(content.toString()))) })
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
        assertThat(source.path("transcriptTurns")[1].path("studyQuestionTurnId").longValue()).isEqualTo(1)
        assertThat(source.path("transcriptTurns").map { it.path("askedStudyQuestion").booleanValue() })
            .containsOnly(false)
    }

    @Test
    fun `GPT receives only attested tutor and learner exchanges while setup configuration turns stay private`() = runBlocking<Unit> {
        var source: JsonNode? = null
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(
            UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map {
                        source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                        response(envelope(lessonResult()))
                    }
                })
            },
        )

        val generated = provider.summarize(session(), mixedLessonAndSetupTurns())

        assertThat(source!!.path("transcriptTurns").map { it.path("id").longValue() })
            .containsExactly(1, 2, 3, 7, 8)
        assertThat(source!!.path("transcriptTurns").map { it.path("transcript").textValue() })
            .noneMatch { it.contains("루트") || it == "네" }
        assertThat(source!!.path("transcriptTurns")[3].path("askedStudyQuestion").booleanValue()).isTrue()
        assertThat(generated.explorations).hasSize(1)
    }

    @Test
    fun `mutation-only transcript returns an empty result without invoking GPT`() = runBlocking<Unit> {
        val invoked = AtomicBoolean()
        val provider = adapter(exchange = ExchangeFunction {
            invoked.set(true)
            Mono.just(response(envelope(lessonResult())))
        })
        val mutationOnly = listOf(
            VoiceTutorTranscriptTurn(
                1, session().id, "mutation-command", VoiceTutorTranscriptRole.USER,
                "Spring 주제 이름을 Spring Boot로 바꾸고 레벨을 7로 수정해줘.",
                1, now, lessonRevision = 1,
            ),
            VoiceTutorTranscriptTurn(
                2, session().id, "mutation-acknowledgement", VoiceTutorTranscriptRole.TUTOR,
                "Spring Boot, 레벨 7로 수정했습니다.",
                2, now.plusSeconds(1), lessonRevision = 2,
            ),
        )

        val generated = provider.summarize(session(), mutationOnly)

        assertThat(invoked.get()).isFalse()
        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.strengths).isEmpty()
        assertThat(generated.improvements).isEmpty()
        assertThat(generated.nextSteps).isEmpty()
        assertThat(generated.explorations).isEmpty()
        assertThat(generated.model).isEqualTo("system")
    }

    @Test
    fun `a provider summary citing only filtered setup turns becomes a deterministic empty learning result`() = runBlocking<Unit> {
        val raw = mapper.readTree(lessonResult()) as com.fasterxml.jackson.databind.node.ObjectNode
        raw.put("summaryMarkdown", "루트 주제를 만든 설정 대화입니다.")
        raw.set<JsonNode>("strengths", mapper.valueToTree(listOf("설정 확인")))
        raw.set<JsonNode>("improvements", mapper.valueToTree(listOf("다시 확인")))
        raw.set<JsonNode>("nextSteps", mapper.valueToTree(listOf("루트 생성")))
        val exchange = raw.path("explorations")[0].path("exchanges")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        exchange.put("question", "루트를 만들까요?")
        exchange.put("answer", "네")
        exchange.put("questionTurnId", 5)
        exchange.putArray("answerTurnIds").add(6)
        exchange.putArray("feedbackTurnIds")
        exchange.putNull("score")
        val provider = adapter(exchange = ExchangeFunction {
            Mono.just(response(envelope(raw.toString())))
        })

        val generated = provider.summarize(session(), mixedLessonAndSetupTurns())

        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.strengths).isEmpty()
        assertThat(generated.improvements).isEmpty()
        assertThat(generated.nextSteps).isEmpty()
        assertThat(generated.explorations).isEmpty()
        assertThat(generated.model).isEqualTo("system")
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
        val invoked = AtomicBoolean()
        val adapter = adapter(properties, ExchangeFunction {
            invoked.set(true)
            Mono.just(response(envelope(lessonResult())))
        })

        val generated = adapter.summarize(session(), lessonTurns())

        assertThat(generated.explorations).isEmpty()
        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.strengths).isEmpty()
        assertThat(generated.improvements).isEmpty()
        assertThat(generated.nextSteps).isEmpty()
        assertThat(invoked.get()).isFalse()
    }

    @Test
    fun `summary request bounds only between complete exchanges and reports a truncated multipart answer`() =
        runBlocking<Unit> {
            val firstExchange = lessonTurns()
            val secondExchange = listOf(
                VoiceTutorTranscriptTurn(
                    4, session().id, "question-2", VoiceTutorTranscriptRole.TUTOR,
                    "두 번째 질문", 4, now.plusSeconds(3), isStudyQuestion = true,
                ),
                VoiceTutorTranscriptTurn(
                    5, session().id, "answer-2-part-1", VoiceTutorTranscriptRole.USER,
                    "첫 답변 부분", 5, now.plusSeconds(4), studyQuestionTurnId = 4,
                ),
                VoiceTutorTranscriptTurn(
                    6, session().id, "answer-2-part-2", VoiceTutorTranscriptRole.USER,
                    "둘째 답변 부분", 6, now.plusSeconds(5), studyQuestionTurnId = 4,
                ),
            )
            val firstCharacters = firstExchange.sumOf { it.transcript.length }
            val partialSecondCharacters = secondExchange.take(2).sumOf { it.transcript.length }
            val properties = properties().apply {
                voiceTutor.transcriptMaxCharacters = firstCharacters + partialSecondCharacters
            }
            var source: JsonNode? = null
            val provider = adapter(properties, ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map {
                        source = mapper.readTree(
                            mapper.readTree(it).path("messages").last().path("content").textValue(),
                        )
                        response(envelope(lessonResult()))
                    }
                })
            })

            provider.summarize(session(), firstExchange + secondExchange)

            assertThat(source!!.path("transcriptTruncated").booleanValue()).isTrue()
            assertThat(source!!.path("transcriptTurns").map { it.path("id").longValue() })
                .containsExactly(1, 2, 3)
        }

    @Test
    fun `summary keeps old pending question and next question separate after rename and level change even when live FK is gone`() = runBlocking<Unit> {
        val old = VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7)
        val revised = old.copy(topic = "Advanced eviction", difficulty = 9, revision = 1)
        val history = listOf(VoiceTutorStudySnapshot(42, null, "Accepted root", 3), old, revised)
        val raw = mapper.readTree(lessonResult()) as com.fasterxml.jackson.databind.node.ObjectNode
        val topic = raw.path("explorations")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        topic.put("topic", revised.topic)
        topic.put("difficulty", 9)
        val exchanges = topic.path("exchanges") as com.fasterxml.jackson.databind.node.ArrayNode
        val next = (exchanges[0] as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
        next.put("questionTurnId", 4)
        next.putArray("answerTurnIds").add(5)
        next.putArray("feedbackTurnIds").add(6)
        exchanges.add(next)
        val transcript = lessonTurns() +
            lessonTurns().map {
                it.copy(
                    id = it.id + 3, providerItemId = "second-${it.providerItemId}",
                    sequenceNumber = it.sequenceNumber + 3, lessonRevision = 1,
                    studyQuestionTurnId = it.studyQuestionTurnId?.plus(3),
                    studyAnswerTurnId = it.studyAnswerTurnId?.plus(3),
                )
            }
        var source: JsonNode? = null
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties, ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                    response(envelope(raw.toString()))
                }
            })
        }, immutableContext(history))

        val result = provider.summarize(session().copy(studyId = null), transcript)

        assertThat(result.explorations.map { it.topic }).containsExactly(old.topic, revised.topic)
        assertThat(result.explorations.map { it.difficulty }).containsExactly(7, 9)
        assertThat(result.explorations.map { it.exchanges.single().questionTurnId }).containsExactly(1, 4)
        assertThat(result.explorations.flatMap { it.exchanges }.map { it.score }).containsExactly(85, 85)
        assertThat(source!!.path("knownTopics").map { it.path("studyId").longValue() }).containsExactly(42, 43, 43)
        assertThat(source!!.path("knownTopics").map { it.path("revision").longValue() }).containsExactly(0, 0, 1)
        assertThat(source!!.path("transcriptTurns").map { it.path("lessonRevision").longValue() }).containsExactly(0, 0, 0, 1, 1, 1)
    }

    @Test
    fun `all sixty four baselines and thirty two captured changes reach the summary instead of dropping revisions after row sixty four`() = runBlocking<Unit> {
        val bases = (300L..363L).map { VoiceTutorStudySnapshot(it, null, "Synthetic topic $it", 3) }
        val revisions = (1L..32L).map { bases.first().copy(topic = "Explicit version $it", revision = it) }
        var source: JsonNode? = null
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties, ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                    response(envelope(result()))
                }
            })
        }, immutableContext(bases + revisions))

        provider.summarize(session().copy(studyId = 300, acceptedStudyId = 300), transcript())

        assertThat(source!!.path("knownTopics").size()).isEqualTo(96)
        assertThat(source!!.path("knownTopics").filter { it.path("revision").longValue() > 0 }.map { it.path("revision").longValue() })
            .containsExactlyElementsOf((1L..32L).toList())
    }

    @Test
    fun `unknown response epoch keeps a valid session summary but never adopts a model guessed study or level`() = runBlocking<Unit> {
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { Mono.just(response(envelope(lessonResult()))) },
            immutableContext(listOf(VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7))),
        )
        val result = provider.summarize(session(), lessonTurns().map { it.copy(lessonRevision = -1) })
        assertThat(result.summaryMarkdown).isEmpty()
        assertThat(result.explorations).isEmpty()
    }

    @Test
    fun `discovery summary receives null metadata and no focus instead of inventing a study or level`(): Unit = runBlocking {
        var source: JsonNode? = null
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map {
                        source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                        response(envelope(result()))
                    }
                })
            }, immutableContext(emptyList()), immutableFocuses(emptyList()),
        )

        val generated = provider.summarize(session().copy(studyId = null, acceptedStudyId = null, topic = "", difficulty = 0), transcript())

        assertThat(source).isNull()
        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.explorations).isEmpty()
    }

    @Test
    fun `focus-aware summary verification removes preselection navigation without rejecting the overall summary`(): Unit = runBlocking {
        val selected = VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7, 1)
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { Mono.just(response(envelope(lessonResult()))) },
            immutableContext(listOf(selected.copy(revision = 0), selected)),
            immutableFocuses(listOf(VoiceTutorLessonFocus(43, 1))),
        )
        val transcript = lessonTurns().map { if (it.id == 1L) it else it.copy(lessonRevision = 1) }

        val generated = provider.summarize(session().copy(studyId = 43, acceptedStudyId = null), transcript)

        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.explorations).isEmpty()
    }

    @Test
    fun `final focus title is never paired with the immutable creation ID and earlier question evidence keeps its original focus`(): Unit = runBlocking {
        val first = VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7, 1)
        val last = VoiceTutorStudySnapshot(90, null, "Final Kafka", 9, 2)
        var source: JsonNode? = null
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map {
                        source = mapper.readTree(mapper.readTree(it).path("messages").last().path("content").textValue())
                        response(envelope(lessonResult()))
                    }
                })
            }, immutableContext(listOf(first.copy(revision = 0), first, last.copy(revision = 0), last)),
            immutableFocuses(listOf(VoiceTutorLessonFocus(43, 1), VoiceTutorLessonFocus(90, 2))),
        )

        val generated = provider.summarize(
            session().copy(studyId = 90, acceptedStudyId = 42, topic = last.topic, difficulty = last.difficulty),
            lessonTurns().map { it.copy(lessonRevision = 1) },
        )

        assertThat(source!!.path("knownTopics").map { it.path("studyId").longValue() }).doesNotContain(42)
        assertThat(source!!.path("lessonFocuses").map { it.path("studyId").longValue() }).containsExactly(42, 43, 90)
        assertThat(generated.explorations.single().studyId).isEqualTo(43)
        assertThat(generated.explorations.single().difficulty).isEqualTo(7)
        assertThat(generated.explorations.single().exchanges.single().score).isEqualTo(85)
    }

    private fun immutableFocuses(focuses: List<VoiceTutorLessonFocus>) = object : VoiceTutorLessonFocusPort {
        override suspend fun focus(
            userId: Long,
            sessionId: String,
            studyId: Long,
            learnerTurnId: Long?,
            expectedCurrentRevision: Long?,
            authorization: VoiceTutorFocusAuthorization?,
            expectedCandidate: com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate?,
            commitAuthority: com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority?,
            expectedTraversal: com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal?,
        ): VoiceTutorLessonFocusSelection? =
            error("Summary must never select a lesson or mutate focus history")

        override suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> {
            assertThat(userId).isEqualTo(7)
            assertThat(sessionId).isEqualTo(session().id)
            return focuses
        }
    }

    private fun assertProviderFailure(error: Throwable?) {
        assertThat(error).isInstanceOf(ApiException::class.java)
        assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(error.message).doesNotContain("PRIVATE_", "private-test-regular-key")
    }

    @Test
    fun `native setup only verdict completes empty without a prose summary request`() = runBlocking<Unit> {
        var calls = 0
        var body: JsonNode? = null
        val provider = adapter(exchange = ExchangeFunction { request ->
            calls += 1
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    body = mapper.readTree(it)
                    response(envelope("""{"exchanges":[],"learnerQuestions":[]}"""))
                }
            })
        })
        val source = nativeTurns().take(2).mapIndexed { index, turn ->
            turn.copy(transcript = if (index == 0) "스프링을 레벨 7로 바꿀까요?" else "네")
        }
        val result = provider.summarize(session(), source)
        assertThat(calls).isEqualTo(1)
        assertThat(result.model).isEqualTo("system")
        assertThat(result.summaryMarkdown).isEmpty()
        assertThat(result.explorations).isEmpty()
        assertThat(result.strengths).isEmpty()
        assertThat(result.improvements).isEmpty()
        assertThat(result.postCallEvidence!!.exchanges).isEmpty()
        assertThat(body!!.path("response_format").path("json_schema").path("name").asText())
            .isEqualTo("voice_tutor_post_call_evidence")
        assertThat(body!!.path("messages")[0].path("content").asText())
            .contains("NOT learning", "not a selected prefix/suffix/subset", "untrusted quoted source")
    }

    @Test
    fun `native call first verifies exact Q and A then reuses summary evidence without changing raw rows`() = runBlocking<Unit> {
        val requests = mutableListOf<JsonNode>()
        val properties = properties()
        val provider = OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map {
                        requests.add(mapper.readTree(it))
                        response(envelope(if (requests.size == 1) nativeEvidence() else lessonResult()))
                    }
                })
            }, immutableContext(listOf(VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7, 1))),
            immutableFocuses(listOf(VoiceTutorLessonFocus(43, 1))),
        )
        val source = nativeTurns().map { it.copy(lessonRevision = 1) }
        val result = provider.summarize(session(), source)
        assertThat(requests).hasSize(2)
        assertThat(result.explorations.single().exchanges.single().score).isEqualTo(85)
        assertThat(result.postCallEvidence!!.exchanges.single().answerTurnIds).containsExactly(2L)
        val secondInput = mapper.readTree(requests[1].path("messages").last().path("content").textValue())
        assertThat(secondInput.path("transcriptTurns")[0].path("isStudyQuestion").booleanValue()).isTrue()
        assertThat(secondInput.path("transcriptTurns")[1].path("studyQuestionTurnId").longValue()).isEqualTo(1)
        assertThat(secondInput.path("transcriptTurns")[2].path("studyAnswerTurnId").longValue()).isEqualTo(2)
        assertThat(source.all { !it.isStudyQuestion && it.studyQuestionTurnId == null && it.studyAnswerTurnId == null }).isTrue()
    }

    @Test
    fun `archived tutor fragment retains source ordering but never enters the prose summary`() = runBlocking<Unit> {
        val requests = mutableListOf<JsonNode>()
        val provider = adapter(exchange = ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    requests.add(mapper.readTree(it))
                    response(envelope(if (requests.size == 1) nativeEvidence() else lessonResult()))
                }
            })
        })
        val original = nativeTurns()
        val fragment = original.last().copy(
            id = 4, providerItemId = "interrupted-fragment", sequenceNumber = 4,
            transcript = "PRIVATE_ARCHIVED_FRAGMENT", postCallEvidence = false, interrupted = true,
        )
        val source = original + fragment

        val result = provider.summarize(session(), source)

        assertThat(requests).hasSize(2)
        val evidenceInput = mapper.readTree(requests[0].path("messages").last().path("content").asText())
        val sourceTurns = evidenceInput.path("transcriptTurns")
        assertThat(sourceTurns.map { it.path("id").longValue() }).containsExactly(1, 2, 3, 4)
        assertThat(sourceTurns.last().path("interrupted").booleanValue()).isTrue()
        assertThat(sourceTurns.last().path("nativeSource").booleanValue()).isFalse()
        assertThat(sourceTurns.last().path("sequence").longValue()).isEqualTo(4)
        assertThat(requests[0].path("messages")[0].path("content").asText())
            .contains("interrupted=true", "ordering boundary")
        assertThat(requests[1].toString()).doesNotContain("PRIVATE_ARCHIVED_FRAGMENT")
        val summaryInput = mapper.readTree(requests[1].path("messages").last().path("content").asText())
        assertThat(summaryInput.path("transcriptTurns").map { it.path("id").longValue() }).containsExactly(1, 2, 3)
        assertThat(result.postCallEvidence!!.attestedTranscript(session().id, source, emptyList(), session().acceptedStudyId))
            .contains(fragment)
    }

    @Test
    fun `native evidence cannot cite an invented or partial answer before summary generation`() = runBlocking<Unit> {
        listOf(
            """{"exchanges":[{"questionTurnId":1,"answerTurnIds":[99],"feedbackTurnId":3}],"learnerQuestions":[]}""",
            """{"exchanges":[{"questionTurnId":1,"answerTurnIds":[2],"feedbackTurnId":null}],"learnerQuestions":[]}""",
        ).forEach { invalid ->
            var calls = 0
            val provider = adapter(exchange = ExchangeFunction {
                calls += 1
                Mono.just(response(envelope(invalid)))
            })
            val source = nativeTurns().toMutableList().apply {
                add(2, this[1].copy(id = 4, providerItemId = "continued-answer", sequenceNumber = 3))
                this[3] = this[3].copy(sequenceNumber = 4)
            }
            assertProviderFailure(runCatching { provider.summarize(session(), source) }.exceptionOrNull())
            assertThat(calls).isEqualTo(1)
        }
    }

    @Test
    fun `native evidence refuses incomplete model output before summary generation`() = runBlocking<Unit> {
        var calls = 0
        val provider = adapter(exchange = ExchangeFunction {
            calls += 1
            Mono.just(response(envelope(nativeEvidence(), finishReason = "length")))
        })
        assertProviderFailure(runCatching { provider.summarize(session(), nativeTurns()) }.exceptionOrNull())
        assertThat(calls).isEqualTo(1)
    }

    private fun nativeTurns() = lessonTurns().map {
        it.copy(isStudyQuestion = false, studyQuestionTurnId = null, studyAnswerTurnId = null, postCallEvidence = true)
    }

    @Test
    fun `second pass rejects setup even when first postcall classifier proposed a Q and A link`() = runBlocking<Unit> {
        val requests = mutableListOf<JsonNode>()
        val provider = adapter(exchange = ExchangeFunction { request ->
            val output = MockClientHttpRequest(request.method(), request.url())
            request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                output.bodyAsString.map {
                    requests.add(mapper.readTree(it))
                    response(envelope(if (requests.size == 1) nativeEvidence() else result(summary = "")))
                }
            })
        })
        val source = nativeTurns().mapIndexed { index, turn ->
            turn.copy(transcript = listOf("레벨 7로 바꿀까요?", "네", "레벨 7로 바꿨어요.")[index])
        }
        val generated = provider.summarize(session(), source)
        assertThat(requests).hasSize(2)
        assertThat(requests[1].path("messages").map { it.path("content").asText() }.joinToString("\n"))
            .contains("independently verify", "Do not assume the flags alone")
        assertThat(generated.summaryMarkdown).isEmpty()
        assertThat(generated.explorations).isEmpty()
        assertThat(generated.postCallEvidence!!.exchanges).isEmpty()
    }

    private fun nativeEvidence() =
        """{"exchanges":[{"questionTurnId":1,"answerTurnIds":[2],"feedbackTurnId":3}],"learnerQuestions":[]}"""

    private fun adapter(properties: BuddyStudyProperties = properties(), exchange: ExchangeFunction) =
        OpenAIVoiceTutorSummaryAdapter(UserContentOpenAIKeyProvider(properties), properties, exchange)

    private fun immutableContext(studies: List<VoiceTutorStudySnapshot>) = object : VoiceTutorStudyContextPort {
        override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> = studies
        override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> = error("Summary must not recapture current metadata")
        override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot> = error("Summary is read-only")
    }

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

    private fun transcript() = lessonTurns()

    private fun lessonTurns() = listOf(
        VoiceTutorTranscriptTurn(
            1, session().id, "question", VoiceTutorTranscriptRole.TUTOR, "키를 왜 제거하나요?", 1, now,
            isStudyQuestion = true,
        ),
        VoiceTutorTranscriptTurn(
            2, session().id, "answer", VoiceTutorTranscriptRole.USER,
            "메모리 공간을 확보하려고요.", 2, now.plusSeconds(1), studyQuestionTurnId = 1,
        ),
        VoiceTutorTranscriptTurn(
            3, session().id, "feedback", VoiceTutorTranscriptRole.TUTOR,
            "85점입니다. 공간 확보 목적을 이해했네요. LRU와 LFU 차이를 복습하세요.",
            3, now.plusSeconds(2), studyAnswerTurnId = 2,
        ),
    )

    private fun mixedLessonAndSetupTurns() = lessonTurns() + listOf(
        VoiceTutorTranscriptTurn(4, session().id, "configure-root", VoiceTutorTranscriptRole.USER, "스프링으로 새롭게 공부하고 싶다.", 4, now.plusSeconds(3)),
        VoiceTutorTranscriptTurn(5, session().id, "confirm-root", VoiceTutorTranscriptRole.TUTOR, "루트를 만들까요?", 5, now.plusSeconds(4)),
        VoiceTutorTranscriptTurn(6, session().id, "confirm-root-answer", VoiceTutorTranscriptRole.USER, "네", 6, now.plusSeconds(5)),
        VoiceTutorTranscriptTurn(
            7, session().id, "learner-question", VoiceTutorTranscriptRole.USER,
            "LRU와 LFU는 어떻게 다른가요?", 7, now.plusSeconds(6), askedStudyQuestion = true,
        ),
        VoiceTutorTranscriptTurn(8, session().id, "learner-question-answer", VoiceTutorTranscriptRole.TUTOR, "LRU는 최근성, LFU는 빈도를 봅니다.", 8, now.plusSeconds(7)),
    )

    private fun lessonResult(summary: String = "합성 학습 요약"): String {
        val root = mapper.readTree(result(summary)) as com.fasterxml.jackson.databind.node.ObjectNode
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
