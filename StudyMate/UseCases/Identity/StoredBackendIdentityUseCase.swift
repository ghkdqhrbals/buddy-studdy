import Foundation

struct StoredBackendIdentityUseCase {
    private let repository: RemotePushRegistrationRepository

    init(repository: RemotePushRegistrationRepository) {
        self.repository = repository
    }

    func loadRegistration() -> RemotePushRegistration? {
        repository.loadRemotePushRegistration()
    }

    func saveRegistration(_ registration: RemotePushRegistration?) {
        repository.saveRemotePushRegistration(registration)
    }

    func installationIdentifier() -> String {
        repository.loadOrCreateBackendInstallationIdentifier()
    }

    func hasAccessToken() -> Bool {
        loadRegistration()?.hasAccessToken == true
    }

    func hasRegisteredAccessToken() -> Bool {
        loadRegistration()?.hasRegisteredAccessToken == true
    }
}
