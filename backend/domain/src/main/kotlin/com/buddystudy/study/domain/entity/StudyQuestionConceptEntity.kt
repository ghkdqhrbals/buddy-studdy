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
    uniqueConstraints = [UniqueConstraint(name = "uq_study_question_concepts_study_path", columnNames = ["study_id", "path"])],
    indexes = [
        Index(name = "idx_study_question_concepts_study_tree_order", columnList = "study_id,parent_concept_id,display_order,id"),
        Index(name = "idx_study_question_concepts_study_path", columnList = "study_id,path"),
    ],
)
class StudyQuestionConceptEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "study_id", nullable = false)
    var studyId: Long = 0,
    @Column(name = "parent_concept_id")
    var parentConceptId: Long? = null,
    @Column(name = "concept_key", nullable = false, length = 255)
    var conceptKey: String = "",
    @Column(name = "concept_name", nullable = false, length = 255)
    var conceptName: String = "",
    @Column(name = "depth", nullable = false)
    var depth: Int = 0,
    @Column(name = "path", nullable = false, length = 1024)
    var path: String = "",
    @Column(name = "concept_path", nullable = false, length = 2048)
    var conceptPath: String = "",
    @Column(name = "leaf", nullable = false)
    var leaf: Boolean = true,
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
