package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import org.springframework.stereotype.Component
import kotlin.math.sqrt

data class QuestionSimilarityMatch(
    val questionId: Long,
    val question: String,
    val similarity: Double,
)

data class GeneratedQuestionWithEmbedding(
    val generated: GeneratedQuestion,
    val embedding: List<Float>,
) {
    val question: String get() = generated.question
    val hint: String? get() = generated.hint
}

@Component
class QuestionSimilarityPolicy {
    fun findSimilar(
        embedding: List<Float>,
        candidates: List<QuestionEmbeddingCandidate>,
        threshold: Double = DEFAULT_THRESHOLD,
    ): QuestionSimilarityMatch? =
        candidates
            .asSequence()
            .mapNotNull { candidate ->
                val similarity = cosineSimilarity(embedding, candidate.embedding) ?: return@mapNotNull null
                QuestionSimilarityMatch(candidate.questionId, candidate.question, similarity)
            }
            .filter { it.similarity >= threshold }
            .maxByOrNull { it.similarity }

    companion object {
        const val DEFAULT_THRESHOLD = 0.86
    }
}

fun cosineSimilarity(left: List<Float>, right: List<Float>): Double? {
    if (left.isEmpty() || right.isEmpty() || left.size != right.size) return null
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        dot += l * r
        leftNorm += l * l
        rightNorm += r * r
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) return null
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}
