package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface StudyRepository : JpaRepository<StudyEntity, Long>, StudyPort {
    override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?
    override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity>

    @Query(
        """
        select s from StudyEntity s
        where s.userId = :userId
          and (
            lower(s.topic) like concat('%', lower(:query), '%')
            or lower(s.customPrompt) like concat('%', lower(:query), '%')
            or lower(s.openaiModel) like concat('%', lower(:query), '%')
            or str(s.difficultyLevel) like concat('%', :query, '%')
          )
        order by s.updatedAt desc
        """
    )
    override fun findByUserIdAndQuery(
        @Param("userId") userId: Long,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<StudyEntity>

    @Query("select s from StudyEntity s where s.enabled = true and s.nextDueAt is not null and s.nextDueAt <= :now order by s.nextDueAt asc")
    override fun findDue(@Param("now") now: Instant, pageable: Pageable): List<StudyEntity>
}
