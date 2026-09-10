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
        for tick in 11...15 {
            let now = 10 + Double(tick) * 0.01
            state.observe(level: quiet, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("A gap shorter than 60 ms must not stop playout.")
            }
        }
        state.observe(level: quiet, duration: 0.01, at: 10.16)
        XCTAssertEqual(state.decision(for: token, now: 10.16), .quietGap)
    }

    func testBriefPhonemeGapAndLowRMSWithSharpPeaksDoNotReleaseTheWait() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 20))
        for tick in 1...5 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        state.observe(level: voiced, duration: 0.01, at: 20.06)
        for tick in 7...11 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        guard case .wait = state.decision(for: token, now: 20.11) else {
            return XCTFail("Separate short gaps cannot accumulate across speech.")
        }
        let consonant = VoiceTutorInterruptionAudioLevel(rms: 0.001, peak: 0.02)
        for tick in 12...20 { state.observe(level: consonant, duration: 0.01, at: 20 + Double(tick) * 0.01) }
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
            for tick in 1...44 {
                let now = 30 + Double(tick) * 0.01
                state.observe(level: level, duration: 0.01, at: now)
                guard case .wait = state.decision(for: token, now: now) else {
                    return XCTFail("Unknown or ongoing audio must not invent a boundary.")
                }
            }
            XCTAssertEqual(state.decision(for: token, now: 30.45), .deadline)
            XCTAssertEqual(state.decision(for: token, now: 300), .deadline,
                "Continued callbacks cannot extend a user's interruption indefinitely.")
        }
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
}
