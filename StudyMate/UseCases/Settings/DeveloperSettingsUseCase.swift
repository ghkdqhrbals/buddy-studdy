import Foundation

struct DeveloperSettingsUseCase {
    private let repository: DeveloperSettingsRepository

    init(repository: DeveloperSettingsRepository) {
        self.repository = repository
    }

    func loadSettings() -> DeveloperSettings {
        repository.loadDeveloperSettings()
    }

    func saveDebugBackendBaseURL(_ baseURL: String) {
        repository.saveDebugBackendBaseURL(baseURL)
    }

    func saveIsDebuggingEnabled(_ isEnabled: Bool) {
        repository.saveIsDebuggingEnabled(isEnabled)
    }

    func saveDeveloperAccessUnlocked(_ isUnlocked: Bool) {
        repository.saveDeveloperAccessUnlocked(isUnlocked)
    }
}
