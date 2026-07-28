package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.config.selectPage
import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
@Suppress("UNUSED_PARAMETER")
class QuestionRepository(
    private val template: R2dbcEntityTemplate,
    private val searchProjection: QuestionSearchProjectionManager,
) : QuestionPort {
    override suspend fun save(entity: QuestionEntity): QuestionEntity {
        val saved = template.saveEntity(entity, entity.id)
        searchProjection.refresh(saved.id)
        return saved
    }

    suspend fun findById(id: Long): QuestionEntity? = findQuestionById(id)

    suspend fun findAll(): List<QuestionEntity> =
        template.select(Query.empty(), QuestionEntity::class.java).collectList().awaitSingle()

    suspend fun deleteAll(): Long = template.delete(QuestionEntity::class.java).all().awaitSingle()

    override suspend fun findQuestionById(id: Long): QuestionEntity? = findOne(Criteria.where("id").`is`(id))

    override suspend fun findStalledGradings(cutoff: Instant, limit: Int): List<QuestionEntity> =
        template.select(
            Query.query(
                Criteria.where("deleted_at").isNull
                    .and("score").isNull
                    .and("grading_request_id").isNotNull
                    .and("grading_requested_at").lessThanOrEquals(cutoff)
                    .and("grading_status").`in`(NON_TERMINAL_GRADING_STATUSES),
            )
                .sort(Sort.by(Sort.Direction.ASC, "grading_requested_at"))
                .limit(limit.coerceIn(1, 500)),
            QuestionEntity::class.java,
        ).collectList().awaitSingle()

    override suspend fun failStalledGrading(
        id: Long,
        requestId: String,
        cutoff: Instant,
        error: String,
        now: Instant,
    ): Boolean {
        val updated = template.update(
            Query.query(
                Criteria.where("id").`is`(id)
                    .and("deleted_at").isNull
                    .and("score").isNull
                    .and("grading_request_id").`is`(requestId)
                    .and("grading_requested_at").lessThanOrEquals(cutoff)
                    .and("grading_status").`in`(NON_TERMINAL_GRADING_STATUSES),
            ),
            Update.update("grading_status", "FAILED")
                .set("grading_error", error.take(255))
                .set("updated_at", now),
            QuestionEntity::class.java,
        ).awaitSingle()
        return updated == 1L
    }

    override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? =
        findOne(Criteria.where("id").`is`(id).and("user_id").`is`(userId).and("deleted_at").isNull)

    override suspend fun findByGradingRequestIdAndUserIdAndDeletedAtIsNull(
        gradingRequestId: String,
        userId: Long,
    ): QuestionEntity? =
        findOne(
            Criteria.where("grading_request_id").`is`(gradingRequestId)
                .and("user_id").`is`(userId)
                .and("deleted_at").isNull,
        )

    override suspend fun lockByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? {
        val lockedId = template.databaseClient.sql(
            """
            select id
            from questions
            where id = :id and user_id = :userId and deleted_at is null
            for update
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("userId", userId)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingleOrNull()
            ?: return null
        return findQuestionById(lockedId)
    }

    override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> =
        page(Criteria.where("user_id").`is`(userId).and("deleted_at").isNull.and("score").isNotNull, pageable)

    override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> =
        userSearchPage(userId, includePending = false, language = null, query, pageable)

    override suspend fun findGradedByUserAndTopics(
        userId: Long,
        topics: Collection<String>,
        pageable: Pageable,
    ): Page<QuestionEntity> {
        if (topics.isEmpty()) return Page.empty(pageable)
        return page(
            Criteria.where("user_id").`is`(userId).and("deleted_at").isNull
                .and("score").isNotNull.and("topic").`in`(topics),
            pageable,
        )
    }

    override suspend fun findLatestGradedByUserAndTopics(
        userId: Long,
        topics: Collection<String>,
        perTopicLimit: Int,
    ): List<QuestionEntity> {
        if (topics.isEmpty()) return emptyList()
        val topicMarkers = indexedBindMarkers("topic", topics.size)
        val ids = template.databaseClient.sql(
            """
            select id from (
                select q.id,
                       row_number() over (
                           partition by q.topic
                           order by coalesce(q.answered_at, q.created_at) desc, q.created_at desc, q.id desc
                       ) as topic_rank
                from questions q
                where q.user_id = :userId and q.deleted_at is null and q.score is not null
                  and q.topic in ($topicMarkers)
            ) ranked
            where topic_rank <= :perTopicLimit
            order by id desc
            """.trimIndent(),
        ).bind("userId", userId).bindIndexed("topic", topics.toList()).bind("perTopicLimit", perTopicLimit)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        return findOrdered(ids)
    }

    override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> =
        page(Criteria.where("deleted_at").isNull.and("score").isNotNull, pageable, "answered_at")

    override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> =
        page(pendingCriteria().and("user_id").`is`(userId), pageable)

    override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> =
        page(pendingCriteria().and("study_id").`is`(studyId), pageable)

    override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> {
        return findLatestPendingByStudyIdsInternal(studyIds)
    }

    override suspend fun findLatestPendingByStudyIdsAndLanguage(
        studyIds: Collection<Long>,
        language: String,
    ): List<QuestionEntity> = findLatestPendingByStudyIdsInternal(studyIds)

    private suspend fun findLatestPendingByStudyIdsInternal(studyIds: Collection<Long>): List<QuestionEntity> {
        if (studyIds.isEmpty()) return emptyList()
        val studyMarkers = indexedBindMarkers("studyId", studyIds.size)
        val ids = template.databaseClient.sql(
            """
            select id from (
                select q.id, row_number() over (partition by q.study_id order by q.created_at desc, q.id desc) as study_rank
                from questions q
                where q.study_id in ($studyMarkers) and q.deleted_at is null
                  and q.score is null and q.skipped_at is null
            ) ranked where study_rank = 1
            """.trimIndent(),
        ).bindIndexed("studyId", studyIds.toList())
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        return findOrdered(ids)
    }

    override suspend fun findVisibleByUser(
        userId: Long,
        includePending: Boolean,
        pageable: Pageable,
    ): Page<QuestionEntity> {
        var criteria = Criteria.where("user_id").`is`(userId).and("deleted_at").isNull
        if (!includePending) criteria = criteria.and("score").isNotNull
        return page(criteria, pageable)
    }

    override suspend fun findVisibleByUserAndLanguage(
        userId: Long,
        includePending: Boolean,
        language: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = findVisibleByUser(userId, includePending, pageable)

    override suspend fun findVisibleByUserAndQuery(
        userId: Long,
        includePending: Boolean,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = userSearchPage(userId, includePending, language = null, query, pageable)

    override suspend fun findVisibleByUserAndLanguageAndQuery(
        userId: Long,
        includePending: Boolean,
        language: String,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> =
        userSearchPage(userId, includePending, QuestionLanguage.normalize(language), query, pageable)

    override suspend fun findVisibleByUserAndStudyId(
        userId: Long,
        includePending: Boolean,
        studyId: Long,
        query: String?,
        pageable: Pageable,
    ): Page<QuestionEntity> {
        val normalizedQuery = query?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedQuery != null) {
            return userSearchPage(
                userId = userId,
                includePending = includePending,
                language = null,
                query = normalizedQuery,
                pageable = pageable,
                studyId = studyId,
            )
        }
        var criteria = Criteria.where("user_id").`is`(userId)
            .and("study_id").`is`(studyId)
            .and("deleted_at").isNull
        if (!includePending) criteria = criteria.and("score").isNotNull
        return page(criteria, pageable)
    }

    override suspend fun findRecentQuestionTextsByStudyIdAndTopic(
        studyId: Long,
        topic: String,
        pageable: Pageable,
    ): List<String> = recentTexts("study_id", studyId, topic, pageable)

    override suspend fun findRecentQuestionTextsByUserIdAndTopic(
        userId: Long,
        topic: String,
        pageable: Pageable,
    ): List<String> = recentTexts("user_id", userId, topic, pageable)

    override suspend fun findRecentQuestionTextsByStudyIdAndTopicAndLanguage(
        studyId: Long,
        topic: String,
        language: String,
        pageable: Pageable,
    ): List<String> = recentTexts("study_id", studyId, topic, pageable, QuestionLanguage.normalize(language))

    override suspend fun findRecentQuestionTextsByUserIdAndTopicAndLanguage(
        userId: Long,
        topic: String,
        language: String,
        pageable: Pageable,
    ): List<String> = recentTexts("user_id", userId, topic, pageable, QuestionLanguage.normalize(language))

    override suspend fun countPendingForStudy(studyId: Long): Long =
        template.count(Query.query(pendingCriteria().and("study_id").`is`(studyId)), QuestionEntity::class.java)
            .awaitSingle()

    override suspend fun countPendingForStudyAndLanguage(studyId: Long, language: String): Long =
        countPendingForStudy(studyId)

    override suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> {
        if (studyIds.isEmpty()) return emptyMap()
        val studyMarkers = indexedBindMarkers("studyId", studyIds.size)
        return template.databaseClient.sql(
            """
            select study_id, count(*) as pending_count from questions
            where study_id in ($studyMarkers) and deleted_at is null and skipped_at is null and score is null
            group by study_id
            """.trimIndent(),
        ).bindIndexed("studyId", studyIds.toList())
            .map { row, _ ->
                row.get("study_id", java.lang.Long::class.java)!!.toLong() to
                    row.get("pending_count", java.lang.Long::class.java)!!.toLong()
            }.all().collectList().awaitSingle().toMap()
    }

    override suspend fun countPendingByStudyIdsAndLanguage(
        studyIds: Collection<Long>,
        language: String,
    ): Map<Long, Long> = countPendingByStudyIds(studyIds)

    override suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = publicPage(null, pageable)

    override suspend fun findPublicAnsweredByLanguage(
        language: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = publicPage(null, pageable, QuestionLanguage.normalize(language))

    override suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> =
        publicPage(topic to false, pageable)

    override suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> =
        publicPage(query to true, pageable)

    override suspend fun findPublicAnsweredByLanguageAndQuery(
        language: String,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> = publicPage(query to true, pageable, QuestionLanguage.normalize(language))

    override suspend fun findPublicAnsweredById(id: Long): QuestionEntity? =
        publicIds("q.id = :value", id, 1, 0).firstOrNull()?.let { findQuestionById(it) }

    override suspend fun findPublicAnsweredByIdAndLanguage(id: Long, language: String): QuestionEntity? =
        publicIds(
            condition = "q.id = :value",
            value = id,
            limit = 1,
            offset = 0,
            language = QuestionLanguage.normalize(language),
        ).firstOrNull()?.let { findQuestionById(it) }

    override suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> {
        if (ids.isEmpty()) return emptyList()
        val idMarkers = indexedBindMarkers("questionId", ids.size)
        val visible = template.databaseClient.sql(
            """
            select q.id from questions q join users u on u.id = q.user_id
            where q.id in ($idMarkers) and q.is_public = true and q.deleted_at is null
              and q.score is not null and u.allow_public_questions = true
            """.trimIndent(),
        ).bindIndexed("questionId", ids.toList())
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        return findOrdered(visible)
    }

    override suspend fun softDelete(id: Long, userId: Long, now: Instant): Int = updateDeleted(
        Criteria.where("id").`is`(id).and("user_id").`is`(userId), now,
    )

    override suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int = updateDeleted(
        Criteria.where("study_id").`is`(studyId).and("user_id").`is`(userId).and("deleted_at").isNull, now,
    )

    override suspend fun softDeleteByStudySubtree(rootStudyId: Long, userId: Long, now: Instant): Int {
        val studyIds = template.databaseClient.sql(
            """
            with recursive study_subtree as (
                select id
                from studies
                where id = :rootStudyId and user_id = :userId
                union all
                select child.id
                from studies child
                join study_subtree parent on child.parent_study_id = parent.id
                where child.user_id = :userId
            )
            select id from study_subtree
            """.trimIndent(),
        )
            .bind("rootStudyId", rootStudyId)
            .bind("userId", userId)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all()
            .collectList()
            .awaitSingle()
        if (studyIds.isEmpty()) return 0
        return updateDeleted(
            Criteria.where("study_id").`in`(studyIds)
                .and("user_id").`is`(userId)
                .and("deleted_at").isNull,
            now,
        )
    }

    override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int = updateDeleted(
        Criteria.where("user_id").`is`(userId).and("topic").`is`(topic).and("deleted_at").isNull, now,
    )

    private suspend fun findOne(criteria: Criteria): QuestionEntity? =
        template.selectOne(Query.query(criteria), QuestionEntity::class.java).awaitSingleOrNull()

    private suspend fun page(
        criteria: Criteria,
        pageable: Pageable,
        sortColumn: String = "created_at",
    ): Page<QuestionEntity> {
        val query = Query.query(criteria).sort(Sort.by(Sort.Direction.DESC, sortColumn, "id"))
        return template.selectPage(query, Query.query(criteria), QuestionEntity::class.java, pageable)
    }

    private fun pendingCriteria(): Criteria =
        Criteria.where("deleted_at").isNull.and("score").isNull.and("skipped_at").isNull

    private suspend fun recentTexts(
        column: String,
        id: Long,
        topic: String,
        pageable: Pageable,
        language: String? = null,
    ): List<String> {
        val normalizedLanguage = language?.let(QuestionLanguage::normalize)
        val localizationJoin = if (normalizedLanguage == null) "" else {
            """
            left join question_localizations ql
              on ql.question_id = q.id and ql.target_language = :language and ql.status = 'READY'
            """.trimIndent()
        }
        val questionColumn = if (normalizedLanguage == null) {
            "q.question"
        } else {
            "case when q.source_language = :language then q.question else coalesce(ql.question, q.question) end"
        }
        var spec = template.databaseClient.sql(
            """
            select $questionColumn as localized_question from questions q
            $localizationJoin
            where q.$column = :id and q.deleted_at is null and lower(q.topic) = lower(:topic)
            order by q.created_at desc, q.id desc limit :limit offset :offset
            """.trimIndent(),
        ).bind("id", id).bind("topic", topic).bind("limit", pageable.pageSize).bind("offset", pageable.offset)
        if (normalizedLanguage != null) {
            spec = spec.bind("language", normalizedLanguage)
        }
        return spec
            .map { row, _ -> row.get("localized_question", String::class.java)!! }
            .all().collectList().awaitSingle()
    }

    private suspend fun publicPage(
        filter: Pair<String, Boolean>?,
        pageable: Pageable,
        language: String? = null,
    ): Page<QuestionEntity> {
        val condition = when {
            filter == null -> "true"
            filter.second && language != null ->
                "(lower(coalesce(qs.topic, '')) like :pattern " +
                    "or lower(coalesce(qs.question, '')) like :pattern " +
                    "or lower(coalesce(qs.answer, '')) like :pattern " +
                    "or lower(coalesce(qs.feedback, '')) like :pattern " +
                    "or lower(coalesce(qs.explanation, '')) like :pattern " +
                    "or lower(u.display_name) like :pattern)"
            filter.second ->
                """
                (
                    exists (
                        select 1 from question_search qs
                        where qs.question_id = q.id
                          and (
                            lower(coalesce(qs.topic, '')) like :pattern
                            or lower(coalesce(qs.question, '')) like :pattern
                            or lower(coalesce(qs.answer, '')) like :pattern
                            or lower(coalesce(qs.feedback, '')) like :pattern
                            or lower(coalesce(qs.explanation, '')) like :pattern
                          )
                    )
                    or lower(u.display_name) like :pattern
                )
                """.trimIndent()
            else -> "lower(q.topic) like :pattern"
        }
        val searchLanguage = language?.takeIf { filter?.second == true }
        var idsSpec = template.databaseClient.sql(publicSelectSql(condition, searchLanguage))
            .bind("limit", pageable.pageSize).bind("offset", pageable.offset)
        var countSpec = template.databaseClient.sql(publicCountSql(condition, searchLanguage))
        if (searchLanguage != null) {
            idsSpec = idsSpec.bind("language", searchLanguage)
            countSpec = countSpec.bind("language", searchLanguage)
        }
        if (filter != null) {
            val pattern = "%${filter.first.lowercase()}%"
            idsSpec = idsSpec.bind("pattern", pattern)
            countSpec = countSpec.bind("pattern", pattern)
        }
        val ids = idsSpec.map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        val total = countSpec.map { row, _ -> row.get("total", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return PageImpl(findOrdered(ids), pageable, total)
    }

    private suspend fun userSearchPage(
        userId: Long,
        includePending: Boolean,
        language: String?,
        query: String,
        pageable: Pageable,
        studyId: Long? = null,
    ): Page<QuestionEntity> {
        val searchJoin = if (language == null) "" else {
            "join question_search qs on qs.question_id = q.id and qs.language = :language"
        }
        val searchCondition = if (language == null) {
            """
            exists (
                select 1 from question_search qs
                where qs.question_id = q.id
                  and (
                    lower(coalesce(qs.topic, '')) like :pattern
                    or lower(coalesce(qs.question, '')) like :pattern
                    or lower(coalesce(qs.answer, '')) like :pattern
                    or lower(coalesce(qs.feedback, '')) like :pattern
                    or lower(coalesce(qs.explanation, '')) like :pattern
                  )
            )
            """.trimIndent()
        } else {
            """
            (
                lower(coalesce(qs.topic, '')) like :pattern
                or lower(coalesce(qs.question, '')) like :pattern
                or lower(coalesce(qs.answer, '')) like :pattern
                or lower(coalesce(qs.feedback, '')) like :pattern
                or lower(coalesce(qs.explanation, '')) like :pattern
            )
            """.trimIndent()
        }
        val gradedCondition = if (includePending) "" else "and q.score is not null"
        val studyCondition = if (studyId == null) "" else "and q.study_id = :studyId"
        val baseSql =
            """
            from questions q
            $searchJoin
            where q.user_id = :userId and q.deleted_at is null
              $gradedCondition $studyCondition and $searchCondition
            """.trimIndent()
        var idsSpec = template.databaseClient.sql(
            """
            select q.id $baseSql
            order by q.created_at desc, q.id desc
            limit :limit offset :offset
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("pattern", "%${query.lowercase()}%")
            .bind("limit", pageable.pageSize)
            .bind("offset", pageable.offset)
        var countSpec = template.databaseClient.sql("select count(*) as total $baseSql")
            .bind("userId", userId)
            .bind("pattern", "%${query.lowercase()}%")
        if (language != null) {
            idsSpec = idsSpec.bind("language", language)
            countSpec = countSpec.bind("language", language)
        }
        if (studyId != null) {
            idsSpec = idsSpec.bind("studyId", studyId)
            countSpec = countSpec.bind("studyId", studyId)
        }
        val ids = idsSpec
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        val total = countSpec
            .map { row, _ -> row.get("total", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return PageImpl(findOrdered(ids), pageable, total)
    }

    private suspend fun publicIds(
        condition: String,
        value: Any,
        limit: Int,
        offset: Long,
        language: String? = null,
    ): List<Long> {
        var spec = template.databaseClient.sql(publicSelectSql(condition))
            .bind("value", value).bind("limit", limit).bind("offset", offset)
        return spec
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
    }

    private fun publicSelectSql(condition: String, searchLanguage: String? = null): String {
        val searchJoin = if (searchLanguage == null) "" else {
            "join question_search qs on qs.question_id = q.id and qs.language = :language"
        }
        return """
        select q.id from questions q join users u on u.id = q.user_id $searchJoin
        where q.is_public = true and q.deleted_at is null and q.score is not null
          and u.allow_public_questions = true and ($condition)
        order by q.created_at desc, q.id desc limit :limit offset :offset
        """.trimIndent()
    }

    private fun publicCountSql(condition: String, searchLanguage: String? = null): String {
        val searchJoin = if (searchLanguage == null) "" else {
            "join question_search qs on qs.question_id = q.id and qs.language = :language"
        }
        return """
        select count(*) as total from questions q join users u on u.id = q.user_id $searchJoin
        where q.is_public = true and q.deleted_at is null and q.score is not null
          and u.allow_public_questions = true and ($condition)
        """.trimIndent()
    }

    private suspend fun findOrdered(ids: List<Long>): List<QuestionEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = template.select(Query.query(Criteria.where("id").`in`(ids)), QuestionEntity::class.java)
            .collectList().awaitSingle().associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private suspend fun updateDeleted(criteria: Criteria, now: Instant): Int =
        template.update(QuestionEntity::class.java)
            .matching(Query.query(criteria))
            .apply(Update.update("deleted_at", now).set("updated_at", now))
            .awaitSingle().toInt()

    private companion object {
        val NON_TERMINAL_GRADING_STATUSES = listOf(
            "QUEUED",
            "ANALYZING_EVIDENCE",
            "CRITIQUING",
            "JUDGING",
            "ADJUDICATING",
        )
    }
}
