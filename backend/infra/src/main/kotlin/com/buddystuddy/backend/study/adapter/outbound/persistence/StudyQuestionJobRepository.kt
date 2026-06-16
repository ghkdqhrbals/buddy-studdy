package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.StudyQuestionJobPort
import com.buddystuddy.study.domain.entity.StudyQuestionJobEntity
import com.buddystuddy.study.domain.entity.StudyQuestionJobStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface StudyQuestionJobRepository : JpaRepository<StudyQuestionJobEntity, Long>, StudyQuestionJobPort {
    override fun saveBatch(entities: Iterable<StudyQuestionJobEntity>): List<StudyQuestionJobEntity> =
        saveAll(entities).filterNotNull()

    fun findFirstByStudyIdOrderByIdDesc(studyId: Long): StudyQuestionJobEntity?

    override fun findLatestByStudyId(studyId: Long): StudyQuestionJobEntity? =
        findFirstByStudyIdOrderByIdDesc(studyId)

    @Query(
        """
        select j
        from StudyQuestionJobEntity j
        where j.studyId in :studyIds
          and j.id in (
              select max(latest.id)
              from StudyQuestionJobEntity latest
              where latest.studyId in :studyIds
              group by latest.studyId
          )
        """
    )
    fun findLatestByStudyIdsInternal(@Param("studyIds") studyIds: Collection<Long>): List<StudyQuestionJobEntity>

    override fun findLatestByStudyIds(studyIds: Collection<Long>): List<StudyQuestionJobEntity> =
        if (studyIds.isEmpty()) emptyList() else findLatestByStudyIdsInternal(studyIds)

    @Query(
        value = """
        select *
        from study_question_jobs
        where status = 'SCHEDULED'
          and scheduled_at <= :now
        order by scheduled_at asc, id asc
        limit :limit
        for update skip locked
        """,
        nativeQuery = true,
    )
    override fun claimDue(@Param("now") now: Instant, @Param("limit") limit: Int): List<StudyQuestionJobEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update StudyQuestionJobEntity j
        set j.status = :canceled,
            j.canceledAt = :now,
            j.updatedAt = :now
        where j.studyId = :studyId
          and j.status = :scheduled
        """
    )
    fun cancelScheduledByStudyIdInternal(
        @Param("studyId") studyId: Long,
        @Param("scheduled") scheduled: StudyQuestionJobStatus,
        @Param("canceled") canceled: StudyQuestionJobStatus,
        @Param("now") now: Instant,
    ): Int

    override fun cancelScheduledByStudyId(studyId: Long, now: Instant): Int =
        cancelScheduledByStudyIdInternal(
            studyId = studyId,
            scheduled = StudyQuestionJobStatus.SCHEDULED,
            canceled = StudyQuestionJobStatus.CANCELED,
            now = now,
        )

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update StudyQuestionJobEntity j
        set j.status = :scheduled,
            j.lockedAt = null,
            j.lockedBy = null,
            j.updatedAt = :now,
            j.lastError = 'Recovered stale processing job.'
        where j.status = :processing
          and j.lockedAt < :before
        """
    )
    fun recoverStaleProcessingInternal(
        @Param("processing") processing: StudyQuestionJobStatus,
        @Param("scheduled") scheduled: StudyQuestionJobStatus,
        @Param("before") before: Instant,
        @Param("now") now: Instant,
    ): Int

    override fun recoverStaleProcessing(before: Instant, now: Instant): Int =
        recoverStaleProcessingInternal(
            processing = StudyQuestionJobStatus.PROCESSING,
            scheduled = StudyQuestionJobStatus.SCHEDULED,
            before = before,
            now = now,
        )

}
