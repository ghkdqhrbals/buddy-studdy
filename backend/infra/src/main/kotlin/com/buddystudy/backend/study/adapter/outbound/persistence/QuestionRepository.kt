package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface QuestionRepository : JpaRepository<QuestionEntity, Long>, QuestionPort {
    override fun findQuestionById(id: Long): java.util.Optional<QuestionEntity> = findById(id)

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

    @Query(
        """
        select q from QuestionEntity q
        where q.userId = :userId
          and q.deletedAt is null
          and q.score is not null
          and q.topic in :topics
        order by q.createdAt desc
        """
    )
    override fun findGradedByUserAndTopics(
        @Param("userId") userId: Long,
        @Param("topics") topics: Collection<String>,
        pageable: Pageable,
    ): Page<QuestionEntity>

    @Query(
        value = """
        select *
        from (
            select q.*,
                   row_number() over (
                       partition by q.topic
                       order by coalesce(q.answered_at, q.created_at) desc, q.created_at desc, q.id desc
                   ) as topic_rank
            from questions q
            where q.user_id = :userId
              and q.deleted_at is null
              and q.score is not null
              and q.topic in (:topics)
        ) ranked
        where ranked.topic_rank <= :perTopicLimit
        order by coalesce(ranked.answered_at, ranked.created_at) desc, ranked.created_at desc, ranked.id desc
        """,
        nativeQuery = true,
    )
    override fun findLatestGradedByUserAndTopics(
        @Param("userId") userId: Long,
        @Param("topics") topics: Collection<String>,
        @Param("perTopicLimit") perTopicLimit: Int,
    ): List<QuestionEntity>

    @Query("select q from QuestionEntity q where q.deletedAt is null and q.score is not null order by q.answeredAt desc, q.createdAt desc")
    override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.userId = :userId and q.deletedAt is null and q.score is null and q.skippedAt is null order by q.createdAt desc")
    override fun findPendingByUser(@Param("userId") userId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query("select q from QuestionEntity q where q.studyId = :studyId and q.deletedAt is null and q.score is null and q.skippedAt is null order by q.createdAt desc")
    override fun findPendingByStudyId(@Param("studyId") studyId: Long, pageable: Pageable): Page<QuestionEntity>

    @Query(
        value = """
        select *
        from (
            select q.*,
                   row_number() over (
                       partition by q.study_id
                       order by q.created_at desc, q.id desc
                   ) as study_rank
            from questions q
            where q.study_id in (:studyIds)
              and q.deleted_at is null
              and q.score is null
              and q.skipped_at is null
        ) ranked
        where ranked.study_rank = 1
        """,
        nativeQuery = true,
    )
    fun findLatestPendingByStudyIdsInternal(@Param("studyIds") studyIds: Collection<Long>): List<QuestionEntity>

    override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> =
        if (studyIds.isEmpty()) emptyList() else findLatestPendingByStudyIdsInternal(studyIds)

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

    @Query(
        """
        select q.question from QuestionEntity q
        where q.studyId = :studyId
          and q.deletedAt is null
          and lower(q.topic) = lower(:topic)
        order by q.createdAt desc, q.id desc
        """
    )
    override fun findRecentQuestionTextsByStudyIdAndTopic(
        @Param("studyId") studyId: Long,
        @Param("topic") topic: String,
        pageable: Pageable,
    ): List<String>

    @Query(
        """
        select q.question from QuestionEntity q
        where q.userId = :userId
          and q.deletedAt is null
          and lower(q.topic) = lower(:topic)
        order by q.createdAt desc, q.id desc
        """
    )
    override fun findRecentQuestionTextsByUserIdAndTopic(
        @Param("userId") userId: Long,
        @Param("topic") topic: String,
        pageable: Pageable,
    ): List<String>

    @Query("select count(q) from QuestionEntity q where q.studyId = :studyId and q.deletedAt is null and q.skippedAt is null and q.score is null")
    override fun countPendingForStudy(@Param("studyId") studyId: Long): Long

    @Query(
        """
        select q.studyId as studyId, count(q) as pendingCount
        from QuestionEntity q
        where q.studyId in :studyIds
          and q.deletedAt is null
          and q.skippedAt is null
          and q.score is null
        group by q.studyId
        """
    )
    fun findPendingCountsByStudyIds(@Param("studyIds") studyIds: Collection<Long>): List<PendingCountRow>

    override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> =
        if (studyIds.isEmpty()) {
            emptyMap()
        } else {
            findPendingCountsByStudyIds(studyIds).associate { it.studyId to it.pendingCount }
        }

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

    @Query(
        """
        select q from QuestionEntity q
        join UserEntity u on u.id = q.userId
        where q.id in :ids
          and q.publicQuestion = true
          and q.deletedAt is null
          and q.score is not null
          and u.allowPublicQuestions = true
        """
    )
    fun findPublicAnsweredByIdsInternal(@Param("ids") ids: Collection<Long>): List<QuestionEntity>

    override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> =
        if (ids.isEmpty()) emptyList() else findPublicAnsweredByIdsInternal(ids)

    @Modifying
    @Query("update QuestionEntity q set q.deletedAt = :now, q.updatedAt = :now where q.id = :id and q.userId = :userId")
    override fun softDelete(@Param("id") id: Long, @Param("userId") userId: Long, @Param("now") now: Instant): Int

    @Modifying
    @Query("update QuestionEntity q set q.deletedAt = :now, q.updatedAt = :now where q.studyId = :studyId and q.userId = :userId and q.deletedAt is null")
    override fun softDeleteByStudyId(@Param("studyId") studyId: Long, @Param("userId") userId: Long, @Param("now") now: Instant): Int

    @Modifying
    @Query("update QuestionEntity q set q.deletedAt = :now, q.updatedAt = :now where q.userId = :userId and lower(q.topic) = lower(:topic) and q.deletedAt is null")
    override fun softDeleteByUserIdAndTopic(@Param("userId") userId: Long, @Param("topic") topic: String, @Param("now") now: Instant): Int
}

interface PendingCountRow {
    val studyId: Long
    val pendingCount: Long
}
