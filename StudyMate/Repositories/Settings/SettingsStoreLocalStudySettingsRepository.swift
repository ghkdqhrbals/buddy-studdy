import Foundation

struct SettingsStoreLocalStudySettingsRepository: LocalStudySettingsRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadLocalStudySettings() -> LocalStudySettingsSnapshot {
        LocalStudySettingsSnapshot(
            settings: settingsStore.loadSettings(),
            apiKey: settingsStore.loadAPIKey(),
            openAIAPIKeyUpdatedAt: settingsStore.loadOpenAIAPIKeyUpdatedAt(),
            localSettingsMutationAt: settingsStore.loadLocalSettingsMutationAt()
        )
    }

    func saveSettings(_ settings: StudySettings) {
        settingsStore.saveSettings(settings)
    }

    func saveAPIKey(_ apiKey: String) {
        settingsStore.saveAPIKey(apiKey)
    }

    func saveOpenAIAPIKeyUpdatedAt(_ date: Date?) {
        settingsStore.saveOpenAIAPIKeyUpdatedAt(date)
    }

    func saveLocalSettingsMutationAt(_ date: Date?) {
        settingsStore.saveLocalSettingsMutationAt(date)
    }
}
