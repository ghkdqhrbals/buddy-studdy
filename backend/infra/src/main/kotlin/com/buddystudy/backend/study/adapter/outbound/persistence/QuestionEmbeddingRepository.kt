package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.study.domain.entity.QuestionEmbeddingEntity
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class QuestionEmbeddingRepository(private val template: R2dbcEntityTemplate) : QuestionEmbeddingPort {
    override suspend fun save(
        questionId: Long, userId: Long, studyId: Long, topic: String, question: String, embedding: List<Float>,
    ): QuestionEmbeddingCandidate {
        val now = Instant.now()
        return template.saveEntity(
            QuestionEmbeddingEntity(
                questionId = questionId, userId = userId, studyId = studyId, topic = topic,
                topicKey = topic.normalizedTopicKey(), question = question, embedding = embedding.joinToString(","),
                createdAt = now, updatedAt = now,
            ),
            0,
        ).toCandidate()
    }

    override suspend fun findRecentByStudyIdAndTopic(
        studyId: Long,
        topic: String,
        limit: Int,
    ): List<QuestionEmbeddingCandidate> = template.select(
        Query.query(
            Criteria.where("study_id").`is`(studyId).and("topic_key").`is`(topic.normalizedTopicKey()),
        ).sort(Sort.by(Sort.Direction.DESC, "created_at", "question_id")).limit(limit.coerceAtLeast(1)),
        QuestionEmbeddingEntity::class.java,
    ).collectList().awaitSingle().map { it.toCandidate() }
}

private fun QuestionEmbeddingEntity.toCandidate() = QuestionEmbeddingCandidate(
    questionId = questionId,
    question = question,
    embedding = embedding.split(',').mapNotNull { it.trim().toFloatOrNull() },
)

private fun String.normalizedTopicKey() = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
