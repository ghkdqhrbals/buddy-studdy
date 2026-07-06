struct SettingsStoreCommunitySessionRepository: CommunitySessionRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadIsCommunitySignedIn() -> Bool {
        settingsStore.loadIsCommunitySignedIn()
    }

    func saveIsCommunitySignedIn(_ isSignedIn: Bool) {
        settingsStore.saveIsCommunitySignedIn(isSignedIn)
    }
}
