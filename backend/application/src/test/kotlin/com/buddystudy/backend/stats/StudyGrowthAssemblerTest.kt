package com.buddystudy.backend.stats

import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthRecord
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StudyGrowthAssemblerTest {
    private val assembler = StudyGrowthAssembler()
    private val startAt = Instant.parse("2026-01-01T00:00:00Z")
    private val endAt = Instant.parse("2026-04-01T00:00:00Z")

    @Test
    fun `growth keeps tree identity and compares non-overlapping windows`() {
        val studies = listOf(
            study(id = 1, topic = "Database"),
            study(id = 2, parentId = 1, topic = "SQL", sortOrder = 0),
            study(id = 3, parentId = 1, topic = "Index", sortOrder = 1),
        )
        val sqlRecords = (0 until 6).map { index ->
            record(
                studyId = 2,
                difficulty = 5,
                score = if (index < 3) 50 else 80,
                day = index + 1,
            )
        }
        val indexRecords = listOf(
            record(studyId = 3, difficulty = 7, score = 50, day = 7),
            record(studyId = 3, difficulty = 7, score = 50, day = 8),
        )

        val response = assembler.assemble(
            studies = studies,
            records = sqlRecords + indexRecords,
            startAt = startAt,
            endAt = endAt,
            generatedAt = endAt,
        )

        val root = response.roots.single()
        val sql = response.nodes.single { it.studyId == 2L }
        assertThat(root.studyId).isEqualTo(1)
        assertThat(root.answerCount).isEqualTo(8)
        assertThat(root.measuredTopicCount).isEqualTo(1)
        assertThat(root.totalTopicCount).isEqualTo(3)
        assertThat(root.growth).isCloseTo(1.0, within(0.0001))
        assertThat(root.profile.achievement).isCloseTo(0.6125, within(0.0001))
        assertThat(root.profile.challenge).isCloseTo(0.55, within(0.0001))
        assertThat(root.profile.completion).isEqualTo(1.0)
        assertThat(root.profile.breadth).isCloseTo(2.0 / 3.0, within(0.0001))
        assertThat(root.profile.depth).isEqualTo(1.0)
        assertThat(sql.parentStudyId).isEqualTo(1)
        assertThat(sql.rootStudyId).isEqualTo(1)
        assertThat(sql.depth).isEqualTo(1)
        assertThat(sql.currentLevel).isCloseTo(6.0, within(0.0001))
        assertThat(sql.previousLevel).isCloseTo(5.0, within(0.0001))
        assertThat(sql.growth).isCloseTo(1.0, within(0.0001))
    }

    @Test
    fun `fewer than six answers shows current estimate without claiming growth`() {
        val response = assembler.assemble(
            studies = listOf(study(id = 10, topic = "Swift")),
            records = (1..5).map { day ->
                record(studyId = 10, difficulty = 4, score = 80, day = day)
            },
            startAt = startAt,
            endAt = endAt,
            generatedAt = endAt,
        )

        val root = response.roots.single()
        assertThat(root.currentLevel).isCloseTo(5.0, within(0.0001))
        assertThat(root.growth).isNull()
        assertThat(root.measuredTopicCount).isZero()
    }

    @Test
    fun `profile includes unfinished questions in completion without treating them as answers`() {
        val answered = record(studyId = 20, difficulty = 8, score = 90, day = 1)
        val unfinished = record(studyId = 20, difficulty = 3, score = 0, day = 2)
            .copy(completed = false)

        val response = assembler.assemble(
            studies = listOf(study(id = 20, topic = "Architecture")),
            records = listOf(answered, unfinished),
            startAt = startAt,
            endAt = endAt,
            generatedAt = endAt,
        )

        val root = response.roots.single()
        assertThat(root.answerCount).isEqualTo(1)
        assertThat(root.profile.achievement).isEqualTo(0.9)
        assertThat(root.profile.challenge).isEqualTo(0.8)
        assertThat(root.profile.completion).isEqualTo(0.5)
    }

    private fun study(
        id: Long,
        topic: String,
        parentId: Long? = null,
        sortOrder: Int = 0,
    ) = StudyEntity(
        id = id,
        userId = 7,
        parentStudyId = parentId,
        sortOrder = sortOrder,
        topic = topic,
    )

    private fun record(
        studyId: Long,
        difficulty: Int,
        score: Int,
        day: Int,
    ) = StudyGrowthRecord(
        studyId = studyId,
        difficultyLevel = difficulty,
        score = score,
        answeredAt = Instant.parse("2026-01-${day.toString().padStart(2, '0')}T00:00:00Z"),
    )

    private fun within(value: Double) = org.assertj.core.data.Offset.offset(value)
}
