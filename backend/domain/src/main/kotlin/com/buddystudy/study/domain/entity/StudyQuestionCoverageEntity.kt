package com.buddystudy.study.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("study_question_coverage")
class StudyQuestionCoverageEntity(
    @Id
    var id: Long = 0,
    var studyId: Long = 0,
    var conceptId: Long = 0,
    var angleKey: String = "",
    var angleName: String = "",
    var askedCount: Long = 0,
    var answerCount: Long = 0,
    var correctCount: Long = 0,
    var scoreSum: Long = 0,
    var lastAskedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
