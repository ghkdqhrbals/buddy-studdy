package com.buddystuddy.backend.study.application.port.outbound

import com.buddystuddy.domain.QuestionEntity
import com.buddystuddy.domain.QuestionStatsEntity
import com.buddystuddy.domain.ScheduleEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

interface SchedulePort {
    fun save(entity: ScheduleEntity): ScheduleEntity
    fun findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(deviceId: String, userId: Long?): ScheduleEntity?
    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long?): ScheduleEntity?
    fun findByDeviceIdAndUserIdAndTopic(deviceId: String, userId: Long?, topic: String): ScheduleEntity?
    fun findByUserIdAndTopic(userId: Long?, topic: String): ScheduleEntity?
    fun findDue(now: Instant, pageable: Pageable): List<ScheduleEntity>
}

interface QuestionPort {
    fun save(entity: QuestionEntity): QuestionEntity
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?
    fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity>
    fun countPendingForStudy(deviceId: String, userId: Long?, topic: String): Long
    fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity>
    fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity>
    fun findPublicAnsweredById(id: Long): QuestionEntity?
    fun softDelete(id: Long, userId: Long, now: Instant): Int
}

interface QuestionStatsPort {
    fun save(entity: QuestionStatsEntity): QuestionStatsEntity
    fun findById(id: Long): Optional<QuestionStatsEntity>
    fun incrementView(questionId: Long, delta: Int, now: Instant): Int
    fun incrementLike(questionId: Long, delta: Int, now: Instant): Int
    fun incrementComment(questionId: Long, delta: Int, now: Instant): Int
}
