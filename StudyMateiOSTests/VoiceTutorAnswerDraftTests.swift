import Foundation
import XCTest
@testable import StudyMate

/// Protocol and draft state only: no microphone, provider, account or device IO.
final class VoiceTutorAnswerDraftTests: XCTestCase {
    private let answerID = "11111111-2222-3333-4444-555555555555"

    func testAnswerStateRequiresExactOwnedQuestionIdentityAndKnownPhase() throws {
        XCTAssertEqual(try parse(fields()), .answerState(event(.listening)))
        for key in ["answerId", "studyId", "recordId", "revision", "phase"] {
            var invalid = fields(); invalid.removeValue(forKey: key)
            XCTAssertEqual(try parse(invalid), .ignored(type: "buddystudy.voice.answer.state"), key)
        }
        for (key, value) in [("answerId", "not-a-uuid" as Any), ("studyId", true), ("studyId", "42"),
                             ("studyId", 1.5), ("recordId", 101), ("recordId", "0101"),
                             ("recordId", "9223372036854775808"), ("revision", -1), ("revision", true),
                             ("phase", "inactive"), ("phase", "unknown"), ("code", "free-form text")] {
            var invalid = fields(); invalid[key] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: "buddystudy.voice.answer.state"), key)
        }
        var oversized = fields(); oversized["text"] = String(repeating: "a", count: 8_001)
        XCTAssertEqual(try parse(oversized), .ignored(type: "buddystudy.voice.answer.state"))
        var extra = fields(); extra["answer"] = "unexpected"
        XCTAssertEqual(try parse(extra), .ignored(type: "buddystudy.voice.answer.state"))
    }

    func testFinalAnswerPartsRequireAnItemIdentityAndPositiveIntegerSequence() throws {
        let part = segment(1, "인식된 답변")
        let valid: [String: Any] = ["type": "buddystudy.voice.answer.transcript", "answerId": answerID,
            "recordId": "101", "itemId": "item-1", "sequence": 1, "text": part.text]
        XCTAssertEqual(try parse(valid), .answerTranscript(part))
        for value in [0, -1, true, 1.5, "1"] as [Any] {
            var invalid = valid; invalid["sequence"] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: "buddystudy.voice.answer.transcript"))
        }
        var missing = valid; missing.removeValue(forKey: "itemId")
        XCTAssertEqual(try parse(missing), .ignored(type: "buddystudy.voice.answer.transcript"))
        XCTAssertEqual(try parse(["type": "conversation.item.input_audio_transcription.completed",
                                 "item_id": "item-1", "transcript": "원본"]), .userTranscript("원본", itemID: "item-1"))
    }

    func testNewPartsAppendAfterEditsAndReviewCannotReplaceTheEditedPrefix() throws {
        var state = listening()
        XCTAssertTrue(state.append(segment(1, "잘못 인식된 첫 문장")))
        XCTAssertTrue(state.edit("내가 수정한 첫 문장"))
        XCTAssertTrue(state.append(segment(2, "새로운 두 번째 문장")))
        XCTAssertNotNil(state.requestFinish())
        XCTAssertTrue(state.append(segment(3, "마지막 문장")))
        XCTAssertTrue(state.apply(event(.review, text: "잘못 인식된 첫 문장\n새로운 두 번째 문장\n마지막 문장")))
        XCTAssertEqual(state.text, "내가 수정한 첫 문장\n새로운 두 번째 문장\n마지막 문장")
        XCTAssertEqual(state.recognizedText, "잘못 인식된 첫 문장\n새로운 두 번째 문장\n마지막 문장")
        XCTAssertTrue(state.canSubmit)
        XCTAssertEqual(state.requestSubmit()?.text, state.text)
        XCTAssertNil(state.requestSubmit(), "One deliberate tap must not enqueue two submissions")
    }

    func testPartsRemainOrderedAndDuplicatesOrUnrelatedAnswersCannotAppend() {
        var state = listening()
        XCTAssertTrue(state.append(segment(2, "두 번째")))
        XCTAssertEqual(state.text, "")
        XCTAssertTrue(state.edit("직접 쓴 시작"))
        XCTAssertTrue(state.append(segment(1, "첫 번째")))
        XCTAssertEqual(state.text, "직접 쓴 시작\n첫 번째\n두 번째")
        XCTAssertFalse(state.append(segment(1, "변경된 재전송")))
        XCTAssertFalse(state.append(VoiceTutorAnswerTranscriptEvent(answerID: UUID().uuidString,
            recordID: "101", itemID: "foreign", sequence: 3, text: "다른 답변")))
        XCTAssertFalse(state.append(VoiceTutorAnswerTranscriptEvent(answerID: answerID,
            recordID: "102", itemID: "foreign", sequence: 3, text: "다른 문제")))
        XCTAssertEqual(state.text, "직접 쓴 시작\n첫 번째\n두 번째")
    }

    func testExistingKeyboardDraftIsNeitherReplacedNorEligibleForAutomaticSaving() {
        var state = listening(existing: "이전에 쓰던 키보드 초안")
        XCTAssertTrue(state.append(segment(1, "새 음성 구간")))
        XCTAssertNotNil(state.requestFinish())
        XCTAssertTrue(state.apply(event(.review, text: "새 음성 구간")))
        XCTAssertEqual(state.text, "이전에 쓰던 키보드 초안\n새 음성 구간")
        XCTAssertFalse(state.shouldPersistAutomatically)
        XCTAssertTrue(state.edit("사용자가 합쳐서 확정할 답변"))
        XCTAssertTrue(state.shouldPersistAutomatically)
    }

    func testUntouchedEmptyDraftCanRecoverFinalReviewTextButTypedTextWins() {
        var untouched = listening()
        _ = untouched.requestFinish()
        XCTAssertTrue(untouched.apply(event(.review, text: "서버의 마지막 인식 결과")))
        XCTAssertEqual(untouched.text, "서버의 마지막 인식 결과")
        var typed = listening()
        XCTAssertTrue(typed.edit("음성 인식 없이 직접 입력"))
        _ = typed.requestFinish()
        XCTAssertTrue(typed.apply(event(.review, text: "", code: "ANSWER_TRANSCRIPT_INCOMPLETE")))
        XCTAssertEqual(typed.text, "음성 인식 없이 직접 입력")
        XCTAssertTrue(typed.canSubmit)
    }

    func testFinishingClosesTheInputUntilReviewedSubmissionOrCancellationCompletes() {
        var state = listening()
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertNotNil(state.requestFinish())
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertNil(state.requestFinish())
        XCTAssertFalse(state.apply(event(.listening)), "Late listening must not reopen the microphone")
        XCTAssertTrue(state.apply(event(.review, text: "답변")))
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertNotNil(state.requestSubmit())
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(state.apply(event(.submitted, text: "원본 인식문")))
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertEqual(state.text, "답변", "The acknowledged typed answer stays authoritative")
    }

    func testSubmissionFailureKeepsEditsAndAllowsShortenedTypedRetry() {
        var state = listening(existing: "보존할 초안")
        _ = state.requestFinish()
        _ = state.apply(event(.review))
        _ = state.requestSubmit()
        XCTAssertTrue(state.apply(event(.failed, text: "다른 서버 원본", code: "ANSWER_SUBMISSION_FAILED")))
        XCTAssertEqual(state.text, "보존할 초안")
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(state.edit(String(repeating: "a", count: 8_001)))
        XCTAssertFalse(state.canSubmit)
        XCTAssertTrue(state.apply(event(.failed, code: "ANSWER_TOO_LONG")))
        XCTAssertTrue(state.edit("줄여서 다시 제출"))
        XCTAssertTrue(state.canSubmit)
        XCTAssertEqual(state.requestSubmit()?.text, "줄여서 다시 제출")
    }

    func testLateASRNeverChangesReviewedFailedOrSubmittedUserText() {
        for target in [VoiceTutorAnswerDraftState.Phase.review, .failed, .submitted] {
            var state = listening()
            _ = state.edit("사용자가 검토한 답변")
            _ = state.requestFinish()
            _ = state.apply(event(.review, text: "다른 원문"))
            if target == .submitted { _ = state.requestSubmit() }
            if target != .review { XCTAssertTrue(state.apply(event(target))) }
            XCTAssertFalse(state.append(segment(1, "늦은 미검토 ASR")))
            XCTAssertEqual(state.text, "사용자가 검토한 답변")
        }
    }

    func testFinishSkipAndLocalEndNeverEraseTheEditedDraft() {
        var state = listening(existing: "기존 키보드 초안")
        XCTAssertTrue(state.edit("사용자가 수정한 초안"))
        XCTAssertEqual(state.requestSkip()?.kind, .skip)
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(state.apply(event(.cancelled)))
        XCTAssertEqual(state.text, "사용자가 수정한 초안")
        var ended = listening()
        _ = ended.edit("백그라운드 전 편집")
        ended.endLocally()
        XCTAssertEqual(ended.phase, .cancelled)
        XCTAssertEqual(ended.text, "백그라운드 전 편집")
        XCTAssertTrue(ended.hasUserEdited)
    }

    func testOldAnswerOrRevisionCannotReviveOrReplaceTheCurrentDraft() {
        var state = listening()
        _ = state.edit("현재 초안")
        XCTAssertFalse(state.apply(VoiceTutorAnswerStateEvent(answerID: answerID, studyID: 42, recordID: "102",
            revision: 1, phase: .review, text: "다른 문제", code: nil)))
        XCTAssertFalse(state.apply(VoiceTutorAnswerStateEvent(answerID: UUID().uuidString, studyID: 42, recordID: "102",
            revision: 2, phase: .listening, text: nil, code: nil)))
        state.endLocally()
        XCTAssertFalse(state.apply(event(.listening)))
        XCTAssertEqual(state.text, "현재 초안")
    }

    func testAnswerControlsPreserveExactUserTextAndRejectInvalidOrOversizedSubmission() throws {
        let typed = "  수정한 답변\n공백도 유지  "
        let control = VoiceTutorAnswerControl(kind: .submit, answerID: answerID, recordID: "101", text: typed)
        let payload = try VoiceTutorTurnProtocol.payload(for: .answer(control))
        XCTAssertEqual(Set(payload.keys), ["type", "answerId", "recordId", "text"])
        XCTAssertEqual(payload["text"] as? String, typed)
        XCTAssertEqual(payload["type"] as? String, "buddystudy.voice.answer.submit")
        for invalid in ["", " \n ", String(repeating: "a", count: 8_001), String(repeating: "😀", count: 4_001)] {
            XCTAssertThrowsError(try VoiceTutorTurnProtocol.payload(for: .answer(VoiceTutorAnswerControl(
                kind: .submit, answerID: answerID, recordID: "101", text: invalid))))
        }
        XCTAssertThrowsError(try VoiceTutorTurnProtocol.payload(for: .answer(VoiceTutorAnswerControl(
            kind: .finish, answerID: answerID, recordID: "101", text: "unexpected"))))
    }

    func testAnswerFinishSharesTheOrderedSpeechControlQueue() async throws {
        let controls = VoiceTutorCallControlEventStream()
        let started = VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        let stopped = VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1)
        let finish = VoiceTutorAnswerControl(kind: .finish, answerID: answerID, recordID: "101")
        controls.yield(started)
        controls.yield(stopped)
        controls.yield(finish)
        controls.finish()
        var delivered: [VoiceTutorCallControlEvent] = []
        for try await event in controls.stream { delivered.append(event) }
        XCTAssertEqual(delivered, [.speech(started), .speech(stopped), .answer(finish)])
    }

    private func listening(existing: String = "") -> VoiceTutorAnswerDraftState {
        var state = VoiceTutorAnswerDraftState()
        XCTAssertTrue(state.apply(event(.listening), existingDraft: existing))
        return state
    }
    private func event(_ phase: VoiceTutorAnswerDraftState.Phase, text: String? = nil, code: String? = nil) -> VoiceTutorAnswerStateEvent {
        VoiceTutorAnswerStateEvent(answerID: answerID, studyID: 42, recordID: "101", revision: 1, phase: phase, text: text, code: code)
    }
    private func segment(_ sequence: Int64, _ text: String) -> VoiceTutorAnswerTranscriptEvent {
        VoiceTutorAnswerTranscriptEvent(answerID: answerID, recordID: "101", itemID: "item-\(sequence)", sequence: sequence, text: text)
    }
    private func fields() -> [String: Any] {
        ["type": "buddystudy.voice.answer.state", "answerId": answerID, "studyId": 42, "recordId": "101", "revision": 1, "phase": "listening"]
    }
    private func parse(_ value: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: value))
    }
}
