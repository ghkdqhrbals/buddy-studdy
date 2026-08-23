package com.buddystudy.backend.study

import com.buddystudy.backend.study.application.service.StudyTreeSelector
import com.buddystudy.backend.study.application.service.advanceScheduledRotation
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StudyTreeSelectorTest {
    @Test
    fun `finds root through an arbitrarily deep parent chain`() {
        val root = study(1, null, "Redis")
        val streams = study(2, 1, "Streams")
        val consumerGroups = study(3, 2, "Consumer Groups")

        assertThat(StudyTreeSelector.rootFor(consumerGroups, listOf(root, streams, consumerGroups))).isSameAs(root)
    }

    @Test
    fun `rotates active topics by oldest selection and ignores inactive topics`() {
        val root = study(1, null, "Redis").apply {
            activeForQuestions = true
            lastSentAt = Instant.parse("2026-07-26T02:00:00Z")
        }
        val streams = study(2, 1, "Streams").apply {
            activeForQuestions = true
            lastSentAt = Instant.parse("2026-07-26T01:00:00Z")
        }
        val cluster = study(3, 1, "Cluster").apply {
            activeForQuestions = false
            lastSentAt = null
        }

        assertThat(StudyTreeSelector.nextActiveTopic(root, listOf(root, streams, cluster))).isSameAs(streams)
    }

    @Test
    fun `never selected active topic is chosen first`() {
        val root = study(1, null, "Redis").apply {
            activeForQuestions = true
            lastSentAt = Instant.parse("2026-07-26T01:00:00Z")
        }
        val streams = study(2, 1, "Streams").apply {
            activeForQuestions = true
            lastSentAt = null
        }

        assertThat(StudyTreeSelector.nextActiveTopic(root, listOf(root, streams))).isSameAs(streams)
    }

    @Test
    fun `scheduled rotation visits every active topic including the root before repeating`() {
        val root = study(1, null, "Backend").apply { sortOrder = 0 }
        val redis = study(2, 1, "Redis").apply { sortOrder = 1 }
        val mysql = study(3, 1, "MySQL").apply { sortOrder = 2 }
        val all = listOf(root, redis, mysql)
        val startedAt = Instant.parse("2026-07-30T00:00:00Z")

        val selectedIds = (0 until 6).map { index ->
            val selected = checkNotNull(StudyTreeSelector.nextActiveTopic(root, all))
            root.advanceScheduledRotation(selected, startedAt.plusSeconds(index * 60L))
            selected.id
        }

        assertThat(selectedIds).containsExactly(1, 2, 3, 1, 2, 3)
    }

    @Test
    fun `selecting a child advances the root schedule without changing the root rotation timestamp`() {
        val rootLastSelectedAt = Instant.parse("2026-07-29T23:00:00Z")
        val root = study(1, null, "Backend").apply {
            lastSentAt = rootLastSelectedAt
            intervalMinutes = 15
        }
        val redis = study(2, 1, "Redis")
        val requestedAt = Instant.parse("2026-07-30T00:00:00Z")

        root.advanceScheduledRotation(redis, requestedAt)

        assertThat(root.lastSentAt).isEqualTo(rootLastSelectedAt)
        assertThat(redis.lastSentAt).isEqualTo(requestedAt)
        assertThat(root.nextDueAt).isEqualTo(requestedAt.plusSeconds(15 * 60))
    }

    private fun study(id: Long, parentId: Long?, topic: String) = StudyEntity(
        id = id,
        userId = 7,
        parentStudyId = parentId,
        topic = topic,
    )
}
