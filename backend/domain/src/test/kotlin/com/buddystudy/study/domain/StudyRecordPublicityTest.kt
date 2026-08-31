package com.buddystudy.study.domain

import com.buddystudy.study.domain.entity.StudyRecordType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class StudyRecordPublicityTest {
    @Test
    fun `an answered completed voice exchange can be shared without a score`() {
        val record = StudyRecord.of(voiceState())
        assertTrue(record.restrictPublicity(true).publicQuestion)
        assertFalse(record.restrictPublicity(false).publicQuestion)
        assertEquals(StudyRecordType.VOICE_TUTOR, record.toProjection().recordType)
        assertEquals("900", record.toProjection().id)
        assertNull(record.toProjection().score)
    }

    @Test
    fun `voice without a completed exchange or extension cannot be shared`() {
        listOf(voiceState().copy(answer = null), voiceState().copy(answer = " "),
            voiceState().copy(question = " "), voiceState().copy(questionStatus = "ungraded"),
            voiceState().copy(voiceRecordId = null)).forEach {
            assertFalse(StudyRecord.of(it).restrictPublicity(true).publicQuestion)
        }
    }

    @Test
    fun `ordinary question publication still requires its actual grading result`() {
        val ordinary = voiceState().copy(recordType = StudyRecordType.QUESTION, voiceRecordId = null)
        assertFalse(StudyRecord.of(ordinary).restrictPublicity(true).publicQuestion)
        assertTrue(StudyRecord.of(ordinary.copy(score = 81)).restrictPublicity(true).publicQuestion)
    }

    private fun voiceState() = StudyRecordState(
        id = 900, question = "캐시가 뭔가요?", hint = null, createdAt = Instant.EPOCH, answer = "임시 저장입니다.",
        score = null, correct = null, feedback = null, explanation = null, topic = "Redis", difficultyLevel = 3,
        answeredAt = Instant.EPOCH, publicQuestion = false, questionStatus = "completed",
        recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = 7,
    )
}
