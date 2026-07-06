import Foundation

@MainActor
protocol GoogleSignInRepository {
    func signIn() async throws -> String
}
