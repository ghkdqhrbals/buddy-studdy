import Foundation
import Combine
import SwiftUI
import UIKit
import XCTest
@testable import StudyMate

final class VoiceTutorSessionStateTests: XCTestCase {
    private let type = "buddystudy.voice.session.state"
    private let answerID = "11111111-2222-3333-4444-555555555555"

    func testEveryServerPhaseHasAnExactTypedSnapshot() throws {
        for phase in VoiceTutorSessionStateEvent.Phase.allCases {
            let value = event(1, phase)
            XCTAssertEqual(try parse(fields(value)), .sessionState(value), phase.rawValue)
        }
    }

    func testMalformedOrUnscopedSnapshotsAreIgnored() throws {
        let valid = fields(event(1, .answering))
        for key in ["sequence", "phase", "paused", "revision", "studyId", "recordId", "answerId"] {
            var invalid = valid
            invalid.removeValue(forKey: key)
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
        let invalidValues: [(String, Any)] = [
            ("sequence", true), ("sequence", 0), ("sequence", -1), ("sequence", 1.5), ("sequence", "1"),
            ("phase", "unknown"), ("paused", 1), ("paused", "true"), ("revision", -1), ("revision", false),
            ("studyId", false), ("studyId", "42"), ("studyId", 0), ("recordId", "0101"),
            ("recordId", "9223372036854775808"), ("recordId", 101), ("answerId", "not-a-uuid"),
            ("answerId", "AAAAAAAA-2222-3333-4444-555555555555"), ("extra", "provider-authored text")
        ]
        for (key, value) in invalidValues {
            var invalid = valid
            invalid[key] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: type), "\(key): \(value)")
        }
        for key in ["studyId", "recordId", "answerId"] {
            var invalid = fields(event(1, .conversation, scoped: false))
            invalid[key] = NSNull()
            XCTAssertEqual(try parse(invalid), .ignored(type: type))
        }
    }

    func testOutOfOrderSnapshotCannotRewindReviewOrResumeTheCall() {
        var state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(3, .answerReview, paused: true)))
        XCTAssertFalse(state.apply(event(2, .answering)))
        XCTAssertFalse(state.apply(event(3, .answerReview)))
        XCTAssertEqual(state.snapshot?.phase, .answerReview)
        XCTAssertEqual(state.snapshot?.paused, true)
        XCTAssertTrue(state.apply(event(4, .answerReview)))
        XCTAssertEqual(state.snapshot?.paused, false)
        XCTAssertEqual(state.snapshot?.answerID, answerID)
    }

    func testNewSequenceCannotRestoreAnOldLessonRevision() {
        var state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(1, .questionReading, revision: 3)))
        XCTAssertFalse(state.apply(event(2, .graded, revision: 2)))
        XCTAssertFalse(state.apply(event(3, .questionReading, revision: 3), minimumRevision: 4))
        XCTAssertTrue(state.apply(event(4, .questionGenerating, revision: 4)))
        XCTAssertEqual(state.snapshot?.revision, 4)
    }

    func testEndCannotBeReversedByLateLiveOrPausedSnapshots() {
        for terminal in [VoiceTutorSessionStateEvent.Phase.ended, .failed] {
            var state = VoiceTutorSessionState()
            XCTAssertTrue(state.apply(event(1, .ending)))
            XCTAssertFalse(state.apply(event(2, .conversation)))
            XCTAssertTrue(state.apply(event(3, terminal)))
            XCTAssertFalse(state.apply(event(4, .answering, paused: true)))
            XCTAssertEqual(state.snapshot?.phase, terminal)
        }
        var state = VoiceTutorSessionState()
        _ = state.apply(event(1, .answering))
        state.endLocally()
        XCTAssertNil(state.snapshot)
        XCTAssertFalse(state.apply(event(2, .graded)))
        state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(1, .conversation)), "A new call owns a new sequence domain")
    }

    private func event(_ sequence: Int64, _ phase: VoiceTutorSessionStateEvent.Phase,
                       paused: Bool = false, revision: Int64 = 1, scoped: Bool = true) -> VoiceTutorSessionStateEvent {
        VoiceTutorSessionStateEvent(sequence: sequence, phase: phase, paused: paused, revision: revision,
            studyID: scoped ? 42 : nil, recordID: scoped ? "101" : nil, answerID: scoped ? answerID : nil)
    }

    private func fields(_ event: VoiceTutorSessionStateEvent) -> [String: Any] {
        var value: [String: Any] = ["type": type, "sequence": event.sequence, "phase": event.phase.rawValue,
                                  "paused": event.paused, "revision": event.revision]
        if let studyID = event.studyID { value["studyId"] = studyID }
        if let recordID = event.recordID { value["recordId"] = recordID }
        if let answerID = event.answerID { value["answerId"] = answerID }
        return value
    }

    private func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}

final class VoiceTutorOperationContextTests: XCTestCase {
    private let type = "buddystudy.voice.operation.context"

    func testContextWireContractRequiresExactSafeIdentifiersAndRejectsExtraPayloads() throws {
        let answerID = "11111111-2222-4333-a444-555555555555"
        let context = VoiceTutorOperationContextEvent(operationID: "call_read_1", responseID: "resp_origin",
            learnerItemID: "learner_origin", tutorItemID: "tutor_origin", answerID: answerID)
        let valid: [String: Any] = ["type": type, "operationId": "call_read_1", "responseId": "resp_origin",
            "learnerItemId": "learner_origin", "tutorItemId": "tutor_origin", "answerId": answerID]
        XCTAssertEqual(try parse(valid), .operationContext(context))
        XCTAssertEqual(try parse(["type": type, "operationId": "call_only"]),
            .operationContext(.init(operationID: "call_only")))
        XCTAssertEqual(try parse(["type": type, "operationId": "call_answer", "answerId": answerID]),
            .operationContext(.init(operationID: "call_answer", answerID: answerID)))
        let invalidValues: [Any] = ["", "unsafe/value", "has space", "한글", String(repeating: "a", count: 192), 1, true, NSNull()]
        for key in ["operationId", "responseId", "learnerItemId", "tutorItemId", "answerId"] {
            for invalid in invalidValues {
                var fields = valid
                fields[key] = invalid
                XCTAssertEqual(try parse(fields), .ignored(type: type), "\(key)=\(invalid)")
            }
        }
        for invalidAnswerID in ["answer_origin-1", String(repeating: "a", count: 191), answerID.uppercased()] {
            var fields = valid
            fields["answerId"] = invalidAnswerID
            XCTAssertEqual(try parse(fields), .ignored(type: type), "answerId must be a canonical lowercase UUID")
        }
        var missing = valid
        missing.removeValue(forKey: "operationId")
        XCTAssertEqual(try parse(missing), .ignored(type: type))
        for extra in ["arguments", "result", "text", "sequence", "elapsedMs"] {
            var fields = valid
            fields[extra] = "unexpected"
            XCTAssertEqual(try parse(fields), .ignored(type: type), extra)
        }
    }

    func testCompletionKeepsTheCaptionAndResponseCapturedAtOperationStart() throws {
        let first = VoiceTutorCaption(speaker: .tutor, text: "첫 대화", responseID: "resp_first")
        let second = VoiceTutorCaption(speaker: .tutor, text: "두 번째 대화", responseID: "resp_second")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "read", .started), at: 10, afterCaptionID: first.id, responseID: "resp_first"))
        XCTAssertTrue(state.apply(event(2, "read", .completed, elapsed: 2_300), at: 12.3,
            afterCaptionID: second.id, responseID: "resp_second"))
        let completed = try XCTUnwrap(state.finished.first)
        XCTAssertEqual(completed.afterCaptionID, first.id)
        XCTAssertEqual(completed.fallbackResponseID, "resp_first")
        XCTAssertEqual(completed.receivedAt, 10)
        XCTAssertEqual(completed.elapsedMilliseconds(at: 9_000), 2_300)
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 9_000),
            captions: [first, second], assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[first.id]?.map(\.id), ["read"])
        XCTAssertNil(layout.byCaptionID[second.id])
        XCTAssertTrue(layout.beforeCaptions.isEmpty)
        state.endLocally()
        XCTAssertEqual(state.visibleEntries(at: 20_000).map(\.id), ["read"])
        XCTAssertFalse(state.apply(event(3, "read", .started), at: 20_001, afterCaptionID: second.id))
    }

    func testContextIsAcceptedOnlyOnceBeforeTheFirstOperationEvent() throws {
        let origin = VoiceTutorOperationContextEvent(operationID: "read", responseID: "resp_origin", learnerItemID: "learner_origin")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(origin))
        XCTAssertFalse(state.applyContext(origin))
        XCTAssertFalse(state.applyContext(.init(operationID: "read", responseID: "resp_replacement")))
        XCTAssertTrue(state.apply(event(1, "read", .started), at: 10))
        XCTAssertFalse(state.applyContext(.init(operationID: "read", responseID: "resp_late")))
        XCTAssertTrue(state.apply(event(2, "read", .completed), at: 11))
        XCTAssertFalse(state.applyContext(origin))
        let learner = VoiceTutorCaption(speaker: .learner, text: "이 요청을 확인해 주세요.", providerItemID: "learner_origin")
        let replacement = VoiceTutorCaption(speaker: .tutor, text: "나중의 설명", responseID: "resp_replacement")
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 11),
            captions: [learner, replacement], assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[learner.id]?.map(\.id), ["read"])
        XCTAssertNil(layout.byCaptionID[replacement.id])
    }

    func testCompletionArrivingWithoutStartConsumesItsEarlierContextAndCannotBeReanchored() throws {
        let origin = VoiceTutorCaption(speaker: .learner, text: "처음 요청", providerItemID: "learner_origin")
        let later = VoiceTutorCaption(speaker: .tutor, text: "나중 대화")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(.init(operationID: "fast_read", learnerItemID: "learner_origin")))
        XCTAssertTrue(state.apply(event(2, "fast_read", .completed, elapsed: 20), at: 10,
            afterCaptionID: later.id, responseID: "resp_later"))
        XCTAssertFalse(state.apply(event(3, "fast_read", .started), at: 11, afterCaptionID: later.id))
        XCTAssertFalse(state.applyContext(.init(operationID: "fast_read", responseID: "resp_later")))
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 11),
            captions: [origin, later], assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[origin.id]?.map(\.id), ["fast_read"])
        XCTAssertNil(layout.byCaptionID[later.id])
        XCTAssertEqual(state.finished.first?.event.phase, .completed)
    }

    func testToolOnlyResponseStaysWithItsLearnerUntilItsExactResponseHasVisibleText() throws {
        let learner = VoiceTutorCaption(speaker: .learner, text: "채점 결과를 확인해 주세요.", providerItemID: "learner_origin")
        let otherTutor = VoiceTutorCaption(speaker: .tutor, text: "다른 대화", responseID: "resp_other", providerItemID: "tutor_previous")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(.init(operationID: "read", responseID: "resp_tool",
            learnerItemID: "learner_origin", tutorItemID: "tutor_previous")))
        XCTAssertTrue(state.apply(event(1, "read", .started), at: 10, afterCaptionID: otherTutor.id))
        let entries = state.visibleEntries(at: 10)
        let toolOnly = VoiceTutorOperationTranscriptLayout(entries: entries, captions: [learner, otherTutor],
            assistantResponseID: "resp_other", hasAssistantDraft: true)
        XCTAssertEqual(toolOnly.byCaptionID[learner.id]?.map(\.id), ["read"])
        XCTAssertNil(toolOnly.byCaptionID[otherTutor.id])
        XCTAssertTrue(toolOnly.afterAssistantDraft.isEmpty)

        let matchingDraft = VoiceTutorOperationTranscriptLayout(entries: entries, captions: [learner, otherTutor],
            assistantResponseID: "resp_tool", hasAssistantDraft: true)
        XCTAssertEqual(matchingDraft.afterAssistantDraft.map(\.id), ["read"])
        XCTAssertTrue(matchingDraft.byCaptionID.isEmpty)
        let exactTutor = VoiceTutorCaption(speaker: .tutor, text: "이 결과를 확인할게요.", responseID: "resp_tool")
        let promoted = VoiceTutorOperationTranscriptLayout(entries: entries, captions: [learner, exactTutor, otherTutor],
            assistantResponseID: "resp_other", hasAssistantDraft: true)
        XCTAssertEqual(promoted.byCaptionID[exactTutor.id]?.map(\.id), ["read"])
        XCTAssertNil(promoted.byCaptionID[learner.id])
        XCTAssertNil(promoted.byCaptionID[otherTutor.id])
        XCTAssertTrue(promoted.afterAssistantDraft.isEmpty)
    }

    func testTutorOriginAndSnapshotFallbackNeverFollowTheLatestUnrelatedCaption() throws {
        let tutor = VoiceTutorCaption(speaker: .tutor, text: "원래 선생님 안내", providerItemID: "tutor_origin")
        let later = VoiceTutorCaption(speaker: .learner, text: "나중 요청")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(.init(operationID: "tutor_read", tutorItemID: "tutor_origin")))
        XCTAssertTrue(state.apply(event(1, "tutor_read", .started), at: 10, afterCaptionID: later.id))
        XCTAssertTrue(state.apply(event(2, "fallback_read", .started), at: 11, afterCaptionID: tutor.id))
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 12),
            captions: [tutor, later], assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[tutor.id]?.map(\.id), ["tutor_read", "fallback_read"])
        XCTAssertNil(layout.byCaptionID[later.id])
    }

    func testTrimmedFallbackOriginIsHiddenRatherThanMovedAndUnanchoredWorkPrecedesCaptions() throws {
        let retired = VoiceTutorCaption(speaker: .learner, text: "화면에서 정리된 과거 대화")
        let later = VoiceTutorCaption(speaker: .tutor, text: "남아 있는 대화")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "retired_read", .started), at: 10, afterCaptionID: retired.id))
        XCTAssertTrue(state.apply(event(2, "unanchored_read", .started), at: 11))
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 12),
            captions: [later], assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.beforeCaptions.map(\.id), ["unanchored_read"])
        XCTAssertTrue(layout.byCaptionID.isEmpty)
        XCTAssertTrue(layout.afterAssistantDraft.isEmpty)
    }

    func testUnresolvedContextOriginNeverUsesANewerSnapshotAndResolvesWhenItsCaptionArrives() {
        let newer = VoiceTutorCaption(speaker: .tutor, text: "폴링 중 새로 도착한 대화", responseID: "resp_newer")
        let learnerOrigin = VoiceTutorCaption(speaker: .learner, text: "이전 채점 요청", providerItemID: "learner_old")
        let tutorOrigin = VoiceTutorCaption(speaker: .tutor, text: "원래 호출 안내", providerItemID: "tutor_old")
        let responseOrigin = VoiceTutorCaption(speaker: .tutor, text: "원래 응답의 뒤늦은 자막", responseID: "resp_old")
        let cases: [(VoiceTutorOperationContextEvent, VoiceTutorCaption)] = [
            (.init(operationID: "old_poll", learnerItemID: "learner_old"), learnerOrigin),
            (.init(operationID: "old_tutor", tutorItemID: "tutor_old"), tutorOrigin),
            (.init(operationID: "old_response", responseID: "resp_old"), responseOrigin)
        ]
        var state = VoiceTutorOperationState()
        for (index, item) in cases.enumerated() {
            XCTAssertTrue(state.applyContext(item.0))
            XCTAssertTrue(state.apply(event(Int64(index + 1), item.0.operationID, .completed), at: Double(index),
                afterCaptionID: newer.id, responseID: newer.responseID))
        }
        let entries = state.visibleEntries(at: 10)
        let unresolved = VoiceTutorOperationTranscriptLayout(entries: entries, captions: [newer],
            assistantResponseID: newer.responseID, hasAssistantDraft: true)
        XCTAssertTrue(unresolved.byCaptionID.isEmpty)
        XCTAssertTrue(unresolved.beforeCaptions.isEmpty)
        XCTAssertTrue(unresolved.afterAssistantDraft.isEmpty)
        XCTAssertTrue(unresolved.byUserInputID.isEmpty)
        XCTAssertEqual(state.finished.count, cases.count, "Hidden origins retain their operations for later resolution")

        let resolved = VoiceTutorOperationTranscriptLayout(entries: entries, captions: [newer] + cases.map { $0.1 },
            assistantResponseID: newer.responseID, hasAssistantDraft: true)
        for (context, origin) in cases {
            XCTAssertEqual(resolved.byCaptionID[origin.id]?.map(\.id), [context.operationID])
        }
        XCTAssertNil(resolved.byCaptionID[newer.id])
        XCTAssertTrue(resolved.afterAssistantDraft.isEmpty)
    }

    func testPendingContextsAreBoundedConsumedAndClosedWithTheCall() {
        var state = VoiceTutorOperationState()
        for index in 1...16 { XCTAssertTrue(state.applyContext(.init(operationID: "call_\(index)", responseID: "resp_\(index)"))) }
        XCTAssertFalse(state.applyContext(.init(operationID: "call_17", responseID: "resp_17")))
        XCTAssertTrue(state.apply(event(1, "call_1", .started), at: 10))
        XCTAssertTrue(state.applyContext(.init(operationID: "call_17", responseID: "resp_17")))
        state.endLocally()
        XCTAssertFalse(state.applyContext(.init(operationID: "call_new", responseID: "resp_new")))
        XCTAssertFalse(state.apply(event(2, "call_2", .started), at: 11))
    }

    func testOperationStartedBeforeAnyCaptionCannotAcquireALaterFallbackAtCompletion() {
        let later = VoiceTutorCaption(speaker: .tutor, text: "나중에 도착한 첫 메시지", responseID: "resp_later")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "early", .started), at: 10))
        XCTAssertTrue(state.apply(event(2, "early", .completed), at: 11,
            afterCaptionID: later.id, responseID: "resp_later"))
        XCTAssertNil(state.finished.first?.afterCaptionID)
        XCTAssertNil(state.finished.first?.fallbackResponseID)
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 11), captions: [later],
            assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.beforeCaptions.map(\.id), ["early"])
        XCTAssertTrue(layout.byCaptionID.isEmpty)
    }

    func testEveryCapturedAnswerSourceItemResolvesToTheSameReviewedLearnerMessage() {
        let answer = VoiceTutorCaption(speaker: .learner, text: "여러 번 말하고 수정한 최종 답변",
            providerItemIDs: Set(["answer_part_1", "answer_part_2", "answer_part_3"]))
        let later = VoiceTutorCaption(speaker: .tutor, text: "후속 설명")
        var state = VoiceTutorOperationState()
        for index in 1...3 {
            XCTAssertTrue(state.applyContext(.init(operationID: "read_\(index)", learnerItemID: "answer_part_\(index)")))
            XCTAssertTrue(state.apply(event(Int64(index), "read_\(index)", .started), at: Double(index), afterCaptionID: later.id))
        }
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 5), captions: [answer, later],
            assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[answer.id]?.map(\.id), ["read_1", "read_2", "read_3"])
        XCTAssertNil(layout.byCaptionID[later.id])
    }

    func testTextOnlySubmittedAnswerTakesPriorityOverTheOldLearnerAndTutorResponseOrigins() {
        let answerID = "11111111-2222-3333-4444-555555555555"
        let oldLearner = VoiceTutorCaption(speaker: .learner, text: "문제를 내 주세요.", providerItemID: "learner_question")
        let tutor = VoiceTutorCaption(speaker: .tutor, text: "이제 답변을 확인할게요.", responseID: "resp_grading")
        let typedAnswer = VoiceTutorCaption(speaker: .learner, text: "음성 없이 직접 입력해 제출한 답변", answerID: answerID)
        XCTAssertNil(typedAnswer.providerItemID)
        XCTAssertTrue(typedAnswer.providerItemIDs.isEmpty)
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(.init(operationID: "grade_typed", responseID: "resp_grading",
            learnerItemID: "learner_question", answerID: answerID)))
        XCTAssertTrue(state.apply(event(1, "grade_typed", .started), at: 10, afterCaptionID: oldLearner.id))
        XCTAssertTrue(state.apply(event(2, "grade_typed", .completed), at: 11, afterCaptionID: tutor.id))
        let entries = state.visibleEntries(at: 11)
        let unknown = VoiceTutorOperationTranscriptLayout(entries: entries,
            captions: [oldLearner, tutor], assistantResponseID: "resp_grading", hasAssistantDraft: true)
        XCTAssertTrue(unknown.byCaptionID.isEmpty, "An unresolved answer ID must not fall back to old learner/tutor origins")
        XCTAssertTrue(unknown.afterAssistantDraft.isEmpty)
        XCTAssertTrue(unknown.beforeCaptions.isEmpty)
        XCTAssertTrue(unknown.afterAnswerDraft.isEmpty)

        let draft = VoiceTutorOperationTranscriptLayout(entries: entries,
            captions: [oldLearner, tutor], assistantResponseID: "resp_grading", hasAssistantDraft: true, answerDraftID: answerID)
        XCTAssertEqual(draft.afterAnswerDraft.map(\.id), ["grade_typed"])
        XCTAssertTrue(draft.byCaptionID.isEmpty)
        XCTAssertTrue(draft.afterAssistantDraft.isEmpty)

        let layout = VoiceTutorOperationTranscriptLayout(entries: entries,
            captions: [oldLearner, typedAnswer, tutor], assistantResponseID: "resp_grading", hasAssistantDraft: true)
        XCTAssertEqual(layout.byCaptionID[typedAnswer.id]?.map(\.id), ["grade_typed"])
        XCTAssertNil(layout.byCaptionID[oldLearner.id])
        XCTAssertNil(layout.byCaptionID[tutor.id])
        XCTAssertTrue(layout.afterAssistantDraft.isEmpty)
        XCTAssertTrue(layout.beforeCaptions.isEmpty)
        XCTAssertTrue(layout.afterAnswerDraft.isEmpty)
    }

    func testStructuredInputOriginAttachesToOnlyItsExactCard() {
        let requestID = "11111111-2222-3333-4444-555555555555"
        let otherRequestID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        let later = VoiceTutorCaption(speaker: .tutor, text: "선택 뒤의 다른 대화")
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.applyContext(.init(operationID: "selected_action", responseID: "resp_tool_only",
            learnerItemID: "buddystudy-user-input-" + requestID)))
        XCTAssertTrue(state.apply(event(1, "selected_action", .started), at: 10, afterCaptionID: later.id))
        XCTAssertTrue(state.apply(event(2, "selected_action", .completed), at: 11, afterCaptionID: later.id))
        let layout = VoiceTutorOperationTranscriptLayout(entries: state.visibleEntries(at: 11), captions: [later],
            assistantResponseID: nil, hasAssistantDraft: false, userInputIDs: Set([requestID, otherRequestID]))
        XCTAssertEqual(layout.byUserInputID[requestID]?.map(\.id), ["selected_action"])
        XCTAssertNil(layout.byUserInputID[otherRequestID])
        XCTAssertTrue(layout.byCaptionID.isEmpty)
        XCTAssertTrue(layout.beforeCaptions.isEmpty)
    }

    private func event(_ sequence: Int64, _ id: String, _ phase: VoiceTutorOperationEvent.Phase,
                       elapsed: Int64 = 0) -> VoiceTutorOperationEvent {
        .init(sequence: sequence, operationID: id, name: "get_grading_process", phase: phase, elapsedMilliseconds: elapsed)
    }

    private func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}

final class VoiceTutorOperationStateTests: XCTestCase {
    private let type = "buddystudy.voice.operation"

    func testOperationWireContractRejectsPayloadsAndMalformedTiming() throws {
        let valid: [String: Any] = ["type": type, "sequence": 1, "operationId": "call_read_1",
            "name": "get_grading_process", "phase": "started", "elapsedMs": 0]
        XCTAssertEqual(try parse(valid), .operation(event(1, "call_read_1", .started)))
        let invalidValues: [(String, Any)] = [
            ("sequence", true), ("sequence", 0), ("sequence", "1"), ("sequence", 1.5),
            ("elapsedMs", false), ("elapsedMs", -1), ("elapsedMs", 0.5), ("elapsedMs", 3_600_001),
            ("operationId", ""), ("operationId", "call/secret"), ("name", "get_record(arguments)"),
            ("name", ""), ("phase", "pending"), ("arguments", ["answer": "private"]),
            ("result", "private response"), ("message", "provider failure body")
        ]
        for (key, value) in invalidValues {
            var invalid = valid
            invalid[key] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
        for key in valid.keys {
            guard key != "type" else { continue }
            var invalid = valid
            invalid.removeValue(forKey: key)
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
    }

    func testElapsedTimeSurvivesBackgroundAndDuplicateStartWithoutRewinding() {
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "call_read_1", .started), at: 100))
        XCTAssertEqual(state.active.first?.elapsedMilliseconds(at: 100.125), 125)
        XCTAssertFalse(state.apply(event(2, "call_read_1", .started), at: 101))
        XCTAssertEqual(state.active.first?.elapsedMilliseconds(at: 130), 30_000)
        XCTAssertTrue(state.apply(event(3, "call_read_1", .completed, elapsed: 30_050), at: 131))
        XCTAssertTrue(state.active.isEmpty)
        XCTAssertEqual(state.visibleEntries(at: 132).first?.elapsedMilliseconds(at: 132), 30_050)
        XCTAssertEqual(state.visibleEntries(at: 3_600).map(\.id), ["call_read_1"])
        state.endLocally()
        XCTAssertEqual(state.visibleEntries(at: 3_700).map(\.id), ["call_read_1"])
        XCTAssertFalse(state.apply(event(2, "call_read_1", .started), at: 137))
        XCTAssertFalse(state.apply(event(4, "call_read_1", .started), at: 137))
    }

    func testConcurrentOperationCompletionDoesNotHideAnotherPendingCall() {
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "one", .started), at: 10))
        XCTAssertTrue(state.apply(event(2, "two", .started), at: 11))
        XCTAssertTrue(state.apply(event(3, "two", .failed, elapsed: 420), at: 12))
        XCTAssertEqual(state.visibleEntries(at: 12).map(\.id), ["one", "two"])
        XCTAssertTrue(state.apply(event(4, "one", .completed, elapsed: 2_300), at: 13))
        XCTAssertEqual(state.visibleEntries(at: 13).map(\.id), ["one", "two"])
        XCTAssertEqual(state.latestFinished?.event.phase, .completed)
    }

    func testOperationLimitAndCallEndFenceLateEvents() {
        var state = VoiceTutorOperationState()
        for sequence in 1...8 {
            XCTAssertTrue(state.apply(event(Int64(sequence), "call_\(sequence)", .started), at: 100))
        }
        XCTAssertFalse(state.apply(event(9, "call_9", .started), at: 100))
        XCTAssertEqual(state.active.count, VoiceTutorOperationState.maximumActiveOperations)
        state.endLocally()
        XCTAssertTrue(state.visibleEntries(at: 101).isEmpty)
        XCTAssertFalse(state.apply(event(10, "call_1", .completed), at: 101))
        state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "new_call", .started), at: 102))
    }

    func testOperationStatusesAreLocalizedAndKeepExactFunctionAndMeasuredTime() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let labels = VoiceTutorOperationEvent.Phase.allCases.map {
                strings.voiceTutorOperationStatus(name: "get_grading_process", phase: $0, elapsedMilliseconds: 275)
            }
            XCTAssertEqual(Set(labels).count, 3)
            XCTAssertTrue(labels.allSatisfy { $0.hasPrefix("get_grading_process · ") && !$0.contains("ms") })
        }
        let strings = AppStrings(language: .korean)
        XCTAssertTrue(strings.voiceTutorOperationStatus(name: "read_studies", phase: .completed, elapsedMilliseconds: 2_300).hasSuffix("2초"))
        XCTAssertTrue(strings.voiceTutorOperationStatus(name: "read_studies", phase: .completed, elapsedMilliseconds: 125_000).hasSuffix("2분"))
    }

    @MainActor
    func testOperationStatusRendersInCallAndTranscript() async throws {
        for expanded in [false, true] {
            var state = VoiceTutorOperationState()
            _ = state.apply(event(1, "visual_operation", .started), at: ProcessInfo.processInfo.systemUptime - 0.275)
            let root = VoiceTutorCallScreen(
                topic: "스프링", presentation: VoiceTutorCallPresentation(phase: .listening, isAwaitingTutorResponse: true),
                strings: AppStrings(language: .korean), operationState: state,
                captions: [VoiceTutorCaption(speaker: .tutor, text: "저장된 채점 결과를 확인하고 있어요.")],
                showsTranscript: .constant(expanded), showsSummary: .constant(false)
            ).environment(\.colorScheme, .dark).environment(\.scenePhase, .active)
            let controller = UIHostingController(rootView: root)
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            let previous = scene?.windows.first { $0.isKeyWindow }
            let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow()
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
            window.overrideUserInterfaceStyle = .dark
            window.rootViewController = controller
            window.makeKeyAndVisible()
            defer { window.isHidden = true; window.rootViewController = nil; previous?.makeKey() }
            controller.view.frame = window.bounds
            controller.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(150))
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
                window.layer.render(in: context.cgContext)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = expanded ? "voice-operation-transcript" : "voice-operation-call"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTAssertGreaterThan(image.size.width, 0)
        }
    }

    private func event(_ sequence: Int64, _ id: String, _ phase: VoiceTutorOperationEvent.Phase,
                       elapsed: Int64 = 0) -> VoiceTutorOperationEvent {
        VoiceTutorOperationEvent(sequence: sequence, operationID: id, name: "get_grading_process",
            phase: phase, elapsedMilliseconds: elapsed)
    }

    private func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}

@MainActor
final class VoiceTutorOperationTranscriptPresentationTests: XCTestCase {
    func testCompletedOperationRemainsBelowItsFirstMessageWhenLaterConversationArrives() async throws {
        try requireHostedAccessibility()
        let first = VoiceTutorCaption(speaker: .tutor, text: "첫 대화: 저장된 결과를 확인할게요.", responseID: "resp_first")
        let second = VoiceTutorCaption(speaker: .learner, text: "두 번째 대화: 다른 내용도 궁금해요.", providerItemID: "learner_second")
        let third = VoiceTutorCaption(speaker: .tutor, text: "세 번째 대화: 이어서 설명할게요.", responseID: "resp_third")
        let harness = try OperationTranscriptHarness(captions: [first])
        defer { harness.close() }
        XCTAssertTrue(harness.probe.operations.apply(event(1, .started), at: 10,
            afterCaptionID: first.id, responseID: first.responseID))
        try await harness.settle()
        harness.probe.captions.append(second)
        try await harness.settle()
        // Complete only after the next message has arrived: the latest caption
        // must not replace the origin captured by the started event.
        XCTAssertTrue(harness.probe.operations.apply(event(2, .completed), at: 12.3,
            afterCaptionID: second.id, responseID: "resp_other"))
        try await harness.settle()
        try assertNativeOrder([first.text, completedLabel, second.text], in: harness)
        attach(harness, name: "voice-operation-first-message-completed-before-second")
        harness.probe.captions.append(third)
        try await harness.settle()
        try assertNativeOrder([first.text, completedLabel, second.text, third.text], in: harness)
        XCTAssertEqual(harness.probe.operations.finished.first?.afterCaptionID, first.id)
        attach(harness, name: "voice-operation-origin-kept-after-later-conversation")
    }

    func testToolOnlyOperationUsesItsLearnerOriginThenItsExactFinalResponseMessage() async throws {
        try requireHostedAccessibility()
        let learner = VoiceTutorCaption(speaker: .learner, text: "원래 요청: 채점 결과를 확인해 주세요.", providerItemID: "learner_origin")
        let other = VoiceTutorCaption(speaker: .tutor, text: "다른 응답: 이 메시지는 호출과 무관해요.", responseID: "resp_other")
        let exact = VoiceTutorCaption(speaker: .tutor, text: "호출한 응답: 해당 결과를 읽었어요.", responseID: "resp_origin")
        let later = VoiceTutorCaption(speaker: .learner, text: "다음 요청: 다음 주제로 넘어갈까요?", providerItemID: "learner_later")
        let harness = try OperationTranscriptHarness(captions: [learner, other])
        defer { harness.close() }
        XCTAssertTrue(harness.probe.operations.applyContext(.init(operationID: "visual_read", responseID: "resp_origin",
            learnerItemID: "learner_origin")))
        XCTAssertTrue(harness.probe.operations.apply(event(1, .started), at: 10, afterCaptionID: other.id))
        XCTAssertTrue(harness.probe.operations.apply(event(2, .completed), at: 12.3, afterCaptionID: other.id))
        try await harness.settle()
        try assertNativeOrder([learner.text, completedLabel, other.text], in: harness)
        attach(harness, name: "voice-operation-tool-only-kept-with-learner")
        harness.probe.captions.append(exact)
        harness.probe.captions.append(later)
        try await harness.settle()
        try assertNativeOrder([other.text, exact.text, completedLabel, later.text], in: harness)
        attach(harness, name: "voice-operation-exact-response-kept-before-next-request")
    }

    func testAnchoredCompletedOperationRendersOnEveryDevice() async throws {
        let first = VoiceTutorCaption(speaker: .tutor, text: "먼저 한 대화: 결과를 확인할게요.", responseID: "resp_first")
        let second = VoiceTutorCaption(speaker: .learner, text: "나중 대화: 다음 질문도 이어갈게요.", providerItemID: "learner_second")
        let harness = try OperationTranscriptHarness(captions: [first])
        defer { harness.close() }
        XCTAssertTrue(harness.probe.operations.apply(event(1, .started), at: 10, afterCaptionID: first.id))
        harness.probe.captions.append(second)
        XCTAssertTrue(harness.probe.operations.apply(event(2, .completed), at: 12.3, afterCaptionID: second.id))
        try await harness.settle()
        XCTAssertEqual(harness.probe.operations.finished.first?.afterCaptionID, first.id)
        attach(harness, name: "voice-operation-anchored-completion-native-render")
    }

    private var completedLabel: String {
        AppStrings(language: .korean).voiceTutorOperationStatus(name: "get_grading_process", phase: .completed, elapsedMilliseconds: 2_300)
    }

    private func event(_ sequence: Int64, _ phase: VoiceTutorOperationEvent.Phase) -> VoiceTutorOperationEvent {
        .init(sequence: sequence, operationID: "visual_read", name: "get_grading_process", phase: phase,
            elapsedMilliseconds: phase == .started ? 0 : 2_300)
    }

    private func requireHostedAccessibility() throws {
        #if !targetEnvironment(simulator)
        // Physical hosted XCTest exposes UIKit descendants without SwiftUI's
        // semantic AX nodes. The render test above remains mandatory on iPhone.
        throw XCTSkip("Hosted SwiftUI native AX ordering is verified on simulator; the anchored render test runs on iPhone")
        #endif
    }

    private func assertNativeOrder(_ texts: [String], in harness: OperationTranscriptHarness) throws {
        let nodes = harness.semanticTextElements()
        let description = nodes.map { "\($0.accessibilityLabel ?? "nil") frame=\($0.accessibilityFrame)" }.joined(separator: "\n")
        var previousIndex: Int?
        var previousFrame: CGRect?
        for text in texts {
            let matches = nodes.indices.filter { nodes[$0].accessibilityLabel?.contains(text) == true }
            let exactMatches = matches.filter { nodes[$0].accessibilityLabel == text }
            XCTAssertEqual(exactMatches.count, 1, "Each visible message/status must have exactly one text leaf: \(text)\n\(description)")
            var index = try XCTUnwrap(exactMatches.first, description)
            var frame = nodes[index].accessibilityFrame
            XCTAssertGreaterThan(frame.height, 0, description)
            // The native automation tree exposes both CaptionBubble's combined
            // speaker + text node and its selectable text leaf. Only accept that
            // containing parent; a second text leaf or a separately positioned
            // copy remains a failure. Compare the full bubble's bounds below.
            let parents = matches.filter { $0 != index }
            XCTAssertLessThanOrEqual(parents.count, 1, "Only the combined caption parent may repeat its text\n\(description)")
            let strings = AppStrings(language: .korean)
            let combinedLabels = ["\(strings.voiceTutorYou), \(text)", "\(strings.voiceTutorTeacher), \(text)"]
            for parentIndex in parents {
                let parent = nodes[parentIndex]
                XCTAssertTrue(combinedLabels.contains(parent.accessibilityLabel ?? ""), description)
                XCTAssertLessThan(parentIndex, index, "The combined caption must precede its text leaf\n\(description)")
                XCTAssertTrue(parent.accessibilityFrame.insetBy(dx: -1, dy: -1).contains(frame),
                    "Combined caption bounds must contain its leaf, not represent another rendering\n\(description)")
                frame = parent.accessibilityFrame
                index = parentIndex
            }
            if let previousIndex { XCTAssertLessThan(previousIndex, index, "Native reading order must follow the operation's origin\n\(description)") }
            if let previousFrame { XCTAssertLessThanOrEqual(previousFrame.maxY, frame.minY + 1, "The operation must render below its origin and above later conversation\n\(description)") }
            previousIndex = index
            previousFrame = frame
        }
    }

    private func attach(_ harness: OperationTranscriptHarness, name: String) {
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

@MainActor
final class VoiceTutorUserInputTranscriptPresentationTests: XCTestCase {
    func testCompletedCardsRenderBelowTheirOriginalConversationInLightAndDarkOnEveryDevice() async throws {
        let first = VoiceTutorCaption(speaker: .tutor, text: "먼저 주제를 골라 주세요.", responseID: "form_origin_first")
        let second = VoiceTutorCaption(speaker: .tutor, text: "이번에는 학습 방식을 골라 주세요.", responseID: "form_origin_second")
        let later = VoiceTutorCaption(speaker: .tutor, text: "제출한 방식으로 이어갈게요.", responseID: "form_later")
        let captions = [first, second, later]
        let sessionID = "11111111-1111-4111-a111-111111111111"
        let attemptID = "22222222-2222-4222-a222-222222222222"
        let cancelled = VoiceTutorUserInputRequest(requestId: "33333333-3333-4333-a333-333333333333",
            sessionId: sessionID, attemptId: attemptID, sequence: 1, title: "처음 제안",
            questions: [.init(id: "topic", prompt: "주제를 선택해 주세요.", selectionMode: .single,
                options: [.init(id: "redis", label: "Redis")], allowFreeText: true)], operationId: "call_form_first")
        let submitted = VoiceTutorUserInputRequest(requestId: "44444444-4444-4444-a444-444444444444",
            sessionId: sessionID, attemptId: attemptID, sequence: 3, title: "학습 방식",
            questions: [.init(id: "style", prompt: "원하는 설명을 모두 골라 주세요.", selectionMode: .multiple,
                options: [.init(id: "concept", label: "개념 정리"), .init(id: "example", label: "실제 예시"),
                    .init(id: "practice", label: "연습 문제")], allowFreeText: true)], operationId: "call_form_second")
        let answer = VoiceTutorUserInputAnswer(questionId: "style", selectedOptionIds: ["concept", "example"],
            text: "서비스 경계 예시를 보여 주세요.\n한글 입력도 그대로 남아요.")
        var operations = VoiceTutorOperationState()
        var inputs = VoiceTutorUserInputState()
        XCTAssertTrue(operations.applyContext(.init(operationID: "call_form_first", responseID: first.responseID)))
        XCTAssertTrue(operations.apply(.init(sequence: 1, operationID: "call_form_first", name: "request_user_input",
            phase: .started, elapsedMilliseconds: 0), at: 1))
        XCTAssertTrue(inputs.apply(cancelled, sessionID: sessionID, operation: operations.active.first,
            afterCaptionID: later.id, responseID: later.responseID))
        XCTAssertNotNil(inputs.submit(requestID: cancelled.id, cancel: true))
        XCTAssertTrue(inputs.apply(.init(requestId: cancelled.id, sessionId: sessionID, attemptId: attemptID,
            sequence: 2, phase: .cancelled, errorCode: nil)))
        XCTAssertTrue(operations.apply(.init(sequence: 2, operationID: "call_form_first", name: "request_user_input",
            phase: .completed, elapsedMilliseconds: 1_000), at: 2, afterCaptionID: later.id))
        XCTAssertTrue(operations.applyContext(.init(operationID: "call_form_second", responseID: second.responseID)))
        XCTAssertTrue(operations.apply(.init(sequence: 3, operationID: "call_form_second", name: "request_user_input",
            phase: .started, elapsedMilliseconds: 0), at: 3))
        XCTAssertTrue(inputs.apply(submitted, sessionID: sessionID, operation: operations.active.first,
            afterCaptionID: later.id, responseID: later.responseID))
        inputs.update(requestID: submitted.id, answer: answer)
        XCTAssertEqual(inputs.submit(requestID: submitted.id, cancel: false)?.answers, [answer])
        XCTAssertTrue(inputs.apply(.init(requestId: submitted.id, sessionId: sessionID, attemptId: attemptID,
            sequence: 4, phase: .submitted, errorCode: nil)))
        XCTAssertTrue(operations.apply(.init(sequence: 4, operationID: "call_form_second", name: "request_user_input",
            phase: .completed, elapsedMilliseconds: 2_000), at: 5, afterCaptionID: later.id))
        let placement = VoiceTutorUserInputTranscriptLayout(entries: inputs.entries, captions: captions,
            assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(placement.byCaptionID[first.id]?.map(\.id), [cancelled.id])
        XCTAssertEqual(placement.byCaptionID[second.id]?.map(\.id), [submitted.id])
        XCTAssertNil(placement.byCaptionID[later.id])
        XCTAssertNil(inputs.entries.first?.submittedAnswers)
        XCTAssertEqual(inputs.entries.last?.submittedAnswers, [answer])

        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let style = appearance == .light ? "light" : "dark"
            let harness = try OperationTranscriptHarness(captions: captions, operations: operations,
                userInputs: inputs, appearance: appearance)
            defer { harness.close() }
            try await harness.settle()
            // Mount already-final cards, then let the full screen's initial
            // scroll/appearance settle before collecting either theme image.
            try await Task.sleep(for: .milliseconds(500))
            try await harness.scrollTranscript(toBottom: false)
            #if targetEnvironment(simulator)
            try assertNativeOrder([first.text, cancelled.title, second.text], in: harness)
            XCTAssertFalse(harness.semanticTextElements().contains { $0.accessibilityLabel == "Redis" },
                "A blank cancelled form cannot look like a selected answer")
            #endif
            attach(harness, name: "voice-input-transcript-\(style)-cancelled-origin")
            try await harness.scrollTranscript(toBottom: true)
            #if targetEnvironment(simulator)
            try assertNativeOrder([second.text, submitted.title, AppStrings(language: .korean).voiceTutorInputSubmittedAnswers,
                submitted.questions[0].prompt, "개념 정리 · 실제 예시", answer.text, later.text], in: harness)
            XCTAssertFalse(harness.semanticTextElements().contains { $0.accessibilityLabel == "연습 문제" },
                "Read-only results omit the option that was never submitted")
            #endif
            attach(harness, name: "voice-input-transcript-\(style)-submitted-origin")
        }
    }

    private func assertNativeOrder(_ texts: [String], in harness: OperationTranscriptHarness) throws {
        let nodes = harness.semanticTextElements()
        let description = nodes.map { "\($0.accessibilityLabel ?? "nil") frame=\($0.accessibilityFrame)" }.joined(separator: "\n")
        var previousIndex: Int?
        var previousFrame: CGRect?
        for text in texts {
            let matches = nodes.indices.filter { nodes[$0].accessibilityLabel == text }
            XCTAssertEqual(matches.count, 1, "Each original caption and result must render once: \(text)\n\(description)")
            let index = try XCTUnwrap(matches.first, description)
            let frame = nodes[index].accessibilityFrame
            XCTAssertGreaterThan(frame.height, 0, description)
            if let previousIndex { XCTAssertLessThan(previousIndex, index, "Native reading order must match the originating conversation\n\(description)") }
            if let previousFrame { XCTAssertLessThanOrEqual(previousFrame.maxY, frame.minY + 1,
                "The card and submitted answers must be below their origin and above the later conversation\n\(description)") }
            previousIndex = index
            previousFrame = frame
        }
    }

    private func attach(_ harness: OperationTranscriptHarness, name: String) {
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

@MainActor
private final class OperationTranscriptProbe: ObservableObject {
    @Published var captions: [VoiceTutorCaption]
    @Published var operations: VoiceTutorOperationState
    @Published var userInputs: VoiceTutorUserInputState
    init(captions: [VoiceTutorCaption], operations: VoiceTutorOperationState = .init(),
         userInputs: VoiceTutorUserInputState = .init()) {
        self.captions = captions
        self.operations = operations
        self.userInputs = userInputs
    }
}

private struct OperationTranscriptTestParent: View {
    @ObservedObject var probe: OperationTranscriptProbe
    var body: some View {
        VoiceTutorCallScreen(topic: "스프링", presentation: VoiceTutorCallPresentation(phase: .listening),
            strings: AppStrings(language: .korean), operationState: probe.operations,
            userInputState: probe.userInputs, captions: probe.captions,
            showsTranscript: .constant(true), showsSummary: .constant(false))
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .environment(\.scenePhase, .active)
    }
}

@MainActor
private final class OperationTranscriptHarness {
    let probe: OperationTranscriptProbe
    let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init(captions: [VoiceTutorCaption], operations: VoiceTutorOperationState = .init(),
         userInputs: VoiceTutorUserInputState = .init(), appearance: UIUserInterfaceStyle = .dark) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        probe = OperationTranscriptProbe(captions: captions, operations: operations, userInputs: userInputs)
        window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = UIHostingController(rootView: OperationTranscriptTestParent(probe: probe))
        window.makeKeyAndVisible()
        layout()
    }

    func settle() async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(350))
        layout()
    }

    func layout() {
        window.setNeedsLayout()
        window.layoutIfNeeded()
        window.rootViewController?.view.layoutIfNeeded()
    }

    func scrollTranscript(toBottom: Bool) async throws {
        func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
        let scroll = try XCTUnwrap(descendants(window).compactMap { $0 as? UIScrollView }
            .max { $0.contentSize.height < $1.contentSize.height }, "The actual CallScreen must contain its native transcript scroll view")
        let top = -scroll.adjustedContentInset.top
        let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: toBottom ? bottom : top), animated: false)
        try await settle()
    }

    func semanticTextElements() -> [NSObject] {
        var visited = Set<ObjectIdentifier>()
        var nodes: [NSObject] = []
        func visit(_ object: NSObject) {
            guard visited.insert(ObjectIdentifier(object)).inserted else { return }
            if object.isAccessibilityElement, object.accessibilityLabel?.isEmpty == false { nodes.append(object) }
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
        return nodes
    }

    func close() {
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}
