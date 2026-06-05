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
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration

    func updatePushToken(
        registration: RemotePushRegistration,
        apnsToken: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws

    func fetchSnapshot(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendSnapshot

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings

    func fetchAPIStatus(registration: RemotePushRegistration) async throws -> BackendAPIStatus

    func validateAPIKey(registration: RemotePushRegistration) async throws -> BackendAPIValidation

    func fetchOpenAIModelOptions() async throws -> [OpenAIModelOption]

    func fetchStats(
        registration: RemotePushRegistration,
        period: BackendStatsPeriod,
        startAt: Date?,
        endAt: Date?,
        search: String,
        sort: BackendStatsSort,
        limit: Int,
        offset: Int
    ) async throws -> BackendStats

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        topic: String?,
        limit: Int,
        offset: Int,
        excludeDeviceID: String?
    ) async throws -> CommunityQuestionsResponse

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityUserProfile

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?
    ) async throws -> CommunityUserProfile

    func reportCommunityQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws

    func createQuestion(registration: RemotePushRegistration) async throws -> StudyRecord

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws

    func clearRecords(registration: RemotePushRegistration) async throws

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord
}

@MainActor
final class RemotePushBackendClient: RemotePushBackendClientProtocol {
    static let defaultBaseURL = URL(string: "https://api.ghkdqhrbals.org")!

    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder: JSONDecoder

    init(
        baseURL: URL = RemotePushBackendClient.defaultBaseURL,
        session: URLSession = URLSession(configuration: .ephemeral)
    ) {
        self.baseURL = baseURL
        self.session = session
        self.decoder = Self.makeDecoder()
    }

    nonisolated static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom(Self.decodeBackendDate)
        return decoder
    }

    func registerDevice(
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        let requestBody = RegisterDeviceRequest(
            apnsToken: apnsToken ?? "",
            platform: "ios",
            apnsEnvironment: apnsEnvironment,
            language: language.backendCode,
            timezone: timezone
        )
        var request = URLRequest(url: endpoint("api", "v1", "devices", "register"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        let data = try await perform(request)
        let response = try decoder.decode(RegisterDeviceResponse.self, from: data)
        return RemotePushRegistration(
            deviceID: response.deviceID,
            clientSecret: response.clientSecret,
            apnsToken: apnsToken ?? ""
        )
    }

    func updatePushToken(
        registration: RemotePushRegistration,
        apnsToken: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        let requestBody = PushTokenRequest(
            apnsToken: apnsToken,
            apnsEnvironment: apnsEnvironment
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "push-token")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        _ = try await perform(request)
        return RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
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
            notificationSound: settings.notificationSound.backendSoundName,
            customPrompt: settings.customPrompt,
            appLanguage: settings.appLanguage.backendCode,
            openAIModel: settings.sanitizedOpenAIModel,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "schedule")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        _ = try await perform(request)
    }

    func fetchSnapshot(
        registration: RemotePushRegistration,
        limit: Int = 500,
        offset: Int = 0
    ) async throws -> BackendSnapshot {
        var components = URLComponents(
            url: endpoint("api", "v1", "devices", registration.deviceID, "snapshot"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "offset", value: "\(offset)")
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendSnapshot.self, from: data)
    }

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "settings")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStudySettings.self, from: data)
    }

    func fetchAPIStatus(registration: RemotePushRegistration) async throws -> BackendAPIStatus {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "api")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendAPIStatus.self, from: data)
    }

    func fetchOpenAIModelOptions() async throws -> [OpenAIModelOption] {
        var request = URLRequest(url: endpoint("api", "v1", "openai", "models"))
        request.httpMethod = "GET"
        let data = try await perform(request)
        let response = try decoder.decode([OpenAIModelDescriptor].self, from: data)
        return response.map {
            OpenAIModelOption(
                id: $0.id,
                displayName: $0.displayName,
                supportsTextVerbosity: $0.supportsTextVerbosity
            )
        }
    }

    func validateAPIKey(registration: RemotePushRegistration) async throws -> BackendAPIValidation {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "api", "validate")
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(BackendAPIValidation.self, from: data)
    }

    func fetchStats(
        registration: RemotePushRegistration,
        period: BackendStatsPeriod = .all,
        startAt: Date? = nil,
        endAt: Date? = nil,
        search: String = "",
        sort: BackendStatsSort = .level,
        limit: Int = 8,
        offset: Int = 0
    ) async throws -> BackendStats {
        var components = URLComponents(
            url: endpoint("api", "v1", "devices", registration.deviceID, "stats"),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "period", value: period.rawValue),
            URLQueryItem(name: "search", value: search),
            URLQueryItem(name: "sort", value: sort.rawValue),
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "offset", value: "\(offset)")
        ]
        if let startAt {
            queryItems.append(URLQueryItem(name: "startAt", value: Self.dateFormatter.string(from: startAt)))
        }
        if let endAt {
            queryItems.append(URLQueryItem(name: "endAt", value: Self.dateFormatter.string(from: endAt)))
        }
        components?.queryItems = queryItems
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStats.self, from: data)
    }

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        topic: String?,
        limit: Int = 20,
        offset: Int = 0,
        excludeDeviceID: String? = nil
    ) async throws -> CommunityQuestionsResponse {
        var components = URLComponents(
            url: endpoint("api", "v1", "public", "questions"),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "limit", value: "\(max(1, min(limit, 100)))"),
            URLQueryItem(name: "offset", value: "\(max(0, offset))")
        ]
        if let topic, !topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            queryItems.append(URLQueryItem(name: "topic", value: topic))
        }
        if let excludeDeviceID,
           !excludeDeviceID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            queryItems.append(URLQueryItem(name: "excludeDeviceId", value: excludeDeviceID))
        }
        components?.queryItems = queryItems
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(CommunityQuestionsResponse.self, from: data)
    }

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "auth", "google")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(GoogleLoginRequest(idToken: idToken))
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "profile")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?
    ) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "profile")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(ProfileUpdateRequest(displayName: displayName, bio: bio))
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func reportCommunityQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "public", "questions", questionID, "report")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(ReportQuestionRequest(reason: reason, message: message))
        _ = try await perform(request)
    }

    func createQuestion(registration: RemotePushRegistration) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "questions")
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records", recordID, "answer")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AnswerRequest(answer: answer))
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records", recordID, "answer")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AnswerRequest(answer: answer))
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records", recordID, "skip")
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records", recordID)
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func clearRecords(registration: RemotePushRegistration) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records")
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "devices", registration.deviceID, "records", recordID)
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        var request = request
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let startedAt = Date()
        let requestLog = APITrafficLogEntry(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "<unknown>",
            requestHeaders: Self.safeHeaderLog(for: request),
            requestBody: Self.safeBodyLog(data: request.httpBody)
        )

        do {
            let (data, response) = try await session.data(for: request)
            let durationMS = Date().timeIntervalSince(startedAt) * 1000
            guard let httpResponse = response as? HTTPURLResponse else {
                let errorEntry = APITrafficLogEntry(
                    id: requestLog.id,
                    method: requestLog.method,
                    url: requestLog.url,
                    durationMS: durationMS,
                    requestHeaders: requestLog.requestHeaders,
                    requestBody: requestLog.requestBody,
                    responseBody: "",
                    error: RemotePushBackendError.invalidResponse.localizedDescription,
                    isError: true
                )
                NotificationCenter.default.post(
                    name: APITrafficNotification.didReceiveLog,
                    object: self,
                    userInfo: [APITrafficNotification.userInfoKey: errorEntry]
                )
                throw RemotePushBackendError.invalidResponse
            }

            let statusCode = httpResponse.statusCode
            let responseBodyText = String(data: data, encoding: .utf8) ?? ""

            if !(200..<300).contains(statusCode) {
                let entry = APITrafficLogEntry(
                    id: requestLog.id,
                    method: requestLog.method,
                    url: requestLog.url,
                    statusCode: statusCode,
                    durationMS: durationMS,
                    requestHeaders: requestLog.requestHeaders,
                    requestBody: requestLog.requestBody,
                    responseBody: Self.safeResponseBody(responseBodyText),
                    error: "HTTP \(statusCode)",
                    isError: true
                )
                NotificationCenter.default.post(
                    name: APITrafficNotification.didReceiveLog,
                    object: self,
                    userInfo: [APITrafficNotification.userInfoKey: entry]
                )
                throw RemotePushBackendError.httpStatus(statusCode, responseBodyText)
            }

            let entry = APITrafficLogEntry(
                id: requestLog.id,
                method: requestLog.method,
                url: requestLog.url,
                statusCode: statusCode,
                durationMS: durationMS,
                requestHeaders: requestLog.requestHeaders,
                requestBody: requestLog.requestBody,
                responseBody: Self.safeResponseBody(responseBodyText),
                isError: false
            )
            NotificationCenter.default.post(
                name: APITrafficNotification.didReceiveLog,
                object: self,
                userInfo: [APITrafficNotification.userInfoKey: entry]
            )

            return data
        } catch {
            let durationMS = Date().timeIntervalSince(startedAt) * 1000
            let entry = APITrafficLogEntry(
                id: requestLog.id,
                method: requestLog.method,
                url: requestLog.url,
                durationMS: durationMS,
                requestHeaders: requestLog.requestHeaders,
                requestBody: requestLog.requestBody,
                responseBody: "",
                error: error.localizedDescription,
                isError: true
            )
            NotificationCenter.default.post(
                name: APITrafficNotification.didReceiveLog,
                object: self,
                userInfo: [APITrafficNotification.userInfoKey: entry]
            )
            throw error
        }
    }

    private static func safeHeaderLog(for request: URLRequest) -> String {
        guard let headers = request.allHTTPHeaderFields,
              !headers.isEmpty else {
            return ""
        }

        var safeHeaders = headers
        for sensitiveKey in ["X-Client-Secret", "Authorization"] {
            if safeHeaders[sensitiveKey] != nil {
                safeHeaders[sensitiveKey] = "[REDACTED]"
            }
        }

        return safeHeaders
            .map { "\($0.key): \($0.value)" }
            .sorted()
            .joined(separator: "\n")
    }

    private static func safeBodyLog(data: Data?) -> String {
        guard let data,
              !data.isEmpty,
              let body = String(data: data, encoding: .utf8) else {
            return ""
        }

        let sanitized = body
            .replacingOccurrences(
                of: #"("openaiApiKey"\s*:\s*)\"[^\"]+\""#,
                with: #"$1\"[REDACTED]\""#,
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)

        return sanitized
    }

    private static func safeResponseBody(_ body: String) -> String {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > 2_000 else {
            return trimmed
        }

        return String(trimmed.prefix(2_000))
    }

    private func endpoint(_ components: String...) -> URL {
        components.reduce(baseURL) { partialURL, component in
            partialURL.appendingPathComponent(component)
        }
    }

    private func authenticatedRequest(registration: RemotePushRegistration, url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(registration.deviceID, forHTTPHeaderField: "X-Device-Id")
        request.setValue(registration.clientSecret, forHTTPHeaderField: "X-Client-Secret")
        return request
    }

    private static let dateFormatter = ISO8601DateFormatter()

    private struct RegisterDeviceRequest: Encodable {
        var apnsToken: String
        var platform: String
        var apnsEnvironment: String
        var language: String
        var timezone: String
    }

    private struct PushTokenRequest: Encodable {
        var apnsToken: String
        var apnsEnvironment: String
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
        var customPrompt: String
        var appLanguage: String
        var openAIModel: String
        var maxHistoryCount: Int
        var isQuestionPublic: Bool

        enum CodingKeys: String, CodingKey {
            case topic
            case difficultyLevel
            case intervalMinutes
            case enabled
            case openAIAPIKey = "openaiApiKey"
            case notificationSound
            case customPrompt
            case appLanguage
            case openAIModel = "openaiModel"
            case maxHistoryCount
            case isQuestionPublic = "isQuestionPublic"
        }
    }

    private struct AnswerRequest: Encodable {
        var answer: String
    }

    private struct GoogleLoginRequest: Encodable {
        var idToken: String
    }

    private struct ProfileUpdateRequest: Encodable {
        var displayName: String?
        var bio: String?
    }

    private struct ReportQuestionRequest: Encodable {
        var reason: String
        var message: String
    }

    private struct OpenAIModelDescriptor: Decodable {
        var id: String
        var displayName: String
        var supportsTextVerbosity: Bool
    }
}

struct BackendSnapshot: Decodable, Equatable {
    var settings: BackendStudySettings
    var api: BackendAPIStatus?
    var records: [StudyRecord]
    var stats: BackendStats?
    var totalCount: Int
    var serverTime: Date
}

struct BackendAPIStatus: Decodable, Equatable {
    var openAIKeyConfigured: Bool
    var openAIModel: String
    var usageURL: URL
    var billingURL: URL
    var creditsURL: URL

    enum CodingKeys: String, CodingKey {
        case openAIKeyConfigured = "openaiKeyConfigured"
        case openAIModel = "openaiModel"
        case usageURL = "usageUrl"
        case billingURL = "billingUrl"
        case creditsURL = "creditsUrl"
    }
}

struct BackendAPIValidation: Decodable, Equatable {
    var openAIKeyConfigured: Bool
    var isValid: Bool
    var openAIModel: String

    enum CodingKeys: String, CodingKey {
        case openAIKeyConfigured = "openaiKeyConfigured"
        case isValid
        case openAIModel = "openaiModel"
    }
}

enum BackendStatsPeriod: String {
    case all
    case today
    case last7
    case last30
    case last90
}

enum BackendStatsSort: String {
    case level
    case recent
    case name
    case count
}

struct BackendStats: Decodable, Equatable {
    var totalResponses: Int
    var totalTopics: Int
    var topics: [BackendTopicStats]
    var limit: Int
    var offset: Int
    var generatedAt: Date
}

struct CommunityQuestion: Decodable, Equatable, Identifiable {
    var id: String
    var question: String
    var topic: String
    var difficultyLevel: Int
    var status: String
    var source: String
    var createdAt: Date
    var author: CommunityUserProfile?
}

struct CommunityUserProfile: Codable, Equatable, Identifiable {
    var id: Int
    var displayName: String
    var bio: String
    var avatarURL: URL?

    enum CodingKeys: String, CodingKey {
        case id
        case displayName
        case bio
        case avatarURL = "avatarUrl"
    }
}

struct CommunityQuestionsResponse: Decodable, Equatable {
    var questions: [CommunityQuestion]
    var totalCount: Int
    var limit: Int
    var offset: Int
}

struct BackendTopicStats: Decodable, Equatable, Identifiable {
    var topicKey: String
    var topic: String
    var topicAliases: [String]
    var count: Int
    var average: Int
    var best: Int
    var correctRate: Int
    var levelRange: BackendTopicLevelRange
    var latestAt: Date
    var records: [StudyRecord]

    var id: String { topicKey }
}

struct BackendTopicLevelRange: Decodable, Equatable {
    var level: Int
    var average: Int
    var sampleCount: Int
    var centerLevel: Double
    var lowerBound: Double
    var upperBound: Double
}

struct BackendStudySettings: Decodable, Equatable {
    var topic: String
    var difficultyLevel: Int
    var intervalMinutes: Int
    var enabled: Bool
    var notificationSound: String?
    var customPrompt: String
    var appLanguage: String
    var openAIModel: String
    var maxHistoryCount: Int
    var isQuestionPublic: Bool
    var openAIKeyConfigured: Bool
    var nextDueAt: Date?
    var lastError: String?

    enum CodingKeys: String, CodingKey {
        case topic
        case difficultyLevel
        case intervalMinutes
        case enabled
        case notificationSound
        case customPrompt
        case appLanguage
        case openAIModel = "openaiModel"
        case maxHistoryCount
        case isQuestionPublic = "isQuestionPublic"
        case openAIKeyConfigured = "openaiKeyConfigured"
        case nextDueAt
        case lastError
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        topic = try container.decodeIfPresent(String.self, forKey: .topic) ?? ""
        difficultyLevel = try container.decodeIfPresent(Int.self, forKey: .difficultyLevel) ?? 5
        intervalMinutes = try container.decodeIfPresent(Int.self, forKey: .intervalMinutes) ?? 15
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        notificationSound = try container.decodeIfPresent(String.self, forKey: .notificationSound)
        customPrompt = try container.decodeIfPresent(String.self, forKey: .customPrompt) ?? ""
        appLanguage = try container.decodeIfPresent(String.self, forKey: .appLanguage) ?? "ko"
        openAIModel = try container.decodeIfPresent(String.self, forKey: .openAIModel) ?? StudySettings.defaultOpenAIModel
        maxHistoryCount = try container.decodeIfPresent(Int.self, forKey: .maxHistoryCount) ?? 100
        isQuestionPublic = try container.decodeIfPresent(Bool.self, forKey: .isQuestionPublic) ?? false
        openAIKeyConfigured = try container.decodeIfPresent(Bool.self, forKey: .openAIKeyConfigured) ?? false
        nextDueAt = try container.decodeIfPresent(Date.self, forKey: .nextDueAt)
        lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
    }

    func studySettings(fallback: StudySettings) -> StudySettings {
        let language = AppLanguage(backendCode: appLanguage) ?? fallback.appLanguage
        return StudySettings(
            topic: topic.isEmpty ? fallback.topic : topic,
            difficulty: Difficulty(level: difficultyLevel),
            appLanguage: language,
            language: language.studyLanguage,
            openAIModel: openAIModel,
            notificationSound: NotificationSoundOption(backendSoundName: notificationSound) ?? fallback.notificationSound,
            customPrompt: customPrompt,
            intervalMinutes: intervalMinutes,
            maxHistoryCount: maxHistoryCount,
            isQuestionPublic: isQuestionPublic
        )
    }
}

private extension RemotePushBackendClient {
    nonisolated static func decodeBackendDate(from decoder: Decoder) throws -> Date {
        let container = try decoder.singleValueContainer()
        if let timestamp = try? container.decode(Double.self) {
            return Date(timeIntervalSince1970: timestamp)
        }

        let value = try container.decode(String.self)
        if let date = backendDateFormatterWithFractionalSeconds.date(from: value) ??
            backendDateFormatter.date(from: value) {
            return date
        }

        throw DecodingError.dataCorruptedError(
            in: container,
            debugDescription: "Expected an ISO-8601 backend date."
        )
    }

    nonisolated static var backendDateFormatter: ISO8601DateFormatter {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }

    nonisolated static var backendDateFormatterWithFractionalSeconds: ISO8601DateFormatter {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
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
    init?(backendCode: String) {
        switch backendCode {
        case "ko":
            self = .korean
        case "en":
            self = .english
        default:
            return nil
        }
    }

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
    init?(backendSoundName: String?) {
        switch backendSoundName {
        case nil, "default":
            self = .defaultSound
        case "none":
            self = .none
        case "study_ping.wav":
            self = .softPing
        case "study_chime.wav":
            self = .chime
        case "study_pop.wav":
            self = .pop
        case "study_bell.wav":
            self = .bell
        case "study_tap.wav":
            self = .tap
        default:
            return nil
        }
    }

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
