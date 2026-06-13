package com.buddystuddy.backend.study.application.port.outbound

import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.ScheduleEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

interface SchedulePort {
    fun save(entity: ScheduleEntity): ScheduleEntity
    fun findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(deviceId: String, userId: Long?): ScheduleEntity?
    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long?): ScheduleEntity?
    fun findByIdAndUserId(id: Long, userId: Long?): ScheduleEntity?
    fun findByDeviceIdAndUserIdAndTopic(deviceId: String, userId: Long?, topic: String): ScheduleEntity?
    fun findByUserIdAndTopic(userId: Long?, topic: String): ScheduleEntity?
    fun findDue(now: Instant, pageable: Pageable): List<ScheduleEntity>
}

interface StudyPort {
    fun save(entity: StudyEntity): StudyEntity
    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?
    fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity>
    fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity>
    fun findDue(now: Instant, pageable: Pageable): List<StudyEntity>
}

interface QuestionPort {
    fun save(entity: QuestionEntity): QuestionEntity
    fun findQuestionById(id: Long): Optional<QuestionEntity>
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?
    fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity>
    fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity>
    fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity>
    fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity>
    fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity>
    fun countPendingForStudy(studyId: Long): Long
    fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity>
    fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity>
    fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity>
    fun findPublicAnsweredById(id: Long): QuestionEntity?
    fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity>
    fun softDelete(id: Long, userId: Long, now: Instant): Int
}

interface QuestionStatsPort {
    fun save(entity: QuestionStatsEntity): QuestionStatsEntity
    fun findById(id: Long): Optional<QuestionStatsEntity>
    fun incrementView(questionId: Long, delta: Int, now: Instant): Int
    fun incrementLike(questionId: Long, delta: Int, now: Instant): Int
    fun incrementComment(questionId: Long, delta: Int, now: Instant): Int
    fun setLikeCount(questionId: Long, count: Int, now: Instant): Int
}
