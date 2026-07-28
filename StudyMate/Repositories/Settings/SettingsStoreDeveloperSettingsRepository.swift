struct SettingsStoreDeveloperSettingsRepository: DeveloperSettingsRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadDeveloperSettings() -> DeveloperSettings {
        DeveloperSettings(
            isDebuggingEnabled: settingsStore.loadIsDebuggingEnabled(),
            debugBackendBaseURL: settingsStore.loadDebugBackendBaseURL(),
            isDeveloperAccessUnlocked: settingsStore.loadIsDeveloperAccessUnlocked(),
            developerAccessBuildIdentifier: settingsStore.loadDeveloperAccessBuildIdentifier()
        )
    }

    func saveIsDebuggingEnabled(_ isEnabled: Bool) {
        settingsStore.saveIsDebuggingEnabled(isEnabled)
    }

    func saveDebugBackendBaseURL(_ baseURL: String) {
        settingsStore.saveDebugBackendBaseURL(baseURL)
    }

    func saveDeveloperAccessUnlocked(_ isUnlocked: Bool) {
        settingsStore.saveDeveloperAccessUnlocked(isUnlocked)
    }

    func saveDeveloperAccessBuildIdentifier(_ buildIdentifier: String?) {
        settingsStore.saveDeveloperAccessBuildIdentifier(buildIdentifier)
    }
}
