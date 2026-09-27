package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.study.application.port.outbound.AiCriterionAssessment
import com.buddystudy.backend.study.application.port.outbound.AiGradingAssessment
import com.buddystudy.study.domain.StudyRecordProjection
import com.buddystudy.study.domain.StudyRecord
import com.buddystudy.study.domain.StudyRecordState
import com.buddystudy.study.domain.entity.StudyRecordType
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.backend.study.application.service.toStudyRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StudyRecordMappersTest {
    @Test
    fun `voice spoken score cannot masquerade as an ordinary grading result`() {
        val state = StudyRecordState(id = 900, question = "캐시가 뭔가요?", hint = null, createdAt = Instant.EPOCH,
            answer = "임시 저장입니다.", score = 82, correct = null, feedback = "잘 설명했어요.", explanation = null,
            topic = "Redis", difficultyLevel = 3, answeredAt = Instant.EPOCH, publicQuestion = false,
            questionStatus = "completed", recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = 7, source = "voice_tutor")
        val response = StudyRecord.of(state).toProjection().toRecordResponse()
        assertThat(response.id).isEqualTo("900")
        assertThat(response.gradingResult).isNull()
        assertThat(response.question.question).isEqualTo(state.question)
        val json = JsonMapperProvider.mapper.readTree(JsonMapperProvider.mapper.writeValueAsString(response))
        assertThat(json["recordType"].asText()).isEqualTo("VOICE_TUTOR")
        assertThat(json["questionStatus"].asText()).isEqualTo("COMPLETED")
        assertThat(json["source"].asText()).isEqualTo("voice_tutor")
        assertThat(response.followUpDepth).isZero()
        assertThat(response.parentRecordId).isNull()
    }

    @Test
    fun `canonical entity projection preserves voice origin and follow-up lineage`() {
        val voice = QuestionEntity(id = 90, recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = 7,
            source = QuestionSource.VOICE_TUTOR, status = QuestionStatus.COMPLETED)
        val voiceResponse = voice.toStudyRecord().toProjection().toRecordResponse()
        assertThat(voiceResponse.source).isEqualTo("voice_tutor")
        assertThat(voiceResponse.recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
        assertThat(voiceResponse.followUpDepth).isZero()
        val followUp = QuestionEntity(id = 91, source = QuestionSource.FOLLOW_UP,
            parentRecordId = 40, rootRecordId = 30, followUpDepth = 2)
        val response = followUp.toStudyRecord().toProjection().toRecordResponse()
        assertThat(response.source).isEqualTo("follow_up")
        assertThat(response.recordType).isEqualTo(StudyRecordType.QUESTION)
        assertThat(response.parentRecordId).isEqualTo("40")
        assertThat(response.rootRecordId).isEqualTo("30")
        assertThat(response.followUpDepth).isEqualTo(2)
    }

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
