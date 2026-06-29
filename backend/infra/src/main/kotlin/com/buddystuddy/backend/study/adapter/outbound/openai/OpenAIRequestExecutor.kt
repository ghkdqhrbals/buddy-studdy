package com.buddystuddy.backend.study.adapter.outbound.openai

import com.buddystuddy.backend.config.BuddyStuddyProperties
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
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.stereotype.Component

@Component
class OpenAIRequestExecutor(
    private val properties: BuddyStuddyProperties,
) {
    private val mapper = jacksonObjectMapper()
    private val jsonResponseFormat = OpenAiChatModel.ResponseFormat.builder()
        .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
        .build()

    fun validate(apiKey: String) {
        val model = chatModel(apiKey, OpenAiChatOptions.DEFAULT_CHAT_MODEL, json = false)
        model.call(Prompt("Reply with ok.", options(apiKey, OpenAiChatOptions.DEFAULT_CHAT_MODEL, json = false, maxCompletionTokens = 4)))
    }

    fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
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

    fun embedText(apiKey: String, text: String): List<Float> =
        OpenAiEmbeddingModel.builder()
            .options(
                OpenAiEmbeddingOptions.builder()
                    .apiKey(apiKey)
                    .model(properties.openai.embeddingModel)
                    .build()
            )
            .build()
            .embed(text)
            .toList()

    fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<OpenAIPort.QuestionCoverageConcept> {
        val prompt = """
            Split this study topic into 8 to 12 practical learning concepts.
            For each concept, provide 3 to 5 question angles.
            Topic: ${topic.ifBlank { "general study" }}
            Level: ${level.coerceIn(1, 10)}/10
            Extra tutor prompt: ${customPrompt.ifBlank { "None" }}

            Return JSON only:
            {
              "concepts": [
                {
                  "key": "stable_snake_case",
                  "name": "Human readable concept",
                  "angles": [
                    {"key": "stable_snake_case", "name": "Human readable angle"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val response = chatModel(apiKey, model, json = true).call(
            Prompt(UserMessage(prompt), options(apiKey, model, json = true))
        )
        val text = response.result?.output?.text ?: "{}"
        val parsed: Map<String, Any?> = mapper.readValue(text.ifBlank { "{}" })
        val concepts = parsed["concepts"] as? List<*> ?: return emptyList()
        return concepts.mapNotNull { raw ->
            val concept = raw as? Map<*, *> ?: return@mapNotNull null
            val key = concept["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = concept["name"]?.toString()?.takeIf { it.isNotBlank() } ?: key
            val angles = (concept["angles"] as? List<*>)
                ?.mapNotNull { rawAngle ->
                    val angle = rawAngle as? Map<*, *> ?: return@mapNotNull null
                    val angleKey = angle["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val angleName = angle["name"]?.toString()?.takeIf { it.isNotBlank() } ?: angleKey
                    OpenAIPort.QuestionCoverageAngle(angleKey, angleName)
                }
                .orEmpty()
            OpenAIPort.QuestionCoverageConcept(key, name, angles)
        }
    }

    fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer {
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
