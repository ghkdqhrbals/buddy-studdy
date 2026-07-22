package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("study_question_concepts")
class StudyQuestionConceptEntity(
    @Id
    var id: Long = 0,
    var studyId: Long = 0,
    var parentConceptId: Long? = null,
    var conceptKey: String = "",
    var conceptName: String = "",
    var depth: Int = 0,
    var path: String = "",
    var conceptPath: String = "",
    var leaf: Boolean = true,
    var displayOrder: Int = 0,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
