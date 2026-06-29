package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystuddy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystuddy.study.domain.entity.QuestionEmbeddingEntity
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface QuestionEmbeddingRepository : JpaRepository<QuestionEmbeddingEntity, Long>, QuestionEmbeddingPort {
    override fun save(
        questionId: Long,
        userId: Long,
        studyId: Long,
        topic: String,
        question: String,
        embedding: List<Float>,
    ): QuestionEmbeddingCandidate {
        val now = Instant.now()
        val entity = QuestionEmbeddingEntity(
            questionId = questionId,
            userId = userId,
            studyId = studyId,
            topic = topic,
            topicKey = topic.normalizedTopicKey(),
            question = question,
            embedding = embedding.toEmbeddingText(),
            createdAt = now,
            updatedAt = now,
        )
        val saved = save(entity)
        return saved.toCandidate()
    }

    override fun findRecentByStudyIdAndTopic(studyId: Long, topic: String, limit: Int): List<QuestionEmbeddingCandidate> =
        findRecentByStudyIdAndTopicKeyInternal(studyId, topic.normalizedTopicKey(), PageRequest.of(0, limit.coerceAtLeast(1)))
            .map { it.toCandidate() }

    @Query(
        """
        select e from QuestionEmbeddingEntity e
        where e.studyId = :studyId and e.topicKey = :topicKey
        order by e.createdAt desc, e.questionId desc
        """
    )
    fun findRecentByStudyIdAndTopicKeyInternal(
        @Param("studyId") studyId: Long,
        @Param("topicKey") topicKey: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<QuestionEmbeddingEntity>
}

private fun QuestionEmbeddingEntity.toCandidate(): QuestionEmbeddingCandidate =
    QuestionEmbeddingCandidate(
        questionId = questionId,
        question = question,
        embedding = embedding.toFloatList(),
    )

private fun List<Float>.toEmbeddingText(): String = joinToString(",")

private fun String.toFloatList(): List<Float> =
    split(',')
        .mapNotNull { it.trim().toFloatOrNull() }

private fun String.normalizedTopicKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
