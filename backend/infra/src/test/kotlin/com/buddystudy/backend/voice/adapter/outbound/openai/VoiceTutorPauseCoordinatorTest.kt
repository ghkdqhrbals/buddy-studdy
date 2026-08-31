package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorPauseCoordinator.Action
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorPauseCoordinator.Phase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/** Pure clock/state tests. No socket, classifier, audio, persistence or quota. */
class VoiceTutorPauseCoordinatorTest {
    @Test
    fun `pause holds new responses immediately but needs input quiescence and the existing output boundary`() {
        val state = state()
        assertThat(state.requestPause(1, 0)).isEmpty()
        assertThat(state.blocksResponses).isTrue()
        assertThat(state.acceptsSpeechEdges).isTrue()
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
        assertThat(state.advance(boundaryReady = true, 1)).isEmpty()
        state.confirmInputQuiesced(2)
        assertThat(state.advance(boundaryReady = true, 2)).isEmpty()
        state.confirmInputQuiesced(1)
        assertThat(state.acceptsSpeechEdges).isFalse()
        assertThat(state.advance(boundaryReady = false, 3)).isEmpty()
        assertThat(state.advance(boundaryReady = true, 4).single()).isInstanceOf(Action.ClearInput::class.java)
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
        assertThat(state.advance(boundaryReady = true, 5)).isEmpty()
        assertThat(state.acknowledgeClear("provider-clear-1", 6)).containsExactly(Action.Acknowledge(1, true))
        assertThat(state.phase).isEqualTo(Phase.PAUSED)
        assertThat(state.blocksResponses).isTrue()
    }

    @Test
    fun `unrequested or duplicate clear ACK cannot acknowledge a later operation`() {
        val state = state()
        assertThat(state.acknowledgeClear("unsolicited", 0)).isEmpty()
        state.requestPause(1, 1)
        state.confirmInputQuiesced(1)
        state.advance(true, 2)
        assertThat(state.acknowledgeClear("unsolicited", 3)).isEmpty()
        assertThat(state.acknowledgeClear("", 3)).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
        assertThat(state.acknowledgeClear("pause-clear", 4)).containsExactly(Action.Acknowledge(1, true))
        state.requestResume(2, 5)
        state.advance(true, 6)
        assertThat(state.acknowledgeClear("pause-clear", 7)).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.RESUMING)
        assertThat(state.acknowledgeClear("resume-clear", 8)).containsExactly(Action.Acknowledge(2, false))
        assertThat(state.blocksResponses).isFalse()
        assertThat(state.acceptsSpeechEdges).isTrue()
    }

    @Test
    fun `replayed valid requests get their exact ACK without changing sequence or repeating clear`() {
        val state = paused()
        assertThat(state.requestPause(1, 10)).containsExactly(Action.Acknowledge(1, true))
        assertThat(state.requestPause(1, 11)).containsExactly(Action.Acknowledge(1, true))
        assertThat(state.advance(true, 12)).isEmpty()
        assertThat(state.requestPause(999, 13)).isEmpty()
        assertThat(state.requestResume(0, 13)).isEmpty()
        state.requestResume(2, 14)
        assertThat(state.requestResume(2, 15)).isEmpty()
        assertThat(state.requestPause(1, 15)).isEmpty()
        assertThat(state.advance(true, 16).single()).isInstanceOf(Action.ClearInput::class.java)
        assertThat(state.acknowledgeClear("resume", 17)).containsExactly(Action.Acknowledge(2, false))
        assertThat(state.requestResume(2, 18)).containsExactly(Action.Acknowledge(2, false))
        assertThat(state.requestPause(1, 19)).isEmpty()
        assertThat(state.requestResume(999, 20)).isEmpty()
        assertThat(state.advance(true, 21)).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.ACTIVE)
    }

    @Test
    fun `invalid or conflicting sequences do not tear down the current call`() {
        val state = state()
        assertThat(state.requestPause(-1, 0)).isEmpty()
        assertThat(state.requestPause(0, 0)).isEmpty()
        assertThat(state.requestResume(1, 0)).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.ACTIVE)
        state.requestPause(3, 0)
        assertThat(state.requestPause(4, 1)).isEmpty()
        assertThat(state.requestResume(4, 1)).isEmpty()
        state.confirmInputQuiesced(4)
        assertThat(state.advance(true, 2)).isEmpty()
        state.confirmInputQuiesced(3)
        state.advance(true, 3)
        assertThat(state.acknowledgeClear("clear", 4)).containsExactly(Action.Acknowledge(3, true))
    }

    @Test
    fun `paused silence clearing is spaced bounded and never creates another hold acknowledgement`() {
        val state = paused()
        assertThat(state.advance(true, seconds(29))).isEmpty()
        val clear = state.advance(true, seconds(31)).single() as Action.ClearInput
        assertThat(clear.eventId).startsWith("buddystudy-internal-duplex-pause-input-clear-")
        repeat(100) { assertThat(state.advance(true, seconds(32))).isEmpty() }
        assertThat(state.acknowledgeClear("maintenance", seconds(33))).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.PAUSED)
        assertThat(state.advance(true, seconds(62))).isEmpty()
        assertThat(state.advance(true, seconds(63))).hasSize(1)
    }

    @Test
    fun `resume waits for maintenance ACK then uses its own fresh clear fence`() {
        val state = paused()
        state.advance(true, seconds(31))
        state.requestResume(2, seconds(32))
        assertThat(state.advance(true, seconds(32))).isEmpty()
        assertThat(state.phase).isEqualTo(Phase.RESUMING)
        assertThat(state.acknowledgeClear("maintenance", seconds(33))).isEmpty()
        assertThat(state.blocksResponses).isTrue()
        val resumeClear = state.advance(true, seconds(33)).single() as Action.ClearInput
        assertThat(resumeClear.eventId).isNotBlank()
        assertThat(state.acknowledgeClear("maintenance", seconds(33))).isEmpty()
        assertThat(state.acknowledgeClear("resume", seconds(34))).containsExactly(Action.Acknowledge(2, false))
        assertThat(state.needsClock).isFalse()
        assertThat(state.advance(true, seconds(3_600))).isEmpty()
    }

    @Test
    fun `missing clear ACK fails at a finite deadline instead of inventing paused success`() {
        val state = state()
        state.requestPause(1, 0)
        state.confirmInputQuiesced(1)
        state.advance(true, 0)
        assertThrows(VoiceTutorPauseAcknowledgementTimeoutException::class.java) {
            state.advance(true, seconds(5))
        }
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
        assertThrows(VoiceTutorPauseAcknowledgementTimeoutException::class.java) {
            state.acknowledgeClear("late", seconds(5))
        }
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
    }

    @Test
    fun `lost quiescence or output completion is finite and duplicate requests cannot extend it`() {
        val state = state()
        state.requestPause(1, 0)
        state.requestPause(1, seconds(69))
        assertThat(state.advance(false, seconds(69))).isEmpty()
        assertThrows(VoiceTutorPauseAcknowledgementTimeoutException::class.java) {
            state.advance(false, seconds(70))
        }
    }

    @Test
    fun `late clear ACK cannot extend the total pause transition deadline`() {
        val state = state()
        state.requestPause(1, 0)
        state.confirmInputQuiesced(1)
        state.advance(true, seconds(69))
        assertThrows(VoiceTutorPauseAcknowledgementTimeoutException::class.java) {
            state.acknowledgeClear("late", seconds(70))
        }
        assertThat(state.phase).isEqualTo(Phase.PAUSING)
    }

    @Test
    fun `close disposes hold state and ignores all late callbacks`() {
        val state = paused()
        state.requestResume(2, 10)
        state.advance(true, 11)
        state.close()
        assertThat(state.needsClock).isFalse()
        assertThat(state.acceptsSpeechEdges).isFalse()
        assertThat(state.blocksResponses).isTrue()
        assertThat(state.acknowledgeClear("late", 12)).isEmpty()
        assertThat(state.requestPause(3, 13)).isEmpty()
        assertThat(state.requestResume(4, 14)).isEmpty()
        assertThat(state.advance(true, seconds(3_600))).isEmpty()
    }

    private fun state() = VoiceTutorPauseCoordinator(Duration.ofSeconds(60))

    private fun paused() = state().apply {
        requestPause(1, 0)
        confirmInputQuiesced(1)
        advance(true, 0)
        assertThat(acknowledgeClear("pause", 1)).containsExactly(Action.Acknowledge(1, true))
    }

    private fun seconds(value: Long) = Duration.ofSeconds(value).toNanos()
}
