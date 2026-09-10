import XCTest
#if os(iOS)
import Combine
import SwiftUI
import UIKit
#endif
@testable import StudyMate

final class FixedProfileAvatarTests: XCTestCase {
    func testProfileUsesSimpleLocalPixelAvatarPresets() {
        XCTAssertEqual(BuddyStudyAvatar.symbolName, "pixel-fox")
        XCTAssertEqual(ProfileAvatarOption.canonicalName(for: "pixel-cat-laptop"), "pixel-cat")
        XCTAssertEqual(ProfileAvatarOption.canonicalName(for: "pixel-dog-corgi-reader"), "pixel-explorer")
        XCTAssertEqual(ProfileAvatarOption.canonicalName(for: "pixel-robot"), "pixel-tutor-bot")
        XCTAssertEqual(ProfileAvatarOption.all.count, 13)
        XCTAssertTrue(ProfileAvatarOption.all.allSatisfy { $0.hasPrefix("pixel-") })
    }

    func testLoadingAvatarCacheRemovesLegacyProfilePhotoData() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        store.saveProfileAvatarImageData(Data([0x01, 0x02, 0x03]))
        let useCase = CommunityProfileCacheUseCase(
            repository: SettingsStoreCommunityProfileCacheRepository(settingsStore: store)
        )

        let cache = useCase.loadAvatarCache { "avatar-color-sage" }

        XCTAssertNil(cache.imageData)
        XCTAssertNil(store.loadProfileAvatarImageData())
    }
}

#if os(iOS)
/// Native controls must have their prepared content in the very first layout.
/// Every account, token, draft and response below belongs to an isolated fixture.
@MainActor
final class ProfilePresentationTests: XCTestCase {
    func testPreparedEditorShowsFinalNameBeforeAppearanceAndDoesNotRefetch() async throws {
        let fixture = try ProfilePresentationFixture()
        defer { fixture.close() }
        let mounted = try mount(MobileProfileEditorView(initialProfile: fixture.profile), appState: fixture.appState)
        defer { mounted.close() }

        let field = try XCTUnwrap(textFields(in: mounted.controller.view).first,
            "The prepared name field must exist before the appearance task runs")
        XCTAssertEqual(textFields(in: mounted.controller.view).count, 1)
        XCTAssertEqual(field.text, fixture.profile.displayName)
        let firstFrame = field.convert(field.bounds, to: mounted.window)
        XCTAssertGreaterThan(firstFrame.width, 100)
        XCTAssertGreaterThan(firstFrame.height, 20)
        XCTAssertTrue(mounted.window.bounds.intersects(firstFrame))
        attach(mounted, name: "prepared-profile-editor-first-layout", afterScreenUpdates: false)

        try await settle(mounted)
        let settledField = try XCTUnwrap(textFields(in: mounted.controller.view).first)
        XCTAssertEqual(settledField.text, fixture.profile.displayName)
        assertFrame(settledField.convert(settledField.bounds, to: mounted.window), equals: firstFrame)
        XCTAssertEqual(fixture.transport.requestCount, 0,
            "Presenting an already prepared editor must not start another profile request")
        fixture.assertDraftPreserved()
        attach(mounted, name: "prepared-profile-editor-settled", afterScreenUpdates: true)
    }

    func testPreparedEditorKeepsNativeTextEditsWhenRemoteProfileChanges() async throws {
        let fixture = try ProfilePresentationFixture()
        defer { fixture.close() }
        let mounted = try mount(MobileProfileEditorView(initialProfile: fixture.profile), appState: fixture.appState)
        defer { mounted.close() }
        try await settle(mounted)

        let field = try XCTUnwrap(textFields(in: mounted.controller.view).first)
        field.text = "내가 수정 중인 이름"
        field.sendActions(for: .editingChanged)
        try await settle(mounted)
        XCTAssertEqual(textFields(in: mounted.controller.view).first?.text, "내가 수정 중인 이름")

        var remoteProfile = fixture.profile
        remoteProfile.displayName = "서버에서 새로 도착한 이름"
        remoteProfile.avatarSymbolName = "pixel-penguin"
        fixture.appState.communityProfile = remoteProfile
        try await settle(mounted)

        XCTAssertEqual(textFields(in: mounted.controller.view).first?.text, "내가 수정 중인 이름",
            "A profile publication while this editor is open must not replace its native input")
        XCTAssertEqual(fixture.appState.communityProfile?.displayName, remoteProfile.displayName)
        XCTAssertEqual(fixture.transport.requestCount, 0)
        fixture.assertDraftPreserved()
    }

    func testPreparedProfileHubKeepsItsFirstRowsWithoutASecondLoad() async throws {
        let fixture = try ProfilePresentationFixture()
        defer { fixture.close() }
        fixture.transport.allowOneResponse(path: "/api/v1/questions/quota", json: """
        {"usedCount":42,"monthlyLimit":300,"remainingCount":258,
         "resetAt":"2026-10-01T00:00:00Z","tierCode":"TIER2"}
        """)
        fixture.transport.allowOneResponse(path: "/api/v1/voice-tutor/status", json: """
        {"eligible":true,"tierCode":"TIER2","maxSessionSeconds":3600,
         "quota":{"limitSeconds":3600,"usedSeconds":600,"reservedSeconds":0,"remainingSeconds":3000}}
        """)
        await fixture.appState.refreshQuestionQuota()
        await fixture.appState.refreshVoiceTutorStatus()
        XCTAssertEqual(fixture.appState.questionQuota?.remainingCount, 258)
        XCTAssertEqual(fixture.appState.voiceTutorStatus?.quota.remainingSeconds, 3000)
        XCTAssertEqual(fixture.transport.requestCount, 2)
        let mounted = try mount(
            MobileProfilePage(preparedIdentity: fixture.appState.commonRecordsIdentity),
            appState: fixture.appState
        )
        defer { mounted.close() }

        let collection = try XCTUnwrap(descendants(of: UICollectionView.self, in: mounted.controller.view).first)
        let firstRows = rowFrames(in: collection, window: mounted.window)
        XCTAssertGreaterThan(firstRows.count, 3,
            "The prepared profile hub must lay out its membership and profile rows immediately")
        attach(mounted, name: "prepared-profile-hub-first-layout", afterScreenUpdates: false)

        try await settle(mounted)
        let settledRows = rowFrames(in: collection, window: mounted.window)
        XCTAssertEqual(Set(settledRows.keys), Set(firstRows.keys),
            "Starting the page task must not insert quota or account rows after presentation")
        for (indexPath, firstFrame) in firstRows {
            assertFrame(try XCTUnwrap(settledRows[indexPath]), equals: firstFrame)
        }
        XCTAssertEqual(fixture.transport.requestCount, 2,
            "A prepared hub must not immediately repeat profile, billing or voice status requests")
        fixture.assertDraftPreserved()
        attach(mounted, name: "prepared-profile-hub-settled", afterScreenUpdates: true)
    }

    private func mount<Content: View>(_ content: Content, appState: AppState) throws -> ProfilePresentationWindow {
        let controller = UIHostingController(rootView: content
            .environmentObject(appState)
            .environment(\.colorScheme, .dark)
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .dynamicTypeSize(.large))
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)
        let mounted = ProfilePresentationWindow(
            window: UIWindow(windowScene: scene),
            controller: controller,
            previousKeyWindow: scene.windows.first { $0.isKeyWindow }
        )
        mounted.window.overrideUserInterfaceStyle = .dark
        mounted.window.rootViewController = controller
        mounted.window.makeKeyAndVisible()
        controller.loadViewIfNeeded()
        let bounds = CGRect(x: 0, y: 0, width: 393, height: 852)
        mounted.window.frame = bounds
        controller.view.frame = bounds
        layout(mounted)
        return mounted
    }

    private func settle(_ mounted: ProfilePresentationWindow) async throws {
        await Task.yield()
        try await Task.sleep(for: .milliseconds(350))
        layout(mounted)
    }

    private func layout(_ mounted: ProfilePresentationWindow) {
        UIView.performWithoutAnimation {
            mounted.window.setNeedsLayout()
            mounted.window.layoutIfNeeded()
            mounted.controller.view.setNeedsLayout()
            mounted.controller.view.layoutIfNeeded()
        }
        CATransaction.flush()
    }

    private func textFields(in view: UIView) -> [UITextField] {
        descendants(of: UITextField.self, in: view).filter(\.isEnabled)
    }

    private func descendants<T: UIView>(of type: T.Type, in view: UIView) -> [T] {
        (view as? T).map { [$0] } ?? view.subviews.flatMap { descendants(of: type, in: $0) }
    }

    private func rowFrames(in collection: UICollectionView, window: UIWindow) -> [IndexPath: CGRect] {
        Dictionary(uniqueKeysWithValues: collection.indexPathsForVisibleItems.compactMap { indexPath in
            guard let cell = collection.cellForItem(at: indexPath) else { return nil }
            return (indexPath, cell.convert(cell.bounds, to: window))
        })
    }

    private func assertFrame(_ actual: CGRect, equals expected: CGRect, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.minX, expected.minX, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.minY, expected.minY, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.width, expected.width, accuracy: 1, file: file, line: line)
        XCTAssertEqual(actual.height, expected.height, accuracy: 1, file: file, line: line)
    }

    private func attach(_ mounted: ProfilePresentationWindow, name: String, afterScreenUpdates: Bool) {
        let image = UIGraphicsImageRenderer(size: mounted.window.bounds.size).image { _ in
            mounted.window.drawHierarchy(in: mounted.window.bounds, afterScreenUpdates: afterScreenUpdates)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

@MainActor
private struct ProfilePresentationWindow {
    let window: UIWindow
    let controller: UIViewController
    let previousKeyWindow: UIWindow?

    func close() {
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}

@MainActor
private final class ProfilePresentationFixture {
    let suiteName = "ProfilePresentationTests-\(UUID().uuidString)"
    let host = "\(UUID().uuidString.lowercased()).profile-presentation.invalid"
    let defaults: UserDefaults
    let store: SettingsStore
    let appState: AppState
    let session: URLSession
    let transport = ProfilePresentationTransport()
    let profile = CommunityUserProfile(
        id: 801, displayName: "테스트 프로필", status: "ACTIVE", provider: "APPLE",
        bio: "", avatarURL: nil, avatarSymbolName: "pixel-cat", avatarColorSeed: "avatar-color-mint"
    )

    init() throws {
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        store.saveSettings(.initial(for: .korean))
        store.saveIsCommunitySignedIn(true)
        store.saveLastAnswer("합성 보존 초안")
        store.saveAnswerDraft("합성 기록별 초안", recordID: "profile-presentation-record")
        let payload = Data("{\"device_id\":\"profile-fixture\",\"user_id\":801,\"is_anonymous\":false,\"status\":\"ACTIVE\",\"jti\":\"fixture\"}".utf8)
        let encoded = payload.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        store.saveRemotePushRegistration(RemotePushRegistration(
            deviceID: "profile-fixture", clientSecret: "synthetic-secret", apnsToken: "",
            accessToken: "e30.\(encoded).synthetic-signature",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        ))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ProfilePresentationURLProtocol.self]
        configuration.timeoutIntervalForRequest = 3
        configuration.timeoutIntervalForResource = 3
        session = URLSession(configuration: configuration)
        ProfilePresentationURLProtocol.install(transport, host: host)
        appState = AppState(
            settingsStore: store,
            remotePushBackendClient: RemotePushBackendClient(
                baseURL: try XCTUnwrap(URL(string: "https://\(host)")), session: session
            ),
            appNotificationEventProvider: ProfilePresentationNotificationEvents()
        )
        appState.communityProfile = profile
        XCTAssertTrue(appState.isCommunitySessionActive)
    }

    func assertDraftPreserved(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(store.loadLastAnswer(), "합성 보존 초안", file: file, line: line)
        XCTAssertEqual(store.loadAnswerDraft(recordID: "profile-presentation-record"), "합성 기록별 초안", file: file, line: line)
    }

    func close() {
        session.invalidateAndCancel()
        ProfilePresentationURLProtocol.remove(host: host)
        defaults.removePersistentDomain(forName: suiteName)
    }
}

@MainActor
private struct ProfilePresentationNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }
    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable {
        AnyCancellable {}
    }
}

private final class ProfilePresentationTransport: @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [String: Data] = [:]
    private var count = 0

    var requestCount: Int { lock.withLock { count } }

    func allowOneResponse(path: String, json: String) {
        lock.withLock { responses[path] = Data(json.utf8) }
    }

    func response(for request: URLRequest) throws -> Data {
        try lock.withLock {
            count += 1
            guard request.httpMethod == "GET",
                  let response = responses.removeValue(forKey: request.url?.path ?? "") else {
                XCTFail("Prepared profile presentation attempted an unexpected request: \(request.url?.path ?? "unknown")")
                throw URLError(.unsupportedURL)
            }
            return response
        }
    }
}

/// The private session intercepts every URL, not only the fixture host.
private final class ProfilePresentationURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var transports: [String: ProfilePresentationTransport] = [:]

    static func install(_ transport: ProfilePresentationTransport, host: String) {
        lock.withLock { transports[host] = transport }
    }

    static func remove(host: String) {
        _ = lock.withLock { transports.removeValue(forKey: host) }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let transport = Self.lock.withLock { Self.transports[request.url?.host ?? ""] }
            let data = try XCTUnwrap(transport).response(for: request)
            let response = try XCTUnwrap(HTTPURLResponse(
                url: try XCTUnwrap(request.url), statusCode: 200, httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            ))
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
#endif
