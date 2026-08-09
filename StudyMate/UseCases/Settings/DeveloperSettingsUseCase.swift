import Foundation

struct DeveloperSettingsUseCase {
    private let repository: DeveloperSettingsRepository

    init(repository: DeveloperSettingsRepository) {
        self.repository = repository
    }

    func loadSettings() -> DeveloperSettings {
        repository.loadDeveloperSettings()
    }

    func prepareForLaunch(distribution: AppDistributionContext) -> DeveloperSettings {
        var settings = repository.loadDeveloperSettings()
        guard distribution.isTestFlight else {
            return settings
        }

        if !settings.isDeveloperAccessUnlocked {
            repository.saveDeveloperAccessUnlocked(true)
            settings.isDeveloperAccessUnlocked = true
        }
        if settings.developerAccessBuildIdentifier != distribution.buildIdentifier {
            repository.saveDeveloperAccessBuildIdentifier(distribution.buildIdentifier)
            settings.developerAccessBuildIdentifier = distribution.buildIdentifier
        }
        if !settings.isDebuggingEnabled {
            repository.saveIsDebuggingEnabled(true)
            settings.isDebuggingEnabled = true
        }
        return settings
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

    func saveDeveloperAccessBuildIdentifier(_ buildIdentifier: String?) {
        repository.saveDeveloperAccessBuildIdentifier(buildIdentifier)
    }
}
