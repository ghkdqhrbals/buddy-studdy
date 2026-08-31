package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.config.selectPage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.query.Update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class StudyRepository(
    private val template: R2dbcEntityTemplate,
    private val properties: BuddyStudyProperties,
) : StudyPort {
    override suspend fun save(entity: StudyEntity): StudyEntity = template.saveEntity(entity, entity.id)

    override suspend fun lockMutationOwner(userId: Long): Boolean =
        template.databaseClient.sql("select id from users where id = :userId for update")
            .bind("userId", userId)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingleOrNull() != null

    override suspend fun updateTopicMetadata(
        id: Long,
        userId: Long,
        topic: String?,
        difficultyLevel: Int?,
        now: Instant,
    ): StudyEntity? {
        require(topic != null || difficultyLevel != null) { "A study metadata field is required." }
        require(topic == null || (topic.isNotBlank() && topic.length <= 255)) { "Invalid study topic." }
        require(difficultyLevel == null || difficultyLevel in 1..10) { "Invalid study difficulty." }
        var patch = Update.update("updated_at", now)
        topic?.let { patch = patch.set("topic", it) }
        difficultyLevel?.let { patch = patch.set("difficulty_level", it) }
        val changed = template.update(StudyEntity::class.java)
            .matching(Query.query(Criteria.where("id").`is`(id).and("user_id").`is`(userId)))
            .apply(patch).awaitSingle()
        // Repeating a patch is idempotent. Drivers may report changed rows rather
        // than matched rows, so still resolve the owned row after a zero count.
        check(changed in 0L..1L) { "A metadata patch affected an unexpected number of study rows." }
        return findByIdAndUserId(id, userId)
    }

    override suspend fun findSubtreeIdsForMutation(userId: Long, studyId: Long, limit: Int): List<Long>? {
        require(limit in 1..129) { "A bounded study subtree limit is required." }
        if (findByIdAndUserId(studyId, userId) == null) return emptyList()
        val seen = linkedSetOf(studyId)
        val parents = ArrayDeque<Long>().also { it.add(studyId) }
        // A bounded traversal avoids materializing an arbitrary recursive tree.
        // Read child ownership too: the FK cascade itself is not owner-filtered.
        while (parents.isNotEmpty() && seen.size < limit) {
            val children = template.databaseClient.sql(
                "select id, user_id from studies where parent_study_id = :parentId order by id limit :remaining for update",
            ).bind("parentId", parents.removeFirst())
                .bind("remaining", limit - seen.size)
                .map { row, _ ->
                    row.get("id", java.lang.Long::class.java)!!.toLong() to
                        row.get("user_id", java.lang.Long::class.java)!!.toLong()
                }.all().collectList().awaitSingle()
            children.forEach { (id, ownerId) ->
                if (ownerId != userId || !seen.add(id)) return null
                parents.add(id)
            }
        }
        return seen.toList()
    }

    suspend fun findById(id: Long): StudyEntity? =
        template.selectOne(Query.query(Criteria.where("id").`is`(id)), StudyEntity::class.java).awaitSingleOrNull()

    suspend fun findAll(): List<StudyEntity> =
        template.select(Query.empty(), StudyEntity::class.java).collectList().awaitSingle()

    suspend fun deleteAll(): Long = template.delete(StudyEntity::class.java).all().awaitSingle()

    override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long =
        template.delete(StudyEntity::class.java)
            .matching(Query.query(Criteria.where("id").`is`(id).and("user_id").`is`(userId)))
            .all()
            .awaitSingle()

    override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? =
        template.select(
            Query.query(Criteria.where("user_id").`is`(userId))
                .sort(Sort.by(Sort.Direction.DESC, "updated_at"))
                .limit(1),
            StudyEntity::class.java,
        ).next().awaitSingleOrNull()

    override suspend fun findFirstRootByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? =
        template.select(
            Query.query(
                Criteria.where("user_id").`is`(userId)
                    .and("parent_study_id").isNull,
            )
                .sort(
                    Sort.by(
                        Sort.Order.desc("updated_at"),
                        Sort.Order.desc("id"),
                    ),
                )
                .limit(1),
            StudyEntity::class.java,
        ).next().awaitSingleOrNull()

    override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
        template.selectOne(
            Query.query(Criteria.where("id").`is`(id).and("user_id").`is`(userId)),
            StudyEntity::class.java,
        ).awaitSingleOrNull()

    override suspend fun findByUserIdAndParentStudyIdAndTopic(
        userId: Long,
        parentStudyId: Long?,
        topic: String,
    ): StudyEntity? {
        var criteria = Criteria.where("user_id").`is`(userId).and("topic").`is`(topic)
        criteria = if (parentStudyId == null) {
            criteria.and("parent_study_id").isNull
        } else {
            criteria.and("parent_study_id").`is`(parentStudyId)
        }
        return template.selectOne(Query.query(criteria), StudyEntity::class.java).awaitSingleOrNull()
    }

    override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? =
        template.select(
            Query.query(
                Criteria.where("user_id").`is`(userId)
                    .and("parent_study_id").isNull
                    .and("topic").`is`(topic),
            ).sort(Sort.by(Sort.Direction.ASC, "id")).limit(1),
            StudyEntity::class.java,
        ).next().awaitSingleOrNull()

    override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> {
        if (topics.isEmpty()) return emptyList()
        return template.select(
            Query.query(
                Criteria.where("user_id").`is`(userId)
                    .and("parent_study_id").isNull
                    .and("topic").`in`(topics),
            ),
            StudyEntity::class.java,
        ).collectList().awaitSingle()
    }

    override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> {
        val criteria = Criteria.where("user_id").`is`(userId)
        val query = Query.query(criteria).sort(
            Sort.by(
                Sort.Order.asc("parent_study_id").nullsFirst(),
                Sort.Order.asc("sort_order"),
                Sort.Order.asc("id"),
            ),
        )
        return template.selectPage(query, Query.query(criteria), StudyEntity::class.java, pageable)
    }

    override suspend fun findAllByUserId(userId: Long): List<StudyEntity> =
        template.select(
            Query.query(Criteria.where("user_id").`is`(userId)).sort(
                Sort.by(
                    Sort.Order.asc("parent_study_id").nullsFirst(),
                    Sort.Order.asc("sort_order"),
                    Sort.Order.asc("id"),
                ),
            ),
            StudyEntity::class.java,
        ).collectList().awaitSingle()

    override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> {
        val pattern = "%${query.lowercase()}%"
        val search = Criteria.where("topic").like(pattern).ignoreCase(true)
            .or("custom_prompt").like(pattern).ignoreCase(true)
            .or("openai_model").like(pattern).ignoreCase(true)
        val criteria = Criteria.where("user_id").`is`(userId).and(search)
        val select = Query.query(criteria).sort(Sort.by(Sort.Direction.DESC, "updated_at"))
        return template.selectPage(select, Query.query(criteria), StudyEntity::class.java, pageable)
    }

    override suspend fun findByUserIdAndParentStudyId(
        userId: Long,
        parentStudyId: Long,
        query: String?,
        pageable: Pageable,
    ): Page<StudyEntity> {
        var criteria = Criteria.where("user_id").`is`(userId)
            .and("parent_study_id").`is`(parentStudyId)
        if (!query.isNullOrBlank()) {
            val pattern = "%${query.lowercase()}%"
            val search = Criteria.where("topic").like(pattern).ignoreCase(true)
                .or("custom_prompt").like(pattern).ignoreCase(true)
                .or("openai_model").like(pattern).ignoreCase(true)
            criteria = criteria.and(search)
        }
        val select = Query.query(criteria).sort(
            Sort.by(Sort.Order.asc("sort_order"), Sort.Order.asc("id")),
        )
        return template.selectPage(select, Query.query(criteria), StudyEntity::class.java, pageable)
    }

    @Transactional
    override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> {
        val claimUntil = now.plusSeconds(properties.scheduler.processingTimeoutSeconds.coerceIn(30, 3_600))
        val ids = template.databaseClient.sql(
            """
            select id from studies
            where parent_study_id is null
              and enabled = true and next_due_at is not null and next_due_at <= :now
              and (schedule_claimed_until is null or schedule_claimed_until <= :now)
            order by next_due_at asc, id asc
            limit :limit for update skip locked
            """.trimIndent(),
        ).bind("now", now).bind("limit", limit)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        if (ids.isEmpty()) return emptyList()
        template.update(StudyEntity::class.java)
            .matching(Query.query(Criteria.where("id").`in`(ids)))
            .apply(
                Update.update("schedule_claimed_until", claimUntil)
                    .set("updated_at", now),
            )
            .awaitSingle()
        val byId = template.select(Query.query(Criteria.where("id").`in`(ids)), StudyEntity::class.java)
            .collectList().awaitSingle().associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }
}
