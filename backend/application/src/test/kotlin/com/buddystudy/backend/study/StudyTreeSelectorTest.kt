package com.buddystudy.backend.study

import com.buddystudy.backend.study.application.service.StudyTreeSelector
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

    private fun study(id: Long, parentId: Long?, topic: String) = StudyEntity(
        id = id,
        userId = 7,
        parentStudyId = parentId,
        topic = topic,
    )
}
