package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.port.outbound.AiGradingAssessment
import com.buddystudy.study.domain.StudyRecordProjection

fun StudyRecordProjection.toRecordResponse(): StudyRecordResponse {
    val assessment = gradingAssessmentJson?.let { json ->
        runCatching { JsonMapperProvider.mapper.readValue(json, AiGradingAssessment::class.java) }.getOrNull()
    }
    return StudyRecordResponse(
        id = id,
        question = QuestionItemResponse(question = question, expectedAnswerHint = expectedAnswerHint, createdAt = createdAt),
        answer = answer,
        gradingResult = score?.let {
            GradingResultResponse(
                score = it,
                isCorrect = correct ?: (it >= 70),
                feedback = feedback ?: "",
                explanation = explanation ?: "",
                verdict = gradingVerdict,
                confidence = gradingConfidence,
                criteria = assessment?.criteria.orEmpty().map { criterion ->
                    GradingCriterionResponse(
                        criterionId = criterion.criterionId,
                        satisfied = criterion.satisfied,
                        evidence = criterion.evidence,
                        missing = criterion.missing,
                        reason = criterion.reason,
                    )
                },
                contradictions = assessment?.contradictions.orEmpty(),
                misconceptions = assessment?.misconceptions.orEmpty(),
                unsupportedClaims = assessment?.unsupportedClaims.orEmpty(),
                auditReason = assessment?.judgeReason,
                policyVersion = gradingPolicyVersion,
                model = gradingModel,
            )
        },
        topic = topic,
        difficulty = difficulty,
        answeredAt = answeredAt,
        isPublic = isPublic,
        likeCount = likeCount,
        commentCount = commentCount,
        viewCount = viewCount,
        studyId = studyId,
        gradingRequestId = gradingRequestId,
        gradingStatus = gradingStatus?.let { runCatching { AnswerGradingStatus.valueOf(it) }.getOrNull() },
        gradingError = gradingError,
    )
}
