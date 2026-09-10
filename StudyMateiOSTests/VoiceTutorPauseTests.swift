import Foundation
import Combine
import SwiftUI
import UIKit
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

    func testRealtimeNativeReadyMustConfirmTheExactProtocolAndPreservesPauseCapability() throws {
        let ready = try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.session.ready","pauseProtocol":"pause-v1","turnProtocol":"realtime-native-v1"}"#)
        XCTAssertEqual(ready, .sessionReady(
            hardEndsAt: nil, quotaRemainingSeconds: nil,
            pauseProtocol: "pause-v1", turnProtocol: "realtime-native-v1"
        ))
        XCTAssertTrue(VoiceTutorTurnProtocol.acceptsReady("realtime-native-v1"))
        let unsupportedProtocols: [String?] = [nil, "", "local-vad-v1", "realtime-native-v2", "realtime-native-v1 "]
        for unsupported in unsupportedProtocols {
            XCTAssertFalse(VoiceTutorTurnProtocol.acceptsReady(unsupported),
                           "Capture must stay closed until both sides agree on who commits learner input")
        }
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

    func testPausingAnAnswerPreservesItsDraftAndResumesTheSameCaptureAfterExactAcknowledgement() async throws {
        let answerID = "11111111-2222-3333-4444-555555555555"
        let recordID = "101"
        var draft = VoiceTutorAnswerDraftState()
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: recordID,
            revision: 7, phase: .listening, text: nil, code: nil), existingDraft: "직접 작성한 첫 문장"))
        var pauseState = supportedState()
        var detector = VoiceTutorLocalSpeechDetector()
        let controls = VoiceTutorCallControlEventStream()
        _ = detector.updateGate(mediaReady: true, muted: false)
        let firstStart = try XCTUnwrap(speechStart(&detector))
        controls.yield(firstStart)
        XCTAssertTrue(draft.append(.init(answerID: answerID, recordID: recordID,
            itemID: "before-pause", sequence: 1, text: "말로 덧붙인 설명")))
        XCTAssertTrue(draft.edit("직접 고친 첫 문장\n말로 덧붙인 설명"))

        let pause = try XCTUnwrap(pauseState.requestPause())
        controls.yield(pause)
        let firstStop = try XCTUnwrap(detector.updateGate(mediaReady: true,
            muted: pauseState.effectiveMicrophoneMuted(userMuted: false)))
        controls.yield(firstStop)
        let quiesced = VoiceTutorPauseControl(kind: .inputQuiesced, sequence: pause.sequence)
        controls.yield(quiesced)
        XCTAssertEqual(firstStop.sequence, firstStart.sequence)
        XCTAssertTrue(pauseState.acknowledge(sequence: pause.sequence, paused: true))

        // Committed speech can finish ASR after the input-clear acknowledgement.
        // Holding capture must not discard that tail or replace the edited prefix.
        XCTAssertTrue(draft.append(.init(answerID: answerID, recordID: recordID,
            itemID: "pause-tail", sequence: 2, text: "멈추기 직전의 마지막 말")))
        XCTAssertEqual(draft.phase, .listening)
        XCTAssertFalse(draft.holdsMicrophone)
        XCTAssertTrue(pauseState.holdsMicrophone)
        XCTAssertEqual(draft.text, "직접 고친 첫 문장\n말로 덧붙인 설명\n멈추기 직전의 마지막 말")
        for _ in 0..<40 { XCTAssertNil(detector.process(speechProbability: 0.99, duration: 0.032)) }

        let resume = try XCTUnwrap(pauseState.requestResume())
        controls.yield(resume)
        XCTAssertFalse(pauseState.acknowledge(sequence: pause.sequence, paused: false))
        XCTAssertFalse(pauseState.acknowledge(sequence: resume.sequence, paused: true))
        XCTAssertTrue(pauseState.holdsMicrophone)
        XCTAssertNil(detector.process(speechProbability: 0.99, duration: 0.032))
        XCTAssertTrue(pauseState.acknowledge(sequence: resume.sequence, paused: false))
        XCTAssertNil(detector.updateGate(mediaReady: true,
            muted: pauseState.effectiveMicrophoneMuted(userMuted: false)))
        let resumedStart = try XCTUnwrap(speechStart(&detector))
        controls.yield(resumedStart)
        XCTAssertEqual(resumedStart.sequence, firstStart.sequence + 1)
        XCTAssertTrue(draft.append(.init(answerID: answerID, recordID: recordID,
            itemID: "after-resume", sequence: 3, text: "생각을 정리한 뒤 이어 말합니다")))
        XCTAssertEqual(draft.answerID, answerID)
        XCTAssertEqual(draft.recordID, recordID)
        XCTAssertEqual(draft.studyID, 42)
        XCTAssertEqual(draft.revision, 7)
        XCTAssertEqual(draft.phase, .listening)
        XCTAssertTrue(draft.hasUserEdited)
        XCTAssertEqual(draft.sourceItemIDs, Set(["before-pause", "pause-tail", "after-resume"]))
        XCTAssertEqual(draft.text, "직접 고친 첫 문장\n말로 덧붙인 설명\n멈추기 직전의 마지막 말\n생각을 정리한 뒤 이어 말합니다")

        let finalStop = try XCTUnwrap(detector.updateGate(mediaReady: true, muted: true))
        controls.yield(finalStop)
        let finish = try XCTUnwrap(draft.requestFinish())
        controls.yield(finish)
        controls.finish()
        var received: [VoiceTutorCallControlEvent] = []
        for try await event in controls.stream { received.append(event) }
        XCTAssertEqual(received, [.speech(firstStart), .pause(pause), .speech(firstStop),
            .pause(quiesced), .pause(resume), .speech(resumedStart), .speech(finalStop), .answer(finish)])
        XCTAssertEqual(finish, .init(kind: .finish, answerID: answerID, recordID: recordID))
        XCTAssertEqual(draft.phase, .finalizing, "Only explicit Answer Finish may finalize the retained capture")
    }

    func testAnswerFinishPresentationWaitsForResumeAcknowledgementAndMatchingUnpausedSnapshot() throws {
        let answerID = "11111111-2222-3333-4444-555555555555"
        var draft = VoiceTutorAnswerDraftState()
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101",
            revision: 7, phase: .listening, text: nil, code: nil), existingDraft: "생각 중인 답변\n두 번째 문장"))
        let originalDraft = draft
        var pause = supportedState()
        var session = VoiceTutorSessionState()
        XCTAssertTrue(session.apply(.init(sequence: 1, phase: .answering, paused: false,
            revision: 7, studyID: 42, recordID: "101", answerID: answerID)))
        var presentation = VoiceTutorCallPresentation(phase: .listening, pauseState: pause, sessionState: session)
        XCTAssertTrue(presentation.canDisplayActiveAnswer)
        XCTAssertEqual(presentation.orbState, .capturingAnswer)

        let requestedPause = try XCTUnwrap(pause.requestPause())
        presentation.pauseState = pause
        XCTAssertFalse(presentation.canDisplayActiveAnswer, "Answer Finish must not be offered while its microphone hold is being acknowledged")
        XCTAssertFalse(presentation.canChangePause)
        XCTAssertEqual(presentation.orbState, .pausing)
        XCTAssertTrue(pause.acknowledge(sequence: requestedPause.sequence, paused: true))
        XCTAssertTrue(session.apply(.init(sequence: 2, phase: .answering, paused: true,
            revision: 7, studyID: 42, recordID: "101", answerID: answerID)))
        presentation.pauseState = pause
        presentation.sessionState = session
        XCTAssertFalse(presentation.canDisplayActiveAnswer)
        XCTAssertTrue(presentation.canChangePause)
        XCTAssertEqual(presentation.orbAction, .resume)
        XCTAssertEqual(presentation.orbState, .paused)
        XCTAssertEqual(presentation.lessonPhase, .answering)

        let requestedResume = try XCTUnwrap(pause.requestResume())
        presentation.pauseState = pause
        XCTAssertFalse(presentation.canDisplayActiveAnswer)
        XCTAssertFalse(presentation.canChangePause)
        XCTAssertEqual(presentation.orbState, .resuming)
        XCTAssertFalse(pause.acknowledge(sequence: requestedPause.sequence, paused: false))
        XCTAssertTrue(pause.holdsMicrophone)
        XCTAssertTrue(pause.acknowledge(sequence: requestedResume.sequence, paused: false))
        presentation.pauseState = pause
        XCTAssertFalse(presentation.canDisplayActiveAnswer, "The old paused snapshot must not flash a Finish button before the new display snapshot arrives")
        XCTAssertTrue(session.apply(.init(sequence: 3, phase: .answering, paused: false,
            revision: 7, studyID: 42, recordID: "101", answerID: answerID)))
        presentation.sessionState = session
        XCTAssertTrue(presentation.canDisplayActiveAnswer)
        XCTAssertTrue(presentation.canChangePause)
        XCTAssertEqual(presentation.orbState, .capturingAnswer)
        XCTAssertEqual(presentation.sessionState.snapshot?.answerID, answerID)
        XCTAssertEqual(draft, originalDraft, "Display and pause acknowledgements must leave the draft and its capture identity untouched")

        presentation.phase = .ending
        XCTAssertFalse(presentation.canDisplayActiveAnswer)
        XCTAssertFalse(presentation.canChangePause)
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
        XCTAssertTrue(playback.isUserSpeaking)
        XCTAssertFalse(playback.assistantResponseActive)
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

/// The production surface is hosted without a call, account, microphone, or
/// network. Native AX actions run on simulator; rendering runs on iPhone too.
@MainActor
final class VoiceTutorAnswerPausePresentationTests: XCTestCase {
    func testAnswerPauseCompanionRendersInCompactAndTranscriptAtSmallAndLargeTextSizes() async throws {
        for fixture in AnswerPauseFixture.all {
            let harness = try AnswerPauseHarness(fixture: fixture)
            defer { harness.close() }
            try await harness.settle()
            attach(harness, name: "answer-pause-\(fixture.name)-listening")
            harness.probe.requestPauseOrResume()
            XCTAssertTrue(harness.probe.acknowledge(paused: true))
            try await harness.settle()
            attach(harness, name: "answer-pause-\(fixture.name)-paused")
            XCTAssertEqual(harness.probe.draft.phase, .listening)
            XCTAssertEqual(harness.probe.draft.text, AnswerPauseProbe.originalText)
            XCTAssertEqual(harness.probe.finishCount, 0)
            XCTAssertTrue(harness.probe.pause.holdsMicrophone)
        }
    }

    func testAnswerPauseCompanionUsesOneNativeButtonAndRejectsPendingRepeatedActions() async throws {
        #if !targetEnvironment(simulator)
        // The physical hosted XCTest process does not expose SwiftUI AX nodes.
        // Keep its mandatory production rendering test above; the simulator
        // performs the exact public accessibility actions and trait checks.
        throw XCTSkip("Native SwiftUI accessibility-node activation is verified on simulator; physical rendering remains required")
        #endif
        let reference = try await disabledReferenceMetadata()
        for fixture in AnswerPauseFixture.all {
            let harness = try AnswerPauseHarness(fixture: fixture)
            defer { harness.close() }
            try await harness.settle()
            let strings = AppStrings(language: .korean)
            let pauseButtons = harness.buttons(label: strings.voiceTutorTakeBreak)
            XCTAssertEqual(pauseButtons.count, 1, "Compact and transcript placeholders must share one interactive pause button")
            let pauseButton = try XCTUnwrap(pauseButtons.first)
            let finish = try XCTUnwrap(harness.buttons(label: strings.voiceTutorAnswerFinish).first)
            XCTAssertGreaterThan(pauseButton.accessibilityFrame.height, 0)
            XCTAssertGreaterThanOrEqual(pauseButton.accessibilityFrame.minY, finish.accessibilityFrame.maxY - 1,
                "The companion belongs below the Answer Finish circle")
            XCTAssertFalse(pauseButton.accessibilityTraits.contains(.notEnabled))
            XCTAssertTrue(pauseButton.accessibilityActivate())
            try await harness.settle()
            XCTAssertEqual(harness.probe.pause.mode, .pausing)
            XCTAssertEqual(harness.probe.pauseCount, 1)
            let pendingPause = try XCTUnwrap(harness.companionButton(label: strings.voiceTutorTakeBreak))
            XCTAssertEqual(pendingPause.accessibilityTraits.contains(.notEnabled), reference.notEnabled,
                "The pending pill should expose the same disabled metadata as a standard SwiftUI disabled button. \(reference.description); \(metadata(pendingPause))")
            attachMetadata(pendingPause, name: "answer-pause-\(fixture.name)-pending-native-ax")
            _ = pendingPause.accessibilityActivate()
            try await harness.settle()
            XCTAssertEqual(harness.probe.pauseCount, 1, "A pending pause must not deliver another callback")
            XCTAssertEqual(harness.probe.pause.mode, .pausing)
            XCTAssertEqual(harness.probe.pause.sequence, 1)
            XCTAssertEqual(harness.probe.draft.text, AnswerPauseProbe.originalText)
            XCTAssertEqual(harness.probe.finishCount, 0)

            XCTAssertTrue(harness.probe.acknowledge(paused: true))
            try await harness.settle()
            XCTAssertTrue(harness.buttons(label: strings.voiceTutorAnswerFinish).isEmpty,
                "The paused call must not offer a Finish action that its view model rejects")
            let resume = try XCTUnwrap(harness.companionButton(label: strings.voiceTutorResumeLesson))
            XCTAssertFalse(resume.accessibilityTraits.contains(.notEnabled))
            XCTAssertTrue(resume.accessibilityActivate())
            try await harness.settle()
            XCTAssertEqual(harness.probe.pause.mode, .resuming)
            XCTAssertEqual(harness.probe.pauseCount, 2)
            let pendingResume = try XCTUnwrap(harness.companionButton(label: strings.voiceTutorResumeLesson))
            XCTAssertEqual(pendingResume.accessibilityTraits.contains(.notEnabled), reference.notEnabled,
                "The pending pill should expose the same disabled metadata as a standard SwiftUI disabled button. \(reference.description); \(metadata(pendingResume))")
            attachMetadata(pendingResume, name: "answer-pause-\(fixture.name)-resuming-native-ax")
            _ = pendingResume.accessibilityActivate()
            try await harness.settle()
            XCTAssertEqual(harness.probe.pauseCount, 2, "A pending resume must not deliver another callback")
            XCTAssertEqual(harness.probe.pause.mode, .resuming)
            XCTAssertEqual(harness.probe.pause.sequence, 2)
            XCTAssertEqual(harness.probe.draft.text, AnswerPauseProbe.originalText)
            XCTAssertEqual(harness.probe.finishCount, 0)
            XCTAssertTrue(harness.probe.acknowledge(paused: false))
            try await harness.settle()
            XCTAssertEqual(harness.probe.pause.mode, .active)
            XCTAssertEqual(harness.probe.draft.text, AnswerPauseProbe.originalText)
            XCTAssertEqual(harness.probe.draft.phase, .listening)
            XCTAssertEqual(harness.probe.finishCount, 0)
            XCTAssertEqual(harness.buttons(label: strings.voiceTutorTakeBreak).count, 1)
            XCTAssertEqual(harness.buttons(label: strings.voiceTutorAnswerFinish).count, 1)
            attach(harness, name: "answer-pause-\(fixture.name)-resumed-actions")
        }
    }

    private func disabledReferenceMetadata() async throws -> (notEnabled: Bool, description: String) {
        let harness = try AnswerPauseHarness(fixture: AnswerPauseFixture.all[0], disabledReferenceOnly: true)
        defer { harness.close() }
        try await harness.settle()
        let button = try XCTUnwrap(harness.buttons(label: "Disabled accessibility reference").first)
        // Hosted SwiftUI uses an AccessibilityNode proxy rather than UIButton.
        // Compare against the framework's own disabled button in this runtime,
        // and separately verify that the production action cannot reenter.
        attachMetadata(button, name: "answer-pause-standard-disabled-swiftui-native-ax")
        return (button.accessibilityTraits.contains(.notEnabled), metadata(button))
    }

    private func metadata(_ node: NSObject) -> String {
        "node=\(type(of: node)) traits=\(node.accessibilityTraits.rawValue) responds=\(node.accessibilityRespondsToUserInteraction) label=\(node.accessibilityLabel ?? "nil")"
    }

    private func attachMetadata(_ node: NSObject, name: String) {
        let attachment = XCTAttachment(string: metadata(node))
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attach(_ harness: AnswerPauseHarness, name: String) {
        harness.layout()
        let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
            XCTAssertTrue(harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 1_024)
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private struct AnswerPauseFixture {
    let name: String
    let size: CGSize
    let dynamicType: DynamicTypeSize
    let transcript: Bool

    static let all: [Self] = [
        .init(name: "compact-small", size: .init(width: 320, height: 696), dynamicType: .large, transcript: false),
        .init(name: "transcript-small", size: .init(width: 320, height: 696), dynamicType: .large, transcript: true),
        .init(name: "compact-accessibility", size: .init(width: 414, height: 896), dynamicType: .accessibility2, transcript: false),
        .init(name: "transcript-accessibility", size: .init(width: 414, height: 896), dynamicType: .accessibility2, transcript: true)
    ]
}

@MainActor
private final class AnswerPauseProbe: ObservableObject {
    static let originalText = "지금까지 말한 답변입니다.\n조금 생각한 뒤 이어서 설명하겠습니다."
    @Published var pause = VoiceTutorCallPauseState()
    @Published var session = VoiceTutorSessionState()
    @Published var draft = VoiceTutorAnswerDraftState()
    @Published var showsTranscript: Bool
    @Published var showsSummary = false
    private(set) var pauseCount = 0
    private(set) var finishCount = 0

    init(transcript: Bool) {
        showsTranscript = transcript
        pause.isSupported = true
        let answerID = "11111111-2222-3333-4444-555555555555"
        _ = draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 7,
            phase: .listening, text: nil, code: nil), existingDraft: Self.originalText)
        _ = session.apply(.init(sequence: 1, phase: .answering, paused: false,
            revision: 7, studyID: 42, recordID: "101", answerID: answerID))
    }

    func requestPauseOrResume() {
        pauseCount += 1
        if pause.mode == .active { _ = pause.requestPause() }
        else if pause.mode == .paused { _ = pause.requestResume() }
    }

    func acknowledge(paused: Bool) -> Bool {
        guard pause.acknowledge(sequence: pause.sequence, paused: paused) else { return false }
        return session.apply(.init(sequence: (session.snapshot?.sequence ?? 0) + 1,
            phase: .answering, paused: paused, revision: 7,
            studyID: 42, recordID: "101", answerID: draft.answerID))
    }

    func finishAnswer() { finishCount += 1 }
}

private struct AnswerPauseTestParent: View {
    @ObservedObject var probe: AnswerPauseProbe
    let dynamicType: DynamicTypeSize

    var body: some View {
        VoiceTutorCallScreen(topic: "스프링",
            presentation: VoiceTutorCallPresentation(phase: .listening, pauseState: probe.pause,
                sessionState: probe.session, sessionSecondsRemaining: 3_000),
            strings: AppStrings(language: .korean), errorMessage: nil,
            showsTranscript: $probe.showsTranscript, showsSummary: $probe.showsSummary,
            onPause: { probe.requestPauseOrResume() }, answerDraftState: probe.draft,
            answerDraftText: Binding(get: { probe.draft.text }, set: { _ = probe.draft.edit($0) }),
            onFinishAnswer: { probe.finishAnswer() })
            .dynamicTypeSize(dynamicType)
            .environment(\.locale, Locale(identifier: "ko_KR"))
    }
}

@MainActor
private final class AnswerPauseHarness {
    let probe: AnswerPauseProbe
    let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init(fixture: AnswerPauseFixture, disabledReferenceOnly: Bool = false) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        probe = AnswerPauseProbe(transcript: fixture.transcript)
        window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: fixture.size)
        window.overrideUserInterfaceStyle = .dark
        if disabledReferenceOnly {
            window.rootViewController = UIHostingController(rootView:
                Button("Disabled accessibility reference") { [probe] in probe.requestPauseOrResume() }
                    .disabled(true))
        } else {
            window.rootViewController = UIHostingController(rootView: AnswerPauseTestParent(probe: probe, dynamicType: fixture.dynamicType))
        }
        window.makeKeyAndVisible()
        layout()
    }

    func settle() async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(400))
        layout()
    }

    func layout() {
        window.setNeedsLayout()
        window.layoutIfNeeded()
        window.rootViewController?.view.layoutIfNeeded()
    }

    func buttons(label: String) -> [NSObject] {
        var visited = Set<ObjectIdentifier>()
        var result: [NSObject] = []
        func visit(_ object: NSObject) {
            guard visited.insert(ObjectIdentifier(object)).inserted else { return }
            if object.accessibilityLabel == label && object.accessibilityTraits.contains(.button) { result.append(object) }
            if #available(iOS 17.0, *) {
                for child in (object.automationElements ?? []).compactMap({ $0 as? NSObject }) { visit(child) }
            }
            for child in (object.accessibilityElements ?? []).compactMap({ $0 as? NSObject }) { visit(child) }
            let count = object.accessibilityElementCount()
            if count > 0, count < 512 {
                for index in 0..<count {
                    if let child = object.accessibilityElement(at: index) as? NSObject { visit(child) }
                }
            }
            for child in (object as? UIView)?.subviews ?? [] { visit(child) }
        }
        visit(window)
        return result
    }

    func companionButton(label: String) -> NSObject? {
        // The circle and companion intentionally share Continue while paused.
        // The companion is the semantic button below that circle, in either
        // compact or transcript geometry; never activate the circle by mistake.
        buttons(label: label).max { $0.accessibilityFrame.midY < $1.accessibilityFrame.midY }
    }

    func close() {
        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}
