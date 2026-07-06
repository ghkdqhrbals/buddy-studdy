import Foundation

struct LocalStudySettingsSnapshot {
    var settings: StudySettings
    var apiKey: String
    var openAIAPIKeyUpdatedAt: Date?
    var localSettingsMutationAt: Date?
}

protocol LocalStudySettingsRepository {
    func loadLocalStudySettings() -> LocalStudySettingsSnapshot
    func saveSettings(_ settings: StudySettings)
    func saveAPIKey(_ apiKey: String)
    func saveOpenAIAPIKeyUpdatedAt(_ date: Date?)
    func saveLocalSettingsMutationAt(_ date: Date?)
}
