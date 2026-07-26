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
    func loadStudyTreeNodeOffsets(rootStudyID: Int) -> [Int: StudyTreeNodeOffset]
    func saveStudyTreeNodeOffsets(_ offsets: [Int: StudyTreeNodeOffset], rootStudyID: Int)
    func loadStudyTreeViewport(rootStudyID: Int) -> StudyTreeViewportState
    func saveStudyTreeViewport(_ viewport: StudyTreeViewportState, rootStudyID: Int)
}
