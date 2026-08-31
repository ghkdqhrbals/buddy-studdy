package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.study.domain.entity.QuestionEmbeddingEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyRecordType
import kotlinx.coroutines.reactive.awaitSingle
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
        require(template.exists(
            Query.query(Criteria.where("id").`is`(questionId).and("user_id").`is`(userId)
                .and("study_id").`is`(studyId).and("record_type").`is`(StudyRecordType.QUESTION.name)),
            QuestionEntity::class.java,
        ).awaitSingle()) { "Embeddings require an owned generated question." }
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
    ): List<QuestionEmbeddingCandidate> = template.databaseClient.sql(
        """
        select e.question_id, e.question, e.embedding
        from question_embeddings e join questions q on q.id = e.question_id and q.record_type = 'QUESTION'
        where e.study_id = :studyId and e.topic_key = :topicKey
        order by e.created_at desc, e.question_id desc limit :limit
        """.trimIndent(),
    ).bind("studyId", studyId).bind("topicKey", topic.normalizedTopicKey()).bind("limit", limit.coerceAtLeast(1))
        .map { row, _ ->
            QuestionEmbeddingCandidate(
                questionId = (row.get("question_id") as Number).toLong(),
                question = row.get("question", String::class.java)!!,
                embedding = row.get("embedding", String::class.java)!!.split(',').mapNotNull { it.trim().toFloatOrNull() },
            )
        }.all().collectList().awaitSingle()
}

private fun QuestionEmbeddingEntity.toCandidate() = QuestionEmbeddingCandidate(
    questionId = questionId,
    question = question,
    embedding = embedding.split(',').mapNotNull { it.trim().toFloatOrNull() },
)

private fun String.normalizedTopicKey() = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
