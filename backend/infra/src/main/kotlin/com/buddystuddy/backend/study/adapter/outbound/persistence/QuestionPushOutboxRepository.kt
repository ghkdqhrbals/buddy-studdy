package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.study.domain.entity.QuestionPushOutboxEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.time.Instant

interface QuestionPushOutboxJpaRepository : JpaRepository<QuestionPushOutboxEntity, Long> {
    @Query(
        """
        select o from QuestionPushOutboxEntity o
        where o.status = 'PENDING'
          and o.nextAttemptAt <= :now
        order by o.createdAt asc
        """
    )
    fun findPending(@Param("now") now: Instant, pageable: Pageable): List<QuestionPushOutboxEntity>
}

@Component
class QuestionPushOutboxPersistenceAdapter(
    private val outbox: QuestionPushOutboxJpaRepository,
) : QuestionPushOutboxPort {
    override fun enqueue(request: QuestionPushRequest, now: Instant) {
        outbox.save(
            QuestionPushOutboxEntity(
                recordId = request.recordId,
                deviceId = request.deviceId,
                userId = request.userId,
                question = request.question,
                expectedAnswerHint = request.expectedAnswerHint,
                topic = request.topic,
                difficultyLevel = request.difficultyLevel,
                language = request.language,
                sound = request.sound,
                intervalMinutes = request.intervalMinutes,
                status = "PENDING",
                attempts = 0,
                nextAttemptAt = now,
                createdAt = request.createdAt,
                updatedAt = now,
            )
        )
    }
}
