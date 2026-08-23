package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.GradingPromptPreviewCommand
import com.buddystudy.backend.study.application.model.GradingPromptPreviewResponse
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.AiGradingRubric
import com.buddystudy.backend.study.application.port.outbound.AiGradingStage
import com.buddystudy.backend.study.application.port.outbound.GradingPromptPreviewPort
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.adapter.outbound.translation.QuestionTranslationProvider
import com.buddystudy.backend.study.adapter.outbound.translation.QuestionTranslationRequest
import org.springframework.stereotype.Component

@Component
class OpenAIClient(
    private val executor: OpenAIRequestExecutor,
    private val keys: UserContentOpenAIKeyProvider,
    private val properties: BuddyStudyProperties,
) : OpenAIPort, QuestionTranslationProvider, GradingPromptPreviewPort {
    override val providerId: String = "openai"

    override suspend fun validate(apiKey: String) =
        executor.validate(keys.requireApiKey())

    override suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion =
        executor.generateQuestion(keys.requireApiKey(), model, prompt)

    override suspend fun embedText(apiKey: String, text: String): List<Float> =
        executor.embedText(keys.requireApiKey(), text)

    override suspend fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> =
        executor.generateQuestionCoverageBlueprint(keys.requireApiKey(), model, topic, level, customPrompt)

    override suspend fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
        executor.grade(keys.requireApiKey(), model, question, answer, topic, level, language)

    override suspend fun gradeWithRubric(
        apiKey: String,
        model: String,
        question: String,
        answer: String,
        topic: String,
        level: Int,
        language: String,
        rubric: AiGradingRubric?,
        onProgress: suspend (AiGradingStage) -> Unit,
    ): GradedAnswer = executor.grade(
        keys.requireApiKey(),
        model,
        question,
        answer,
        topic,
        level,
        language,
        rubric,
        onProgress,
    )

    override suspend fun translate(request: QuestionTranslationRequest): TranslatedQuestionContent =
        executor.translateQuestion(
            apiKey = keys.requireApiKey(),
            model = properties.openai.model,
            topic = request.topic,
            question = request.question,
            hint = request.hint,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
        )

    override suspend fun compare(command: GradingPromptPreviewCommand): GradingPromptPreviewResponse =
        executor.compareGradingResponses(
            apiKey = keys.requireApiKey(),
            model = properties.openai.model,
            command = command,
        )
}
