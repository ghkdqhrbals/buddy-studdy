import XCTest
@testable import StudyMate

final class PageAccessPolicyTests: XCTestCase {
    func testLoginGateIsHiddenForRegisteredTokenWhileAccessRefreshIsPending() {
        XCTAssertFalse(
            PageAccessPolicy.shouldShowLoginGate(
                for: .statistics,
                in: .signedOut,
                hasRegisteredAccessToken: true
            )
        )
        XCTAssertFalse(
            PageAccessPolicy.shouldShowLoginGate(
                for: .records,
                in: .signedOut,
                hasRegisteredAccessToken: true
            )
        )
    }

    func testLoginGateIsShownWithoutRegisteredToken() {
        XCTAssertTrue(
            PageAccessPolicy.shouldShowLoginGate(
                for: .statistics,
                in: .signedOut,
                hasRegisteredAccessToken: false
            )
        )
    }

    func testCommunitySessionUsesOneAuthoritativePolicy() {
        XCTAssertFalse(
            PageAccessPolicy.isCommunitySessionActive(
                accessState: .signedOut,
                hasRegisteredAccessToken: false
            )
        )

        XCTAssertTrue(
            PageAccessPolicy.isCommunitySessionActive(
                accessState: .signedOut,
                hasRegisteredAccessToken: true
            )
        )
    }

    func testCommunitySessionEpochInvalidatesInFlightRequestAfterSignOut() {
        var epoch = CommunitySessionEpoch()
        let requestSnapshot = epoch.value

        XCTAssertTrue(epoch.isCurrent(requestSnapshot, sessionIsActive: true))

        epoch.invalidate()

        XCTAssertFalse(epoch.isCurrent(requestSnapshot, sessionIsActive: true))
        XCTAssertFalse(epoch.isCurrent(epoch.value, sessionIsActive: false))
    }
}
