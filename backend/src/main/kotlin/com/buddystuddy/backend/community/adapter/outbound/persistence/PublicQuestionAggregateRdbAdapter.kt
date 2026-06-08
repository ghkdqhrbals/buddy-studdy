package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateQueryPort
import com.buddystuddy.backend.community.domain.PublicQuestionAggregate
import com.buddystuddy.backend.community.domain.PublicQuestionAuthorSnapshot
import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot
import com.buddystuddy.backend.domain.UserEntity
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import org.springframework.stereotype.Component

@Component("publicQuestionAggregateRdbAdapter")
class PublicQuestionAggregateRdbAdapter(
    private val questions: QuestionRepository,
    private val users: UserRepository,
    private val stats: QuestionStatsRepository,
) : PublicQuestionAggregateQueryPort {
    override fun findByQuestionId(questionId: Long): PublicQuestionSnapshot? {
        val question = questions.findPublicAnsweredById(questionId) ?: return null
        val author = question.userId?.let { users.findEntityById(it)?.toAuthorSnapshot() }
        return PublicQuestionAggregate.of(
            question = question,
            author = author,
            stats = stats.findEntityByQuestionId(question.id),
            likedByMe = false,
        ).snapshot()
    }
}

private fun UserEntity.toAuthorSnapshot() = PublicQuestionAuthorSnapshot(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    publicQuestionsAllowed = allowPublicQuestions,
)
