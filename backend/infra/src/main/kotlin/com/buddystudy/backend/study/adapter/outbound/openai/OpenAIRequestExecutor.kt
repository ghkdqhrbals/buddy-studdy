package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
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
    private val properties: BuddyStudyProperties,
) {
    private val mapper = JsonMapperProvider.mapper
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
            Split this study topic into a practical learning concept tree.
            Concepts may contain recursive children with no fixed maximum depth.
            Put 3 to 5 question angles on leaf concepts.
            Topic: ${topic.ifBlank { "general study" }}
            Level: ${level.coerceIn(1, 10)}/10
            Extra tutor prompt: ${customPrompt.ifBlank { "None" }}

            Return JSON only:
            {
              "concepts": [
                {
                  "key": "stable_snake_case",
                  "name": "Human readable concept",
                  "children": [],
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
        return parseQuestionCoverageConcepts(text)
    }

    fun suggestStudyTopics(
        apiKey: String,
        model: String,
        rootTopic: String,
        parentTopic: String,
        existingTopics: Collection<String>,
        language: String,
        count: Int,
    ): List<String> {
        val outputLanguage = if (language.lowercase().startsWith("en")) "English" else "Korean"
        val prompt = """
            Recommend distinct child study topics for a learning tree.
            Root topic: $rootTopic
            Parent topic: $parentTopic
            Existing topics that must not be repeated: ${existingTopics.joinToString(", ")}
            Output language: $outputLanguage
            Return exactly ${count.coerceIn(1, 8)} concise, concrete topics.
            Do not repeat, rename, pluralize, or closely paraphrase an existing topic.
            Return JSON only:
            {"topics":["topic 1","topic 2"]}
        """.trimIndent()
        val response = chatModel(apiKey, model, json = true).call(
            Prompt(UserMessage(prompt), options(apiKey, model, json = true)),
        )
        val text = response.result?.output?.text ?: "{}"
        val parsed: Map<String, Any?> = mapper.readValue(text.ifBlank { "{}" })
        return (parsed["topics"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
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

internal fun parseQuestionCoverageConcepts(text: String): List<OpenAIPort.QuestionCoverageConcept> {
    val parsed: Map<String, Any?> = JsonMapperProvider.mapper.readValue(text.ifBlank { "{}" })
    val concepts = parsed["concepts"] as? List<*> ?: return emptyList()
    return concepts.mapNotNull(::parseQuestionCoverageConcept)
}

private fun parseQuestionCoverageConcept(raw: Any?): OpenAIPort.QuestionCoverageConcept? {
    val concept = raw as? Map<*, *> ?: return null
    val key = concept["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val name = concept["name"]?.toString()?.takeIf { it.isNotBlank() } ?: key
    val angles = (concept["angles"] as? List<*>)
        ?.mapNotNull { rawAngle ->
            val angle = rawAngle as? Map<*, *> ?: return@mapNotNull null
            val angleKey = angle["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val angleName = angle["name"]?.toString()?.takeIf { it.isNotBlank() } ?: angleKey
            OpenAIPort.QuestionCoverageAngle(angleKey, angleName)
        }
        .orEmpty()
    val children = (concept["children"] as? List<*>)
        ?.mapNotNull(::parseQuestionCoverageConcept)
        .orEmpty()
    return OpenAIPort.QuestionCoverageConcept(key, name, angles, children)
}
