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
    fun validate() {
        validate(systemApiKey())
    }

    fun generateQuestion(model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        generateQuestion(systemApiKey(), model, prompt)

    fun embedText(text: String): List<Float> =
        embedText(systemApiKey(), text)

    fun generateQuestionCoverageBlueprint(
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

    fun grade(model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        grade(
            apiKey = systemApiKey(),
            model = model,
            question = question,
            answer = answer,
            topic = topic,
            level = level,
            language = language,
        )

    override fun validate(apiKey: String) {
        executor.validate(systemApiKey())
    }

    override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        executor.generateQuestion(systemApiKey(), model, prompt)

    override fun embedText(apiKey: String, text: String): List<Float> =
        executor.embedText(systemApiKey(), text)

    override fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        executor.generateQuestionCoverageBlueprint(systemApiKey(), model, topic, level, customPrompt)

    override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        executor.grade(systemApiKey(), model, question, answer, topic, level, language)

    private fun systemApiKey(): String =
        properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.OPENAI_API_KEY_MISSING,
                "System OpenAI API key is not configured.",
            )
}
