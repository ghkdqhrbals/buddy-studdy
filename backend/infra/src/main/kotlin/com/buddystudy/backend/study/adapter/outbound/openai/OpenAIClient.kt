package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.AiGradingRubric
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import org.springframework.stereotype.Component

@Component
class OpenAIClient(
    private val executor: OpenAIRequestExecutor,
) : OpenAIPort {
    override suspend fun validate(apiKey: String) =
        executor.validate(apiKey)

    override suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        executor.generateQuestion(apiKey, model, prompt)

    override suspend fun embedText(apiKey: String, text: String): List<Float> =
        executor.embedText(apiKey, text)

    override suspend fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        executor.generateQuestionCoverageBlueprint(apiKey, model, topic, level, customPrompt)

    override suspend fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        executor.grade(apiKey, model, question, answer, topic, level, language)

    override suspend fun gradeWithRubric(
        apiKey: String,
        model: String,
        question: String,
        answer: String,
        topic: String,
        level: Int,
        language: String,
        rubric: AiGradingRubric?,
    ): GradedAnswer = executor.grade(apiKey, model, question, answer, topic, level, language, rubric)
}
