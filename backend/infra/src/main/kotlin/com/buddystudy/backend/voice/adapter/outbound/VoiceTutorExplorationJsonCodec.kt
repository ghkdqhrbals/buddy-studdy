package com.buddystudy.backend.voice.adapter.outbound

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorExplorationLimits
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode

/** Provider groups stay small; the same shape can be split into bounded question epochs for storage. */
internal object VoiceTutorExplorationJsonCodec {
    private val mapper = JsonMapperProvider.mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun decode(value: String?): List<VoiceTutorExploration> {
        if (value == null) return emptyList() // Rows written before the additive migration.
        checkSize(value)
        val node = try {
            mapper.readTree(value)
        } catch (_: Exception) {
            invalid("EXPLORATIONS_JSON")
        }
        if (node == null || node.isNull) return emptyList()
        return decodeNode(node, VoiceTutorExplorationLimits.MAX_STORED_EXPLORATIONS)
    }

    fun decodeNode(node: JsonNode): List<VoiceTutorExploration> = decodeNode(node, VoiceTutorExplorationLimits.MAX_EXPLORATIONS)

    private fun decodeNode(node: JsonNode, maxExplorations: Int): List<VoiceTutorExploration> {
        requireValid(node.isArray && node.size() <= maxExplorations, "EXPLORATIONS_SHAPE")
        checkSize(node.toString())
        var exchangeCount = 0
        return node.map { exploration ->
            requireFields(exploration, EXPLORATION_FIELDS)
            val exchanges = exploration.path("exchanges")
            requireValid(exchanges.isArray && exchanges.size() in 1..VoiceTutorExplorationLimits.MAX_EXCHANGES_PER_EXPLORATION, "EXCHANGES_SHAPE")
            exchangeCount += exchanges.size()
            requireValid(exchangeCount <= VoiceTutorExplorationLimits.MAX_TOTAL_EXCHANGES, "EXCHANGES_LIMIT")
            val studyId = nullableLong(exploration.path("studyId"), "STUDY_ID")
            requireValid(studyId == null || studyId > 0, "STUDY_ID")
            val difficulty = nullableInt(exploration.path("difficulty"), "DIFFICULTY")
            requireValid(difficulty == null || difficulty in 1..10, "DIFFICULTY")
            VoiceTutorExploration(
                topic = text(exploration.path("topic"), VoiceTutorExplorationLimits.MAX_TOPIC_CHARACTERS, "TOPIC"),
                studyId = studyId,
                difficulty = difficulty,
                depthSummary = text(exploration.path("depthSummary"), VoiceTutorExplorationLimits.MAX_DEPTH_CHARACTERS, "DEPTH_SUMMARY"),
                exchanges = exchanges.map { exchange ->
                    requireFields(exchange, EXCHANGE_FIELDS)
                    val kind = VoiceTutorExchangeKind.entries.firstOrNull { it.name == exchange.path("kind").textValue() }
                        ?: invalid("EXCHANGE_KIND")
                    val questionId = positiveLong(exchange.path("questionTurnId"))
                    val answerIds = ids(exchange.path("answerTurnIds"))
                    val feedbackIds = ids(exchange.path("feedbackTurnIds"))
                    val score = nullableInt(exchange.path("score"), "SCORE")
                    requireValid(score == null || score in 0..100, "SCORE")
                    val strengths = strings(exchange.path("strengths"))
                    val improvements = strings(exchange.path("improvements"))
                    val answer = text(exchange.path("answer"), VoiceTutorExplorationLimits.MAX_EXCHANGE_TEXT_CHARACTERS, "ANSWER", allowEmpty = true)
                    VoiceTutorLearningExchange(
                        kind = kind,
                        question = text(exchange.path("question"), VoiceTutorExplorationLimits.MAX_EXCHANGE_TEXT_CHARACTERS, "QUESTION"),
                        answer = answer,
                        score = score,
                        strengths = strengths,
                        improvements = improvements,
                        questionTurnId = questionId,
                        answerTurnIds = answerIds,
                        feedbackTurnIds = feedbackIds,
                    )
                },
            )
        }
    }

    fun encode(explorations: List<VoiceTutorExploration>): String {
        // Explicit maps avoid reflective construction of domain models in the native image.
        val json = mapper.writeValueAsString(explorations.map { exploration ->
            linkedMapOf(
                "topic" to exploration.topic,
                "studyId" to exploration.studyId,
                "difficulty" to exploration.difficulty,
                "depthSummary" to exploration.depthSummary,
                "exchanges" to exploration.exchanges.map { exchange ->
                    linkedMapOf(
                        "kind" to exchange.kind.name,
                        "question" to exchange.question,
                        "answer" to exchange.answer,
                        "score" to exchange.score,
                        "strengths" to exchange.strengths,
                        "improvements" to exchange.improvements,
                        "questionTurnId" to exchange.questionTurnId,
                        "answerTurnIds" to exchange.answerTurnIds,
                        "feedbackTurnIds" to exchange.feedbackTurnIds,
                    )
                },
            )
        })
        decode(json) // Enforce the storage bounds even for non-provider callers.
        return json
    }

    private fun strings(node: JsonNode): List<String> {
        requireValid(node.isArray && node.size() <= VoiceTutorExplorationLimits.MAX_FEEDBACK_ITEMS, "FEEDBACK_SHAPE")
        return node.map { text(it, VoiceTutorExplorationLimits.MAX_FEEDBACK_CHARACTERS, "FEEDBACK") }
    }

    private fun ids(node: JsonNode): List<Long> {
        requireValid(node.isArray && node.size() <= VoiceTutorExplorationLimits.MAX_EVIDENCE_TURNS, "EVIDENCE_SHAPE")
        return node.map(::positiveLong)
    }

    private fun positiveLong(node: JsonNode): Long {
        requireValid(node.isIntegralNumber && node.canConvertToLong() && node.longValue() > 0, "TURN_ID")
        return node.longValue()
    }

    private fun nullableLong(node: JsonNode, reason: String): Long? {
        if (node.isNull) return null
        requireValid(node.isIntegralNumber && node.canConvertToLong(), reason)
        return node.longValue()
    }

    private fun nullableInt(node: JsonNode, reason: String): Int? {
        if (node.isNull) return null
        requireValid(node.isIntegralNumber && node.canConvertToInt(), reason)
        return node.intValue()
    }

    private fun text(node: JsonNode, max: Int, reason: String, allowEmpty: Boolean = false): String {
        requireValid(node.isTextual, reason)
        val value = node.textValue()
        requireValid(value.length <= max && (allowEmpty || value.isNotBlank()), reason)
        return value
    }

    private fun requireFields(node: JsonNode, fields: Set<String>) =
        requireValid(node.isObject && node.fieldNames().asSequence().toSet() == fields, "EXPLORATION_FIELDS")

    private fun checkSize(value: String) = requireValid(value.toByteArray(Charsets.UTF_8).size <= VoiceTutorExplorationLimits.MAX_JSON_BYTES, "EXPLORATIONS_BYTES")

    private fun requireValid(condition: Boolean, reason: String) {
        if (!condition) invalid(reason)
    }

    private fun invalid(reason: String): Nothing = throw InvalidVoiceTutorExploration(reason)

    private val EXPLORATION_FIELDS = setOf("topic", "studyId", "difficulty", "depthSummary", "exchanges")
    private val EXCHANGE_FIELDS = setOf("kind", "question", "answer", "score", "strengths", "improvements", "questionTurnId", "answerTurnIds", "feedbackTurnIds")
}

/** Fixed reason codes only; never carries transcript, generated text, or provider bodies. */
internal class InvalidVoiceTutorExploration(val reason: String) : RuntimeException("Voice Tutor exploration was invalid: $reason")
