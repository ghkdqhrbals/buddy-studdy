package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.YearMonth

interface StudyPort {
    suspend fun save(entity: StudyEntity): StudyEntity
    suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long
    suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?
    suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity>
    suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity>
    suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity>
    suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity>
}

interface QuestionPort {
    suspend fun save(entity: QuestionEntity): QuestionEntity
    suspend fun findQuestionById(id: Long): QuestionEntity?
    suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?
    suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity>
    suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity>
    suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity>
    suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity>
    suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity>
    suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String>
    suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String>
    suspend fun countPendingForStudy(studyId: Long): Long
    suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long>
    suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredById(id: Long): QuestionEntity?
    suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity>
    suspend fun softDelete(id: Long, userId: Long, now: Instant): Int
    suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int
    suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int
}

interface QuestionStatsPort {
    suspend fun save(entity: QuestionStatsEntity): QuestionStatsEntity
    suspend fun findById(id: Long): QuestionStatsEntity?
    suspend fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity>
    suspend fun incrementView(questionId: Long, delta: Int, now: Instant): Int
    suspend fun incrementLike(questionId: Long, delta: Int, now: Instant): Int
    suspend fun incrementComment(questionId: Long, delta: Int, now: Instant): Int
    suspend fun setLikeCount(questionId: Long, count: Int, now: Instant): Int
}

data class QuestionEmbeddingCandidate(
    val questionId: Long,
    val question: String,
    val embedding: List<Float>,
)

interface QuestionEmbeddingPort {
    suspend fun save(
        questionId: Long,
        userId: Long,
        studyId: Long,
        topic: String,
        question: String,
        embedding: List<Float>,
    ): QuestionEmbeddingCandidate

    suspend fun findRecentByStudyIdAndTopic(studyId: Long, topic: String, limit: Int): List<QuestionEmbeddingCandidate>
}

data class QuestionCoverageSelection(
    val conceptId: Long,
    val coverageId: Long,
    val conceptKey: String,
    val conceptName: String,
    val angleKey: String,
    val angleName: String,
    val conceptKeyPath: String = conceptKey,
    val conceptPath: String = conceptName,
)

interface QuestionCoveragePort {
    data class CoverageConceptBlueprint(
        val key: String,
        val name: String,
        val angles: List<CoverageAngleBlueprint>,
        val children: List<CoverageConceptBlueprint> = emptyList(),
    )

    data class CoverageAngleBlueprint(
        val key: String,
        val name: String,
    )

    suspend fun ensureCoverage(studyId: Long, topic: String, concepts: List<CoverageConceptBlueprint>)
    suspend fun selectNext(studyId: Long): QuestionCoverageSelection?
    suspend fun markAsked(selection: QuestionCoverageSelection, now: Instant)
    suspend fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant)
}

data class QuestionMembershipPlan(
    val tierCode: String,
    val monthlyQuestionLimit: Int,
)

interface QuestionMembershipPort {
    suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan?
    suspend fun tryConsumeMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean
    suspend fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant)
}

interface QuestionCreatedPublishPort {
    suspend fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant = Instant.now()): Boolean
}

interface QuestionSearchTranslationPort {
    suspend fun translateSearchText(
        sourceLanguage: String,
        targetLanguage: String,
        topic: String,
        question: String,
        answer: String?,
        feedback: String?,
        explanation: String?,
    ): TranslatedQuestionSearchText
}

data class TranslatedQuestionSearchText(
    val topic: String,
    val question: String,
    val answer: String?,
    val feedback: String?,
    val explanation: String?,
)
