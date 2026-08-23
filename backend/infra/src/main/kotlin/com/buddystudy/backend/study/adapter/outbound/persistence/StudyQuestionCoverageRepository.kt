package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.study.domain.entity.StudyQuestionConceptEntity
import com.buddystudy.study.domain.entity.StudyQuestionCoverageEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class StudyQuestionCoveragePersistenceAdapter(
    private val template: R2dbcEntityTemplate,
) : QuestionCoveragePort {
    @Transactional
    override suspend fun ensureCoverage(
        studyId: Long,
        topic: String,
        concepts: List<QuestionCoveragePort.CoverageConceptBlueprint>,
    ) {
        if (concepts.isEmpty() || conceptExists(studyId)) return
        saveConceptTree(studyId, null, 0, "", "", concepts, Instant.now())
    }

    override suspend fun selectNext(studyId: Long): QuestionCoverageSelection? =
        template.databaseClient.sql(
            """
            select c.id as coverage_id, c.concept_id, concept.concept_key, concept.concept_name,
                   concept.path as concept_key_path, concept.concept_path,
                   c.angle_key, c.angle_name
            from study_question_coverage c
            join study_question_concepts concept on concept.id = c.concept_id
            where c.study_id = :studyId
            order by c.asked_count asc,
                     case when c.last_asked_at is null then 0 else 1 end asc,
                     c.last_asked_at asc, c.id asc
            limit 1
            """.trimIndent(),
        ).bind("studyId", studyId).map { row, _ ->
            QuestionCoverageSelection(
                coverageId = row.get("coverage_id", java.lang.Long::class.java)!!.toLong(),
                conceptId = row.get("concept_id", java.lang.Long::class.java)!!.toLong(),
                conceptKey = row.get("concept_key", String::class.java)!!,
                conceptName = row.get("concept_name", String::class.java)!!,
                conceptKeyPath = row.get("concept_key_path", String::class.java)!!,
                conceptPath = row.get("concept_path", String::class.java)!!,
                angleKey = row.get("angle_key", String::class.java)!!,
                angleName = row.get("angle_name", String::class.java)!!,
            )
        }.one().awaitSingleOrNull()

    override suspend fun markAsked(selection: QuestionCoverageSelection, now: Instant) {
        template.databaseClient.sql(
            "update study_question_coverage set asked_count = asked_count + 1, last_asked_at = :now, updated_at = :now where id = :id",
        ).bind("now", now).bind("id", selection.coverageId).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun rollbackAsked(conceptId: Long, angleKey: String, now: Instant) {
        template.databaseClient.sql(
            """
            update study_question_coverage
            set asked_count = greatest(asked_count - 1, 0),
                last_asked_at = case when asked_count <= 1 then null else last_asked_at end,
                updated_at = :now
            where concept_id = :conceptId and angle_key = :angleKey
            """.trimIndent(),
        ).bind("now", now).bind("conceptId", conceptId).bind("angleKey", angleKey)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant) {
        template.databaseClient.sql(
            """
            update study_question_coverage
            set answer_count = answer_count + 1, score_sum = score_sum + :score,
                correct_count = correct_count + :correctDelta, updated_at = :now
            where concept_id = :conceptId and angle_key = :angleKey
            """.trimIndent(),
        ).bind("score", score.coerceIn(0, 100)).bind("correctDelta", if (correct) 1 else 0)
            .bind("now", now).bind("conceptId", conceptId).bind("angleKey", angleKey)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun conceptExists(studyId: Long): Boolean =
        template.exists(Query.query(Criteria.where("study_id").`is`(studyId)), StudyQuestionConceptEntity::class.java)
            .awaitSingle()

    private suspend fun saveConceptTree(
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
            val keyPath = listOf(parentKeyPath, key).filter(String::isNotBlank).joinToString("/")
            val namePath = listOf(parentNamePath, name).filter(String::isNotBlank).joinToString(" > ")
            val leaf = concept.children.isEmpty()
            val saved = template.saveEntity(
                StudyQuestionConceptEntity(
                    studyId = studyId, parentConceptId = parentConceptId, conceptKey = key, conceptName = name,
                    depth = depth, path = keyPath, conceptPath = namePath, leaf = leaf, displayOrder = index,
                    createdAt = now, updatedAt = now,
                ),
                0,
            )
            if (leaf) {
                concept.angles.ifEmpty { listOf(QuestionCoveragePort.CoverageAngleBlueprint("general", "General")) }
                    .forEach { angle ->
                        template.saveEntity(
                            StudyQuestionCoverageEntity(
                                studyId = studyId, conceptId = saved.id,
                                angleKey = angle.key.normalizedCoverageKey(),
                                angleName = angle.name.ifBlank { angle.key }, createdAt = now, updatedAt = now,
                            ),
                            0,
                        )
                    }
            } else {
                saveConceptTree(studyId, saved.id, depth + 1, keyPath, namePath, concept.children, now)
            }
        }
    }
}

private fun String.normalizedCoverageKey(): String =
    lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifBlank { "general" }
