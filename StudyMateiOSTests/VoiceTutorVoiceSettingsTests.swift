import Combine
import Foundation
import XCTest
@testable import StudyMate

/// Isolated settings and intercepted REST requests only. No provider, WebRTC,
/// microphone, recording, account purge, or real voice/question quota operations.
@MainActor
final class VoiceTutorVoiceSettingsTests: XCTestCase {
    func testDefaultSelectionPreservesServerDefaultInEveryAppLanguage() {
        XCTAssertEqual(StudySettings.default.voiceTutorVoice, .serverDefault)
        for language in AppLanguage.allCases {
            let settings = StudySettings.initial(for: language)
            XCTAssertEqual(settings.voiceTutorVoice, .serverDefault)
            XCTAssertNil(settings.voiceTutorVoice.apiValue)
        }
    }

    func testVoiceChoicesMatchRealtimeAllowlistWithoutTTSOnlyVoices() {
        let expected: Set<String> = [
            "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"
        ]
        XCTAssertEqual(Set(VoiceTutorVoice.allCases.compactMap(\.apiValue)), expected)
        XCTAssertEqual(VoiceTutorVoice.allCases.count, expected.count + 1)
        XCTAssertEqual(Set(VoiceTutorVoice.allCases.map(\.id)).count, VoiceTutorVoice.allCases.count)
        XCTAssertNil(VoiceTutorVoice(rawValue: "onyx"))
        XCTAssertNil(VoiceTutorVoice(rawValue: "nova"))
        XCTAssertNil(VoiceTutorVoice(rawValue: "fable"))
    }

    func testLegacySettingsWithoutVoiceRetainAllOtherValues() throws {
        let expected = makeSettings(voice: .serverDefault)
        var object = try settingsObject(expected)
        object.removeValue(forKey: "voiceTutorVoice")
        XCTAssertEqual(try decodeSettings(object), expected)
    }

    func testUnknownOrMalformedVoiceDoesNotDiscardOtherSavedSettings() throws {
        let expected = makeSettings(voice: .serverDefault)
        let invalidValues: [Any] = [
            "future-voice", "", "Marin", "marin ", 42, true, NSNull(), [String](), ["voice": "marin"]
        ]
        for value in invalidValues {
            var object = try settingsObject(expected)
            object["voiceTutorVoice"] = value
            XCTAssertEqual(try decodeSettings(object), expected)
        }
    }

    func testEveryVoiceSurvivesSettingsCodableRoundTrip() throws {
        for voice in VoiceTutorVoice.allCases {
            let settings = makeSettings(voice: voice)
            let data = try JSONEncoder().encode(settings)
            XCTAssertEqual(try JSONDecoder().decode(StudySettings.self, from: data), settings)
            XCTAssertEqual(try settingsObject(settings)["voiceTutorVoice"] as? String, voice.rawValue)
        }
    }

    func testLocalSettingsUseCasePersistsEveryVoiceWithoutChangingAnswerDrafts() throws {
        let fixture = try VoiceSettingsStoreFixture()
        defer { fixture.close() }
        fixture.store.saveLastAnswer("합성 답안 초안")
        fixture.store.saveAnswerDraft("합성 기록별 초안", recordID: "synthetic-draft")
        let useCase = LocalStudySettingsUseCase(
            repository: SettingsStoreLocalStudySettingsRepository(settingsStore: fixture.store)
        )

        for voice in VoiceTutorVoice.allCases {
            let settings = makeSettings(voice: voice)
            useCase.saveSettings(settings)
            XCTAssertEqual(useCase.loadSettings().settings, settings)
            XCTAssertEqual(fixture.store.loadLastAnswer(), "합성 답안 초안")
            XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "synthetic-draft"), "합성 기록별 초안")
        }
    }

    func testSettingsStoreLoadsInvalidVoiceAsDefaultRatherThanResettingSettings() throws {
        let fixture = try VoiceSettingsStoreFixture()
        defer { fixture.close() }
        let expected = makeSettings(voice: .serverDefault)
        var object = try settingsObject(expected)
        object["voiceTutorVoice"] = ["unsupported": true]
        fixture.defaults.set(try JSONSerialization.data(withJSONObject: object), forKey: "studySettings")
        XCTAssertEqual(fixture.store.loadSettings(), expected)
    }

    func testCategorySelectionAndPrivacyCopiesKeepVoicePreference() {
        let first = StudyCategory(id: "41", title: "합성 첫 주제", createdAt: Date(timeIntervalSince1970: 0))
        let second = StudyCategory(id: "42", title: "합성 둘째 주제", createdAt: Date(timeIntervalSince1970: 0))
        for voice in VoiceTutorVoice.allCases {
            let settings = makeSettings(voice: voice)
                .withStudyCategories([first, second], selectedID: first.id)
            XCTAssertEqual(settings.voiceTutorVoice, voice)
            XCTAssertEqual(settings.withSelectedCategoryID(second.id).voiceTutorVoice, voice)
            XCTAssertEqual(settings.withSelectedCategoryID(nil).voiceTutorVoice, voice)
            XCTAssertEqual(settings.withQuestionPrivacy(true).voiceTutorVoice, voice)
            XCTAssertEqual(settings.withQuestionPrivacy(false).voiceTutorVoice, voice)
            XCTAssertEqual(settings.withStudyCategories([]).voiceTutorVoice, voice)
        }
    }

    func testBackendSettingsRefreshKeepsLocalVoiceWhileApplyingServerPreferences() throws {
        let backend = try RemotePushBackendClient.makeDecoder().decode(BackendStudySettings.self, from: Data("""
        {"topic":"", "difficultyLevel":5, "intervalMinutes":31, "appLanguage":"en", "enabled":false}
        """.utf8))
        for voice in VoiceTutorVoice.allCases {
            let local = makeSettings(voice: voice)
            let refreshed = backend.studySettings(fallback: local)
            XCTAssertEqual(refreshed.voiceTutorVoice, voice)
            XCTAssertEqual(refreshed.intervalMinutes, 31)
            XCTAssertEqual(refreshed.appLanguage, .english)
            XCTAssertEqual(refreshed.topic, local.topic)
        }
    }

    func testVoiceSettingLabelsAreLocalizedAndNamesAreStable() {
        let languages: [AppLanguage] = [.korean, .english, .japanese]
        let titles = languages.map { AppStrings(language: $0).voiceTutorVoiceSetting }
        let help = languages.map { AppStrings(language: $0).voiceTutorVoiceSettingHelp }
        let defaults = languages.map { AppStrings(language: $0).voiceTutorVoiceName(.serverDefault) }
        XCTAssertEqual(Set(titles).count, 3)
        XCTAssertEqual(Set(help).count, 3)
        XCTAssertEqual(defaults, ["기본", "Default", "デフォルト"])
        for language in languages {
            let strings = AppStrings(language: language)
            XCTAssertFalse(strings.voiceTutorVoiceSetting.isEmpty)
            XCTAssertFalse(strings.voiceTutorVoiceSettingHelp.isEmpty)
            XCTAssertEqual(strings.voiceTutorVoiceName(.marin), "Marin")
            XCTAssertEqual(strings.voiceTutorVoiceName(.cedar), "Cedar")
        }
    }

    func testVoiceEditingIsDirtyCancelableAndDoesNotStartRequests() throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .sage))
        defer { fixture.close() }
        let original = fixture.appState.settings
        fixture.appState.beginSettingsEditing()
        XCTAssertFalse(fixture.appState.hasUnsavedSettingsChanges)

        fixture.appState.setDraftVoiceTutorVoice(.cedar)
        XCTAssertTrue(fixture.appState.hasUnsavedSettingsChanges)
        XCTAssertEqual(fixture.appState.draftSettings.voiceTutorVoice, .cedar)
        XCTAssertEqual(fixture.appState.settings, original)
        XCTAssertEqual(fixture.storage.store.loadSettings(), original)
        XCTAssertTrue(fixture.requests.isEmpty)
        fixture.assertAnswerDraftsUnchanged()

        fixture.appState.cancelSettingsEditing()
        XCTAssertEqual(fixture.appState.settings, original)
        XCTAssertEqual(fixture.appState.draftSettings, original)
        XCTAssertFalse(fixture.appState.hasUnsavedSettingsChanges)
        XCTAssertTrue(fixture.requests.isEmpty)
    }

    func testLanguageEditingDoesNotResetSelectedVoice() throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .ballad))
        defer { fixture.close() }
        fixture.appState.beginSettingsEditing()
        for language in AppLanguage.allCases {
            fixture.appState.updateDraftAppLanguage(language)
            XCTAssertEqual(fixture.appState.draftSettings.voiceTutorVoice, .ballad)
        }
        fixture.appState.cancelSettingsEditing()
        XCTAssertEqual(fixture.appState.settings.voiceTutorVoice, .ballad)
        fixture.assertAnswerDraftsUnchanged()
    }

    func testSavedVoiceTravelsThroughAppStateUseCaseRepositoryAndPOSTBody() async throws {
        for voice in VoiceTutorVoice.allCases where voice != .serverDefault {
            let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: voice))
            defer { fixture.close() }
            let connection = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
            let body = try XCTUnwrap(fixture.creationBodies.first)
            XCTAssertEqual(fixture.creationBodies.count, 1)
            XCTAssertEqual(body["voice"] as? String, voice.apiValue)
            XCTAssertEqual(body["studyId"] as? Int, 42)
            XCTAssertEqual(body["language"] as? String, "ja")
            XCTAssertEqual(body["recordingConsent"] as? Bool, false)
            XCTAssertNil(body["recordingConsentVersion"])
            XCTAssertEqual(fixture.requests.map { $0.url?.path }, ["/api/v1/voice-tutor/sessions"])
            XCTAssertNotNil(fixture.requests.first?.value(forHTTPHeaderField: "Idempotency-Key"))
            XCTAssertNotNil(connection.webRTC)
            XCTAssertTrue(connection.isCurrent())
            fixture.assertAnswerDraftsUnchanged()
        }
    }

    func testDefaultVoiceOmitsPOSTFieldInsteadOfPinningCurrentServerVoice() async throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .serverDefault))
        defer { fixture.close() }
        _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
        let body = try XCTUnwrap(fixture.creationBodies.first)
        XCTAssertFalse(body.keys.contains("voice"), "Default must remain a server-owned choice, not an encoded null or literal default")
    }

    func testUnsavedVoiceDoesNotAffectNewCall() async throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .ash))
        defer { fixture.close() }
        fixture.appState.beginSettingsEditing()
        fixture.appState.setDraftVoiceTutorVoice(.coral)
        _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
        XCTAssertEqual(fixture.creationBodies.first?["voice"] as? String, "ash")
        XCTAssertEqual(fixture.appState.draftSettings.voiceTutorVoice, .coral)
        XCTAssertEqual(fixture.storage.store.loadSettings().voiceTutorVoice, .ash)
        fixture.appState.cancelSettingsEditing()
        fixture.assertAnswerDraftsUnchanged()
    }

    func testVoiceIsCapturedBeforeRegistrationBootstrapSuspends() async throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .echo), requiresBootstrap: true)
        defer { fixture.close() }
        fixture.onTokenRequest = { [weak fixture] in
            fixture?.appState.settings.voiceTutorVoice = .cedar
        }
        _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
        XCTAssertEqual(fixture.creationBodies.first?["voice"] as? String, "echo")
        XCTAssertEqual(fixture.appState.settings.voiceTutorVoice, .cedar)
        XCTAssertEqual(fixture.requests.map { $0.url?.path }, [
            "/api/v1/auth/token", "/api/v1/voice-tutor/sessions"
        ])
    }

    func testIdentityRecoveryKeepsVoiceAndIdempotencyUntilNextCall() async throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .verse))
        defer { fixture.close() }
        fixture.expireFirstCreate = true
        fixture.onTokenRequest = { [weak fixture] in
            fixture?.appState.settings.voiceTutorVoice = .marin
        }
        let firstConnection = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
        XCTAssertEqual(fixture.creationBodies.count, 2)
        XCTAssertEqual(fixture.creationBodies.compactMap { $0["voice"] as? String }, ["verse", "verse"])
        let createRequests = fixture.requests.filter { $0.url?.path == "/api/v1/voice-tutor/sessions" }
        XCTAssertEqual(createRequests.first?.value(forHTTPHeaderField: "Idempotency-Key"),
                       createRequests.last?.value(forHTTPHeaderField: "Idempotency-Key"))

        _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
        XCTAssertEqual(fixture.creationBodies.last?["voice"] as? String, "marin")
        XCTAssertTrue(firstConnection.isCurrent(), "Choosing the next voice does not invalidate an existing call")
        XCTAssertFalse(fixture.requests.contains { $0.url?.path.hasSuffix("/end") == true })
        fixture.assertAnswerDraftsUnchanged()
    }

    func testExplicitVoiceAndRecordingConsentRemainIndependent() async throws {
        let fixture = try VoiceSettingsAppFixture(settings: makeSettings(voice: .sage))
        defer { fixture.close() }
        _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42, voice: "shimmer", recordingConsent: true)
        let body = try XCTUnwrap(fixture.creationBodies.first)
        XCTAssertEqual(body["voice"] as? String, "shimmer")
        XCTAssertEqual(body["recordingConsent"] as? Bool, true)
        XCTAssertEqual(body["recordingConsentVersion"] as? String, "voice-recording-v1")
        XCTAssertEqual(fixture.appState.settings.voiceTutorVoice, .sage)
        XCTAssertNil(fixture.appState.questionQuota)
        fixture.assertAnswerDraftsUnchanged()
    }

    private func makeSettings(voice: VoiceTutorVoice) -> StudySettings {
        StudySettings(
            topic: "합성 주제",
            difficulty: Difficulty(level: 4),
            appLanguage: .japanese,
            language: .japanese,
            voiceTutorVoice: voice,
            customPrompt: "합성 설정 보존",
            intervalMinutes: 27,
            isQuestionPublic: false
        )
    }

    private func settingsObject(_ settings: StudySettings) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(settings)) as? [String: Any])
    }

    private func decodeSettings(_ object: [String: Any]) throws -> StudySettings {
        try JSONDecoder().decode(StudySettings.self, from: JSONSerialization.data(withJSONObject: object))
    }
}

private final class VoiceSettingsStoreFixture {
    let defaults: UserDefaults
    let store: SettingsStore
    private let suiteName = "VoiceTutorVoiceSettingsTests-\(UUID().uuidString)"

    init() throws {
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
    }

    func close() {
        // Only this fixture's newly created namespace; never standard defaults or account data.
        defaults.removePersistentDomain(forName: suiteName)
    }
}

@MainActor
private final class VoiceSettingsAppFixture {
    let storage: VoiceSettingsStoreFixture
    let appState: AppState
    let registration: RemotePushRegistration
    var requests: [URLRequest] = []
    var creationBodies: [[String: Any]] = []
    var expireFirstCreate = false
    var onTokenRequest: (() -> Void)?
    private let host: String
    private let session: URLSession
    private let question = QuestionItem(
        question: "합성 보존 질문", expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 100)
    )

    init(settings: StudySettings, requiresBootstrap: Bool = false) throws {
        storage = try VoiceSettingsStoreFixture()
        storage.store.saveSettings(settings)
        storage.store.saveIsCommunitySignedIn(true)
        storage.store.saveQuestion(question)
        storage.store.saveLastAnswer("합성 보존 초안")
        storage.store.saveAnswerDraft("합성 기록별 보존 초안", recordID: "synthetic-current-record")
        let payload = try JSONSerialization.data(withJSONObject: [
            "device_id": "voice-settings-fixture-device", "user_id": 7,
            "is_anonymous": false, "status": "ACTIVE", "jti": "voice-settings-fixture"
        ], options: [.sortedKeys])
        let encoded = payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        registration = RemotePushRegistration(
            deviceID: "voice-settings-fixture-device",
            clientSecret: "voice-settings-fixture-secret",
            apnsToken: "",
            accessToken: "e30.\(encoded).fixture-signature",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        )
        storage.store.saveRemotePushRegistration(requiresBootstrap
            ? RemotePushRegistration(deviceID: registration.deviceID, clientSecret: registration.clientSecret, apnsToken: "")
            : registration)
        host = "\(UUID().uuidString.lowercased()).voice-settings.test"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VoiceSettingsURLProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        session = URLSession(configuration: configuration)
        appState = AppState(
            settingsStore: storage.store,
            remotePushBackendClient: RemotePushBackendClient(baseURL: URL(string: "https://\(host)")!, session: session),
            appNotificationEventProvider: VoiceSettingsNotificationEvents()
        )
        appState.communityProfile = CommunityUserProfile(
            id: 7, displayName: "Synthetic Voice Settings", status: "ACTIVE", provider: "GOOGLE", bio: "", avatarURL: nil
        )
        VoiceSettingsURLProtocol.install({ [weak self] request in
            guard let self else { throw URLError(.cancelled) }
            return try self.respond(to: request)
        }, host: host)
    }

    func assertAnswerDraftsUnchanged(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(appState.currentQuestion, question, file: file, line: line)
        XCTAssertEqual(appState.lastAnswer, "합성 보존 초안", file: file, line: line)
        XCTAssertEqual(storage.store.loadLastAnswer(), "합성 보존 초안", file: file, line: line)
        XCTAssertEqual(storage.store.loadAnswerDraft(recordID: "synthetic-current-record"), "합성 기록별 보존 초안", file: file, line: line)
        XCTAssertNil(appState.gradingResult, file: file, line: line)
        XCTAssertNil(appState.questionQuota, file: file, line: line)
    }

    func close() {
        onTokenRequest = nil
        session.invalidateAndCancel()
        VoiceSettingsURLProtocol.remove(host: host)
        storage.close()
    }

    private func respond(to request: URLRequest) throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        XCTAssertEqual(request.httpMethod, "POST")
        let body: String
        var status = 200
        switch request.url?.path {
        case "/api/v1/auth/token":
            onTokenRequest?()
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
                {"sessionId":"\(id)","state":"READY","realtimeTransport":"WEBRTC",\
                "sdpUrl":"/api/v1/voice-tutor/sessions/\(id)/webrtc",\
                "controlWebsocketUrl":"/api/v1/voice-tutor/sessions/\(id)/control"}
                """
            }
        default:
            XCTFail("Voice preference tests must not call other endpoints")
            throw URLError(.unsupportedURL)
        }
        return (try XCTUnwrap(HTTPURLResponse(
            url: try XCTUnwrap(request.url), statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )), Data(body.utf8))
    }

    private static func requestBody(_ request: URLRequest) throws -> [String: Any] {
        let data: Data
        if let direct = request.httpBody {
            data = direct
        } else {
            let stream = try XCTUnwrap(request.httpBodyStream)
            stream.open()
            defer { stream.close() }
            var collected = Data()
            var buffer = [UInt8](repeating: 0, count: 1_024)
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
private struct VoiceSettingsNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }

    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }
}

private final class VoiceSettingsURLProtocol: URLProtocol, @unchecked Sendable {
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

    // Intercept every request from this fixture's private URLSession; unknown hosts fail closed.
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Task { @MainActor in
            do {
                guard let handler = Self.handler(host: request.url?.host ?? "") else {
                    throw URLError(.unsupportedURL)
                }
                let (response, data) = try handler(request)
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {}
}
