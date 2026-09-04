package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorRealtimeMetricsTest {
    @Test
    fun `local VAD stop measures response latency without provider VAD events`() {
        val registry = SimpleMeterRegistry()
        var now = 0L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 1)
        now += 1_000_000_000
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        now += 125_000_000
        tracker.observeProviderEvent("""{"type":"response.created","response":{"id":"resp-local"}}""")

        val timer = registry.get("buddystudy.voice_tutor.turn_to_response").timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(125.0)
    }

    @Test
    fun `local VAD duplicates stale stops and provider VAD cannot move the stop clock`() {
        val registry = SimpleMeterRegistry()
        var now = 0L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 0)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 0)
        tracker.observeProviderEvent("""{"type":"response.created","response":{"id":"resp-opening"}}""")
        assertThat(registry.get("buddystudy.voice_tutor.turn_to_response").timer().count()).isZero()

        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 1)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 1)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 2)
        now += 1_000_000_000
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        now += 50_000_000
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 1)
        tracker.observeProviderEvent("""{"type":"input_audio_buffer.speech_stopped"}""")
        now += 50_000_000
        repeat(2) {
            tracker.observeProviderEvent("""{"type":"response.created","response":{"id":"resp-local"}}""")
        }

        val timer = registry.get("buddystudy.voice_tutor.turn_to_response").timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(100.0)
    }

    @Test
    fun `resumed speech replaces an earlier quiet boundary only after its matching stop`() {
        val registry = SimpleMeterRegistry()
        var now = 0L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 1)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        now += 50_000_000
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, 2)
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 1)
        // An already queued response is not evidence that this active turn ended.
        tracker.observeProviderEvent("""{"type":"response.created","response":{"id":"resp-old"}}""")
        assertThat(registry.get("buddystudy.voice_tutor.turn_to_response").timer().count()).isZero()
        now += 1_000_000_000
        tracker.observeClientSpeechEvent(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, 2)
        now += 75_000_000
        tracker.observeProviderEvent("""{"type":"response.created","response":{"id":"resp-new"}}""")

        val timer = registry.get("buddystudy.voice_tutor.turn_to_response").timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(75.0)
    }

    @Test
    fun `WebRTC latency is measured from learner turn through audible device drain`() {
        val registry = SimpleMeterRegistry()
        var now = 0L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeProviderEvent("""{"type":"input_audio_buffer.speech_stopped"}""")
        now += 100_000_000
        tracker.observeProviderEvent(
            """{"type":"response.created","response":{"id":"resp-1"}}""",
        )
        now += 250_000_000
        tracker.observeProviderEvent(
            """{"type":"output_audio_buffer.started","response_id":"resp-1"}""",
        )
        now += 1_000_000_000
        tracker.observeProviderEvent(
            """{"type":"output_audio_buffer.stopped","response_id":"resp-1"}""",
        )
        now += 80_000_000
        tracker.observeDevicePlayoutDrained("resp-1")

        assertThat(registry.get("buddystudy.voice_tutor.turn_to_response").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(100.0)
        assertThat(registry.get("buddystudy.voice_tutor.response_to_first_audio").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(250.0)
        assertThat(registry.get("buddystudy.voice_tutor.server_stop_to_device_drain").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(80.0)
    }

    @Test
    fun `unexpected WebRTC buffer clear is counted as a sentence integrity violation`() {
        val registry = SimpleMeterRegistry()
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker()

        tracker.observeProviderEvent(
            """{"type":"output_audio_buffer.cleared","response_id":"resp-1"}""",
        )

        assertThat(registry.get("buddystudy.voice_tutor.unexpected_output_buffer_clears").counter().count())
            .isEqualTo(1.0)
    }

    @Test
    fun `duplicate provider boundaries do not double count WebRTC latency`() {
        val registry = SimpleMeterRegistry()
        var now = 0L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeProviderEvent("""{"type":"input_audio_buffer.speech_stopped"}""")
        now += 10_000_000
        repeat(2) {
            tracker.observeProviderEvent(
                """{"type":"response.created","response":{"id":"resp-1"}}""",
            )
        }
        now += 20_000_000
        repeat(2) {
            tracker.observeProviderEvent(
                """{"type":"output_audio_buffer.started","response_id":"resp-1"}""",
            )
        }
        now += 30_000_000
        repeat(2) {
            tracker.observeProviderEvent(
                """{"type":"output_audio_buffer.stopped","response_id":"resp-1"}""",
            )
        }
        now += 40_000_000
        repeat(2) { tracker.observeDevicePlayoutDrained("resp-1") }

        assertThat(registry.get("buddystudy.voice_tutor.turn_to_response").timer().count()).isEqualTo(1)
        assertThat(registry.get("buddystudy.voice_tutor.response_to_first_audio").timer().count()).isEqualTo(1)
        assertThat(registry.get("buddystudy.voice_tutor.server_stop_to_device_drain").timer().count()).isEqualTo(1)
        assertThat(
            registry.get("buddystudy.voice_tutor.server_stop_to_device_drain").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS),
        ).isEqualTo(40.0)
    }

    @Test
    fun `device drain before provider stop completes the metric once without negative latency`() {
        val registry = SimpleMeterRegistry()
        var now = 50_000_000L
        val tracker = VoiceTutorRealtimeMetrics(registry).webRtcTracker { now }

        tracker.observeDevicePlayoutDrained("resp-early")
        now += 25_000_000
        tracker.observeProviderEvent(
            """{"type":"output_audio_buffer.stopped","response_id":"resp-early"}""",
        )
        tracker.observeProviderEvent(
            """{"type":"output_audio_buffer.stopped","response_id":"resp-early"}""",
        )

        val timer = registry.get("buddystudy.voice_tutor.server_stop_to_device_drain").timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isZero()
    }
}
