import Foundation

@MainActor
struct RemoteSettingsRepository: SettingsRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchOpenAIModelOptions() async throws -> [OpenAIModelOption] {
        try await backendClient.fetchOpenAIModelOptions()
    }

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings {
        try await backendClient.fetchSettings(registration: registration)
    }

    func validateAPIKey(registration: RemotePushRegistration) async throws -> BackendAPIValidation {
        try await backendClient.validateAPIKey(registration: registration)
    }

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws {
        try await backendClient.updateSchedule(
            registration: registration,
            settings: settings,
            apiKey: apiKey,
            enabled: enabled
        )
    }
}
