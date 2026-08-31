package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorLessonFocusIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorLessonFocusIndexTest {
    @Test
    fun `discovery never gains a focus retroactively and intervening metadata epochs retain the earlier focus`() {
        val first = VoiceTutorLessonFocus(11, 1)
        val second = VoiceTutorLessonFocus(90, 3)
        val index = VoiceTutorLessonFocusIndex(listOf(first, second))

        assertThat(index.at(-1)).isNull()
        assertThat(index.at(0)).isNull()
        assertThat(index.at(1)).isEqualTo(first)
        assertThat(index.at(2)).isEqualTo(first)
        assertThat(index.at(3)).isEqualTo(second)
        assertThat(index.current).isEqualTo(second)
    }

    @Test
    fun `original accepted identity remains legacy epoch zero while explicit selection becomes authoritative afterwards`() {
        val index = VoiceTutorLessonFocusIndex(listOf(VoiceTutorLessonFocus(90, 2)), acceptedStudyId = 10)

        assertThat(index.at(0)).isEqualTo(VoiceTutorLessonFocus(10, 0))
        assertThat(index.at(1)).isEqualTo(VoiceTutorLessonFocus(10, 0))
        assertThat(index.at(2)).isEqualTo(VoiceTutorLessonFocus(90, 2))
    }

    @Test
    fun `ambiguous or invalid newer focus cannot fall back to a previously allowed tree`() {
        for (invalid in listOf(
            listOf(VoiceTutorLessonFocus(11, 1), VoiceTutorLessonFocus(90, 1)),
            listOf(VoiceTutorLessonFocus(0, 1)),
        )) {
            val index = VoiceTutorLessonFocusIndex(invalid, acceptedStudyId = 10)
            assertThat(index.at(0)).isEqualTo(VoiceTutorLessonFocus(10, 0))
            assertThat(index.at(1)).isNull()
            assertThat(index.at(2)).isNull()
            assertThat(index.current).isNull()
        }
    }
}
