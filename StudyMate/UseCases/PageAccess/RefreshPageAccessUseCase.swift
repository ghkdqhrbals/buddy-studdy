import Foundation

@MainActor
struct RefreshPageAccessUseCase {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func execute(registration: RemotePushRegistration) async throws -> BackendAccessState {
        try await backendClient.fetchAccess(registration: registration)
    }
}
