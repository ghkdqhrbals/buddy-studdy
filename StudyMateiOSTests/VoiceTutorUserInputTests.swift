import Combine
import Foundation
import SwiftUI
import UIKit
import XCTest
@testable import StudyMate

final class VoiceTutorUserInputTests: XCTestCase {
    func testWireRequestAndAcknowledgementDecodeWithExactIdentityAndQuestions() throws {
        let request = UserInputFixture.request()
        let parsed = try UserInputFixture.parse(request)
        guard case .userInputRequest(let decoded) = parsed else { return XCTFail("Expected an input request") }
        XCTAssertEqual(decoded, request)
        XCTAssertEqual(decoded.questions.map(\.selectionMode), [.single, .multiple, .text])
        let acknowledgement = UserInputFixture.event(for: request, sequence: 2, phase: .submitted)
        guard case .userInputState(let decodedState) = try UserInputFixture.parse(acknowledgement) else {
            return XCTFail("Expected a submitted acknowledgement")
        }
        XCTAssertEqual(decodedState, acknowledgement)
    }

    func testMalformedWireRequestsAndStatesCannotBecomeInteractiveCards() throws {
        let valid = try UserInputFixture.fields(UserInputFixture.request(), type: "buddystudy.voice.user_input.request")
        var invalidRequests: [[String: Any]] = []
        for (key, value) in [("sessionId", "not-a-session"), ("attemptId", ""), ("requestId", "bad")] {
            var invalid = valid
            invalid[key] = value
            invalidRequests.append(invalid)
        }
        let invalidSequences: [Any] = [0, -1, true, 1.5, "1"]
        for sequence in invalidSequences {
            var invalid = valid
            invalid["sequence"] = sequence
            invalidRequests.append(invalid)
        }
        let invalidQuestionLists: [Any] = [[Any](), Array(repeating: ["id": "same"], count: 6)]
        for questions in invalidQuestionLists {
            var invalid = valid
            invalid["questions"] = questions
            invalidRequests.append(invalid)
        }
        var unknownMode = valid
        var questions = try XCTUnwrap(valid["questions"] as? [[String: Any]])
        questions[0]["selectionMode"] = "unknown"
        unknownMode["questions"] = questions
        invalidRequests.append(unknownMode)
        for invalid in invalidRequests {
            guard case .ignored = try UserInputFixture.parse(invalid) else {
                return XCTFail("Malformed input request must be ignored: \(invalid.keys.sorted())")
            }
        }

        let request = UserInputFixture.request()
        let validState = try UserInputFixture.fields(
            UserInputFixture.event(for: request, sequence: 2, phase: .pending),
            type: "buddystudy.voice.user_input.state"
        )
        let invalidStateFields: [(String, Any)] = [("phase", "unknown"), ("sequence", 0), ("requestId", "bad"), ("attemptId", "bad")]
        for (key, value) in invalidStateFields {
            var invalid = validState
            invalid[key] = value
            guard case .ignored = try UserInputFixture.parse(invalid) else { return XCTFail("Malformed state must be ignored") }
        }
    }

    func testAllQuestionsRequireValidSingleMultipleOrFreeTextAnswers() {
        let request = UserInputFixture.request()
        let valid = UserInputFixture.answers()
        XCTAssertTrue(VoiceTutorUserInputState.validAnswers(valid, for: request))
        var invalid = valid
        invalid[0].selectedOptionIds = ["spring", "msa"]
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        invalid = valid
        invalid[1].selectedOptionIds = ["concept", "concept"]
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        invalid[1].selectedOptionIds = ["unoffered"]
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        invalid = valid
        invalid[2].text = " \n\t "
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        invalid[2].text = String(repeating: "가", count: 2_001)
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(Array(valid.dropLast()), for: request))
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers([valid[0], valid[0], valid[2]], for: request))
        invalid = valid
        invalid[0].text = "선택 전용 질문에 허용되지 않은 글"
        XCTAssertFalse(VoiceTutorUserInputState.validAnswers(invalid, for: request))
        invalid = valid
        invalid[1].selectedOptionIds = []
        invalid[1].text = "선택지 대신 직접 설명합니다."
        XCTAssertTrue(VoiceTutorUserInputState.validAnswers(invalid, for: request),
            "Free text is an explicit alternative when that question permits it.")
    }

    func testSubmitKeepsMicrophoneHeldUntilTheMatchingServerAcknowledgement() throws {
        let request = UserInputFixture.request()
        var state = VoiceTutorUserInputState()
        XCTAssertTrue(state.apply(request, sessionID: request.sessionId))
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertFalse(try XCTUnwrap(state.pending).canSubmit)
        for answer in UserInputFixture.answers() { state.update(requestID: request.id, answer: answer) }
        let control = try XCTUnwrap(state.submit(requestID: request.id, cancel: false))
        XCTAssertEqual(control.answers, UserInputFixture.answers())
        XCTAssertEqual(state.pending?.status, .submitting)
        XCTAssertTrue(state.holdsMicrophone, "Sending an answer alone cannot reopen native microphone input.")
        XCTAssertNil(state.submit(requestID: request.id, cancel: false), "A repeated tap must not submit twice.")
        state.update(requestID: request.id, answer: .init(questionId: "notes", text: "전송 중 바뀐 글"))
        XCTAssertEqual(state.pending?.answers, UserInputFixture.answers())
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .submitted)))
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertEqual(state.entries.first?.status, .submitted)
        XCTAssertEqual(state.entries.first?.answers, UserInputFixture.answers(), "The submitted card remains in the transcript.")
        let payload = try control.payload()
        XCTAssertEqual(payload["type"] as? String, "buddystudy.voice.user_input.submit")
        XCTAssertEqual(payload["requestId"] as? String, request.id)
        let encodedAnswers = try JSONSerialization.data(withJSONObject: XCTUnwrap(payload["answers"]))
        XCTAssertEqual(try JSONDecoder().decode([VoiceTutorUserInputAnswer].self, from: encodedAnswers), UserInputFixture.answers())
    }

    func testInvalidAnswerAcknowledgementRestoresEditingAndKeepsEveryDraft() throws {
        let request = UserInputFixture.request()
        var state = UserInputFixture.readyState(request)
        XCTAssertNotNil(state.submit(requestID: request.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .pending, errorCode: "INVALID_ANSWERS")))
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertEqual(state.pending?.answers, UserInputFixture.answers())
        XCTAssertTrue(try XCTUnwrap(state.pending).hasError)
        var correction = UserInputFixture.answers()[2]
        correction.text += "\n수정한 설명"
        state.update(requestID: request.id, answer: correction)
        XCTAssertFalse(try XCTUnwrap(state.pending).hasError)
        let retry = try XCTUnwrap(state.submit(requestID: request.id, cancel: false))
        XCTAssertEqual(retry.answers?.last?.text, correction.text)
        XCTAssertTrue(state.holdsMicrophone)
    }

    func testWrongSessionAttemptStaleAndDuplicateMessagesCannotChangePendingInput() throws {
        let request = UserInputFixture.request()
        var state = UserInputFixture.readyState(request)
        let original = state
        XCTAssertFalse(state.apply(request, sessionID: UUID().uuidString))
        XCTAssertFalse(state.apply(request, sessionID: request.sessionId))
        let another = UserInputFixture.request(sequence: 100)
        XCTAssertFalse(state.apply(another, sessionID: request.sessionId), "A pending form cannot be replaced by a newer one.")
        for event in [
            UserInputFixture.event(for: request, sequence: 2, phase: .submitted, sessionID: UUID().uuidString),
            UserInputFixture.event(for: request, sequence: 2, phase: .submitted, attemptID: UUID().uuidString),
            UserInputFixture.event(for: request, sequence: 2, phase: .submitted, requestID: UUID().uuidString),
            UserInputFixture.event(for: request, sequence: 1, phase: .submitted),
            UserInputFixture.event(for: request, sequence: 2, phase: .pending, errorCode: "UNKNOWN_ERROR")
        ] {
            XCTAssertFalse(state.apply(event))
            XCTAssertEqual(state, original)
        }
        XCTAssertNotNil(state.submit(requestID: request.id, cancel: false))
        let ack = UserInputFixture.event(for: request, sequence: 2, phase: .submitted)
        XCTAssertTrue(state.apply(ack))
        XCTAssertFalse(state.apply(ack))
        XCTAssertFalse(state.apply(UserInputFixture.event(for: request, sequence: 3, phase: .pending)),
            "A delayed pending event cannot reopen a terminal card.")
    }

    func testFailedActionCanBeRetriedWithTheSameCompleteSelectionAndDraft() throws {
        let request = UserInputFixture.request()
        var state = UserInputFixture.readyState(request)
        let first = try XCTUnwrap(state.submit(requestID: request.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .pending, errorCode: "ACTION_FAILED")))
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(try XCTUnwrap(state.pending).actionFailed)
        XCTAssertTrue(try XCTUnwrap(state.pending).hasError)
        XCTAssertEqual(state.pending?.answers, first.answers)
        let retry = try XCTUnwrap(state.submit(requestID: request.id, cancel: false))
        XCTAssertEqual(retry, first, "Retry retains the exact request and selected IDs for server deduplication.")
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 3, phase: .submitted)))
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertFalse(try XCTUnwrap(state.entries.first).actionFailed)
        XCTAssertEqual(state.entries.first?.answers, first.answers)
    }

    func testInvalidAnswersErrorCannotAuthorizeTerminalMicrophoneRelease() {
        let request = UserInputFixture.request()
        var state = UserInputFixture.readyState(request)
        XCTAssertNotNil(state.submit(requestID: request.id, cancel: false))
        let before = state
        for phase in [VoiceTutorUserInputStateEvent.Phase.submitted, .cancelled] {
            for errorCode in ["INVALID_ANSWERS", "ACTION_FAILED"] {
                XCTAssertFalse(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: phase, errorCode: errorCode)))
                XCTAssertEqual(state, before)
                XCTAssertTrue(state.holdsMicrophone)
            }
        }
    }

    func testCancellationWaitsForAcknowledgementAndCallEndFencesAllLateWork() throws {
        let request = UserInputFixture.request()
        var state = UserInputFixture.readyState(request)
        let cancel = try XCTUnwrap(state.submit(requestID: request.id, cancel: true))
        XCTAssertNil(cancel.answers)
        XCTAssertEqual(try cancel.payload()["type"] as? String, "buddystudy.voice.user_input.cancel")
        XCTAssertTrue(state.holdsMicrophone)
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .cancelled)))
        XCTAssertFalse(state.holdsMicrophone)
        XCTAssertEqual(state.entries.first?.answers, UserInputFixture.answers())

        var ended = UserInputFixture.readyState(request)
        ended.endLocally()
        let terminal = ended
        XCTAssertFalse(ended.holdsMicrophone)
        XCTAssertEqual(ended.entries.first?.status, .cancelled)
        XCTAssertFalse(ended.apply(UserInputFixture.event(for: request, sequence: 2, phase: .submitted)))
        XCTAssertFalse(ended.apply(UserInputFixture.request(sequence: 3), sessionID: request.sessionId))
        XCTAssertNil(ended.submit(requestID: request.id, cancel: false))
        ended.update(requestID: request.id, answer: .init(questionId: "notes", text: "뒤늦은 편집"))
        XCTAssertEqual(ended, terminal)
    }
}

private enum UserInputFixture {
    static let sessionID = "B0A67B66-625C-4D64-B4D7-931324243A5D"
    static let attemptID = "80431858-423F-441F-900F-25FBC154E885"

    static func request(sequence: Int64 = 1, questions: [VoiceTutorUserInputQuestion]? = nil) -> VoiceTutorUserInputRequest {
        .init(requestId: UUID().uuidString, sessionId: sessionID, attemptId: attemptID,
              sequence: sequence, title: "어떤 방식으로 공부할까요?", questions: questions ?? [
                .init(id: "topic", prompt: "주제를 하나 골라 주세요.", selectionMode: .single,
                      options: [.init(id: "spring", label: "스프링"), .init(id: "msa", label: "MSA")], allowFreeText: false),
                .init(id: "style", prompt: "원하는 설명 방식을 모두 골라 주세요.", selectionMode: .multiple,
                      options: [.init(id: "concept", label: "개념 정리"), .init(id: "example", label: "실제 예시"),
                                .init(id: "practice", label: "연습 문제")], allowFreeText: true),
                .init(id: "notes", prompt: "궁금한 점을 적어 주세요.", selectionMode: .text,
                      options: [], allowFreeText: true)
              ])
    }

    static func answers() -> [VoiceTutorUserInputAnswer] {
        [.init(questionId: "topic", selectedOptionIds: ["msa"]),
         .init(questionId: "style", selectedOptionIds: ["concept", "example"], text: "  실무 위주로 부탁해요.  "),
         .init(questionId: "notes", text: "첫째 줄\n서비스 경계가 궁금해요. 👩‍💻")]
    }

    static func readyState(_ request: VoiceTutorUserInputRequest) -> VoiceTutorUserInputState {
        var state = VoiceTutorUserInputState()
        _ = state.apply(request, sessionID: request.sessionId)
        for answer in answers() { state.update(requestID: request.id, answer: answer) }
        return state
    }

    static func event(for request: VoiceTutorUserInputRequest, sequence: Int64,
                      phase: VoiceTutorUserInputStateEvent.Phase, errorCode: String? = nil,
                      sessionID: String? = nil, attemptID: String? = nil, requestID: String? = nil) -> VoiceTutorUserInputStateEvent {
        .init(requestId: requestID ?? request.id, sessionId: sessionID ?? request.sessionId,
              attemptId: attemptID ?? request.attemptId, sequence: sequence, phase: phase, errorCode: errorCode)
    }

    static func fields<T: Encodable>(_ value: T, type: String) throws -> [String: Any] {
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
        fields["type"] = type
        return fields
    }

    static func parse(_ request: VoiceTutorUserInputRequest) throws -> VoiceTutorRealtimeEvent {
        try parse(fields(request, type: "buddystudy.voice.user_input.request"))
    }
    static func parse(_ event: VoiceTutorUserInputStateEvent) throws -> VoiceTutorRealtimeEvent {
        try parse(fields(event, type: "buddystudy.voice.user_input.state"))
    }
    static func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}

/// Hosts the production card and uses its native accessibility actions and text
/// editor. This fixture never starts a call, records audio, or performs network I/O.
@MainActor
final class VoiceTutorUserInputCardTests: XCTestCase {
    func testNativeCardSelectionTextAndSubmitActionsKeepTheDraftUntilAcknowledged() async throws {
        try requireInProcessSwiftUIAccessibility()
        let harness = try UserInputCardHarness()
        defer { harness.close() }
        try await harness.settle()
        attach(harness, name: "voice-input-initial-card")
        let concept = try XCTUnwrap(harness.button(label: "개념 정리"), harness.accessibilityDescription())
        XCTAssertTrue(concept.accessibilityActivate(), "The visible option must expose its native accessibility action")
        try await harness.settle()
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.selectedOptionIds, ["concept"])
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "개념 정리")).accessibilityTraits.contains(.selected))
        let example = try XCTUnwrap(harness.button(label: "실제 예시"))
        XCTAssertTrue(example.accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.selectedOptionIds, ["concept", "example"])

        let editor = try XCTUnwrap(harness.editor())
        XCTAssertTrue(editor.becomeFirstResponder())
        editor.insertText("직접 입력한 설명\n한글과 줄바꿈을 유지합니다.")
        try await harness.settle()
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, editor.text)
        XCTAssertEqual(editor.text, "직접 입력한 설명\n한글과 줄바꿈을 유지합니다.")
        harness.window.endEditing(true)
        try await harness.settle()
        attach(harness, name: "voice-input-selected-and-free-text")

        let submit = try XCTUnwrap(harness.button(label: "제출하고 계속"))
        XCTAssertFalse(submit.accessibilityTraits.contains(.notEnabled))
        XCTAssertTrue(submit.accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.controls.count, 1)
        XCTAssertTrue(harness.probe.state.holdsMicrophone)
        XCTAssertEqual(harness.probe.state.pending?.status, .submitting)
        XCTAssertEqual(harness.probe.controls.first?.answers?.first?.text, editor.text)
        attach(harness, name: "voice-input-awaiting-submit-ack")
    }

    func testNativeCardLargeTextShowsSelectedOptionsAndPreservesInvalidAnswerDraft() async throws {
        try requireInProcessSwiftUIAccessibility()
        let harness = try UserInputCardHarness(dynamicType: .accessibility2)
        defer { harness.close() }
        let request = harness.probe.request
        let answer = VoiceTutorUserInputAnswer(questionId: "style", selectedOptionIds: ["concept", "practice"], text: "  사용자가 작성한 초안\n둘째 줄  ")
        harness.probe.state.update(requestID: request.id, answer: answer)
        _ = harness.probe.state.submit(requestID: request.id, cancel: false)
        XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .pending, errorCode: "INVALID_ANSWERS")))
        try await harness.settle()
        XCTAssertEqual(harness.editor()?.text, answer.text)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "연습 문제"), harness.accessibilityDescription()).accessibilityTraits.contains(.selected))
        XCTAssertFalse(try XCTUnwrap(harness.button(label: "실제 예시"), harness.accessibilityDescription()).accessibilityTraits.contains(.selected))
        XCTAssertTrue(harness.probe.state.holdsMicrophone)
        XCTAssertEqual(harness.probe.state.pending?.answers, [answer])
        attach(harness, name: "voice-input-large-text-invalid-answer-preserved")
    }

    /// Runs on simulator AND iPhone. It uses UIKit's real text-input path and
    /// rendered production card, without depending on an in-process AX tree.
    func testNativeCardKoreanMultilineDraftAndRenderingOnEveryDevice() async throws {
        for size in [DynamicTypeSize.large, .accessibility2] {
            let harness = try UserInputCardHarness(dynamicType: size)
            defer { harness.close() }
            try await harness.settle()
            let editor = try XCTUnwrap(harness.editor())
            XCTAssertTrue(editor.isEditable)
            XCTAssertTrue(editor.becomeFirstResponder())
            let draft = "  직접 작성한 한글 초안\n둘째 줄도 그대로 유지합니다. 👩‍💻  "
            editor.insertText(draft)
            try await harness.settle()
            XCTAssertEqual(editor.text, draft)
            XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, draft)

            let request = harness.probe.request
            var answer = try XCTUnwrap(harness.probe.state.pending?.answers.first)
            answer.selectedOptionIds = ["concept", "practice"]
            harness.probe.state.update(requestID: request.id, answer: answer)
            try await harness.settle()
            XCTAssertEqual(harness.editor()?.text, draft, "Parent selection updates must preserve the actual editor's draft")
            harness.window.endEditing(true)
            try await harness.settle()
            attach(harness, name: "voice-input-native-draft-\(size)")

            harness.probe.send(cancel: false)
            try await harness.settle()
            XCTAssertEqual(harness.probe.controls.first?.answers, [answer])
            XCTAssertTrue(harness.probe.state.holdsMicrophone)
            XCTAssertEqual(harness.probe.state.pending?.status, .submitting)

            // SwiftUI disables interaction without necessarily changing the
            // UIKit editor's isEditable property. Exercise the state boundary
            // as if a stale edit callback and a second submit arrived instead.
            var staleEdit = answer
            staleEdit.text = "제출 중 도착한 오래된 수정"
            staleEdit.selectedOptionIds = ["example"]
            harness.probe.state.update(requestID: request.id, answer: staleEdit)
            harness.probe.send(cancel: false)
            try await harness.settle()
            XCTAssertEqual(harness.probe.state.pending?.answers, [answer], "An edit arriving after submit must not replace the submitted draft")
            XCTAssertEqual(harness.probe.controls.count, 1, "A second submit must not produce another control while awaiting ACK")
            XCTAssertEqual(harness.probe.controls.first?.answers, [answer])
            XCTAssertEqual(harness.probe.state.pending?.status, .submitting)
            XCTAssertTrue(harness.probe.state.holdsMicrophone)
            XCTAssertEqual(harness.editor()?.text, draft)

            XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: request, sequence: 2,
                phase: .pending, errorCode: "INVALID_ANSWERS")))
            try await harness.settle()
            XCTAssertTrue(try XCTUnwrap(harness.editor()).isEditable)
            XCTAssertEqual(harness.editor()?.text, draft)
            XCTAssertEqual(harness.probe.state.pending?.answers, [answer])
            XCTAssertTrue(harness.probe.state.holdsMicrophone)

            let retryEditor = try XCTUnwrap(harness.editor())
            XCTAssertTrue(retryEditor.becomeFirstResponder())
            retryEditor.selectedRange = NSRange(location: draft.utf16.count, length: 0)
            retryEditor.insertText("\n재시도하며 수정했어요.")
            try await harness.settle()
            var revisedAnswer = answer
            revisedAnswer.text = draft + "\n재시도하며 수정했어요."
            XCTAssertEqual(retryEditor.text, revisedAnswer.text)
            XCTAssertEqual(harness.probe.state.pending?.answers, [revisedAnswer], "After the retry ACK, native input must edit the preserved draft again")
            XCTAssertEqual(harness.probe.state.pending?.status, .pending)
            XCTAssertEqual(harness.probe.controls.count, 1)
            XCTAssertTrue(harness.probe.state.holdsMicrophone)
            harness.window.endEditing(true)
            try await harness.settle()
            attach(harness, name: "voice-input-native-retry-draft-\(size)")
        }
    }

    /// Opt in for real touch/typing verification; this does not synthesize
    /// button actions, prefill an answer, or connect to a voice session.
    func testRealTouchSelectionTypingAndSubmitOnPresentedCard() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_USER_INPUT_INTERACTION_SMOKE"] == "1" else {
            throw XCTSkip("Opt-in 60-second touch/typing test; set BUDDYSTUDY_USER_INPUT_INTERACTION_SMOKE=1")
        }
        let harness = try UserInputCardHarness()
        defer { harness.close() }
        try await harness.settle()
        attach(harness, name: "voice-input-real-touch-ready")
        print("VOICE_USER_INPUT_INTERACTION_READY: Tap 개념 정리 and 연습 문제; enter 실기기 입력, newline, 줄바꿈 확인; tap 제출하고 계속 within 60 seconds.")
        let deadline = ProcessInfo.processInfo.systemUptime + 60
        while harness.probe.controls.isEmpty, ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        attach(harness, name: "voice-input-real-touch-result")
        XCTAssertEqual(harness.probe.controls.count, 1, "Complete the visible card with real touches and submit before the deadline")
        let command = try XCTUnwrap(harness.probe.controls.first)
        let answers = try XCTUnwrap(command.answers, "Submit the card; cancellation must not satisfy the smoke test")
        XCTAssertEqual(answers.count, 1)
        let answer = try XCTUnwrap(answers.first)
        XCTAssertEqual(answer.questionId, "style")
        XCTAssertEqual(Set(answer.selectedOptionIds), Set(["concept", "practice"]))
        XCTAssertEqual(answer.selectedOptionIds.count, 2)
        XCTAssertEqual(answer.text, "실기기 입력\n줄바꿈 확인")
        XCTAssertEqual(harness.probe.state.pending?.answers, answers)
        XCTAssertEqual(harness.probe.state.pending?.status, .submitting)
        XCTAssertTrue(harness.probe.state.holdsMicrophone, "Touch submission must keep the microphone held until an exact server ACK")
        print("VOICE_USER_INPUT_INTERACTION_SUBMITTED")
    }

    private func requireInProcessSwiftUIAccessibility() throws {
        #if !targetEnvironment(simulator)
        // The physical iPhone's hosted XCTest process exposes UIKit descendants
        // but no SwiftUI AccessibilityNode labels, traits, or actions (verified
        // with the same binary that passes both action tests in the simulator).
        // Keep those assertions intact on simulator; native text/render coverage
        // above still runs on every device, and real touches use the opt-in test.
        throw XCTSkip("Physical hosted tests do not expose the SwiftUI AX tree; simulator action tests remain required, and native text/render tests run on iPhone")
        #endif
    }

    private func attach(_ harness: UserInputCardHarness, name: String) {
        harness.layout()
        let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
            XCTAssertTrue(harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 1_024, "The actual production card must render into the attached image")
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

@MainActor
private final class UserInputCardProbe: ObservableObject {
    @Published var state = VoiceTutorUserInputState()
    let request: VoiceTutorUserInputRequest
    private(set) var controls: [VoiceTutorUserInputControl] = []

    init() {
        request = UserInputFixture.request(questions: [UserInputFixture.request().questions[1]])
        _ = state.apply(request, sessionID: request.sessionId)
    }

    func send(cancel: Bool) {
        if let control = state.submit(requestID: request.id, cancel: cancel) { controls.append(control) }
    }
}

private struct UserInputCardTestParent: View {
    @ObservedObject var probe: UserInputCardProbe
    let dynamicType: DynamicTypeSize

    var body: some View {
        ScrollView {
            if let entry = probe.state.entries.first {
                VoiceTutorUserInputCard(entry: entry, strings: AppStrings(language: .korean),
                    onChange: { probe.state.update(requestID: entry.id, answer: $0) },
                    onSubmit: { probe.send(cancel: false) }, onCancel: { probe.send(cancel: true) })
                    .padding(16)
            }
        }
        .dynamicTypeSize(dynamicType)
        .environment(\.locale, Locale(identifier: "ko_KR"))
    }
}

@MainActor
private final class UserInputCardHarness {
    let probe = UserInputCardProbe()
    let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init(dynamicType: DynamicTypeSize = .large) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        window = UIWindow(windowScene: scene)
        window.frame = scene.screen.bounds
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = UIHostingController(rootView: UserInputCardTestParent(probe: probe, dynamicType: dynamicType))
        window.makeKeyAndVisible()
        layout()
    }

    func settle() async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(180))
        layout()
    }

    func layout() {
        window.setNeedsLayout()
        window.layoutIfNeeded()
        window.rootViewController?.view.layoutIfNeeded()
    }

    func button(label: String) -> NSObject? {
        // SwiftUI AccessibilityNode exposes labels/actions to the hosted app,
        // but its automation identifier is not a UIKit protocol conformance.
        // Find the same semantic button a screen-reader user would activate.
        accessibilityObjects().first {
            $0.accessibilityLabel == label && $0.accessibilityTraits.contains(.button)
        }
    }

    func accessibilityDescription() -> String {
        accessibilityObjects().map {
            "\(type(of: $0)) traits=\($0.accessibilityTraits.rawValue) label=\($0.accessibilityLabel ?? "nil")"
        }.joined(separator: "\n")
    }

    private func accessibilityObjects() -> [NSObject] {
        var visited = Set<ObjectIdentifier>()
        var result: [NSObject] = []
        func visit(_ object: NSObject) {
            guard visited.insert(ObjectIdentifier(object)).inserted else { return }
            result.append(object)
            // SwiftUI's visible controls may be supplied through its automation
            // container rather than existing as individual UIKit subviews.
            if #available(iOS 17.0, *) {
                for child in (object.automationElements ?? []).compactMap({ $0 as? NSObject }) {
                    visit(child)
                }
            }
            for child in (object.accessibilityElements ?? []).compactMap({ $0 as? NSObject }) {
                visit(child)
            }
            let count = object.accessibilityElementCount()
            if count > 0, count < 512 {
                for index in 0..<count {
                    if let child = object.accessibilityElement(at: index) as? NSObject { visit(child) }
                }
            }
            for child in (object as? UIView)?.subviews ?? [] {
                visit(child)
            }
        }
        visit(window)
        return result
    }

    func editor() -> UITextView? {
        func visit(_ view: UIView) -> UITextView? {
            if let text = view as? UITextView { return text }
            return view.subviews.lazy.compactMap(visit).first
        }
        return visit(window)
    }

    func close() {
        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}
