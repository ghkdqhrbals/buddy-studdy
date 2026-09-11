package com.buddystudy.backend.voice.adapter.outbound.openai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class VoiceTutorRealtimeUsageTrackerTest {
    private var now = 0L
    private val rows = mutableListOf<VoiceTutorRealtimeUsage>()
    private val tracker = VoiceTutorRealtimeUsageTracker("private-call", "gpt-realtime-2.1", "gpt-4o-mini-transcribe",
        nanoTime = { now }, emit = rows::add)

    @Test
    fun `cancelled response keeps billed usage and duplicates count once`() {
        tracker.observe("""{"type":"response.created","response":{"id":"r1","metadata":{"buddystudy_usage_operation":"voice-question-readback"}}}""")
        now = 25_000_000
        val done = """{"type":"response.done","response":{"id":"r1","status":"cancelled","usage":{"input_tokens":100,"output_tokens":20,"input_token_details":{"cached_tokens":60,"audio_tokens":30}}}}"""
        tracker.observe(done)
        tracker.observe(done)
        tracker.close()
        assertThat(rows).hasSize(1)
        rows.single().let {
            assertThat(it.stage).isEqualTo("question-readback")
            assertThat(it.outcome).isEqualTo("cancelled")
            assertThat(it.durationMs).isEqualTo(25)
            assertThat(it.usage!!.path("input_tokens").asInt()).isEqualTo(100)
            assertThat(it.usage.path("input_token_details").path("cached_tokens").asInt()).isEqualTo(60)
            assertThat(it.eventRef).matches("[0-9a-f]{16}")
        }
    }

    @Test
    fun `disconnect reports pending calls as unknown usage exactly once`() {
        tracker.observe("""{"type":"response.created","response":{"id":"r1"}}""")
        now = 12_000_000
        tracker.close()
        tracker.close()
        tracker.observe("""{"type":"response.created","response":{"id":"late"}}""")
        assertThat(rows).hasSize(1)
        assertThat(rows.single().outcome).isEqualTo("disconnected")
        assertThat(rows.single().usage).isNull()
        assertThat(rows.single().durationMs).isEqualTo(12)
    }

    @Test
    fun `done without created still records known usage and stable reattachment identity`() {
        val done = """{"type":"response.done","response":{"id":"r1","status":"incomplete","usage":{"input_tokens":3}}}"""
        tracker.observe(done)
        val otherRows = mutableListOf<VoiceTutorRealtimeUsage>()
        val reattached = VoiceTutorRealtimeUsageTracker("private-call", "gpt-realtime-2.1", "gpt-4o-mini-transcribe", emit = otherRows::add)
        reattached.observe(done)
        assertThat(rows.single().durationMs).isNull()
        assertThat(rows.single().outcome).isEqualTo("incomplete")
        assertThat(rows.single().eventRef).isEqualTo(otherRows.single().eventRef)
    }

    @Test
    fun `transcription has separate model usage and identity per content part`() {
        val completed = """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item1","content_index":0,"transcript":"must not be logged","usage":{"type":"tokens","input_tokens":17,"output_tokens":9}}"""
        tracker.observe(completed)
        tracker.observe(completed)
        tracker.observe("""{"type":"conversation.item.input_audio_transcription.failed","item_id":"item1","content_index":1,"error":{"message":"private"}}""")
        assertThat(rows).hasSize(2)
        assertThat(rows.first().model).isEqualTo("gpt-4o-mini-transcribe")
        assertThat(rows.first().stage).isEqualTo("transcription")
        assertThat(rows.first().usage.toString()).doesNotContain("must not be logged")
        assertThat(rows.last().outcome).isEqualTo("failed")
        assertThat(rows.last().eventRef).isNotEqualTo(rows.first().eventRef)
    }

    @Test
    fun `malformed untrusted events and sink errors cannot break conversation`() {
        listOf("not json", "{}", "[]", "null",
            """{"type":"response.done","response":{"id":7}}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item1","content_index":-1}""",
        ).forEach(tracker::observe)
        assertThat(rows).isEmpty()
        val brokenSink = VoiceTutorRealtimeUsageTracker("call", "model", "transcribe", emit = { error("log sink unavailable") })
        brokenSink.observe("""{"type":"response.created","response":{"id":"r1"}}""")
        brokenSink.close()
    }
}
