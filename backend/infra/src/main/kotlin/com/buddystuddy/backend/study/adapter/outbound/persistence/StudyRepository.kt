package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudyRepository : JpaRepository<StudyEntity, Long>, StudyPort {
    override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?

    @Query("select s from StudyEntity s where s.userId = :userId and s.topic in :topics")
    fun findByUserIdAndTopicsInternal(
        @Param("userId") userId: Long,
        @Param("topics") topics: Collection<String>,
    ): List<StudyEntity>

    override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
        if (topics.isEmpty()) emptyList() else findByUserIdAndTopicsInternal(userId, topics)

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

}
