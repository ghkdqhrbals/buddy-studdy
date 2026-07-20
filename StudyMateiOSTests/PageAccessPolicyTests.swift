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
}
