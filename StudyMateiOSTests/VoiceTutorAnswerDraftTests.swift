import Foundation
import Combine
import SwiftUI
import UIKit
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

    func testCancelExercisePreservesDraftAndHoldsInputUntilNextActionCard() throws {
        for target in [VoiceTutorAnswerDraftState.Phase.listening, .finalizing, .review, .failed] {
            var state = listening(existing: "기존 초안")
            _ = state.edit("직접 수정한 답변\n미제출 상태")
            if target != .listening { _ = state.requestFinish() }
            if [.review, .failed].contains(target) { _ = state.apply(event(target)) }
            let command = try XCTUnwrap(state.requestCancel())
            XCTAssertEqual(command.kind, .cancel)
            XCTAssertNil(command.text)
            XCTAssertEqual(state.phase, .finalizing)
            XCTAssertTrue(state.isCancelling)
            XCTAssertTrue(state.holdsMicrophone)
            XCTAssertNil(state.requestCancel(), "A second tap cannot enqueue another cancellation")
            XCTAssertFalse(state.append(segment(1, "늦은 인식 결과")))
            XCTAssertFalse(state.apply(event(.review, text: "늦게 온 검토 답변")))
            XCTAssertFalse(state.edit("취소 요청 후 입력"))
            XCTAssertTrue(state.apply(event(.cancelled, code: "ANSWER_CANCELLED")))
            XCTAssertFalse(state.isActive)
            XCTAssertTrue(state.holdsMicrophone, "Keep the cancelled answer tail out of ordinary input until the next-step card owns the hold")
            XCTAssertTrue(state.awaitingCancellationChoices)
            XCTAssertEqual(state.text, "직접 수정한 답변\n미제출 상태")
            XCTAssertFalse(state.append(segment(2, "취소 이후 음성")))
            state.didReceiveCancellationChoices()
            XCTAssertFalse(state.holdsMicrophone)
            XCTAssertFalse(state.awaitingCancellationChoices)
            XCTAssertTrue(state.apply(event(.cancelled, code: "ANSWER_CANCELLED")))
            XCTAssertFalse(state.holdsMicrophone, "A replayed cancellation ACK must not acquire a new hold after the choice card")
            XCTAssertEqual(state.text, "직접 수정한 답변\n미제출 상태")
        }
    }

    func testCancellationNeverClaimsToUndoSubmittedOrAlreadySkippingQuestions() {
        var submitting = listening(existing: "최종 답변")
        _ = submitting.requestFinish()
        _ = submitting.apply(event(.review))
        _ = submitting.requestSubmit()
        XCTAssertNil(submitting.requestCancel())
        _ = submitting.apply(event(.submitted))
        XCTAssertNil(submitting.requestCancel())
        var skipping = listening()
        _ = skipping.requestSkip()
        XCTAssertNil(skipping.requestCancel())
        var ending = listening(existing: "보존할 답변")
        _ = ending.requestCancel()
        _ = ending.apply(event(.cancelled, code: "ANSWER_CANCELLED"))
        ending.endLocally()
        XCTAssertFalse(ending.holdsMicrophone)
        XCTAssertEqual(ending.text, "보존할 답변")
    }

    func testCancelControlUsesExactAnswerIdentityWithoutTextOrSkipMutation() throws {
        let command = VoiceTutorAnswerControl(kind: .cancel, answerID: answerID, recordID: "101")
        let payload = try VoiceTutorTurnProtocol.payload(for: .answer(command))
        XCTAssertEqual(Set(payload.keys), ["type", "answerId", "recordId"])
        XCTAssertEqual(payload["type"] as? String, "buddystudy.voice.answer.cancel")
        XCTAssertEqual(payload["answerId"] as? String, answerID)
        XCTAssertEqual(payload["recordId"] as? String, "101")
        XCTAssertThrowsError(try VoiceTutorTurnProtocol.payload(for: .answer(VoiceTutorAnswerControl(
            kind: .cancel, answerID: answerID, recordID: "101", text: "제출하면 안 되는 초안"))))
        var fields = fields()
        fields["phase"] = "cancelled"
        fields["code"] = "ANSWER_CANCELLED"
        XCTAssertEqual(try parse(fields), .answerState(event(.cancelled, code: "ANSWER_CANCELLED")))
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

/// Exercises the real editor's UIKit text-input surface in a presented window.
/// Programmatic caret/IME operations do not simulate a keyboard trackpad press.
@MainActor
final class VoiceTutorAnswerEditorTests: XCTestCase {
    func testNativeEditorMovesCaretBetweenKoreanLines() async throws {
        let harness = try VoiceTutorAnswerEditorHarness(text: "첫째 줄 시작\n둘째 줄 가운데\n셋째 줄 끝")
        defer { harness.close() }
        let editor = try await harness.presentedEditor()
        XCTAssertTrue(editor.isScrollEnabled)
        XCTAssertTrue(editor.isEditable)
        let start = try XCTUnwrap(editor.position(from: editor.beginningOfDocument, offset: 2))
        let down = try XCTUnwrap(editor.position(from: start, in: .down, offset: 1))
        let downOffset = editor.offset(from: editor.beginningOfDocument, to: down)
        let secondLine = (harness.probe.text as NSString).range(of: "둘째 줄 가운데")
        XCTAssertTrue(NSLocationInRange(downOffset, secondLine), "Moving down must reach the next visual line")
        XCTAssertGreaterThan(editor.caretRect(for: down).minY, editor.caretRect(for: start).minY)
        editor.selectedTextRange = editor.textRange(from: down, to: down)
        XCTAssertEqual(editor.selectedRange, NSRange(location: downOffset, length: 0))

        let up = try XCTUnwrap(editor.position(from: down, in: .up, offset: 1))
        XCTAssertLessThan(editor.caretRect(for: up).minY, editor.caretRect(for: down).minY)
        XCTAssertLessThan(editor.offset(from: editor.beginningOfDocument, to: up), secondLine.location)
        XCTAssertEqual(harness.probe.text, "첫째 줄 시작\n둘째 줄 가운데\n셋째 줄 끝")
        attach(harness, name: "voice-answer-editor-regular")
    }

    func testNativeSelectionAndScrollSurviveUnrelatedParentUpdates() async throws {
        let text = (1...24).map { "\($0)번째 줄: 스프링의 역할을 설명합니다." }.joined(separator: "\n")
        let harness = try VoiceTutorAnswerEditorHarness(text: text)
        defer { harness.close() }
        let editor = try await harness.presentedEditor()
        let selection = (text as NSString).range(of: "12번째 줄")
        XCTAssertNotEqual(selection.location, NSNotFound)
        editor.selectedRange = selection
        editor.scrollRangeToVisible(selection)
        editor.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        let offset = editor.contentOffset

        for _ in 0..<5 {
            harness.probe.parentRevision += 1
            await Task.yield()
            harness.layout()
            try await Task.sleep(for: .milliseconds(20))
        }

        let currentEditor = try XCTUnwrap(harness.findEditor())
        XCTAssertTrue(currentEditor === editor, "A call timer or status update must not replace the text-input view")
        XCTAssertTrue(editor.isFirstResponder)
        XCTAssertEqual(editor.selectedRange, selection)
        XCTAssertEqual(editor.contentOffset.y, offset.y, accuracy: 2)
        XCTAssertEqual(harness.probe.text, text)
    }

    func testNativeKoreanAndNewlineEditsUpdateTheExactDraftBinding() async throws {
        let initial = "  첫째 줄 👩‍💻\n수정 전 답변\n마지막 줄  "
        let harness = try VoiceTutorAnswerEditorHarness(text: initial)
        defer { harness.close() }
        let editor = try await harness.presentedEditor()
        let replacedRange = (initial as NSString).range(of: "수정 전 답변")
        let replacement = "수정한 답변\n직접 추가한 문장"
        editor.selectedRange = replacedRange
        editor.insertText(replacement)
        let expected = (initial as NSString).replacingCharacters(in: replacedRange, with: replacement)
        try await waitForBinding(harness.probe, toEqual: expected)
        XCTAssertEqual(editor.text, expected)
        XCTAssertEqual(editor.selectedRange.location, replacedRange.location + replacement.utf16.count)
        XCTAssertEqual(editor.selectedRange.length, 0)

        editor.insertText("\n추가 설명")
        let final = (expected as NSString).replacingCharacters(
            in: NSRange(location: replacedRange.location + replacement.utf16.count, length: 0),
            with: "\n추가 설명"
        )
        try await waitForBinding(harness.probe, toEqual: final)
        XCTAssertEqual(editor.text, final, "Native editing must preserve surrounding whitespace, emoji, and paragraph breaks")
    }

    func testMarkedKoreanTextSurvivesAnUnrelatedParentUpdate() async throws {
        let harness = try VoiceTutorAnswerEditorHarness(text: "답변: ")
        defer { harness.close() }
        let editor = try await harness.presentedEditor()
        editor.selectedRange = NSRange(location: harness.probe.text.utf16.count, length: 0)
        editor.setMarkedText("ㅎ", selectedRange: NSRange(location: 1, length: 0))
        editor.setMarkedText("한", selectedRange: NSRange(location: 1, length: 0))
        let selection = editor.selectedRange
        let marked = try XCTUnwrap(editor.markedTextRange)
        XCTAssertEqual(editor.text(in: marked), "한")

        harness.probe.parentRevision += 1
        await Task.yield()
        harness.layout()
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertTrue(harness.findEditor() === editor)
        let preservedMarked = try XCTUnwrap(editor.markedTextRange, "A parent refresh must not commit or discard IME composition")
        XCTAssertEqual(editor.text(in: preservedMarked), "한")
        XCTAssertEqual(editor.selectedRange, selection)
        editor.unmarkText()
        editor.insertText("글\n다음 줄")
        try await waitForBinding(harness.probe, toEqual: "답변: 한글\n다음 줄")
        XCTAssertNil(editor.markedTextRange)
    }

    func testAccessibilityEditorKeepsScrollableMultilineContent() async throws {
        let text = (1...12).map { "\($0)번째 문장입니다. 답변을 읽고 수정해 주세요." }.joined(separator: "\n")
        let harness = try VoiceTutorAnswerEditorHarness(text: text, dynamicType: .accessibility3)
        defer { harness.close() }
        let editor = try await harness.presentedEditor()
        XCTAssertTrue(editor.isScrollEnabled)
        XCTAssertTrue(editor.isEditable)
        XCTAssertGreaterThan(editor.bounds.height, 100, "Large text must retain a usable independent editing viewport")
        XCTAssertGreaterThan(editor.contentSize.height, editor.bounds.height)
        let end = NSRange(location: text.utf16.count, length: 0)
        editor.selectedRange = end
        editor.scrollRangeToVisible(end)
        editor.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertGreaterThan(editor.contentOffset.y, 0, "The last paragraph must remain reachable with large text")
        XCTAssertEqual(editor.selectedRange, end)
        XCTAssertEqual(harness.probe.text, text)
        attach(harness, name: "voice-answer-editor-accessibility3")
    }

    private func waitForBinding(_ probe: VoiceTutorAnswerEditorProbe, toEqual expected: String) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while probe.text != expected && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(probe.text, expected)
    }

    private func attach(_ harness: VoiceTutorAnswerEditorHarness, name: String) {
        harness.layout()
        let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
            harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// Hosts the production call surface without a microphone, account, or network.
@MainActor
final class VoiceTutorLearningCancelPresentationTests: XCTestCase {
    func testLearningCancelNativeActionPreservesDraftWhilePendingInActiveAndPausedCalls() async throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Native SwiftUI accessibility-node activation is verified on simulator; physical call rendering is covered separately")
        #endif
        for showsTranscript in [false, true] {
            for paused in [false, true] {
                let harness = try VoiceTutorLearningCancelHarness(transcript: showsTranscript, paused: paused)
                defer { harness.close() }
                try await harness.settle()
                let buttons = harness.cancelButtons()
                XCTAssertEqual(buttons.count, 1, "Only the visible compact or transcript surface may expose the learning-cancel action")
                let button = try XCTUnwrap(buttons.first)
                XCTAssertFalse(button.accessibilityTraits.contains(.notEnabled), "Learning cancellation remains available while the conversation is paused")
                XCTAssertGreaterThan(button.accessibilityFrame.height, 0)
                XCTAssertTrue(button.accessibilityActivate(), "Activate the production SwiftUI button, rather than invoking its callback directly")
                try await harness.settle()

                let probe = harness.probe
                XCTAssertEqual(probe.cancelCallbackCount, 1)
                XCTAssertEqual(probe.commands, [VoiceTutorAnswerControl(kind: .cancel,
                    answerID: VoiceTutorLearningCancelProbe.answerID, recordID: "101")])
                XCTAssertEqual(probe.draft.phase, .finalizing)
                XCTAssertTrue(probe.draft.isCancelling)
                XCTAssertTrue(probe.draft.holdsMicrophone)
                XCTAssertFalse(probe.draft.canCancel)
                XCTAssertEqual(probe.draft.text, VoiceTutorLearningCancelProbe.originalText)
                XCTAssertEqual(probe.pause.holdsMicrophone, paused, "Cancelling an exercise must not resume or pause the conversation")
                XCTAssertEqual(probe.otherActionCount, 0, "Cancel must not finish, submit, skip, pause, or end the call")
                XCTAssertTrue(harness.cancelButtons().isEmpty, "A pending cancellation must remove the repeat-tap action")

                XCTAssertTrue(probe.acknowledgeCancellation())
                try await harness.settle()
                XCTAssertEqual(probe.draft.phase, .cancelled)
                XCTAssertFalse(probe.draft.isActive)
                XCTAssertTrue(probe.draft.awaitingCancellationChoices)
                XCTAssertTrue(probe.draft.holdsMicrophone, "The next-step card must take ownership before ordinary listening resumes")
                XCTAssertEqual(probe.draft.text, VoiceTutorLearningCancelProbe.originalText)
                XCTAssertTrue(harness.cancelButtons().isEmpty)
                XCTAssertEqual(probe.cancelCallbackCount, 1)
                XCTAssertEqual(probe.otherActionCount, 0)
            }
        }
    }
}

@MainActor
private final class VoiceTutorLearningCancelProbe: ObservableObject {
    static let answerID = "11111111-2222-3333-4444-555555555555"
    static let originalText = "  작성 중인 답변입니다.\n다른 학습을 해도 이 초안은 보존합니다.  "
    @Published var draft = VoiceTutorAnswerDraftState()
    @Published var pause = VoiceTutorCallPauseState()
    @Published var session = VoiceTutorSessionState()
    @Published var showsTranscript: Bool
    @Published var showsSummary = false
    private(set) var cancelCallbackCount = 0
    private(set) var otherActionCount = 0
    private(set) var commands: [VoiceTutorAnswerControl] = []

    init(transcript: Bool, paused: Bool) {
        showsTranscript = transcript
        pause.isSupported = true
        if paused, let command = pause.requestPause() {
            _ = pause.acknowledge(sequence: command.sequence, paused: true)
        }
        _ = draft.apply(.init(answerID: Self.answerID, studyID: 42, recordID: "101", revision: 7,
            phase: .listening, text: nil, code: nil), existingDraft: Self.originalText)
        _ = session.apply(.init(sequence: 1, phase: .answering, paused: paused,
            revision: 7, studyID: 42, recordID: "101", answerID: Self.answerID))
    }

    func cancelLearning() {
        cancelCallbackCount += 1
        if let command = draft.requestCancel() { commands.append(command) }
    }

    func otherAction() { otherActionCount += 1 }

    func acknowledgeCancellation() -> Bool {
        draft.apply(.init(answerID: Self.answerID, studyID: 42, recordID: "101", revision: 7,
            phase: .cancelled, text: nil, code: "ANSWER_CANCELLED"))
    }
}

private struct VoiceTutorLearningCancelTestParent: View {
    @ObservedObject var probe: VoiceTutorLearningCancelProbe

    var body: some View {
        VoiceTutorCallScreen(topic: "스프링",
            presentation: VoiceTutorCallPresentation(phase: .listening, pauseState: probe.pause,
                sessionState: probe.session, sessionSecondsRemaining: 3_000),
            strings: AppStrings(language: .korean), errorMessage: nil,
            showsTranscript: $probe.showsTranscript, showsSummary: $probe.showsSummary,
            onPause: { probe.otherAction() }, onEnd: { probe.otherAction() },
            answerDraftState: probe.draft,
            answerDraftText: Binding(get: { probe.draft.text }, set: { _ = probe.draft.edit($0) }),
            canSubmitAnswer: !probe.pause.holdsMicrophone && probe.draft.canSubmit,
            onFinishAnswer: { probe.otherAction() }, onSubmitAnswer: { probe.otherAction() },
            canSkipAnswer: !probe.pause.holdsMicrophone && probe.draft.canSkip,
            onSkipAnswer: { probe.otherAction() }, canCancelLearning: probe.draft.canCancel,
            onCancelLearning: { probe.cancelLearning() })
            .dynamicTypeSize(.large)
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .environment(\.scenePhase, .active)
    }
}

@MainActor
private final class VoiceTutorLearningCancelHarness {
    let probe: VoiceTutorLearningCancelProbe
    let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init(transcript: Bool, paused: Bool) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        probe = VoiceTutorLearningCancelProbe(transcript: transcript, paused: paused)
        window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 402, height: 874)
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = UIHostingController(rootView: VoiceTutorLearningCancelTestParent(probe: probe))
        window.makeKeyAndVisible()
    }

    func settle() async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(400))
        window.setNeedsLayout()
        window.layoutIfNeeded()
        window.rootViewController?.view.layoutIfNeeded()
    }

    func cancelButtons() -> [NSObject] {
        var visited = Set<ObjectIdentifier>()
        var result: [NSObject] = []
        let label = AppStrings(language: .korean).voiceTutorLearningCancel
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

    func close() {
        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}

@MainActor
private final class VoiceTutorAnswerEditorProbe: ObservableObject {
    @Published var text: String
    @Published var parentRevision = 0

    init(text: String) { self.text = text }
}

private struct VoiceTutorAnswerEditorTestParent: View {
    @ObservedObject var probe: VoiceTutorAnswerEditorProbe
    let dynamicType: DynamicTypeSize

    var body: some View {
        // Consume a call-like update without changing the editor's identity or
        // geometry, so selection preservation is exercised across body updates.
        let _ = probe.parentRevision
        VoiceTutorAnswerEditor(strings: AppStrings(language: .korean), text: $probe.text)
            .dynamicTypeSize(dynamicType)
            .environment(\.locale, Locale(identifier: "ko_KR"))
    }
}

@MainActor
private final class VoiceTutorAnswerEditorHarness {
    let probe: VoiceTutorAnswerEditorProbe
    let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init(text: String, dynamicType: DynamicTypeSize = .large) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else {
            throw XCTSkip("Native editor verification requires an iOS application window scene")
        }
        probe = VoiceTutorAnswerEditorProbe(text: text)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        window = UIWindow(windowScene: scene)
        window.frame = scene.screen.bounds
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = UIHostingController(
            rootView: VoiceTutorAnswerEditorTestParent(probe: probe, dynamicType: dynamicType)
        )
        window.makeKeyAndVisible()
        layout()
    }

    func presentedEditor() async throws -> UITextView {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while ProcessInfo.processInfo.systemUptime < deadline {
            layout()
            if let editor = findEditor(), editor.bounds.height > 0, editor.isFirstResponder {
                // Let the initial software-keyboard transition finish before
                // comparing subsequent caret/scroll state or taking a snapshot.
                try await Task.sleep(for: .milliseconds(350))
                layout()
                return editor
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        let editor = try XCTUnwrap(findEditor(), "The answer editor must mount a native UITextView")
        XCTAssertTrue(editor.isFirstResponder, "The newly opened editor should focus its text input once")
        return editor
    }

    func findEditor() -> UITextView? {
        func visit(_ view: UIView) -> UITextView? {
            if let editor = view as? UITextView, editor.isEditable { return editor }
            for child in view.subviews {
                if let editor = visit(child) { return editor }
            }
            return nil
        }
        return visit(window)
    }

    func layout() {
        window.setNeedsLayout()
        window.layoutIfNeeded()
        window.rootViewController?.view.layoutIfNeeded()
    }

    func close() {
        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}
