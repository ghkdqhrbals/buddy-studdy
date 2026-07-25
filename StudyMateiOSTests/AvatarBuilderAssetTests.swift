import XCTest
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
