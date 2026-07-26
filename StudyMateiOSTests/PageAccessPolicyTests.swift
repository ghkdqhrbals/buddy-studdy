import XCTest
@testable import StudyMate

final class PageAccessPolicyTests: XCTestCase {
    func testLoginGateUsesAuthoritativeSignedInState() {
        XCTAssertFalse(
            PageAccessPolicy.shouldShowLoginGate(
                for: .statistics,
                isSignedIn: true
            )
        )
        XCTAssertTrue(
            PageAccessPolicy.shouldShowLoginGate(
                for: .records,
                isSignedIn: false
            )
        )
    }

    func testCommunitySessionStateInvalidatesInFlightRequestAfterSignOut() {
        var session = CommunitySessionStateStore(isSignedIn: true)
        let requestSnapshot = session.generation

        XCTAssertTrue(session.isCurrent(requestSnapshot))

        session.signOut()

        XCTAssertFalse(session.isCurrent(requestSnapshot))
        XCTAssertFalse(session.isCurrent(session.generation))

        session.signIn()

        XCTAssertTrue(session.isCurrent(session.generation))
    }

    func testTermsAgreementBackendErrorRoutesToAgreementGate() throws {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "code": 302,
            "message": "Latest terms agreement is required.",
            "requestId": "request-1",
            "status": 403
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(403, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }

    func testTermsAgreementBackendErrorStillRoutesWhenOptionalPayloadCannotDecode() {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "requiredTerms": [{"unexpected": true}]
          }
        }
        """
        let error = RemotePushBackendError.httpStatus(403, payload, nil)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }
}

final class StudyTreeViewportPersistenceTests: XCTestCase {
    func testViewportPersistsPerRootStudyAndSanitizesValues() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96
            ),
            rootStudyID: 7
        )

        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 7),
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96
            )
        )
        XCTAssertEqual(store.loadStudyTreeViewport(rootStudyID: 8), .default)

        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 4,
                contentOffsetX: -20,
                contentOffsetY: .infinity
            ),
            rootStudyID: 9
        )
        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 9),
            StudyTreeViewportState(
                zoomScale: 1.8,
                contentOffsetX: 0,
                contentOffsetY: 0
            )
        )
    }
}
