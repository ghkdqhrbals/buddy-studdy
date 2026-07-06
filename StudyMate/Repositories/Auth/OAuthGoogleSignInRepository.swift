import Foundation

@MainActor
struct OAuthGoogleSignInRepository: GoogleSignInRepository {
    func signIn() async throws -> String {
        #if os(iOS)
        try await GoogleOAuthService().signIn()
        #else
        throw GoogleOAuthError.notConfigured
        #endif
    }
}
