import Foundation

@MainActor
struct BackendIdentityUseCase {
    private let repository: IdentityRepository

    init(repository: IdentityRepository) {
        self.repository = repository
    }

    func registerDevice(
        installationIdentifier: String,
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        try await repository.registerDevice(
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
        try await repository.updatePushToken(
            registration: registration,
            apnsToken: apnsToken,
            apnsEnvironment: apnsEnvironment
        )
    }

    func bootstrapAccessToken(registration: RemotePushRegistration) async throws -> RemotePushRegistration {
        try await repository.bootstrapAccessToken(registration: registration)
    }
}
