package com.buddystuddy.backend.study.application.port.outbound

import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt

data class GeneratedQuestion(val question: String, val hint: String?)
data class GradedAnswer(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)

interface OpenAIPort {
    fun validate(apiKey: String)
    fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion
    fun embedText(apiKey: String, text: String): List<Float>
    fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer
}
