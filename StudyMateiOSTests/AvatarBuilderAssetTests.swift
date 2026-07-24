import XCTest
@testable import StudyMate

final class FixedProfileAvatarTests: XCTestCase {
    func testDefaultAvatarUsesSharedBrandAsset() {
        XCTAssertEqual(BuddyStudyAvatar.assetName, "BuddyStudyBrandLogo")
        XCTAssertEqual(BuddyStudyAvatar.symbolName, "pixel-fox")
        XCTAssertEqual(ProfileAvatarOption.assetName(for: "pixel-cat-laptop"), BuddyStudyAvatar.assetName)
        XCTAssertEqual(ProfileAvatarOption.canonicalName(for: "pixel-dog-corgi-reader"), BuddyStudyAvatar.symbolName)
    }
}
