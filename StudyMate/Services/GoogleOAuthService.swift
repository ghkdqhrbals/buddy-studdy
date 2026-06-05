import AuthenticationServices
import CryptoKit
import Foundation
import Security
#if canImport(UIKit)
import UIKit
#endif

#if os(iOS)
@MainActor
final class GoogleOAuthService: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var currentSession: ASWebAuthenticationSession?

    func signIn() async throws -> String {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GoogleOAuthClientID") as? String,
              !clientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !clientID.contains("$("),
              let redirectScheme = Bundle.main.object(forInfoDictionaryKey: "GoogleOAuthRedirectScheme") as? String,
              !redirectScheme.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !redirectScheme.contains("$(") else {
            throw GoogleOAuthError.notConfigured
        }

        let verifier = Self.makeCodeVerifier()
        let challenge = Self.codeChallenge(for: verifier)
        let state = Self.makeCodeVerifier()
        let redirectURI = "\(redirectScheme):/oauth2redirect"
        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state)
        ]
        guard let authURL = components.url else {
            throw GoogleOAuthError.invalidResponse
        }

        let callbackURL = try await authenticate(url: authURL, callbackScheme: redirectScheme)
        guard let callbackComponents = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false),
              callbackComponents.queryItems?.first(where: { $0.name == "state" })?.value == state,
              let code = callbackComponents.queryItems?.first(where: { $0.name == "code" })?.value,
              !code.isEmpty else {
            throw GoogleOAuthError.invalidResponse
        }

        return try await exchangeCodeForIDToken(
            code: code,
            verifier: verifier,
            clientID: clientID,
            redirectURI: redirectURI
        )
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    private func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] url, error in
                self?.currentSession = nil
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let url else {
                    continuation.resume(throwing: GoogleOAuthError.invalidResponse)
                    return
                }
                continuation.resume(returning: url)
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            currentSession = session
            if !session.start() {
                currentSession = nil
                continuation.resume(throwing: GoogleOAuthError.invalidResponse)
            }
        }
    }

    private func exchangeCodeForIDToken(
        code: String,
        verifier: String,
        clientID: String,
        redirectURI: String
    ) async throws -> String {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body: [String: String] = [
            "client_id": clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": redirectURI
        ]
        request.httpBody = body
            .map { key, value in
                "\(Self.formEncode(key))=\(Self.formEncode(value))"
            }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode),
              let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let idToken = payload["id_token"] as? String,
              !idToken.isEmpty else {
            throw GoogleOAuthError.invalidResponse
        }
        return idToken
    }

    private static func makeCodeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncodedString()
    }

    private static func codeChallenge(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return Data(digest).base64URLEncodedString()
    }

    private static func formEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

enum GoogleOAuthError: LocalizedError {
    case notConfigured
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Google Login is not configured."
        case .invalidResponse:
            return "Google Login response was invalid."
        }
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
#endif
