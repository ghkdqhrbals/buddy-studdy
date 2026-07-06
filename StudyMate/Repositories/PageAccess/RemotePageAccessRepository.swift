import Foundation

@MainActor
struct RemotePageAccessRepository: PageAccessRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchAccess(registration: RemotePushRegistration) async throws -> BackendAccessState {
        try await backendClient.fetchAccess(registration: registration)
    }
}
