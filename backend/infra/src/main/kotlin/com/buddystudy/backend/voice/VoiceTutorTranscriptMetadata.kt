package com.buddystudy.backend.voice

import com.fasterxml.jackson.databind.JsonNode

/** Internal relay annotation. Unknown (-1) preserves source but cannot establish a lesson level. */
internal object VoiceTutorTranscriptMetadata {
    const val INCOMPLETE_EVENT = "buddystudy-internal-voice-transcript-incomplete"
    const val ACCEPTED_AT_EPOCH_MILLIS = "_buddystudy_accepted_at_epoch_millis"
    const val LESSON_REVISION = "_buddystudy_lesson_revision"
    const val STUDY_QUESTION_PROVIDER_ITEM_ID = "_buddystudy_study_question_provider_item_id"
    const val STUDY_ANSWER_PROVIDER_ITEM_ID = "_buddystudy_study_answer_provider_item_id"
    const val STUDY_ANSWER_PROVIDER_ITEM_IDS = "_buddystudy_study_answer_provider_item_ids"
    const val ASKED_STUDY_QUESTION = "_buddystudy_asked_study_question"
    const val IS_STUDY_QUESTION = "_buddystudy_is_study_question"
    const val POST_CALL_EVIDENCE = "_buddystudy_post_call_evidence"
    const val CONVERSATION_SEQUENCE = "_buddystudy_conversation_sequence"

    fun postCallEvidence(node: JsonNode): Boolean =
        node.path(POST_CALL_EVIDENCE).takeIf(JsonNode::isBoolean)?.booleanValue() == true

    fun conversationSequence(node: JsonNode): Long? = node.path(CONVERSATION_SEQUENCE)
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue()?.takeIf { it > 0 }

    fun lessonRevision(node: JsonNode): Long = node.path(LESSON_REVISION)
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue()?.takeIf { it >= -1 } ?: -1

    fun acceptedAt(node: JsonNode): java.time.Instant? = node.path(ACCEPTED_AT_EPOCH_MILLIS)
        .takeIf { it.isIntegralNumber && it.canConvertToLong() }
        ?.longValue()
        ?.let { runCatching { java.time.Instant.ofEpochMilli(it) }.getOrNull() }

    fun studyQuestionProviderItemId(node: JsonNode): String? =
        node.path(STUDY_QUESTION_PROVIDER_ITEM_ID)
            .takeIf(JsonNode::isTextual)
            ?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= 191 }

    fun studyAnswerProviderItemId(node: JsonNode): String? =
        node.path(STUDY_ANSWER_PROVIDER_ITEM_ID)
            .takeIf(JsonNode::isTextual)
            ?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= 191 }

    /**
     * Exact ordered server-owned USER items that form one completed answer. An
     * absent or malformed value has no attestation authority and fails closed.
     */
    fun studyAnswerProviderItemIds(node: JsonNode): List<String> {
        val values = node.path(STUDY_ANSWER_PROVIDER_ITEM_IDS)
        if (!values.isArray || values.size() !in 1..MAX_STUDY_ANSWER_PARTS) return emptyList()
        val ids = values.map { value ->
            value.takeIf(JsonNode::isTextual)?.textValue()
                ?.takeIf { it.isNotBlank() && it.length <= 191 }
                ?: return emptyList()
        }
        return ids.takeIf { it.distinct().size == it.size }.orEmpty()
    }

    fun askedStudyQuestion(node: JsonNode): Boolean =
        node.path(ASKED_STUDY_QUESTION).takeIf(JsonNode::isBoolean)?.booleanValue() == true

    fun isStudyQuestion(node: JsonNode): Boolean =
        node.path(IS_STUDY_QUESTION).takeIf(JsonNode::isBoolean)?.booleanValue() == true

    const val MAX_STUDY_ANSWER_PARTS = 32
}
