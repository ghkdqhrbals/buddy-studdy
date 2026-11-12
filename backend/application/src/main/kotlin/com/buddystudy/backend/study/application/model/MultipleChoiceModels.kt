package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component

data class QuestionChoiceOption(val id: String, val text: String)

data class MultipleChoiceOptionResponse(
    val id: String,
    val text: String,
    val count: Int? = null,
    val percentage: Double? = null,
)

data class MultipleChoiceResponse(
    val options: List<MultipleChoiceOptionResponse>,
    val selectedOptionId: String? = null,
    val totalParticipants: Int? = null,
    val correctOptionId: String? = null,
    @get:JsonProperty("isCorrect")
    val isCorrect: Boolean? = null,
    val explanation: String? = null,
)

/** The stored answer key never belongs to the options JSON or its translations. */
@Component
class QuestionChoiceCodec {
    fun decode(json: String?): List<QuestionChoiceOption> =
        if (json.isNullOrBlank()) emptyList() else JsonMapperProvider.mapper.readValue(json)

    fun encode(options: List<QuestionChoiceOption>): String = JsonMapperProvider.mapper.writeValueAsString(options)
}

fun multipleChoiceResponse(
    options: List<QuestionChoiceOption>,
    selectedOptionId: String?,
    correctOptionId: String?,
    explanation: String?,
    counts: Map<String, Int> = emptyMap(),
): MultipleChoiceResponse {
    val answered = selectedOptionId != null
    val total = counts.values.sum()
    return MultipleChoiceResponse(
        options = options.map { option ->
            val count = counts[option.id] ?: 0
            MultipleChoiceOptionResponse(
                id = option.id,
                text = option.text,
                count = count.takeIf { answered },
                percentage = if (!answered) null else if (total == 0) 0.0 else count * 100.0 / total,
            )
        },
        selectedOptionId = selectedOptionId,
        totalParticipants = total.takeIf { answered },
        correctOptionId = correctOptionId.takeIf { answered },
        isCorrect = if (answered) selectedOptionId == correctOptionId else null,
        explanation = explanation.takeIf { answered },
    )
}
