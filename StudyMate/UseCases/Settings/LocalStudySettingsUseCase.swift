import Foundation

struct LocalStudySettingsUseCase {
    private let repository: LocalStudySettingsRepository

    init(repository: LocalStudySettingsRepository) {
        self.repository = repository
    }

    func loadSettings() -> LocalStudySettingsSnapshot {
        repository.loadLocalStudySettings()
    }

    func saveSettings(_ settings: StudySettings) {
        repository.saveSettings(settings)
    }

    func saveAPIKey(_ apiKey: String) {
        repository.saveAPIKey(apiKey)
    }

    func saveAPIKeyUpdatedAt(_ date: Date?) {
        repository.saveOpenAIAPIKeyUpdatedAt(date)
    }

    func saveSettingsMutationAt(_ date: Date?) {
        repository.saveLocalSettingsMutationAt(date)
    }
}
