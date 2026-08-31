import Combine
import Foundation
import XCTest
@testable import StudyMate

/// Synthetic metadata/text, isolated defaults, and a fail-closed URLProtocol.
/// No device/account purge, microphone, provider, quota write, or real network.
@MainActor
final class StudyLearningRecordsTests: XCTestCase {
    func testMixedPageKeepsQuestionAndVoiceIdentitiesSeparate() throws {
        let page = try decodePage([questionItem(id: 101), voiceItem(id: 101)])
        XCTAssertEqual(page.items.map(\.id), ["question:101", "voice:101"])
        XCTAssertNotNil(page.items[0].questionRecord)
        XCTAssertNil(page.items[0].voiceRecord)
        XCTAssertNil(page.items[1].questionRecord)
        XCTAssertEqual(page.items[1].voiceRecord?.questionTurnID, "1001")
        XCTAssertEqual(page.items[1].voiceRecord?.answerTurnIDs, ["1002"])
    }

    func testMalformedSourceAndMismatchedNodeCannotMasqueradeAsAnotherRecord() throws {
        var wrongSource = voiceItem()
        wrongSource["source"] = "QUESTION"
        var wrongIdentity = voiceItem()
        wrongIdentity["id"] = "question:456"
        var wrongNode = voiceItem()
        wrongNode["studyId"] = 999
        for item in [wrongSource, wrongIdentity, wrongNode] {
            XCTAssertThrowsError(try decodePage([item]))
        }
    }

    func testVoiceTurnReferencesAcceptNumericOrStringIDsWithoutChangingText() throws {
        var voice = voiceBody()
        voice["questionTurnId"] = String(Int64.max)
        voice["answerTurnIds"] = ["1002", "1003"]
        voice["question"] = "  합성 질문?\n"
        let result = try decodeVoice(voice)
        XCTAssertEqual(result.questionTurnID, String(Int64.max))
        XCTAssertEqual(result.answerTurnIDs, ["1002", "1003"])
        XCTAssertEqual(result.question, "  합성 질문?\n")
        voice["questionTurnId"] = 0
        XCTAssertThrowsError(try decodeVoice(voice))
    }

    func testMissingInvalidOrLearnerQuestionScoresNeverBecomeZeroGrades() throws {
        for value in [NSNull(), -1, 101, "85"] as [Any] {
            var voice = voiceBody()
            voice["score"] = value
            XCTAssertNil(try decodeVoice(voice).displayScore)
        }
        var unanswered = voiceBody()
        unanswered["answer"] = NSNull()
        XCTAssertNil(try decodeVoice(unanswered).displayScore)
        var learnerQuestion = voiceBody()
        learnerQuestion["kind"] = "LEARNER_QUESTION"
        XCTAssertNil(try decodeVoice(learnerQuestion).displayScore)
        var actualZero = voiceBody()
        actualZero["score"] = 0
        XCTAssertEqual(try decodeVoice(actualZero).displayScore, 0)
    }

    func testPageRejectsMissingItemsDuplicateIDsAndUnusableNextCursor() throws {
        XCTAssertThrowsError(try decode(BackendStudyLearningRecordsPage.self, ["hasMore": false, "limit": 30]))
        XCTAssertThrowsError(try decodePage([voiceItem(), voiceItem()]))
        XCTAssertThrowsError(try decodePage([voiceItem()], next: nil, hasMore: true))
        // Every consumed key can disappear between keyset lookup and content hydration.
        XCTAssertEqual(try decodePage([], next: "next", hasMore: true).nextCursor, "next")
        XCTAssertThrowsError(try decodePage([voiceItem()], next: String(repeating: "x", count: 4_097), hasMore: true))
        XCTAssertEqual(try decodePage([]).items, [])
    }

    func testPageCacheUsesEightEntryLRUAndEvictsOldIdentityMaterial() throws {
        var cache = StudyLearningRecordsPageCache()
        let page = try decodePage([voiceItem()])
        for index in 0..<8 { cache.save(page, for: cacheKey(cursor: "\(index)")) }
        XCTAssertNotNil(cache.page(for: cacheKey(cursor: "0")))
        cache.save(page, for: cacheKey(cursor: "8"))
        XCTAssertEqual(cache.count, 8)
        XCTAssertNil(cache.page(for: cacheKey(cursor: "1")))
        XCTAssertNotNil(cache.page(for: cacheKey(cursor: "0")))
        var other = cacheKey(cursor: "0")
        other.context.identity.userID = 8
        XCTAssertNil(cache.page(for: other))
        XCTAssertEqual(cache.count, 0)
    }

    func testCacheSeparatesNodeSubtreeLocaleEnvironmentAndOriginalView() throws {
        let original = cacheKey()
        var variants: [StudyLearningRecordsCacheKey] = []
        var node = original; node.context.studyID = 42; variants.append(node)
        var scope = original; scope.context.scope = .subtree; variants.append(scope)
        var language = original; language.context.identity.languageCode = "en"; variants.append(language)
        var server = original; server.context.identity.backendGeneration += 1; variants.append(server)
        var session = original; session.context.identity.sessionGeneration += 1; variants.append(session)
        var view = original; view.view = "original"; variants.append(view)
        for variant in variants {
            var cache = StudyLearningRecordsPageCache()
            cache.save(try decodePage([voiceItem()]), for: original)
            XCTAssertNil(cache.page(for: variant))
        }
    }

    func testSettingsStoreCacheDoesNotPersistOrMutateQuestionDraftsAndClearsWithRecordCache() throws {
        let fixture = try LearningStoreFixture()
        defer { fixture.close() }
        let useCase = LocalStudyRecordUseCase(repository: SettingsStoreLocalStudyRecordRepository(settingsStore: fixture.store))
        let question = try XCTUnwrap(try decodePage([questionItem()]).items.first?.questionRecord)
        useCase.saveRecord(question)
        useCase.saveAnswerDraft("합성 초안", recordID: "999")
        fixture.store.saveLastAnswer("합성 활성 초안")
        let before = fixture.defaults.dictionaryRepresentation()
        let page = try decodePage([voiceItem()])
        useCase.saveLearningRecordsPage(page, for: cacheKey())
        XCTAssertEqual(useCase.loadLearningRecordsPage(for: cacheKey()), page)
        XCTAssertEqual(useCase.loadRecords(), [question])
        XCTAssertEqual(useCase.loadAnswerDraft(recordID: "999"), "합성 초안")
        XCTAssertEqual(fixture.store.loadLastAnswer(), "합성 활성 초안")
        XCTAssertEqual(NSDictionary(dictionary: fixture.defaults.dictionaryRepresentation()), NSDictionary(dictionary: before))
        let reloaded = SettingsStore(defaults: fixture.defaults, usesSecureBackendIdentityStorage: false)
        XCTAssertNil(reloaded.loadStudyLearningRecordsPage(for: cacheKey()))
        useCase.clearLearningRecordsPages()
        XCTAssertNil(useCase.loadLearningRecordsPage(for: cacheKey()))
        XCTAssertEqual(useCase.loadRecords(), [question])
    }

    func testRequestUsesExactNodeOpaqueCursorAndLocalizationParameters() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([voiceItem()]))
        defer { fixture.close() }
        let cursor = "opaque+/=:&?"
        _ = try await fixture.client.fetchStudyLearningRecords(
            registration: fixture.registration, studyID: 41, scope: .node,
            limit: 30, cursor: cursor, language: .japanese, view: .original
        )
        let request = try XCTUnwrap(fixture.requests.last)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v1/studies/41/learning-records")
        let query = try queryItems(request)
        XCTAssertEqual(query["scope"], "node")
        XCTAssertEqual(query["limit"], "30")
        XCTAssertEqual(query["cursor"], cursor)
        XCTAssertEqual(query["tl"], "ja")
        XCTAssertEqual(query["view"], "original")
        XCTAssertNil(query["offset"])
    }

    func testSubtreeKeepsExactChildIDsWhileNodeScopeRejectsForeignNodes() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([voiceItem(studyID: 42)]))
        defer { fixture.close() }
        let subtree = try await fixture.client.fetchStudyLearningRecords(
            registration: fixture.registration, studyID: 41, scope: .subtree,
            limit: 30, cursor: nil, language: .korean, view: .localized
        )
        XCTAssertEqual(subtree.items.first?.studyID, 42)
        do {
            _ = try await fixture.client.fetchStudyLearningRecords(
                registration: fixture.registration, studyID: 41, scope: .node,
                limit: 30, cursor: nil, language: .korean, view: .localized
            )
            XCTFail("Exact-node reads must not silently relabel descendants")
        } catch { XCTAssertTrue(error is StudyLearningRecordsError) }
    }

    func testVoiceDetailUsesSingleRecordReadAndChecksReturnedIdentity() async throws {
        let fixture = try LearningHTTPFixture(page: voiceBody())
        defer { fixture.close() }
        let record = try await fixture.client.fetchVoiceStudyLearningRecord(
            registration: fixture.registration, recordID: "456", language: .english, view: .original
        )
        XCTAssertEqual(record.id, "456")
        XCTAssertEqual(fixture.requests.last?.url?.path, "/api/v1/voice-tutor/learning-records/456")
        XCTAssertEqual(try queryItems(try XCTUnwrap(fixture.requests.last))["view"], "original")
        do {
            _ = try await fixture.client.fetchVoiceStudyLearningRecord(
                registration: fixture.registration, recordID: "457", language: .english, view: .localized
            )
            XCTFail("The body must match the requested private record")
        } catch { XCTAssertTrue(error is StudyLearningRecordsError) }
    }

    func testPrivateLearningBodyPolicyMatchesEndpointsNotWordsOrUnrelatedPaths() {
        for path in [
            "/api/v1/studies/41/learning-records?tl=ko", "/prefix/api/v1/studies/41/learning-records/",
            "/api/v1/voice-tutor/learning-records/456", "/api/v1/voice-tutor/sessions/fixture", "/api/v1/records"
        ] {
            XCTAssertTrue(RemotePushBackendClient.suppressesPrivateLearningBodies(for: URL(string: "https://fixture.test\(path)")))
        }
        for path in ["/api/v1/studies/41", "/api/v1/studies/41/learning-records-other"] {
            XCTAssertFalse(RemotePushBackendClient.suppressesPrivateLearningBodies(for: URL(string: "https://fixture.test\(path)")))
        }
    }

    func testPrivateLearningSuccessAndErrorLogsNeverContainResponseText() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([voiceItem()]))
        defer { fixture.close() }
        let logs = LearningTrafficCapture()
        let observer = NotificationCenter.default.addObserver(forName: APITrafficNotification.didReceiveLog, object: nil, queue: nil) { note in
            if let entry = note.userInfo?[APITrafficNotification.userInfoKey] as? APITrafficLogEntry {
                logs.append(entry)
            }
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        _ = try await fixture.client.fetchStudyLearningRecords(
            registration: fixture.registration, studyID: 41, scope: .node,
            limit: 30, cursor: nil, language: .korean, view: .localized
        )
        fixture.status = 500
        fixture.response = ["code": "INTERNAL_ERROR", "message": "PRIVATE_SYNTHETIC_ERROR"]
        do {
            _ = try await fixture.client.fetchStudyLearningRecords(
                registration: fixture.registration, studyID: 41, scope: .node,
                limit: 30, cursor: nil, language: .korean, view: .localized
            )
            XCTFail("Expected a bounded read error")
        } catch { XCTAssertFalse(error.localizedDescription.contains("PRIVATE_SYNTHETIC_ERROR")) }
        let entries = logs.snapshot().filter { $0.url.contains(fixture.host) }
        XCTAssertEqual(entries.count, 2)
        for entry in entries {
            XCTAssertEqual(entry.requestBody, "[REDACTED]")
            XCTAssertEqual(entry.responseBody, "[REDACTED]")
            XCTAssertFalse((entry.error ?? "").contains("PRIVATE_SYNTHETIC_ERROR"))
            XCTAssertFalse(entry.responseBody.contains("합성"))
        }
    }

    func testPaginationUsesServerCursorStackAndKeepsOnlyCurrentPage() async throws {
        let first = try decodePage([voiceItem(id: 1)], next: "opaque-next", hasMore: true)
        let second = try decodePage([voiceItem(id: 2)])
        var requests: [String?] = []
        let model = StudyLearningRecordsViewModel()
        let loader = makeLoader { cursor in
            requests.append(cursor)
            return cursor == nil ? first : second
        }
        await model.activate(loader)
        await model.load(.next)
        XCTAssertEqual(model.page?.items.map(\.id), ["voice:2"])
        XCTAssertEqual(model.pageNumber, 2)
        XCTAssertFalse(model.canGoNext)
        await model.load(.previous)
        XCTAssertEqual(model.page?.items.map(\.id), ["voice:1"])
        XCTAssertEqual(model.pageNumber, 1)
        XCTAssertEqual(requests, [nil, "opaque-next", nil])
    }

    func testFailedNextPageRetainsCurrentRowsAndRetryUsesSameCursor() async throws {
        let first = try decodePage([voiceItem(id: 1)], next: "opaque-next", hasMore: true)
        let second = try decodePage([voiceItem(id: 2)])
        var fails = true
        var requests: [String?] = []
        let loader = makeLoader { cursor in
            requests.append(cursor)
            if cursor != nil && fails { throw URLError(.timedOut) }
            return cursor == nil ? first : second
        }
        let model = StudyLearningRecordsViewModel()
        await model.activate(loader)
        await model.load(.next)
        XCTAssertTrue(model.failed)
        XCTAssertEqual(model.pageNumber, 1)
        XCTAssertEqual(model.page, first)
        fails = false
        await model.load(.retry)
        XCTAssertEqual(requests, [nil, "opaque-next", "opaque-next"])
        XCTAssertEqual(model.pageNumber, 2)
    }

    func testCursorCycleCannotCauseAnInfinitePagingLoop() async throws {
        let first = try decodePage([voiceItem(id: 1)], next: "loop", hasMore: true)
        let loop = try decodePage([voiceItem(id: 2)], next: "loop", hasMore: true)
        let model = StudyLearningRecordsViewModel()
        await model.activate(makeLoader { $0 == nil ? first : loop })
        await model.load(.next)
        XCTAssertTrue(model.failed)
        XCTAssertEqual(model.pageNumber, 1)
        XCTAssertEqual(model.page, first)
    }

    func testLatePageAfterScopeReplacementCannotReplaceNewRows() async throws {
        let old = try decodePage([voiceItem(id: 1)])
        let current = try decodePage([voiceItem(id: 2, studyID: 42)])
        let gate = LearningReadGate()
        let model = StudyLearningRecordsViewModel()
        let task = Task { await model.activate(makeLoader { _ in await gate.wait(); return old }) }
        await gate.untilWaiting()
        await model.activate(makeLoader(studyID: 42) { _ in current })
        gate.resume()
        await task.value
        XCTAssertEqual(model.page, current)
        XCTAssertEqual(model.context?.studyID, 42)
        XCTAssertFalse(model.isLoading)
    }

    func testDeactivatedPageAndInvalidIdentityDoNotPublishLateResults() async throws {
        let result = try decodePage([voiceItem()])
        let gate = LearningReadGate()
        let model = StudyLearningRecordsViewModel()
        let task = Task { await model.activate(makeLoader { _ in await gate.wait(); return result }) }
        await gate.untilWaiting()
        model.deactivate()
        gate.resume()
        await task.value
        XCTAssertNil(model.page)
        XCTAssertFalse(model.failed)
    }

    func testDetailTranslationRefreshIsBoundedAndOriginalNeverPolls() async throws {
        let entry = try XCTUnwrap(try decodePage([voiceItem()]).items.first)
        var pendingBody = voiceBody(); pendingBody["translationPending"] = true
        let pending = try decodeVoice(pendingBody)
        var calls: [LocalizedContentView] = []
        var sleeps = 0
        var loader = makeLoader { _ in try self.decodePage([]) }
        loader.loadVoice = { _, view in calls.append(view); return pending }
        let model = StudyLearningRecordDetailViewModel(record: entry, sleep: { sleeps += 1 })
        await model.load(using: loader, view: .localized)
        XCTAssertEqual(calls.count, 4)
        XCTAssertEqual(sleeps, 3)
        XCTAssertTrue(model.record.translationPending)
        XCTAssertFalse(model.failed)
        await model.load(using: loader, view: .original)
        XCTAssertEqual(calls.count, 5)
        XCTAssertEqual(sleeps, 3)
        XCTAssertTrue(model.isShowingOriginal)
    }

    func testTranslatedDetailStopsPollingAndKeepsAssessmentEvidence() async throws {
        let entry = try XCTUnwrap(try decodePage([voiceItem()]).items.first)
        var translated = voiceBody()
        translated["question"] = "Synthetic translated question"
        translated["displayLanguage"] = "en"
        var calls = 0
        var loader = makeLoader { _ in try self.decodePage([]) }
        loader.loadVoice = { _, _ in calls += 1; return try self.decodeVoice(translated) }
        let model = StudyLearningRecordDetailViewModel(record: entry, sleep: { XCTFail("Completed translation must not poll") })
        await model.load(using: loader, view: .localized)
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(model.record.question, "Synthetic translated question")
        XCTAssertEqual(model.record.score, 85)
        XCTAssertEqual(model.record.voiceRecord?.questionTurnID, "1001")
    }

    func testFailedOriginalToggleKeepsDisplayedTranslationAndErrorIsRetryable() async throws {
        let entry = try XCTUnwrap(try decodePage([voiceItem()]).items.first)
        var loader = makeLoader { _ in try self.decodePage([]) }
        loader.loadVoice = { _, _ in throw URLError(.timedOut) }
        let model = StudyLearningRecordDetailViewModel(record: entry)
        await model.load(using: loader, view: .original)
        XCTAssertTrue(model.failed)
        XCTAssertFalse(model.isShowingOriginal)
        XCTAssertEqual(model.record, entry)
    }

    func testAppStateReadLeavesCurrentQuestionQuotaAndDraftsUntouched() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([questionItem(), voiceItem()]))
        defer { fixture.close() }
        let app = fixture.makeAppState()
        let loader = try XCTUnwrap(app.makeStudyLearningRecordsLoader(studyID: 41, scope: .node))
        let page = try await loader.loadPage(nil)
        XCTAssertEqual(page.items.count, 2)
        XCTAssertEqual(loader.cachedPage(nil), page)
        fixture.assertDraftsUnchanged(app)
        XCTAssertEqual(fixture.requests.map(\.httpMethod), ["GET"])
        XCTAssertTrue(fixture.storage.store.loadStudyRecords().isEmpty)
    }

    func testAppStateDropsPageAfterLocaleChangesWhileReadIsInFlight() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([voiceItem()]))
        defer { fixture.close() }
        let app = fixture.makeAppState()
        let loader = try XCTUnwrap(app.makeStudyLearningRecordsLoader(studyID: 41, scope: .node))
        fixture.onRequest = { _ in app.settings.appLanguage = .english }
        do { _ = try await loader.loadPage(nil); XCTFail("Stale locale must be discarded") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertFalse(loader.isCurrent())
        XCTAssertNil(loader.cachedPage(nil))
        XCTAssertEqual(try queryItems(try XCTUnwrap(fixture.requests.first))["tl"], "ko")
        fixture.assertDraftsUnchanged(app)
    }

    func testAppStateDropsPageAfterAccountReplacementWithoutARecordMutation() async throws {
        let fixture = try LearningHTTPFixture(page: pageObject([voiceItem()]))
        defer { fixture.close() }
        let app = fixture.makeAppState()
        let loader = try XCTUnwrap(app.makeStudyLearningRecordsLoader(studyID: 41, scope: .node))
        fixture.onRequest = { _ in app.communityProfile = LearningHTTPFixture.profile(id: 8) }
        do { _ = try await loader.loadPage(nil); XCTFail("Stale owner must be discarded") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertFalse(loader.isCurrent())
        XCTAssertNil(loader.cachedPage(nil))
        fixture.assertDraftsUnchanged(app)
    }

    func testAppStateMissingLoginHasNoLoaderAndMakesNoRequest() throws {
        let fixture = try LearningHTTPFixture(page: pageObject([]))
        defer { fixture.close() }
        let app = fixture.makeAppState()
        app.communityProfile = nil
        XCTAssertNil(app.makeStudyLearningRecordsLoader(studyID: 41, scope: .node))
        XCTAssertNil(app.makeStudyLearningRecordsLoader(studyID: 0, scope: .node))
        XCTAssertTrue(fixture.requests.isEmpty)
    }

    func testLearningRecordStringsCoverKoreanEnglishAndJapanese() {
        let strings = AppLanguage.allCases.map { AppStrings(language: $0) }
        XCTAssertEqual(Set(strings.map(\.studyLearningRecordsTitle)).count, 3)
        XCTAssertEqual(Set(strings.map(\.studyLearningSubtreeScope)).count, 3)
        XCTAssertEqual(Set(strings.map(\.studyLearningTranslationPending)).count, 3)
        XCTAssertEqual(Set(strings.map { $0.studyLearningPageNumber(2) }).count, 3)
    }

    private func makeLoader(
        studyID: Int = 41,
        load: @escaping @MainActor (String?) async throws -> BackendStudyLearningRecordsPage
    ) -> StudyLearningRecordsLoader {
        StudyLearningRecordsLoader(
            context: cacheKey(studyID: studyID).context, isCurrent: { true }, cachedPage: { _ in nil }, loadPage: load,
            loadVoice: { _, _ in throw StudyLearningRecordsError.unavailable },
            loadQuestion: { _, _ in throw StudyLearningRecordsError.unavailable }
        )
    }

    private func cacheKey(studyID: Int = 41, cursor: String? = nil) -> StudyLearningRecordsCacheKey {
        StudyLearningRecordsCacheKey(
            context: StudyLearningRecordsContext(identity: StudyLearningRecordsIdentity(
                lifetimeID: UUID(uuidString: "11111111-1111-4111-8111-111111111111")!,
                userID: 7, sessionGeneration: 1, backendGeneration: 0, languageCode: "ko"
            ), studyID: studyID, scope: .node), cursor: cursor, view: "localized"
        )
    }

    private func voiceBody(id: Int = 456, studyID: Int = 41) -> [String: Any] {
        [
            "id": String(id), "sessionId": "synthetic-session", "studyId": studyID, "parentStudyId": NSNull(),
            "topic": "합성 주제", "difficulty": 3, "createdAt": "2026-09-01T00:00:00Z", "kind": "TUTOR_QUESTION",
            "question": "합성 선생님 질문", "answer": "합성 학습자 답변", "score": 85,
            "strengths": ["합성 강점"], "improvements": ["합성 보완"], "depthSummary": "합성 탐색", "feedback": "합성 피드백",
            "questionTurnId": 1001, "answerTurnIds": [1002], "feedbackTurnIds": [1003],
            "sourceLanguage": "ko", "requestedLanguage": "ko", "displayLanguage": "ko", "translationPending": false
        ]
    }

    private func voiceItem(id: Int = 456, studyID: Int = 41) -> [String: Any] {
        ["id": "voice:\(id)", "source": "VOICE_TUTOR", "studyId": studyID, "createdAt": "2026-09-01T00:00:00Z",
         "questionRecord": NSNull(), "voiceRecord": voiceBody(id: id, studyID: studyID)]
    }

    private func questionItem(id: Int = 123) -> [String: Any] {
        ["id": "question:\(id)", "source": "QUESTION", "studyId": 41, "createdAt": "2026-09-01T00:00:00Z",
         "voiceRecord": NSNull(), "questionRecord": [
            "id": String(id), "studyId": 41, "topic": "합성 주제", "difficulty": 3,
            "question": ["question": "합성 일반 질문", "createdAt": "2026-09-01T00:00:00Z"],
            "answer": "합성 일반 답", "gradingResult": ["score": 90, "isCorrect": true, "feedback": "합성 피드백", "explanation": "합성 해설"],
            "isPublic": false
         ]]
    }

    private func pageObject(_ items: [[String: Any]], next: String? = nil, hasMore: Bool = false) -> [String: Any] {
        ["items": items, "nextCursor": next as Any? ?? NSNull(), "hasMore": hasMore, "limit": 30]
    }

    private func decodePage(_ items: [[String: Any]], next: String? = nil, hasMore: Bool = false) throws -> BackendStudyLearningRecordsPage {
        try decode(BackendStudyLearningRecordsPage.self, pageObject(items, next: next, hasMore: hasMore))
    }

    private func decodeVoice(_ object: [String: Any]) throws -> BackendVoiceStudyLearningRecord {
        try decode(BackendVoiceStudyLearningRecord.self, object)
    }

    private func decode<Value: Decodable>(_ type: Value.Type, _ object: [String: Any]) throws -> Value {
        try RemotePushBackendClient.makeDecoder().decode(type, from: JSONSerialization.data(withJSONObject: object))
    }

    private func queryItems(_ request: URLRequest) throws -> [String: String] {
        let url = try XCTUnwrap(request.url)
        let pairs = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        return Dictionary(uniqueKeysWithValues: pairs.map { ($0.name, $0.value ?? "") })
    }
}

@MainActor
private final class LearningStoreFixture {
    let suite = "StudyLearningRecordsTests.\(UUID().uuidString)"
    let defaults: UserDefaults
    let store: SettingsStore

    init() throws {
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false, preferredAppLanguageProvider: { .korean })
    }

    func close() { defaults.removePersistentDomain(forName: suite) }
}

@MainActor
private final class LearningHTTPFixture {
    let storage: LearningStoreFixture
    let host = "\(UUID().uuidString.lowercased()).learning-records.test"
    let client: RemotePushBackendClient
    let registration: RemotePushRegistration
    private let session: URLSession
    var response: [String: Any]
    var status = 200
    var requests: [URLRequest] = []
    var onRequest: ((URLRequest) -> Void)?
    private let question = QuestionItem(question: "합성 활성 질문", expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 100))

    init(page: [String: Any]) throws {
        storage = try LearningStoreFixture()
        response = page
        storage.store.saveSettings(.initial(for: .korean))
        storage.store.saveIsCommunitySignedIn(true)
        storage.store.saveQuestion(question)
        storage.store.saveLastAnswer("합성 활성 초안")
        storage.store.saveAnswerDraft("합성 개별 초안", recordID: "999")
        let payload = try JSONSerialization.data(withJSONObject: [
            "device_id": "learning-fixture-device", "user_id": 7, "is_anonymous": false,
            "status": "ACTIVE", "jti": "learning-fixture"
        ])
        let encoded = payload.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        registration = RemotePushRegistration(
            deviceID: "learning-fixture-device", clientSecret: "learning-fixture-secret", apnsToken: "",
            accessToken: "e30.\(encoded).fixture-signature", accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        )
        storage.store.saveRemotePushRegistration(registration)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [LearningURLProtocol.self]
        config.timeoutIntervalForRequest = 3
        config.timeoutIntervalForResource = 3
        session = URLSession(configuration: config)
        client = RemotePushBackendClient(baseURL: try XCTUnwrap(URL(string: "https://\(host)")), session: session)
        LearningURLProtocol.install({ [weak self] request in
            guard let self else { throw URLError(.cancelled) }
            guard request.httpMethod == "GET", request.url?.path.contains("/learning-records") == true else {
                XCTFail("Only the agreed read endpoints are allowed in this fixture")
                throw URLError(.unsupportedURL)
            }
            requests.append(request)
            onRequest?(request)
            return (try XCTUnwrap(HTTPURLResponse(url: try XCTUnwrap(request.url), statusCode: status, httpVersion: nil,
                headerFields: ["Content-Type": "application/json"])), try JSONSerialization.data(withJSONObject: response))
        }, host: host)
    }

    func makeAppState() -> AppState {
        let app = AppState(settingsStore: storage.store, remotePushBackendClient: client, appNotificationEventProvider: LearningNotificationEvents())
        app.communityProfile = Self.profile(id: 7)
        return app
    }

    static func profile(id: Int) -> CommunityUserProfile {
        CommunityUserProfile(id: id, displayName: "Synthetic learner", status: "ACTIVE", provider: "GOOGLE", bio: "", avatarURL: nil)
    }

    func assertDraftsUnchanged(_ app: AppState, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(app.currentQuestion, question, file: file, line: line)
        XCTAssertEqual(app.lastAnswer, "합성 활성 초안", file: file, line: line)
        XCTAssertEqual(storage.store.loadAnswerDraft(recordID: "999"), "합성 개별 초안", file: file, line: line)
        XCTAssertNil(app.gradingResult, file: file, line: line)
        XCTAssertNil(app.questionQuota, file: file, line: line)
    }

    func close() {
        session.invalidateAndCancel()
        LearningURLProtocol.remove(host: host)
        storage.close()
    }
}

@MainActor
private struct LearningNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable { AnyCancellable {} }
    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable { AnyCancellable {} }
}

@MainActor
private final class LearningReadGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    func wait() async {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            waiter?.resume()
            waiter = nil
        }
    }
    func untilWaiting() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func resume() { continuation?.resume(); continuation = nil }
}

private final class LearningTrafficCapture: @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [APITrafficLogEntry] = []
    func append(_ entry: APITrafficLogEntry) { lock.lock(); defer { lock.unlock() }; entries.append(entry) }
    func snapshot() -> [APITrafficLogEntry] { lock.lock(); defer { lock.unlock() }; return entries }
}

private final class LearningURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @MainActor @Sendable (URLRequest) throws -> (HTTPURLResponse, Data)
    private static let lock = NSLock()
    private nonisolated(unsafe) static var handlers: [String: Handler] = [:]
    static func install(_ handler: @escaping Handler, host: String) { lock.lock(); defer { lock.unlock() }; handlers[host] = handler }
    static func remove(host: String) { lock.lock(); defer { lock.unlock() }; handlers.removeValue(forKey: host) }
    private static func handler(host: String) -> Handler? { lock.lock(); defer { lock.unlock() }; return handlers[host] }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            do {
                guard let handler = Self.handler(host: request.url?.host ?? "") else { throw URLError(.unsupportedURL) }
                let (response, data) = try handler(request)
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch { client?.urlProtocol(self, didFailWithError: error) }
        }
    }
    override func stopLoading() {}
}
