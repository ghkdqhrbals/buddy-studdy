struct SettingsStoreOnboardingStateRepository: OnboardingStateRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadHasCompletedOnboarding() -> Bool {
        settingsStore.loadHasCompletedOnboarding()
    }

    func saveHasCompletedOnboarding(_ hasCompleted: Bool) {
        settingsStore.saveHasCompletedOnboarding(hasCompleted)
    }
}
