package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.study.domain.entity.StudyQuestionConceptEntity
import com.buddystudy.study.domain.entity.StudyQuestionCoverageEntity
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface StudyQuestionConceptRepository : JpaRepository<StudyQuestionConceptEntity, Long> {
    fun existsByStudyId(studyId: Long): Boolean
}

interface StudyQuestionCoverageJpaRepository : JpaRepository<StudyQuestionCoverageEntity, Long> {
    fun findByConceptIdAndAngleKey(conceptId: Long, angleKey: String): StudyQuestionCoverageEntity?

    @Query(
        """
        select c.id as coverageId,
               c.conceptId as conceptId,
               concept.conceptKey as conceptKey,
               concept.conceptName as conceptName,
               concept.path as conceptKeyPath,
               concept.conceptPath as conceptPath,
               c.angleKey as angleKey,
               c.angleName as angleName
        from StudyQuestionCoverageEntity c
        join StudyQuestionConceptEntity concept on concept.id = c.conceptId
        where c.studyId = :studyId
        order by c.askedCount asc,
                 case when c.lastAskedAt is null then 0 else 1 end asc,
                 c.lastAskedAt asc,
                 c.id asc
        """
    )
    fun selectNextInternal(@Param("studyId") studyId: Long, pageable: org.springframework.data.domain.Pageable): List<QuestionCoverageSelectionRow>

    @Modifying
    @Query(
        """
        update StudyQuestionCoverageEntity c
        set c.askedCount = c.askedCount + 1,
            c.lastAskedAt = :now,
            c.updatedAt = :now
        where c.id = :coverageId
        """
    )
    fun incrementAsked(@Param("coverageId") coverageId: Long, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update StudyQuestionCoverageEntity c
        set c.answerCount = c.answerCount + 1,
            c.scoreSum = c.scoreSum + :score,
            c.correctCount = c.correctCount + :correctDelta,
            c.updatedAt = :now
        where c.conceptId = :conceptId and c.angleKey = :angleKey
        """
    )
    fun incrementAnswered(
        @Param("conceptId") conceptId: Long,
        @Param("angleKey") angleKey: String,
        @Param("score") score: Int,
        @Param("correctDelta") correctDelta: Int,
        @Param("now") now: Instant,
    ): Int
}

interface QuestionCoverageSelectionRow {
    val coverageId: Long
    val conceptId: Long
    val conceptKey: String
    val conceptName: String
    val conceptKeyPath: String
    val conceptPath: String
    val angleKey: String
    val angleName: String
}

@Component
class StudyQuestionCoveragePersistenceAdapter(
    private val concepts: StudyQuestionConceptRepository,
    private val coverage: StudyQuestionCoverageJpaRepository,
) : QuestionCoveragePort {
    @Transactional
    override fun ensureCoverage(studyId: Long, topic: String, concepts: List<QuestionCoveragePort.CoverageConceptBlueprint>) {
        if (concepts.isEmpty() || this.concepts.existsByStudyId(studyId)) return
        val now = Instant.now()
        saveConceptTree(
            studyId = studyId,
            parentConceptId = null,
            depth = 0,
            parentKeyPath = "",
            parentNamePath = "",
            concepts = concepts,
            now = now,
        )
    }

    override fun selectNext(studyId: Long): QuestionCoverageSelection? =
        coverage.selectNextInternal(studyId, PageRequest.of(0, 1)).firstOrNull()?.let {
            QuestionCoverageSelection(
                conceptId = it.conceptId,
                coverageId = it.coverageId,
                conceptKey = it.conceptKey,
                conceptName = it.conceptName,
                angleKey = it.angleKey,
                angleName = it.angleName,
                conceptKeyPath = it.conceptKeyPath,
                conceptPath = it.conceptPath,
            )
        }

    @Transactional
    override fun markAsked(selection: QuestionCoverageSelection, now: Instant) {
        coverage.incrementAsked(selection.coverageId, now)
    }

    @Transactional
    override fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant) {
        coverage.incrementAnswered(conceptId, angleKey, score.coerceIn(0, 100), if (correct) 1 else 0, now)
    }

    private fun saveConceptTree(
        studyId: Long,
        parentConceptId: Long?,
        depth: Int,
        parentKeyPath: String,
        parentNamePath: String,
        concepts: List<QuestionCoveragePort.CoverageConceptBlueprint>,
        now: Instant,
    ) {
        concepts.forEachIndexed { index, concept ->
            val key = concept.key.normalizedCoverageKey()
            val name = concept.name.ifBlank { concept.key.ifBlank { key } }
            val keyPath = listOf(parentKeyPath, key).filter { it.isNotBlank() }.joinToString("/")
            val namePath = listOf(parentNamePath, name).filter { it.isNotBlank() }.joinToString(" > ")
            val leaf = concept.children.isEmpty()
            val savedConcept = this.concepts.save(
                StudyQuestionConceptEntity(
                    studyId = studyId,
                    parentConceptId = parentConceptId,
                    conceptKey = key,
                    conceptName = name,
                    depth = depth,
                    path = keyPath,
                    conceptPath = namePath,
                    leaf = leaf,
                    displayOrder = index,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (leaf) {
                concept.angles
                    .ifEmpty { listOf(QuestionCoveragePort.CoverageAngleBlueprint("general", "General")) }
                    .forEach { angle ->
                        coverage.save(
                            StudyQuestionCoverageEntity(
                                studyId = studyId,
                                conceptId = savedConcept.id,
                                angleKey = angle.key.normalizedCoverageKey(),
                                angleName = angle.name.ifBlank { angle.key },
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                    }
            } else {
                saveConceptTree(
                    studyId = studyId,
                    parentConceptId = savedConcept.id,
                    depth = depth + 1,
                    parentKeyPath = keyPath,
                    parentNamePath = namePath,
                    concepts = concept.children,
                    now = now,
                )
            }
        }
    }
}

private fun String.normalizedCoverageKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')
        .ifBlank { "general" }
