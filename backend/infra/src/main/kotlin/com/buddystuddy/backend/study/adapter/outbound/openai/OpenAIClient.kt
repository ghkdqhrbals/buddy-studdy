package com.buddystuddy.backend.study.adapter.outbound.openai

import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Component

@Component
class OpenAIClient : OpenAIPort {
    private val mapper = jacksonObjectMapper()
    private val jsonResponseFormat = OpenAiChatModel.ResponseFormat.builder()
        .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
        .build()

    override fun validate(apiKey: String) {
        val model = chatModel(apiKey, OpenAiChatOptions.DEFAULT_CHAT_MODEL, json = false)
        model.call(Prompt("Reply with ok.", options(apiKey, OpenAiChatOptions.DEFAULT_CHAT_MODEL, json = false, maxCompletionTokens = 4)))
    }

    override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
        val response = chatModel(apiKey, model, json = true).call(
            Prompt(
                listOf(
                    SystemMessage(prompt.systemPrompt),
                    UserMessage(prompt.userPrompt),
                ),
                options(apiKey, model, json = true),
            )
        )
        val text = response.result?.output?.text ?: "{}"
        val parsed: Map<String, Any?> = mapper.readValue(text.ifBlank { "{}" })
        return GeneratedQuestion(
            question = parsed["question"]?.toString()?.takeIf { it.isNotBlank() } ?: "Explain one key idea about ${prompt.fallbackTopic}.",
            hint = parsed["expectedAnswerHint"]?.toString(),
        )
    }

    override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer {
        val prompt = """
            Grade this answer consistently from 0 to 100.
            Topic: $topic
            Level: $level/10
            Question: $question
            Answer: $answer
            Language: ${if (language == "en") "English" else "Korean"}
            Return JSON only with score, isCorrect, feedback, explanation.
        """.trimIndent()
        val response = chatModel(apiKey, model, json = true).call(
            Prompt(UserMessage(prompt), options(apiKey, model, json = true))
        )
        val text = response.result?.output?.text ?: "{}"
        val parsed: Map<String, Any?> = mapper.readValue(text.ifBlank { "{}" })
        val score = (parsed["score"] as? Number)?.toInt() ?: parsed["score"]?.toString()?.toIntOrNull() ?: 0
        return GradedAnswer(
            score = score.coerceIn(0, 100),
            isCorrect = (parsed["isCorrect"] as? Boolean) ?: (score >= 70),
            feedback = parsed["feedback"]?.toString() ?: "",
            explanation = parsed["explanation"]?.toString() ?: "",
        )
    }

    private fun chatModel(apiKey: String, model: String, json: Boolean): OpenAiChatModel =
        OpenAiChatModel.builder()
            .options(options(apiKey, model, json))
            .build()

    private fun options(
        apiKey: String,
        model: String,
        json: Boolean,
        maxCompletionTokens: Int? = null,
    ): OpenAiChatOptions {
        val builder = OpenAiChatOptions.builder()
            .apiKey(apiKey)
            .model(model)
        if (json) {
            builder.responseFormat(jsonResponseFormat)
        }
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens)
        }
        return builder.build()
    }
}
