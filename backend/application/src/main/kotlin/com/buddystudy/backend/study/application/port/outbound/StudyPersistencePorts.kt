package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

interface StudyPort {
    suspend fun save(entity: StudyEntity): StudyEntity
    suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long
    suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity?
    suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity?
    suspend fun findByUserIdAndParentStudyIdAndTopic(userId: Long, parentStudyId: Long?, topic: String): StudyEntity?
    suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity?
    suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity>
    suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity>
    suspend fun findAllByUserId(userId: Long): List<StudyEntity> =
        findByUserId(userId, Pageable.unpaged()).content
    suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity>
    suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity>
}

interface StudyTopicSuggestionPort {
    suspend fun suggestTopics(
        rootTopic: String,
        parentTopic: String,
        existingTopics: Collection<String>,
        language: String,
        count: Int,
    ): List<String>
}

data class SystemTopicCatalogCandidate(
    val topic: String,
    val sortOrder: Int,
)

interface SystemTopicCatalogPort {
    suspend fun findChildren(
        rootTopicKey: String,
        parentPathKey: String,
        language: String,
        depth: Int,
        limit: Int,
    ): List<SystemTopicCatalogCandidate>

    suspend fun saveChildren(
        rootTopicKey: String,
        parentPathKey: String,
        language: String,
        depth: Int,
        topics: List<String>,
        now: Instant,
    )
}

interface QuestionPort {
    suspend fun save(entity: QuestionEntity): QuestionEntity
    suspend fun saveEnglishTranslation(
        questionId: Long,
        question: String,
        hint: String?,
        now: Instant,
    ): Boolean {
        val entity = findQuestionById(questionId) ?: return false
        entity.questionEn = question
        entity.hintEn = hint
        entity.translationStatus = "READY"
        entity.translationError = null
        entity.updatedAt = now
        save(entity)
        return true
    }
    suspend fun markEnglishTranslationFailed(questionId: Long, error: String, now: Instant) {
        val entity = findQuestionById(questionId) ?: return
        entity.translationStatus = "FAILED"
        entity.translationError = error
        entity.updatedAt = now
        save(entity)
    }
    suspend fun findQuestionById(id: Long): QuestionEntity?
    suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity?
    suspend fun findByGradingRequestIdAndUserIdAndDeletedAtIsNull(
        gradingRequestId: String,
        userId: Long,
    ): QuestionEntity? = null
    suspend fun lockByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? =
        findByIdAndUserIdAndDeletedAtIsNull(id, userId)
    suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity>
    suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity>
    suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity>
    suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity>
    suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity>
    suspend fun findLatestPendingByStudyIdsAndLanguage(studyIds: Collection<Long>, language: String): List<QuestionEntity> =
        findLatestPendingByStudyIds(studyIds).filter { it.language == language }
    suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity>
    suspend fun findVisibleByUserAndLanguage(
        userId: Long,
        includePending: Boolean,
        language: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = findVisibleByUser(userId, includePending, pageable)
    suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findVisibleByUserAndLanguageAndQuery(
        userId: Long,
        includePending: Boolean,
        language: String,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = findVisibleByUserAndQuery(userId, includePending, query, pageable)
    suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String>
    suspend fun findRecentQuestionTextsByStudyIdAndTopicAndLanguage(
        studyId: Long,
        topic: String,
        language: String,
        pageable: Pageable,
    ): List<String> = findRecentQuestionTextsByStudyIdAndTopic(studyId, topic, pageable)
    suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String>
    suspend fun findRecentQuestionTextsByUserIdAndTopicAndLanguage(
        userId: Long,
        topic: String,
        language: String,
        pageable: Pageable,
    ): List<String> = findRecentQuestionTextsByUserIdAndTopic(userId, topic, pageable)
    suspend fun countPendingForStudy(studyId: Long): Long
    suspend fun countPendingForStudyAndLanguage(studyId: Long, language: String): Long =
        countPendingForStudy(studyId)
    suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long>
    suspend fun countPendingByStudyIdsAndLanguage(studyIds: Collection<Long>, language: String): Map<Long, Long> =
        countPendingByStudyIds(studyIds)
    suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredByLanguage(language: String, pageable: Pageable): Page<QuestionEntity> =
        findPublicAnswered(pageable)
    suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity>
    suspend fun findPublicAnsweredByLanguageAndQuery(language: String, query: String, pageable: Pageable): Page<QuestionEntity> =
        findPublicAnsweredByQuery(query, pageable)
    suspend fun findPublicAnsweredById(id: Long): QuestionEntity?
    suspend fun findPublicAnsweredByIdAndLanguage(id: Long, language: String): QuestionEntity? =
        findPublicAnsweredById(id)?.takeIf { it.language == language }
    suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity>
    suspend fun softDelete(id: Long, userId: Long, now: Instant): Int
    suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int
    suspend fun softDeleteByStudySubtree(rootStudyId: Long, userId: Long, now: Instant): Int =
        softDeleteByStudyId(rootStudyId, userId, now)
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

data class QuestionQuotaStatus(
    val tierCode: String,
    val usedCount: Int,
    val monthlyQuestionLimit: Int,
)

interface QuestionMembershipPort {
    suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan?
    suspend fun quotaStatusForUser(userId: Long, periodStartedAt: Instant): QuestionQuotaStatus?
    suspend fun tryConsumeMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, limit: Int, now: Instant): Boolean
    suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant)
}
