package com.buddystudy.backend.study

import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.backend.study.application.prompt.QuestionDiversityGuide
import com.buddystudy.study.domain.entity.QuestionFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MultipleChoiceGenerationTest {
    private fun valid() = GeneratedQuestion(
        question = "Which value is even?", hint = null,
        options = listOf(QuestionChoiceOption("A", "2"), QuestionChoiceOption("B", "3")),
        correctOptionId = "A", explanation = "2 is divisible by 2.",
    )

    @Test
    fun `generation accepts exactly one correct answer among two to four choices`() {
        valid().validateMultipleChoice()
        valid().copy(options = valid().options + listOf(QuestionChoiceOption("C", "5"), QuestionChoiceOption("D", "7"))).validateMultipleChoice()
    }

    @Test
    fun `generation rejects invalid answer keys duplicate blank or excess options`() {
        val invalid = listOf(
            valid().copy(options = valid().options.take(1)),
            valid().copy(options = (1..5).map { QuestionChoiceOption(it.toString(), "choice $it") }),
            valid().copy(correctOptionId = "Z"),
            valid().copy(options = listOf(QuestionChoiceOption("A", "2"), QuestionChoiceOption("A", "3"))),
            valid().copy(options = listOf(QuestionChoiceOption("A", "2"), QuestionChoiceOption("B", " 2 "))),
            valid().copy(options = listOf(QuestionChoiceOption("A", ""), QuestionChoiceOption("B", "3"))),
            valid().copy(explanation = ""),
        )
        invalid.forEach { question -> assertThatThrownBy { question.validateMultipleChoice() }.isInstanceOf(IllegalArgumentException::class.java) }
    }

    @Test
    fun `objective prompt requests independent options and no written answer`() {
        val prompt = QuestionPromptProvider().buildQuestionGenerationPrompt(
            "Math", 2, "en", "", emptyList(),
            QuestionDiversityGuide("definition", "scenario", "cause", "1"),
            questionFormat = QuestionFormat.MULTIPLE_CHOICE,
        )
        assertThat(prompt.questionFormat).isEqualTo(QuestionFormat.MULTIPLE_CHOICE)
        assertThat(prompt.userPrompt).contains("two to four", "Exactly one option is correct", "correctOptionId", "Keep options out of the question text")
        assertThat(prompt.userPrompt).doesNotContain("immutable grading rubric")
    }
}
