package com.buddystuddy.backend.study.adapter.outbound.openai

import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import org.springframework.stereotype.Component

@Component
class OpenAIClient(
    private val executor: OpenAIRequestExecutor,
) : OpenAIPort {
    override fun validate(apiKey: String) =
        executor.validate(apiKey)

    override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        executor.generateQuestion(apiKey, model, prompt)

    override fun embedText(apiKey: String, text: String): List<Float> =
        executor.embedText(apiKey, text)

    override fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        executor.generateQuestionCoverageBlueprint(apiKey, model, topic, level, customPrompt)

    override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        executor.grade(apiKey, model, question, answer, topic, level, language)
}
