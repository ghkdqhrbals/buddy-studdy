import Foundation
import Combine
import XCTest
@testable import StudyMate

final class VoiceTutorGradingResultTests: XCTestCase {
    private let attemptID = UUID()

    func testSavedResultFailuresExplainExactReadProblemAndKeepReloadScopedToTheSameResult() throws {
        let failures: [VoiceTutorGradingResultState.Failure] = [.unavailable, .mismatchedRecord, .incompleteResult, .timedOut]
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            var messages = Set<String>()
            for failure in failures {
                var state = VoiceTutorGradingResultState()
                let first = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
                    attemptID: attemptID, ownerUserID: 7))
                XCTAssertTrue(state.fail(for: first, reason: failure))
                let failedState = state
                let explanation = strings.voiceTutorGradingResultFailureHelp(state.failure)
                XCTAssertFalse(explanation.isEmpty)
                messages.insert(explanation)
                XCTAssertEqual(state, failedState, "Showing the cause cannot implicitly retry")
                let reload = try XCTUnwrap(state.retry())
                XCTAssertEqual(reload.target, first.target, "Reload only the existing authenticated result")
                XCTAssertNotEqual(reload.id, first.id)
            }
            XCTAssertEqual(messages.count, failures.count, "Each known result-read failure needs its own explanation")
            XCTAssertFalse(strings.voiceTutorGradingResultReloadTitle.isEmpty)
            XCTAssertNotEqual(strings.voiceTutorGradingResultReloadTitle, strings.voiceTutorAnswerSubmit)
            XCTAssertEqual(strings.voiceTutorGradingResultFailureHelp(nil),
                strings.voiceTutorGradingResultFailureHelp(.unavailable))
        }
    }

    func testLessonRecoveryInstructionsOnlyAppearForQuestionAndGradingFailure() throws {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let questionHelp = try XCTUnwrap(strings.voiceTutorLessonFailureHelp(.questionFailed))
            let gradingHelp = try XCTUnwrap(strings.voiceTutorLessonFailureHelp(.gradingFailed))
            XCTAssertNotEqual(questionHelp, strings.voiceTutorQuestionFailed)
            XCTAssertNotEqual(gradingHelp, strings.voiceTutorGradingFailed)
            XCTAssertNotEqual(questionHelp, gradingHelp)
            XCTAssertNil(strings.voiceTutorLessonFailureHelp(nil))
            for phase in VoiceTutorSessionStateEvent.Phase.allCases where phase != .questionFailed && phase != .gradingFailed {
                XCTAssertNil(strings.voiceTutorLessonFailureHelp(phase))
            }
        }
    }

    func testExactSavedGradeIncludesScoreReasonAndExplanation() throws {
        var state = VoiceTutorGradingResultState()
        let snapshot = Self.snapshot()
        let request = try XCTUnwrap(state.reconcile(snapshot: snapshot, sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertEqual(state.phase, .loading)
        XCTAssertNil(state.result)
        XCTAssertTrue(state.resolve(Self.record(), for: request))
        XCTAssertEqual(state.phase, .ready)
        XCTAssertEqual(state.result, Self.record().gradingResult)
        XCTAssertEqual(state.result?.score, 82)
        XCTAssertEqual(state.result?.feedback, "트랜잭션 경계를 정확하게 설명했어요.")
        XCTAssertEqual(state.result?.explanation, "보상 트랜잭션으로 실패를 복구할 수 있어요.")
        XCTAssertTrue(state.matches(snapshot))
        XCTAssertTrue(state.matches(Self.snapshot(sequence: 2, paused: true)))
    }

    func testOnlyValidAuthenticatedGradedScopeStartsLoading() {
        for phase in VoiceTutorSessionStateEvent.Phase.allCases where phase != .graded {
            var state = VoiceTutorGradingResultState()
            XCTAssertNil(state.reconcile(snapshot: Self.snapshot(phase: phase), sessionID: "call-1",
                attemptID: attemptID, ownerUserID: 7), phase.rawValue)
            XCTAssertEqual(state.phase, .idle)
        }
        var state = VoiceTutorGradingResultState()
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(), sessionID: "", attemptID: attemptID, ownerUserID: 7))
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1", attemptID: attemptID, ownerUserID: 0))
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1", attemptID: attemptID,
            ownerUserID: 7, minimumRevision: 4))
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(recordID: "0101"), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertNil(state.retry())
    }

    func testDuplicateAndPauseSnapshotsDoNotPollOrReplaceReadyResult() throws {
        var state = VoiceTutorGradingResultState()
        let request = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(sequence: 2), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertNil(state.retry(), "An in-flight request cannot be duplicated")
        XCTAssertTrue(state.resolve(Self.record(), for: request))
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(sequence: 3, paused: true), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertEqual(state.phase, .ready)
        XCTAssertEqual(state.result?.score, 82)
        XCTAssertNil(state.retry())
    }

    func testTimeoutNeedsExplicitRetryAndCannotAcceptLatePreviousAttempt() throws {
        var state = VoiceTutorGradingResultState()
        let oldRequest = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertTrue(state.fail(for: oldRequest, reason: .timedOut))
        XCTAssertEqual(state.failure, .timedOut)
        XCTAssertNil(state.reconcile(snapshot: Self.snapshot(sequence: 2), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7), "Another graded receipt must not auto-retry")
        let retry = try XCTUnwrap(state.retry())
        XCTAssertNotEqual(retry.id, oldRequest.id)
        XCTAssertEqual(retry.target, oldRequest.target)
        XCTAssertFalse(state.resolve(Self.record(score: 25), for: oldRequest))
        XCTAssertFalse(state.fail(for: oldRequest))
        XCTAssertEqual(state.phase, .loading)
        XCTAssertTrue(state.resolve(Self.record(), for: retry))
        XCTAssertEqual(state.result?.score, 82)
    }

    func testUnrelatedIncompleteAndInvalidRecordsNeverBecomeVisibleResults() throws {
        var wrongID = Self.record(); wrongID.id = "102"
        var wrongStudy = Self.record(); wrongStudy.studyID = 43
        var voiceRecord = Self.record(); voiceRecord.recordType = .voiceTutor
        var ungraded = Self.record(); ungraded.questionStatus = .ungraded
        var running = Self.record(); running.gradingStatus = .judging
        var noResult = Self.record(); noResult.gradingResult = nil
        var noReason = Self.record(); noReason.gradingResult?.feedback = " \n "
        var noExplanation = Self.record(); noExplanation.gradingResult?.explanation = "\t"
        let fixtures: [(StudyRecord?, VoiceTutorGradingResultState.Failure)] = [
            (nil, .unavailable), (wrongID, .mismatchedRecord), (wrongStudy, .mismatchedRecord),
            (voiceRecord, .mismatchedRecord), (ungraded, .incompleteResult), (running, .incompleteResult),
            (noResult, .incompleteResult), (Self.record(score: -1), .incompleteResult),
            (Self.record(score: 101), .incompleteResult), (noReason, .incompleteResult),
            (noExplanation, .incompleteResult)
        ]
        for (record, failure) in fixtures {
            var state = VoiceTutorGradingResultState()
            let request = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
                attemptID: attemptID, ownerUserID: 7))
            XCTAssertTrue(state.resolve(record, for: request))
            XCTAssertEqual(state.phase, .failed)
            XCTAssertEqual(state.failure, failure)
            XCTAssertNil(state.result)
        }
    }

    func testOwnerCallAttemptRevisionStudyAndRecordEachInvalidateOlderFetch() throws {
        let cases: [(VoiceTutorSessionStateEvent, String, UUID, Int64)] = [
            (Self.snapshot(), "call-1", attemptID, 8),
            (Self.snapshot(), "call-2", attemptID, 7),
            (Self.snapshot(), "call-1", UUID(), 7),
            (Self.snapshot(revision: 4), "call-1", attemptID, 7),
            (Self.snapshot(studyID: 43), "call-1", attemptID, 7),
            (Self.snapshot(recordID: "102"), "call-1", attemptID, 7)
        ]
        for (snapshot, sessionID, attemptID, ownerID) in cases {
            var state = VoiceTutorGradingResultState()
            let old = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
                attemptID: self.attemptID, ownerUserID: 7))
            let current = try XCTUnwrap(state.reconcile(snapshot: snapshot, sessionID: sessionID,
                attemptID: attemptID, ownerUserID: ownerID))
            XCTAssertNotEqual(old.target, current.target)
            XCTAssertFalse(state.resolve(Self.record(), for: old))
            XCTAssertFalse(state.fail(for: old))
            XCTAssertNil(state.result)
            XCTAssertTrue(state.accepts(current))
        }
    }

    func testLeavingLessonClearsCompletedGradeAndRejectsDelayedCallbacks() throws {
        for next in [Self.snapshot(phase: .conversation), Self.snapshot(phase: .grading),
                     Self.snapshot(phase: .ended), nil] {
            var state = VoiceTutorGradingResultState()
            let request = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
                attemptID: attemptID, ownerUserID: 7))
            XCTAssertTrue(state.resolve(Self.record(), for: request))
            XCTAssertNil(state.reconcile(snapshot: next, sessionID: "call-1", attemptID: attemptID, ownerUserID: 7))
            XCTAssertEqual(state.phase, .idle)
            XCTAssertNil(state.target)
            XCTAssertNil(state.result)
            XCTAssertFalse(state.resolve(Self.record(), for: request))
            XCTAssertNil(state.retry())
        }
    }

    func testDisplayMatchRejectsDifferentLessonOrNonGradedPhase() throws {
        var state = VoiceTutorGradingResultState()
        let request = try XCTUnwrap(state.reconcile(snapshot: Self.snapshot(), sessionID: "call-1",
            attemptID: attemptID, ownerUserID: 7))
        XCTAssertTrue(state.resolve(Self.record(), for: request))
        for snapshot in [Self.snapshot(revision: 4), Self.snapshot(studyID: 43), Self.snapshot(recordID: "102"),
                         Self.snapshot(phase: .grading), nil] {
            XCTAssertFalse(state.matches(snapshot))
        }
    }

    fileprivate static func snapshot(sequence: Int64 = 1, phase: VoiceTutorSessionStateEvent.Phase = .graded,
                                     paused: Bool = false, revision: Int64 = 3, studyID: Int = 42,
                                     recordID: String = "101") -> VoiceTutorSessionStateEvent {
        VoiceTutorSessionStateEvent(sequence: sequence, phase: phase, paused: paused, revision: revision,
            studyID: studyID, recordID: recordID, answerID: "11111111-2222-4333-a444-555555555555")
    }

    fileprivate static func record(score: Int = 82) -> StudyRecord {
        StudyRecord(id: "101", studyID: 42,
            question: QuestionItem(question: "Saga에서 실패를 어떻게 처리하나요?", expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 0)), answer: "보상 트랜잭션을 실행합니다.",
            gradingResult: GradingResult(score: score, isCorrect: true,
                feedback: "트랜잭션 경계를 정확하게 설명했어요.", explanation: "보상 트랜잭션으로 실패를 복구할 수 있어요."),
            topic: "Saga", difficulty: Difficulty(level: 8), gradingStatus: .completed, questionStatus: .graded)
    }
}

@MainActor
final class VoiceTutorGradingResultLoadingTests: XCTestCase {
    func testGradedSnapshotsLoadExactAuthenticatedRecordOnceWithoutReplacingDraftOrStore() async throws {
        let fixture = try CommonRecordHTTPFixture(response: payload(VoiceTutorGradingResultTests.record()))
        defer { fixture.close() }
        let app = fixture.makeApp()
        let originalRecords = fixture.store.loadStudyRecords()
        let attemptID = UUID()
        var lesson = VoiceTutorSessionState()
        var state = VoiceTutorGradingResultState()
        for sequence in [Int64(1), 2, 3] {
            let event = VoiceTutorGradingResultTests.snapshot(sequence: sequence, paused: sequence == 2)
            XCTAssertTrue(lesson.apply(event))
            if let request = state.reconcile(snapshot: lesson.snapshot, sessionID: "call-1",
                attemptID: attemptID, ownerUserID: 7) {
                let record = await app.loadStudyRecordDetail(recordID: request.target.recordID)
                XCTAssertTrue(state.resolve(record, for: request))
            }
        }
        XCTAssertEqual(state.phase, .ready)
        XCTAssertEqual(state.result, VoiceTutorGradingResultTests.record().gradingResult)
        XCTAssertEqual(fixture.requests.count, 1, "Duplicate graded/pause receipts must not poll record detail")
        let request = try XCTUnwrap(fixture.requests.first)
        XCTAssertEqual(request.url?.path, "/api/v1/records/101")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(fixture.registration.accessToken!)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Device-Id"), fixture.registration.deviceID)
        let query = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query?.first(where: { $0.name == "view" })?.value, "localized")
        XCTAssertEqual(query?.first(where: { $0.name == "tl" })?.value, "ko")
        XCTAssertNil(request.httpBody)
        fixture.assertDraftPreserved(app)
        XCTAssertEqual(fixture.store.loadStudyRecords(), originalRecords,
            "Grade detail is transient; questionChanged owns existing record-store reconciliation")
    }

    func testAccountReplacementDuringExactRecordReadCannotPublishOldOwnersGrade() async throws {
        let fixture = try CommonRecordHTTPFixture(response: payload(VoiceTutorGradingResultTests.record()))
        defer { fixture.close() }
        let app = fixture.makeApp()
        var state = VoiceTutorGradingResultState()
        let request = try XCTUnwrap(state.reconcile(snapshot: VoiceTutorGradingResultTests.snapshot(),
            sessionID: "call-1", attemptID: UUID(), ownerUserID: 7))
        fixture.onRequest = { _ in app.communityProfile = CommonRecordHTTPFixture.profile(id: 8) }
        let record = await app.loadStudyRecordDetail(recordID: request.target.recordID)
        XCTAssertNil(record, "The authenticated AppState request fence must discard the old account response")
        XCTAssertTrue(state.resolve(record, for: request))
        XCTAssertNil(state.result)
        XCTAssertEqual(state.phase, .failed)
        XCTAssertEqual(fixture.requests.count, 1)
        fixture.assertDraftPreserved(app)
    }

    func testWrongRecordResponseFailsAndExplicitRetryReadsOnlyTheSameRecord() async throws {
        var unrelated = VoiceTutorGradingResultTests.record()
        unrelated.id = "102"
        let fixture = try CommonRecordHTTPFixture(response: payload(unrelated))
        defer { fixture.close() }
        let app = fixture.makeApp()
        let attemptID = UUID()
        var state = VoiceTutorGradingResultState()
        let request = try XCTUnwrap(state.reconcile(snapshot: VoiceTutorGradingResultTests.snapshot(),
            sessionID: "call-1", attemptID: attemptID, ownerUserID: 7))
        let wrongRecord = await app.loadStudyRecordDetail(recordID: request.target.recordID)
        XCTAssertNil(wrongRecord)
        XCTAssertTrue(state.resolve(wrongRecord, for: request))
        XCTAssertEqual(state.phase, .failed)
        XCTAssertNil(state.result)
        XCTAssertNil(state.reconcile(snapshot: VoiceTutorGradingResultTests.snapshot(sequence: 2),
            sessionID: "call-1", attemptID: attemptID, ownerUserID: 7))
        XCTAssertEqual(fixture.requests.count, 1)
        fixture.response = try payload(VoiceTutorGradingResultTests.record())
        let retry = try XCTUnwrap(state.retry())
        let correctedRecord = await app.loadStudyRecordDetail(recordID: retry.target.recordID)
        XCTAssertTrue(state.resolve(correctedRecord, for: retry))
        XCTAssertEqual(state.phase, .ready)
        XCTAssertEqual(state.result?.score, 82)
        XCTAssertEqual(fixture.requests.map { $0.url?.path }, ["/api/v1/records/101", "/api/v1/records/101"])
        XCTAssertTrue(fixture.requests.allSatisfy { $0.httpMethod == "GET" })
        fixture.assertDraftPreserved(app)
    }

    private func payload(_ record: StudyRecord) throws -> [String: Any] {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(record)) as? [String: Any])
    }
}
