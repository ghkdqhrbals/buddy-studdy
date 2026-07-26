package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyTopicActivationCommand
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.StudyTopicSuggestionPort
import com.buddystudy.backend.study.application.service.StudyTreeService
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant

class StudyTreeServiceTest {
    private val studies = FakeStudyPort()
    private val service = StudyTreeService(studies, NoopUserPort(), NoopSuggestionPort())
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `activating a descendant resumes its root schedule`(): Unit = runBlocking {
        val root = study(1, null, "Redis").apply {
            enabled = false
            activeForQuestions = false
            nextDueAt = null
            lastError = "Monthly question limit reached."
        }
        val child = study(2, 1, "Streams").apply {
            activeForQuestions = false
        }
        studies.rows += listOf(root, child)

        val response = service.updateTopicActivation(
            principal,
            studyId = child.id,
            command = UpdateStudyTopicActivationCommand(active = true),
        )

        assertThat(response.activeForQuestions).isTrue()
        assertThat(root.enabled).isTrue()
        assertThat(root.nextDueAt).isNotNull()
        assertThat(root.lastError).isNull()
        assertThat(studies.saved.map { it.id }).containsExactly(child.id, root.id)
    }

    @Test
    fun `the final active topic cannot be deactivated`(): Unit = runBlocking {
        val root = study(1, null, "Redis").apply {
            enabled = true
            activeForQuestions = false
            nextDueAt = Instant.now()
        }
        val child = study(2, 1, "Streams").apply {
            activeForQuestions = true
        }
        studies.rows += listOf(root, child)

        assertThatThrownBy {
            runBlocking {
                service.updateTopicActivation(
                    principal,
                    studyId = child.id,
                    command = UpdateStudyTopicActivationCommand(active = false),
                )
            }
        }.isInstanceOf(ApiException::class.java)
        assertThat(child.activeForQuestions).isTrue()
        assertThat(studies.saved).isEmpty()
    }

    private fun study(id: Long, parentId: Long?, topic: String) = StudyEntity(
        id = id,
        userId = 7,
        deviceId = "dev-1",
        parentStudyId = parentId,
        topic = topic,
    )

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        val saved = mutableListOf<StudyEntity>()

        override suspend fun save(entity: StudyEntity): StudyEntity {
            saved += entity
            return entity
        }

        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long = 0
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = rows.firstOrNull()
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId }

        override suspend fun findByUserIdAndParentStudyIdAndTopic(
            userId: Long,
            parentStudyId: Long?,
            topic: String,
        ): StudyEntity? = rows.firstOrNull {
            it.userId == userId && it.parentStudyId == parentStudyId && it.topic == topic
        }

        override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? =
            rows.firstOrNull { it.userId == userId && it.topic == topic }

        override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
            rows.filter { it.userId == userId && it.topic in topics }

        override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId })

        override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId && it.topic.contains(query) })

        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class NoopUserPort : UserPort {
        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? = null
        override suspend fun findAllById(ids: Iterable<Long>): List<UserEntity> = emptyList()
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class NoopSuggestionPort : StudyTopicSuggestionPort {
        override suspend fun suggestTopics(
            rootTopic: String,
            parentTopic: String,
            existingTopics: Collection<String>,
            language: String,
            count: Int,
        ): List<String> = emptyList()
    }
}
