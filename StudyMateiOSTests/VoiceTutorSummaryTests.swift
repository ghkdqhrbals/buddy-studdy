import Foundation
import XCTest
@testable import StudyMate

/// Synthetic detail reads only: no AppState, network, microphone, disk writes,
/// account data, result regeneration, or voice/question quota allocation.
final class VoiceTutorSummaryTests: XCTestCase {
    private let sessionID = "synthetic-summary-session"

    func testProcessingResultObjectIsNotTerminalEvenWithPartialContent() throws {
        for status in ["PENDING", "PROCESSING"] {
            let detail = try makeDetail(status: status, result: ["summaryMarkdown": "미완성 합성 요약"])
            XCTAssertEqual(VoiceTutorSummaryState(detail: detail), .pending)
            XCTAssertFalse(VoiceTutorSummaryState(detail: detail).isTerminal)
        }
    }

    func testNestedProcessingResultObjectIsNotTerminal() throws {
        let detail = try makeDetail(result: ["status": "PROCESSING", "summaryMarkdown": "부분 결과"])
        XCTAssertEqual(VoiceTutorSummaryState(detail: detail), .pending)
        XCTAssertFalse(VoiceTutorSummaryState(detail: detail).isTerminal)
    }

    func testOnlyExplicitCompletedStatusPublishesEverySupportedContentSection() throws {
        let contents: [[String: Any]] = [
            ["summaryMarkdown": "합성 요약"],
            ["strengths": ["합성 강점"]],
            ["improvements": ["합성 보완점"]],
            ["nextSteps": ["합성 다음 단계"]]
        ]
        for content in contents {
            XCTAssertEqual(VoiceTutorSummaryState(detail: try makeDetail(status: "COMPLETED", result: content)), .ready)
            XCTAssertEqual(VoiceTutorSummaryState(detail: try makeDetail(result: content)), .unknown)
        }
    }

    func testCompletedEmptyOrWhitespaceResultIsTerminalNotPerpetuallyPending() throws {
        let results: [[String: Any]?] = [nil, [:], [
            "summaryMarkdown": " \n\t ", "strengths": [" \n"], "improvements": [""], "nextSteps": ["\t"]
        ]]
        for result in results {
            let state = VoiceTutorSummaryState(detail: try makeDetail(status: "COMPLETED", result: result))
            XCTAssertEqual(state, .empty)
            XCTAssertTrue(state.isTerminal)
        }
        XCTAssertEqual(VoiceTutorSummaryState.nonemptyItems(["", " \t", "남길 항목", "\n"]), ["남길 항목"])
    }

    func testStatusNormalizationAndConservativeNestedStatusPrecedence() throws {
        let cases: [(String?, String?, VoiceTutorSummaryState)] = [
            (" completed \n", nil, .ready),
            (nil, " completed \t", .ready),
            ("completed", " failed ", .failed),
            ("pending", "completed", .pending),
            ("COMPLETED", " processing ", .pending),
            ("FAILED", "PROCESSING", .failed),
            ("UNKNOWN", nil, .unknown)
        ]
        for (outer, nested, expected) in cases {
            var result: [String: Any] = ["summaryMarkdown": "합성 요약"]
            if let nested { result["status"] = nested }
            XCTAssertEqual(VoiceTutorSummaryState(detail: try makeDetail(status: outer, result: result)), expected)
        }
    }

    func testFailedCallDoesNotMaskPendingOrCompletedSummary() throws {
        let pending = try makeDetail(status: "PROCESSING", callState: "FAILED")
        let completed = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "저장된 합성 요약"], callState: "FAILED")
        XCTAssertEqual(VoiceTutorSummaryState(detail: pending), .pending)
        XCTAssertEqual(VoiceTutorSummaryState(detail: completed), .ready)
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .failed, detail: pending).summaryState, .pending)
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .failed, detail: completed).summaryState, .ready)
    }

    func testSummaryExhaustionIsDeferredAndDistinctFromGenerationFailure() throws {
        let pending = try makeDetail(status: "PROCESSING", result: [:])
        let presentation = VoiceTutorCallPresentation(phase: .ended, detail: pending, summaryRefreshState: .deferred)
        XCTAssertEqual(presentation.summaryState, .deferred)
        XCTAssertNotEqual(presentation.summaryState, .failed)
        XCTAssertEqual(VoiceTutorSummaryPollOutcome(detail: pending, reason: .exhausted).refreshState, .deferred)
    }

    func testFetchFailureDoesNotBecomeBackendSummaryFailure() throws {
        let pending = try makeDetail(status: "PROCESSING")
        XCTAssertEqual(
            VoiceTutorCallPresentation(phase: .ended, detail: pending, summaryRefreshState: .unavailable).summaryState,
            .unavailable
        )
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .ended, summaryRefreshState: .unavailable).summaryState, .unavailable)
        let completed = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "합성 요약"])
        XCTAssertEqual(
            VoiceTutorCallPresentation(phase: .ended, detail: completed, summaryRefreshState: .unavailable).summaryState,
            .ready,
            "A refresh failure does not invalidate an already completed private result"
        )
    }

    func testSummaryMessagesDistinguishDelayReadFailureGenerationFailureAndEmpty() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let messages = [strings.voiceTutorSummaryPending, strings.voiceTutorSummaryDeferred,
                            strings.voiceTutorSummaryUnavailable, strings.voiceTutorSummaryFailed,
                            strings.voiceTutorSummaryEmpty]
            XCTAssertEqual(Set(messages).count, messages.count)
            XCTAssertTrue(messages.allSatisfy { !$0.isEmpty })
            XCTAssertFalse(strings.voiceTutorSummaryRefresh.isEmpty)
        }
    }

    @MainActor
    func testPollContinuesThroughProcessingPlaceholdersUntilCompleted() async throws {
        let pending = try makeDetail(status: "PENDING", result: ["status": "PENDING"])
        let processing = try makeDetail(status: "PROCESSING", result: ["status": "PROCESSING", "summaryMarkdown": "부분"])
        let completed = try makeDetail(status: "COMPLETED", result: ["status": "COMPLETED", "summaryMarkdown": "최종 합성 요약"])
        let probe = SummaryReadProbe(responses: [processing, completed])
        let outcome = await poll(probe, initial: pending)
        XCTAssertEqual(outcome, .init(detail: completed, reason: .terminal))
        XCTAssertEqual(probe.requestCount, 2)
        XCTAssertEqual(probe.updates, [processing, completed])
        XCTAssertEqual(probe.delays, [750, 750])
    }

    @MainActor
    func testPollUsesNestedStatusWhenTopLevelStatusIsMissing() async throws {
        let pending = try makeDetail(result: ["status": "PROCESSING", "summaryMarkdown": "미완성"])
        let complete = try makeDetail(result: ["status": "COMPLETED", "summaryMarkdown": "합성 요약"])
        let probe = SummaryReadProbe(responses: [pending, complete])
        let outcome = await poll(probe)
        XCTAssertEqual(outcome.detail, complete)
        XCTAssertEqual(outcome.reason, .terminal)
        XCTAssertEqual(probe.requestCount, 2)
    }

    @MainActor
    func testBackendFailedSummaryStopsPollingWithoutRetryingGeneration() async throws {
        let failed = try makeDetail(status: "FAILED", result: ["errorMessage": "synthetic private provider detail"])
        let probe = SummaryReadProbe(responses: [failed])
        let outcome = await poll(probe)
        XCTAssertEqual(outcome, .init(detail: failed, reason: .terminal))
        XCTAssertEqual(outcome.refreshState, .idle)
        XCTAssertEqual(probe.requestCount, 1)
        XCTAssertTrue(probe.delays.isEmpty)
    }

    @MainActor
    func testCompletedEmptyResultEndsPolling() async throws {
        let empty = try makeDetail(status: "COMPLETED", result: [:])
        let probe = SummaryReadProbe(responses: [empty])
        let outcome = await poll(probe)
        XCTAssertEqual(outcome, .init(detail: empty, reason: .terminal))
        XCTAssertEqual(probe.requestCount, 1)
    }

    @MainActor
    func testHistoryEntryAlwaysRefreshesEvenWhenCachedResultIsTerminal() async throws {
        let cached = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "기존 합성 요약"])
        let fresh = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "새 합성 요약"])
        let probe = SummaryReadProbe(responses: [fresh])
        let outcome = await poll(probe, initial: cached, refreshCachedDetail: true)
        XCTAssertEqual(outcome.detail, fresh)
        XCTAssertEqual(probe.requestCount, 1)
        XCTAssertTrue(probe.delays.isEmpty, "Reopening history performs its first GET immediately")
    }

    @MainActor
    func testTerminalEndResponseDoesNotNeedAnotherPoll() async throws {
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "합성 요약"])
        let probe = SummaryReadProbe(responses: [])
        let outcome = await poll(probe, initial: complete)
        XCTAssertEqual(outcome, .init(detail: complete, reason: .terminal))
        XCTAssertEqual(probe.requestCount, 0)
    }

    @MainActor
    func testPendingAndUnknownResponsesExhaustExactlyEightReads() async throws {
        for status in ["PROCESSING", "UNKNOWN"] {
            let placeholder = try makeDetail(status: status, result: ["summaryMarkdown": "잠정 합성 결과"])
            let probe = SummaryReadProbe(responses: [placeholder])
            let outcome = await poll(probe)
            XCTAssertEqual(outcome, .init(detail: placeholder, reason: .exhausted))
            XCTAssertEqual(probe.requestCount, 8)
            XCTAssertEqual(probe.delays.count, 7)
            XCTAssertEqual(probe.updates.count, 8)
            XCTAssertEqual(outcome.refreshState, .deferred)
        }
    }

    @MainActor
    func testCompletionOnLastAllowedReadIsNotMistakenForExhaustion() async throws {
        let pending = try makeDetail(status: "PROCESSING", result: [:])
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "최종 합성 요약"])
        let responses: [BackendVoiceTutorSessionDetail?] = Array(repeating: pending, count: 7) + [complete]
        let probe = SummaryReadProbe(responses: responses)
        let outcome = await poll(probe)
        XCTAssertEqual(outcome, .init(detail: complete, reason: .terminal))
        XCTAssertEqual(probe.requestCount, 8)
        XCTAssertEqual(probe.delays.count, 7)
    }

    func testServerPollDelayIsBoundedForAllIntegerValues() {
        let cases: [(Int?, Int)] = [(nil, 750), (Int.min, 250), (-1, 250), (0, 250),
                                   (249, 250), (250, 250), (751, 751), (5_000, 5_000),
                                   (5_001, 5_000), (Int.max, 5_000)]
        for (input, expected) in cases {
            XCTAssertEqual(VoiceTutorSummaryPolling.clampedDelayMilliseconds(input), expected)
        }
    }

    @MainActor
    func testPollingAdoptsEachNewBoundedServerDelay() async throws {
        let initial = try makeDetail(status: "PROCESSING", delay: Int.min)
        let slow = try makeDetail(status: "PROCESSING", delay: Int.max)
        let fast = try makeDetail(status: "PROCESSING", delay: -1)
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "합성 요약"])
        let probe = SummaryReadProbe(responses: [slow, fast, complete])
        let outcome = await poll(probe, initial: initial)
        XCTAssertEqual(outcome.reason, .terminal)
        XCTAssertEqual(probe.delays, [250, 5_000, 250])
    }

    @MainActor
    func testFailedReadPreservesCachedDetailAndOffersReadRetry() async throws {
        let cached = try makeDetail(status: "PROCESSING", result: [:])
        let probe = SummaryReadProbe(responses: [nil])
        let outcome = await poll(probe, initial: cached, refreshCachedDetail: true)
        XCTAssertEqual(outcome, .init(detail: cached, reason: .unavailable))
        XCTAssertEqual(outcome.refreshState, .unavailable)
        XCTAssertEqual(probe.requestCount, 1)
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testExplicitRetryReadsExistingPendingSummaryToCompletion() async throws {
        let cached = try makeDetail(status: "PROCESSING", result: [:])
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "합성 요약"])
        let probe = SummaryReadProbe(responses: [complete])
        let outcome = await poll(probe, initial: cached, refreshCachedDetail: true)
        XCTAssertEqual(outcome.detail?.sessionId, cached.sessionId)
        XCTAssertEqual(outcome.detail, complete)
        XCTAssertEqual(probe.requestCount, 1)
    }

    @MainActor
    func testMismatchedSessionResponseCannotPublishIntoCurrentSummary() async throws {
        let pending = try makeDetail(status: "PROCESSING")
        var wrong = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "다른 합성 세션"])
        wrong.sessionId = "different-synthetic-session"
        let probe = SummaryReadProbe(responses: [wrong])
        let outcome = await poll(probe, initial: pending, refreshCachedDetail: true)
        XCTAssertEqual(outcome, .init(detail: pending, reason: .unavailable))
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testInvalidatedContextCannotReadEvenATerminalCachedSummary() async throws {
        let cached = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "예전 계정 합성 요약"])
        let probe = SummaryReadProbe(responses: [])
        probe.valid = false
        let outcome = await poll(probe, initial: cached)
        XCTAssertEqual(outcome, .init(detail: nil, reason: .invalidated))
        XCTAssertEqual(probe.requestCount, 0)
    }

    @MainActor
    func testAccountInvalidationWhileWaitingPreventsNextRead() async throws {
        let pending = try makeDetail(status: "PROCESSING")
        let probe = SummaryReadProbe(responses: [pending])
        probe.onSleep = { [weak probe] in probe?.valid = false }
        let outcome = await poll(probe, initial: pending)
        XCTAssertEqual(outcome, .init(detail: nil, reason: .invalidated))
        XCTAssertEqual(probe.requestCount, 0)
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testNewAttemptRejectsNonCooperativeInFlightSummaryDelivery() async throws {
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "오래된 시도 합성 요약"])
        let probe = SummaryReadProbe(responses: [])
        let gate = SummaryReadGate()
        probe.gate = gate
        let attempt = probe.attemptID
        let task = Task {
            await VoiceTutorSummaryPolling.poll(
                sessionID: sessionID,
                initialDetail: nil,
                loader: probe.loader,
                isCurrent: { probe.attemptID == attempt },
                onUpdate: { probe.updates.append($0) },
                sleep: { probe.delays.append($0) }
            )
        }
        defer { task.cancel(); gate.open(nil) }
        let arrived = await XCTWaiter.fulfillment(of: [gate.entered], timeout: 2)
        XCTAssertEqual(arrived, .completed)
        probe.attemptID = UUID()
        gate.open(complete)
        let outcome = await task.value
        XCTAssertEqual(outcome, .init(detail: nil, reason: .invalidated))
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testAccountInvalidationRejectsNonCooperativeInFlightSummary() async throws {
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "예전 계정 합성 요약"])
        let probe = SummaryReadProbe(responses: [])
        let gate = SummaryReadGate()
        probe.gate = gate
        let task = Task { await poll(probe) }
        defer { task.cancel(); gate.open(nil) }
        let arrived = await XCTWaiter.fulfillment(of: [gate.entered], timeout: 2)
        XCTAssertEqual(arrived, .completed)
        probe.valid = false
        gate.open(complete)
        let outcome = await task.value
        XCTAssertEqual(outcome, .init(detail: nil, reason: .invalidated))
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testCancelledHistoryTaskCannotPublishLateSummary() async throws {
        let complete = try makeDetail(status: "COMPLETED", result: ["summaryMarkdown": "취소된 조회 합성 요약"])
        let probe = SummaryReadProbe(responses: [])
        let gate = SummaryReadGate()
        probe.gate = gate
        let task = Task { await poll(probe) }
        defer { task.cancel(); gate.open(nil) }
        let arrived = await XCTWaiter.fulfillment(of: [gate.entered], timeout: 2)
        XCTAssertEqual(arrived, .completed)
        task.cancel()
        gate.open(complete)
        let outcome = await task.value
        XCTAssertEqual(outcome, .init(detail: nil, reason: .cancelled))
        XCTAssertTrue(probe.updates.isEmpty)
    }

    @MainActor
    func testCancellationDuringPollDelayDoesNotFallThroughToAnotherGet() async throws {
        let pending = try makeDetail(status: "PROCESSING")
        let probe = SummaryReadProbe(responses: [pending])
        let outcome = await VoiceTutorSummaryPolling.poll(
            sessionID: sessionID,
            initialDetail: pending,
            loader: probe.loader,
            sleep: { _ in throw CancellationError() }
        )
        XCTAssertEqual(outcome.reason, .cancelled)
        XCTAssertEqual(probe.requestCount, 0)
    }

    func testVoiceCreatedStudyRefreshPreservesExistingPendingAndLatestRecords() {
        var previous = makeStudy(id: 102)
        previous.pendingQuestion = makeRecord(id: "draft-fixture", studyID: 102)
        previous.latestQuestion = makeRecord(id: "latest-fixture", studyID: 102)
        var fetched = makeStudy(id: 102)
        fetched.topic = "갱신된 합성 하위 주제"
        fetched.parentStudyId = 101
        fetched.difficultyLevel = 7
        fetched.pendingQuestion = makeRecord(id: "remote-fixture", studyID: 102)
        let metadata = VoiceTutorCreatedStudyMetadata.merging(fetched, with: previous)
        XCTAssertEqual(metadata.topic, fetched.topic)
        XCTAssertEqual(metadata.parentStudyId, 101)
        XCTAssertEqual(metadata.difficultyLevel, 7)
        XCTAssertEqual(metadata.pendingQuestion, previous.pendingQuestion)
        XCTAssertEqual(metadata.latestQuestion, previous.latestQuestion)
        XCTAssertEqual(previous.pendingQuestion?.answer, "수정 중인 합성 답안")
        XCTAssertEqual(fetched.pendingQuestion?.id, "remote-fixture", "Merging must not mutate the input snapshot")
    }

    func testNewVoiceCreatedStudyImportsMetadataWithoutImportingQuestions() {
        var fetched = makeStudy(id: 102)
        fetched.parentStudyId = 101
        fetched.pendingQuestion = makeRecord(id: "remote-pending", studyID: 102)
        fetched.latestQuestion = makeRecord(id: "remote-latest", studyID: 102)
        let metadata = VoiceTutorCreatedStudyMetadata.merging(fetched, with: nil)
        XCTAssertEqual(metadata.id, fetched.id)
        XCTAssertEqual(metadata.parentStudyId, 101)
        XCTAssertNil(metadata.pendingQuestion)
        XCTAssertNil(metadata.latestQuestion)
    }

    func testVoiceCreatedStudyMetadataNeverCopiesRecordsFromAnotherStudy() {
        var other = makeStudy(id: 101)
        other.pendingQuestion = makeRecord(id: "other-draft", studyID: 101)
        let child = makeStudy(id: 102)
        let metadata = VoiceTutorCreatedStudyMetadata.merging(child, with: other)
        XCTAssertNil(metadata.pendingQuestion)
        XCTAssertNil(metadata.latestQuestion)
        var rooms = StudyRoomStateStore()
        rooms.replace(with: [other])
        rooms.upsertStudy(metadata)
        XCTAssertEqual(rooms.rooms.count, 2)
        XCTAssertEqual(rooms.rooms.first, other)
        XCTAssertEqual(rooms.rooms.last, metadata)
    }

    @MainActor
    private func poll(
        _ probe: SummaryReadProbe,
        initial: BackendVoiceTutorSessionDetail? = nil,
        refreshCachedDetail: Bool = false
    ) async -> VoiceTutorSummaryPollOutcome {
        await VoiceTutorSummaryPolling.poll(
            sessionID: sessionID,
            initialDetail: initial,
            refreshCachedDetail: refreshCachedDetail,
            loader: probe.loader,
            onUpdate: { probe.updates.append($0) },
            sleep: { delay in
                probe.delays.append(delay)
                probe.onSleep?()
            }
        )
    }

    private func makeDetail(
        status: String? = nil,
        result: [String: Any]? = nil,
        callState: String = "ENDED",
        delay: Int? = nil
    ) throws -> BackendVoiceTutorSessionDetail {
        var json: [String: Any] = ["sessionId": sessionID, "topic": "합성 학습", "state": callState]
        if let status { json["resultStatus"] = status }
        if let result { json["result"] = result }
        if let delay { json["pollAfterMs"] = delay }
        return try JSONDecoder().decode(
            BackendVoiceTutorSessionDetail.self,
            from: JSONSerialization.data(withJSONObject: json)
        )
    }

    private func makeStudy(id: Int) -> BackendStudyRoom {
        BackendStudyRoom(
            id: id, topic: "합성 주제", difficultyLevel: 3, intervalMinutes: 60,
            enabled: true, notificationSound: nil, customPrompt: "", openAIModel: "synthetic-model",
            maxHistoryCount: 30, nextDueAt: nil, lastSentAt: nil, lastError: nil,
            pendingQuestion: nil, createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
    }

    private func makeRecord(id: String, studyID: Int) -> StudyRecord {
        StudyRecord(
            id: id, studyID: studyID,
            question: QuestionItem(question: "합성 질문", expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 0)),
            answer: "수정 중인 합성 답안", topic: "합성 주제", difficulty: .level3,
            isPublic: false
        )
    }
}

@MainActor
private final class SummaryReadProbe {
    var responses: [BackendVoiceTutorSessionDetail?]
    var requestCount = 0
    var delays: [Int] = []
    var updates: [BackendVoiceTutorSessionDetail] = []
    var valid = true
    var attemptID = UUID()
    var onSleep: (() -> Void)?
    var gate: SummaryReadGate?

    init(responses: [BackendVoiceTutorSessionDetail?]) { self.responses = responses }

    var loader: VoiceTutorSessionDetailLoader {
        VoiceTutorSessionDetailLoader(
            isCurrent: { [self] in valid },
            load: { [self] in
                requestCount += 1
                if let gate { return await gate.wait() }
                guard !responses.isEmpty else { return nil }
                return responses[min(requestCount - 1, responses.count - 1)]
            }
        )
    }
}

@MainActor
private final class SummaryReadGate {
    let entered = XCTestExpectation(description: "Synthetic summary read entered")
    private var continuation: CheckedContinuation<BackendVoiceTutorSessionDetail?, Never>?
    private var released = false
    private var value: BackendVoiceTutorSessionDetail?

    func wait() async -> BackendVoiceTutorSessionDetail? {
        entered.fulfill()
        if released { return value }
        return await withCheckedContinuation { continuation = $0 }
    }

    func open(_ value: BackendVoiceTutorSessionDetail?) {
        guard !released else { return }
        released = true
        self.value = value
        continuation?.resume(returning: value)
        continuation = nil
    }
}
