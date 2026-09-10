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

    func testOptionalOperationIdentityDecodesWithoutChangingControlAuthority() throws {
        var request = UserInputFixture.request()
        request.operationId = "call_exact_form"
        guard case .userInputRequest(let decoded) = try UserInputFixture.parse(request) else {
            return XCTFail("Expected a correlated card")
        }
        XCTAssertEqual(decoded.operationId, request.operationId)
        var state = UserInputFixture.readyState(decoded)
        let payload = try XCTUnwrap(state.submit(requestID: decoded.id, cancel: false)).payload()
        XCTAssertNil(payload["operationId"], "A display anchor is never an action authorization field")
        let valid = try UserInputFixture.fields(request, type: "buddystudy.voice.user_input.request")
        let invalidIDs: [Any] = ["", "wrong/id", String(repeating: "x", count: 192), 42, true]
        for invalidID in invalidIDs {
            var invalid = valid
            invalid["operationId"] = invalidID
            guard case .ignored = try UserInputFixture.parse(invalid) else {
                return XCTFail("Unsafe operation IDs must not create a card")
            }
        }
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
        XCTAssertEqual(state.entries.first?.submittedAnswers, control.answers, "Read-only results are the exact acknowledged submission")
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
        XCTAssertNil(state.entries.first?.submittedAnswers, "Cancelled draft selections are not submitted answers")

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

    func testCompletedAndCancelledCardsKeepTheirCapturedLegacyConversation() throws {
        let first = VoiceTutorCaption(speaker: .tutor, text: "첫 번째 선택 안내")
        let second = VoiceTutorCaption(speaker: .tutor, text: "두 번째 선택 안내")
        let later = VoiceTutorCaption(speaker: .learner, text: "다음 대화")
        let submitted = UserInputFixture.request()
        let cancelled = UserInputFixture.request(sequence: 3)
        var state = VoiceTutorUserInputState()
        XCTAssertTrue(state.apply(submitted, sessionID: submitted.sessionId, afterCaptionID: first.id))
        for answer in UserInputFixture.answers() { state.update(requestID: submitted.id, answer: answer) }
        XCTAssertNotNil(state.submit(requestID: submitted.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: submitted, sequence: 2, phase: .submitted)))
        XCTAssertTrue(state.apply(cancelled, sessionID: cancelled.sessionId, afterCaptionID: second.id))
        XCTAssertNotNil(state.submit(requestID: cancelled.id, cancel: true))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: cancelled, sequence: 4, phase: .cancelled)))
        state.endLocally()
        let layout = UserInputFixture.layout(state, captions: [first, second, later])
        XCTAssertEqual(layout.byCaptionID[first.id]?.map(\.id), [submitted.id])
        XCTAssertEqual(layout.byCaptionID[second.id]?.map(\.id), [cancelled.id])
        XCTAssertNil(layout.byCaptionID[later.id])
        XCTAssertTrue(layout.beforeCaptions.isEmpty)
        XCTAssertTrue(layout.unresolvedWaiting.isEmpty)
        XCTAssertEqual(state.entries.first?.submittedAnswers, UserInputFixture.answers())
        XCTAssertNil(state.entries.last?.submittedAnswers)
    }

    func testCurriculumCardsStayBelowTheExactSelectingOrQuestionOperation() throws {
        for name in ["select_voice_study", "advance_voice_study", "request_question", "list_pending_questions"] {
            for cancelled in [false, true] {
                var request = UserInputFixture.request()
                request.operationId = "curriculum_\(name)"
                let source = VoiceTutorCaption(speaker: .learner, text: "이 주제로 시작해보자", providerItemID: "start_topic")
                let later = VoiceTutorCaption(speaker: .tutor, text: "다음 대화", responseID: "later")
                let operation = try UserInputFixture.operation(request.operationId!,
                    context: .init(operationID: request.operationId!, learnerItemID: "start_topic"), name: name)
                var state = VoiceTutorUserInputState()
                XCTAssertTrue(state.apply(request, sessionID: request.sessionId, afterCaptionID: later.id))
                state.bindOperation(operation)
                XCTAssertEqual(state.entries.first?.originOperation?.event.name, name)
                for answer in UserInputFixture.answers() { state.update(requestID: request.id, answer: answer) }
                XCTAssertNotNil(state.submit(requestID: request.id, cancel: cancelled))
                XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2,
                    phase: cancelled ? .cancelled : .submitted)))
                let layout = UserInputFixture.layout(state, captions: [source, later])
                XCTAssertEqual(layout.byCaptionID[source.id]?.map(\.id), [request.id])
                XCTAssertNil(layout.byCaptionID[later.id])
                XCTAssertTrue(layout.unresolvedWaiting.isEmpty)
                XCTAssertEqual(state.entries.first?.submittedAnswers, cancelled ? nil : UserInputFixture.answers())
            }
        }
    }

    func testExactCardOriginWaitsForLateOperationAndCaptionWithoutBorrowingNewerConversation() throws {
        var request = UserInputFixture.request()
        request.operationId = "call_late"
        let newer = VoiceTutorCaption(speaker: .tutor, text: "새로운 응답", responseID: "response_new")
        let source = VoiceTutorCaption(speaker: .tutor, text: "원래 선택 안내", responseID: "response_source")
        var state = VoiceTutorUserInputState()
        XCTAssertTrue(state.apply(request, sessionID: request.sessionId, afterCaptionID: newer.id, responseID: newer.responseID))
        var layout = UserInputFixture.layout(state, captions: [newer])
        XCTAssertEqual(layout.unresolvedWaiting.map(\.id), [request.id], "A pending form remains reachable while its source is delayed")
        XCTAssertTrue(layout.byCaptionID.isEmpty)

        state.bindOperation(try UserInputFixture.operation("unrelated", context: .init(operationID: "unrelated", responseID: newer.responseID)))
        XCTAssertNil(state.entries.first?.originOperation)
        state.bindOperation(try UserInputFixture.operation("call_late", context: .init(operationID: "call_late", responseID: source.responseID)))
        for answer in UserInputFixture.answers() { state.update(requestID: request.id, answer: answer) }
        XCTAssertNotNil(state.submit(requestID: request.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .submitted)))
        layout = UserInputFixture.layout(state, captions: [newer])
        XCTAssertTrue(layout.byCaptionID.isEmpty)
        XCTAssertTrue(layout.unresolvedWaiting.isEmpty, "Unresolved history is retained without attaching to a random current message")
        XCTAssertEqual(state.entries.count, 1)

        layout = UserInputFixture.layout(state, captions: [newer], responseID: source.responseID, hasDraft: true)
        XCTAssertEqual(layout.afterAssistantDraft.map(\.id), [request.id])
        layout = UserInputFixture.layout(state, captions: [source, newer])
        XCTAssertEqual(layout.byCaptionID[source.id]?.map(\.id), [request.id])
        XCTAssertNil(layout.byCaptionID[newer.id])
        state.bindOperation(try UserInputFixture.operation("call_late", context: .init(operationID: "call_late", responseID: newer.responseID)))
        state.endLocally()
        layout = UserInputFixture.layout(state, captions: [source, newer])
        XCTAssertEqual(layout.byCaptionID[source.id]?.map(\.id), [request.id], "A late completion cannot overwrite the first immutable source")
        XCTAssertTrue(UserInputFixture.layout(state, captions: [newer]).byCaptionID.isEmpty,
            "Trimming an exact source cannot move its old card under a newer response")
    }

    func testToolOnlyCardAndFollowupCardStayWithOriginWhenItsTutorCaptionArrivesLate() throws {
        let learner = VoiceTutorCaption(speaker: .learner, text: "추천해 주세요", providerItemID: "learner_source")
        let tutor = VoiceTutorCaption(speaker: .tutor, text: "어떤 것을 고를까요?", responseID: "response_source")
        let later = VoiceTutorCaption(speaker: .tutor, text: "다음 안내", responseID: "response_later")
        var first = UserInputFixture.request()
        first.operationId = "call_first"
        var next = UserInputFixture.request(sequence: 3)
        next.operationId = "call_followup"
        var state = VoiceTutorUserInputState()
        XCTAssertTrue(state.apply(first, sessionID: first.sessionId,
            operation: try UserInputFixture.operation("call_first", context: .init(operationID: "call_first",
                responseID: tutor.responseID, learnerItemID: "learner_source"))))
        XCTAssertEqual(UserInputFixture.layout(state, captions: [learner, later]).byCaptionID[learner.id]?.map(\.id), [first.id])
        XCTAssertNotNil(state.submit(requestID: first.id, cancel: true))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: first, sequence: 2, phase: .cancelled)))
        XCTAssertTrue(state.apply(next, sessionID: next.sessionId,
            operation: try UserInputFixture.operation("call_followup", context: .init(operationID: "call_followup",
                responseID: "response_no_text", learnerItemID: "buddystudy-user-input-" + first.id))))
        var layout = UserInputFixture.layout(state, captions: [learner, later])
        XCTAssertEqual(layout.byCaptionID[learner.id]?.map(\.id), [first.id, next.id])
        layout = UserInputFixture.layout(state, captions: [learner, tutor, later])
        XCTAssertEqual(layout.byCaptionID[tutor.id]?.map(\.id), [first.id, next.id])
        XCTAssertNil(layout.byCaptionID[learner.id])
        XCTAssertNil(layout.byCaptionID[later.id])
    }

    func testCardAfterTypedAnswerMovesWithThatExactAnswerAndPreservesItsSubmissionSnapshot() throws {
        let answerID = UUID().uuidString.lowercased()
        let answerCaption = VoiceTutorCaption(speaker: .learner, text: "수정 후 제출한 답변", answerID: answerID)
        let unrelated = VoiceTutorCaption(speaker: .tutor, text: "다른 안내")
        var request = UserInputFixture.request()
        request.operationId = "call_answer"
        var state = VoiceTutorUserInputState()
        XCTAssertTrue(state.apply(request, sessionID: request.sessionId,
            operation: try UserInputFixture.operation("call_answer", context: .init(operationID: "call_answer", answerID: answerID))))
        let draftLayout = VoiceTutorUserInputTranscriptLayout(entries: state.entries, captions: [unrelated],
            assistantResponseID: nil, hasAssistantDraft: false, answerDraftID: answerID)
        XCTAssertEqual(draftLayout.afterAnswerDraft.map(\.id), [request.id])
        for answer in UserInputFixture.answers() { state.update(requestID: request.id, answer: answer) }
        XCTAssertNotNil(state.submit(requestID: request.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 2, phase: .pending, errorCode: "ACTION_FAILED")))
        XCTAssertNil(state.entries.first?.submittedAnswers)
        let revised = VoiceTutorUserInputAnswer(questionId: "notes", text: "  재시도 때 바꾼 내용\n그대로  ")
        state.update(requestID: request.id, answer: revised)
        let control = try XCTUnwrap(state.submit(requestID: request.id, cancel: false))
        XCTAssertTrue(state.apply(UserInputFixture.event(for: request, sequence: 3, phase: .submitted)))
        state.update(requestID: request.id, answer: .init(questionId: "notes", text: "늦은 편집"))
        XCTAssertEqual(state.entries.first?.submittedAnswers, control.answers)
        XCTAssertEqual(state.entries.first?.submittedAnswers?.last, revised)
        XCTAssertEqual(UserInputFixture.layout(state, captions: [answerCaption, unrelated]).byCaptionID[answerCaption.id]?.map(\.id), [request.id])
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

    static func operation(_ id: String, context: VoiceTutorOperationContextEvent,
                          name: String = "request_user_input") throws -> VoiceTutorOperationState.Entry {
        var operations = VoiceTutorOperationState()
        XCTAssertTrue(operations.applyContext(context))
        XCTAssertTrue(operations.apply(.init(sequence: 1, operationID: id, name: name, phase: .started,
            elapsedMilliseconds: 0), at: 0))
        return try XCTUnwrap(operations.active.first)
    }

    static func layout(_ state: VoiceTutorUserInputState, captions: [VoiceTutorCaption], responseID: String? = nil,
                       hasDraft: Bool = false) -> VoiceTutorUserInputTranscriptLayout {
        .init(entries: state.entries, captions: captions, assistantResponseID: responseID, hasAssistantDraft: hasDraft)
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

        XCTAssertNil(harness.editor(), "The compact card must not mount a second scrolling text editor")
        let custom = try XCTUnwrap(harness.button(label: AppStrings(language: .korean).voiceTutorInputCustom),
                                   harness.accessibilityDescription())
        XCTAssertTrue(custom.accessibilityActivate())
        let editor = try await harness.presentedEditor()
        XCTAssertNotNil(harness.window.rootViewController?.presentedViewController,
                        "Direct input must open its independent editing sheet")
        XCTAssertTrue(editor.becomeFirstResponder())
        let draft = "직접 입력한 설명\n한글과 줄바꿈을 유지합니다."
        editor.insertText(draft)
        try await harness.settle()
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, editor.text)
        XCTAssertEqual(editor.text, draft)
        attach(harness, name: "voice-input-independent-editor")
        let done = try XCTUnwrap(harness.button(label: AppStrings(language: .korean).done), harness.accessibilityDescription())
        XCTAssertTrue(done.accessibilityActivate())
        try await harness.waitForEditorDismissal()
        XCTAssertTrue(harness.probe.controls.isEmpty, "Done saves the draft without submitting the request")
        XCTAssertEqual(harness.button(label: AppStrings(language: .korean).voiceTutorInputCustom)?.accessibilityValue, draft)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "실제 예시")).accessibilityActivate())
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "연습 문제")).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, draft,
                       "Choosing another option after closing the editor must preserve custom text")
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.selectedOptionIds, ["concept", "practice"])
        attach(harness, name: "voice-input-selected-and-free-text")

        let submit = try XCTUnwrap(harness.button(label: "제출하고 계속"))
        XCTAssertFalse(submit.accessibilityTraits.contains(.notEnabled))
        XCTAssertTrue(submit.accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.controls.count, 1)
        XCTAssertTrue(harness.probe.state.holdsMicrophone)
        XCTAssertEqual(harness.probe.state.pending?.status, .submitting)
        XCTAssertEqual(harness.probe.controls.first?.answers?.first?.text, draft)
        attach(harness, name: "voice-input-awaiting-submit-ack")
        XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: harness.probe.request, sequence: 2, phase: .submitted)))
        try await harness.settle()
        XCTAssertFalse(harness.probe.state.holdsMicrophone)
        XCTAssertNil(harness.button(label: AppStrings(language: .korean).voiceTutorInputCustom))
        XCTAssertNil(harness.button(label: "제출하고 계속"))
        XCTAssertTrue(harness.hasAccessibilityLabel("개념 정리 · 연습 문제"), harness.accessibilityDescription())
        XCTAssertFalse(harness.hasAccessibilityLabel("실제 예시"), "Completed cards omit unselected choices")
        XCTAssertTrue(harness.hasAccessibilityLabel(draft), "The compact completed card retains custom text")
        XCTAssertTrue(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputSubmittedAnswers))
        XCTAssertFalse(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputUnsubmittedDraft))
        XCTAssertEqual(harness.probe.state.entries.first?.submittedAnswers, harness.probe.controls.first?.answers)
        attach(harness, name: "voice-input-compact-completed-card")
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
        XCTAssertNil(harness.editor())
        let custom = try XCTUnwrap(harness.button(label: AppStrings(language: .korean).voiceTutorInputCustom),
                                   harness.accessibilityDescription())
        XCTAssertEqual(custom.accessibilityValue, answer.text)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "연습 문제"), harness.accessibilityDescription()).accessibilityTraits.contains(.selected))
        XCTAssertFalse(try XCTUnwrap(harness.button(label: "실제 예시"), harness.accessibilityDescription()).accessibilityTraits.contains(.selected))
        XCTAssertTrue(harness.probe.state.holdsMicrophone)
        XCTAssertEqual(harness.probe.state.pending?.answers, [answer])
        attach(harness, name: "voice-input-large-text-invalid-answer-preserved")
        XCTAssertTrue(custom.accessibilityActivate())
        let editor = try await harness.presentedEditor()
        XCTAssertEqual(editor.text, answer.text)
        let done = try XCTUnwrap(harness.button(label: AppStrings(language: .korean).done))
        XCTAssertTrue(done.accessibilityActivate())
        try await harness.waitForEditorDismissal()
        XCTAssertEqual(harness.probe.state.pending?.answers, [answer])
        XCTAssertTrue(harness.probe.controls.isEmpty)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: AppStrings(language: .korean).cancel)).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.controls.count, 1)
        XCTAssertNil(harness.probe.controls.first?.answers)
        XCTAssertTrue(harness.probe.state.holdsMicrophone)
        XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: request, sequence: 3, phase: .cancelled)))
        try await harness.settle()
        XCTAssertFalse(harness.probe.state.holdsMicrophone)
        XCTAssertEqual(harness.probe.state.entries.last?.answers, [answer])
        XCTAssertNil(harness.button(label: AppStrings(language: .korean).voiceTutorInputCustom))
        XCTAssertNil(harness.button(label: "제출하고 계속"))
        XCTAssertTrue(harness.hasAccessibilityLabel("개념 정리 · 연습 문제"), harness.accessibilityDescription())
        XCTAssertFalse(harness.hasAccessibilityLabel("실제 예시"))
        XCTAssertTrue(harness.hasAccessibilityLabel(answer.text))
        XCTAssertTrue(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputUnsubmittedDraft),
            "Cancelled local selections must be labeled as drafts, never as submitted answers")
        XCTAssertFalse(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputSubmittedAnswers))
        XCTAssertNil(harness.probe.state.entries.first?.submittedAnswers)
        attach(harness, name: "voice-input-large-text-compact-cancelled-card")
    }

    func testEmptyCancelledCardShowsNoSelectionsOrSubmittedAnswerSummary() async throws {
        try requireInProcessSwiftUIAccessibility()
        let harness = try UserInputCardHarness()
        defer { harness.close() }
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.button(label: AppStrings(language: .korean).cancel)).accessibilityActivate())
        try await harness.settle()
        XCTAssertNil(harness.probe.controls.first?.answers)
        XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: harness.probe.request, sequence: 2, phase: .cancelled)))
        try await harness.settle()
        XCTAssertTrue(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputCancelled))
        XCTAssertFalse(harness.hasAccessibilityLabel("개념 정리"))
        XCTAssertFalse(harness.hasAccessibilityLabel("실제 예시"))
        XCTAssertFalse(harness.hasAccessibilityLabel("연습 문제"))
        XCTAssertFalse(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputSubmittedAnswers))
        XCTAssertFalse(harness.hasAccessibilityLabel(AppStrings(language: .korean).voiceTutorInputUnsubmittedDraft))
        XCTAssertNil(harness.button(label: AppStrings(language: .korean).voiceTutorInputContinue))
        attach(harness, name: "voice-input-empty-cancelled-card")
    }

    /// Runs on simulator AND iPhone. It uses UIKit's real text-input path and
    /// rendered production card, without depending on an in-process AX tree.
    func testNativeCardKoreanMultilineDraftAndRenderingOnEveryDevice() async throws {
        for size in [DynamicTypeSize.large, .accessibility2] {
            let harness = try UserInputCardHarness(dynamicType: size)
            defer { harness.close() }
            try await harness.settle()
            XCTAssertNil(harness.editor(), "The card uses a compact edit button, not an embedded textarea")
            attach(harness, name: "voice-input-native-compact-card-\(size)")
            harness.probe.presentsStandaloneEditor = true
            let editor = try await harness.presentedEditor()
            XCTAssertTrue(editor.isEditable)
            XCTAssertTrue(editor.becomeFirstResponder())
            let draft = "  직접 작성한 한글 초안\n둘째 줄도 그대로 유지합니다. 👩‍💻  "
            editor.insertText(draft)
            try await harness.settle()
            XCTAssertEqual(editor.text, draft)
            XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, draft)
            XCTAssertTrue(editor.isScrollEnabled)
            let secondLine = try XCTUnwrap(editor.position(from: editor.beginningOfDocument, in: .down, offset: 1))
            XCTAssertGreaterThan(editor.offset(from: editor.beginningOfDocument, to: secondLine), 0,
                                 "The native editor must support cursor movement between rendered lines")

            let request = harness.probe.request
            var answer = try XCTUnwrap(harness.probe.state.pending?.answers.first)
            answer.selectedOptionIds = ["concept", "practice"]
            let selection = NSRange(location: 2, length: 2)
            editor.selectedRange = selection
            harness.probe.state.update(requestID: request.id, answer: answer)
            try await harness.settle()
            XCTAssertTrue(harness.editor() === editor, "Option updates must keep the native editor instance")
            XCTAssertEqual(harness.editor()?.text, draft, "Parent selection updates must preserve the actual editor's draft")
            XCTAssertEqual(editor.selectedRange, selection)
            harness.window.endEditing(true)
            harness.probe.presentsStandaloneEditor = false
            try await harness.settle()
            XCTAssertNil(harness.editor())
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
            XCTAssertNil(harness.editor())

            XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: request, sequence: 2,
                phase: .pending, errorCode: "INVALID_ANSWERS")))
            try await harness.settle()
            XCTAssertNil(harness.editor(), "A retry must preserve the compact card until the learner opens the editor")
            XCTAssertEqual(harness.probe.state.pending?.answers, [answer])
            XCTAssertTrue(harness.probe.state.holdsMicrophone)

            harness.probe.presentsStandaloneEditor = true
            let retryEditor = try await harness.presentedEditor()
            XCTAssertTrue(retryEditor.isEditable)
            XCTAssertEqual(retryEditor.text, draft)
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
            harness.probe.presentsStandaloneEditor = false
            try await harness.settle()
            XCTAssertNil(harness.editor())
            attach(harness, name: "voice-input-native-retry-draft-\(size)")
            harness.probe.send(cancel: false)
            XCTAssertTrue(harness.probe.state.apply(UserInputFixture.event(for: request, sequence: 3, phase: .submitted)))
            try await harness.settle()
            XCTAssertEqual(harness.probe.controls.count, 2)
            XCTAssertEqual(harness.probe.controls.last?.answers, [revisedAnswer])
            XCTAssertFalse(harness.probe.state.holdsMicrophone)
            XCTAssertEqual(harness.probe.state.entries.last?.answers, [revisedAnswer])
            XCTAssertNil(harness.editor())
            // This attachment shows the settled read-only card, after its
            // pending-to-submitted transition has completely faded out.
            try await Task.sleep(for: .milliseconds(500))
            attach(harness, name: "voice-input-native-completed-card-\(size)")
        }
        // Additional visual coverage shares the same production card and has
        // no separate interaction harness or network dependencies.
        let narrow = try UserInputCardHarness(appearance: .light, width: 375)
        defer { narrow.close() }
        try await narrow.settle()
        XCTAssertNil(narrow.editor())
        attach(narrow, name: "voice-input-light-375-empty-card")
        let answer = VoiceTutorUserInputAnswer(questionId: "style", selectedOptionIds: ["concept", "practice"],
            text: "실제 프로젝트에서 쓰는 방식이 궁금해요.\n코드 예시도 함께 설명해 주세요.")
        narrow.probe.state.update(requestID: narrow.probe.request.id, answer: answer)
        try await narrow.settle()
        attach(narrow, name: "voice-input-light-375-selected-draft-card")
    }

    func testNativeInputEditorPreservesKoreanCompositionAcrossParentUpdates() async throws {
        let harness = try UserInputCardHarness()
        defer { harness.close() }
        var answer = try XCTUnwrap(harness.probe.state.pending?.answers.first)
        answer.text = "직접 입력: "
        harness.probe.state.update(requestID: harness.probe.request.id, answer: answer)
        harness.probe.presentsStandaloneEditor = true
        let editor = try await harness.presentedEditor()
        XCTAssertTrue(editor.becomeFirstResponder())
        editor.selectedRange = NSRange(location: answer.text.utf16.count, length: 0)
        editor.setMarkedText("ㅎ", selectedRange: NSRange(location: 1, length: 0))
        editor.setMarkedText("한", selectedRange: NSRange(location: 1, length: 0))
        let selection = editor.selectedRange
        XCTAssertEqual(editor.text(in: try XCTUnwrap(editor.markedTextRange)), "한")
        harness.probe.parentRevision += 1
        try await harness.settle()
        XCTAssertTrue(harness.editor() === editor)
        XCTAssertEqual(editor.text(in: try XCTUnwrap(editor.markedTextRange)), "한",
                       "A parent refresh must neither commit nor discard unfinished Korean composition")
        XCTAssertEqual(editor.selectedRange, selection)
        editor.unmarkText()
        editor.insertText("글\n둘째 줄")
        try await harness.settle()
        let expected = "직접 입력: 한글\n둘째 줄"
        XCTAssertNil(editor.markedTextRange)
        XCTAssertEqual(editor.text, expected)
        XCTAssertEqual(harness.probe.state.pending?.answers.first?.text, expected)
        XCTAssertTrue(harness.probe.controls.isEmpty)
        attach(harness, name: "voice-input-native-korean-composition")
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
        print("VOICE_USER_INPUT_INTERACTION_READY: Tap 개념 정리 and 연습 문제, then 직접 입력; enter 실기기 입력, newline, 줄바꿈 확인; tap 완료, then 제출 within 60 seconds.")
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
    @Published var presentsStandaloneEditor = false
    @Published var parentRevision = 0
    let request: VoiceTutorUserInputRequest
    private(set) var controls: [VoiceTutorUserInputControl] = []

    init() {
        request = UserInputFixture.request(questions: [UserInputFixture.request().questions[1]])
        _ = state.apply(request, sessionID: request.sessionId)
    }

    func send(cancel: Bool) {
        if let control = state.submit(requestID: request.id, cancel: cancel) { controls.append(control) }
    }

    func editorText(questionID: String) -> Binding<String> {
        Binding(get: {
            self.state.entries.first(where: { $0.id == self.request.id })?.answers
                .first(where: { $0.questionId == questionID })?.text ?? ""
        }, set: { text in
            guard let entry = self.state.pending, entry.id == self.request.id,
                  entry.status == .pending,
                  var answer = entry.answers.first(where: { $0.questionId == questionID }) else { return }
            answer.text = text
            self.state.update(requestID: entry.id, answer: answer)
        })
    }
}

private struct UserInputCardTestParent: View {
    @ObservedObject var probe: UserInputCardProbe
    let dynamicType: DynamicTypeSize

    var body: some View {
        let _ = probe.parentRevision
        Group {
            if probe.presentsStandaloneEditor, let entry = probe.state.entries.first,
               let question = entry.request.questions.first {
                // Physical hosted tests cannot activate SwiftUI's AX nodes.
                // Host the same production editor directly; simulator tests
                // above also verify the real card-to-sheet button route.
                VoiceTutorUserInputEditor(strings: AppStrings(language: .korean), question: question,
                    text: probe.editorText(questionID: question.id), isEditable: entry.status == .pending)
            } else {
                ScrollView {
                    if let entry = probe.state.entries.first {
                        VoiceTutorUserInputCard(entry: entry, strings: AppStrings(language: .korean),
                            onChange: { probe.state.update(requestID: entry.id, answer: $0) },
                            onSubmit: { probe.send(cancel: false) }, onCancel: { probe.send(cancel: true) })
                            .padding(16)
                    }
                }
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

    init(dynamicType: DynamicTypeSize = .large, appearance: UIUserInterfaceStyle = .dark, width: CGFloat? = nil) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        window = UIWindow(windowScene: scene)
        window.frame = scene.screen.bounds
        if let width { window.frame.size.width = min(width, scene.screen.bounds.width) }
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = UIHostingController(rootView: UserInputCardTestParent(probe: probe, dynamicType: dynamicType))
        window.makeKeyAndVisible()
        layout()
    }

    func settle() async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(180))
        layout()
    }

    func presentedEditor() async throws -> UITextView {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while ProcessInfo.processInfo.systemUptime < deadline {
            try await settle()
            if let editor = editor(), editor.window != nil, editor.bounds.height > 0 {
                return editor
            }
        }
        return try XCTUnwrap(editor(), "The production input editor must become visible")
    }

    func waitForEditorDismissal() async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while window.rootViewController?.presentedViewController != nil,
              ProcessInfo.processInfo.systemUptime < deadline {
            try await settle()
        }
        XCTAssertNil(window.rootViewController?.presentedViewController, "Done must dismiss the editing sheet")
        XCTAssertNil(editor(), "Dismissal must return to the compact card")
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
            "\(type(of: $0)) element=\($0.isAccessibilityElement) traits=\($0.accessibilityTraits.rawValue) frame=\($0.accessibilityFrame) label=\($0.accessibilityLabel ?? "nil") value=\($0.accessibilityValue ?? "nil")"
        }.joined(separator: "\n")
    }

    func hasAccessibilityLabel(_ label: String) -> Bool {
        accessibilityObjects().contains { $0.accessibilityLabel == label }
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
