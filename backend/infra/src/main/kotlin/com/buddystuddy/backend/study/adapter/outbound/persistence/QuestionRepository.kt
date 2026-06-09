package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface QuestionRepository : JpaRepository<QuestionEntity, Long>, QuestionPort {
    override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and q.score is not null order by q.createdAt desc")
    override fun findGradedByUser(@Param("userId") userId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        where q.userId = :userId
          and q.deletedAt is null
          and q.score is not null
          and (
            lower(q.topic) like concat('%', lower(:query), '%')
            or lower(q.question) like concat('%', lower(:query), '%')
            or lower(q.answer) like concat('%', lower(:query), '%')
            or lower(q.feedback) like concat('%', lower(:query), '%')
            or lower(q.explanation) like concat('%', lower(:query), '%')
            or str(q.difficultyLevel) like concat('%', :query, '%')
          )
        order by q.createdAt desc
        """
    )
    override fun findGradedByUserAndQuery(
        @Param("userId") userId: Long,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and q.score is null and q.skippedAt is null order by q.createdAt desc")
    override fun findPendingByUser(@Param("userId") userId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.studyId = :studyId and q.deletedAt is null and q.score is null and q.skippedAt is null order by q.createdAt desc")
    override fun findPendingByStudyId(@Param("studyId") studyId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and (:includePending = true or q.score is not null) order by q.createdAt desc")
    override fun findVisibleByUser(@Param("userId") userId: Long, @Param("includePending") includePending: Boolean, pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        where q.userId = :userId
          and q.deletedAt is null
          and (:includePending = true or q.score is not null)
          and (
            lower(q.topic) like concat('%', lower(:query), '%')
            or lower(q.question) like concat('%', lower(:query), '%')
            or lower(q.answer) like concat('%', lower(:query), '%')
            or lower(q.feedback) like concat('%', lower(:query), '%')
            or lower(q.explanation) like concat('%', lower(:query), '%')
            or str(q.difficultyLevel) like concat('%', :query, '%')
          )
        order by q.createdAt desc
        """
    )
    override fun findVisibleByUserAndQuery(
        @Param("userId") userId: Long,
        @Param("includePending") includePending: Boolean,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<QuestionEntity>

    @Query("select count(q) from QuestionEntity q where q.studyId = :studyId and q.deletedAt is null and q.skippedAt is null and q.score is null")
    override fun countPendingForStudy(@Param("studyId") studyId: Long): Long

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.publicQuestion = true
          and q.deletedAt is null
          and q.score is not null
          and u.allowPublicQuestions = true
        order by q.createdAt desc
        """
    )
    override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.publicQuestion = true
          and q.deletedAt is null
          and q.score is not null
          and u.allowPublicQuestions = true
          and lower(q.topic) like concat('%', lower(:topic), '%')
        order by q.createdAt desc
        """
    )
    override fun findPublicAnsweredByTopic(@Param("topic") topic: String, pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.publicQuestion = true
          and q.deletedAt is null
          and q.score is not null
          and u.allowPublicQuestions = true
          and (
            lower(q.topic) like concat('%', lower(:query), '%')
            or lower(q.question) like concat('%', lower(:query), '%')
            or lower(q.answer) like concat('%', lower(:query), '%')
            or lower(q.feedback) like concat('%', lower(:query), '%')
            or lower(q.explanation) like concat('%', lower(:query), '%')
            or lower(u.displayName) like concat('%', lower(:query), '%')
            or str(q.difficultyLevel) like concat('%', :query, '%')
          )
        order by q.createdAt desc
        """
    )
    override fun findPublicAnsweredByQuery(@Param("query") query: String, pageable: Pageable): Page<QuestionEntity>

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.id = :id and q.publicQuestion = true and q.deletedAt is null and q.score is not null and u.allowPublicQuestions = true
        """
    )
    override fun findPublicAnsweredById(@Param("id") id: Long): QuestionEntity?

    @Modifying
    @Query("update QuestionEntity q set q.deletedAt = :now, q.updatedAt = :now where q.id = :id and q.userId = :userId")
    override fun softDelete(@Param("id") id: Long, @Param("userId") userId: Long, @Param("now") now: Instant): Int
}
