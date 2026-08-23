package com.buddystudy.study.domain.entity

import com.buddystudy.common.domain.SupportedLanguage
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("questions")
class QuestionEntity(
    @Id
    var id: Long = 0,
    var deviceId: String = "",
    var userId: Long? = null,
    var studyId: Long? = null,
    var conceptId: Long? = null,
    var conceptKey: String? = null,
    var angleKey: String? = null,
    var question: String = "",
    var hint: String? = null,
    var topic: String = "",
    var sourceLanguage: SupportedLanguage = SupportedLanguage.KOREAN,
    var difficultyLevel: Int = 5,
    var scheduledFor: Instant = Instant.now(),
    var sentAt: Instant? = null,
    var status: QuestionStatus = QuestionStatus.UNGRADED,
    var error: String? = null,
    var answer: String? = null,
    var answerSourceLanguage: SupportedLanguage? = null,
    var score: Int? = null,
    @Column("is_correct")
    var correct: Boolean? = null,
    var feedback: String? = null,
    var explanation: String? = null,
    var aiResponseSourceLanguage: SupportedLanguage? = null,
    var gradingRubricJson: String? = null,
    var gradingAssessmentJson: String? = null,
    var gradingVerdict: GradingVerdict? = null,
    var gradingConfidence: Double? = null,
    var gradingPolicyVersion: String? = null,
    var gradingModel: String? = null,
    var gradingRequestId: String? = null,
    var gradingStatus: AnswerGradingStatus? = null,
    var gradingError: String? = null,
    var gradingLastEventId: Long? = null,
    var gradingRequestedAt: Instant? = null,
    var gradingStartedAt: Instant? = null,
    var answeredAt: Instant? = null,
    var gradedAt: Instant? = null,
    var skippedAt: Instant? = null,
    var deletedAt: Instant? = null,
    var source: QuestionSource = QuestionSource.SCHEDULED,
    @Column("is_public")
    var publicQuestion: Boolean = true,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
