package com.buddystudy.backend.localization

import com.buddystudy.backend.localization.application.policy.QuestionChoiceLocalizationPolicy
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class QuestionChoiceLocalizationPolicyTest {
    @Test
    fun `duplicate translated labels are rejected before storing an ambiguous quiz`() {
        val question = QuestionEntity(
            questionFormat = QuestionFormat.MULTIPLE_CHOICE,
            choiceOptionsJson = QuestionChoiceCodec().encode(listOf(
                QuestionChoiceOption("A", "First source choice"), QuestionChoiceOption("B", "Second source choice"),
            )),
        )
        assertThatThrownBy {
            QuestionChoiceLocalizationPolicy.translatedJson(question, mapOf("choice_A" to "Index Scan", "choice_B" to " index   scan "))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("distinct")
        assertThat(QuestionChoiceCodec().decode(question.choiceOptionsJson).map { it.text })
            .containsExactly("First source choice", "Second source choice")
    }

    @Test
    fun `generation validation rejects Unicode equivalent labels and accepts distinct text`() {
        assertThatThrownBy {
            QuestionChoiceLocalizationPolicy.validateTranslatedOptions(listOf(QuestionChoiceOption("A", "Ａ"), QuestionChoiceOption("B", "A")))
        }.isInstanceOf(IllegalArgumentException::class.java)
        QuestionChoiceLocalizationPolicy.validateTranslatedOptions(listOf(QuestionChoiceOption("A", "Index"), QuestionChoiceOption("B", "Full scan")))
    }
}
