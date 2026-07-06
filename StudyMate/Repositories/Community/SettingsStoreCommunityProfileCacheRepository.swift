import Foundation

struct SettingsStoreCommunityProfileCacheRepository: CommunityProfileCacheRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadProfileAvatarSymbolName() -> String {
        settingsStore.loadProfileAvatarSymbolName()
    }

    func saveProfileAvatarSymbolName(_ symbolName: String) {
        settingsStore.saveProfileAvatarSymbolName(symbolName)
    }

    func loadProfileAvatarImageData() -> Data? {
        settingsStore.loadProfileAvatarImageData()
    }

    func saveProfileAvatarImageData(_ data: Data?) {
        settingsStore.saveProfileAvatarImageData(data)
    }

    func loadProfileAvatarColorSeed() -> String? {
        settingsStore.loadProfileAvatarColorSeed()
    }

    func saveProfileAvatarColorSeed(_ seed: String) {
        settingsStore.saveProfileAvatarColorSeed(seed)
    }

    func loadCommunityProfileDisplayName() -> String? {
        settingsStore.loadCommunityProfileDisplayName()
    }

    func saveCommunityProfileDisplayName(_ displayName: String) {
        settingsStore.saveCommunityProfileDisplayName(displayName)
    }

    func loadCommunityProfileID() -> Int? {
        settingsStore.loadCommunityProfileID()
    }

    func saveCommunityProfileID(_ id: Int?) {
        settingsStore.saveCommunityProfileID(id)
    }
}
