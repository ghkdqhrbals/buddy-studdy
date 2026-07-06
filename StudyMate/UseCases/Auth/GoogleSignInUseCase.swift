import Foundation

@MainActor
struct GoogleSignInUseCase {
    private let repository: GoogleSignInRepository

    init(repository: GoogleSignInRepository) {
        self.repository = repository
    }

    func signIn() async throws -> String {
        try await repository.signIn()
    }
}
