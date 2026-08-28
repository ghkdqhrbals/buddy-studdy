import Foundation
import Combine
import XCTest
@testable import StudyMate

@MainActor
final class QuestionGenerationFlowTests: XCTestCase {
    override func tearDown() {
        QuestionGenerationURLProtocol.requestHandler = nil
        QuestionGenerationURLProtocol.responseDelayNanoseconds = 0
        QuestionGenerationURLProtocol.responseDelayHandler = nil
        super.tearDown()
    }

    func testQuestionStatusDecodesFailedAsTerminalBackendState() throws {
        let decoded = try JSONDecoder().decode(
            QuestionStatus.self,
            from: Data(#""FAILED""#.utf8)
        )

        XCTAssertEqual(decoded, .failed)
    }

    func testReferralLinkParserIsStrictAndSeparateFromAppRoute() throws {
        let webURL = try XCTUnwrap(
            URL(string: "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
        )
        let customURL = try XCTUnwrap(
            URL(string: "buddystudy://referrals/bs-abcdefgh")
        )
        XCTAssertEqual(ReferralLink(url: webURL)?.code, "BS-ABCDEFGH")
        XCTAssertEqual(ReferralLink(url: customURL)?.code, "BS-ABCDEFGH")
        XCTAssertNil(AppRoute(url: customURL))

        let rejectedURLs = [
            "http://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH",
            "https://example.com/referrals/BS-ABCDEFGH",
            "https://api%2eghkdqhrbals.org/referrals/BS-ABCDEFGH",
            "https://api.ghkdqhrbals.org:443/referrals/BS-ABCDEFGH",
            "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH/extra",
            "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH?campaign=1",
            "https://api.ghkdqhrbals.org/referrals/BS-ABCDEF01",
            "buddystudy://profile/BS-ABCDEFGH",
        ]
        for rawURL in rejectedURLs {
            XCTAssertNil(
                ReferralLink(url: try XCTUnwrap(URL(string: rawURL))),
                rawURL
            )
        }
    }

    func testPendingReferralPersistsThroughSettingsStoreUseCase() throws {
        let suiteName = "PendingReferralTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        let useCase = PendingReferralUseCase(
            repository: SettingsStorePendingReferralRepository(settingsStore: store)
        )

        let initialCapturedAt = Date(timeIntervalSince1970: 1_800_000_000)
        let captured = useCase.capture(
            code: " bs-abcdefgh ",
            source: .authentication,
            capturedAt: initialCapturedAt
        )
        XCTAssertEqual(captured?.code, "BS-ABCDEFGH")
        XCTAssertEqual(captured?.source, .authentication)
        XCTAssertEqual(captured?.state, .captured)
        let relaunchedUseCase = PendingReferralUseCase(
            repository: SettingsStorePendingReferralRepository(
                settingsStore: SettingsStore(
                    defaults: defaults,
                    usesSecureBackendIdentityStorage: false
                )
            )
        )
        XCTAssertEqual(relaunchedUseCase.pendingAttribution()?.code, "BS-ABCDEFGH")
        let bound = relaunchedUseCase.capture(
            code: "BS-ABCDEFGH",
            source: .requiredTerms,
            accountID: 42,
            capturedAt: Date().addingTimeInterval(3_600)
        )
        XCTAssertEqual(bound?.capturedAt, captured?.capturedAt)
        XCTAssertEqual(bound?.accountID, 42)
        XCTAssertEqual(bound?.source, .requiredTerms)
        XCTAssertNil(
            relaunchedUseCase.markServerConfirmed(
                code: "BS-ABCDEFGH",
                accountID: 99
            )
        )
        XCTAssertEqual(
            relaunchedUseCase.markServerConfirmed(
                code: "BS-ABCDEFGH",
                accountID: 42
            )?.state,
            .serverConfirmed
        )
        XCTAssertEqual(
            relaunchedUseCase.capture(
                code: "BS-ZYXWVUTS",
                source: .authentication
            )?.code,
            "BS-ABCDEFGH"
        )
        XCTAssertEqual(relaunchedUseCase.pendingAttribution()?.accountID, 42)
        XCTAssertFalse(relaunchedUseCase.clear(ifMatching: "BS-ZYXWVUTS"))
        XCTAssertTrue(relaunchedUseCase.clear(ifMatching: "BS-ABCDEFGH"))
        XCTAssertNil(relaunchedUseCase.pendingAttribution())

        let legacyCapturedAt = Date(timeIntervalSince1970: 1_700_000_000)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "bs-abcdefgh",
                source: .requiredTerms,
                capturedAt: legacyCapturedAt,
                accountID: 42,
                state: .serverConfirmed
            )
        )
        let normalizedLegacy = relaunchedUseCase.pendingAttribution()
        XCTAssertEqual(normalizedLegacy?.code, "BS-ABCDEFGH")
        XCTAssertEqual(normalizedLegacy?.capturedAt, legacyCapturedAt)
        XCTAssertEqual(normalizedLegacy?.accountID, 42)
        XCTAssertEqual(normalizedLegacy?.state, .serverConfirmed)
    }

    func testReferralSummaryPrefersValidatedServerURLAndFallsBackToCanonicalLink() throws {
        let canonicalURL = try XCTUnwrap(
            URL(string: "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
        )
        var summary = BackendReferralSummary(
            code: "BS-ABCDEFGH",
            successfulReferralCount: 0,
            rewardMonthsEarned: 0,
            rewardStartsAt: nil,
            rewardEndsAt: nil,
            hasRedeemedReferral: false,
            referralUrl: canonicalURL
        )
        XCTAssertEqual(summary.canonicalReferralURL, canonicalURL)

        summary.referralUrl = URL(
            string: "https://malicious.example/referrals/BS-ABCDEFGH"
        )
        XCTAssertEqual(summary.canonicalReferralURL, canonicalURL)
        summary.referralUrl = nil
        XCTAssertEqual(summary.canonicalReferralURL, canonicalURL)
        summary.referralUrl = URL(
            string: "buddystudy://referrals/BS-ABCDEFGH"
        )
        XCTAssertEqual(summary.canonicalReferralURL, canonicalURL)

        let malformedServerURLSummary = try JSONDecoder().decode(
            BackendReferralSummary.self,
            from: Data(
                """
                {
                  "code": "BS-ABCDEFGH",
                  "successfulReferralCount": 0,
                  "rewardMonthsEarned": 0,
                  "rewardStartsAt": null,
                  "rewardEndsAt": null,
                  "hasRedeemedReferral": false,
                  "referralUrl": "https://%"
                }
                """.utf8
            )
        )
        XCTAssertNil(malformedServerURLSummary.referralUrl)
        XCTAssertEqual(malformedServerURLSummary.canonicalReferralURL, canonicalURL)
    }

    func testGoogleAppleAndEmailAuthCarryReferralCodeAndDecodeAttribution() async throws {
        let receivedReferralCodes = LockedValue<[String]>([])
        let client = makeClient { request in
            let data = try Self.bodyData(from: request)
            let body = try XCTUnwrap(
                JSONSerialization.jsonObject(with: data) as? [String: Any]
            )
            if let referralCode = body["referralCode"] as? String {
                receivedReferralCodes.set(receivedReferralCodes.value + [referralCode])
            }
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "profile": {
                    "id": 7,
                    "displayName": "New-Buddy-0007",
                    "status": "PENDING_TERMS"
                  },
                  "accessToken": "access-token",
                  "accessTokenExpiresAt": "2026-08-29T00:00:00Z",
                  "referralAttributed": true,
                  "isNewAccount": true
                }
                """
            )
        }

        let google = try await client.loginWithGoogle(
            registration: Self.registration,
            idToken: "google-token",
            referralCode: "BS-ABCDEFGH"
        )
        _ = try await client.loginWithApple(
            registration: Self.registration,
            idToken: "apple-token",
            referralCode: "BS-ABCDEFGH"
        )
        _ = try await client.loginWithEmail(
            registration: Self.registration,
            email: "new@example.com",
            password: "password",
            verificationCode: "123456",
            referralCode: "BS-ABCDEFGH"
        )

        XCTAssertEqual(google.referralAttributed, true)
        XCTAssertEqual(google.isNewAccount, true)
        XCTAssertEqual(
            receivedReferralCodes.value,
            ["BS-ABCDEFGH", "BS-ABCDEFGH", "BS-ABCDEFGH"]
        )
    }

    func testReferralLinkCapturePersistsWithoutReplacingDraft() throws {
        let suiteName = "ReferralAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        let appState = AppState(settingsStore: store)
        appState.lastAnswer = "작성 중인 답변"

        appState.openDeepLink(
            try XCTUnwrap(
                URL(string: "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
            )
        )

        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertTrue(appState.shouldPresentReferralLogin)
        XCTAssertEqual(appState.lastAnswer, "작성 중인 답변")
        XCTAssertEqual(store.loadPendingReferralAttribution()?.code, "BS-ABCDEFGH")
    }

    func testTerminalReferralRejectionClearsPendingCodeWithoutReplacingDraft() async throws {
        let suiteName = "ReferralRejectionAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveRemotePushRegistration(
            RemotePushRegistration(
                deviceID: "device-1",
                clientSecret: "client-secret",
                apnsToken: "",
                accessToken: "e30.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6dHJ1ZSwic3RhdHVzIjoiQU5PTllNT1VTIn0.signature",
                accessTokenExpiresAt: Date().addingTimeInterval(3_600)
            )
        )
        let submittedReferralCodes = LockedValue<[String]>([])
        let client = makeClient { request in
            guard request.url?.path == "/api/v1/auth/google" else {
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
            let data = try Self.bodyData(from: request)
            let body = try XCTUnwrap(
                JSONSerialization.jsonObject(with: data) as? [String: Any]
            )
            if let referralCode = body["referralCode"] as? String {
                submittedReferralCodes.set(
                    submittedReferralCodes.value + [referralCode]
                )
            }
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "profile": {
                    "id": 7,
                    "displayName": "Existing-Buddy-0007",
                    "status": "ACTIVE",
                    "provider": "GOOGLE"
                  },
                  "accessToken": "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                  "accessTokenExpiresAt": "2030-08-29T00:00:00Z",
                  "referralAttributed": false,
                  "isNewAccount": false
                }
                """
            )
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        appState.lastAnswer = "작성 중인 답변"

        appState.openDeepLink(
            try XCTUnwrap(
                URL(string: "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
            )
        )
        await appState.signInToCommunity(idToken: "google-token")

        XCTAssertEqual(submittedReferralCodes.value, ["BS-ABCDEFGH"])
        XCTAssertNil(appState.pendingReferralCode)
        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertEqual(appState.referralNotice, .notEligible)
        XCTAssertEqual(appState.lastAnswer, "작성 중인 답변")
    }

    func testAttributedLoginWaitsForRedeemedSummaryBeforeShowingReward() async throws {
        let suiteName = "ReferralConfirmationAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveRemotePushRegistration(Self.anonymousRegistration)
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/auth/google"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "profile": {
                        "id": 7,
                        "displayName": "New-Buddy-0007",
                        "status": "ACTIVE",
                        "provider": "GOOGLE"
                      },
                      "accessToken": "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                      "accessTokenExpiresAt": "2030-08-29T00:00:00Z",
                      "referralAttributed": true,
                      "isNewAccount": true
                    }
                    """
                )
            case ("GET", "/api/v1/referrals/me"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "code": "BS-ZYXWVUTS",
                      "successfulReferralCount": 0,
                      "rewardMonthsEarned": 0,
                      "rewardStartsAt": null,
                      "rewardEndsAt": null,
                      "hasRedeemedReferral": false,
                      "referralUrl": "https://api.ghkdqhrbals.org/referrals/BS-ZYXWVUTS"
                    }
                    """
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        appState.openDeepLink(
            try XCTUnwrap(
                URL(string: "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH")
            )
        )
        await appState.signInToCommunity(idToken: "google-token")
        let didFinishVerification = await waitUntil(maxAttempts: 250) {
            appState.referralNotice == .attributionPending
        }

        XCTAssertTrue(didFinishVerification)
        XCTAssertNotEqual(appState.referralNotice, .rewardApplied)
        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertEqual(
            store.loadPendingReferralAttribution()?.state,
            .serverConfirmed
        )
        XCTAssertEqual(store.loadPendingReferralAttribution()?.accountID, 7)
    }

    func testStoredActiveAccountWaitsForAuthoritativeProfileBeforeRejectingReferralLink() async throws {
        let suiteName = "ActiveReferralAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        QuestionGenerationURLProtocol.responseDelayNanoseconds = 150_000_000
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "id": 7,
                  "displayName": "Existing-Buddy-0007",
                  "status": "ACTIVE",
                  "provider": "GOOGLE"
                }
                """
            )
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        appState.openDeepLink(
            try XCTUnwrap(URL(string: "buddystudy://referrals/BS-ABCDEFGH"))
        )

        XCTAssertNil(appState.referralNotice)
        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertEqual(
            store.loadPendingReferralAttribution()?.state,
            .captured
        )
        XCTAssertFalse(appState.shouldPresentReferralLogin)
        let didResolveProfile = await waitUntil {
            appState.referralNotice == .existingAccount
        }

        XCTAssertTrue(didResolveProfile)
        XCTAssertEqual(appState.referralNotice, .existingAccount)
        XCTAssertNil(store.loadPendingReferralAttribution())
    }

    func testPendingTermsProfileUsesTermsSpecificReferralNotice() async throws {
        let suiteName = "PendingTermsReferralAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "id": 8,
                  "displayName": "New-Buddy-0008",
                  "status": "PENDING_TERMS",
                  "provider": "GOOGLE"
                }
                """
            )
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        appState.openDeepLink(
            try XCTUnwrap(URL(string: "buddystudy://referrals/BS-ABCDEFGH"))
        )
        let didResolveProfile = await waitUntil {
            appState.referralNotice == .readyAfterTerms
        }

        XCTAssertTrue(didResolveProfile)
        XCTAssertEqual(store.loadPendingReferralAttribution()?.accountID, 8)
        XCTAssertFalse(appState.shouldPresentReferralLogin)
    }

    func testRelaunchRestoresAccountlessCapturedReferralUntilProfileIsAuthoritative() async throws {
        let suiteName = "AccountlessCapturedReferralRelaunchTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .authentication,
                accountID: nil
            )
        )
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            return Self.pendingTermsProfileResponse(for: request)
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        XCTAssertNil(appState.referralNotice)
        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertFalse(appState.shouldPresentReferralLogin)

        await appState.loadCommunityProfile()

        XCTAssertEqual(appState.referralNotice, .readyAfterTerms)
        XCTAssertEqual(store.loadPendingReferralAttribution()?.accountID, 7)
        XCTAssertEqual(store.loadPendingReferralAttribution()?.state, .captured)
    }

    func testRequiredTermsTerminalReferralFailureClearsPendingAttribution() async throws {
        let suiteName = "TerminalTermsReferralTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 7
            )
        )
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.pendingTermsProfileResponse(for: request)
            case ("POST", "/api/v1/referrals/redeem"):
                return Self.response(
                    for: request,
                    statusCode: 409,
                    body: """
                    {
                      "code": "REFERRAL_NOT_ELIGIBLE",
                      "message": "Referral is not eligible"
                    }
                    """
                )
            case ("GET", "/api/v1/referrals/me"):
                return Self.unredeemedReferralSummaryResponse(for: request)
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        await appState.loadCommunityProfile()

        await appState.finishReferralOnboardingAfterRequiredTerms()

        XCTAssertNil(appState.pendingReferralCode)
        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertEqual(appState.referralNotice, .notEligible)
    }

    func testRequiredTermsTransientReferralFailureKeepsPendingAttribution() async throws {
        let suiteName = "TransientTermsReferralTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 7
            )
        )
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.pendingTermsProfileResponse(for: request)
            case ("POST", "/api/v1/referrals/redeem"),
                 ("GET", "/api/v1/referrals/me"):
                return Self.response(for: request, statusCode: 503, body: "{}")
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        await appState.loadCommunityProfile()

        await appState.finishReferralOnboardingAfterRequiredTerms()

        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertEqual(
            store.loadPendingReferralAttribution()?.state,
            .captured
        )
        XCTAssertEqual(appState.referralNotice, .attributionPending)
    }

    func testSuccessfulRedeemWaitsForFreshSummaryBeforeShowingReward() async throws {
        let suiteName = "UnconfirmedRedeemReferralTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 7
            )
        )
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.pendingTermsProfileResponse(for: request)
            case ("POST", "/api/v1/referrals/redeem"):
                return Self.redeemedReferralSummaryResponse(for: request)
            case ("GET", "/api/v1/referrals/me"):
                return Self.response(for: request, statusCode: 503, body: "{}")
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        await appState.loadCommunityProfile()

        await appState.finishReferralOnboardingAfterRequiredTerms()

        XCTAssertEqual(appState.referralNotice, .attributionPending)
        XCTAssertNotEqual(appState.referralNotice, .rewardApplied)
        XCTAssertEqual(appState.pendingReferralCode, "BS-ABCDEFGH")
        XCTAssertEqual(
            store.loadPendingReferralAttribution()?.state,
            .serverConfirmed
        )
    }

    func testRequiredTermsReferralCompletionCannotRestoreNoticeAfterSignOut() async throws {
        let suiteName = "ReferralTermsSignOutRaceTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 7
            )
        )
        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            request.url?.path == "/api/v1/referrals/redeem" ? 150_000_000 : 0
        }
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.pendingTermsProfileResponse(for: request)
            case ("POST", "/api/v1/referrals/redeem"):
                return Self.redeemedReferralSummaryResponse(for: request)
            default:
                return Self.response(for: request, statusCode: 204, body: "")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        await appState.loadCommunityProfile()
        let completion = Task { @MainActor in
            await appState.finishReferralOnboardingAfterRequiredTerms()
        }
        try? await Task.sleep(nanoseconds: 30_000_000)

        appState.signOutFromCommunity()
        await completion.value

        XCTAssertFalse(appState.isCommunitySessionActive)
        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertNil(appState.referralNotice)
    }

    func testActiveRelaunchReconcilesServerConfirmedReferralWithFreshSummary() async throws {
        let suiteName = "ConfirmedReferralRelaunchTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .authentication,
                accountID: 7,
                state: .serverConfirmed
            )
        )
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.activeProfileResponse(for: request)
            case ("GET", "/api/v1/referrals/me"):
                return Self.redeemedReferralSummaryResponse(for: request)
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        await appState.loadCommunityProfile()
        let didReconcile = await waitUntil(maxAttempts: 250) {
            appState.referralNotice == .rewardApplied
        }

        XCTAssertTrue(didReconcile)
        XCTAssertNil(store.loadPendingReferralAttribution())
    }

    func testActiveRelaunchLetsServerDecideEligibilityForOldCapturedReferral() async throws {
        let suiteName = "CapturedReferralRelaunchTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                capturedAt: Date().addingTimeInterval(-72 * 60 * 60),
                accountID: 7
            )
        )
        let redeemRequestCount = LockedValue(0)
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.activeProfileResponse(for: request)
            case ("POST", "/api/v1/referrals/redeem"):
                redeemRequestCount.set(redeemRequestCount.value + 1)
                return Self.redeemedReferralSummaryResponse(for: request)
            case ("GET", "/api/v1/referrals/me"):
                return Self.redeemedReferralSummaryResponse(for: request)
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        await appState.loadCommunityProfile()
        let didRecover = await waitUntil(maxAttempts: 250) {
            appState.referralNotice == .rewardApplied
        }

        XCTAssertTrue(didRecover)
        XCTAssertEqual(redeemRequestCount.value, 1)
        XCTAssertNil(store.loadPendingReferralAttribution())
    }

    func testActiveRelaunchClearsReferralBoundToAnotherAccount() async throws {
        let suiteName = "MismatchedReferralRelaunchTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 99
            )
        )
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            return Self.activeProfileResponse(for: request)
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        await appState.loadCommunityProfile()

        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertEqual(appState.referralNotice, .notEligible)
    }

    func testPendingTermsRelaunchDoesNotRebindReferralFromAnotherAccount() async throws {
        let suiteName = "MismatchedPendingTermsReferralTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .requiredTerms,
                accountID: 99
            )
        )
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            return Self.pendingTermsProfileResponse(for: request)
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )

        await appState.loadCommunityProfile()

        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertNil(appState.pendingReferralCode)
        XCTAssertEqual(appState.referralNotice, .notEligible)
    }

    func testDelayedReferralSummaryCannotRestoreNoticeAfterSignOut() async throws {
        let suiteName = "ReferralSignOutRaceTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .authentication,
                accountID: 7,
                state: .serverConfirmed
            )
        )
        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            request.url?.path == "/api/v1/referrals/me" ? 150_000_000 : 0
        }
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/profile"):
                return Self.activeProfileResponse(for: request)
            case ("GET", "/api/v1/referrals/me"):
                return Self.redeemedReferralSummaryResponse(for: request)
            case ("POST", "/api/v1/auth/logout"):
                return Self.response(for: request, statusCode: 204, body: "")
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        await appState.loadCommunityProfile()
        try? await Task.sleep(nanoseconds: 30_000_000)

        appState.signOutFromCommunity()
        try? await Task.sleep(nanoseconds: 220_000_000)

        XCTAssertFalse(appState.isCommunitySessionActive)
        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertNil(appState.referralNotice)
    }

    func testDelayedProfileCannotReactivateSessionOrLoseReferralAfterSignOut() async throws {
        let suiteName = "ReferralProfileSignOutRaceTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(
            defaults: defaults,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .authentication
            )
        )
        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            request.url?.path == "/api/v1/profile" ? 150_000_000 : 0
        }
        let client = makeClient { request in
            if request.url?.path == "/api/v1/profile" {
                return Self.activeProfileResponse(for: request)
            }
            return Self.response(for: request, statusCode: 204, body: "")
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client
        )
        let profileLoad = Task { @MainActor in
            await appState.loadCommunityProfile()
        }
        try? await Task.sleep(nanoseconds: 30_000_000)

        appState.signOutFromCommunity()
        await profileLoad.value

        XCTAssertFalse(appState.isCommunitySessionActive)
        XCTAssertNil(appState.communityProfile)
        XCTAssertEqual(store.loadPendingReferralAttribution()?.code, "BS-ABCDEFGH")
        XCTAssertTrue(appState.shouldPresentReferralLogin)
        XCTAssertNil(appState.referralNotice)
    }

    func testReferralNoticesAreLocalizedInKoreanEnglishAndJapanese() {
        let korean = AppStrings(language: .korean).referralRewardAppliedNotice
        let english = AppStrings(language: .english).referralRewardAppliedNotice
        let japanese = AppStrings(language: .japanese).referralRewardAppliedNotice

        XCTAssertNotEqual(korean, english)
        XCTAssertNotEqual(english, japanese)
        XCTAssertTrue(japanese.contains("Pro"))
        for language in AppLanguage.allCases {
            let strings = AppStrings(language: language)
            XCTAssertNotEqual(
                strings.referralInvitationReady,
                strings.referralReadyAfterTermsNotice
            )
        }
    }

    func testOpeningStudyOnlyPersistsSelectionWithoutUpdatingBackendSettings() async throws {
        let suiteName = "StudyNavigationSettingsTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let first = StudyCategory(id: "11", title: "Redis")
        let second = StudyCategory(id: "12", title: "Redis Streams")
        let store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        store.saveSettings(
            StudySettings(
                topic: first.title,
                difficulty: .intermediate,
                customPrompt: "",
                intervalMinutes: 30,
                studyCategories: [first, second],
                selectedStudyCategoryID: first.id
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let requestedMethodsAndPaths = LockedValue<[String]>([])
        let client = makeClient { request in
            let requestDescription = "\(request.httpMethod ?? "") \(request.url?.path ?? "")"
            requestedMethodsAndPaths.set(requestedMethodsAndPaths.value + [requestDescription])
            switch request.url?.path {
            case "/api/v1/studies/12":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedChildStudyDetailResponse
                )
            case "/api/v1/questions/quota":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.questionQuotaResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        appState.openStudyCategory(second.id)
        let didFinishOpening = await waitUntil {
            appState.homeStudyRoute?.categoryID == second.id
        }

        XCTAssertTrue(didFinishOpening)
        XCTAssertEqual(appState.settings.selectedStudyCategoryID, second.id)
        XCTAssertEqual(store.loadSettings().selectedStudyCategoryID, second.id)
        XCTAssertFalse(
            requestedMethodsAndPaths.value.contains(where: { $0.hasPrefix("PUT ") }),
            "Opening a study may preload its detail but must not persist backend schedule settings."
        )
    }

    func testOpeningNestedStudyRoutesToChildIdentifierWhenSettingsPersistOnlyTheRoot() async throws {
        let suiteName = "NestedStudyNavigationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let root = StudyCategory(id: "11", title: "Redis", difficulty: .level10)
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: root.title,
                difficulty: root.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 30,
                studyCategories: [root],
                selectedStudyCategoryID: root.id
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            switch request.url?.path {
            case "/api/v1/studies":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedStudyPageResponse
                )
            case "/api/v1/studies/12":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedChildStudyDetailResponse
                )
            case "/api/v1/questions/quota":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.questionQuotaResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.refreshVisibleData()
        appState.openStudyCategory("12")
        let didFinishOpening = await waitUntil {
            appState.homeStudyRoute?.categoryID == "12"
        }

        XCTAssertTrue(didFinishOpening)
        XCTAssertEqual(appState.homeStudyRoute?.categoryID, "12")
        XCTAssertFalse(appState.homeStudyRoute?.showsTree ?? true)
        XCTAssertTrue(appState.homeStudyRoute?.isContentPrepared ?? false)
        XCTAssertEqual(
            store.loadSettings().selectedStudyCategoryID,
            root.id,
            "Opening a nested topic must not replace the persisted root-study selection."
        )
    }

    func testPreparingNestedStudyRequestsChildDetailWithoutFallingBackToRoot() async throws {
        let suiteName = "NestedStudyDetailTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let root = StudyCategory(id: "11", title: "Redis", difficulty: .level10)
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: root.title,
                difficulty: root.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 30,
                studyCategories: [root],
                selectedStudyCategoryID: root.id
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let requestedPaths = LockedValue<[String]>([])
        let client = makeClient { request in
            let path = request.url?.path ?? ""
            requestedPaths.set(requestedPaths.value + [path])
            switch path {
            case "/api/v1/studies":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedStudyPageResponse
                )
            case "/api/v1/studies/12":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedChildStudyDetailResponse
                )
            case "/api/v1/studies/11":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedRootStudyDetailResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.refreshVisibleData()
        await appState.prepareStudyRoom(categoryID: "12")

        XCTAssertEqual(requestedPaths.value, ["/api/v1/studies", "/api/v1/studies/12"])
        let displayed = try XCTUnwrap(appState.studyRoomRecordForDisplay(categoryID: "12"))
        XCTAssertEqual(displayed.id, "latest-child-12")
        XCTAssertEqual(displayed.studyID, 12)
        XCTAssertEqual(displayed.topic, "메모리 관리와 만료 정책")
    }

    func testOpeningNestedStudyWaitsForDetailAndQuotaBeforePublishingPreparedRoute() async throws {
        let suiteName = "PreparedNestedStudyNavigationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = makeNestedStudyStore(
            defaults: defaults,
            databaseURL: databaseURL
        )
        let requestedPaths = LockedValue<[String]>([])
        let client = makeClient { request in
            let path = request.url?.path ?? ""
            requestedPaths.set(requestedPaths.value + [path])
            switch path {
            case "/api/v1/studies":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedStudyPageResponse
                )
            case "/api/v1/studies/12":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedChildStudyDetailResponse
                )
            case "/api/v1/questions/quota":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.questionQuotaResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        await appState.refreshVisibleData()

        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            switch request.url?.path {
            case "/api/v1/studies/12": 40_000_000
            case "/api/v1/questions/quota": 250_000_000
            default: 0
            }
        }

        appState.openStudyCategory("12")

        XCTAssertNil(appState.homeStudyRoute)
        XCTAssertEqual(appState.openingStudyCategoryID, "12")

        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(requestedPaths.value.contains("/api/v1/studies/12"))
        XCTAssertNil(
            appState.homeStudyRoute,
            "The destination must remain hidden after detail finishes while quota is still loading."
        )

        let didFinishOpening = await waitUntil {
            appState.homeStudyRoute?.categoryID == "12"
        }
        XCTAssertTrue(didFinishOpening)
        XCTAssertTrue(appState.homeStudyRoute?.isContentPrepared ?? false)
        XCTAssertNil(appState.openingStudyCategoryID)

        let detailRequestCountBeforePreparation = requestedPaths.value.filter {
            $0 == "/api/v1/studies/12"
        }.count
        await appState.prepareStudyRoom(
            categoryID: "12",
            shouldRefreshDetail: false
        )
        let detailRequestCountAfterPreparation = requestedPaths.value.filter {
            $0 == "/api/v1/studies/12"
        }.count

        XCTAssertEqual(detailRequestCountBeforePreparation, 1)
        XCTAssertEqual(
            detailRequestCountAfterPreparation,
            detailRequestCountBeforePreparation,
            "A prepared destination must not immediately request the same study detail again."
        )
    }

    func testRapidSecondStudySelectionCannotBeOverwrittenByStaleFirstPreload() async throws {
        let suiteName = "RapidNestedStudyNavigationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = makeNestedStudyStore(
            defaults: defaults,
            databaseURL: databaseURL
        )
        let client = makeClient { request in
            switch request.url?.path {
            case "/api/v1/studies":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedStudyPageWithSecondChildResponse
                )
            case "/api/v1/studies/12":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nestedChildStudyDetailResponse
                )
            case "/api/v1/studies/13":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.secondNestedChildStudyDetailResponse
                )
            case "/api/v1/questions/quota":
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.questionQuotaResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        await appState.refreshVisibleData()

        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            switch request.url?.path {
            case "/api/v1/studies/12": 350_000_000
            case "/api/v1/studies/13": 30_000_000
            case "/api/v1/questions/quota": 30_000_000
            default: 0
            }
        }

        appState.openStudyCategory("12")
        try await Task.sleep(nanoseconds: 20_000_000)
        appState.openStudyCategory("13")

        XCTAssertEqual(appState.openingStudyCategoryID, "13")
        let didOpenSecondSelection = await waitUntil {
            appState.homeStudyRoute?.categoryID == "13"
        }
        XCTAssertTrue(didOpenSecondSelection)
        XCTAssertTrue(appState.homeStudyRoute?.isContentPrepared ?? false)

        try await Task.sleep(nanoseconds: 450_000_000)
        XCTAssertEqual(
            appState.homeStudyRoute?.categoryID,
            "13",
            "A slower, cancelled preload must never replace the user's newer selection."
        )
    }

    func testDeletingStudyCategoryPreservesItsStudyRecords() throws {
        let suiteName = "StudyDeletionRecordLifecycleTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: "Redis",
                difficulty: .level6,
                customPrompt: "짧게",
                intervalMinutes: 15,
                studyCategories: [StudyCategory(id: "42", title: "Redis", difficulty: .level6)],
                selectedStudyCategoryID: "42"
            )
        )
        let record = StudyRecord(
            id: "record-42",
            studyID: 42,
            question: QuestionItem(
                question: "Redis Stream의 consumer group을 설명하세요.",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            answer: "여러 consumer가 메시지를 분담해 처리합니다.",
            topic: "Redis",
            difficulty: .level6,
            answeredAt: Date()
        )
        store.saveStudyRecord(record)
        let appState = AppState(settingsStore: store)

        appState.deleteStudyCategory(id: "42")

        XCTAssertTrue(appState.settings.studyCategories.isEmpty)
        XCTAssertEqual(appState.studyRecords.map(\.id), [record.id])
        XCTAssertEqual(store.loadStudyRecords().map(\.id), [record.id])
    }

    func testAutosavingStudyRoomDraftDoesNotPromoteItToSubmittedAnswer() {
        let suiteName = "AnswerDraftFlowTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let settings = StudySettings(
            topic: "운영체제",
            difficulty: .intermediate,
            customPrompt: "짧게",
            intervalMinutes: 15
        )
        let question = QuestionItem(
            question: "프로세스와 스레드의 차이는?",
            expectedAnswerHint: nil,
            createdAt: Date()
        )
        store.appendStudyRecord(question: question, settings: settings)
        let record = store.loadStudyRecords()[0]
        let appState = AppState(settingsStore: store)

        appState.updateAnswer(
            "작성 중인 답변은 아직 제출된 답변이 아닙니다.",
            for: record
        )
        appState.flushPendingAnswerDraftSave()

        let persistedRecord = store.loadStudyRecords()[0]
        XCTAssertNil(persistedRecord.answer)
        XCTAssertEqual(
            store.loadAnswerDraft(recordID: record.id),
            "작성 중인 답변은 아직 제출된 답변이 아닙니다."
        )
        XCTAssertEqual(
            appState.answerDraft(for: persistedRecord),
            "작성 중인 답변은 아직 제출된 답변이 아닙니다."
        )
        XCTAssertTrue(StudyAnswerPresentationPolicy.shouldShowEditor(for: persistedRecord))
    }

    func testReopeningPendingRecordRestoresDraftWithoutShowingSubmittedMessage() {
        let suiteName = "AnswerDraftRestoreTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let settings = StudySettings(
            topic: "네트워크",
            difficulty: .level5,
            customPrompt: "짧게",
            intervalMinutes: 15
        )
        let question = QuestionItem(
            question: "TCP 흐름 제어를 설명하세요.",
            expectedAnswerHint: nil,
            createdAt: Date()
        )
        store.appendStudyRecord(question: question, settings: settings)
        let record = store.loadStudyRecords()[0]
        store.saveAnswerDraft("수신 윈도우를 기준으로 전송량을 조절합니다.", recordID: record.id)
        let appState = AppState(settingsStore: store)

        appState.selectStudyRecord(record)

        XCTAssertEqual(appState.lastAnswer, "수신 윈도우를 기준으로 전송량을 조절합니다.")
        XCTAssertEqual(
            appState.answerDraft(for: appState.studyRecords.first),
            "수신 윈도우를 기준으로 전송량을 조절합니다."
        )
        XCTAssertNil(appState.studyRecords.first?.answer)
        XCTAssertTrue(StudyAnswerPresentationPolicy.shouldShowEditor(for: appState.studyRecords.first))
    }

    func testPersistedGradingRequestHidesEditorEvenWhenResponseOmitsAnswer() {
        let record = StudyRecord(
            id: "record-grading",
            question: QuestionItem(
                question: "왜 멱등성이 필요한가요?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "분산 시스템",
            difficulty: .intermediate,
            gradingRequestID: "grading-request",
            gradingStatus: .queued,
            questionStatus: .grading
        )

        XCTAssertEqual(
            StudyAnswerPresentationPolicy.state(for: record),
            .grading(.queued)
        )
        XCTAssertFalse(StudyAnswerPresentationPolicy.shouldShowEditor(for: record))
    }

    func testAlreadySubmittedAnswerIsRejectedBeforeAnotherRequest() async throws {
        let suiteName = "DuplicateAnswerTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let requests = LockedRequestCounter()
        let client = makeClient { request in
            requests.increment()
            return Self.response(for: request, statusCode: 500, body: "{}")
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let record = StudyRecord(
            id: "record-submitted",
            question: QuestionItem(
                question: "중복 요청은 왜 위험한가요?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            answer: "부작용이 두 번 실행될 수 있습니다.",
            topic: "API",
            difficulty: .intermediate,
            answeredAt: Date(),
            gradingRequestID: "grading-submitted",
            gradingStatus: .queued
        )
        store.saveStudyRecord(record)
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.gradeStudyRoomRecord(
            record,
            answer: "답변을 바꿔서 다시 제출합니다."
        )

        XCTAssertEqual(requests.value, 0)
        XCTAssertEqual(appState.errorMessage, appState.strings.answerAlreadySubmitted)
        XCTAssertEqual(store.loadStudyRecords().first?.answer, record.answer)
    }

    func testLeavingDuringSubmissionKeepsRequestAliveUntilAnswerIsPersisted() async throws {
        let suiteName = "InFlightAnswerSubmissionTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let question = QuestionItem(
            question: "트랜잭션 격리가 필요한 이유는?",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_753_660_800)
        )
        let submittedAnswer = "동시 변경의 일관성을 지키기 위해서입니다."
        QuestionGenerationURLProtocol.responseDelayNanoseconds = 100_000_000
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/records/record-in-flight/answer")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "id": "record-in-flight",
                  "question": {
                    "question": "트랜잭션 격리가 필요한 이유는?",
                    "expectedAnswerHint": null,
                    "createdAt": "2025-07-28T00:00:00Z"
                  },
                  "answer": "\(submittedAnswer)",
                  "topic": "데이터베이스",
                  "difficulty": 5,
                  "answeredAt": "2025-07-28T00:01:00Z",
                  "gradingRequestId": "grading-in-flight",
                  "correlationId": "grading-in-flight",
                  "gradingStatus": "QUEUED",
                  "gradingLastEventId": 1,
                  "questionStatus": "GRADING"
                }
                """
            )
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let record = StudyRecord(
            id: "record-in-flight",
            question: question,
            topic: "데이터베이스",
            difficulty: .intermediate
        )
        store.saveStudyRecord(record)
        store.saveAnswerDraft(submittedAnswer, recordID: record.id)
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        let ownerID = "study-view-owner"

        let submission = Task { @MainActor in
            await appState.gradeStudyRoomRecord(
                record,
                answer: submittedAnswer,
                pollingOwnerID: ownerID
            )
        }
        try await Task.sleep(nanoseconds: 20_000_000)
        appState.cancelAnswerGradingPolling(
            ownerID: ownerID,
            reason: "study-view-disappeared"
        )
        await submission.value

        let persisted = try XCTUnwrap(
            store.loadStudyRecords().first(where: { $0.id == record.id })
        )
        XCTAssertEqual(persisted.answer, submittedAnswer)
        XCTAssertEqual(persisted.gradingRequestID, "grading-in-flight")
        XCTAssertEqual(persisted.correlationID, "grading-in-flight")
        XCTAssertEqual(persisted.gradingLastEventID, 1)
        XCTAssertEqual(persisted.gradingStatus, .queued)
        XCTAssertEqual(persisted.questionStatus, .grading)
        XCTAssertTrue(
            store.loadAnswerDraft(recordID: record.id)
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
        )
        XCTAssertFalse(StudyAnswerPresentationPolicy.shouldShowEditor(for: persisted))
        XCTAssertTrue(appState.isAnswerGradingInProgress(for: persisted))
    }

    func testDeletedRecordIsNotReinsertedIntoAllStudiesByStaleCommunityPage() {
        let deletedQuestion = CommunityQuestion(
            id: "record-42",
            question: "삭제된 기록",
            answer: "삭제된 답변",
            gradingResult: GradingResult(
                score: 80,
                isCorrect: true,
                feedback: "좋아요.",
                explanation: "설명"
            ),
            topic: "Swift",
            difficultyLevel: 5,
            status: "GRADED",
            source: "STUDY",
            createdAt: Date(timeIntervalSince1970: 1_000),
            answeredAt: Date(timeIntervalSince1970: 1_100),
            author: nil
        )
        let response = CommunityQuestionsResponse(
            questions: [deletedQuestion],
            totalCount: 1,
            limit: 20,
            offset: 0
        )
        var state = CommunityFeedStateStore()
        state.applyPage(response, offset: 0, reset: true)

        state.removeQuestion(id: deletedQuestion.id)
        state.applyPage(response, offset: 0, reset: true)

        XCTAssertFalse(state.questions.contains { $0.id == deletedQuestion.id })
        XCTAssertEqual(state.totalCount, 0)
    }

    func testClearedRecordsAreNotReinsertedIntoAllStudiesByStaleCommunityPage() {
        let questions = (1...3).map { index in
            CommunityQuestion(
                id: "record-\(index)",
                question: "삭제된 기록 \(index)",
                answer: "삭제된 답변",
                gradingResult: nil,
                topic: "Swift",
                difficultyLevel: 5,
                status: "GRADED",
                source: "STUDY",
                createdAt: Date(timeIntervalSince1970: TimeInterval(index)),
                answeredAt: nil,
                author: nil
            )
        }
        let response = CommunityQuestionsResponse(
            questions: questions,
            totalCount: questions.count,
            limit: 20,
            offset: 0
        )
        var state = CommunityFeedStateStore()
        state.applyPage(response, offset: 0, reset: true)

        state.removeQuestions(ids: Set(questions.map(\.id)))
        state.applyPage(response, offset: 0, reset: true)

        XCTAssertTrue(state.questions.isEmpty)
        XCTAssertEqual(state.totalCount, 0)
    }

    func testAllStudiesKeepsOnlySuccessfullyGradedQuestions() {
        let questions = ["GRADED", "FAILED", "GRADING", "UNGRADED"].enumerated().map { index, status in
            CommunityQuestion(
                id: "record-\(index)",
                question: "Question \(index)",
                answer: "Answer \(index)",
                gradingResult: nil,
                topic: "Swift",
                difficultyLevel: 5,
                status: status,
                source: "STUDY",
                createdAt: Date(timeIntervalSince1970: TimeInterval(index)),
                answeredAt: Date(timeIntervalSince1970: TimeInterval(index + 10)),
                author: nil
            )
        }
        let response = CommunityQuestionsResponse(
            questions: questions,
            totalCount: questions.count,
            limit: 20,
            offset: 0
        )
        var state = CommunityFeedStateStore()

        state.applyPage(response, offset: 0, reset: true)

        XCTAssertEqual(state.questions.map(\.status), ["GRADED"])
        XCTAssertEqual(state.totalCount, 1)
        XCTAssertEqual(state.offset, 4)
    }

    func testLikedQuestionsStateRemovesUnlikeWithoutSkippingShiftedPage() {
        func question(_ id: String) -> CommunityQuestion {
            CommunityQuestion(
                id: id,
                question: "Question \(id)",
                answer: "Answer",
                gradingResult: nil,
                topic: "Swift",
                difficultyLevel: 5,
                status: "GRADED",
                source: "STUDY",
                createdAt: Date(),
                answeredAt: Date(),
                author: nil,
                likeCount: 1,
                isLikedByMe: true
            )
        }

        var state = LikedQuestionsStateStore()
        state.applyPage(
            CommunityQuestionsResponse(
                questions: [question("1"), question("2")],
                totalCount: 3,
                limit: 20,
                offset: 0
            ),
            offset: 0,
            reset: true
        )

        state.removeQuestion(id: "1")

        XCTAssertEqual(state.questions.map(\.id), ["2"])
        XCTAssertEqual(state.totalCount, 2)
        XCTAssertEqual(state.offset, 1)

        state.applyPage(
            CommunityQuestionsResponse(
                questions: [question("3")],
                totalCount: 2,
                limit: 20,
                offset: 1
            ),
            offset: 1,
            reset: false
        )

        XCTAssertEqual(state.questions.map(\.id), ["2", "3"])
        XCTAssertEqual(state.offset, 2)
        XCTAssertFalse(state.canLoadMore())
    }

    func testLikeRequestTokenFromOldIdentityCannotClearNewIdentityRequest() throws {
        var requests = CommunityQuestionLikeRequestStore()
        let oldRequestID = try XCTUnwrap(requests.begin(questionID: "shared-question"))

        requests.reset()
        let newRequestID = try XCTUnwrap(requests.begin(questionID: "shared-question"))
        requests.finish(questionID: "shared-question", requestID: oldRequestID)

        XCTAssertTrue(requests.isCurrent(questionID: "shared-question", requestID: newRequestID))
        XCTAssertTrue(requests.contains(questionID: "shared-question"))
    }

    func testLikedQuestionsRequestUsesDedicatedV1URLAndLocalizedQuery() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/public/questions/liked")
            let items = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems ?? []
            let values = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
            XCTAssertEqual(values["query"], "Swift concurrency")
            XCTAssertEqual(values["limit"], "100")
            XCTAssertEqual(values["offset"], "0")
            XCTAssertEqual(values["tl"], "en")
            XCTAssertEqual(values["view"], "localized")
            return Self.response(
                for: request,
                statusCode: 200,
                body: #"{"questions":[],"totalCount":0,"limit":100,"offset":0}"#
            )
        }

        _ = try await client.fetchLikedPublicQuestions(
            registration: Self.signedInRegistration,
            query: "  Swift concurrency  ",
            limit: 120,
            offset: -10,
            language: .english,
            view: .localized
        )
    }

    func testTermsAgreementRequestSendsExactDocumentIdentity() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/terms/agreements")
            let body = try Self.bodyData(from: request)
            let payload = try XCTUnwrap(
                JSONSerialization.jsonObject(with: body) as? [String: Any]
            )
            XCTAssertEqual(payload["type"] as? String, "PRIVACY_POLICY")
            XCTAssertEqual(payload["version"] as? String, "2026-08-25")
            XCTAssertEqual(payload["contentHash"] as? String, "privacy-hash")
            XCTAssertEqual(payload["action"] as? String, "AGREED")
            XCTAssertEqual(payload["source"] as? String, "REQUIRED_GATE")
            return Self.response(
                for: request,
                statusCode: 200,
                body: #"{"permissions":[]}"#
            )
        }

        _ = try await client.saveTermsAgreement(
            registration: Self.signedInRegistration,
            type: .privacyPolicy,
            version: "2026-08-25",
            contentHash: "privacy-hash",
            action: .agreed,
            source: .requiredGate
        )
    }

    func testSignedInTier1ResolvesAdEntitlementBeforeFirstPublicFeedAndKeepsSlot() async throws {
        let suiteName = "Tier1NativeAdEntitlementTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let requestedPaths = LockedValue<[String]>([])
        let store = makeNestedStudyStore(defaults: defaults, databaseURL: databaseURL)
        let client = makeClient { request in
            let path = request.url?.path ?? ""
            requestedPaths.set(requestedPaths.value + [path])
            switch (request.httpMethod, path) {
            case ("GET", "/api/v1/billing/status"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.tier1BillingStatusResponse
                )
            case ("GET", "/api/v2/public/questions"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nativeAdSlotFeedResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.loadCommunityQuestions(reset: true, userInitiated: true)

        XCTAssertEqual(
            Array(requestedPaths.value.prefix(2)),
            ["/api/v1/billing/status", "/api/v2/public/questions"]
        )
        XCTAssertEqual(appState.billingStatus?.adFree, false)
        guard case .nativeAdSlot(let slot) = try XCTUnwrap(appState.communityFeedItems.first) else {
            return XCTFail("TIER1 should retain the server-delivered native ad slot.")
        }
        XCTAssertEqual(slot.slotID, "slot-tier1")
    }

    func testSignedInUnknownAdEntitlementDropsFirstPublicFeedSlot() async throws {
        let suiteName = "UnknownNativeAdEntitlementTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let store = makeNestedStudyStore(defaults: defaults, databaseURL: databaseURL)
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/billing/status"):
                return Self.response(for: request, statusCode: 503, body: #"{"message":"unavailable"}"#)
            case ("GET", "/api/v2/public/questions"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.nativeAdSlotFeedResponse
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.loadCommunityQuestions(reset: true, userInitiated: true)

        XCTAssertNil(appState.billingStatus)
        XCTAssertTrue(appState.communityFeedItems.isEmpty)
    }

    func testLikedQuestionsAppStateLoadsPagesSearchesAndPreservesRowsOnRefreshFailure() async throws {
        let suiteName = "LikedQuestionsAppStateTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let shouldFail = LockedValue(false)
        let store = makeNestedStudyStore(defaults: defaults, databaseURL: databaseURL)
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/public/questions/liked")
            if shouldFail.value {
                return Self.response(for: request, statusCode: 500, body: #"{"message":"temporary failure"}"#)
            }
            let items = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems ?? []
            let values = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
            if values["query"] == "redis" {
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.communityQuestionPageJSON(ids: ["search-1"], totalCount: 1, offset: 0)
                )
            }
            if values["offset"] == "2" {
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.communityQuestionPageJSON(ids: ["liked-3"], totalCount: 3, offset: 2)
                )
            }
            return Self.response(
                for: request,
                statusCode: 200,
                body: Self.communityQuestionPageJSON(ids: ["liked-1", "liked-2"], totalCount: 3, offset: 0)
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        XCTAssertFalse(appState.hasLoadedLikedCommunityQuestions)
        await appState.loadLikedCommunityQuestions(userInitiated: true)
        XCTAssertEqual(appState.likedCommunityQuestions.map(\.id), ["liked-1", "liked-2"])
        XCTAssertEqual(appState.likedCommunityQuestionsOffset, 2)
        XCTAssertTrue(appState.canLoadMoreLikedCommunityQuestions)

        await appState.loadNextLikedCommunityQuestionsPage()
        XCTAssertEqual(appState.likedCommunityQuestions.map(\.id), ["liked-1", "liked-2", "liked-3"])
        XCTAssertFalse(appState.canLoadMoreLikedCommunityQuestions)

        await appState.loadLikedCommunityQuestions(query: " redis ", reset: true, userInitiated: true)
        XCTAssertEqual(appState.likedCommunityQuestions.map(\.id), ["search-1"])

        shouldFail.set(true)
        await appState.loadLikedCommunityQuestions(
            query: "redis",
            reset: true,
            userInitiated: true,
            preserveExistingOnFailure: true
        )
        XCTAssertEqual(appState.likedCommunityQuestions.map(\.id), ["search-1"])
        XCTAssertNotNil(appState.likedCommunityQuestionsErrorMessage)
    }

    func testUnlikeSynchronizesFeedsAndRejectsConcurrentLikeRequest() async throws {
        let suiteName = "LikedQuestionsUnlikeTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let likeRequestCount = LockedRequestCounter()
        let store = makeNestedStudyStore(defaults: defaults, databaseURL: databaseURL)
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v2/public/questions"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.communityQuestionPageJSON(ids: ["liked-1"], totalCount: 1, offset: 0)
                )
            case ("GET", "/api/v1/public/questions/liked"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: Self.communityQuestionPageJSON(ids: ["liked-1"], totalCount: 1, offset: 0)
                )
            case ("DELETE", "/api/v1/public/questions/liked-1/like"):
                likeRequestCount.increment()
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: #"{"questionId":"liked-1","likeCount":0,"isLikedByMe":false}"#
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        await appState.loadCommunityQuestions(reset: true, userInitiated: true)
        await appState.loadLikedCommunityQuestions(reset: true, userInitiated: true)
        let question = try XCTUnwrap(appState.likedCommunityQuestions.first)
        QuestionGenerationURLProtocol.responseDelayHandler = { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/public/questions/liked"):
                250_000_000
            case ("DELETE", "/api/v1/public/questions/liked-1/like"):
                80_000_000
            default:
                0
            }
        }

        let staleRefresh = Task { @MainActor in
            await appState.loadLikedCommunityQuestions(
                reset: true,
                userInitiated: true,
                preserveExistingOnFailure: true
            )
        }
        let didStartRefresh = await waitUntil {
            appState.isLoadingLikedCommunityQuestions
        }
        XCTAssertTrue(didStartRefresh)

        let firstRequest = Task { @MainActor in
            await appState.setCommunityQuestionLike(question, isLiked: false)
        }
        let didStart = await waitUntil {
            appState.isCommunityQuestionLikeRequestInFlight(questionID: question.id)
        }
        XCTAssertTrue(didStart)
        let concurrentResult = await appState.setCommunityQuestionLike(question, isLiked: false)
        XCTAssertNil(concurrentResult)
        let firstResult = await firstRequest.value
        await staleRefresh.value

        XCTAssertEqual(firstResult?.isLikedByMe, false)
        XCTAssertEqual(likeRequestCount.value, 1)
        XCTAssertTrue(appState.likedCommunityQuestions.isEmpty)
        XCTAssertEqual(appState.likedCommunityQuestionsTotalCount, 0)
        XCTAssertEqual(appState.likedCommunityQuestionsOffset, 0)
        XCTAssertEqual(appState.communityQuestions.first?.isLikedByMe, false)
        XCTAssertEqual(appState.communityQuestions.first?.likeCount, 0)
        XCTAssertFalse(appState.isCommunityQuestionLikeRequestInFlight(questionID: question.id))

        appState.signOutFromCommunity()
        XCTAssertTrue(appState.likedCommunityQuestions.isEmpty)
        XCTAssertFalse(appState.hasLoadedLikedCommunityQuestions)
    }

    func testBackendUnauthorizedEventClearsTransientLikedQuestionsState() async throws {
        let suiteName = "LikedQuestionsUnauthorizedTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }
        let eventProvider = TestAppNotificationEventProvider()
        let store = makeNestedStudyStore(defaults: defaults, databaseURL: databaseURL)
        store.savePendingReferralAttribution(
            PendingReferralAttribution(
                code: "BS-ABCDEFGH",
                source: .authentication,
                accountID: 7,
                state: .serverConfirmed
            )
        )
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/public/questions/liked")
            return Self.response(
                for: request,
                statusCode: 200,
                body: Self.communityQuestionPageJSON(ids: ["liked-1"], totalCount: 1, offset: 0)
            )
        }
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appNotificationEventProvider: eventProvider
        )
        await appState.loadLikedCommunityQuestions(reset: true, userInitiated: true)
        XCTAssertEqual(appState.likedCommunityQuestions.map(\.id), ["liked-1"])

        eventProvider.sendBackendUnauthorized()

        XCTAssertTrue(appState.likedCommunityQuestions.isEmpty)
        XCTAssertFalse(appState.hasLoadedLikedCommunityQuestions)
        XCTAssertFalse(appState.isCommunitySessionActive)
        XCTAssertNil(store.loadRemotePushRegistration()?.accessToken)
        XCTAssertNil(store.loadPendingReferralAttribution())
        XCTAssertNil(appState.referralNotice)
    }

    func testCreateQuestionSendsIdempotencyKeyAndDecodesAcceptedProcess() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/studies/16/questions")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), "request-key-1")
            return Self.response(
                for: request,
                statusCode: 202,
                body: """
                {
                  "correlationId": "correlation-1",
                  "studyId": "16",
                  "topicId": "16",
                  "status": "QUEUED",
                  "pollAfterMs": 250,
                  "submittedAt": "2026-07-27T12:00:00Z"
                }
                """
            )
        }

        let accepted = try await client.createQuestion(
            registration: Self.registration,
            studyID: 16,
            idempotencyKey: "request-key-1"
        )

        XCTAssertEqual(accepted.correlationID, "correlation-1")
        XCTAssertEqual(accepted.status, .queued)
        XCTAssertEqual(accepted.pollAfterMilliseconds, 250)
    }

    func testFetchProcessUsesCorrelationIDAndDecodesTerminalFailure() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(
                request.url?.path,
                "/api/v1/question-processes/correlation-1"
            )
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "correlationId": "correlation-1",
                  "status": "FAILED",
                  "currentStep": "GENERATING",
                  "terminal": true,
                  "pollAfterMs": null,
                  "questionId": null,
                  "question": null,
                  "failedStep": "GENERATING",
                  "error": {
                    "code": "QUESTION_GENERATION_FAILED",
                    "message": "Question generation failed.",
                    "retryable": false
                  },
                  "updatedAt": "2026-07-27T12:00:01Z",
                  "completedAt": null
                }
                """
            )
        }

        let process = try await client.fetchQuestionGenerationProcess(
            registration: Self.registration,
            correlationID: "correlation-1"
        )

        XCTAssertTrue(process.terminal)
        XCTAssertEqual(process.status, .failed)
        XCTAssertEqual(process.failedStep, .generating)
        XCTAssertEqual(process.error?.retryable, false)
    }

    func testFetchAnswerGradingProcessUsesCorrelationIDAndEventCursor() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/answer-processes/grading-77")
            XCTAssertEqual(
                request.url?.absoluteString,
                "https://example.test/api/v1/answer-processes/grading-77?after=4"
            )
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "correlationId": "grading-77",
                  "recordId": "record-77",
                  "status": "COMPLETED",
                  "questionStatus": "GRADED",
                  "terminal": true,
                  "pollAfterMs": null,
                  "events": [
                    {
                      "id": 5,
                      "recordId": "record-77",
                      "correlationId": "grading-77",
                      "status": "COMPLETED",
                      "questionStatus": "GRADED",
                      "errorMessage": null,
                      "occurredAt": "2026-07-27T12:00:01Z"
                    }
                  ],
                  "errorMessage": null,
                  "updatedAt": "2026-07-27T12:00:01Z"
                }
                """
            )
        }

        let process = try await client.fetchAnswerGradingProcess(
            registration: Self.registration,
            correlationID: "grading-77",
            afterEventID: 4
        )

        XCTAssertEqual(process.correlationID, "grading-77")
        XCTAssertEqual(process.recordID, "record-77")
        XCTAssertEqual(process.questionStatus, .graded)
        XCTAssertTrue(process.terminal)
        XCTAssertNil(process.pollAfterMilliseconds)
        XCTAssertEqual(process.events.map(\.id), [5])
        XCTAssertEqual(process.events.first?.status, .completed)
        XCTAssertEqual(process.events.first?.questionStatus, .graded)
    }

    func testSigningOutStopsAnswerGradingProcessPolling() async {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pollCounter = LockedRequestCounter()
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/records/record-sign-out/answer"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-sign-out",
                      "question": {
                        "question": "How should polling react to sign-out?",
                        "expectedAnswerHint": null,
                        "createdAt": "2026-07-28T00:00:00Z"
                      },
                      "answer": "It should stop.",
                      "topic": "Concurrency",
                      "difficulty": 5,
                      "gradingRequestId": "grading-sign-out",
                      "gradingStatus": "QUEUED"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-sign-out"):
                pollCounter.increment()
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-sign-out",
                      "recordId": "record-sign-out",
                      "status": "QUEUED",
                      "terminal": false,
                      "pollAfterMs": 1000,
                      "events": [],
                      "errorMessage": null,
                      "updatedAt": "2026-07-28T00:00:00Z"
                    }
                    """
                )
            case ("POST", "/api/v1/auth/logout"):
                return Self.response(for: request, statusCode: 200, body: "{}")
            default:
                XCTFail("Unexpected request: \(request.httpMethod ?? "-") \(request.url?.path ?? "-")")
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(
            RemotePushRegistration(
                deviceID: "device-1",
                clientSecret: "client-secret",
                apnsToken: "",
                accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                accessTokenExpiresAt: Date().addingTimeInterval(3_600)
            )
        )
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        let record = StudyRecord(
            id: "record-sign-out",
            question: QuestionItem(
                question: "How should polling react to sign-out?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "Concurrency",
            difficulty: .intermediate
        )

        let gradingTask = Task { @MainActor in
            await appState.gradeRecord(record, answer: "It should stop.")
        }
        for _ in 0..<100 where pollCounter.value == 0 {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(
            pollCounter.value,
            1,
            "Polling did not start. error=\(appState.errorMessage ?? "nil"), status=\(appState.statusMessage ?? "nil")"
        )

        appState.signOutFromCommunity()
        await gradingTask.value
        try? await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(
            pollCounter.value,
            1,
            "Polling continued or never started. error=\(appState.errorMessage ?? "nil"), status=\(appState.statusMessage ?? "nil")"
        )
        XCTAssertFalse(appState.isGradingAnswer)
        XCTAssertNil(appState.answerGradingStatusMessage)
    }

    func testLeavingAnswerScreenStopsThreeSecondPollingImmediately() async {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pollCounter = LockedRequestCounter()
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/records/record-screen-exit/answer"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-screen-exit",
                      "question": {
                        "question": "What should happen after leaving the screen?",
                        "expectedAnswerHint": null,
                        "createdAt": "2026-07-28T00:00:00Z"
                      },
                      "answer": "Polling should stop.",
                      "topic": "Concurrency",
                      "difficulty": 5,
                      "gradingRequestId": "grading-screen-exit",
                      "gradingStatus": "QUEUED"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-screen-exit"):
                pollCounter.increment()
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-screen-exit",
                      "recordId": "record-screen-exit",
                      "status": "QUEUED",
                      "terminal": false,
                      "pollAfterMs": 250,
                      "events": [],
                      "errorMessage": null,
                      "updatedAt": "2026-07-28T00:00:00Z"
                    }
                    """
                )
            default:
                XCTFail("Unexpected request: \(request.httpMethod ?? "-") \(request.url?.path ?? "-")")
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveRemotePushRegistration(
            RemotePushRegistration(
                deviceID: "device-1",
                clientSecret: "client-secret",
                apnsToken: "",
                accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                accessTokenExpiresAt: Date().addingTimeInterval(3_600)
            )
        )
        let sleepProvider = BlockingRecordingAppSleepProvider()
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appSleepProvider: sleepProvider
        )
        let record = StudyRecord(
            id: "record-screen-exit",
            question: QuestionItem(
                question: "What should happen after leaving the screen?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "Concurrency",
            difficulty: .intermediate
        )
        let ownerID = UUID().uuidString
        let gradingTask = Task { @MainActor in
            await appState.gradeRecord(
                record,
                answer: "Polling should stop.",
                pollingOwnerID: ownerID
            )
        }

        for _ in 0..<100 {
            if !(await sleepProvider.requestedNanoseconds()).isEmpty {
                break
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }

        let requestedNanoseconds = await sleepProvider.requestedNanoseconds()
        XCTAssertEqual(requestedNanoseconds, [3_000_000_000])
        XCTAssertEqual(pollCounter.value, 1)
        let persistedQueuedRecord = try? XCTUnwrap(
            store.loadStudyRecords().first(where: { $0.id == record.id })
        )
        XCTAssertEqual(persistedQueuedRecord?.answer, "Polling should stop.")
        XCTAssertEqual(persistedQueuedRecord?.gradingRequestID, "grading-screen-exit")
        XCTAssertEqual(persistedQueuedRecord?.gradingStatus, .queued)

        appState.cancelAnswerGradingPolling(
            ownerID: ownerID,
            reason: "test-screen-disappeared"
        )
        await gradingTask.value
        try? await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(pollCounter.value, 1)
        XCTAssertFalse(appState.isGradingAnswer)
        XCTAssertNil(appState.answerGradingStatusMessage)
        XCTAssertTrue(appState.isAnswerGradingInProgress(for: persistedQueuedRecord))
        XCTAssertEqual(
            appState.gradingPresentationMessage(for: persistedQueuedRecord),
            appState.strings.gradingQueued
        )
        var requestOnlyRecord = persistedQueuedRecord
        requestOnlyRecord?.gradingStatus = nil
        XCTAssertTrue(appState.isAnswerGradingInProgress(for: requestOnlyRecord))
        XCTAssertEqual(
            appState.gradingPresentationMessage(for: requestOnlyRecord),
            appState.strings.gradingQueued
        )
    }

    func testReopeningStudyRoomRestoresPersistedAnswerAndGradingState() async throws {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let category = StudyCategory(
            id: "11",
            title: "운영체제",
            difficulty: .intermediate
        )
        let question = QuestionItem(
            question: "프로세스와 스레드의 차이는 무엇인가요?",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_753_660_800)
        )
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: category.title,
                difficulty: category.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [category],
                selectedStudyCategoryID: category.id
            )
        )
        store.saveStudyRecord(
            StudyRecord(
                id: "record-11",
                studyID: 11,
                question: question,
                topic: category.title,
                difficulty: category.difficulty
            )
        )
        store.saveQuestion(question)
        store.saveLastAnswer("")
        store.saveRemotePushRegistration(Self.signedInRegistration)

        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/studies/11")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "id": 11,
                  "topic": "운영체제",
                  "difficultyLevel": 5,
                  "intervalMinutes": 15,
                  "enabled": true,
                  "activeForQuestions": true,
                  "notificationSound": null,
                  "customPrompt": "짧게",
                  "openaiModel": "gpt-5.4",
                  "maxHistoryCount": 100,
                  "nextDueAt": null,
                  "lastSentAt": null,
                  "lastError": null,
                  "pendingQuestion": {
                    "id": "record-11",
                    "studyId": 11,
                    "question": {
                      "question": "프로세스와 스레드의 차이는 무엇인가요?",
                      "expectedAnswerHint": null,
                      "createdAt": "2025-07-28T00:00:00Z"
                    },
                    "answer": "프로세스는 독립된 메모리를 갖고 스레드는 메모리를 공유합니다.",
                    "gradingResult": null,
                    "topic": "운영체제",
                    "difficulty": 5,
                    "answeredAt": "2025-07-28T00:01:00Z",
                    "isPublic": true,
                    "gradingRequestId": "grading-11",
                    "gradingStatus": "FAILED",
                    "gradingError": "일시적인 채점 오류"
                  },
                  "latestQuestion": null,
                  "createdAt": "2025-07-28T00:00:00Z",
                  "updatedAt": "2025-07-28T00:01:00Z"
                }
                """
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.prepareStudyRoom(categoryID: category.id)

        let restored = try XCTUnwrap(
            appState.studyRoomRecordForDisplay(categoryID: category.id)
        )
        XCTAssertEqual(
            restored.answer,
            "프로세스는 독립된 메모리를 갖고 스레드는 메모리를 공유합니다."
        )
        XCTAssertEqual(restored.gradingRequestID, "grading-11")
        XCTAssertEqual(restored.gradingStatus, .failed)
        XCTAssertEqual(store.loadStudyRecords().first?.answer, restored.answer)
        XCTAssertEqual(appState.lastAnswer, restored.answer)
    }

    func testReopeningStudyRoomUsesDetailEndpointAndShowsLatestCompletedQuestionWhenNoPendingQuestionExists() async throws {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let category = StudyCategory(id: "42", title: "Swift", difficulty: .level5)
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: category.title,
                difficulty: category.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 30,
                studyCategories: [category],
                selectedStudyCategoryID: category.id
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let requestedPaths = LockedValue<[String]>([])
        let client = makeClient { request in
            requestedPaths.set(requestedPaths.value + [request.url?.path ?? ""])
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "id": 42,
                  "topic": "Swift",
                  "difficultyLevel": 5,
                  "intervalMinutes": 30,
                  "enabled": true,
                  "activeForQuestions": true,
                  "notificationSound": null,
                  "customPrompt": "",
                  "openaiModel": "gpt-5.4",
                  "maxHistoryCount": 100,
                  "nextDueAt": null,
                  "lastSentAt": null,
                  "lastError": null,
                  "pendingQuestion": null,
                  "latestQuestion": {
                    "id": "latest-42",
                    "studyId": 42,
                    "question": {
                      "question": "가장 최근 완료 질문",
                      "expectedAnswerHint": null,
                      "createdAt": "2026-07-30T00:00:00Z"
                    },
                    "answer": "사용자 답변",
                    "gradingResult": {
                      "score": 90,
                      "correct": true,
                      "feedback": "좋아요",
                      "explanation": "완료된 채점 설명"
                    },
                    "topic": "Swift",
                    "difficulty": 5,
                    "answeredAt": "2026-07-30T00:01:00Z",
                    "isPublic": true,
                    "gradingRequestId": "grading-42",
                    "gradingStatus": "COMPLETED",
                    "gradingError": null
                  },
                  "createdAt": "2026-07-30T00:00:00Z",
                  "updatedAt": "2026-07-30T00:01:00Z"
                }
                """
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.prepareStudyRoom(categoryID: category.id)

        XCTAssertEqual(requestedPaths.value, ["/api/v1/studies/42"])
        let displayed = try XCTUnwrap(appState.studyRoomRecordForDisplay(categoryID: category.id))
        XCTAssertEqual(displayed.id, "latest-42")
        XCTAssertEqual(displayed.answer, "사용자 답변")
        XCTAssertEqual(displayed.gradingResult?.feedback, "좋아요")
    }

    func testReopeningStudyRoomResumesPersistedAnswerGrading() async throws {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let category = StudyCategory(
            id: "12",
            title: "데이터베이스",
            difficulty: .intermediate
        )
        let question = QuestionItem(
            question: "트랜잭션 격리 수준을 설명하세요.",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_753_660_800)
        )
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: category.title,
                difficulty: category.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [category],
                selectedStudyCategoryID: category.id
            )
        )
        store.saveStudyRecord(
            StudyRecord(
                id: "record-12",
                studyID: 12,
                question: question,
                answer: "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                topic: category.title,
                difficulty: category.difficulty,
                answeredAt: Date(timeIntervalSince1970: 1_753_660_860),
                gradingRequestID: "grading-12",
                gradingStatus: .judging,
                gradingLastEventID: 4
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let gradingCursorQuery = LockedValue<String?>(nil)

        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/studies/12"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": 12,
                      "topic": "데이터베이스",
                      "difficultyLevel": 5,
                      "intervalMinutes": 15,
                      "enabled": true,
                      "activeForQuestions": true,
                      "notificationSound": null,
                      "customPrompt": "짧게",
                      "openaiModel": "gpt-5.4",
                      "maxHistoryCount": 100,
                      "nextDueAt": null,
                      "lastSentAt": null,
                      "lastError": null,
                      "pendingQuestion": {
                        "id": "record-12",
                        "studyId": 12,
                        "question": {
                          "question": "트랜잭션 격리 수준을 설명하세요.",
                          "expectedAnswerHint": null,
                          "createdAt": "2025-07-28T00:00:00Z"
                        },
                        "answer": "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                        "gradingResult": null,
                        "topic": "데이터베이스",
                        "difficulty": 5,
                        "answeredAt": "2025-07-28T00:01:00Z",
                        "isPublic": true,
                        "gradingRequestId": "grading-12",
                        "gradingStatus": "JUDGING",
                        "gradingError": null
                      },
                      "latestQuestion": null,
                      "createdAt": "2025-07-28T00:00:00Z",
                      "updatedAt": "2025-07-28T00:01:00Z"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-12"):
                let queryItems = URLComponents(
                    url: request.url!,
                    resolvingAgainstBaseURL: false
                )?.queryItems
                gradingCursorQuery.set(
                    queryItems?.first(where: { $0.name == "after" })?.value
                )
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-12",
                      "recordId": "record-12",
                      "status": "COMPLETED",
                      "terminal": true,
                      "pollAfterMs": null,
                      "events": [
                        {
                          "id": 5,
                          "recordId": "record-12",
                          "correlationId": "grading-12",
                          "status": "COMPLETED",
                          "errorMessage": null,
                          "occurredAt": "2025-07-28T00:02:00Z"
                        }
                      ],
                      "errorMessage": null,
                      "updatedAt": "2025-07-28T00:02:00Z"
                    }
                    """
                )
            case ("GET", "/api/v1/records/record-12"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-12",
                      "studyId": 12,
                      "question": {
                        "question": "트랜잭션 격리 수준을 설명하세요.",
                        "expectedAnswerHint": null,
                        "createdAt": "2025-07-28T00:00:00Z"
                      },
                      "answer": "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                      "gradingResult": {
                        "score": 91,
                        "correct": true,
                        "feedback": "핵심을 잘 설명했습니다.",
                        "explanation": "격리 수준별 현상까지 연결하면 더 좋습니다."
                      },
                      "topic": "데이터베이스",
                      "difficulty": 5,
                      "answeredAt": "2025-07-28T00:01:00Z",
                      "isPublic": true,
                      "gradingRequestId": "grading-12",
                      "gradingStatus": "COMPLETED",
                      "gradingError": null
                    }
                    """
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let sleepProvider = RecordingAppSleepProvider()
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appSleepProvider: sleepProvider
        )

        await appState.prepareStudyRoom(
            categoryID: category.id,
            gradingPollingOwnerID: "study-view-12"
        )

        let restored = try XCTUnwrap(
            appState.studyRoomRecordForDisplay(categoryID: category.id)
        )
        XCTAssertEqual(
            restored.answer,
            "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다."
        )
        XCTAssertEqual(restored.gradingResult?.score, 91)
        XCTAssertEqual(restored.gradingStatus, .completed)
        XCTAssertEqual(restored.gradingLastEventID, 5)
        XCTAssertEqual(
            gradingCursorQuery.value,
            "4",
            "Reopening must continue after the last persisted event instead of reading the stream from zero."
        )
        XCTAssertFalse(appState.isGradingAnswer)
        let replayDelays = await sleepProvider.requestedNanoseconds()
        XCTAssertEqual(
            replayDelays,
            [],
            "Reopening must use the process snapshot instead of replaying historical grading events."
        )
    }

    func testJapaneseLanguageUsesJapaneseLocaleAndBackendCode() {
        XCTAssertEqual(AppLanguage.japanese.locale.identifier, "ja_JP")
        XCTAssertEqual(AppLanguage.japanese.backendCode, "ja")
        XCTAssertEqual(AppLanguage.japanese.displayName, "日本語")
        XCTAssertEqual(AppStrings(language: .japanese).showOriginal, "原文を見る")
        XCTAssertEqual(AppStrings(language: .japanese).showTranslation, "翻訳を見る")
        XCTAssertEqual(AppStrings(language: .japanese).translatedIntoLanguage, "日本語に翻訳済み")
        XCTAssertEqual(AppStrings(language: .japanese).reviewCancelledPurchase, "購入確認と返金")
    }

    func testContentLanguageRecognizerSupportsKoreanEnglishAndJapanese() {
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("이 답변은 한국어로 작성되었습니다.", fallback: .english),
            "ko"
        )
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("This answer is written in English.", fallback: .korean),
            "en"
        )
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("この回答は日本語で書かれています。", fallback: .english),
            "ja"
        )
        XCTAssertEqual(ContentLanguageRecognizer.detect("OK", fallback: .japanese), "ja")
    }

    func testStatisticsAutoRefreshIgnoresRapidTabReselection() {
        let now = Date(timeIntervalSince1970: 1_785_427_200)

        XCTAssertTrue(StatisticsAutoRefreshPolicy.shouldRefresh(lastRefreshAt: nil, now: now))
        XCTAssertFalse(
            StatisticsAutoRefreshPolicy.shouldRefresh(
                lastRefreshAt: now,
                now: now.addingTimeInterval(59)
            )
        )
        XCTAssertTrue(
            StatisticsAutoRefreshPolicy.shouldRefresh(
                lastRefreshAt: now,
                now: now.addingTimeInterval(60)
            )
        )
    }

    func testStatisticsTopicCaptionUsesStableNameTieBreak() {
        XCTAssertEqual(
            StatsTopicFocusPolicy.topTopic(from: ["Swift", "Redis", "Redis", "Swift"]),
            "Redis"
        )
        XCTAssertEqual(
            StatsTopicFocusPolicy.topTopic(from: ["Redis", "Swift", "Swift", "Redis"]),
            "Redis"
        )
    }

    func testPixelChartLayoutQuantizesAbilityIntoTenRows() {
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(-3), 1)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(1.49), 1)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(1.5), 2)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(6.4), 6)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(6.5), 7)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(12), 10)
        XCTAssertEqual(PixelChartLayoutPolicy.quantizedAbility(.nan), 1)
        XCTAssertEqual(PixelChartLayoutPolicy.normalizedAbility(1), 0, accuracy: 0.001)
        XCTAssertEqual(PixelChartLayoutPolicy.normalizedAbility(10), 1, accuracy: 0.001)
    }

    func testPixelChartLayoutBuildsHorizontalThenVerticalStairSteps() {
        let points = [
            CGPoint(x: 0, y: 9),
            CGPoint(x: 10, y: 5),
            CGPoint(x: 20, y: 6)
        ]

        XCTAssertEqual(
            PixelChartLayoutPolicy.staircasePoints(points),
            [
                CGPoint(x: 0, y: 9),
                CGPoint(x: 10, y: 9),
                CGPoint(x: 10, y: 5),
                CGPoint(x: 20, y: 5),
                CGPoint(x: 20, y: 6)
            ]
        )
        XCTAssertEqual(PixelChartLayoutPolicy.staircasePoints([]), [])
        XCTAssertEqual(
            PixelChartLayoutPolicy.staircasePoints([CGPoint(x: 4, y: 7)]),
            [CGPoint(x: 4, y: 7)]
        )
    }

    func testStudyGrowthPeriodUsesStableUtcDayBounds() {
        let morning = Date(timeIntervalSince1970: 1_785_369_600)
        let evening = morning.addingTimeInterval(86_399)

        let morningBounds = StudyGrowthPeriod.last30Days.bounds(now: morning)
        let eveningBounds = StudyGrowthPeriod.last30Days.bounds(now: evening)

        XCTAssertEqual(morningBounds.startAt, eveningBounds.startAt)
        XCTAssertEqual(morningBounds.endAt, eveningBounds.endAt)
        XCTAssertEqual(
            morningBounds.endAt.timeIntervalSince(morningBounds.startAt),
            30 * 86_400
        )
    }

    func testLocalizationMetadataDecodesMixedSourceAndDisplayLanguages() throws {
        let data = Data(
            """
            {
              "question": {
                "sourceLanguage": "en",
                "requestedLanguage": "ja",
                "displayLanguage": "ja",
                "translationState": "TRANSLATED",
                "isTranslated": true,
                "originalAvailable": true,
                "translationReason": "EXPLICIT_TL"
              },
              "answer": {
                "sourceLanguage": "ko",
                "requestedLanguage": "ja",
                "displayLanguage": "ko",
                "translationState": "PENDING",
                "isTranslated": false,
                "originalAvailable": true,
                "translationReason": "EXPLICIT_TL"
              },
              "aiResponse": null
            }
            """.utf8
        )

        let metadata = try JSONDecoder().decode(RecordLocalizationMetadata.self, from: data)

        XCTAssertTrue(metadata.containsTranslation)
        XCTAssertTrue(metadata.containsPendingTranslation)
        XCTAssertEqual(metadata.question.sourceLanguage, "en")
        XCTAssertEqual(metadata.question.displayLanguage, "ja")
        XCTAssertEqual(metadata.answer?.sourceLanguage, "ko")
        XCTAssertEqual(metadata.answer?.displayLanguage, "ko")
    }

    func testLocalizationMetadataDecodesLegacyTranslatedField() throws {
        let data = Data(
            """
            {
              "sourceLanguage": "ko",
              "requestedLanguage": "ko",
              "displayLanguage": "ko",
              "translationState": "ORIGINAL",
              "translated": false,
              "originalAvailable": false,
              "translationReason": "EXPLICIT_TL"
            }
            """.utf8
        )

        let metadata = try JSONDecoder().decode(ContentLocalizationMetadata.self, from: data)

        XCTAssertFalse(metadata.isTranslated)
        XCTAssertEqual(metadata.displayLanguage, "ko")
    }

    func testSubmittedBackendAnswerOverridesStaleEditableDraft() throws {
        let suiteName = "SubmittedAnswerTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let record = StudyRecord(
            id: "submitted-answer",
            question: QuestionItem(
                question: "트랜잭션이란 무엇인가요?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            answer: "서버가 수락한 최종 답변",
            topic: "데이터베이스",
            difficulty: .intermediate,
            gradingRequestID: "grading-submitted",
            gradingStatus: .judging
        )
        store.saveAnswerDraft("제출 전에 남아 있던 편집 초안", recordID: record.id)
        let appState = AppState(settingsStore: store)

        XCTAssertEqual(appState.answerDraft(for: record), "서버가 수락한 최종 답변")
        XCTAssertFalse(StudyAnswerPresentationPolicy.shouldShowEditor(for: record))
        XCTAssertEqual(
            StudyAnswerPresentationPolicy.submittedAnswer(for: record),
            "서버가 수락한 최종 답변"
        )

        var unansweredRecord = record
        unansweredRecord.answer = nil
        unansweredRecord.gradingRequestID = nil
        unansweredRecord.correlationID = nil
        unansweredRecord.gradingStatus = nil
        unansweredRecord.questionStatus = .ungraded
        XCTAssertTrue(StudyAnswerPresentationPolicy.shouldShowEditor(for: unansweredRecord))
        XCTAssertEqual(appState.answerDraft(for: unansweredRecord), "제출 전에 남아 있던 편집 초안")
    }

    func testLearningRhythmUpdatesSettingsDraftAndSurvivesRelaunch() throws {
        let suiteName = "LearningRhythmTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let appState = AppState(settingsStore: store)

        appState.setTimerInterval(47)

        XCTAssertEqual(appState.settings.intervalMinutes, 47)
        XCTAssertEqual(appState.draftSettings.intervalMinutes, 47)
        XCTAssertEqual(store.loadSettings().intervalMinutes, 47)

        let relaunchedState = AppState(settingsStore: store)
        XCTAssertEqual(relaunchedState.settings.intervalMinutes, 47)
        XCTAssertEqual(relaunchedState.draftSettings.intervalMinutes, 47)
    }

    func testBackendSettingsRefreshUsesAuthoritativeServerLearningRhythm() async throws {
        let suiteName = "BackendLearningRhythmTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: "Redis",
                difficulty: .level6,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [StudyCategory(title: "Redis", difficulty: .level6)]
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "topic": "Redis",
                  "difficultyLevel": 6,
                  "intervalMinutes": 47,
                  "enabled": true,
                  "notificationSound": "default",
                  "customPrompt": "",
                  "appLanguage": "ko",
                  "openAIModel": "\(StudySettings.defaultOpenAIModel)",
                  "maxHistoryCount": 100,
                  "isQuestionPublic": true,
                  "openAIKeyConfigured": true
                }
                """
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        appState.beginSettingsEditing()
        await appState.loadBackendSettingsForEditing()

        XCTAssertEqual(appState.settings.intervalMinutes, 47)
        XCTAssertEqual(appState.draftSettings.intervalMinutes, 47)
        XCTAssertEqual(store.loadSettings().intervalMinutes, 47)
    }

    func testBackendSettingsWithoutLearningRhythmFailsInsteadOfDefaultingToFifteen() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "topic": "Redis",
                  "difficultyLevel": 6,
                  "enabled": true,
                  "notificationSound": "default",
                  "customPrompt": "",
                  "appLanguage": "ko",
                  "openAIModel": "\(StudySettings.defaultOpenAIModel)",
                  "maxHistoryCount": 100,
                  "isQuestionPublic": true,
                  "openAIKeyConfigured": true
                }
                """
            )
        }

        do {
            _ = try await client.fetchSettings(registration: Self.registration)
            XCTFail("Missing intervalMinutes must not silently become 15 minutes.")
        } catch DecodingError.keyNotFound(let key, _) {
            XCTAssertEqual(key.stringValue, "intervalMinutes")
        } catch {
            XCTFail("Expected a missing intervalMinutes decoding error, got \(error).")
        }
    }

    func testFirstSettingsLoadReadsServerBeforeUploadingLocalDefault() async throws {
        let suiteName = "BackendSettingsBootstrapTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/devices/register"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "deviceId": "device-bootstrap",
                      "clientSecret": "client-secret-bootstrap",
                      "accessToken": "\(Self.signedInRegistration.accessToken ?? "")",
                      "accessTokenExpiresAt": "2027-07-30T00:00:00Z"
                    }
                    """
                )
            case ("GET", "/api/v1/settings"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "topic": "Redis",
                      "difficultyLevel": 6,
                      "intervalMinutes": 47,
                      "enabled": true,
                      "notificationSound": "default",
                      "customPrompt": "",
                      "appLanguage": "ko",
                      "openAIModel": "\(StudySettings.defaultOpenAIModel)",
                      "maxHistoryCount": 100,
                      "isQuestionPublic": true,
                      "openAIKeyConfigured": true
                    }
                    """
                )
            case ("PUT", "/api/v1/settings"):
                XCTFail("Local 15-minute default was uploaded before server settings were read.")
                return Self.response(for: request, statusCode: 200, body: "{}")
            default:
                XCTFail("Unexpected request: \(request.httpMethod ?? "-") \(request.url?.path ?? "-")")
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        appState.beginSettingsEditing()
        await appState.loadBackendSettingsForEditing()

        XCTAssertEqual(appState.settings.intervalMinutes, 47)
        XCTAssertEqual(store.loadSettings().intervalMinutes, 47)
    }

    func testPendingProcessSurvivesSettingsStoreRecreation() {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pending = PendingQuestionGenerationProcess(
            idempotencyKey: "request-key-1",
            correlationID: "correlation-1",
            studyID: 16,
            studyCategoryID: "study-16",
            submittedAt: Date(timeIntervalSince1970: 1_785_153_600)
        )
        SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        ).savePendingQuestionGenerationProcess(pending)

        let relaunchedStore = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        XCTAssertEqual(relaunchedStore.loadPendingQuestionGenerationProcess(), pending)

        relaunchedStore.savePendingQuestionGenerationProcess(nil)
        XCTAssertNil(relaunchedStore.loadPendingQuestionGenerationProcess())
    }

    func testQuotaExceededStopsQuestionGenerationRetryLoop() {
        let error = RemotePushBackendError.httpStatus(
            403,
            "",
            BackendAPIError(
                code: "QUOTA_EXCEEDED",
                numericCode: 305,
                message: "Monthly question limit reached.",
                status: 403
            )
        )

        XCTAssertTrue(AppErrorHandlingUseCase().isPermanentBackendOperationError(error))
    }

    func testSettingsRequestOmitsClientDefaultsWithoutRootStudies() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            let bodyData = try Self.bodyData(from: request)
            let body = try XCTUnwrap(
                JSONSerialization.jsonObject(with: bodyData) as? [String: Any]
            )
            XCTAssertNil(body["customPrompt"])
            XCTAssertEqual(body["topic"] as? String, "")
            let schedules = try XCTUnwrap(body["schedules"] as? [[String: Any]])
            XCTAssertTrue(schedules.isEmpty)
            return Self.response(for: request, statusCode: 200, body: "{}")
        }

        try await client.updateSchedule(
            registration: Self.registration,
            settings: StudySettings(
                topic: "Swift",
                difficulty: .level5,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15
            ),
            apiKey: nil,
            enabled: true
        )
    }

    func testSettingsRequestUsesFirstRootTopicWhenStudiesExist() async throws {
        let first = StudyCategory(id: "11", title: "Redis", difficulty: .level6)
        let second = StudyCategory(id: "12", title: "Kafka", difficulty: .level7)
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            let body = try XCTUnwrap(
                JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: Any]
            )
            XCTAssertEqual(body["topic"] as? String, first.title)
            let schedules = try XCTUnwrap(body["schedules"] as? [[String: Any]])
            XCTAssertEqual(schedules.compactMap { $0["topic"] as? String }, [first.title, second.title])
            return Self.response(for: request, statusCode: 200, body: "{}")
        }

        try await client.updateSchedule(
            registration: Self.registration,
            settings: StudySettings(
                topic: second.title,
                difficulty: second.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [first, second],
                selectedStudyCategoryID: second.id
            ),
            apiKey: nil,
            enabled: true
        )
    }

    func testServerRefreshDoesNotPromoteLocalizedFallbackRootsIntoMyStudies() async throws {
        let suiteName = "LocalizedFallbackStudyTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false,
            preferredAppLanguageProvider: { .english }
        )
        store.saveHasCompletedOnboarding(true)
        store.saveSettings(.initial(for: .english))
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/studies")
            return Self.response(
                for: request,
                statusCode: 200,
                body: Self.localizedFallbackStudyPageResponse
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.refreshVisibleData()

        XCTAssertEqual(appState.rootStudyCategoriesForDisplay.map(\.title), ["English"])
        XCTAssertEqual(appState.settings.studyCategories.map(\.title), ["English"])
        XCTAssertEqual(store.loadSettings().studyCategories.map(\.title), ["English"])
    }

    func testAppUpdateCheckSendsInstalledVersionAndDecodesForcedCampaign() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/app-updates/check")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: Any]
            XCTAssertEqual(body?["platform"] as? String, "ios")
            let expectedVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            let expectedBuild = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            XCTAssertEqual(body?["currentVersion"] as? String, expectedVersion)
            XCTAssertEqual(body?["currentBuild"] as? String, expectedBuild)
            XCTAssertEqual(body?["language"] as? String, "ja")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "updateAvailable": true,
                  "shouldPresent": true,
                  "campaignId": 9,
                  "mode": "FORCE",
                  "targetVersion": "2.0.0",
                  "targetBuild": "100",
                  "title": "更新が必要です",
                  "message": "続けるには更新してください。",
                  "appStoreUrl": "https://apps.apple.com/app/id6774108938"
                }
                """
            )
        }

        let decision = try await client.checkAppUpdate(
            registration: Self.registration,
            language: .japanese
        )

        XCTAssertTrue(decision.updateAvailable)
        XCTAssertTrue(decision.shouldPresent)
        XCTAssertEqual(decision.mode, .force)
        XCTAssertEqual(decision.campaignID, 9)
    }

    func testAppUpdateDismissalEventUsesCampaignEndpoint() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/app-updates/42/events")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: Any]
            XCTAssertEqual(body?["event"] as? String, "DISMISSED")
            return Self.response(for: request, statusCode: 204, body: "")
        }

        try await client.recordAppUpdateEvent(
            registration: Self.registration,
            campaignID: 42,
            event: .dismissed
        )
    }

    func testBillingCheckoutCreatesPendingInvoiceBeforeStoreKitPurchase() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/billing/checkouts")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: String]
            XCTAssertEqual(body?["productId"], "io.github.ghkdqhrbals.StudyMate.tier2.monthly")
            XCTAssertEqual(body?["idempotencyKey"], "ios-checkout-test")
            return Self.pendingInvoiceResponse(for: request)
        }

        let invoice = try await client.createBillingCheckout(
            registration: Self.signedInRegistration,
            productID: "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            idempotencyKey: "ios-checkout-test"
        )

        XCTAssertEqual(invoice.type, "NORMAL")
        XCTAssertEqual(invoice.status, "WAITING")
        XCTAssertNil(invoice.paymentId)
    }

    func testBillingTransactionSyncIncludesPendingInvoiceNumber() async throws {
        let invoiceNumber = UUID(uuidString: "9f041446-e898-4ef7-974d-91ac70e1a89b")!
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/billing/apple/transactions")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: String]
            XCTAssertEqual(body?["signedTransaction"], "signed-jws")
            XCTAssertEqual(body?["environment"], "SANDBOX")
            XCTAssertEqual(body?["invoiceNumber"]?.lowercased(), invoiceNumber.uuidString.lowercased())
            return Self.pendingInvoiceResponse(for: request)
        }

        _ = try await client.syncAppleTransaction(
            registration: Self.signedInRegistration,
            signedTransaction: "signed-jws",
            environment: "SANDBOX",
            invoiceNumber: invoiceNumber
        )
    }

    func testBillingCheckoutCanBeAbandonedAfterUserCancellation() async throws {
        let invoiceNumber = UUID(uuidString: "9f041446-e898-4ef7-974d-91ac70e1a89b")!
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(
                request.url?.path,
                "/api/v1/billing/checkouts/9f041446-e898-4ef7-974d-91ac70e1a89b/abandon"
            )
            return Self.pendingInvoiceResponse(for: request)
        }

        _ = try await client.abandonBillingCheckout(
            registration: Self.signedInRegistration,
            invoiceNumber: invoiceNumber
        )
    }

    func testRevenueCatConfirmationEncodesTheExactTransactionIdentifier() async throws {
        let invoiceNumber = UUID(uuidString: "9f041446-e898-4ef7-974d-91ac70e1a89b")!
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(
                request.url?.path,
                "/api/v1/billing/invoices/9f041446-e898-4ef7-974d-91ac70e1a89b/confirm"
            )
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let body = try JSONSerialization.jsonObject(
                with: Self.bodyData(from: request)
            ) as? [String: Any]
            XCTAssertEqual(body?["transactionId"] as? String, "200000000000002")
            return Self.pendingInvoiceResponse(for: request)
        }

        _ = try await client.confirmRevenueCatTransaction(
            registration: Self.signedInRegistration,
            invoiceNumber: invoiceNumber,
            transactionID: "200000000000002"
        )
    }

    func testBillingInvoicesDecodeCompletedNormalAndLinkedRefundStates() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/billing/invoices")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "limit": 20,
                  "offset": 0,
                  "invoices": [
                    {
                      "id": 41,
                      "invoiceNumber": "9f041446-e898-4ef7-974d-91ac70e1a89b",
                      "type": "NORMAL",
                      "originalInvoiceId": null,
                      "tierCode": "TIER2",
                      "productId": "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
                      "status": "COMPLETED",
                      "version": 4,
                      "paymentId": 71,
                      "transactionId": "200000000000001",
                      "originalTransactionId": "200000000000001",
                      "paymentStatus": "SETTLED",
                      "priceMilliunits": 7900000,
                      "currency": "KRW",
                      "purchaseAt": "2026-08-03T00:00:00Z",
                      "expiresAt": "2026-09-03T00:00:00Z",
                      "createdAt": "2026-08-03T00:00:00Z",
                      "updatedAt": "2026-08-03T00:00:03Z",
                      "latestEventType": "FULFILLED"
                    },
                    {
                      "id": 42,
                      "invoiceNumber": "af041446-e898-4ef7-974d-91ac70e1a89b",
                      "type": "REFUND",
                      "originalInvoiceId": 41,
                      "tierCode": "TIER2",
                      "productId": "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
                      "status": "WAITING",
                      "version": 2,
                      "paymentId": 71,
                      "transactionId": "200000000000001",
                      "originalTransactionId": "200000000000001",
                      "paymentStatus": "REFUND_PENDING",
                      "priceMilliunits": 7900000,
                      "currency": "KRW",
                      "purchaseAt": "2026-08-03T00:00:00Z",
                      "expiresAt": "2026-09-03T00:00:00Z",
                      "createdAt": "2026-08-03T00:00:04Z",
                      "updatedAt": "2026-08-03T00:00:05Z"
                    }
                  ]
                }
                """
            )
        }

        let page = try await client.fetchBillingInvoices(
            registration: Self.signedInRegistration,
            limit: 20,
            offset: 0
        )

        XCTAssertEqual(page.invoices.map(\.status), ["COMPLETED", "WAITING"])
        XCTAssertTrue(page.invoices[0].isRefundable)
        XCTAssertEqual(page.invoices[0].latestEventType, "FULFILLED")
        XCTAssertFalse(page.invoices[1].isRefundable)
        XCTAssertEqual(page.invoices[1].type, "REFUND")
        XCTAssertEqual(page.invoices[1].originalInvoiceId, 41)

        var cancelledCheckout = page.invoices[0]
        cancelledCheckout.status = "FAILED"
        cancelledCheckout.paymentId = nil
        cancelledCheckout.paymentStatus = nil
        cancelledCheckout.latestEventType = "CANCELLED"
        XCTAssertTrue(cancelledCheckout.requiresCustomerCenterResolution)

        var failedFulfillment = cancelledCheckout
        failedFulfillment.latestEventType = "FULFILLMENT_FAILED"
        XCTAssertFalse(failedFulfillment.requiresCustomerCenterResolution)

        var cancelledRefund = cancelledCheckout
        cancelledRefund.type = "REFUND"
        XCTAssertFalse(cancelledRefund.requiresCustomerCenterResolution)
    }

    private static let nestedStudyPageResponse = """
        {
          "studies": [
            {
              "id": 11,
              "topic": "Redis",
              "parentStudyId": null,
              "sortOrder": 0,
              "difficultyLevel": 10,
              "intervalMinutes": 30,
              "enabled": true,
              "activeForQuestions": true,
              "notificationSound": "default",
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "nextDueAt": null,
              "lastSentAt": null,
              "lastError": null,
              "pendingQuestion": null,
              "latestQuestion": null,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-12T00:00:00Z"
            },
            {
              "id": 12,
              "topic": "메모리 관리와 만료 정책",
              "parentStudyId": 11,
              "sortOrder": 0,
              "difficultyLevel": 2,
              "intervalMinutes": 30,
              "enabled": true,
              "activeForQuestions": true,
              "notificationSound": "default",
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "nextDueAt": null,
              "lastSentAt": null,
              "lastError": null,
              "pendingQuestion": null,
              "latestQuestion": null,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-12T00:00:00Z"
            }
          ],
          "totalCount": 2,
          "limit": 500,
          "offset": 0,
          "serverTime": "2026-08-12T00:00:00Z"
        }
        """

    private static let localizedFallbackStudyPageResponse = """
        {
          "studies": [
            {
              "id": 31,
              "topic": "내 학습",
              "difficultyLevel": 2,
              "intervalMinutes": 15,
              "enabled": true,
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-01T00:00:00Z"
            },
            {
              "id": 32,
              "topic": "My Study",
              "difficultyLevel": 2,
              "intervalMinutes": 15,
              "enabled": true,
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-01T00:00:00Z"
            },
            {
              "id": 33,
              "topic": "マイ学習",
              "difficultyLevel": 2,
              "intervalMinutes": 15,
              "enabled": true,
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-01T00:00:00Z"
            },
            {
              "id": 34,
              "topic": "English",
              "difficultyLevel": 4,
              "intervalMinutes": 30,
              "enabled": true,
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "createdAt": "2026-08-10T00:00:00Z",
              "updatedAt": "2026-08-10T00:00:00Z"
            }
          ],
          "totalCount": 4,
          "limit": 500,
          "offset": 0,
          "serverTime": "2026-08-16T00:00:00Z"
        }
        """

    private static let nestedStudyPageWithSecondChildResponse = """
        {
          "studies": [
            {
              "id": 11,
              "topic": "Redis",
              "parentStudyId": null,
              "sortOrder": 0,
              "difficultyLevel": 10,
              "intervalMinutes": 30,
              "enabled": true,
              "activeForQuestions": true,
              "notificationSound": "default",
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "nextDueAt": null,
              "lastSentAt": null,
              "lastError": null,
              "pendingQuestion": null,
              "latestQuestion": null,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-12T00:00:00Z"
            },
            {
              "id": 12,
              "topic": "메모리 관리와 만료 정책",
              "parentStudyId": 11,
              "sortOrder": 0,
              "difficultyLevel": 2,
              "intervalMinutes": 30,
              "enabled": true,
              "activeForQuestions": true,
              "notificationSound": "default",
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "nextDueAt": null,
              "lastSentAt": null,
              "lastError": null,
              "pendingQuestion": null,
              "latestQuestion": null,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-12T00:00:00Z"
            },
            {
              "id": 13,
              "topic": "지속성 설정: RDB와 AOF",
              "parentStudyId": 11,
              "sortOrder": 1,
              "difficultyLevel": 3,
              "intervalMinutes": 30,
              "enabled": true,
              "activeForQuestions": true,
              "notificationSound": "default",
              "customPrompt": "",
              "openaiModel": "gpt-5.4",
              "maxHistoryCount": 100,
              "nextDueAt": null,
              "lastSentAt": null,
              "lastError": null,
              "pendingQuestion": null,
              "latestQuestion": null,
              "createdAt": "2026-08-01T00:00:00Z",
              "updatedAt": "2026-08-12T00:00:00Z"
            }
          ],
          "totalCount": 3,
          "limit": 500,
          "offset": 0,
          "serverTime": "2026-08-12T00:00:00Z"
        }
        """

    private static let nestedChildStudyDetailResponse = """
        {
          "id": 12,
          "topic": "메모리 관리와 만료 정책",
          "parentStudyId": 11,
          "sortOrder": 0,
          "difficultyLevel": 2,
          "intervalMinutes": 30,
          "enabled": true,
          "activeForQuestions": true,
          "notificationSound": "default",
          "customPrompt": "",
          "openaiModel": "gpt-5.4",
          "maxHistoryCount": 100,
          "nextDueAt": null,
          "lastSentAt": null,
          "lastError": null,
          "pendingQuestion": null,
          "latestQuestion": {
            "id": "latest-child-12",
            "studyId": 12,
            "question": {
              "question": "Redis의 만료 정책을 설명하세요.",
              "expectedAnswerHint": null,
              "createdAt": "2026-08-12T00:01:00Z"
            },
            "answer": "TTL 만료 키를 주기적으로 제거합니다.",
            "gradingResult": {
              "score": 72,
              "correct": true,
              "feedback": "핵심을 설명했습니다.",
              "explanation": "능동 만료와 지연 만료가 함께 사용됩니다."
            },
            "topic": "메모리 관리와 만료 정책",
            "difficulty": 2,
            "answeredAt": "2026-08-12T00:02:00Z",
            "isPublic": false,
            "gradingRequestId": "grading-child-12",
            "gradingStatus": "COMPLETED",
            "gradingError": null
          },
          "createdAt": "2026-08-01T00:00:00Z",
          "updatedAt": "2026-08-12T00:02:00Z"
        }
        """

    private static let nestedRootStudyDetailResponse = """
        {
          "id": 11,
          "topic": "Redis",
          "parentStudyId": null,
          "sortOrder": 0,
          "difficultyLevel": 10,
          "intervalMinutes": 30,
          "enabled": true,
          "activeForQuestions": true,
          "notificationSound": "default",
          "customPrompt": "",
          "openaiModel": "gpt-5.4",
          "maxHistoryCount": 100,
          "nextDueAt": null,
          "lastSentAt": null,
          "lastError": null,
          "pendingQuestion": null,
          "latestQuestion": {
            "id": "latest-root-11",
            "studyId": 11,
            "question": {
              "question": "Redis를 한 문장으로 설명하세요.",
              "expectedAnswerHint": null,
              "createdAt": "2026-08-12T00:01:00Z"
            },
            "answer": "메모리 기반 데이터 저장소입니다.",
            "gradingResult": {
              "score": 72,
              "correct": true,
              "feedback": "핵심을 설명했습니다.",
              "explanation": "루트 질문입니다."
            },
            "topic": "Redis",
            "difficulty": 10,
            "answeredAt": "2026-08-12T00:02:00Z",
            "isPublic": false,
            "gradingRequestId": "grading-root-11",
            "gradingStatus": "COMPLETED",
            "gradingError": null
          },
          "createdAt": "2026-08-01T00:00:00Z",
          "updatedAt": "2026-08-12T00:02:00Z"
        }
        """

    private static let secondNestedChildStudyDetailResponse = """
        {
          "id": 13,
          "topic": "지속성 설정: RDB와 AOF",
          "parentStudyId": 11,
          "sortOrder": 1,
          "difficultyLevel": 3,
          "intervalMinutes": 30,
          "enabled": true,
          "activeForQuestions": true,
          "notificationSound": "default",
          "customPrompt": "",
          "openaiModel": "gpt-5.4",
          "maxHistoryCount": 100,
          "nextDueAt": null,
          "lastSentAt": null,
          "lastError": null,
          "pendingQuestion": null,
          "latestQuestion": {
            "id": "latest-child-13",
            "studyId": 13,
            "question": {
              "question": "Redis의 RDB와 AOF 차이를 설명하세요.",
              "expectedAnswerHint": null,
              "createdAt": "2026-08-12T00:01:00Z"
            },
            "answer": "RDB는 스냅샷이고 AOF는 명령 로그입니다.",
            "gradingResult": {
              "score": 84,
              "correct": true,
              "feedback": "차이를 정확히 설명했습니다.",
              "explanation": "두 지속성 방식의 절충점을 이해했습니다."
            },
            "topic": "지속성 설정: RDB와 AOF",
            "difficulty": 3,
            "answeredAt": "2026-08-12T00:02:00Z",
            "isPublic": false,
            "gradingRequestId": "grading-child-13",
            "gradingStatus": "COMPLETED",
            "gradingError": null
          },
          "createdAt": "2026-08-01T00:00:00Z",
          "updatedAt": "2026-08-12T00:02:00Z"
        }
        """

    private static let questionQuotaResponse = """
        {
          "usedCount": 2,
          "monthlyLimit": 30,
          "remainingCount": 28,
          "resetAt": "2026-09-12T00:00:00Z",
          "tierCode": "TIER1",
          "periodStartedAt": "2026-08-12T00:00:00Z",
          "reservedCount": 0,
          "baseLimit": 30,
          "bonusLimit": 0,
          "anchorType": "ACCOUNT_CREATED",
          "policyVersion": 2
        }
        """

    private static let tier1BillingStatusResponse = """
        {
          "tierCode": "TIER1",
          "adFree": false,
          "source": "FREE",
          "accessStatus": "ACTIVE",
          "renewalStatus": "NONE",
          "willRenew": false,
          "synchronizedAt": "2026-08-25T00:00:00Z",
          "quota": {
            "periodStartedAt": "2026-08-25T00:00:00Z",
            "resetAt": "2026-09-25T00:00:00Z",
            "anchorType": "ACCOUNT_CREATED",
            "baseLimit": 30,
            "bonusLimit": 0,
            "usedCount": 0,
            "reservedCount": 0,
            "remainingCount": 30,
            "policyVersion": 2
          }
        }
        """

    private static let nativeAdSlotFeedResponse = """
        {
          "questions": [],
          "items": [
            {
              "type": "NATIVE_AD_SLOT",
              "nativeAdSlot": {
                "slotId": "slot-tier1",
                "placement": "COMMUNITY_FEED"
              }
            }
          ],
          "totalCount": 0,
          "limit": 20,
          "offset": 0
        }
        """

    private func makeNestedStudyStore(
        defaults: UserDefaults,
        databaseURL: URL
    ) -> SettingsStore {
        let root = StudyCategory(id: "11", title: "Redis", difficulty: .level10)
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: root.title,
                difficulty: root.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 30,
                studyCategories: [root],
                selectedStudyCategoryID: root.id
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        store.saveIsCommunitySignedIn(true)
        return store
    }

    private func waitUntil(
        maxAttempts: Int = 100,
        intervalNanoseconds: UInt64 = 20_000_000,
        condition: @MainActor () -> Bool
    ) async -> Bool {
        for _ in 0..<maxAttempts {
            if condition() {
                return true
            }
            try? await Task.sleep(nanoseconds: intervalNanoseconds)
        }
        return condition()
    }

    private func makeClient(
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> RemotePushBackendClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [QuestionGenerationURLProtocol.self]
        QuestionGenerationURLProtocol.requestHandler = handler
        return RemotePushBackendClient(
            baseURL: URL(string: "https://example.test")!,
            session: URLSession(configuration: configuration)
        )
    }

    private static let registration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: ""
    )

    private static let anonymousRegistration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: "",
        accessToken: "e30.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6dHJ1ZSwic3RhdHVzIjoiQU5PTllNT1VTIn0.signature",
        accessTokenExpiresAt: Date().addingTimeInterval(3_600)
    )

    private static let signedInRegistration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: "",
        accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
        accessTokenExpiresAt: Date().addingTimeInterval(3_600)
    )

    private static func response(
        for request: URLRequest,
        statusCode: Int,
        body: String
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    private static func pendingTermsProfileResponse(
        for request: URLRequest
    ) -> (HTTPURLResponse, Data) {
        response(
            for: request,
            statusCode: 200,
            body: """
            {
              "id": 7,
              "displayName": "New-Buddy-0007",
              "status": "PENDING_TERMS",
              "provider": "GOOGLE"
            }
            """
        )
    }

    private static func activeProfileResponse(
        for request: URLRequest
    ) -> (HTTPURLResponse, Data) {
        response(
            for: request,
            statusCode: 200,
            body: """
            {
              "id": 7,
              "displayName": "New-Buddy-0007",
              "status": "ACTIVE",
              "provider": "GOOGLE"
            }
            """
        )
    }

    private static func unredeemedReferralSummaryResponse(
        for request: URLRequest
    ) -> (HTTPURLResponse, Data) {
        response(
            for: request,
            statusCode: 200,
            body: """
            {
              "code": "BS-ZYXWVUTS",
              "successfulReferralCount": 0,
              "rewardMonthsEarned": 0,
              "rewardStartsAt": null,
              "rewardEndsAt": null,
              "hasRedeemedReferral": false,
              "referralUrl": "https://api.ghkdqhrbals.org/referrals/BS-ZYXWVUTS"
            }
            """
        )
    }

    private static func redeemedReferralSummaryResponse(
        for request: URLRequest
    ) -> (HTTPURLResponse, Data) {
        response(
            for: request,
            statusCode: 200,
            body: """
            {
              "code": "BS-ZYXWVUTS",
              "successfulReferralCount": 0,
              "rewardMonthsEarned": 1,
              "rewardStartsAt": "2026-08-28T00:00:00Z",
              "rewardEndsAt": "2026-09-28T00:00:00Z",
              "hasRedeemedReferral": true,
              "referralUrl": "https://api.ghkdqhrbals.org/referrals/BS-ZYXWVUTS"
            }
            """
        )
    }

    private static func communityQuestionPageJSON(
        ids: [String],
        totalCount: Int,
        offset: Int
    ) -> String {
        let questions = ids.map { id in
            """
            {
              "id": "\(id)",
              "question": "Question \(id)",
              "answer": "Answer",
              "gradingResult": null,
              "topic": "Swift",
              "difficultyLevel": 5,
              "status": "GRADED",
              "source": "STUDY",
              "createdAt": "2026-08-01T00:00:00Z",
              "answeredAt": "2026-08-01T00:01:00Z",
              "author": null,
              "likeCount": 1,
              "commentCount": 0,
              "viewCount": 0,
              "isLikedByMe": true
            }
            """
        }.joined(separator: ",")
        return """
        {
          "questions": [\(questions)],
          "totalCount": \(totalCount),
          "limit": 20,
          "offset": \(offset)
        }
        """
    }

    private static func pendingInvoiceResponse(for request: URLRequest) -> (HTTPURLResponse, Data) {
        response(
            for: request,
            statusCode: 200,
            body: """
            {
              "id": 41,
              "invoiceNumber": "9f041446-e898-4ef7-974d-91ac70e1a89b",
              "type": "NORMAL",
              "originalInvoiceId": null,
              "tierCode": "TIER2",
              "productId": "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
              "status": "WAITING",
              "version": 1,
              "paymentId": null,
              "transactionId": null,
              "originalTransactionId": null,
              "paymentStatus": null,
              "priceMilliunits": null,
              "currency": null,
              "purchaseAt": null,
              "expiresAt": null,
              "createdAt": "2026-08-03T00:00:00Z",
              "updatedAt": "2026-08-03T00:00:00Z"
            }
            """
        )
    }

    private static func bodyData(from request: URLRequest) throws -> Data {
        if let body = request.httpBody {
            return body
        }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }

        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count >= 0 else {
                throw try XCTUnwrap(stream.streamError)
            }
            if count == 0 {
                break
            }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }
}

@MainActor
private final class TestAppNotificationEventProvider: AppNotificationEventProviding {
    private var unauthorizedHandler: (() -> Void)?

    func observeAPITrafficLogs(
        _ handler: @MainActor @escaping (APITrafficLogEntry) -> Void
    ) -> AnyCancellable {
        AnyCancellable {}
    }

    func observeBackendUnauthorized(
        _ handler: @MainActor @escaping () -> Void
    ) -> AnyCancellable {
        unauthorizedHandler = handler
        return AnyCancellable {}
    }

    func sendBackendUnauthorized() {
        unauthorizedHandler?()
    }
}

private final class QuestionGenerationURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var requestHandler:
        ((URLRequest) throws -> (HTTPURLResponse, Data))?
    nonisolated(unsafe) static var responseDelayNanoseconds: UInt64 = 0
    nonisolated(unsafe) static var responseDelayHandler: ((URLRequest) -> UInt64)?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let protocolReference = UncheckedSendableBox(self)
        Task { @MainActor in
            let protocolInstance = protocolReference.value
            let responseDelay = Self.responseDelayHandler?(protocolInstance.request)
                ?? Self.responseDelayNanoseconds
            if responseDelay > 0 {
                try? await Task.sleep(nanoseconds: responseDelay)
            }
            guard let requestHandler = Self.requestHandler else {
                protocolInstance.client?.urlProtocol(
                    protocolInstance,
                    didFailWithError: URLError(.badServerResponse)
                )
                return
            }

            do {
                let (response, data) = try requestHandler(protocolInstance.request)
                protocolInstance.client?.urlProtocol(
                    protocolInstance,
                    didReceive: response,
                    cacheStoragePolicy: .notAllowed
                )
                protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
                protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
            } catch {
                protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {}
}

private final class UncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

private final class LockedRequestCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer {
            lock.unlock()
        }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}

private final class LockedValue<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storedValue: Value

    init(_ value: Value) {
        storedValue = value
    }

    var value: Value {
        lock.lock()
        defer { lock.unlock() }
        return storedValue
    }

    func set(_ value: Value) {
        lock.lock()
        storedValue = value
        lock.unlock()
    }
}

private actor BlockingRecordingAppSleepProvider: AppSleepProviding {
    private var values: [UInt64] = []

    func sleep(nanoseconds: UInt64) async throws {
        values.append(nanoseconds)
        try await Task.sleep(nanoseconds: 60_000_000_000)
    }

    func requestedNanoseconds() -> [UInt64] {
        values
    }
}

private actor RecordingAppSleepProvider: AppSleepProviding {
    private var values: [UInt64] = []

    func sleep(nanoseconds: UInt64) async throws {
        values.append(nanoseconds)
    }

    func requestedNanoseconds() -> [UInt64] {
        values
    }
}

private struct ImmediateAppSleepProvider: AppSleepProviding {
    func sleep(nanoseconds: UInt64) async throws {}
}
