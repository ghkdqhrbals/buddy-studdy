struct SettingsStoreDeveloperSettingsRepository: DeveloperSettingsRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadDeveloperSettings() -> DeveloperSettings {
        DeveloperSettings(
            isDebuggingEnabled: settingsStore.loadIsDebuggingEnabled(),
            debugBackendBaseURL: settingsStore.loadDebugBackendBaseURL()
        )
    }

    func saveIsDebuggingEnabled(_ isEnabled: Bool) {
        settingsStore.saveIsDebuggingEnabled(isEnabled)
    }

    func saveDebugBackendBaseURL(_ baseURL: String) {
        settingsStore.saveDebugBackendBaseURL(baseURL)
    }
}
