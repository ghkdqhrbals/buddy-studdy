import Foundation

struct RemotePushRegistration: Codable, Equatable {
    var deviceID: String
    var clientSecret: String
    var apnsToken: String

    enum CodingKeys: String, CodingKey {
        case deviceID = "deviceId"
        case clientSecret
        case apnsToken
    }
}

@MainActor
protocol RemotePushBackendClientProtocol {
    func registerDevice(
        apnsToken: String,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws
}

@MainActor
final class RemotePushBackendClient: RemotePushBackendClientProtocol {
    static let defaultBaseURL = URL(string: "https://api.ghkdqhrbals.org")!

    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: URL = RemotePushBackendClient.defaultBaseURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func registerDevice(
        apnsToken: String,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        let requestBody = RegisterDeviceRequest(
            apnsToken: apnsToken,
            platform: "ios",
            apnsEnvironment: apnsEnvironment,
            language: language.backendCode,
            timezone: timezone
        )
        var request = URLRequest(url: endpoint("v1", "devices", "register"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        let data = try await perform(request)
        let response = try decoder.decode(RegisterDeviceResponse.self, from: data)
        return RemotePushRegistration(
            deviceID: response.deviceID,
            clientSecret: response.clientSecret,
            apnsToken: apnsToken
        )
    }

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws {
        let requestBody = ScheduleRequest(
            topic: settings.topic,
            difficultyLevel: settings.difficulty.level,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            enabled: enabled,
            openAIAPIKey: apiKey,
            notificationSound: settings.notificationSound.backendSoundName
        )
        var request = URLRequest(
            url: endpoint("v1", "devices", registration.deviceID, "schedule")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(registration.deviceID, forHTTPHeaderField: "X-Device-Id")
        request.setValue(registration.clientSecret, forHTTPHeaderField: "X-Client-Secret")
        request.httpBody = try encoder.encode(requestBody)

        _ = try await perform(request)
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RemotePushBackendError.invalidResponse
        }

        guard (200..<300).contains(httpResponse.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw RemotePushBackendError.httpStatus(httpResponse.statusCode, body)
        }

        return data
    }

    private func endpoint(_ components: String...) -> URL {
        components.reduce(baseURL) { partialURL, component in
            partialURL.appendingPathComponent(component)
        }
    }

    private struct RegisterDeviceRequest: Encodable {
        var apnsToken: String
        var platform: String
        var apnsEnvironment: String
        var language: String
        var timezone: String
    }

    private struct RegisterDeviceResponse: Decodable {
        var deviceID: String
        var clientSecret: String

        enum CodingKeys: String, CodingKey {
            case deviceID = "deviceId"
            case clientSecret
        }
    }

    private struct ScheduleRequest: Encodable {
        var topic: String
        var difficultyLevel: Int
        var intervalMinutes: Int
        var enabled: Bool
        var openAIAPIKey: String?
        var notificationSound: String?

        enum CodingKeys: String, CodingKey {
            case topic
            case difficultyLevel
            case intervalMinutes
            case enabled
            case openAIAPIKey = "openaiApiKey"
            case notificationSound
        }
    }
}

enum RemotePushBackendError: LocalizedError {
    case invalidResponse
    case httpStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Invalid backend response."
        case .httpStatus(let statusCode, let body):
            return "Backend request failed: HTTP \(statusCode) \(body)"
        }
    }
}

private extension AppLanguage {
    var backendCode: String {
        switch self {
        case .korean:
            return "ko"
        case .english:
            return "en"
        }
    }
}

private extension NotificationSoundOption {
    var backendSoundName: String? {
        switch self {
        case .defaultSound:
            return "default"
        case .none:
            return "none"
        default:
            return bundledFileName
        }
    }
}
