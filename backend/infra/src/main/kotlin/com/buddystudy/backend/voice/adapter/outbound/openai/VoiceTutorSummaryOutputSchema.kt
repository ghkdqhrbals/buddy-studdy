package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExplorationLimits

/** Strict provider shape; correlation/semantic evidence is checked separately per exchange. */
internal object VoiceTutorSummaryOutputSchema {
    fun responseFormat(): Map<String, Any> = mapOf(
        "type" to "json_schema",
        "json_schema" to mapOf(
            "name" to "voice_tutor_learning_result",
            "strict" to true,
            "schema" to resultSchema,
        ),
    )

    private fun text(max: Int, min: Int = 0): Map<String, Any> =
        mapOf("type" to "string", "minLength" to min, "maxLength" to max)

    private fun array(items: Map<String, Any>, max: Int, min: Int = 0): Map<String, Any> =
        mapOf("type" to "array", "items" to items, "minItems" to min, "maxItems" to max)

    private fun objectShape(fields: Map<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to fields,
        "required" to fields.keys.toList(),
        "additionalProperties" to false,
    )

    private val evidenceIds = array(
        mapOf("type" to "integer", "minimum" to 1),
        VoiceTutorExplorationLimits.MAX_EVIDENCE_TURNS,
    )
    private val feedbackItems = array(
        text(VoiceTutorExplorationLimits.MAX_FEEDBACK_CHARACTERS, 1),
        VoiceTutorExplorationLimits.MAX_FEEDBACK_ITEMS,
    )
    private val exchangeSchema = objectShape(linkedMapOf(
        "kind" to mapOf("type" to "string", "enum" to VoiceTutorExchangeKind.entries.map { it.name }),
        "question" to text(VoiceTutorExplorationLimits.MAX_EXCHANGE_TEXT_CHARACTERS, 1),
        "answer" to text(VoiceTutorExplorationLimits.MAX_EXCHANGE_TEXT_CHARACTERS),
        "score" to mapOf("type" to listOf("integer", "null"), "minimum" to 0, "maximum" to 100),
        "strengths" to feedbackItems,
        "improvements" to feedbackItems,
        "questionTurnId" to mapOf("type" to "integer", "minimum" to 1),
        "answerTurnIds" to evidenceIds,
        "feedbackTurnIds" to evidenceIds,
    ))
    private val explorationSchema = objectShape(linkedMapOf(
        "topic" to text(VoiceTutorExplorationLimits.MAX_TOPIC_CHARACTERS, 1),
        "studyId" to mapOf("type" to listOf("integer", "null"), "minimum" to 1),
        "difficulty" to mapOf("type" to listOf("integer", "null"), "minimum" to 1, "maximum" to 10),
        "depthSummary" to text(VoiceTutorExplorationLimits.MAX_DEPTH_CHARACTERS, 1),
        "exchanges" to array(exchangeSchema, VoiceTutorExplorationLimits.MAX_EXCHANGES_PER_EXPLORATION, 1),
    ))
    private val resultSchema = objectShape(linkedMapOf(
        "summaryMarkdown" to text(20_000, 1),
        "strengths" to array(text(500, 1), 10),
        "improvements" to array(text(500, 1), 10),
        "nextSteps" to array(text(500, 1), 10),
        "explorations" to array(explorationSchema, VoiceTutorExplorationLimits.MAX_EXPLORATIONS),
    ))
}
