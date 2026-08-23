import Foundation

@MainActor
struct RemoteIdentityRepository: IdentityRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func registerDevice(
        installationIdentifier: String,
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        try await backendClient.registerDevice(
            installationIdentifier: installationIdentifier,
            apnsToken: apnsToken,
            language: language,
            timezone: timezone,
            apnsEnvironment: apnsEnvironment
        )
    }

    func updatePushToken(
        registration: RemotePushRegistration,
        apnsToken: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        try await backendClient.updatePushToken(
            registration: registration,
            apnsToken: apnsToken,
            apnsEnvironment: apnsEnvironment
        )
    }

    func bootstrapAccessToken(registration: RemotePushRegistration) async throws -> RemotePushRegistration {
        try await backendClient.bootstrapAccessToken(registration: registration)
    }
}
