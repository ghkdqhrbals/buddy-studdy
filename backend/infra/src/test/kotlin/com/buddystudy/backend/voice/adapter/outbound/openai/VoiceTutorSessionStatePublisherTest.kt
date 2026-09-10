package com.buddystudy.backend.voice.adapter.outbound.openai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorSessionStatePublisherTest {
    @Test
    fun `pause preserves exact answer stage and snapshots advance only for actual changes`() {
        val events = mutableListOf<Map<String, Any>>()
        val publisher = VoiceTutorSessionStatePublisher(2, 7) { events += it }
        publisher.start()
        publisher.update("answering", recordId = "42", answerId = ANSWER)
        publisher.pause(true)
        publisher.pause(true)
        publisher.update("answer_review")
        publisher.pause(false)
        assertThat(events.map { it["sequence"] }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(events.map { it["phase"] }).containsExactly("conversation", "answering", "answering", "answer_review", "answer_review")
        assertThat(events[2]).containsEntry("paused", true).containsEntry("answerId", ANSWER).containsEntry("recordId", "42")
        assertThat(events.last()).containsEntry("paused", false)
    }

    @Test
    fun `old revisions incomplete identities and late work after terminal cannot regress state`() {
        val events = mutableListOf<Map<String, Any>>()
        val publisher = VoiceTutorSessionStatePublisher(2, 7) { events += it }
        publisher.start()
        publisher.update("answering")
        publisher.update("grading")
        publisher.update("question_generating", revision = 1)
        publisher.update("answer_review", recordId = "42", answerId = "invalid")
        publisher.update("question_reading", studyId = null, recordId = "42")
        assertThat(events).hasSize(1)
        publisher.update("ending")
        publisher.update("question_generating", revision = 3)
        publisher.update("ended")
        publisher.update("conversation", revision = 3)
        publisher.pause(true)
        assertThat(events.map { it["phase"] }).containsExactly("conversation", "ending", "ended")
    }

    private companion object { const val ANSWER = "00112233-4455-6677-8899-aabbccddeeff" }
}
