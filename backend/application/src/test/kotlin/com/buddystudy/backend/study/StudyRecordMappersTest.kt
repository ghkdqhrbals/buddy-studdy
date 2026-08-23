package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.study.application.port.outbound.AiCriterionAssessment
import com.buddystudy.backend.study.application.port.outbound.AiGradingAssessment
import com.buddystudy.study.domain.StudyRecordProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StudyRecordMappersTest {
    @Test
    fun `graded record exposes final AI verdict and auditable criterion evidence`() {
        val assessment = AiGradingAssessment(
            criteria = listOf(
                AiCriterionAssessment(
                    criterionId = "trade_off",
                    satisfied = true,
                    evidence = listOf("The answer compares durability and latency."),
                )
            ),
            contradictions = listOf("One claim reverses the persistence guarantees."),
            judgeReason = "Core trade-off is correct with one contradiction.",
        )
        val projection = StudyRecordProjection(
            id = "1",
            question = "Compare AOF and RDB.",
            expectedAnswerHint = null,
            createdAt = Instant.parse("2026-07-27T00:00:00Z"),
            answer = "Answer",
            score = 82,
            correct = false,
            feedback = "Mostly correct.",
            explanation = "One contradiction remains.",
            topic = "Redis",
            difficulty = 7,
            answeredAt = Instant.parse("2026-07-27T00:01:00Z"),
            isPublic = false,
            likeCount = 0,
            commentCount = 0,
            viewCount = 0,
            gradingVerdict = "PARTIALLY_CORRECT",
            gradingConfidence = 0.91,
            gradingPolicyVersion = "ai-judge-v1",
            gradingModel = "test-model",
            gradingAssessmentJson = JsonMapperProvider.mapper.writeValueAsString(assessment),
        )

        val result = projection.toRecordResponse().gradingResult

        assertThat(result?.verdict).isEqualTo("PARTIALLY_CORRECT")
        assertThat(result?.isCorrect).isFalse()
        assertThat(result?.confidence).isEqualTo(0.91)
        assertThat(result?.criteria?.single()?.criterionId).isEqualTo("trade_off")
        assertThat(result?.contradictions).containsExactly("One claim reverses the persistence guarantees.")
        assertThat(result?.auditReason).isEqualTo("Core trade-off is correct with one contradiction.")
        assertThat(result?.policyVersion).isEqualTo("ai-judge-v1")
    }
}
