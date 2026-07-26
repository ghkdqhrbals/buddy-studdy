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

    func loadStudyTreeNodeOffsets(rootStudyID: Int) -> [Int: StudyTreeNodeOffset] {
        repository.loadStudyTreeNodeOffsets(rootStudyID: rootStudyID)
    }

    func saveStudyTreeNodeOffsets(_ offsets: [Int: StudyTreeNodeOffset], rootStudyID: Int) {
        repository.saveStudyTreeNodeOffsets(offsets, rootStudyID: rootStudyID)
    }

    func loadStudyTreeViewport(rootStudyID: Int) -> StudyTreeViewportState {
        repository.loadStudyTreeViewport(rootStudyID: rootStudyID)
    }

    func saveStudyTreeViewport(_ viewport: StudyTreeViewportState, rootStudyID: Int) {
        repository.saveStudyTreeViewport(viewport, rootStudyID: rootStudyID)
    }
}
