package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class VoiceTutorRealtimeMetrics(
    private val registry: MeterRegistry,
) {
    fun webRtcTracker(nanoTime: () -> Long = System::nanoTime): WebRtcTracker = WebRtcTracker(
        turnToResponse = registry.timer("buddystudy.voice_tutor.turn_to_response", "transport", "webrtc"),
        responseToFirstAudio = registry.timer("buddystudy.voice_tutor.response_to_first_audio", "transport", "webrtc"),
        serverStopToDeviceDrain = registry.timer(
            "buddystudy.voice_tutor.server_stop_to_device_drain",
            "transport",
            "webrtc",
        ),
        unexpectedClears = registry.counter(
            "buddystudy.voice_tutor.unexpected_output_buffer_clears",
            "transport",
            "webrtc",
        ),
        nanoTime = nanoTime,
    )

    class WebRtcTracker internal constructor(
        private val turnToResponse: Timer,
        private val responseToFirstAudio: Timer,
        private val serverStopToDeviceDrain: Timer,
        private val unexpectedClears: Counter,
        private val nanoTime: () -> Long,
    ) {
        private var lastSpeechStoppedNanos: Long? = null
        private var lastClientSpeechSequence = 0L
        private var activeClientSpeechSequence: Long? = null
        private val responseCreatedNanos = linkedMapOf<String, Long>()
        private val firstAudioResponseIds = linkedSetOf<String>()
        private val outputStoppedNanos = linkedMapOf<String, Long>()
        private val deviceDrainedNanos = linkedMapOf<String, Long>()
        private val completedResponseIds = linkedSetOf<String>()

        /** Observe validated local-VAD controls, without retaining any audio or transcript. */
        @Synchronized
        fun observeClientSpeechEvent(type: String, sequence: Long) {
            if (sequence <= 0) return
            when (type) {
                VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT -> {
                    if (sequence <= lastClientSpeechSequence) return
                    lastClientSpeechSequence = sequence
                    // A resumed learner turn supersedes the earlier quiet boundary.
                    // Overlapping/stale controls never replace an active pair.
                    if (activeClientSpeechSequence != null) return
                    activeClientSpeechSequence = sequence
                    lastSpeechStoppedNanos = null
                }
                VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT -> {
                    if (activeClientSpeechSequence != sequence) return
                    activeClientSpeechSequence = null
                    lastSpeechStoppedNanos = nanoTime()
                }
            }
        }

        @Synchronized
        fun observeProviderEvent(raw: String) {
            val node = runCatching { JsonMapperProvider.mapper.readTree(raw) }.getOrNull() ?: return
            val now = nanoTime()
            when (node.path("type").asText()) {
                // Legacy diagnostics may still provide server VAD boundaries.
                // Once local VAD is observed, provider events cannot move its clock.
                "input_audio_buffer.speech_stopped" -> if (lastClientSpeechSequence == 0L) {
                    lastSpeechStoppedNanos = now
                }
                "response.created" -> {
                    val responseId = node.path("response").path("id").asText()
                    if (
                        responseId.isNotBlank() &&
                        responseId !in completedResponseIds &&
                        responseCreatedNanos.putIfAbsent(responseId, now) == null
                    ) {
                        lastSpeechStoppedNanos?.let { record(turnToResponse, now - it) }
                        lastSpeechStoppedNanos = null
                        trim(responseCreatedNanos)
                    }
                }
                "output_audio_buffer.started" -> {
                    val responseId = node.path("response_id").asText()
                    val created = responseCreatedNanos[responseId]
                    if (
                        created != null &&
                        responseId !in completedResponseIds &&
                        firstAudioResponseIds.add(responseId)
                    ) {
                        record(responseToFirstAudio, now - created)
                        trim(firstAudioResponseIds)
                    }
                }
                "output_audio_buffer.stopped" -> {
                    val responseId = node.path("response_id").asText()
                    if (responseId.isNotBlank() && responseId !in completedResponseIds) {
                        outputStoppedNanos.putIfAbsent(responseId, now)
                        trim(outputStoppedNanos)
                        finishDeviceDrainIfReady(responseId)
                    }
                }
                "output_audio_buffer.cleared" -> unexpectedClears.increment()
            }
        }

        @Synchronized
        fun observeDevicePlayoutDrained(responseId: String) {
            if (responseId.isBlank() || responseId in completedResponseIds) return
            deviceDrainedNanos.putIfAbsent(responseId, nanoTime())
            trim(deviceDrainedNanos)
            finishDeviceDrainIfReady(responseId)
        }

        private fun finishDeviceDrainIfReady(responseId: String) {
            val stopped = outputStoppedNanos[responseId] ?: return
            val drained = deviceDrainedNanos[responseId] ?: return
            outputStoppedNanos.remove(responseId)
            deviceDrainedNanos.remove(responseId)
            responseCreatedNanos.remove(responseId)
            firstAudioResponseIds.remove(responseId)
            completedResponseIds.add(responseId)
            trim(completedResponseIds, MAX_COMPLETED_RESPONSE_IDS)
            record(serverStopToDeviceDrain, (drained - stopped).coerceAtLeast(0))
        }

        private fun record(timer: Timer, nanos: Long) {
            if (nanos >= 0) timer.record(Duration.ofNanos(nanos))
        }

        private fun trim(values: LinkedHashMap<String, Long>) {
            while (values.size > MAX_TRACKED_RESPONSES) values.remove(values.keys.first())
        }

        private fun trim(values: LinkedHashSet<String>, limit: Int = MAX_TRACKED_RESPONSES) {
            while (values.size > limit) values.remove(values.first())
        }

        private companion object {
            const val MAX_TRACKED_RESPONSES = 8
            const val MAX_COMPLETED_RESPONSE_IDS = 32
        }
    }
}
