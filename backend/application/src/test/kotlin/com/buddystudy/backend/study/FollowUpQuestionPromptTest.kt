package com.buddystudy.backend.study

import com.buddystudy.backend.stats.UserStatsRowBuilder
import com.buddystudy.backend.study.application.prompt.FollowUpQuestionPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class FollowUpQuestionPromptTest {
    @Test
    fun `prompt carries original answer grading and prior turn while keeping topic and difficulty`() {
        val first = QuestionEntity(question = "Original prompt", answer = "Original answer", topic = "Redis", difficultyLevel = 7, feedback = "Missing atomicity", explanation = "Atomic operations", gradingAssessmentJson = "{\"missing\":[\"atomicity\"]}")
        val second = QuestionEntity(question = "Earlier follow-up", answer = "Latest answer", feedback = "Apply this correctly")
        val prompt = FollowUpQuestionPrompt.build(QuestionPromptProvider(), listOf(first, second), "en")
        assertThat(prompt.fallbackTopic).isEqualTo("Redis")
        assertThat(prompt.level).isEqualTo(7)
        assertThat(prompt.language).isEqualTo("en")
        assertThat(prompt.userPrompt).contains("Original prompt", "Original answer", "Missing atomicity", "Earlier follow-up", "Latest answer", "atomicity")
        assertThat(prompt.systemPrompt).contains("untrusted learning content", "Do not quote personal details", "coached study session")
    }

    @Test
    fun `coached practice is excluded from ability buckets but independent answer remains`() {
        val now = Instant.parse("2026-09-24T00:00:00Z")
        val independent = QuestionEntity(id = 1, userId = 7, topic = "Redis", score = 40, answeredAt = now)
        val followUp = QuestionEntity(id = 2, userId = 7, topic = "Redis", score = 100, answeredAt = now, source = QuestionSource.FOLLOW_UP, followUpDepth = 1)
        val rows = UserStatsRowBuilder().build(listOf(independent, followUp), now)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().responseCount).isEqualTo(1)
        assertThat(rows.single().scoreSum).isEqualTo(40)
    }
}
