import Foundation

@MainActor
protocol PageAccessRepository {
    func fetchAccess(registration: RemotePushRegistration) async throws -> BackendAccessState
}
