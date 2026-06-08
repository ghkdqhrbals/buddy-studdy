package com.buddystuddy.backend.openai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

data class GeneratedQuestion(val question: String, val hint: String?)
data class GradedAnswer(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)

@Service
class OpenAIClient {
    private val rest = RestClient.builder().baseUrl("https://api.openai.com").build()
    private val mapper = jacksonObjectMapper()

    fun validate(apiKey: String) {
        rest.get().uri("/v1/models").header("Authorization", "Bearer $apiKey").retrieve().toBodilessEntity()
    }

    fun generateQuestion(apiKey: String, model: String, topic: String, level: Int, language: String, customPrompt: String, recent: List<String>): GeneratedQuestion {
        val prompt = """
            Create one short study question.
            Topic: $topic
            Level: $level/10
            Language: ${if (language == "en") "English" else "Korean"}
            Avoid repeating these recent questions: ${recent.joinToString(" | ")}
            Extra tutor prompt: $customPrompt
            Return JSON only with keys question and expectedAnswerHint.
        """.trimIndent()
        val body = mapOf(
            "model" to model,
            "input" to prompt,
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
            question = parsed["question"]?.toString()?.takeIf { it.isNotBlank() } ?: "Explain one key idea about $topic.",
            hint = parsed["expectedAnswerHint"]?.toString(),
        )
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
