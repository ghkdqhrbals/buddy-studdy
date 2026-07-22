package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt

data class GeneratedQuestion(val question: String, val hint: String?)
data class GradedAnswer(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)

interface OpenAIPort {
    suspend fun validate(apiKey: String)
    suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion
    suspend fun embedText(apiKey: String, text: String): List<Float>
    suspend fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<QuestionCoverageConcept>
    suspend fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer

    data class QuestionCoverageConcept(
        val key: String,
        val name: String,
        val angles: List<QuestionCoverageAngle>,
        val children: List<QuestionCoverageConcept> = emptyList(),
    )

    data class QuestionCoverageAngle(
        val key: String,
        val name: String,
    )
}
