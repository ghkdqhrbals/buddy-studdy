struct DeveloperSettings {
    var isDebuggingEnabled: Bool
    var debugBackendBaseURL: String
}

protocol DeveloperSettingsRepository {
    func loadDeveloperSettings() -> DeveloperSettings
    func saveIsDebuggingEnabled(_ isEnabled: Bool)
    func saveDebugBackendBaseURL(_ baseURL: String)
}
