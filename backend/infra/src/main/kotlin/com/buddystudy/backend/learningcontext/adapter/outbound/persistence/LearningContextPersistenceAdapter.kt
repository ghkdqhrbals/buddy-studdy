package com.buddystudy.backend.learningcontext.adapter.outbound.persistence

import com.buddystudy.backend.learningcontext.application.port.outbound.LearningContextPort
import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.query.Update
import org.springframework.stereotype.Component

@Component
class LearningContextPersistenceAdapter(
    private val template: R2dbcEntityTemplate,
) : LearningContextPort {
    override suspend fun findByUserId(userId: Long): UserLearningContextEntity? =
        template.selectOne(
            query(userId),
            UserLearningContextEntity::class.java,
        ).awaitSingleOrNull()

    override suspend fun save(entity: UserLearningContextEntity): UserLearningContextEntity {
        if (updateExisting(entity) > 0) {
            return entity
        }

        return try {
            template.insert(entity).awaitSingle()
        } catch (duplicate: DuplicateKeyException) {
            if (updateExisting(entity) == 0L) {
                throw duplicate
            }
            entity
        }
    }

    override suspend fun deleteByUserId(userId: Long): Long =
        template.delete(UserLearningContextEntity::class.java)
            .matching(query(userId))
            .all()
            .awaitSingle()

    private suspend fun updateExisting(entity: UserLearningContextEntity): Long =
        template.update(UserLearningContextEntity::class.java)
            .matching(query(entity.userId))
            .apply(
                Update.update("resume_markdown", entity.resumeMarkdown)
                    .set("interests_json", entity.interestsJson)
                    .set("updated_at", entity.updatedAt),
            )
            .awaitSingle()

    private fun query(userId: Long): Query =
        Query.query(Criteria.where("user_id").`is`(userId))
}
