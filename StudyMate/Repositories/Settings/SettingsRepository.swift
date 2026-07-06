import Foundation

@MainActor
protocol SettingsRepository {
    func fetchOpenAIModelOptions() async throws -> [OpenAIModelOption]

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings

    func validateAPIKey(registration: RemotePushRegistration) async throws -> BackendAPIValidation

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws
}
