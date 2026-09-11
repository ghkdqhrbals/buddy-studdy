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
    func testOperationStatusIsVisibleOnlyInTranscript() async throws {
        for expanded in [false, true] {
            var state = VoiceTutorOperationState()
            let caption = VoiceTutorCaption(speaker: .tutor, text: "저장된 채점 결과를 확인했어요.")
            XCTAssertTrue(state.apply(event(1, "visual_operation", .started), at: 10, afterCaptionID: caption.id))
            XCTAssertTrue(state.apply(event(2, "visual_operation", .completed, elapsed: 2_300), at: 12.3))
            let harness = try OperationTranscriptHarness(captions: [caption], operations: state, showsTranscript: expanded)
            defer { harness.close() }
            try await harness.settle()
            #if targetEnvironment(simulator)
            let label = AppStrings(language: .korean).voiceTutorOperationStatus(name: "get_grading_process",
                phase: .completed, elapsedMilliseconds: 2_300)
            XCTAssertEqual(harness.semanticTextElements().filter { $0.accessibilityLabel == label }.count, expanded ? 1 : 0,
                "The same persisted MCP result is discoverable only in the expanded conversation")
            #endif
            XCTAssertEqual(harness.probe.operations.finished.count, 1, "Hiding MCP must not discard its history")
            let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
                XCTAssertTrue(harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: true))
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = expanded ? "voice-operation-transcript" : "voice-operation-call"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTAssertGreaterThan(image.pngData()?.count ?? 0, 1_024)
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
        var previousIndex: Int?
        var previousFrame: CGRect?
        for text in texts {
            let row = try harness.logicalTextRow(text)
            if let previousIndex { XCTAssertLessThan(previousIndex, row.index, "Native reading order must follow the operation's origin") }
            if let previousFrame { XCTAssertLessThanOrEqual(previousFrame.maxY, row.frame.minY + 1,
                "The operation must render below its origin and above later conversation") }
            previousIndex = row.index
            previousFrame = row.frame
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
            try assertNativeOrder([first.text, AppStrings(language: .korean).voiceTutorInputCancelled, second.text], in: harness)
            XCTAssertFalse(harness.semanticTextElements().contains { $0.accessibilityLabel == "Redis" },
                "A blank cancelled form cannot look like a selected answer")
            #endif
            attach(harness, name: "voice-input-transcript-\(style)-cancelled-origin")
            try await harness.scrollTranscript(toBottom: true)
            #if targetEnvironment(simulator)
            try assertNativeOrder([second.text, AppStrings(language: .korean).voiceTutorInputSubmitted,
                "개념 정리 · 실제 예시", answer.text, later.text], in: harness)
            XCTAssertFalse(harness.semanticTextElements().contains {
                $0.accessibilityLabel == AppStrings(language: .korean).voiceTutorInputSubmittedAnswers
            }, "The compact result keeps the chosen answer without auxiliary submitted-answer headings")
            XCTAssertFalse(harness.semanticTextElements().contains { $0.accessibilityLabel == "연습 문제" },
                "Read-only results omit the option that was never submitted")
            #endif
            attach(harness, name: "voice-input-transcript-\(style)-submitted-origin")
        }
    }

    private func assertNativeOrder(_ texts: [String], in harness: OperationTranscriptHarness) throws {
        var previousIndex: Int?
        var previousFrame: CGRect?
        for text in texts {
            let row = try harness.logicalTextRow(text)
            if let previousIndex { XCTAssertLessThan(previousIndex, row.index, "Native reading order must match the originating conversation") }
            if let previousFrame { XCTAssertLessThanOrEqual(previousFrame.maxY, row.frame.minY + 1,
                "The card and submitted answers must be below their origin and above the later conversation") }
            previousIndex = row.index
            previousFrame = row.frame
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

/// Drives only the production view's value inputs. No ViewModel, session owner,
/// account, transport, audio engine, or microphone is created by these fixtures.
@MainActor
final class VoiceTutorCompactInteractionPresentationTests: XCTestCase {
    private let strings = AppStrings(language: .korean)

    func testCompactChoiceSelectionAndTypingSubmitFromTheSameOrbWithoutOpeningChat() async throws {
        try requireHostedAccessibility()
        let harness = try OperationTranscriptHarness(captions: [], showsTranscript: false)
        defer { harness.close() }
        try await harness.settle()
        let request = request()
        XCTAssertTrue(harness.probe.userInputs.apply(request, sessionID: request.sessionId))
        try await harness.settle()
        try assertCompact(harness)
        try harness.assertSingleButtonRow(label: "개념 정리")
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "개념 정리")).accessibilityActivate())
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "실제 예시")).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.userInputs.pending?.answers.first?.selectedOptionIds, ["concept", "example"])

        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.voiceTutorInputCustom)).accessibilityActivate())
        let editor = try await harness.presentedEditor()
        XCTAssertNotNil(harness.window.rootViewController?.presentedViewController)
        XCTAssertFalse(harness.probe.showsTranscript, "Opening the independent editor cannot switch conversation layouts")
        XCTAssertTrue(editor.becomeFirstResponder())
        let draft = "서비스 경계부터 설명해 주세요.\n한글 초안과 선택을 함께 제출합니다."
        editor.insertText(draft)
        try await harness.settle()
        // A tool update while editing cannot replace the compact card, its
        // selected options, or the native editing session.
        XCTAssertTrue(harness.probe.operations.apply(.init(sequence: 1, operationID: "synthetic_lookup",
            name: "list_studies", phase: .completed, elapsedMilliseconds: 120), at: 1))
        try await harness.settle()
        XCTAssertTrue(harness.editor() === editor)
        XCTAssertEqual(editor.text, draft)
        XCTAssertFalse(harness.probe.showsTranscript)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.done)).accessibilityActivate())
        try await harness.waitForEditorDismissal()
        try assertCompact(harness)
        XCTAssertEqual(harness.button(label: strings.voiceTutorInputCustom)?.accessibilityValue, draft)
        XCTAssertTrue(harness.probe.controls.isEmpty, "Done retains the draft; only the explicit submit action sends it")

        let orb = try XCTUnwrap(harness.orbElements.first)
        XCTAssertEqual(orb.accessibilityLabel, strings.voiceTutorInputSubmit)
        XCTAssertTrue(orb.accessibilityActivate(), "The same compact orb must submit the completed choice")
        try await harness.settle()
        XCTAssertEqual(harness.probe.controls.count, 1)
        XCTAssertEqual(harness.probe.controls.first?.answers?.first?.selectedOptionIds, ["concept", "example"])
        XCTAssertEqual(harness.probe.controls.first?.answers?.first?.text, draft)
        XCTAssertEqual(harness.probe.userInputs.pending?.status, .submitting)
        try assertCompact(harness)
        XCTAssertTrue(harness.probe.userInputs.apply(acknowledgment(request, phase: .submitted)))
        try await harness.settle()
        XCTAssertNil(harness.probe.userInputs.pending)
        XCTAssertEqual(harness.probe.userInputs.entries.first?.submittedAnswers, harness.probe.controls.first?.answers)
        try assertCompact(harness)
    }

    func testCompactChoiceCanBeCancelledWithoutSubmittingOrOpeningChat() async throws {
        try requireHostedAccessibility()
        let harness = try OperationTranscriptHarness(captions: [], showsTranscript: false)
        defer { harness.close() }
        try await harness.settle()
        let request = request()
        XCTAssertTrue(harness.probe.userInputs.apply(request, sessionID: request.sessionId))
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.button(label: "개념 정리")).accessibilityActivate())
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.cancel)).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.controls.count, 1)
        XCTAssertNil(harness.probe.controls.first?.answers, "Cancel sends the cancellation control, never an answer")
        try assertCompact(harness)
        XCTAssertTrue(harness.probe.userInputs.apply(acknowledgment(request, phase: .cancelled)))
        try await harness.settle()
        XCTAssertNil(harness.probe.userInputs.pending)
        XCTAssertEqual(harness.probe.userInputs.entries.first?.status, .cancelled)
        XCTAssertEqual(harness.probe.userInputs.entries.first?.answers.first?.selectedOptionIds, ["concept"])
        XCTAssertNil(harness.probe.userInputs.entries.first?.submittedAnswers)
        try assertCompact(harness)
    }

    func testGradingAndGradedStateKeepOneAccessibleCircleInBothLayoutsAndThemes() async throws {
        try requireHostedAccessibility()
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let harness = try OperationTranscriptHarness(captions: [
                VoiceTutorCaption(speaker: .learner, text: "내 답변을 확인해 주세요."),
                VoiceTutorCaption(speaker: .tutor, text: "채점 결과를 확인했어요.")
            ], appearance: appearance, showsTranscript: false)
            defer { harness.close() }
            try await harness.settle()
            for (index, phase) in [VoiceTutorSessionStateEvent.Phase.grading, .graded].enumerated() {
                XCTAssertTrue(harness.probe.presentation.sessionState.apply(.init(sequence: Int64(index + 1),
                    phase: phase, paused: false, revision: 1, studyID: 101, recordID: "202")))
                try await harness.settle()
                try assertCompact(harness)
                let orb = try XCTUnwrap(harness.orbElements.first)
                XCTAssertTrue(orb.accessibilityValue?.contains(harness.probe.presentation.statusText(strings)) == true)
                XCTAssertGreaterThan(orb.accessibilityFrame.width, 100, "A lesson state update must preserve the full compact circle")
            }
            harness.probe.showsTranscript = true
            try await harness.settle()
            XCTAssertEqual(harness.orbElements.count, 1, "Disclosure moves the existing orb instead of mounting a second circle")
            let orb = try XCTUnwrap(harness.orbElements.first)
            XCTAssertGreaterThan(orb.accessibilityFrame.width, 64, "The chat circle reserves room for its grading label")
            XCTAssertLessThanOrEqual(orb.accessibilityFrame.width, 96)
            XCTAssertTrue(orb.accessibilityValue?.contains(strings.voiceTutorAnswerGraded) == true)
            XCTAssertNotNil(harness.button(label: strings.voiceTutorCallCollapseConversation))
            harness.probe.showsTranscript = false
            try await harness.settle()
            try assertCompact(harness)
        }
    }

    func testCompactChoiceAndGradedCircleRenderWithoutStartingARealSession() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_UI_RENDER_SMOKE"] == "1" else {
            throw XCTSkip("Opt in with BUDDYSTUDY_VOICE_UI_RENDER_SMOKE=1 for synthetic native iPhone/simulator screenshots")
        }
        let request = request()
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let theme = appearance == .light ? "light" : "dark"
            let first = VoiceTutorCaption(speaker: .tutor, text: "서비스 경계부터 함께 살펴볼까요?", responseID: "synthetic_origin")
            let learner = VoiceTutorCaption(speaker: .learner, text: "개념과 실제 예시를 함께 공부할게요.")
            let harness = try OperationTranscriptHarness(captions: [first, learner], appearance: appearance,
                showsTranscript: false, useDeviceBounds: true)
            defer { harness.close() }
            try await harness.settle()
            XCTAssertTrue(harness.probe.userInputs.apply(request, sessionID: request.sessionId, afterCaptionID: first.id))
            try await harness.settle()
            XCTAssertFalse(harness.probe.showsTranscript)
            attach(harness, name: "voice-compact-choice-\(theme)")
            harness.probe.userInputs.update(requestID: request.id,
                answer: .init(questionId: "style", selectedOptionIds: ["concept", "example"], text: "서비스 경계 예시도 함께 설명해 주세요."))
            try await harness.settle()
            attach(harness, name: "voice-compact-selected-choice-\(theme)")
            harness.probe.send(requestID: request.id, cancel: false)
            XCTAssertTrue(harness.probe.userInputs.apply(acknowledgment(request, phase: .submitted)))
            XCTAssertTrue(harness.probe.operations.apply(.init(sequence: 1, operationID: "synthetic_grade",
                name: "get_grading_process", phase: .completed, elapsedMilliseconds: 2_300), at: 3, afterCaptionID: learner.id))
            XCTAssertTrue(harness.probe.presentation.sessionState.apply(.init(sequence: 1, phase: .graded,
                paused: false, revision: 1, studyID: 101, recordID: "202")))
            try await harness.settle()
            XCTAssertFalse(harness.probe.showsTranscript)
            attach(harness, name: "voice-graded-circle-compact-\(theme)")
            harness.probe.showsTranscript = true
            await Task.yield()
            try await Task.sleep(for: .milliseconds(80))
            // A time-sampled native animation image, not a claim of exactly 50%
            // spring progress. Orb midpoint geometry is tested separately.
            attach(harness, name: "voice-disclosure-in-flight-\(theme)", afterScreenUpdates: false)
            try await harness.settle()
            try await harness.scrollTranscript(toBottom: true)
            attach(harness, name: "voice-graded-circle-transcript-\(theme)")
            let metadata = XCTAttachment(string: "Synthetic native CallScreen only; no account, network, audio or microphone. Theme=\(theme), bounds=\(harness.window.bounds). Pending/selected/graded states are injected test values. In-flight image captured 80ms after disclosure request; exact midpoint geometry is verified by testVoiceCallUsesOneOrbAlongAContinuousLayoutPath.")
            metadata.name = "voice-ui-render-fixture-metadata-\(theme)"
            metadata.lifetime = .keepAlways
            add(metadata)
        }
    }

    func testPortraitCompactChoiceAndGradedTranscriptRenderWithoutStartingARealSession() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_UI_RENDER_SMOKE"] == "1" else {
            throw XCTSkip("Opt in with BUDDYSTUDY_VOICE_UI_RENDER_SMOKE=1 for the synthetic portrait viewport")
        }
        let request = request()
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let theme = appearance == .light ? "light" : "dark"
            let first = VoiceTutorCaption(speaker: .tutor, text: "서비스 경계부터 함께 살펴볼까요?", responseID: "synthetic_portrait_origin")
            let learner = VoiceTutorCaption(speaker: .learner, text: "개념과 실제 예시를 함께 공부할게요.")
            let harness = try OperationTranscriptHarness(captions: [first, learner], appearance: appearance,
                showsTranscript: false, useDeviceBounds: true, usePortraitViewport: true)
            defer { harness.close() }
            try await harness.settle()
            XCTAssertGreaterThan(harness.window.bounds.height, harness.window.bounds.width)
            XCTAssertTrue(harness.probe.userInputs.apply(request, sessionID: request.sessionId, afterCaptionID: first.id))
            harness.probe.userInputs.update(requestID: request.id,
                answer: .init(questionId: "style", selectedOptionIds: ["concept", "example"], text: "서비스 경계 예시도 함께 설명해 주세요."))
            try await harness.settle()
            XCTAssertFalse(harness.probe.showsTranscript)
            attach(harness, name: "voice-portrait-compact-selected-choice-\(theme)")
            harness.probe.send(requestID: request.id, cancel: false)
            XCTAssertTrue(harness.probe.userInputs.apply(acknowledgment(request, phase: .submitted)))
            XCTAssertTrue(harness.probe.operations.apply(.init(sequence: 1, operationID: "synthetic_portrait_grade",
                name: "get_grading_process", phase: .completed, elapsedMilliseconds: 2_300), at: 3, afterCaptionID: learner.id))
            XCTAssertTrue(harness.probe.presentation.sessionState.apply(.init(sequence: 1, phase: .graded,
                paused: false, revision: 1, studyID: 101, recordID: "202")))
            harness.probe.showsTranscript = true
            try await harness.settle()
            try await harness.scrollTranscript(toBottom: true)
            XCTAssertGreaterThan(harness.window.bounds.height, harness.window.bounds.width)
            attach(harness, name: "voice-portrait-graded-transcript-\(theme)")
            let metadata = XCTAttachment(string: "Synthetic native CallScreen portrait viewport; not touch or orientation E2E. No account, network, audio or microphone. Theme=\(theme), windowBounds=\(harness.window.bounds), nativeScreenBounds=\(String(describing: harness.window.windowScene?.screen.bounds)), nativeSceneOrientation=\(String(describing: harness.window.windowScene?.interfaceOrientation.rawValue)). The viewport uses the device's shorter dimension as width and longer dimension as height. No device/scene orientation change is requested; closing restores the previous key window.")
            metadata.name = "voice-portrait-render-fixture-metadata-\(theme)"
            metadata.lifetime = .keepAlways
            add(metadata)
        }
    }

    private func request() -> VoiceTutorUserInputRequest {
        .init(requestId: "55555555-5555-4555-a555-555555555555",
            sessionId: "11111111-1111-4111-a111-111111111111",
            attemptId: "22222222-2222-4222-a222-222222222222", sequence: 1, title: "학습 방식",
            questions: [.init(id: "style", prompt: "필요한 설명을 골라 주세요.", selectionMode: .multiple,
                options: [.init(id: "concept", label: "개념 정리"), .init(id: "example", label: "실제 예시")],
                allowFreeText: true)], operationId: "synthetic_form")
    }

    private func acknowledgment(_ request: VoiceTutorUserInputRequest,
                                phase: VoiceTutorUserInputStateEvent.Phase) -> VoiceTutorUserInputStateEvent {
        .init(requestId: request.id, sessionId: request.sessionId, attemptId: request.attemptId,
            sequence: 2, phase: phase, errorCode: nil)
    }

    private func requireHostedAccessibility() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Native SwiftUI AX actions run on simulator; opt-in synthetic render coverage runs on iPhone")
        #endif
    }

    private func assertCompact(_ harness: OperationTranscriptHarness, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertFalse(harness.probe.showsTranscript, "Only the learner opens the conversation", file: file, line: line)
        XCTAssertNil(harness.button(label: strings.voiceTutorCallCollapseConversation), file: file, line: line)
        XCTAssertEqual(harness.orbElements.count, 1, "Keep one accessible circle across updates", file: file, line: line)
    }

    private func attach(_ harness: OperationTranscriptHarness, name: String, afterScreenUpdates: Bool = true) {
        let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
            XCTAssertTrue(harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: afterScreenUpdates))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 1_024)
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

@MainActor
final class VoiceTutorLessonContentPresentationTests: XCTestCase {
    private let strings = AppStrings(language: .korean)
    private let question = "주문 서비스와 결제 서비스를 분리한 환경에서 결제는 성공했지만 주문 저장에 실패했습니다. Saga 패턴으로 이 상황을 복구하는 절차를 설명하고, 보상 작업을 재시도할 때 같은 요청이 두 번 처리되지 않도록 만드는 방법도 함께 설명해 주세요."
    private let answer = "결제가 완료된 상태를 확인하고 보상 작업으로 결제를 취소합니다.\n멱등 키를 저장해서 동일한 보상 요청을 중복 처리하지 않습니다."
    private let grade = GradingResult(score: 82, isCorrect: true,
        feedback: "보상 작업과 멱등성의 필요성을 잘 설명했어요. 재시도 한도와 실패 상태를 저장하는 방법도 덧붙이면 더 정확해요.",
        explanation: "각 서비스가 로컬 트랜잭션의 결과를 저장하고 다음 단계를 이벤트로 전달합니다. 주문 저장이 실패하면 결제 취소를 요청하고, 같은 요청이 반복되어도 멱등 키로 이미 처리한 보상인지 확인합니다.")

    func testCanonicalQuestionAndEditedAnswerStayOnOrbSurfaceThroughExplicitSubmissionAndExactGrade() async throws {
        try requireHostedAccessibility()
        let harness = try OperationTranscriptHarness(captions: [], appearance: .light, showsTranscript: false)
        defer { harness.close() }
        try await harness.settle()
        XCTAssertNil(harness.button(label: strings.voiceTutorAnswerFinish))
        XCTAssertTrue(harness.probe.receiveQuestion(question))
        XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.answering, sequence: 1)))
        try await harness.settle()
        XCTAssertFalse(harness.probe.showsTranscript)
        let questionRow = try harness.logicalTextRow(question)
        let waitingOrb = try XCTUnwrap(harness.orbElements.first)
        XCTAssertGreaterThan(questionRow.frame.height, 60, "A long saved question must remain multiline")
        XCTAssertLessThanOrEqual(questionRow.frame.maxY, waitingOrb.accessibilityFrame.minY + 1,
            "The full canonical question stays above the answer action")
        XCTAssertTrue(waitingOrb.accessibilityValue?.contains(strings.voiceTutorAnswerWaiting) == true)
        XCTAssertTrue(harness.probe.answerControls.isEmpty)

        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.voiceTutorAnswerEdit)).accessibilityActivate())
        let editor = try await harness.presentedEditor()
        XCTAssertTrue(editor.becomeFirstResponder())
        editor.insertText(answer)
        editor.selectedRange = NSRange(location: 8, length: 0)
        try await harness.settle()
        let selection = editor.selectedRange
        XCTAssertTrue(harness.probe.operations.apply(.init(sequence: 1, operationID: "synthetic_editor_update",
            name: "list_studies", phase: .completed, elapsedMilliseconds: 120), at: 1))
        try await harness.settle()
        XCTAssertTrue(harness.editor() === editor, "A view update must preserve the native editor instance")
        XCTAssertEqual(editor.text, answer)
        XCTAssertEqual(editor.selectedRange, selection)
        XCTAssertFalse(harness.probe.showsTranscript)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.done)).accessibilityActivate())
        try await harness.waitForEditorDismissal()
        XCTAssertEqual(harness.button(label: strings.voiceTutorAnswerEdit)?.accessibilityValue, answer)
        XCTAssertEqual(harness.probe.answerDraft.questionText, question)
        XCTAssertTrue(harness.probe.answerControls.isEmpty, "Closing the editor must not submit an answer")

        XCTAssertTrue(try XCTUnwrap(harness.orbElements.first).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.answerControls.map(\.kind), [.finish])
        XCTAssertTrue(harness.probe.answerDraft.apply(answerEvent(.review)))
        XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.answerReview, sequence: 2)))
        try await harness.settle()
        XCTAssertTrue(try XCTUnwrap(harness.orbElements.first).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.answerControls.map(\.kind), [.finish, .submit])
        XCTAssertEqual(harness.probe.answerControls.last?.text, answer)
        XCTAssertTrue(harness.probe.answerDraft.apply(answerEvent(.submitted)))
        XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.graded, sequence: 3)))
        try makeReadyGrade(in: harness)
        try await harness.settle()
        XCTAssertFalse(harness.probe.showsTranscript)
        try assertFullGrade(in: harness)
        XCTAssertEqual(harness.probe.answerDraft.text, answer, "Displaying a grade cannot erase the submitted answer")
    }

    func testMissingFailedAndMismatchedGradeNeverDisplayAStaleScoreAndRetryRemainsExplicit() async throws {
        try requireHostedAccessibility()
        let harness = try OperationTranscriptHarness(captions: [], showsTranscript: false)
        defer { harness.close() }
        XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.graded, sequence: 1)))
        let request = try XCTUnwrap(harness.probe.gradingResult.reconcile(snapshot: harness.probe.presentation.sessionState.snapshot,
            sessionID: "synthetic-ui-session", attemptID: UUID(), ownerUserID: 7))
        try await harness.settle()
        try assertNoGrade(in: harness)
        _ = try harness.logicalTextRow(strings.voiceTutorGradingResultLoading)
        XCTAssertNil(harness.button(label: strings.retry))
        XCTAssertTrue(harness.probe.gradingResult.fail(for: request))
        try await harness.settle()
        _ = try harness.logicalTextRow(strings.voiceTutorGradingResultFailed)
        XCTAssertTrue(try XCTUnwrap(harness.button(label: strings.retry)).accessibilityActivate())
        try await harness.settle()
        XCTAssertEqual(harness.probe.gradingRetryCount, 1)
        XCTAssertEqual(harness.probe.gradingResult.phase, .loading)
        try assertNoGrade(in: harness)
        let retry = try XCTUnwrap(harness.probe.gradingRetryRequest)
        XCTAssertTrue(harness.probe.gradingResult.resolve(savedRecord(), for: retry))
        try await harness.settle()
        try assertFullGrade(in: harness)

        XCTAssertTrue(harness.probe.presentation.sessionState.apply(.init(sequence: 2, phase: .graded,
            paused: false, revision: 2, studyID: 101, recordID: "303")))
        try await harness.settle()
        XCTAssertEqual(harness.probe.gradingResult.result?.score, grade.score,
            "Keep the old fixture value to prove the view rejects its mismatched identity")
        try assertNoGrade(in: harness)
        _ = try harness.logicalTextRow(strings.voiceTutorGradingResultLoading)
        XCTAssertFalse(harness.probe.showsTranscript)
    }

    func testSavedGradeRemainsVisibleWhileAChoiceOwnsTheOrb() async throws {
        try requireHostedAccessibility()
        let harness = try OperationTranscriptHarness(captions: [], showsTranscript: false)
        defer { harness.close() }
        XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.graded, sequence: 1)))
        try makeReadyGrade(in: harness)
        let request = VoiceTutorUserInputRequest(requestId: "55555555-5555-4555-a555-555555555555",
            sessionId: "11111111-1111-4111-a111-111111111111",
            attemptId: "22222222-2222-4222-a222-222222222222", sequence: 1, title: "다음 학습",
            questions: [.init(id: "next", prompt: "다음 하위 주제를 골라 주세요.", selectionMode: .single,
                options: [.init(id: "retry", label: "재시도와 멱등성")], allowFreeText: true)])
        XCTAssertTrue(harness.probe.userInputs.apply(request, sessionID: request.sessionId))
        try await harness.settle()
        XCTAssertFalse(harness.probe.showsTranscript)
        XCTAssertEqual(try XCTUnwrap(harness.orbElements.first).accessibilityLabel, strings.voiceTutorInputWaitingShort)
        let score = try harness.logicalTextRow(strings.voiceTutorLessonScore(grade.score))
        let reason = try harness.logicalTextRow(grade.feedback)
        _ = try harness.logicalTextRow(grade.explanation)
        XCTAssertLessThan(score.index, reason.index, "The saved score moves into the result panel while the choice owns the orb")
        XCTAssertLessThanOrEqual(score.frame.maxY, reason.frame.minY + 1)
    }

    func testQuestionAnswerAndSavedGradeRenderInPortraitWithoutStartingARealSession() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_UI_RENDER_SMOKE"] == "1" else {
            throw XCTSkip("Opt in with BUDDYSTUDY_VOICE_UI_RENDER_SMOKE=1 for synthetic lesson screenshots")
        }
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let theme = appearance == .light ? "light" : "dark"
            let harness = try OperationTranscriptHarness(captions: [], appearance: appearance,
                showsTranscript: false, useDeviceBounds: true, usePortraitViewport: true)
            defer { harness.close() }
            XCTAssertTrue(harness.probe.receiveQuestion(question))
            XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.answering, sequence: 1)))
            try await harness.settle()
            XCTAssertFalse(harness.probe.showsTranscript)
            attach(harness, name: "voice-lesson-question-waiting-portrait-\(theme)")
            XCTAssertTrue(harness.probe.answerDraft.edit(answer))
            try await harness.settle()
            attach(harness, name: "voice-lesson-question-answer-portrait-\(theme)")
            harness.probe.answerDraft = .init()
            XCTAssertTrue(harness.probe.presentation.sessionState.apply(snapshot(.graded, sequence: 2)))
            try makeReadyGrade(in: harness)
            try await harness.settle()
            XCTAssertFalse(harness.probe.showsTranscript)
            XCTAssertEqual(harness.probe.gradingResult.result, grade)
            attach(harness, name: "voice-lesson-score-reason-explanation-portrait-\(theme)")
        }
        let large = try OperationTranscriptHarness(captions: [], appearance: .dark, showsTranscript: false,
            useDeviceBounds: true, usePortraitViewport: true, dynamicType: .accessibility3)
        defer { large.close() }
        XCTAssertTrue(large.probe.receiveQuestion("Redis의 TTL을 설명하고 캐시 만료 전략의 예시를 두 가지 들어 주세요.", existingDraft: answer))
        XCTAssertTrue(large.probe.presentation.sessionState.apply(snapshot(.answering, sequence: 1)))
        try await large.settle()
        attach(large, name: "voice-lesson-question-answer-portrait-accessibility3")
        large.probe.answerDraft = .init()
        XCTAssertTrue(large.probe.presentation.sessionState.apply(snapshot(.graded, sequence: 2)))
        try makeReadyGrade(in: large)
        try await large.settle()
        attach(large, name: "voice-lesson-score-reason-explanation-portrait-accessibility3")
        let metadata = XCTAttachment(string: "Eight synthetic native portrait renders: question waiting, drafted answer and exact saved grade in light/dark, plus AX3 question and grade. The fixture does not start a voice session, capture audio, grade an answer or change account settings. The normal test-host app may initialize its services. Explicit min/max screen viewport; actual scene orientation is unchanged. This is rendering evidence, not real-touch or audio E2E. Fixture score/reason/explanation are injected via exact-target grading state.")
        metadata.name = "voice-lesson-content-render-metadata"
        metadata.lifetime = .keepAlways
        add(metadata)
    }

    private func snapshot(_ phase: VoiceTutorSessionStateEvent.Phase, sequence: Int64) -> VoiceTutorSessionStateEvent {
        .init(sequence: sequence, phase: phase, paused: false, revision: 1,
            studyID: 101, recordID: "202", answerID: OperationTranscriptProbe.answerID)
    }

    private func answerEvent(_ phase: VoiceTutorAnswerDraftState.Phase) -> VoiceTutorAnswerStateEvent {
        .init(answerID: OperationTranscriptProbe.answerID, studyID: 101, recordID: "202", revision: 1,
            phase: phase, text: nil, code: nil)
    }

    private func savedRecord() -> StudyRecord {
        .init(id: "202", studyID: 101,
            question: QuestionItem(question: question, expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 0)),
            answer: answer, gradingResult: grade, topic: "스프링", difficulty: Difficulty(level: 8),
            gradingStatus: .completed, questionStatus: .graded)
    }

    private func makeReadyGrade(in harness: OperationTranscriptHarness) throws {
        let request = try XCTUnwrap(harness.probe.gradingResult.reconcile(snapshot: harness.probe.presentation.sessionState.snapshot,
            sessionID: "synthetic-ui-session", attemptID: UUID(), ownerUserID: 7))
        XCTAssertTrue(harness.probe.gradingResult.resolve(savedRecord(), for: request))
    }

    private func assertFullGrade(in harness: OperationTranscriptHarness) throws {
        XCTAssertEqual(harness.orbElements.count, 1)
        let orb = try XCTUnwrap(harness.orbElements.first)
        XCTAssertTrue(orb.accessibilityValue?.contains(strings.voiceTutorLessonScore(grade.score)) == true)
        let reason = try harness.logicalTextRow(grade.feedback)
        let explanation = try harness.logicalTextRow(grade.explanation)
        XCTAssertGreaterThanOrEqual(reason.frame.minY, orb.accessibilityFrame.maxY - 1)
        XCTAssertLessThan(reason.index, explanation.index)
        XCTAssertLessThanOrEqual(reason.frame.maxY, explanation.frame.minY + 1)
        _ = try harness.logicalTextRow(strings.voiceTutorGradingFeedbackTitle)
        _ = try harness.logicalTextRow(strings.explanation)
    }

    private func assertNoGrade(in harness: OperationTranscriptHarness) throws {
        let nodes = harness.semanticTextElements()
        XCTAssertFalse(nodes.contains { $0.accessibilityLabel == grade.feedback || $0.accessibilityLabel == grade.explanation })
        XCTAssertFalse(nodes.contains { $0.accessibilityLabel == strings.voiceTutorLessonScore(grade.score) })
        XCTAssertFalse(harness.orbElements.contains { $0.accessibilityValue?.contains(strings.voiceTutorLessonScore(grade.score)) == true })
    }

    private func requireHostedAccessibility() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Hosted SwiftUI AX actions run on simulator; synthetic portrait lesson renders run on iPhone")
        #endif
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
final class VoiceTutorProviderFailurePresentationTests: XCTestCase {
    func testProviderCreditsFailureShowsServiceExplanationAndDismissWithoutReconnect() async throws {
        let capture = ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_UI_RENDER_SMOKE"] == "1"
        #if !targetEnvironment(simulator)
        guard capture else { throw XCTSkip("Opt in to native provider-error rendering on iPhone") }
        #endif
        let strings = AppStrings(language: .korean)
        let error = VoiceTutorWebRTCError.sdpExchangeFailed(statusCode: 503,
            backendFailure: .init(code: "VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED", message: "private provider detail"))
        let failure = VoiceTutorStartupFailurePolicy.presentation(for: error, strings: strings)
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let harness = try OperationTranscriptHarness(captions: [], appearance: appearance,
                showsTranscript: false, useDeviceBounds: true, usePortraitViewport: true)
            defer { harness.close() }
            harness.probe.errorMessage = failure.message
            harness.probe.presentation = VoiceTutorCallPresentation(phase: .failed, failureCause: failure.cause)
            try await harness.settle()
            #if targetEnvironment(simulator)
            XCTAssertTrue(try XCTUnwrap(harness.orbElements.first).accessibilityValue?
                .contains(strings.voiceTutorProviderQuotaUnavailableTitle) == true)
            _ = try harness.logicalTextRow(strings.voiceTutorProviderQuotaUnavailableMessage)
            XCTAssertNotNil(harness.button(label: strings.done))
            XCTAssertNil(harness.button(label: strings.voiceTutorCallReconnect))
            XCTAssertFalse(harness.semanticTextElements().contains { $0.accessibilityLabel?.contains("private provider detail") == true })
            #endif
            if capture {
                harness.layout()
                let image = UIGraphicsImageRenderer(bounds: harness.window.bounds).image { _ in
                    XCTAssertTrue(harness.window.drawHierarchy(in: harness.window.bounds, afterScreenUpdates: true))
                }
                let attachment = XCTAttachment(image: image)
                attachment.name = "voice-provider-credits-failure-\(appearance == .light ? "light" : "dark")"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }
}

@MainActor
private final class OperationTranscriptProbe: ObservableObject {
    static let answerID = "66666666-6666-4666-a666-666666666666"
    @Published var captions: [VoiceTutorCaption]
    @Published var operations: VoiceTutorOperationState
    @Published var userInputs: VoiceTutorUserInputState
    @Published var showsTranscript: Bool
    @Published var presentation = VoiceTutorCallPresentation(phase: .listening)
    @Published var errorMessage: String?
    @Published var answerDraft = VoiceTutorAnswerDraftState()
    @Published var gradingResult = VoiceTutorGradingResultState()
    private(set) var controls: [VoiceTutorUserInputControl] = []
    private(set) var answerControls: [VoiceTutorAnswerControl] = []
    private(set) var gradingRetryCount = 0
    private(set) var gradingRetryRequest: VoiceTutorGradingResultState.Request?
    let dynamicType: DynamicTypeSize
    init(captions: [VoiceTutorCaption], operations: VoiceTutorOperationState = .init(),
         userInputs: VoiceTutorUserInputState = .init(), showsTranscript: Bool = true,
         dynamicType: DynamicTypeSize = .large) {
        self.captions = captions
        self.operations = operations
        self.userInputs = userInputs
        self.showsTranscript = showsTranscript
        self.dynamicType = dynamicType
    }

    func send(requestID: String, cancel: Bool) {
        if let control = userInputs.submit(requestID: requestID, cancel: cancel) { controls.append(control) }
    }

    func receiveQuestion(_ question: String, existingDraft: String = "") -> Bool {
        answerDraft.apply(.init(answerID: Self.answerID, studyID: 101, recordID: "202", revision: 1,
            phase: .listening, text: nil, code: nil, question: question), existingDraft: existingDraft)
    }

    func finishAnswer() {
        if let control = answerDraft.requestFinish() { answerControls.append(control) }
    }

    func submitAnswer() {
        if let control = answerDraft.requestSubmit() { answerControls.append(control) }
    }

    func retryGrading() {
        gradingRetryCount += 1
        gradingRetryRequest = gradingResult.retry()
    }
}

private struct OperationTranscriptTestParent: View {
    @ObservedObject var probe: OperationTranscriptProbe

    private var presentation: VoiceTutorCallPresentation {
        var value = probe.presentation
        value.hasCanonicalAnswerQuestion = probe.answerDraft.belongsToCurrentLesson(value.sessionState.snapshot)
        return value
    }

    var body: some View {
        VoiceTutorCallScreen(topic: "스프링", presentation: presentation,
            strings: AppStrings(language: .korean), operationState: probe.operations,
            userInputState: probe.userInputs,
            onUserInputChange: { probe.userInputs.update(requestID: $0, answer: $1) },
            onUserInputSubmit: { probe.send(requestID: $0, cancel: $1) },
            captions: probe.captions, errorMessage: probe.errorMessage,
            showsTranscript: $probe.showsTranscript, showsSummary: .constant(false),
            answerDraftState: probe.answerDraft,
            answerDraftText: Binding(get: { probe.answerDraft.text }, set: { _ = probe.answerDraft.edit($0) }),
            canSubmitAnswer: probe.answerDraft.canSubmit,
            onFinishAnswer: { probe.finishAnswer() }, onSubmitAnswer: { probe.submitAnswer() },
            gradingResultState: probe.gradingResult, onGradingResultRetry: { probe.retryGrading() })
            .dynamicTypeSize(probe.dynamicType)
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .environment(\.scenePhase, .active)
    }
}

@MainActor
private final class OperationTranscriptHarness {
    let probe: OperationTranscriptProbe
    let window: UIWindow
    private let previousKeyWindow: UIWindow?
    private let portraitViewport: CGRect?

    init(captions: [VoiceTutorCaption], operations: VoiceTutorOperationState = .init(),
         userInputs: VoiceTutorUserInputState = .init(), appearance: UIUserInterfaceStyle = .dark,
         showsTranscript: Bool = true, useDeviceBounds: Bool = false, usePortraitViewport: Bool = false,
         dynamicType: DynamicTypeSize = .large) throws {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let deviceBounds = scene.screen.bounds
        // This is a native rendering canvas, not a request to rotate the phone.
        // Keep a portrait viewport even if the hosted test's scene is landscape.
        portraitViewport = usePortraitViewport ? CGRect(x: 0, y: 0,
            width: min(deviceBounds.width, deviceBounds.height),
            height: max(deviceBounds.width, deviceBounds.height)) : nil
        probe = OperationTranscriptProbe(captions: captions, operations: operations,
            userInputs: userInputs, showsTranscript: showsTranscript, dynamicType: dynamicType)
        window = UIWindow(windowScene: scene)
        window.frame = portraitViewport ?? (useDeviceBounds ? deviceBounds : CGRect(x: 0, y: 0, width: 393, height: 852))
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
        if let portraitViewport {
            window.frame = portraitViewport
            window.rootViewController?.view.frame = window.bounds
        }
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

    func button(label: String) -> NSObject? {
        semanticTextElements().first { $0.accessibilityLabel == label && $0.accessibilityTraits.contains(.button) }
    }

    func logicalTextRow(_ text: String) throws -> (index: Int, frame: CGRect) {
        let nodes = semanticTextElements()
        let description = nodes.map { "\($0.accessibilityLabel ?? "nil") frame=\($0.accessibilityFrame)" }.joined(separator: "\n")
        let strings = AppStrings(language: .korean)
        let combinedLabel = probe.captions.first(where: { $0.text == text }).map {
            ($0.speaker == .learner ? strings.voiceTutorYou : strings.voiceTutorTeacher) + ". " + text
        }
        let leaves = nodes.indices.filter { nodes[$0].accessibilityLabel == text }
        let parents = combinedLabel.map { label in nodes.indices.filter { nodes[$0].accessibilityLabel == label } } ?? []
        XCTAssertLessThanOrEqual(leaves.count, 1, "A second text leaf is a duplicate row: \(text)\n\(description)")
        XCTAssertLessThanOrEqual(parents.count, 1, "A second combined caption is a duplicate row: \(text)\n\(description)")
        let index = try XCTUnwrap(parents.first ?? leaves.first, "Missing exact message/status: \(text)\n\(description)")
        let frame = nodes[index].accessibilityFrame
        XCTAssertGreaterThan(frame.height, 0, description)
        // SwiftUI may expose the combined caption alone, or also its selectable
        // text child. Collapse only that exact speaker label and contained leaf.
        if let parent = parents.first, let leaf = leaves.first {
            XCTAssertLessThan(parent, leaf, "A containing caption must precede its own leaf\n\(description)")
            XCTAssertTrue(nodes[parent].accessibilityFrame.insetBy(dx: -1, dy: -1).contains(nodes[leaf].accessibilityFrame),
                "A separate rendering cannot be normalized as a caption child\n\(description)")
        }
        return (index, frame)
    }

    func assertSingleButtonRow(label: String) throws {
        let nodes = semanticTextElements()
        let matches = nodes.indices.filter { nodes[$0].accessibilityLabel == label }
        let buttons = matches.filter { nodes[$0].accessibilityTraits.contains(.button) }
        let leaves = matches.filter { !nodes[$0].accessibilityTraits.contains(.button) }
        let description = matches.map {
            "\(type(of: nodes[$0])) button=\(nodes[$0].accessibilityTraits.contains(.button)) frame=\(nodes[$0].accessibilityFrame)"
        }.joined(separator: "\n")
        XCTAssertEqual(buttons.count, 1, "The hidden transcript cannot expose another option button\n\(description)")
        XCTAssertLessThanOrEqual(leaves.count, 1, "Only the option's own text leaf may repeat its label\n\(description)")
        let button = try XCTUnwrap(buttons.first, description)
        XCTAssertGreaterThan(nodes[button].accessibilityFrame.height, 0, description)
        if let leaf = leaves.first {
            XCTAssertLessThan(button, leaf, description)
            XCTAssertTrue(nodes[button].accessibilityFrame.insetBy(dx: -1, dy: -1).contains(nodes[leaf].accessibilityFrame),
                "A separately positioned copy is a second form, not the button's text\n\(description)")
        }
    }

    var orbElements: [NSObject] {
        semanticTextElements().filter {
            $0.accessibilityTraits.contains(.button) && $0.accessibilityValue?.hasPrefix("스프링, ") == true
        }
    }

    func editor() -> UITextView? {
        func visit(_ view: UIView) -> UITextView? {
            if let text = view as? UITextView { return text }
            return view.subviews.lazy.compactMap(visit).first
        }
        return visit(window)
    }

    func presentedEditor() async throws -> UITextView {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while ProcessInfo.processInfo.systemUptime < deadline {
            try await settle()
            if let editor = editor(), editor.window != nil, editor.bounds.height > 0 { return editor }
        }
        return try XCTUnwrap(editor(), "The compact choice must present its production editor sheet")
    }

    func waitForEditorDismissal() async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while window.rootViewController?.presentedViewController != nil,
              ProcessInfo.processInfo.systemUptime < deadline { try await settle() }
        XCTAssertNil(window.rootViewController?.presentedViewController)
        XCTAssertNil(editor())
    }

    func close() {
        window.endEditing(true)
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}
