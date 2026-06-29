package com.buddystuddy.study.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "study_question_coverage",
    uniqueConstraints = [UniqueConstraint(name = "uq_study_question_coverage_concept_angle", columnNames = ["concept_id", "angle_key"])],
    indexes = [
        Index(name = "idx_study_question_coverage_pick", columnList = "study_id,asked_count,last_asked_at,id"),
        Index(name = "idx_study_question_coverage_study", columnList = "study_id,concept_id"),
    ],
)
class StudyQuestionCoverageEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "study_id", nullable = false)
    var studyId: Long = 0,
    @Column(name = "concept_id", nullable = false)
    var conceptId: Long = 0,
    @Column(name = "angle_key", nullable = false, length = 255)
    var angleKey: String = "",
    @Column(name = "angle_name", nullable = false, length = 255)
    var angleName: String = "",
    @Column(name = "asked_count", nullable = false)
    var askedCount: Long = 0,
    @Column(name = "answer_count", nullable = false)
    var answerCount: Long = 0,
    @Column(name = "correct_count", nullable = false)
    var correctCount: Long = 0,
    @Column(name = "score_sum", nullable = false)
    var scoreSum: Long = 0,
    @Column(name = "last_asked_at")
    var lastAskedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
