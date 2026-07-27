package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt

data class AiGradingCriterion(
    val id: String,
    val description: String,
    val weight: Int,
    val essential: Boolean = false,
    val expectedEvidence: List<String> = emptyList(),
    val acceptedAlternatives: List<String> = emptyList(),
    val misconceptions: List<String> = emptyList(),
)

data class AiGradingRubric(
    val version: String = "question-rubric-v1",
    val assessmentType: String,
    val criteria: List<AiGradingCriterion>,
    val acceptedAlternatives: List<String> = emptyList(),
    val fatalMisconceptions: List<String> = emptyList(),
)

data class AiCriterionAssessment(
    val criterionId: String,
    val satisfied: Boolean,
    val evidence: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val reason: String = "",
)

data class AiGradingAssessment(
    val criteria: List<AiCriterionAssessment> = emptyList(),
    val contradictions: List<String> = emptyList(),
    val misconceptions: List<String> = emptyList(),
    val unsupportedClaims: List<String> = emptyList(),
    val judgeReason: String = "",
)

data class GeneratedQuestion(
    val question: String,
    val hint: String?,
    val rubric: AiGradingRubric? = null,
)

data class GradedAnswer(
    val score: Int,
    val isCorrect: Boolean,
    val feedback: String,
    val explanation: String,
    val verdict: String = if (isCorrect) "CORRECT" else "INCORRECT",
    val confidence: Double = 1.0,
    val rubric: AiGradingRubric? = null,
    val assessment: AiGradingAssessment? = null,
    val policyVersion: String = "ai-judge-v1",
    val model: String? = null,
)

enum class AiGradingStage {
    ANALYZING_EVIDENCE,
    CRITIQUING,
    JUDGING,
    ADJUDICATING,
}

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

    suspend fun gradeWithRubric(
        apiKey: String,
        model: String,
        question: String,
        answer: String,
        topic: String,
        level: Int,
        language: String,
        rubric: AiGradingRubric?,
        onProgress: suspend (AiGradingStage) -> Unit = {},
    ): GradedAnswer = grade(apiKey, model, question, answer, topic, level, language)

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
