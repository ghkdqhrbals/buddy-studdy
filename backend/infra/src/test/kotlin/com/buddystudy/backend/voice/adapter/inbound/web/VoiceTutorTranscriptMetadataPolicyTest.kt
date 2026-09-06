package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorWebRtcUseCase
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

/** Local relay/payload contracts only; never opens sockets or calls an external service. */
class VoiceTutorTranscriptMetadataPolicyTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `server-confirmed focus is visible but malformed or legacy focus is not`() {
        val policy = VoiceTutorRealtimeEventPolicy()
        val focus = mapOf("studyId" to 5, "parentStudyId" to 2, "topic" to "Spring", "difficulty" to 7, "revision" to 3)
        val raw = mapper.writeValueAsString(mapOf("type" to "buddystudy.voice.study.focused", "focus" to focus))
        val valid = policy.providerDecision(raw, "session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
        assertThat(mapper.readTree(valid.payload).path("focus").path("difficulty").asInt()).isEqualTo(7)
        assertThat(policy.providerDecision(raw, "session", Instant.EPOCH).payload).isNull()
        val invalid = mapper.writeValueAsString(mapOf("type" to "buddystudy.voice.study.focused", "focus" to (focus + ("difficulty" to 99))))
        assertThat(policy.providerDecision(invalid, "session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND).payload).isNull()
    }

    @Test
    fun `all transcript client payloads strip the internal revision without changing the original content`(): Unit {
        val policy = VoiceTutorRealtimeEventPolicy()
        listOf(
            "response.output_audio_transcript.delta" to "delta",
            "response.output_audio_transcript.done" to "transcript",
            "conversation.item.input_audio_transcription.delta" to "delta",
            "conversation.item.input_audio_transcription.completed" to "transcript",
        ).forEach { (type, field) ->
            val raw = mapper.writeValueAsString(mapOf(
                "type" to type, "item_id" to "owned-item", "response_id" to "owned-response",
                field to "원문 그대로", VoiceTutorTranscriptMetadata.LESSON_REVISION to 3,
                VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID to "private-question-item",
                VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID to "private-answer-item",
                VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS to
                    listOf("private-answer-part-1", "private-answer-part-2"),
                VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION to true,
                VoiceTutorTranscriptMetadata.POST_CALL_EVIDENCE to true,
                VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE to 7,
            ))

            val decision = policy.providerDecision(raw, "session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND)
            val payload = mapper.readTree(decision.payload)

            assertThat(decision.terminate).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.LESSON_REVISION)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.POST_CALL_EVIDENCE)).isFalse()
            assertThat(payload.has(VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE)).isFalse()
            assertThat(payload.path(field).asText()).isEqualTo("원문 그대로")
            assertThat(payload.path("response_id").asText()).isEqualTo("owned-response")
            assertThat(VoiceTutorTranscriptMetadata.lessonRevision(mapper.readTree(raw))).isEqualTo(3)
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(mapper.readTree(raw)))
                .isEqualTo("private-question-item")
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(mapper.readTree(raw)))
                .containsExactly("private-answer-part-1", "private-answer-part-2")
            assertThat(VoiceTutorTranscriptMetadata.askedStudyQuestion(mapper.readTree(raw))).isTrue()
            assertThat(VoiceTutorTranscriptMetadata.postCallEvidence(mapper.readTree(raw))).isTrue()
            assertThat(VoiceTutorTranscriptMetadata.conversationSequence(mapper.readTree(raw))).isEqualTo(7)
        }
    }

    @Test
    fun `native incomplete source event persists only the owned session fence and is never public`(): Unit {
        val principal = Principal(7, "device", 70, anonymous = false)
        var persisted = false
        val relay = proxy<VoiceTutorRelayUseCase> { method, args ->
            check(method == "markTranscriptIncomplete")
            assertThat(args[0]).isEqualTo(principal)
            assertThat(args[1]).isEqualTo("owned-session")
            persisted
        }
        val handler = VoiceTutorControlWebSocketHandler(
            proxy<VoiceTutorWebRtcUseCase> { _, _ -> error("No provider connection is expected.") },
            relay, proxy<VoiceTutorUseCase> { _, _ -> error("No quota request is expected.") },
            VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
        )
        val inspect = handler.javaClass.getDeclaredMethod(
            "inspectProviderEvent", Principal::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        val raw = mapper.writeValueAsString(mapOf("type" to VoiceTutorTranscriptMetadata.INCOMPLETE_EVENT))
        assertThat((inspect.invoke(handler, principal, "owned-session", raw) as Mono<*>).block()).isEqualTo(false)
        persisted = true
        assertThat((inspect.invoke(handler, principal, "owned-session", raw) as Mono<*>).block()).isEqualTo(true)
        val decision = VoiceTutorRealtimeEventPolicy().providerDecision(
            raw, "owned-session", Instant.EPOCH, VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
        )
        assertThat(decision.payload).isNull()
        assertThat(decision.terminate).isFalse()
    }

    @Test
    fun `missing malformed and unassigned metadata never silently fall back to the initial level`(): Unit {
        listOf("null", "0", "-1", "1.5", "\"1\"", "9223372036854775808").forEach { value ->
            assertThat(VoiceTutorTranscriptMetadata.conversationSequence(mapper.readTree(
                """{"${VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE}":$value}""",
            ))).isNull()
        }
        assertThat(VoiceTutorTranscriptMetadata.postCallEvidence(mapper.readTree(
            """{"${VoiceTutorTranscriptMetadata.POST_CALL_EVIDENCE}":"true"}""",
        ))).isFalse()
        val invalid = listOf(
            "{}",
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":null}""",
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":"4"}""",
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":-2}""",
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":1.5}""",
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":9223372036854775808}""",
        )
        invalid.forEach { assertThat(VoiceTutorTranscriptMetadata.lessonRevision(mapper.readTree(it))).isEqualTo(-1) }
        assertThat(VoiceTutorTranscriptMetadata.lessonRevision(mapper.readTree(
            """{"${VoiceTutorTranscriptMetadata.LESSON_REVISION}":0}""",
        ))).isZero()
        assertThat(VoiceTutorTranscriptMetadata.askedStudyQuestion(mapper.readTree(
            """{"${VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION}":"true"}""",
        ))).isFalse()

        val answerPartsField = VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS
        listOf(
            "{}",
            """{"$answerPartsField":null}""",
            """{"$answerPartsField":"part"}""",
            """{"$answerPartsField":[]}""",
            """{"$answerPartsField":["same","same"]}""",
            """{"$answerPartsField":["part",3]}""",
            """{"$answerPartsField":[""]}""",
            mapper.writeValueAsString(mapOf(answerPartsField to (1..33).map { "part-$it" })),
        ).forEach { raw ->
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(mapper.readTree(raw))).isEmpty()
        }
        assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(mapper.readTree(
            """{"$answerPartsField":["part-1","part-2"]}""",
        ))).containsExactly("part-1", "part-2")
    }

    @Test
    fun `both server handlers pass known and unassigned epochs to transcript persistence unchanged`(): Unit {
        val received = mutableListOf<Triple<VoiceTutorTranscriptRole, String, Long>>()
        val questionEvidence = mutableListOf<String?>()
        val learnerQuestionEvidence = mutableListOf<Boolean>()
        val answerFeedbackEvidence = mutableListOf<String?>()
        val answerPartEvidence = mutableListOf<List<String>>()
        val relay = proxy<VoiceTutorRelayUseCase> { method, args ->
            check(method == "appendTranscript")
            received += Triple(args[3] as VoiceTutorTranscriptRole, args[4] as String, args[6] as Long)
            questionEvidence += args[7] as String?
            answerFeedbackEvidence += args[8] as String?
            learnerQuestionEvidence += args[9] as Boolean
            @Suppress("UNCHECKED_CAST")
            answerPartEvidence += args[11] as List<String>
            true
        }
        val voiceTutor = proxy<VoiceTutorUseCase> { _, _ -> error("No quota or session request is expected.") }
        val handlers = listOf(
            VoiceTutorControlWebSocketHandler(
                proxy<VoiceTutorWebRtcUseCase> { _, _ -> error("No provider connection is expected.") },
                relay, voiceTutor, VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
            ),
            VoiceTutorWebSocketHandler(relay, voiceTutor),
        )
        val principal = Principal(7, "device", 70, anonymous = false)
        handlers.forEach { handler ->
            val inspect = handler.javaClass.getDeclaredMethod(
                "inspectProviderEvent", Principal::class.java, String::class.java, String::class.java,
            ).apply { isAccessible = true }
            val events = listOf(
                mapOf("type" to "response.output_audio_transcript.done", "response_id" to "tutor", "item_id" to "tutor-item",
                    "transcript" to "기존 질문 원문", VoiceTutorTranscriptMetadata.LESSON_REVISION to 4,
                    VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID to "exact-answer"),
                mapOf("type" to "conversation.item.input_audio_transcription.completed", "item_id" to "learner-item",
                    "transcript" to "불확정 발화 원문", VoiceTutorTranscriptMetadata.LESSON_REVISION to -1,
                    VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID to "exact-study-question",
                    VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS to
                        listOf("exact-answer-part-1", "learner-item"),
                    VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION to true),
                mapOf("type" to "conversation.item.input_audio_transcription.completed", "item_id" to "missing-epoch",
                    "transcript" to "메타데이터 없는 원문"),
            )
            events.forEach { event ->
                (inspect.invoke(handler, principal, "session", mapper.writeValueAsString(event)) as Mono<*>)
                    .block(Duration.ofSeconds(1)).let { assertThat(it).isEqualTo(true) }
            }
        }

        assertThat(received).containsExactly(
            Triple(VoiceTutorTranscriptRole.TUTOR, "기존 질문 원문", 4L),
            Triple(VoiceTutorTranscriptRole.USER, "불확정 발화 원문", -1L),
            Triple(VoiceTutorTranscriptRole.USER, "메타데이터 없는 원문", -1L),
            Triple(VoiceTutorTranscriptRole.TUTOR, "기존 질문 원문", 4L),
            Triple(VoiceTutorTranscriptRole.USER, "불확정 발화 원문", -1L),
            Triple(VoiceTutorTranscriptRole.USER, "메타데이터 없는 원문", -1L),
        )
        assertThat(questionEvidence).containsExactly(
            null, "exact-study-question", null,
            null, "exact-study-question", null,
        )
        assertThat(learnerQuestionEvidence).containsExactly(
            false, true, false,
            false, true, false,
        )
        assertThat(answerFeedbackEvidence).containsExactly(
            "exact-answer", null, null,
            "exact-answer", null, null,
        )
        assertThat(answerPartEvidence).containsExactly(
            emptyList(), listOf("exact-answer-part-1", "learner-item"), emptyList(),
            emptyList(), listOf("exact-answer-part-1", "learner-item"), emptyList(),
        )
    }

    @Test
    fun `both handlers preserve a false storage receipt rather than treating forwarding as persistence`(): Unit {
        val relay = proxy<VoiceTutorRelayUseCase> { method, _ ->
            check(method == "appendTranscript")
            false
        }
        val voiceTutor = proxy<VoiceTutorUseCase> { _, _ -> error("No session or quota call is expected.") }
        val handlers = listOf(
            VoiceTutorControlWebSocketHandler(
                proxy<VoiceTutorWebRtcUseCase> { _, _ -> error("No provider connection is expected.") },
                relay, voiceTutor, VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
            ),
            VoiceTutorWebSocketHandler(relay, voiceTutor),
        )
        val raw = mapper.writeValueAsString(mapOf(
            "type" to "conversation.item.input_audio_transcription.completed", "item_id" to "not-stored",
            "transcript" to "저장되지 않은 응답", VoiceTutorTranscriptMetadata.LESSON_REVISION to 3,
        ))
        handlers.forEach { handler ->
            val inspect = handler.javaClass.getDeclaredMethod(
                "inspectProviderEvent", Principal::class.java, String::class.java, String::class.java,
            ).apply { isAccessible = true }
            val result = inspect.invoke(handler, Principal(7, "device", 70, anonymous = false), "session", raw) as Mono<*>
            assertThat(result.block(Duration.ofSeconds(1))).isEqualTo(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(crossinline invocation: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "toString" -> "Synthetic ${T::class.simpleName}"
                "hashCode" -> 1
                "equals" -> false
                else -> invocation(method.name, args ?: emptyArray())
            }
        } as T
}
