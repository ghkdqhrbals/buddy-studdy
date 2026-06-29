package com.buddystuddy.backend.study.adapter.outbound.openai

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class SystemOpenAIClient(
    private val delegate: OpenAIClient,
    private val properties: BuddyStuddyProperties,
) {
    fun validate() {
        delegate.validate(systemApiKey())
    }

    fun generateQuestion(model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        delegate.generateQuestion(systemApiKey(), model, prompt)

    fun embedText(text: String): List<Float> =
        delegate.embedText(systemApiKey(), text)

    fun generateQuestionCoverageBlueprint(
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        delegate.generateQuestionCoverageBlueprint(
            apiKey = systemApiKey(),
            model = model,
            topic = topic,
            level = level,
            customPrompt = customPrompt,
        )

    fun grade(model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        delegate.grade(
            apiKey = systemApiKey(),
            model = model,
            question = question,
            answer = answer,
            topic = topic,
            level = level,
            language = language,
        )

    private fun systemApiKey(): String =
        properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.OPENAI_API_KEY_MISSING,
                "System OpenAI API key is not configured.",
            )
}
