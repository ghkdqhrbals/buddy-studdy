package com.buddystuddy.backend.study.repository

import com.buddystuddy.backend.domain.QuestionStatsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

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
