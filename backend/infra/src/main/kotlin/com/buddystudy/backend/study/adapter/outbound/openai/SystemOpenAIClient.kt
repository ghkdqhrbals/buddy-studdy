package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class SystemOpenAIClient(
    private val executor: OpenAIRequestExecutor,
    private val properties: BuddyStudyProperties,
) : OpenAIPort {
    suspend fun validate() {
        validate(systemApiKey())
    }

    suspend fun generateQuestion(model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        generateQuestion(systemApiKey(), model, prompt)

    suspend fun embedText(text: String): List<Float> =
        embedText(systemApiKey(), text)

    suspend fun generateQuestionCoverageBlueprint(
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        generateQuestionCoverageBlueprint(
            apiKey = systemApiKey(),
            model = model,
            topic = topic,
            level = level,
            customPrompt = customPrompt,
        )

    suspend fun grade(model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        grade(
            apiKey = systemApiKey(),
            model = model,
            question = question,
            answer = answer,
            topic = topic,
            level = level,
            language = language,
        )

    override suspend fun validate(apiKey: String) {
        executor.validate(systemApiKey())
    }

    override suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        executor.generateQuestion(systemApiKey(), model, prompt)

    override suspend fun embedText(apiKey: String, text: String): List<Float> =
        executor.embedText(systemApiKey(), text)

    override suspend fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        executor.generateQuestionCoverageBlueprint(systemApiKey(), model, topic, level, customPrompt)

    override suspend fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        executor.grade(systemApiKey(), model, question, answer, topic, level, language)

    private fun systemApiKey(): String =
        properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.OPENAI_API_KEY_MISSING,
                "System OpenAI API key is not configured.",
            )
}
