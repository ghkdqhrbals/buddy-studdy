import Foundation
import XCTest
@testable import StudyMate

/// No microphone, provider, database, or live session is opened by these tests.
final class VoiceTutorPauseTests: XCTestCase {
    func testPauseCopyUsesDirectCallControlsInEveryLanguage() {
        let expected = [
            (AppLanguage.korean, "일시정지", "계속하기", "일시정지됨"),
            (AppLanguage.english, "Pause", "Continue", "Paused"),
            (AppLanguage.japanese, "一時停止", "続ける", "一時停止中")
        ]
        for (language, pause, resume, paused) in expected {
            let strings = AppStrings(language: language)
            XCTAssertEqual(strings.voiceTutorTakeBreak, pause)
            XCTAssertEqual(strings.voiceTutorResumeLesson, resume)
            XCTAssertEqual(strings.voiceTutorPaused, paused)
        }
    }

    func testPauseIsUnavailableWithoutTheExplicitServerCapability() throws {
        var state = VoiceTutorCallPauseState()
        XCTAssertNil(state.requestPause())
        XCTAssertFalse(state.holdsMicrophone)
        let legacy = try VoiceTutorRealtimeEventParser.parse(text: #"{"type":"buddystudy.voice.session.ready"}"#)
        XCTAssertEqual(legacy, .sessionReady(hardEndsAt: nil, quotaRemainingSeconds: nil))
        let supported = try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.session.ready","pauseProtocol":"pause-v1"}"#)
        XCTAssertEqual(supported, .sessionReady(hardEndsAt: nil, quotaRemainingSeconds: nil, pauseProtocol: "pause-v1"))
        XCTAssertFalse(VoiceTutorCallPresentation(phase: .listening).showsPauseControl)
    }

    func testPauseAndResumeNeedTheExactAcknowledgementBeforeChangingStableState() throws {
        var state = supportedState()
        let pause = try XCTUnwrap(state.requestPause())
        XCTAssertEqual(pause, VoiceTutorPauseControl(kind: .pause, sequence: 1))
        XCTAssertEqual(state.mode, .pausing)
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(state.isAwaitingAcknowledgement)
        XCTAssertFalse(state.acknowledge(sequence: 1, paused: false))
        XCTAssertFalse(state.acknowledge(sequence: 2, paused: true))
        XCTAssertEqual(state.mode, .pausing)

        XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
        XCTAssertEqual(state.mode, .paused)
        XCTAssertFalse(state.isAwaitingAcknowledgement)
        let resume = try XCTUnwrap(state.requestResume())
        XCTAssertEqual(resume, VoiceTutorPauseControl(kind: .resume, sequence: 2))
        XCTAssertEqual(state.mode, .resuming)
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertFalse(state.acknowledge(sequence: 1, paused: true))
        XCTAssertFalse(state.acknowledge(sequence: 2, paused: true))
        XCTAssertTrue(state.acknowledge(sequence: 2, paused: false))
        XCTAssertEqual(state.mode, .active)
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertFalse(state.acknowledge(sequence: 2, paused: false))
    }

    func testRapidRepeatedTapsCannotQueueConflictingPauseOperations() throws {
        var state = supportedState()
        _ = try XCTUnwrap(state.requestPause())
        for _ in 0..<32 {
            XCTAssertNil(state.requestPause())
            XCTAssertNil(state.requestResume())
        }
        XCTAssertEqual(state.sequence, 1)
        XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
        XCTAssertNil(state.requestPause())
        _ = try XCTUnwrap(state.requestResume())
        XCTAssertNil(state.requestResume())
        XCTAssertNil(state.requestPause())
        XCTAssertEqual(state.sequence, 2)
    }

    func testPauseTemporarilyClosesCaptureAndRestoresBothPreviousMuteChoices() throws {
        for userMuted in [false, true] {
            var state = supportedState()
            XCTAssertEqual(state.effectiveMicrophoneMuted(userMuted: userMuted), userMuted)
            _ = try XCTUnwrap(state.requestPause())
            XCTAssertTrue(state.effectiveMicrophoneMuted(userMuted: userMuted))
            XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
            XCTAssertTrue(state.effectiveMicrophoneMuted(userMuted: userMuted))
            _ = try XCTUnwrap(state.requestResume())
            XCTAssertTrue(state.effectiveMicrophoneMuted(userMuted: userMuted))
            XCTAssertTrue(state.acknowledge(sequence: 2, paused: false))
            XCTAssertEqual(state.effectiveMicrophoneMuted(userMuted: userMuted), userMuted)
        }
    }

    func testStaleAcknowledgementFromAPreviousOperationCannotReleaseNewPause() throws {
        var state = supportedState()
        _ = state.requestPause()
        XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
        _ = state.requestResume()
        XCTAssertTrue(state.acknowledge(sequence: 2, paused: false))
        _ = state.requestPause()
        XCTAssertFalse(state.acknowledge(sequence: 1, paused: true))
        XCTAssertFalse(state.acknowledge(sequence: 2, paused: false))
        XCTAssertEqual(state.mode, .pausing)
        XCTAssertTrue(state.isAwaitingAcknowledgement)
        XCTAssertEqual(state.sequence, 3)

        let reset = VoiceTutorCallPauseState()
        XCTAssertFalse(reset.isAwaitingAcknowledgement)
        XCTAssertEqual(reset.sequence, 0)
    }

    func testMalformedPauseAcknowledgementsNeverBecomeTruth() throws {
        let malformed = [
            #"{"type":"buddystudy.voice.pause.state","sequence":true,"paused":true}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":0,"paused":true}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":-1,"paused":true}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":"1","paused":true}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":1.5,"paused":true}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":1,"paused":1}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":1,"paused":"true"}"#,
            #"{"type":"buddystudy.voice.pause.state","sequence":1}"#
        ]
        for raw in malformed {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text: raw), .ignored(type: "buddystudy.voice.pause.state"))
        }
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(text: #"{"type":"buddystudy.voice.pause.state","sequence":3,"paused":true}"#),
            .pauseAcknowledged(sequence: 3, paused: true)
        )
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(text: #"{"type":"buddystudy.voice.pause.state","sequence":4,"paused":false}"#),
            .pauseAcknowledged(sequence: 4, paused: false)
        )
    }

    func testCaptureStopAndQuiescenceHaveOneFIFOBoundary() async throws {
        let controls = VoiceTutorCallControlEventStream()
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        let start = try XCTUnwrap(speechStart(&detector))
        controls.yield(start)
        let pause = VoiceTutorPauseControl(kind: .pause, sequence: 1)
        controls.yield(pause)
        let stop = try XCTUnwrap(detector.updateGate(mediaReady: true, muted: true))
        controls.yield(stop)
        let quiesced = VoiceTutorPauseControl(kind: .inputQuiesced, sequence: 1)
        controls.yield(quiesced)
        for _ in 0..<20 {
            XCTAssertNil(detector.process(speechProbability: 1, duration: 0.032))
        }
        controls.finish()
        var received: [VoiceTutorCallControlEvent] = []
        for try await event in controls.stream { received.append(event) }
        XCTAssertEqual(received, [.speech(start), .pause(pause), .speech(stop), .pause(quiesced)])
        XCTAssertEqual(start.sequence, stop.sequence)
        XCTAssertEqual(stop.activity, .stopped)
    }

    func testResumeNeedsFreshSpeechEvidenceAndAFreshUtteranceSequence() throws {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        let first = try XCTUnwrap(speechStart(&detector))
        XCTAssertEqual(detector.updateGate(mediaReady: true, muted: true)?.sequence, first.sequence)
        for _ in 0..<20 { XCTAssertNil(detector.process(speechProbability: 1, duration: 0.032)) }
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertNil(detector.process(speechProbability: 0.99, duration: 0.032))
        XCTAssertNil(detector.process(speechProbability: 0.99, duration: 0.032))
        let next = try XCTUnwrap(detector.process(speechProbability: 0.99, duration: 0.032))
        XCTAssertEqual(next.sequence, first.sequence + 1)
        XCTAssertEqual(next.activity, .started)
    }

    func testPauseControlQueueOverflowIsVisibleInsteadOfDroppingTheFence() async {
        let controls = VoiceTutorCallControlEventStream(capacity: 1)
        controls.yield(VoiceTutorPauseControl(kind: .pause, sequence: 1))
        controls.yield(VoiceTutorPauseControl(kind: .inputQuiesced, sequence: 1))
        var observedError: Error?
        do {
            for try await _ in controls.stream {}
        } catch { observedError = error }
        XCTAssertEqual(observedError as? VoiceTutorLocalSpeechDeliveryError, .bufferOverflow)
    }

    func testPausedPresentationKeepsTheCallCountdownAndEndControl() throws {
        var state = supportedState()
        _ = state.requestPause()
        XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
        var presentation = VoiceTutorCallPresentation(
            phase: .listening, pauseState: state, sessionSecondsRemaining: 3_010,
            quotaRemainingSeconds: 0, quotaReservedSeconds: 3_600, quotaLimitSeconds: 3_600
        )
        XCTAssertEqual(presentation.remainingTime, .call(3_010))
        XCTAssertEqual(presentation.primaryAction, .end)
        XCTAssertTrue(presentation.showsPauseControl)
        XCTAssertTrue(presentation.canChangePause)
        presentation.sessionSecondsRemaining = 3_009
        XCTAssertEqual(presentation.remainingTime, .call(3_009), "Pause never freezes or grants call time")
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPaused)
            XCTAssertFalse(strings.voiceTutorPauseUsesTime.isEmpty)
        }
        presentation.phase = .failed
        XCTAssertFalse(presentation.showsPauseControl)
        XCTAssertEqual(presentation.primaryAction, .retry)
        XCTAssertNil(presentation.remainingTime, "Reserved monthly quota is not settled usage")
    }

    func testSingleOrbUsesOnlyStablePauseAcknowledgementStatesAsTapActions() throws {
        let strings = AppStrings(language: .korean)
        var state = supportedState()
        var presentation = VoiceTutorCallPresentation(phase: .listening, pauseState: state)
        XCTAssertEqual(presentation.orbState, .listening)
        XCTAssertEqual(presentation.orbAction, .pause)
        XCTAssertTrue(presentation.orbAnimates)
        XCTAssertFalse(presentation.needsVisibleStatus(strings, errorMessage: nil))

        let pause = try XCTUnwrap(state.requestPause())
        presentation.pauseState = state
        XCTAssertEqual(presentation.orbState, .pausing)
        XCTAssertEqual(presentation.orbAction, .none)
        XCTAssertFalse(presentation.orbAnimates)
        XCTAssertTrue(presentation.needsVisibleStatus(strings, errorMessage: nil))

        XCTAssertTrue(state.acknowledge(sequence: pause.sequence, paused: true))
        presentation.pauseState = state
        XCTAssertEqual(presentation.orbState, .paused)
        XCTAssertEqual(presentation.orbAction, .resume)
        XCTAssertFalse(presentation.orbAnimates, "A paused orb must have an unmistakably stopped effect")

        _ = try XCTUnwrap(state.requestResume())
        presentation.pauseState = state
        XCTAssertEqual(presentation.orbState, .resuming)
        XCTAssertEqual(presentation.orbAction, .none)
        XCTAssertFalse(presentation.orbAnimates)
    }

    func testSingleOrbKeepsNormalStatusQuietButSurfacesActionableLiveStates() {
        let strings = AppStrings(language: .english)
        var presentation = VoiceTutorCallPresentation(phase: .speaking)
        XCTAssertEqual(presentation.orbState, .speaking)
        XCTAssertTrue(presentation.orbAnimates)
        XCTAssertFalse(presentation.needsVisibleStatus(strings, errorMessage: nil))

        presentation.phase = .listening
        presentation.inputNeedsRepeat = true
        XCTAssertTrue(presentation.needsVisibleStatus(strings, errorMessage: nil))
        presentation.inputNeedsRepeat = false
        XCTAssertTrue(presentation.needsVisibleStatus(strings, errorMessage: strings.voiceTutorMicrophoneDenied))
        XCTAssertTrue(VoiceTutorCallPresentation(phase: .connecting).needsVisibleStatus(strings, errorMessage: nil))
        XCTAssertTrue(VoiceTutorCallPresentation(phase: .failed).needsVisibleStatus(strings, errorMessage: nil))
    }

    func testPendingStateIsNotRenderedAsPausedAndHasNoSecondPauseAction() throws {
        var state = supportedState()
        _ = try XCTUnwrap(state.requestPause())
        let strings = AppStrings(language: .korean)
        var presentation = VoiceTutorCallPresentation(phase: .speaking, pauseState: state)
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPausing)
        XCTAssertFalse(presentation.canChangePause)
        XCTAssertEqual(presentation.primaryAction, .end)
        XCTAssertTrue(state.acknowledge(sequence: 1, paused: true))
        _ = state.requestResume()
        presentation.pauseState = state
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorResuming)
        XCTAssertFalse(presentation.canChangePause)
        presentation.phase = .ending
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorCallEnding)
    }

    func testPauseStateNeverChangesNormalLearnerOverlapPolicy() {
        var playback = VoiceTutorDuplexPlaybackState()
        playback.responseStarted(responseID: "tutor", isTutorIntervention: false)
        playback.userSpeechStarted()
        let state = supportedState()
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertTrue(playback.assistantResponseActive)
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .speaking).primaryAction, .end)
    }

    private func supportedState() -> VoiceTutorCallPauseState {
        var state = VoiceTutorCallPauseState()
        state.isSupported = true
        return state
    }

    private func speechStart(_ detector: inout VoiceTutorLocalSpeechDetector) -> VoiceTutorLocalSpeechEvent? {
        var result: VoiceTutorLocalSpeechEvent?
        for _ in 0..<3 {
            if let event = detector.process(speechProbability: 0.99, duration: 0.032) { result = event }
        }
        return result
    }
}
