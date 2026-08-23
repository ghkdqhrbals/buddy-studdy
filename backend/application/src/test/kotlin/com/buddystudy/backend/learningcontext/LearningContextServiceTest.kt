package com.buddystudy.backend.learningcontext

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.learningcontext.application.model.encodeInterests
import com.buddystudy.backend.learningcontext.application.port.outbound.LearningContextPort
import com.buddystudy.backend.learningcontext.application.service.LearningContextService
import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LearningContextServiceTest {
    private val contexts = InMemoryLearningContextPort()
    private val service = LearningContextService(contexts)
    private val principal = Principal(userId = 7, deviceId = "device-1", sessionId = 1, anonymous = false)

    @Test
    fun `missing context returns an empty response without creating storage`(): Unit = runBlocking {
        val response = service.get(principal)

        assertThat(response.resumeMarkdown).isNull()
        assertThat(response.interests).isEmpty()
        assertThat(response.updatedAt).isNull()
        assertThat(contexts.saveCount).isZero()
    }

    @Test
    fun `patch trims resume and normalizes and deduplicates interests`(): Unit = runBlocking {
        val response = service.patch(
            principal,
            LearningContextPatchCommand(
                resumeMarkdown = "  # Backend Engineer\n\nKotlin and Redis  ",
                interests = listOf("  Kotlin  ", "distributed\n systems", "kOtLiN", "", "   "),
            ),
        )

        assertThat(response.resumeMarkdown).isEqualTo("# Backend Engineer\n\nKotlin and Redis")
        assertThat(response.interests).containsExactly("Kotlin", "distributed systems")
        assertThat(contexts.rows.getValue(principal.userId).userId).isEqualTo(principal.userId)
        assertThat(contexts.saveCount).isEqualTo(1)
    }

    @Test
    fun `null patch fields preserve existing values without writing`(): Unit = runBlocking {
        val updatedAt = Instant.parse("2026-08-01T00:00:00Z")
        contexts.rows[principal.userId] = UserLearningContextEntity(
            userId = principal.userId,
            resumeMarkdown = "Existing resume",
            interestsJson = encodeInterests(listOf("Kotlin")),
            createdAt = updatedAt.minusSeconds(60),
            updatedAt = updatedAt,
        )

        val response = service.patch(principal, LearningContextPatchCommand())

        assertThat(response.resumeMarkdown).isEqualTo("Existing resume")
        assertThat(response.interests).containsExactly("Kotlin")
        assertThat(response.updatedAt).isEqualTo(updatedAt)
        assertThat(contexts.saveCount).isZero()
        assertThat(contexts.deleteCount).isZero()
    }

    @Test
    fun `blank resume deletes only the resume while null interests are preserved`(): Unit = runBlocking {
        contexts.rows[principal.userId] = context(
            resumeMarkdown = "Existing resume",
            interests = listOf("Kotlin", "Redis"),
        )

        val response = service.patch(
            principal,
            LearningContextPatchCommand(resumeMarkdown = " \n\t "),
        )

        assertThat(response.resumeMarkdown).isNull()
        assertThat(response.interests).containsExactly("Kotlin", "Redis")
        assertThat(contexts.rows).containsKey(principal.userId)
    }

    @Test
    fun `empty interests delete only interests while null resume is preserved`(): Unit = runBlocking {
        contexts.rows[principal.userId] = context(
            resumeMarkdown = "Existing resume",
            interests = listOf("Kotlin", "Redis"),
        )

        val response = service.patch(
            principal,
            LearningContextPatchCommand(interests = listOf("", "  ")),
        )

        assertThat(response.resumeMarkdown).isEqualTo("Existing resume")
        assertThat(response.interests).isEmpty()
        assertThat(contexts.rows).containsKey(principal.userId)
    }

    @Test
    fun `patch deletes storage when both resume and interests become empty`(): Unit = runBlocking {
        contexts.rows[principal.userId] = context(
            resumeMarkdown = "Existing resume",
            interests = listOf("Kotlin"),
        )

        val response = service.patch(
            principal,
            LearningContextPatchCommand(resumeMarkdown = "", interests = emptyList()),
        )

        assertThat(response.resumeMarkdown).isNull()
        assertThat(response.interests).isEmpty()
        assertThat(response.updatedAt).isNull()
        assertThat(contexts.rows).doesNotContainKey(principal.userId)
        assertThat(contexts.deleteCount).isEqualTo(1)
        assertThat(contexts.saveCount).isZero()
    }

    @Test
    fun `resume accepts exactly fifty thousand characters and rejects longer input`(): Unit = runBlocking {
        val accepted = service.patch(
            principal,
            LearningContextPatchCommand(resumeMarkdown = "x".repeat(50_000)),
        )
        assertThat(accepted.resumeMarkdown).hasSize(50_000)

        val failure = runCatching {
            service.patch(
                principal,
                LearningContextPatchCommand(resumeMarkdown = "x".repeat(50_001)),
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertThat(contexts.saveCount).isEqualTo(1)
    }

    @Test
    fun `interest accepts one hundred characters and rejects a longer normalized value`(): Unit = runBlocking {
        val accepted = service.patch(
            principal,
            LearningContextPatchCommand(interests = listOf("x".repeat(100))),
        )
        assertThat(accepted.interests.single()).hasSize(100)

        val failure = runCatching {
            service.patch(
                principal,
                LearningContextPatchCommand(interests = listOf(" x${"y".repeat(100)} ")),
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertThat(contexts.saveCount).isEqualTo(1)
    }

    @Test
    fun `interest count is enforced after case insensitive deduplication`(): Unit = runBlocking {
        val accepted = service.patch(
            principal,
            LearningContextPatchCommand(
                interests = List(50) { "Interest $it" } + listOf("interest 0", "INTEREST 1"),
            ),
        )
        assertThat(accepted.interests).hasSize(50)

        val failure = runCatching {
            service.patch(
                principal,
                LearningContextPatchCommand(interests = List(51) { "Unique $it" }),
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertThat(contexts.saveCount).isEqualTo(1)
    }

    private fun context(
        resumeMarkdown: String?,
        interests: List<String>,
    ) = UserLearningContextEntity(
        userId = principal.userId,
        resumeMarkdown = resumeMarkdown,
        interestsJson = encodeInterests(interests),
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

    private fun assertValidationFailure(failure: Throwable?) {
        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
    }

    private class InMemoryLearningContextPort : LearningContextPort {
        val rows = linkedMapOf<Long, UserLearningContextEntity>()
        var saveCount = 0
        var deleteCount = 0

        override suspend fun findByUserId(userId: Long): UserLearningContextEntity? = rows[userId]

        override suspend fun save(entity: UserLearningContextEntity): UserLearningContextEntity {
            saveCount += 1
            rows[entity.userId] = entity
            return entity
        }

        override suspend fun deleteByUserId(userId: Long): Long {
            deleteCount += 1
            return if (rows.remove(userId) == null) 0 else 1
        }
    }
}
