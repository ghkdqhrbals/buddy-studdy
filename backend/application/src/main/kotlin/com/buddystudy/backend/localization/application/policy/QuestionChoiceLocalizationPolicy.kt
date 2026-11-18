package com.buddystudy.backend.localization.application.policy

import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import java.text.Normalizer
import java.util.Locale
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat

/** Only option labels are translated; identities and ordering belong to the source question. */
object QuestionChoiceLocalizationPolicy {
    private val codec = QuestionChoiceCodec()

    fun fields(question: QuestionEntity): Map<String, String?> =
        if (question.questionFormat == QuestionFormat.MULTIPLE_CHOICE) {
            codec.decode(question.choiceOptionsJson).associate { option -> "choice_${option.id}" to option.text }
        } else {
            emptyMap()
        }

    fun translatedJson(question: QuestionEntity, fields: Map<String, String?>): String? {
        if (question.questionFormat != QuestionFormat.MULTIPLE_CHOICE) return null
        return codec.encode(codec.decode(question.choiceOptionsJson).map { option ->
            option.copy(text = requireNotNull(fields["choice_${option.id}"]?.takeIf(String::isNotBlank)) {
                "Missing translated choice ${option.id}."
            })
        }.also(::validateTranslatedOptions))
    }

    fun validateTranslatedOptions(options: List<QuestionChoiceOption>) {
        require(options.all { it.text.isNotBlank() }) { "Translated choices must not be blank." }
        val labels = options.map { option ->
            Normalizer.normalize(option.text, Normalizer.Form.NFKC)
                .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        }
        require(labels.distinct().size == labels.size) { "Translated choices must remain distinct." }
    }
}
