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
class QuestionRepository(
    private val template: R2dbcEntityTemplate,
) : QuestionPort {
    override suspend fun save(entity: QuestionEntity): QuestionEntity = template.saveEntity(entity, entity.id)

    override suspend fun saveEnglishTranslation(
        questionId: Long,
        topic: String,
        question: String,
        hint: String?,
        now: Instant,
    ): Boolean =
        template.update(QuestionEntity::class.java)
            .matching(Query.query(Criteria.where("id").`is`(questionId).and("deleted_at").isNull))
            .apply(
                Update.update("topic_en", topic)
                    .set("question_en", question)
                    .set("hint_en", hint)
                    .set("translation_status", "READY")
                    .set("translation_error", null)
                    .set("updated_at", now),
            )
            .awaitSingle() > 0

    override suspend fun findEnglishTopicBackfillCandidates(limit: Int): List<QuestionEntity> =
        template.select(
            Query.query(
                Criteria.where("deleted_at").isNull
                    .and("translation_status").`is`("READY")
                    .and("question_en").isNotNull
                    .and("topic_en").isNull,
            )
                .sort(Sort.by(Sort.Direction.ASC, "id"))
                .limit(limit.coerceIn(1, 100)),
            QuestionEntity::class.java,
        ).collectList().awaitSingle()

    override suspend fun saveEnglishTopicTranslation(questionId: Long, topic: String, now: Instant): Boolean =
        template.update(QuestionEntity::class.java)
            .matching(
                Query.query(
                    Criteria.where("id").`is`(questionId)
                        .and("deleted_at").isNull
                        .and("topic_en").isNull,
                ),
            )
            .apply(
                Update.update("topic_en", topic)
                    .set("updated_at", now),
            )
            .awaitSingle() > 0

    override suspend fun markEnglishTranslationFailed(questionId: Long, error: String, now: Instant) {
        template.update(QuestionEntity::class.java)
            .matching(Query.query(Criteria.where("id").`is`(questionId).and("deleted_at").isNull))
            .apply(
                Update.update("translation_status", "FAILED")
                    .set("translation_error", error.take(2_000))
                    .set("updated_at", now),
            )
            .awaitSingle()
    }

    suspend fun findById(id: Long): QuestionEntity? = findQuestionById(id)

    suspend fun findAll(): List<QuestionEntity> =
        template.select(Query.empty(), QuestionEntity::class.java).collectList().awaitSingle()

    suspend fun deleteAll(): Long = template.delete(QuestionEntity::class.java).all().awaitSingle()

    override suspend fun findQuestionById(id: Long): QuestionEntity? = findOne(Criteria.where("id").`is`(id))

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
        page(
            Criteria.where("user_id").`is`(userId)
                .and("deleted_at").isNull
                .and("score").isNotNull
                .and(textSearch(query)),
            pageable,
        )

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
        return findLatestPendingByStudyIds(studyIds, language = null)
    }

    override suspend fun findLatestPendingByStudyIdsAndLanguage(
        studyIds: Collection<Long>,
        language: String,
    ): List<QuestionEntity> = findLatestPendingByStudyIds(studyIds, QuestionLanguage.normalize(language))

    private suspend fun findLatestPendingByStudyIds(
        studyIds: Collection<Long>,
        language: String?,
    ): List<QuestionEntity> {
        if (studyIds.isEmpty()) return emptyList()
        val studyMarkers = indexedBindMarkers("studyId", studyIds.size)
        val languageCondition = when (language) {
            QuestionLanguage.ENGLISH ->
                "and q.translation_status = 'READY' and q.question_en is not null"
            null -> ""
            else -> "and q.language = :language"
        }
        var spec = template.databaseClient.sql(
            """
            select id from (
                select q.id, row_number() over (partition by q.study_id order by q.created_at desc, q.id desc) as study_rank
                from questions q
                where q.study_id in ($studyMarkers) and q.deleted_at is null
                  and q.score is null and q.skipped_at is null
                  $languageCondition
            ) ranked where study_rank = 1
            """.trimIndent(),
        ).bindIndexed("studyId", studyIds.toList())
        if (language != null && language != QuestionLanguage.ENGLISH) spec = spec.bind("language", language)
        val ids = spec
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
    ): Page<QuestionEntity> {
        var criteria = Criteria.where("user_id").`is`(userId)
            .and(languageCriteria(language))
            .and("deleted_at").isNull
        if (!includePending) criteria = criteria.and("score").isNotNull
        return page(criteria, pageable)
    }

    override suspend fun findVisibleByUserAndQuery(
        userId: Long,
        includePending: Boolean,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> {
        var criteria = Criteria.where("user_id").`is`(userId).and("deleted_at").isNull.and(textSearch(query))
        if (!includePending) criteria = criteria.and("score").isNotNull
        return page(criteria, pageable)
    }

    override suspend fun findVisibleByUserAndLanguageAndQuery(
        userId: Long,
        includePending: Boolean,
        language: String,
        query: String,
        pageable: Pageable,
    ): Page<QuestionEntity> {
        var criteria = Criteria.where("user_id").`is`(userId)
            .and(languageCriteria(language))
            .and("deleted_at").isNull
            .and(textSearch(query))
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
        template.count(
            Query.query(
                pendingCriteria()
                    .and("study_id").`is`(studyId)
                    .and(languageCriteria(language)),
            ),
            QuestionEntity::class.java,
        ).awaitSingle()

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
    ): Map<Long, Long> {
        if (studyIds.isEmpty()) return emptyMap()
        val studyMarkers = indexedBindMarkers("studyId", studyIds.size)
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val languageCondition = if (normalizedLanguage == QuestionLanguage.ENGLISH) {
            "translation_status = 'READY' and question_en is not null"
        } else {
            "language = :language"
        }
        var spec = template.databaseClient.sql(
            """
            select study_id, count(*) as pending_count from questions
            where study_id in ($studyMarkers) and $languageCondition
              and deleted_at is null and skipped_at is null and score is null
            group by study_id
            """.trimIndent(),
        ).bindIndexed("studyId", studyIds.toList())
        if (normalizedLanguage != QuestionLanguage.ENGLISH) {
            spec = spec.bind("language", normalizedLanguage)
        }
        return spec
            .map { row, _ ->
                row.get("study_id", java.lang.Long::class.java)!!.toLong() to
                    row.get("pending_count", java.lang.Long::class.java)!!.toLong()
            }.all().collectList().awaitSingle().toMap()
    }

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

    private fun languageCriteria(language: String): Criteria =
        if (QuestionLanguage.normalize(language) == QuestionLanguage.ENGLISH) {
            Criteria.where("translation_status").`is`("READY").and("question_en").isNotNull
        } else {
            Criteria.where("language").`is`(QuestionLanguage.KOREAN)
        }

    private fun textSearch(value: String): Criteria {
        val pattern = "%${value.lowercase()}%"
        return Criteria.where("topic").like(pattern).ignoreCase(true)
            .or("question").like(pattern).ignoreCase(true)
            .or("question_en").like(pattern).ignoreCase(true)
            .or("answer").like(pattern).ignoreCase(true)
            .or("feedback").like(pattern).ignoreCase(true)
            .or("explanation").like(pattern).ignoreCase(true)
    }

    private suspend fun recentTexts(
        column: String,
        id: Long,
        topic: String,
        pageable: Pageable,
        language: String? = null,
    ): List<String> {
        val normalizedLanguage = language?.let(QuestionLanguage::normalize)
        val languageCondition = when (normalizedLanguage) {
            QuestionLanguage.ENGLISH ->
                "and translation_status = 'READY' and question_en is not null"
            null -> ""
            else -> "and language = :language"
        }
        val questionColumn = if (normalizedLanguage == QuestionLanguage.ENGLISH) "question_en" else "question"
        var spec = template.databaseClient.sql(
            """
            select $questionColumn as localized_question from questions
            where $column = :id and deleted_at is null and lower(topic) = lower(:topic)
              $languageCondition
            order by created_at desc, id desc limit :limit offset :offset
            """.trimIndent(),
        ).bind("id", id).bind("topic", topic).bind("limit", pageable.pageSize).bind("offset", pageable.offset)
        if (normalizedLanguage != null && normalizedLanguage != QuestionLanguage.ENGLISH) {
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
            filter.second -> "(lower(q.topic) like :pattern or lower(q.question) like :pattern or lower(coalesce(q.question_en, '')) like :pattern or lower(coalesce(q.answer, '')) like :pattern or lower(coalesce(q.feedback, '')) like :pattern or lower(coalesce(q.explanation, '')) like :pattern or lower(u.display_name) like :pattern)"
            else -> "lower(q.topic) like :pattern"
        }
        var idsSpec = template.databaseClient.sql(publicSelectSql(condition, language != null))
            .bind("limit", pageable.pageSize).bind("offset", pageable.offset)
        var countSpec = template.databaseClient.sql(publicCountSql(condition, language != null))
        if (language != null) {
            idsSpec = idsSpec.bind("language", language)
            countSpec = countSpec.bind("language", language)
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

    private suspend fun publicIds(
        condition: String,
        value: Any,
        limit: Int,
        offset: Long,
        language: String? = null,
    ): List<Long> {
        var spec = template.databaseClient.sql(publicSelectSql(condition, language != null))
            .bind("value", value).bind("limit", limit).bind("offset", offset)
        if (language != null) spec = spec.bind("language", language)
        return spec
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
    }

    private fun publicSelectSql(condition: String, filterLanguage: Boolean = false): String {
        val languageCondition = if (filterLanguage) "and ${publicLanguageCondition()}" else ""
        return """
        select q.id from questions q join users u on u.id = q.user_id
        where q.is_public = true and q.deleted_at is null and q.score is not null
          and u.allow_public_questions = true $languageCondition and ($condition)
        order by q.created_at desc, q.id desc limit :limit offset :offset
        """.trimIndent()
    }

    private fun publicCountSql(condition: String, filterLanguage: Boolean = false): String {
        val languageCondition = if (filterLanguage) "and ${publicLanguageCondition()}" else ""
        return """
        select count(*) as total from questions q join users u on u.id = q.user_id
        where q.is_public = true and q.deleted_at is null and q.score is not null
          and u.allow_public_questions = true $languageCondition and ($condition)
        """.trimIndent()
    }

    private fun publicLanguageCondition(): String =
        "((:language = 'en' and q.translation_status = 'READY' and q.question_en is not null) " +
            "or (:language <> 'en' and q.language = :language))"

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
}
