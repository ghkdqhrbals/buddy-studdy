import Combine
import Foundation
import XCTest
@testable import StudyMate

/// Pure focus contracts and isolated, intercepted REST requests. Never starts a
/// call, microphone, player, provider request, recording, or real account mutation.
@MainActor
final class VoiceTutorDiscoveryTests: XCTestCase {
    func testDiscoveryStartDoesNotRequireALocalStudy() throws {
        XCTAssertTrue(VoiceTutorCallStartPolicy.canStart(status: try status()))
    }

    func testDiscoveryStartStillRequiresEligibilityQuotaAndNoActiveSession() throws {
        XCTAssertFalse(VoiceTutorCallStartPolicy.canStart(status: nil))
        XCTAssertFalse(VoiceTutorCallStartPolicy.canStart(status: try status(eligible: false)))
        XCTAssertFalse(VoiceTutorCallStartPolicy.canStart(status: try status(remaining: 0)))
        XCTAssertFalse(VoiceTutorCallStartPolicy.canStart(status: try status(active: true)))
        XCTAssertTrue(VoiceTutorCallStartPolicy.canStart(status: try status(remaining: 1)))
    }

    func testRootFocusUsesServerMetadataAndNoStudyRoomDefaults() throws {
        let parsed = try event(fields: focusFields())
        XCTAssertEqual(parsed, .studyFocused(try focus()))
    }

    func testChildFocusUsesTheExactParentAndDifficulty() throws {
        var fields = focusFields()
        fields["studyId"] = 43
        fields["parentStudyId"] = 42
        fields["difficulty"] = 8
        fields["revision"] = 2
        XCTAssertEqual(try event(fields: fields), .studyFocused(
            try focus(id: 43, parent: 42, difficulty: 8, revision: 2)
        ))
    }

    func testExplicitNullFocusClearsWhileAbsentFieldIsIgnored() throws {
        XCTAssertEqual(try parse(["focus": NSNull()]), .studyFocused(nil))
        XCTAssertEqual(try parse([:]), .ignored(type: focusEventType))
    }

    func testMissingFocusFieldsDoNotInventAStudyOrEpoch() throws {
        for field in ["studyId", "parentStudyId", "topic", "difficulty", "revision"] {
            var fields = focusFields()
            fields.removeValue(forKey: field)
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType), field)
        }
    }

    func testInvalidFocusContainersAndIDsAreIgnored() throws {
        let invalidContainers: [Any] = [true, 42, "topic", [], [:]]
        for value in invalidContainers {
            XCTAssertEqual(try parse(["focus": value]), .ignored(type: focusEventType))
        }
        let invalidIDs: [Any] = [NSNull(), true, false, 0, -1, 1.5, "42", [], [:]]
        for value in invalidIDs {
            var fields = focusFields()
            fields["studyId"] = value
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType))
        }
    }

    func testFocusParentMustBeNullOrADifferentPositiveNumericID() throws {
        let invalidParents: [Any] = [true, 0, -1, 42, 1.5, "41", [], [:]]
        for value in invalidParents {
            var fields = focusFields()
            fields["parentStudyId"] = value
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType))
        }
    }

    func testFocusTopicMustBeNonblankAndWithinPersistedUTF16Bound() throws {
        let invalidTopics: [Any] = [NSNull(), true, 42, "", " \n\t ", String(repeating: "가", count: 256),
                                    String(repeating: "😀", count: 128)]
        for value in invalidTopics {
            var fields = focusFields()
            fields["topic"] = value
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType))
        }
        var fields = focusFields()
        fields["topic"] = "  합성 주제 \n"
        XCTAssertEqual(try event(fields: fields), .studyFocused(try focus()))
        let boundary = String(repeating: "😀", count: 127) + "가"
        fields["topic"] = boundary
        XCTAssertEqual(try event(fields: fields), .studyFocused(try focus(topic: boundary)))
    }

    func testDifficultyRequiresTheExactOneThroughTenField() throws {
        let invalidLevels: [Any] = [NSNull(), true, 0, 11, -1, 3.5, "4"]
        for value in invalidLevels {
            var fields = focusFields()
            fields["difficulty"] = value
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType))
        }
        for level in [1, 10] {
            var fields = focusFields()
            fields["difficulty"] = level
            XCTAssertEqual(try event(fields: fields), .studyFocused(try focus(difficulty: level)))
        }
        var wrongField = focusFields()
        wrongField.removeValue(forKey: "difficulty")
        wrongField["difficultyLevel"] = 4
        XCTAssertEqual(try event(fields: wrongField), .ignored(type: focusEventType))
    }

    func testFocusRevisionMustBeAnExactPositiveInt64() throws {
        let invalid: [Any] = [NSNull(), true, false, 0, -1, 1.5, "1", [], [:]]
        for value in invalid {
            var fields = focusFields()
            fields["revision"] = value
            XCTAssertEqual(try event(fields: fields), .ignored(type: focusEventType))
        }
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text: """
        {"type":"\(focusEventType)","focus":{"studyId":42,"parentStudyId":null,"topic":"합성 주제","difficulty":4,"revision":9223372036854775808}}
        """), .ignored(type: focusEventType))
        var fields = focusFields()
        fields["revision"] = Int64.max
        XCTAssertEqual(try event(fields: fields), .studyFocused(try focus(revision: Int64.max)))
    }

    func testFocusStartsEmptyUntilAConfirmedEvent() throws {
        var state = VoiceTutorStudyFocusState()
        XCTAssertNil(state.focus)
        XCTAssertEqual(state.revision, 0)
        XCTAssertFalse(state.apply(try focus(), attemptID: UUID()))
        let attempt = UUID()
        state.beginAttempt(attempt)
        XCTAssertNil(state.focus)
        XCTAssertTrue(state.apply(try focus(), attemptID: attempt))
    }

    func testOnlyNewerFocusCanSwitchNodesOrUpdateMetadata() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        state.apply(try focus(), attemptID: attempt)
        let child = try focus(id: 43, parent: 42, topic: "합성 하위 주제", difficulty: 7, revision: 2)
        XCTAssertTrue(state.apply(child, attemptID: attempt))
        let renamed = try focus(id: 43, parent: 42, topic: "바뀐 합성 하위 주제", difficulty: 8, revision: 3)
        XCTAssertTrue(state.apply(renamed, attemptID: attempt))
        XCTAssertEqual(state.focus, renamed)
        XCTAssertFalse(state.apply(child, attemptID: attempt))
        XCTAssertEqual(state.focus, renamed)
    }

    func testSameEpochCannotReplaceIdentityOrMetadata() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        let original = try focus(revision: 4)
        state.apply(original, attemptID: attempt)
        for candidate in [original, try focus(id: 43, revision: 4),
                          try focus(topic: "다른 합성 주제", revision: 4),
                          try focus(difficulty: 8, revision: 4), try focus(revision: 3)] {
            XCTAssertFalse(state.apply(candidate, attemptID: attempt))
            XCTAssertEqual(state.focus, original)
        }
    }

    func testClearKeepsEpochAndRejectsDelayedDuplicates() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        state.apply(try focus(revision: 4), attemptID: attempt)
        XCTAssertTrue(state.apply(nil, attemptID: attempt))
        XCTAssertNil(state.focus)
        XCTAssertEqual(state.revision, 4)
        XCTAssertFalse(state.apply(try focus(revision: 4), attemptID: attempt))
        XCTAssertFalse(state.apply(nil, attemptID: attempt))
        XCTAssertTrue(state.apply(try focus(id: 43, revision: 5), attemptID: attempt))
    }

    func testConfirmedDeletionClearsCurrentFocusAndRejectsLateResurrection() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        state.apply(try focus(revision: 2), attemptID: attempt)
        state.remove(studyIDs: [42], attemptID: attempt)
        XCTAssertNil(state.focus)
        XCTAssertEqual(state.revision, 2)
        XCTAssertFalse(state.apply(try focus(revision: 2), attemptID: attempt))
        XCTAssertFalse(state.apply(try focus(revision: 3), attemptID: attempt))
        XCTAssertTrue(state.apply(try focus(id: 45, revision: 4), attemptID: attempt))
    }

    func testUnrelatedDeletionPreservesCurrentFocus() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        let original = try focus()
        state.apply(original, attemptID: attempt)
        state.remove(studyIDs: [44, 45], attemptID: attempt)
        XCTAssertEqual(state.focus, original)
        XCTAssertEqual(state.revision, 1)
    }

    func testDeletionOfParentClearsChildFocus() throws {
        let attempt = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(attempt)
        state.apply(try focus(id: 43, parent: 42), attemptID: attempt)
        state.remove(studyIDs: [42], attemptID: attempt)
        XCTAssertNil(state.focus)
        XCTAssertFalse(state.apply(try focus(id: 43, parent: 42, revision: 2), attemptID: attempt))
    }

    func testNewAttemptResetsFocusEpochAndDeletionTombstones() throws {
        let first = UUID(), second = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(first)
        state.apply(try focus(revision: 50), attemptID: first)
        state.remove(studyIDs: [42], attemptID: first)
        state.beginAttempt(second)
        XCTAssertNil(state.focus)
        XCTAssertEqual(state.revision, 0)
        XCTAssertTrue(state.apply(try focus(), attemptID: second))
    }

    func testOldAttemptCannotFocusClearOrDeleteCurrentTopic() throws {
        let first = UUID(), second = UUID()
        var state = VoiceTutorStudyFocusState()
        state.beginAttempt(first)
        state.apply(try focus(), attemptID: first)
        state.beginAttempt(second)
        let current = try focus(id: 45)
        state.apply(current, attemptID: second)
        XCTAssertFalse(state.apply(try focus(revision: 999), attemptID: first))
        XCTAssertFalse(state.apply(nil, attemptID: first))
        state.remove(studyIDs: [45], attemptID: first)
        XCTAssertEqual(state.focus, current)
    }

    func testDiscoveryLabelsAreLocalizedWithoutInventingATopic() {
        let expected: [AppLanguage: (String, String)] = [
            .korean: ("AI 선생님", "주제를 이야기해 주세요"),
            .english: ("AI tutor", "Tell me what you'd like to discuss"),
            .japanese: ("AI先生", "話したいテーマを教えてください")
        ]
        for language in AppLanguage.allCases {
            let strings = AppStrings(language: language)
            XCTAssertEqual(strings.voiceTutorDiscoveryTeacher, expected[language]?.0)
            XCTAssertEqual(strings.voiceTutorDiscoveryPrompt, expected[language]?.1)
        }
    }

    func testTopiclessHistoryDetailRetainsTranscriptAndRecordingMetadata() throws {
        let detail = try JSONDecoder().decode(BackendVoiceTutorSessionDetail.self, from: Data(#"{"sessionId":"synthetic-discovery","studyId":null,"topic":null,"difficulty":null,"state":"COMPLETED","resultStatus":"COMPLETED","result":{"status":"COMPLETED","summaryMarkdown":"합성 탐색 요약"},"transcriptTurns":[{"id":1,"role":"TUTOR","text":"합성 주제 질문"}],"recording":{"enabled":false,"consentRequired":true}}"#.utf8))
        XCTAssertNil(detail.studyId)
        XCTAssertEqual(detail.topic, "")
        XCTAssertNil(detail.difficultyLevel)
        XCTAssertEqual(detail.transcriptTurns.map(\.text), ["합성 주제 질문"])
        XCTAssertEqual(detail.result?.summaryMarkdown, "합성 탐색 요약")
        XCTAssertEqual(detail.recording?.enabled, false)
    }

    func testHistoryPageSupportsOldSelectedAndNewDiscoverySessionsTogether() throws {
        let page = try JSONDecoder().decode(BackendVoiceTutorSessionPage.self, from: Data(#"{"items":[{"sessionId":"old","studyId":42,"topic":"합성 기존 주제","difficulty":4},{"sessionId":"new","studyId":null,"topic":null,"difficulty":null}],"nextCursor":"synthetic-cursor"}"#.utf8))
        XCTAssertEqual(page.sessions.map(\.studyId), [42, nil])
        XCTAssertEqual(page.sessions.map(\.topic), ["합성 기존 주제", ""])
        XCTAssertEqual(page.sessions.map(\.difficultyLevel), [4, nil])
        XCTAssertEqual(page.nextCursor, "synthetic-cursor")
    }

    func testTopiclessPOSTPreservesLanguageVoiceAndActiveAnswerDrafts() async throws {
        for language in AppLanguage.allCases {
            let fixture = try VoiceDiscoveryAppFixture(language: language)
            defer { fixture.close() }
            XCTAssertTrue(fixture.appState.voiceTutorStudies.isEmpty)
            let connection = try await fixture.appState.createVoiceTutorConnection()
            let body = try XCTUnwrap(fixture.creationBodies.first)
            XCTAssertFalse(body.keys.contains("studyId"))
            XCTAssertEqual(body["language"] as? String, language.backendCode)
            XCTAssertEqual(body["voice"] as? String, "sage")
            XCTAssertEqual(body["recordingConsent"] as? Bool, false)
            XCTAssertNotNil(connection.webRTC)
            XCTAssertTrue(connection.isCurrent())
            fixture.assertDraftsUnchanged()
        }
    }

    func testTopiclessRecordingConsentIsExplicitAndIndependent() async throws {
        for consent in [false, true] {
            let fixture = try VoiceDiscoveryAppFixture()
            defer { fixture.close() }
            _ = try await fixture.appState.createVoiceTutorConnection(recordingConsent: consent)
            let body = try XCTUnwrap(fixture.creationBodies.first)
            XCTAssertNil(body["studyId"])
            XCTAssertEqual(body["recordingConsent"] as? Bool, consent)
            XCTAssertEqual(body["recordingConsentVersion"] as? String, consent ? "voice-recording-v1" : nil)
            fixture.assertDraftsUnchanged()
        }
    }

    func testTopiclessIdentityRecoveryKeepsOmittedStudyAndIdempotency() async throws {
        let fixture = try VoiceDiscoveryAppFixture()
        defer { fixture.close() }
        fixture.expireFirstCreate = true
        _ = try await fixture.appState.createVoiceTutorConnection()
        XCTAssertEqual(fixture.creationBodies.count, 2)
        XCTAssertTrue(fixture.creationBodies.allSatisfy { !$0.keys.contains("studyId") })
        let creates = fixture.requests.filter { $0.url?.path == "/api/v1/voice-tutor/sessions" }
        let keys = creates.compactMap { $0.value(forHTTPHeaderField: "Idempotency-Key") }
        XCTAssertEqual(keys.count, 2)
        XCTAssertEqual(Set(keys).count, 1)
        fixture.assertDraftsUnchanged()
    }

    func testDiscoveryDefaultsCanOmitTheStudyArgument() async throws {
        let fixture = try VoiceDiscoveryAppFixture()
        defer { fixture.close() }
        _ = try await fixture.appState.createVoiceTutorConnection()
        XCTAssertEqual(fixture.creationBodies.count, 1)
        XCTAssertNil(fixture.creationBodies.first?["studyId"])
        XCTAssertEqual(fixture.requests.map { $0.url?.path }, ["/api/v1/voice-tutor/sessions"])
        fixture.assertDraftsUnchanged()
    }

    private let focusEventType = "buddystudy.voice.study.focused"

    private func focus(id: Int = 42, parent: Int? = nil, topic: String = "합성 주제",
                       difficulty: Int = 4, revision: Int64 = 1) throws -> VoiceTutorStudyFocus {
        try XCTUnwrap(VoiceTutorStudyFocus(studyID: id, parentStudyID: parent, topic: topic,
                                         difficulty: difficulty, revision: revision))
    }

    private func focusFields() -> [String: Any] {
        ["studyId": 42, "parentStudyId": NSNull(), "topic": "합성 주제", "difficulty": 4, "revision": 1]
    }

    private func event(fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try parse(["focus": fields])
    }

    private func parse(_ payload: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        var object = payload
        object["type"] = focusEventType
        return try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: object))
    }

    private func status(eligible: Bool = true, remaining: Int = 3_600,
                        active: Bool = false) throws -> BackendVoiceTutorStatus {
        var object: [String: Any] = [
            "eligible": eligible, "quota": ["limitSeconds": 3_600, "usedSeconds": 0,
                                            "reservedSeconds": 0, "remainingSeconds": remaining]
        ]
        if active { object["activeSession"] = ["sessionId": "synthetic-active", "state": "ACTIVE"] }
        return try JSONDecoder().decode(BackendVoiceTutorStatus.self, from: JSONSerialization.data(withJSONObject: object))
    }
}

@MainActor
private final class VoiceDiscoveryAppFixture {
    let appState: AppState
    let store: SettingsStore
    var requests: [URLRequest] = []
    var creationBodies: [[String: Any]] = []
    var expireFirstCreate = false
    private let suiteName = "VoiceTutorDiscoveryTests-\(UUID().uuidString)"
    private let defaults: UserDefaults
    private let session: URLSession
    private let host: String
    private let registration: RemotePushRegistration
    private let originalSettings: StudySettings
    private let originalQuestion = QuestionItem(
        question: "합성 보존 질문", expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 100)
    )

    init(language: AppLanguage = .korean) throws {
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        originalSettings = StudySettings(
            topic: "기존 합성 선택", difficulty: Difficulty(level: 6), appLanguage: language,
            language: language.studyLanguage, voiceTutorVoice: .sage, customPrompt: "합성 설정 보존",
            intervalMinutes: 27, isQuestionPublic: false,
            studyCategories: [StudyCategory(id: "42", title: "기존 합성 선택", difficulty: .level6)],
            selectedStudyCategoryID: "42"
        )
        store.saveSettings(originalSettings)
        store.saveIsCommunitySignedIn(true)
        store.saveQuestion(originalQuestion)
        store.saveLastAnswer("합성 보존 답안")
        store.saveAnswerDraft("합성 기록별 답안", recordID: "synthetic-discovery-draft")
        let payload = try JSONSerialization.data(withJSONObject: [
            "device_id": "discovery-fixture-device", "user_id": 7, "is_anonymous": false,
            "status": "ACTIVE", "jti": "synthetic-discovery"
        ], options: [.sortedKeys])
        let encoded = payload.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        registration = RemotePushRegistration(
            deviceID: "discovery-fixture-device", clientSecret: "discovery-fixture-secret", apnsToken: "",
            accessToken: "e30.\(encoded).fixture-signature",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        )
        store.saveRemotePushRegistration(registration)
        host = "\(UUID().uuidString.lowercased()).voice-discovery.test"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VoiceDiscoveryURLProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        session = URLSession(configuration: configuration)
        appState = AppState(
            settingsStore: store,
            remotePushBackendClient: RemotePushBackendClient(baseURL: URL(string: "https://\(host)")!, session: session),
            appNotificationEventProvider: VoiceDiscoveryNotificationEvents()
        )
        appState.communityProfile = CommunityUserProfile(
            id: 7, displayName: "Synthetic Discovery", status: "ACTIVE", provider: "GOOGLE", bio: "", avatarURL: nil
        )
        VoiceDiscoveryURLProtocol.install({ [weak self] request in
            guard let self else { throw URLError(.cancelled) }
            return try self.respond(to: request)
        }, host: host)
    }

    func assertDraftsUnchanged(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(appState.currentQuestion, originalQuestion, file: file, line: line)
        XCTAssertEqual(appState.lastAnswer, "합성 보존 답안", file: file, line: line)
        XCTAssertEqual(store.loadLastAnswer(), "합성 보존 답안", file: file, line: line)
        XCTAssertEqual(store.loadAnswerDraft(recordID: "synthetic-discovery-draft"), "합성 기록별 답안", file: file, line: line)
        XCTAssertEqual(appState.settings.selectedStudyCategoryID, originalSettings.selectedStudyCategoryID, file: file, line: line)
        XCTAssertEqual(appState.settings.topic, originalSettings.topic, file: file, line: line)
        XCTAssertEqual(appState.settings.difficulty, originalSettings.difficulty, file: file, line: line)
        XCTAssertEqual(appState.settings.customPrompt, originalSettings.customPrompt, file: file, line: line)
        XCTAssertNil(appState.gradingResult, file: file, line: line)
        XCTAssertNil(appState.questionQuota, file: file, line: line)
    }

    func close() {
        session.invalidateAndCancel()
        VoiceDiscoveryURLProtocol.remove(host: host)
        // Delete only this fixture's random namespace, never standard defaults or recordings.
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func respond(to request: URLRequest) throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        guard request.httpMethod == "POST" else {
            XCTFail("Unexpected synthetic discovery method")
            throw URLError(.unsupportedURL)
        }
        let body: String
        var status = 200
        switch request.url?.path {
        case "/api/v1/auth/token":
            body = """
            {"accessToken":"\(registration.accessToken!)","accessTokenExpiresAt":"2100-01-01T00:00:00Z"}
            """
        case "/api/v1/voice-tutor/sessions":
            creationBodies.append(try Self.requestBody(request))
            if expireFirstCreate, creationBodies.count == 1 {
                status = 401
                body = #"{"code":"AUTH_INVALID_ACCESS_TOKEN","message":"Synthetic expired token"}"#
            } else {
                let id = UUID().uuidString
                body = """
                {"sessionId":"\(id)","state":"READY","studyId":null,"topic":null,"realtimeTransport":"WEBRTC",\
                "sdpUrl":"/api/v1/voice-tutor/sessions/\(id)/webrtc",\
                "controlWebsocketUrl":"/api/v1/voice-tutor/sessions/\(id)/control"}
                """
            }
        default:
            XCTFail("Discovery fixtures must not request studies, provider media, quotas or drafts")
            throw URLError(.unsupportedURL)
        }
        return (try XCTUnwrap(HTTPURLResponse(
            url: try XCTUnwrap(request.url), statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )), Data(body.utf8))
    }

    private static func requestBody(_ request: URLRequest) throws -> [String: Any] {
        let data: Data
        if let body = request.httpBody {
            data = body
        } else {
            let stream = try XCTUnwrap(request.httpBodyStream)
            stream.open()
            defer { stream.close() }
            var collected = Data(), buffer = [UInt8](repeating: 0, count: 1_024)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                guard count >= 0 else { throw stream.streamError ?? URLError(.cannotDecodeContentData) }
                if count == 0 { break }
                collected.append(contentsOf: buffer.prefix(count))
                guard collected.count <= 8_192 else { throw URLError(.dataLengthExceedsMaximum) }
            }
            data = collected
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

@MainActor
private struct VoiceDiscoveryNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }
    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }
}

private final class VoiceDiscoveryURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @MainActor @Sendable (URLRequest) throws -> (HTTPURLResponse, Data)
    private static let lock = NSLock()
    private nonisolated(unsafe) static var handlers: [String: Handler] = [:]

    static func install(_ handler: @escaping Handler, host: String) {
        lock.lock()
        defer { lock.unlock() }
        handlers[host] = handler
    }
    static func remove(host: String) {
        lock.lock()
        defer { lock.unlock() }
        handlers.removeValue(forKey: host)
    }
    private static func handler(host: String) -> Handler? {
        lock.lock()
        defer { lock.unlock() }
        return handlers[host]
    }

    // This fixture owns its URLSession. Unknown hosts never fall through to the network.
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let reference = VoiceDiscoveryProtocolReference(self)
        Task { @MainActor in
            let protocolInstance = reference.value
            do {
                guard let handler = Self.handler(host: protocolInstance.request.url?.host ?? "") else { throw URLError(.unsupportedURL) }
                let (response, data) = try handler(protocolInstance.request)
                protocolInstance.client?.urlProtocol(protocolInstance, didReceive: response, cacheStoragePolicy: .notAllowed)
                protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
                protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
            } catch {
                protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: error)
            }
        }
    }
    override func stopLoading() {}
}

/// Same explicit crossing as the existing contract fixtures: callbacks and
/// handlers are MainActor-only, while the registry is separately lock-protected.
private final class VoiceDiscoveryProtocolReference: @unchecked Sendable {
    let value: VoiceDiscoveryURLProtocol
    init(_ value: VoiceDiscoveryURLProtocol) { self.value = value }
}
