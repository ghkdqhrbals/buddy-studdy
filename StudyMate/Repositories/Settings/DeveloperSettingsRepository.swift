struct DeveloperSettings {
    var isDebuggingEnabled: Bool
    var debugBackendBaseURL: String
    var isDeveloperAccessUnlocked: Bool
}

struct DeveloperFeatureAccess: Equatable {
    var developerOptionsAllowed: Bool
    var debugPopupAllowed: Bool

    static let restricted = DeveloperFeatureAccess(
        developerOptionsAllowed: false,
        debugPopupAllowed: false
    )

    static let fullyAllowed = DeveloperFeatureAccess(
        developerOptionsAllowed: true,
        debugPopupAllowed: true
    )
}

protocol DeveloperSettingsRepository {
    func loadDeveloperSettings() -> DeveloperSettings
    func saveIsDebuggingEnabled(_ isEnabled: Bool)
    func saveDebugBackendBaseURL(_ baseURL: String)
    func saveDeveloperAccessUnlocked(_ isUnlocked: Bool)
}
