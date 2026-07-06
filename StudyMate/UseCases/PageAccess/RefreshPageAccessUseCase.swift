import Foundation

@MainActor
struct RefreshPageAccessUseCase {
    private let repository: PageAccessRepository

    init(repository: PageAccessRepository) {
        self.repository = repository
    }

    func execute(registration: RemotePushRegistration) async throws -> BackendAccessState {
        try await repository.fetchAccess(registration: registration)
    }
}
