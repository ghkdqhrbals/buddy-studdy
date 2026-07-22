package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class QuestionPushOutboxRepository(
    private val template: R2dbcEntityTemplate,
) : QuestionPushOutboxPort {
    override suspend fun enqueue(request: QuestionPushRequest, now: Instant): Long = save(
        QuestionPushOutboxEntity(
            recordId = request.recordId, studyId = request.studyId, deviceId = request.deviceId,
            userId = request.userId, question = request.question, expectedAnswerHint = request.expectedAnswerHint,
            topic = request.topic, difficultyLevel = request.difficultyLevel, language = request.language,
            sound = request.sound, intervalMinutes = request.intervalMinutes, status = "PENDING",
            attempts = 0, nextAttemptAt = now, createdAt = request.createdAt, updatedAt = now,
        ),
    ).id

    suspend fun findPending(now: Instant, limit: Int): List<QuestionPushOutboxEntity> = template.select(
        Query.query(Criteria.where("status").`is`("PENDING").and("next_attempt_at").lessThanOrEquals(now))
            .sort(Sort.by(Sort.Direction.ASC, "created_at")).limit(limit),
        QuestionPushOutboxEntity::class.java,
    ).collectList().awaitSingle()

    suspend fun findById(id: Long): QuestionPushOutboxEntity? =
        template.selectOne(Query.query(Criteria.where("id").`is`(id)), QuestionPushOutboxEntity::class.java)
            .awaitSingleOrNull()

    suspend fun save(entity: QuestionPushOutboxEntity): QuestionPushOutboxEntity = template.saveEntity(entity, entity.id)

    suspend fun deleteAll(): Long =
        template.delete(QuestionPushOutboxEntity::class.java).all().awaitSingle()
}
