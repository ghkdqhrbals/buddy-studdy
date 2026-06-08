package com.buddystuddy.backend.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}

interface DeviceRepository : JpaRepository<DeviceEntity, Long> {
    fun findByDeviceId(deviceId: String): DeviceEntity?
}

interface UserDeviceRepository : JpaRepository<UserDeviceEntity, Long> {
    fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?
}

interface ScheduleRepository : JpaRepository<ScheduleEntity, Long> {
    fun findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(deviceId: String, userId: Long?): ScheduleEntity?
    fun findByDeviceIdAndUserIdAndTopic(deviceId: String, userId: Long?, topic: String): ScheduleEntity?

    @Query("select s from ScheduleEntity s where s.enabled = true and s.nextDueAt is not null and s.nextDueAt <= :now order by s.nextDueAt asc")
    fun findDue(@Param("now") now: Instant, pageable: Pageable): List<ScheduleEntity>
}

interface QuestionRepository : JpaRepository<QuestionEntity, Long> {
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and q.score is not null order by q.createdAt desc")
    fun findGradedByUser(@Param("userId") userId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and q.score is null and q.skippedAt is null order by q.createdAt desc")
    fun findPendingByUser(@Param("userId") userId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and (:includePending = true or q.score is not null) order by q.createdAt desc")
    fun findVisibleByUser(@Param("userId") userId: Long, @Param("includePending") includePending: Boolean, pageable: Pageable): Page<QuestionEntity>

    @Query("select count(q) from QuestionEntity q where q.deviceId = :deviceId and q.userId = :userId and lower(q.topic) = lower(:topic) and q.deletedAt is null and q.skippedAt is null and q.score is null")
    fun countPendingForStudy(@Param("deviceId") deviceId: String, @Param("userId") userId: Long?, @Param("topic") topic: String): Long

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.publicQuestion = true
          and q.deletedAt is null
          and q.score is not null
          and u.allowPublicQuestions = true
          and (:topic is null or lower(q.topic) like lower(concat('%', :topic, '%')))
        order by q.createdAt desc
        """
    )
    fun findPublicAnswered(@Param("topic") topic: String?, pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.id = :id and q.publicQuestion = true and q.deletedAt is null and q.score is not null and u.allowPublicQuestions = true
        """
    )
    fun findPublicAnsweredById(@Param("id") id: Long): QuestionEntity?

    @Modifying
    @Query("update QuestionEntity q set q.deletedAt = :now, q.updatedAt = :now where q.id = :id and q.userId = :userId")
    fun softDelete(@Param("id") id: Long, @Param("userId") userId: Long, @Param("now") now: Instant): Int
}

interface QuestionStatsRepository : JpaRepository<QuestionStatsEntity, Long> {
    @Modifying
    @Query(
        """
        update QuestionStatsEntity s
           set s.viewCount = case when s.viewCount + :delta < 0 then 0 else s.viewCount + :delta end,
               s.updatedAt = :now
         where s.questionId = :questionId
        """
    )
    fun incrementView(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update QuestionStatsEntity s
           set s.likeCount = case when s.likeCount + :delta < 0 then 0 else s.likeCount + :delta end,
               s.updatedAt = :now
         where s.questionId = :questionId
        """
    )
    fun incrementLike(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update QuestionStatsEntity s
           set s.commentCount = case when s.commentCount + :delta < 0 then 0 else s.commentCount + :delta end,
               s.updatedAt = :now
         where s.questionId = :questionId
        """
    )
    fun incrementComment(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int
}

interface QuestionLikeRepository : JpaRepository<QuestionLikeEntity, Long> {
    fun findByQuestionIdAndUserId(questionId: Long, userId: Long): QuestionLikeEntity?
    fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}

interface QuestionCommentRepository : JpaRepository<QuestionCommentEntity, Long> {
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}

interface ReportRepository : JpaRepository<ReportEntity, Long>
