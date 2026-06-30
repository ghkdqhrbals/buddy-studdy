package com.buddystudy.study.domain.entity

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
    name = "study_question_concepts",
    uniqueConstraints = [UniqueConstraint(name = "uq_study_question_concepts_study_key", columnNames = ["study_id", "concept_key"])],
    indexes = [Index(name = "idx_study_question_concepts_study_order", columnList = "study_id,display_order,id")],
)
class StudyQuestionConceptEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "study_id", nullable = false)
    var studyId: Long = 0,
    @Column(name = "concept_key", nullable = false, length = 255)
    var conceptKey: String = "",
    @Column(name = "concept_name", nullable = false, length = 255)
    var conceptName: String = "",
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
