package com.buddystudy.study.domain.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionStatusTest {
    @Test
    fun `completed statuses are exactly failed and graded`() {
        assertEquals(
            setOf(QuestionStatus.FAILED, QuestionStatus.GRADED),
            QuestionStatus.COMPLETED_STATUSES,
        )
    }

    @Test
    fun `only in-progress statuses prevent the next question`() {
        assertFalse(QuestionStatus.UNGRADED.allowsNextQuestion)
        assertFalse(QuestionStatus.GRADING.allowsNextQuestion)
        assertTrue(QuestionStatus.FAILED.allowsNextQuestion)
        assertTrue(QuestionStatus.GRADED.allowsNextQuestion)
        assertTrue(QuestionStatus.SKIPPED.allowsNextQuestion)
    }

    @Test
    fun `failed status preserves its lowercase database contract`() {
        assertEquals("failed", QuestionStatus.FAILED.databaseValue)
        assertEquals(QuestionStatus.FAILED, QuestionStatus.fromDatabaseValue("failed"))
    }
}
