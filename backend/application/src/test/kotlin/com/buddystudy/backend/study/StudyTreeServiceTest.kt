package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyTopicActivationCommand
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.StudyTopicSuggestionPort
import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogCandidate
import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogPort
import com.buddystudy.backend.study.application.service.StudyTreeService
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.CancellationException
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
    private val suggestions = FakeSuggestionPort()
    private val catalog = FakeSystemTopicCatalogPort()
    private val service = StudyTreeService(studies, NoopUserPort(), suggestions, catalog)
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

    @Test
    fun `topic suggestions reuse the system catalog before calling AI`(): Unit = runBlocking {
        studies.rows += study(1, null, "Database")
        catalog.rows += SystemTopicCatalogCandidate("Indexes", 0)
        catalog.rows += SystemTopicCatalogCandidate("Transactions", 1)

        val response = service.suggestTopics(principal, parentStudyId = 1, count = 2)

        assertThat(response.suggestions).containsExactly("Indexes", "Transactions")
        assertThat(response.source).isEqualTo("CATALOG")
        assertThat(response.depth).isEqualTo(1)
        assertThat(response.maxDepth).isEqualTo(5)
        assertThat(response.childLimit).isEqualTo(10)
        assertThat(suggestions.calls).isZero()
    }

    @Test
    fun `missing catalog topics are generated once and saved for reuse`(): Unit = runBlocking {
        studies.rows += study(1, null, "Database")
        suggestions.next = listOf("Indexes", "Transactions")

        val response = service.suggestTopics(principal, parentStudyId = 1, count = 2)

        assertThat(response.suggestions).containsExactly("Indexes", "Transactions")
        assertThat(response.source).isEqualTo("GENERATED")
        assertThat(suggestions.calls).isEqualTo(1)
        assertThat(catalog.savedTopics).containsExactly("Indexes", "Transactions")
        assertThat(catalog.savedDepth).isEqualTo(1)
    }

    @Test
    fun `topic suggestions return a localized fallback when the provider fails`(): Unit = runBlocking {
        studies.rows += study(1, null, "Database")
        suggestions.failure = IllegalStateException("OpenAI unavailable")

        val response = service.suggestTopics(principal, parentStudyId = 1, count = 3)

        assertThat(response.suggestions).containsExactly(
            "Database · 기초",
            "Database · 핵심 개념",
            "Database · 실전",
        )
        assertThat(response.source).isEqualTo("FALLBACK")
        assertThat(suggestions.calls).isEqualTo(1)
        assertThat(catalog.savedTopics).isEmpty()
    }

    @Test
    fun `provider failure keeps cached topics and fills only the missing suggestions`(): Unit = runBlocking {
        studies.rows += study(1, null, "Database")
        catalog.rows += SystemTopicCatalogCandidate("Indexes", 0)
        suggestions.failure = IllegalStateException("OpenAI unavailable")

        val response = service.suggestTopics(principal, parentStudyId = 1, count = 3)

        assertThat(response.suggestions).containsExactly(
            "Indexes",
            "Database · 기초",
            "Database · 핵심 개념",
        )
        assertThat(response.source).isEqualTo("CATALOG_FALLBACK")
        assertThat(catalog.savedTopics).isEmpty()
    }

    @Test
    fun `topic suggestion cancellation is not converted into a fallback response`() {
        studies.rows += study(1, null, "Database")
        suggestions.failure = CancellationException("request cancelled")

        assertThatThrownBy {
            runBlocking { service.suggestTopics(principal, parentStudyId = 1, count = 3) }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `topic suggestions stop after five descendant levels`(): Unit = runBlocking {
        studies.rows += study(1, null, "Root")
        studies.rows += study(2, 1, "One")
        studies.rows += study(3, 2, "Two")
        studies.rows += study(4, 3, "Three")
        studies.rows += study(5, 4, "Four")
        studies.rows += study(6, 5, "Five")

        val response = service.suggestTopics(principal, parentStudyId = 6, count = 10)

        assertThat(response.suggestions).isEmpty()
        assertThat(response.source).isEqualTo("DEPTH_LIMIT")
        assertThat(suggestions.calls).isZero()
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

    private class FakeSuggestionPort : StudyTopicSuggestionPort {
        var next: List<String> = emptyList()
        var failure: RuntimeException? = null
        var calls = 0

        override suspend fun suggestTopics(
            rootTopic: String,
            parentTopic: String,
            existingTopics: Collection<String>,
            language: String,
            count: Int,
        ): List<String> {
            calls += 1
            failure?.let { throw it }
            return next.take(count)
        }
    }

    private class FakeSystemTopicCatalogPort : SystemTopicCatalogPort {
        val rows = mutableListOf<SystemTopicCatalogCandidate>()
        var savedTopics: List<String> = emptyList()
        var savedDepth: Int? = null

        override suspend fun findChildren(
            rootTopicKey: String,
            parentPathKey: String,
            language: String,
            depth: Int,
            limit: Int,
        ): List<SystemTopicCatalogCandidate> = rows.take(limit)

        override suspend fun saveChildren(
            rootTopicKey: String,
            parentPathKey: String,
            language: String,
            depth: Int,
            topics: List<String>,
            now: Instant,
        ) {
            savedTopics = topics
            savedDepth = depth
        }
    }
}
