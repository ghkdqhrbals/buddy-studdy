package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt

data class GeneratedQuestion(val question: String, val hint: String?)
data class GradedAnswer(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)

interface OpenAIPort {
    fun validate(apiKey: String)
    fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion
    fun embedText(apiKey: String, text: String): List<Float>
    fun generateQuestionCoverageBlueprint(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        customPrompt: String,
    ): List<QuestionCoverageConcept>
    fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer

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
