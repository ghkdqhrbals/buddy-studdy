import Foundation

@MainActor
protocol IdentityRepository {
    func registerDevice(
        installationIdentifier: String,
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration

    func updatePushToken(
        registration: RemotePushRegistration,
        apnsToken: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration

    func bootstrapAccessToken(registration: RemotePushRegistration) async throws -> RemotePushRegistration
}
