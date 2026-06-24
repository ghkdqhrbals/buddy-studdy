package com.buddystuddy.backend.study.adapter.outbound.openai

import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class OpenAIClient : OpenAIPort {
    private val rest = RestClient.builder().baseUrl("https://api.openai.com").build()
    private val mapper = jacksonObjectMapper()

    override fun validate(apiKey: String) {
        rest.get().uri("/v1/models").header("Authorization", "Bearer $apiKey").retrieve().toBodilessEntity()
    }

    override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
        val body = mapOf(
            "model" to model,
            "instructions" to prompt.systemPrompt,
            "input" to prompt.userPrompt,
            "text" to mapOf("format" to mapOf("type" to "json_object")),
        )
        val response = rest.post()
            .uri("/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .body(body)
            .retrieve()
            .body(String::class.java) ?: "{}"
        val text = extractOutputText(response)
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
        val body = mapOf("model" to model, "input" to prompt, "text" to mapOf("format" to mapOf("type" to "json_object")))
        val response = rest.post()
            .uri("/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .body(body)
            .retrieve()
            .body(String::class.java) ?: "{}"
        val text = extractOutputText(response)
        val parsed: Map<String, Any?> = mapper.readValue(text.ifBlank { "{}" })
        val score = (parsed["score"] as? Number)?.toInt() ?: parsed["score"]?.toString()?.toIntOrNull() ?: 0
        return GradedAnswer(
            score = score.coerceIn(0, 100),
            isCorrect = (parsed["isCorrect"] as? Boolean) ?: (score >= 70),
            feedback = parsed["feedback"]?.toString() ?: "",
            explanation = parsed["explanation"]?.toString() ?: "",
        )
    }

    private fun extractOutputText(raw: String): String {
        val root: Map<String, Any?> = mapper.readValue(raw)
        val output = root["output"] as? List<*> ?: return root["output_text"]?.toString() ?: ""
        return output.asSequence()
            .flatMap { ((it as? Map<*, *>)?.get("content") as? List<*>)?.asSequence() ?: emptySequence() }
            .mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }
            .firstOrNull()
            ?: root["output_text"]?.toString()
            ?: ""
    }
}
