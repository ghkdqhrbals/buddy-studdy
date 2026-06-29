package com.buddystuddy.backend.study.application.port.outbound

import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.YearMonth
import java.util.Optional

interface StudyPort {
    fun save(entity: StudyEntity): StudyEntity
    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?
    fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity>
    fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity>
    fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity>
    fun claimDue(now: Instant, limit: Int): List<StudyEntity>
}

interface QuestionPort {
    fun save(entity: QuestionEntity): QuestionEntity
    fun findQuestionById(id: Long): Optional<QuestionEntity>
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?
    fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity>
    fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity>
    fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity>
    fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity>
    fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity>
    fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity>
    fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity>
    fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity>
    fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String>
    fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String>
    fun countPendingForStudy(studyId: Long): Long
    fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long>
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
    fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity>
    fun incrementView(questionId: Long, delta: Int, now: Instant): Int
    fun incrementLike(questionId: Long, delta: Int, now: Instant): Int
    fun incrementComment(questionId: Long, delta: Int, now: Instant): Int
    fun setLikeCount(questionId: Long, count: Int, now: Instant): Int
}

data class QuestionEmbeddingCandidate(
    val questionId: Long,
    val question: String,
    val embedding: List<Float>,
)

interface QuestionEmbeddingPort {
    fun save(
        questionId: Long,
        userId: Long,
        studyId: Long,
        topic: String,
        question: String,
        embedding: List<Float>,
    ): QuestionEmbeddingCandidate

    fun findRecentByStudyIdAndTopic(studyId: Long, topic: String, limit: Int): List<QuestionEmbeddingCandidate>
}

data class QuestionCoverageSelection(
    val conceptId: Long,
    val coverageId: Long,
    val conceptKey: String,
    val conceptName: String,
    val angleKey: String,
    val angleName: String,
)

interface QuestionCoveragePort {
    data class CoverageConceptBlueprint(
        val key: String,
        val name: String,
        val angles: List<CoverageAngleBlueprint>,
    )

    data class CoverageAngleBlueprint(
        val key: String,
        val name: String,
    )

    fun ensureCoverage(studyId: Long, topic: String, concepts: List<CoverageConceptBlueprint>)
    fun selectNext(studyId: Long): QuestionCoverageSelection?
    fun markAsked(selection: QuestionCoverageSelection, now: Instant)
    fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant)
}

interface QuestionMembershipPort {
    fun activeTierCodeForUser(userId: Long): String?
    fun tryConsumeMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean
    fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant)
}

interface QuestionCreatedPublishPort {
    fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant = Instant.now()): Boolean
}

interface QuestionSearchTranslationPort {
    fun translateSearchText(
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
