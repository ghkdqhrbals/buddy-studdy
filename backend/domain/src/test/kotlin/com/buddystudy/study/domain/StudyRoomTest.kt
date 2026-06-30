package com.buddystudy.study.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudyRoomTest {
    @Test
    fun `room allows question creation while pending count is below the per-study limit`() {
        val room = StudyRoom.of(schedule(), pendingCount = 0)

        room.canCreateQuestion(maxPendingPerStudy = 1)
    }

    @Test
    fun `room rejects question creation once pending count reaches the per-study limit`() {
        val room = StudyRoom.of(schedule(), pendingCount = 1)

        val error = assertFailsWith<StudyRoomPendingLimitExceeded> {
            room.canCreateQuestion(maxPendingPerStudy = 1)
        }
        assertEquals("A pending question already exists for this study.", error.message)
    }

    @Test
    fun `created question draft preserves room settings and starts ungraded`() {
        val now = Instant.parse("2026-06-10T08:00:00Z")
        val room = StudyRoom.of(schedule(), pendingCount = 0)

        val draft = room.createQuestion(
            question = "What is backpressure?",
            hint = "Think about producer and consumer speed.",
            source = "scheduled",
            now = now,
        )

        assertEquals(10, draft.studyId)
        assertEquals("device-1", draft.deviceId)
        assertEquals(20, draft.userId)
        assertEquals("What is backpressure?", draft.question)
        assertEquals("Think about producer and consumer speed.", draft.hint)
        assertEquals("Distributed Systems", draft.topic)
        assertEquals(7, draft.difficultyLevel)
        assertEquals("ungraded", draft.status)
        assertEquals("scheduled", draft.source)
        assertTrue(draft.publicQuestion)
        assertEquals(now, draft.scheduledFor)
        assertEquals(now, draft.sentAt)
        assertEquals(now, draft.createdAt)
        assertEquals(now, draft.updatedAt)
    }

    private fun schedule() = StudyRoomSchedule(
        id = 10,
        deviceId = "device-1",
        userId = 20,
        topic = "Distributed Systems",
        difficultyLevel = 7,
        openaiModel = "gpt-5.4",
        appLanguage = "ko",
        customPrompt = "Ask practical production questions.",
    )
}
