import AVFoundation
import XCTest
@testable import StudyMate

final class VoiceTutorInterruptionBoundaryTests: XCTestCase {
    private let voiced = VoiceTutorInterruptionAudioLevel(rms: 0.08, peak: 0.18)
    private let quiet = VoiceTutorInterruptionAudioLevel(rms: 0.0005, peak: 0.001)

    func testInterruptionWaitsThroughSoundAndUsesTheNextSustainedQuietGap() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("spoken-answer")
        let token = try XCTUnwrap(state.request(responseID: "spoken-answer", at: 10))
        for tick in 1...10 {
            let now = 10 + Double(tick) * 0.01
            state.observe(level: voiced, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("An ongoing sound should receive the bounded grace.")
            }
        }
        for tick in 11...13 {
            let now = 10 + Double(tick) * 0.01
            state.observe(level: quiet, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("A gap shorter than 40 ms must not stop playout.")
            }
        }
        state.observe(level: quiet, duration: 0.01, at: 10.14)
        XCTAssertEqual(state.decision(for: token, now: 10.14), .quietGap,
                       "The first short word gap must stop without waiting for a longer phrase pause.")
    }

    func testBriefPhonemeGapAndLowRMSWithSharpPeaksDoNotReleaseTheWait() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 20))
        for tick in 1...3 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        state.observe(level: voiced, duration: 0.01, at: 20.04)
        for tick in 5...7 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        guard case .wait = state.decision(for: token, now: 20.07) else {
            return XCTFail("Separate short gaps cannot accumulate across speech.")
        }
        let consonant = VoiceTutorInterruptionAudioLevel(rms: 0.001, peak: 0.02)
        for tick in 8...20 { state.observe(level: consonant, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        guard case .wait = state.decision(for: token, now: 20.20) else {
            return XCTFail("RMS alone cannot classify a quiet consonant as a pause.")
        }
    }

    func testContinuousSpeechMissingCallbacksAndInvalidPCMRespectTheHardDeadline() throws {
        let levels: [VoiceTutorInterruptionAudioLevel?] = [voiced, nil, .init(rms: .nan, peak: 1)]
        for level in levels {
            var state = VoiceTutorInterruptionBoundaryState()
            state.responseStarted("answer")
            let token = try XCTUnwrap(state.request(responseID: "answer", at: 30))
            for tick in 1...74 {
                let now = 30 + Double(tick) * 0.01
                state.observe(level: level, duration: 0.01, at: now)
                guard case .wait = state.decision(for: token, now: now) else {
                    return XCTFail("Unknown or ongoing audio must not invent a boundary.")
                }
            }
            XCTAssertEqual(state.decision(for: token, now: 30.75), .deadline)
            XCTAssertEqual(state.decision(for: token, now: 300), .deadline,
                "Continued callbacks cannot extend a user's interruption indefinitely.")
        }
    }

    func testAWordCanFinishBeyondTheFormerCutoffWithoutWaitingForTheWholeReply() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 60))
        for tick in 1...65 {
            let now = 60 + Double(tick) * 0.01
            state.observe(level: voiced, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("The former 450 ms deadline must not cut this still-running word")
            }
        }
        for tick in 66...69 { state.observe(level: quiet, duration: 0.01, at: 60 + Double(tick) * 0.01) }
        XCTAssertEqual(state.decision(for: token, now: 60.69), .quietGap)
        XCTAssertLessThan(0.69, VoiceTutorInterruptionBoundaryState.maximumGraceSeconds)
    }

    func testResultArrivalAndLearnerInterruptionShareTheFirstWordDeadline() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("waiting-notice")
        let learner = try XCTUnwrap(state.request(responseID: "waiting-notice", at: 100))
        let grading = try XCTUnwrap(state.request(responseID: "waiting-notice", at: 100.5))
        XCTAssertEqual(grading, learner, "A second interruption cannot buy the old speech another word.")
        XCTAssertEqual(state.decision(for: grading, now: 100.75), .deadline)
        XCTAssertNil(state.request(responseID: "waiting-notice", at: 99))
        state.responseStarted("grading-feedback")
        let next = try XCTUnwrap(state.request(responseID: "grading-feedback", at: 101))
        XCTAssertEqual(next.requestedAtUptime, 101, "The next response owns an independent deadline.")
    }

    func testRecentSilenceBeforeTheRequestCannotSkipTheNextBoundary() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        for tick in 1...9 { state.observe(level: quiet, duration: 0.01, at: 70 + Double(tick) * 0.01) }
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 70.10))
        guard case .wait = state.decision(for: token, now: 70.10) else {
            return XCTFail("A fresh but earlier gap is not an observation after the user's request")
        }
        state.observe(level: voiced, duration: 0.01, at: 70.11)
        guard case .wait = state.decision(for: token, now: 70.11) else {
            return XCTFail("The current voiced sound must retain its boundary opportunity")
        }
    }

    func testGainFadeIsContinuousMonotonicAndEndsWithinItsShortBound() throws {
        var fade = VoiceTutorInterruptionFadeState()
        fade.responseStarted("answer")
        let token = try XCTUnwrap(fade.request(responseID: "answer", at: 80))
        XCTAssertEqual(fade.gain(for: token, at: 80), 1)
        var previous: Float = 1
        for tick in 1...10 {
            let gain = try XCTUnwrap(fade.gain(for: token, at: 80 + Double(tick) * 0.004))
            XCTAssertLessThanOrEqual(gain, previous)
            XCTAssertGreaterThanOrEqual(gain, 0)
            if tick < 10 { XCTAssertGreaterThan(gain, 0, "A fade must not immediately hard-mute the source") }
            previous = gain
        }
        XCTAssertEqual(try XCTUnwrap(fade.gain(for: token, at: 80.02)), 0.5, accuracy: 0.000_01)
        XCTAssertEqual(fade.gain(for: token, at: 80.05), 0)
        XCTAssertEqual(fade.currentGain(at: 100), 0, "Completed fading stays silent until the next response")
    }

    func testDuplicateResponseEventsCannotReopenAFadeAndOldTokensCannotMuteNewSpeech() throws {
        var fade = VoiceTutorInterruptionFadeState()
        fade.responseStarted("old")
        let old = try XCTUnwrap(fade.request(responseID: "old", at: 90))
        fade.responseStarted("old")
        XCTAssertEqual(try XCTUnwrap(fade.gain(for: old, at: 90.02)), 0.5, accuracy: 0.000_01)
        XCTAssertEqual(fade.request(responseID: "old", at: 90.02), old,
                       "Repeated interruption requests must not restart the ramp")
        fade.responseStarted("new")
        XCTAssertNil(fade.gain(for: old, at: 90.05))
        XCTAssertEqual(fade.currentGain(at: 90.05), 1)
        XCTAssertNil(fade.request(responseID: "old", at: 90.05))
        let next = try XCTUnwrap(fade.request(responseID: "new", at: 90.06))
        fade.invalidate()
        XCTAssertNil(fade.gain(for: next, at: 90.07))
        XCTAssertNil(fade.request(responseID: "new", at: 90.07))
    }

    func testOldQuietAudioAndRepeatedCallbacksCannotInventANewGap() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        for tick in 1...6 { state.observe(level: quiet, duration: 0.01, at: 40 + Double(tick) * 0.01) }
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 41))
        guard case .wait = state.decision(for: token, now: 41) else {
            return XCTFail("A suspended renderer's earlier silence is not a current gap.")
        }
        state.observe(level: quiet, duration: 0.01, at: 41.01)
        for _ in 1...100 { state.observe(level: quiet, duration: 0.01, at: 41.01) }
        guard case .wait = state.decision(for: token, now: 41.01) else {
            return XCTFail("Duplicate callbacks must not count the same samples repeatedly.")
        }
        state.observe(level: nil, duration: 0.01, at: 41.02)
        state.observe(level: quiet, duration: .nan, at: 41.03)
        guard case .wait = state.decision(for: token, now: 41.03) else {
            return XCTFail("Invalid audio metadata must not become a silence observation.")
        }
    }

    func testReplacementInterruptionAndCloseInvalidateOnlyTheExactResponseWait() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("old")
        let old = try XCTUnwrap(state.request(responseID: "old", at: 50))
        state.responseStarted("new")
        XCTAssertEqual(state.decision(for: old, now: 50.01), .superseded)
        let new = try XCTUnwrap(state.request(responseID: "new", at: 50.01))
        state.invalidate(responseID: "old")
        guard case .wait = state.decision(for: new, now: 50.02) else {
            return XCTFail("A delayed old-response interruption must not stop the replacement wait.")
        }
        state.invalidate(responseID: "new")
        XCTAssertEqual(state.decision(for: new, now: 50.03), .superseded)
        state.responseStarted("last")
        let last = try XCTUnwrap(state.request(responseID: "last", at: 51))
        state.invalidate()
        XCTAssertEqual(state.decision(for: last, now: 51.01), .superseded)
        XCTAssertNil(state.request(responseID: "last", at: 51.01))
    }

    func testNativePCMMeasurementPreservesSamplesAndRejectsUnknownAudio() throws {
        for interleaved in [true, false] {
            let format = try XCTUnwrap(AVAudioFormat(
                commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: 2, interleaved: interleaved
            ))
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            buffer.frameLength = 480
            let channels = try XCTUnwrap(buffer.int16ChannelData)
            for channel in 0..<(interleaved ? 1 : 2) {
                for index in 0..<(interleaved ? 960 : 480) { channels[channel][index] = 16 }
            }
            let quietLevel = try XCTUnwrap(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(buffer))
            XCTAssertTrue(quietLevel.isQuiet)
            channels[interleaved ? 0 : 1][interleaved ? 959 : 479] = 2_000
            let peakLevel = try XCTUnwrap(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(buffer))
            XCTAssertFalse(peakLevel.isQuiet)
            XCTAssertEqual(peakLevel.peak, Double(2_000) / 32_768, accuracy: 1e-12)
            XCTAssertEqual(channels[0][0], 16, "Boundary inspection must never modify audible PCM.")
            XCTAssertEqual(channels[interleaved ? 0 : 1][interleaved ? 959 : 479], 2_000)
        }
        let floatFormat = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false
        ))
        let invalid = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: floatFormat, frameCapacity: 1))
        invalid.frameLength = 1
        invalid.floatChannelData?[0][0] = .nan
        XCTAssertNil(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(invalid))
        invalid.frameLength = 0
        XCTAssertNil(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(invalid))
    }

    func testServerFinishWordRequestRoundTripsOnlyItsExactResponseAndRequestFences() throws {
        let request = try XCTUnwrap(VoiceTutorFinishWordRequest(responseID: "resp_1", requestID: "request-2"))
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.response.finish_word","responseId":"resp_1","requestId":"request-2"}"#),
            .finishWordRequested(request))
        XCTAssertEqual(request.acknowledgementPayload, [
            "type": "buddystudy.voice.response.word_finished", "responseId": "resp_1", "requestId": "request-2"
        ])
        let malformedPayloads: [[String: Any]] = [
            ["responseId": "resp_1"], ["responseId": "resp_1", "requestId": ""],
            ["responseId": " ", "requestId": "request-2"],
            ["responseId": "resp_1", "requestId": String(repeating: "a", count: 192)],
            ["responseId": "resp_1", "requestId": 2],
            ["responseId": "resp_1", "requestId": "request-2", "recordId": "42"]
        ]
        for malformed in malformedPayloads {
            var payload = malformed
            payload["type"] = "buddystudy.voice.response.finish_word"
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: payload)),
                           .ignored(type: "buddystudy.voice.response.finish_word"))
        }
    }

    func testWordFinishedAcknowledgementCannotUseAnotherConnectionAttempt() async throws {
        let transport = VoiceTutorWebSocketTransport()
        let request = try XCTUnwrap(VoiceTutorFinishWordRequest(responseID: "resp_1", requestID: "request-2"))
        do {
            try await transport.sendWordFinished(request, attemptID: UUID())
            XCTFail("A stale boundary task must not send an acknowledgement.")
        } catch {
            XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .staleAttempt)
        }
    }
}
