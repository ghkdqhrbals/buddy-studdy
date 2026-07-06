import Foundation

@MainActor
struct GoogleSignInUseCase {
    func signIn() async throws -> String {
        #if os(iOS)
        try await GoogleOAuthService().signIn()
        #else
        throw GoogleOAuthError.notConfigured
        #endif
    }
}
