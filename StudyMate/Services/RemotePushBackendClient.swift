import Foundation
import NaturalLanguage

struct RemotePushRegistration: Codable, Equatable {
    var deviceID: String
    var clientSecret: String
    var apnsToken: String
    var accessToken: String? = nil
    var accessTokenExpiresAt: Date? = nil

    enum CodingKeys: String, CodingKey {
        case deviceID = "deviceId"
        case clientSecret
        case apnsToken
        case accessToken
        case accessTokenExpiresAt
    }

    var hasAccessToken: Bool {
        guard let accessToken,
              !accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }

        guard accessTokenDeviceID == deviceID else {
            return false
        }

        if let accessTokenExpiresAt {
            return accessTokenExpiresAt > Date().addingTimeInterval(60)
        }

        return true
    }

    var hasRegisteredAccessToken: Bool {
        hasAccessToken && accessTokenIsAnonymous == false
    }

    private var accessTokenDeviceID: String? {
        accessTokenPayload?["device_id"] as? String
    }

    private var accessTokenIsAnonymous: Bool? {
        if let value = accessTokenPayload?["is_anonymous"] as? Bool {
            return value
        }

        if let status = accessTokenPayload?["status"] as? String {
            return status == "ANONYMOUS"
        }

        return nil
    }

    private var accessTokenPayload: [String: Any]? {
        guard let accessToken else {
            return nil
        }

        let segments = accessToken.split(separator: ".")
        guard segments.count >= 2,
              let payloadData = Self.base64URLDecodedData(String(segments[1])),
              let object = try? JSONSerialization.jsonObject(with: payloadData) as? [String: Any] else {
            return nil
        }

        return object
    }

    private static func base64URLDecodedData(_ value: String) -> Data? {
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let paddingLength = (4 - base64.count % 4) % 4
        if paddingLength > 0 {
            base64.append(String(repeating: "=", count: paddingLength))
        }
        return Data(base64Encoded: base64)
    }
}

struct CommunityLoginResult: Equatable {
    var profile: CommunityUserProfile
    var registration: RemotePushRegistration
}

struct AvatarCatalogResponse: Codable, Equatable {
    var categories: [AvatarCategory]
    var items: [AvatarCatalogItem]
    var defaultConfig: [String: String]
    var currentConfig: [String: String]

    func item(for key: String?) -> AvatarCatalogItem? {
        guard let key else { return nil }
        return items.first { $0.key == key }
    }

    func items(for category: AvatarCategory) -> [AvatarCatalogItem] {
        items
            .filter { $0.category == category.key }
            .sorted { lhs, rhs in
                if lhs.sortOrder == rhs.sortOrder {
                    return lhs.key < rhs.key
                }
                return lhs.sortOrder < rhs.sortOrder
            }
    }

    var resolvedCurrentConfig: [String: String] {
        defaultConfig.merging(currentConfig) { _, current in current }
    }
}

struct AvatarCategory: Codable, Equatable, Identifiable {
    var key: String
    var titleKo: String
    var titleEn: String
    var slot: String
    var required: Bool
    var singleSelect: Bool
    var zIndex: Int
    var sortOrder: Int

    var id: String { key }

    func title(language: AppLanguage) -> String {
        switch language {
        case .korean:
            return titleKo
        case .english:
            return titleEn
        case .japanese:
            return titleEn
        }
    }
}

struct AvatarCatalogItem: Codable, Equatable, Identifiable {
    var key: String
    var category: String
    var slot: String
    var displayNameKo: String
    var displayNameEn: String
    var assetName: String
    var colorHex: String
    var defaultGrant: Bool
    var compatibleBases: [String]
    var zIndex: Int
    var sortOrder: Int

    var id: String { key }

    func displayName(language: AppLanguage) -> String {
        switch language {
        case .korean:
            return displayNameKo
        case .english:
            return displayNameEn
        case .japanese:
            return displayNameEn
        }
    }
}

struct EmailVerificationCodeResult: Equatable {
    var email: String
    var expiresInSeconds: Int
}

@MainActor
struct BackendBaseURLConfiguration: Equatable {
    static let bundledBaseURLInfoKey = "BuddyStudyBackendBaseURL"
    static let defaultDebugBaseURL = URL(string: "https://lowfidev.cloud")!

    var isDebuggingEnabled: Bool
    var debugBackendBaseURL: String
    var bundledBackendBaseURL: String? = Bundle.main.object(
        forInfoDictionaryKey: BackendBaseURLConfiguration.bundledBaseURLInfoKey
    ) as? String
    var launchBackendBaseURL: String? = ProcessInfo.processInfo.environment["BUDDYSTUDY_BACKEND_BASE_URL"]

    var normalizedDebugBackendBaseURL: String {
        Self.normalizedDebugBackendBaseURL(debugBackendBaseURL)
    }

    var debugBackendURL: URL? {
        Self.resolvedDebugBackendURL(from: normalizedDebugBackendBaseURL)
    }

    var effectiveBaseURL: URL {
        if let launchBackendBaseURL,
           let launchURL = Self.resolvedDebugBackendURL(from: launchBackendBaseURL) {
            return launchURL
        }
        if isDebuggingEnabled {
            return debugBackendURL ?? Self.defaultDebugBaseURL
        }
        if let bundledBackendBaseURL,
           let bundledURL = Self.resolvedDebugBackendURL(from: bundledBackendBaseURL) {
            return bundledURL
        }

        return RemotePushBackendClient.defaultBaseURL
    }

    var displayBaseURL: String {
        effectiveBaseURL.absoluteString
    }

    var isDebugBackendBaseURLValid: Bool {
        normalizedDebugBackendBaseURL.isEmpty || debugBackendURL != nil
    }

    func makeClient() -> RemotePushBackendClient {
        RemotePushBackendClient(baseURL: effectiveBaseURL)
    }

    static func normalizedDebugBackendBaseURL(_ value: String) -> String {
        let trimmedURL = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedURL.count > 1 else {
            return trimmedURL
        }

        let normalizedURL = String(trimmedURL.drop { $0 == "/" })
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        switch normalizedURL.lowercased() {
        case "https://api.lowfidev.cloud":
            return "https://lowfidev.cloud"
        case "http://api.lowfidev.cloud":
            return "http://lowfidev.cloud"
        default:
            return normalizedURL
        }
    }

    static func resolvedDebugBackendURL(from value: String) -> URL? {
        let normalizedURL = normalizedDebugBackendBaseURL(value)
        guard !normalizedURL.isEmpty,
              let url = URL(string: normalizedURL),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              url.host != nil else {
            return nil
        }

        return url
    }
}

@MainActor
protocol RemotePushBackendClientProtocol {
    func checkAppUpdate(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision

    func recordAppUpdateEvent(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws

    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request: BackendAppControlEventRequest
    ) async throws

    func registerDevice(
        installationIdentifier: String,
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

    func bootstrapAccessToken(registration: RemotePushRegistration) async throws -> RemotePushRegistration

    func logout(registration: RemotePushRegistration) async throws

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms]

    func saveTermsAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        version: String?,
        contentHash: String?,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference]

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference

    func fetchNotifications(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendNotificationsPage

    func fetchNotificationUnreadCount(registration: RemotePushRegistration) async throws -> Int

    func markNotificationRead(registration: RemotePushRegistration, notificationID: String) async throws

    func markAllNotificationsRead(registration: RemotePushRegistration) async throws

    func deleteNotification(registration: RemotePushRegistration, notificationID: String) async throws

    func deleteAllNotifications(registration: RemotePushRegistration) async throws

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws

    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendStudyPage

    func fetchStudyDetail(
        registration: RemotePushRegistration,
        studyID: Int,
        language: AppLanguage
    ) async throws -> BackendStudyRoom

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings
    ) async throws -> BackendStudyRoom

    func createStudyTopic(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        topic: String,
        difficulty: Difficulty,
        sortOrder: Int,
        activeForQuestions: Bool
    ) async throws -> BackendStudyRoom

    func suggestStudyTopics(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        count: Int
    ) async throws -> [String]

    func updateStudyTopicActivation(
        registration: RemotePushRegistration,
        studyID: Int,
        active: Bool
    ) async throws -> BackendStudyRoom

    func updateStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        category: StudyCategory,
        settings: StudySettings
    ) async throws

    func deleteStudy(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws

    func fetchQuestionQuota(
        registration: RemotePushRegistration
    ) async throws -> BackendQuestionQuota

    func fetchBillingCatalog(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingCatalog

    func fetchBillingStatus(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus

    func reconcileBillingSubscription(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus

    func createBillingCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice

    func abandonBillingCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice

    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String
    ) async throws -> BackendBillingInvoice

    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice

    func fetchBillingInvoices(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendBillingInvoicePage

    func fetchBillingInvoice(
        registration: RemotePushRegistration,
        invoiceID: Int64
    ) async throws -> BackendBillingInvoice

    func fetchReferralSummary(
        registration: RemotePushRegistration
    ) async throws -> BackendReferralSummary

    func redeemReferral(
        registration: RemotePushRegistration,
        code: String
    ) async throws -> BackendReferralSummary

    func requestBillingRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction

    func requestBillingCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction

    func fetchRecords(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendRecordsPage

    func fetchRecordsForStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        limit: Int,
        offset: Int,
        language: AppLanguage
    ) async throws -> BackendRecordsPage

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings

    func fetchAPIStatus(registration: RemotePushRegistration) async throws -> BackendAPIStatus

    func validateAPIKey(registration: RemotePushRegistration) async throws -> BackendAPIValidation

    func fetchOpenAIModelOptions() async throws -> [OpenAIModelOption]

    func fetchStats(
        registration: RemotePushRegistration,
        period: BackendStatsPeriod,
        startAt: Date?,
        endAt: Date?,
        sort: BackendStatsSort,
        limit: Int,
        offset: Int
    ) async throws -> BackendStats

    func fetchStatsActivity(
        registration: RemotePushRegistration,
        startAt: Date?,
        endAt: Date?
    ) async throws -> BackendStatsActivity

    func fetchStudyGrowth(
        registration: RemotePushRegistration,
        startAt: Date?,
        endAt: Date?
    ) async throws -> BackendStudyGrowth

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        excludeDeviceID: String?,
        language: AppLanguage
    ) async throws -> CommunityQuestionsResponse

    func fetchNativeAdvertisementFallback(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws -> CommunityNativeAdvertisement?

    func recordAdMobNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws

    func recordAdMobNativeAdvertisementClick(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws

    func fetchLikedPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestionsResponse

    func fetchPublicQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestion

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult

    func requestEmailVerificationCode(
        registration: RemotePushRegistration,
        email: String
    ) async throws -> EmailVerificationCodeResult

    func loginWithEmail(
        registration: RemotePushRegistration,
        email: String,
        password: String,
        verificationCode: String?
    ) async throws -> CommunityLoginResult

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile

    func fetchAvatarCatalog(registration: RemotePushRegistration) async throws -> AvatarCatalogResponse

    func updateProfileAvatar(
        registration: RemotePushRegistration,
        avatarMode: String,
        avatarConfig: [String: String],
        avatarColorSeed: String?
    ) async throws -> CommunityUserProfile

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?,
        avatarSymbolName: String?,
        avatarColorSeed: String?,
        avatarMode: String?,
        avatarConfig: [String: String]?,
        allowPublicQuestions: Bool?
    ) async throws -> CommunityUserProfile

    func withdrawMyProfile(registration: RemotePushRegistration) async throws -> RemotePushRegistration

    func reportCommunityQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws

    func setCommunityUserBlocked(
        registration: RemotePushRegistration,
        userID: Int,
        blocked: Bool
    ) async throws -> CommunityUserBlockState

    func submitAppFeedback(
        registration: RemotePushRegistration,
        content: String
    ) async throws

    func recordNativeAdvertisementView(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func recordNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func setCommunityQuestionLike(
        registration: RemotePushRegistration,
        questionID: String,
        isLiked: Bool
    ) async throws -> CommunityLikeState

    func fetchCommunityQuestionComments(
        registration: RemotePushRegistration,
        questionID: String,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityCommentsResponse

    func createCommunityQuestionComment(
        registration: RemotePushRegistration,
        questionID: String,
        body: String,
        sourceLanguage: String
    ) async throws -> CommunityQuestionComment

    func deleteCommunityQuestionComment(
        registration: RemotePushRegistration,
        questionID: String,
        commentID: String
    ) async throws

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int,
        idempotencyKey: String
    ) async throws -> QuestionGenerationAccepted

    func fetchQuestionGenerationProcess(
        registration: RemotePushRegistration,
        correlationID: String
    ) async throws -> QuestionGenerationProcess

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord

    func fetchAnswerGradingProcess(
        registration: RemotePushRegistration,
        correlationID: String,
        afterEventID: Int64
    ) async throws -> AnswerGradingProcess

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws

    func updateRecordPublicity(
        registration: RemotePushRegistration,
        recordID: String,
        isPublic: Bool
    ) async throws -> StudyRecord

    func clearRecords(registration: RemotePushRegistration) async throws

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> StudyRecord
}

extension RemotePushBackendClientProtocol {
    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchNativeAdvertisementFallback(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws -> CommunityNativeAdvertisement? {
        throw RemotePushBackendError.invalidResponse
    }

    func recordAdMobNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws {
        throw RemotePushBackendError.invalidResponse
    }

    func recordAdMobNativeAdvertisementClick(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchBillingStatus(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus {
        throw RemotePushBackendError.invalidResponse
    }

    func reconcileBillingSubscription(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchBillingCatalog(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingCatalog {
        throw RemotePushBackendError.invalidResponse
    }

    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice {
        throw RemotePushBackendError.invalidResponse
    }

    func createBillingCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice {
        throw RemotePushBackendError.invalidResponse
    }

    func abandonBillingCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice {
        throw RemotePushBackendError.invalidResponse
    }

    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String
    ) async throws -> BackendBillingInvoice {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchBillingInvoices(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendBillingInvoicePage {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchBillingInvoice(
        registration: RemotePushRegistration,
        invoiceID: Int64
    ) async throws -> BackendBillingInvoice {
        throw RemotePushBackendError.invalidResponse
    }

    func fetchReferralSummary(
        registration: RemotePushRegistration
    ) async throws -> BackendReferralSummary {
        throw RemotePushBackendError.invalidResponse
    }

    func redeemReferral(
        registration: RemotePushRegistration,
        code: String
    ) async throws -> BackendReferralSummary {
        throw RemotePushBackendError.invalidResponse
    }

    func requestBillingRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        throw RemotePushBackendError.invalidResponse
    }

    func requestBillingCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        throw RemotePushBackendError.invalidResponse
    }

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        throw RemotePushBackendError.invalidResponse
    }

    func checkAppUpdate(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        BackendAppUpdateDecision(updateAvailable: false, shouldPresent: false)
    }

    func recordAppUpdateEvent(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {}

    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request: BackendAppControlEventRequest
    ) async throws {}

    func fetchRecordsForStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        limit: Int,
        offset: Int,
        language: AppLanguage
    ) async throws -> BackendRecordsPage {
        throw RemotePushBackendError.invalidResponse
    }
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

    func checkAppUpdate(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "app-updates", "check")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            AppUpdateCheckRequest(
                platform: "ios",
                currentVersion: Self.currentAppVersion,
                currentBuild: Self.currentAppBuild,
                language: language.backendCode
            )
        )
        let data = try await perform(request)
        return try decoder.decode(BackendAppUpdateDecision.self, from: data)
    }

    func recordAppUpdateEvent(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "app-updates", String(campaignID), "events")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AppUpdateEventRequest(event: event))
        _ = try await perform(request)
    }

    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request event: BackendAppControlEventRequest
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "app-updates", "events")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(event)
        _ = try await perform(request)
    }

    func registerDevice(
        installationIdentifier: String,
        apnsToken: String?,
        language: AppLanguage,
        timezone: String,
        apnsEnvironment: String
    ) async throws -> RemotePushRegistration {
        let requestBody = RegisterDeviceRequest(
            installationId: installationIdentifier,
            apnsToken: apnsToken ?? "",
            platform: "ios",
            apnsEnvironment: apnsEnvironment,
            language: language.backendCode,
            timezone: timezone,
            appVersion: Self.currentAppVersion,
            appBuild: Self.currentAppBuild
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
            apnsToken: apnsToken ?? "",
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
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
            url: endpoint("api", "v1", "push-token")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        _ = try await perform(request)
        return RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: apnsToken,
            accessToken: registration.accessToken,
            accessTokenExpiresAt: registration.accessTokenExpiresAt
        )
    }

    func bootstrapAccessToken(registration: RemotePushRegistration) async throws -> RemotePushRegistration {
        var request = URLRequest(url: endpoint("api", "v1", "auth", "token"))
        request.httpMethod = "POST"
        request.setValue(registration.deviceID, forHTTPHeaderField: "X-Device-Id")
        request.setValue(registration.clientSecret, forHTTPHeaderField: "X-Client-Secret")

        let data = try await perform(request)
        let response = try decoder.decode(AccessTokenResponse.self, from: data)
        return RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: registration.apnsToken,
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
        )
    }

    func logout(registration: RemotePushRegistration) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "auth", "logout")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms] {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "terms", "active")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode([BackendTerms].self, from: data)
    }

    func saveTermsAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        version: String?,
        contentHash: String?,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "terms", "agreements")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            TermsAgreementRequest(
                type: type.rawValue,
                version: version,
                contentHash: contentHash,
                action: action.rawValue,
                source: source.rawValue
            )
        )
        let data = try await perform(request)
        return try decoder.decode(BackendPermissionEvaluations.self, from: data)
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "me", "permissions")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendPermissionEvaluations.self, from: data)
    }

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference] {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notification-preferences")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode([BackendNotificationPreference].self, from: data)
    }

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notification-preferences")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(NotificationPreferenceRequest(type: type.rawValue, enabled: enabled))
        let data = try await perform(request)
        return try decoder.decode(BackendNotificationPreference.self, from: data)
    }

    private func customPromptOverride(_ prompt: String) -> String? {
        let value = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value != StudySettings.defaultCustomPrompt else {
            return nil
        }
        return value
    }

    func updateSchedule(
        registration: RemotePushRegistration,
        settings: StudySettings,
        apiKey: String?,
        enabled: Bool
    ) async throws {
        let scheduleItems = settings.studyCategories.map { category in
            ScheduleItemRequest(
                topic: category.normalizedTitle,
                difficultyLevel: category.difficulty.level,
                customPrompt: customPromptOverride(category.normalizedCustomPrompt),
                openAIModel: category.sanitizedOpenAIModel
            )
        }
        let requestBody = ScheduleRequest(
            topic: scheduleItems.first?.topic ?? "",
            difficultyLevel: settings.difficulty.level,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            enabled: enabled,
            openAIAPIKey: apiKey,
            notificationSound: settings.notificationSound.backendSoundName,
            customPrompt: customPromptOverride(settings.customPrompt),
            appLanguage: settings.appLanguage.backendCode,
            openAIModel: settings.sanitizedOpenAIModel,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            schedules: scheduleItems
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "settings")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)

        _ = try await perform(request)
    }

    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int = 500,
        offset: Int = 0,
        query: String = "",
        language: AppLanguage = .korean
    ) async throws -> BackendStudyPage {
        var components = URLComponents(
            url: endpoint("api", "v1", "studies"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "offset", value: "\(offset)"),
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "language", value: language.backendCode)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStudyPage.self, from: data)
    }

    func fetchStudyDetail(
        registration: RemotePushRegistration,
        studyID: Int,
        language: AppLanguage = .korean
    ) async throws -> BackendStudyRoom {
        var components = URLComponents(
            url: endpoint("api", "v1", "studies", String(studyID)),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "tl", value: language.backendCode)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStudyRoom.self, from: data)
    }

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings
    ) async throws -> BackendStudyRoom {
        let body = CreateStudyRequest(
            topic: category.normalizedTitle,
            difficultyLevel: category.difficulty.level,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            enabled: true,
            notificationSound: settings.notificationSound.backendSoundName,
            customPrompt: customPromptOverride(category.normalizedCustomPrompt),
            openAIModel: category.sanitizedOpenAIModel,
            maxHistoryCount: settings.sanitizedMaxHistoryCount
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)

        let data = try await perform(request)
        return try decoder.decode(BackendStudyRoom.self, from: data)
    }

    func createStudyTopic(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        topic: String,
        difficulty: Difficulty,
        sortOrder: Int,
        activeForQuestions: Bool = true
    ) async throws -> BackendStudyRoom {
        let body = CreateStudyTopicRequest(
            topic: topic.trimmingCharacters(in: .whitespacesAndNewlines),
            difficultyLevel: difficulty.level,
            activeForQuestions: activeForQuestions,
            sortOrder: sortOrder
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies", String(parentStudyID), "topics")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)

        let data = try await perform(request)
        return try decoder.decode(BackendStudyRoom.self, from: data)
    }

    func suggestStudyTopics(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        count: Int = 10
    ) async throws -> [String] {
        var components = URLComponents(
            url: endpoint("api", "v1", "studies", String(parentStudyID), "topic-suggestions"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "count", value: "\(count)")]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }
        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(StudyTopicSuggestionsResponse.self, from: data).suggestions
    }

    func updateStudyTopicActivation(
        registration: RemotePushRegistration,
        studyID: Int,
        active: Bool
    ) async throws -> BackendStudyRoom {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies", String(studyID), "question-activation")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(StudyTopicActivationRequest(active: active))
        let data = try await perform(request)
        return try decoder.decode(BackendStudyRoom.self, from: data)
    }

    func updateStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        category: StudyCategory,
        settings: StudySettings
    ) async throws {
        let requestBody = ScheduleRequest(
            topic: category.normalizedTitle,
            difficultyLevel: category.difficulty.level,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            enabled: true,
            openAIAPIKey: nil,
            notificationSound: settings.notificationSound.backendSoundName,
            customPrompt: customPromptOverride(category.normalizedCustomPrompt),
            appLanguage: settings.appLanguage.backendCode,
            openAIModel: category.sanitizedOpenAIModel,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            schedules: []
        )
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies", String(studyID), "settings")
        )
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requestBody)
        _ = try await perform(request)
    }

    func fetchQuestionQuota(
        registration: RemotePushRegistration
    ) async throws -> BackendQuestionQuota {
        let url = endpoint("api", "v1", "questions", "quota")
        let request = authenticatedRequest(registration: registration, url: url)
        let data = try await perform(request)
        return try decoder.decode(BackendQuestionQuota.self, from: data)
    }

    func fetchBillingStatus(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus {
        let request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "status")
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingStatus.self, from: data)
    }

    func reconcileBillingSubscription(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingStatus {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "subscriptions", "reconcile")
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(BackendBillingStatus.self, from: data)
    }

    func fetchBillingCatalog(
        registration: RemotePushRegistration
    ) async throws -> BackendBillingCatalog {
        let request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "catalog")
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingCatalog.self, from: data)
    }

    func createBillingCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "checkouts")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            BillingCheckoutRequest(productId: productID, idempotencyKey: idempotencyKey)
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoice.self, from: data)
    }

    func abandonBillingCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint(
                "api", "v1", "billing", "checkouts",
                invoiceNumber.uuidString.lowercased(), "abandon"
            )
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoice.self, from: data)
    }

    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String
    ) async throws -> BackendBillingInvoice {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint(
                "api", "v1", "billing", "invoices",
                invoiceNumber.uuidString.lowercased(), "confirm"
            )
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            RevenueCatTransactionConfirmRequest(transactionId: transactionID)
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoice.self, from: data)
    }

    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "apple", "transactions")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            AppleTransactionSyncRequest(
                signedTransaction: signedTransaction,
                environment: environment,
                invoiceNumber: invoiceNumber
            )
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoice.self, from: data)
    }

    func fetchBillingInvoices(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendBillingInvoicePage {
        var components = URLComponents(
            url: endpoint("api", "v1", "billing", "invoices"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: String(max(1, min(limit, 100)))),
            URLQueryItem(name: "offset", value: String(max(offset, 0)))
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }
        let request = authenticatedRequest(registration: registration, url: url)
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoicePage.self, from: data)
    }

    func fetchBillingInvoice(
        registration: RemotePushRegistration,
        invoiceID: Int64
    ) async throws -> BackendBillingInvoice {
        let request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "invoices", String(invoiceID))
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingInvoiceDetail.self, from: data).invoice
    }

    func fetchReferralSummary(
        registration: RemotePushRegistration
    ) async throws -> BackendReferralSummary {
        let request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "referrals", "me")
        )
        let data = try await perform(request)
        return try decoder.decode(BackendReferralSummary.self, from: data)
    }

    func redeemReferral(
        registration: RemotePushRegistration,
        code: String
    ) async throws -> BackendReferralSummary {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "referrals", "redeem")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(ReferralRedemptionRequest(code: code))
        let data = try await perform(request)
        return try decoder.decode(BackendReferralSummary.self, from: data)
    }

    func requestBillingRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "billing", "payments", String(paymentID), "refund-requests")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            BillingActionRequest(idempotencyKey: idempotencyKey, reason: reason)
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingAction.self, from: data)
    }

    func requestBillingCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint(
                "api", "v1", "billing", "subscriptions", originalTransactionID,
                "cancellation-requests"
            )
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            BillingActionRequest(idempotencyKey: idempotencyKey, reason: reason)
        )
        let data = try await perform(request)
        return try decoder.decode(BackendBillingAction.self, from: data)
    }

    func deleteStudy(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies", String(studyID))
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func fetchRecords(
        registration: RemotePushRegistration,
        limit: Int = 100,
        offset: Int = 0,
        query: String = "",
        language: AppLanguage = .korean
    ) async throws -> BackendRecordsPage {
        var components = URLComponents(
            url: endpoint("api", "v1", "records"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "offset", value: "\(offset)"),
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: LocalizedContentView.localized.rawValue)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendRecordsPage.self, from: data)
    }

    func fetchRecordsForStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        limit: Int = 30,
        offset: Int = 0,
        language: AppLanguage = .korean
    ) async throws -> BackendRecordsPage {
        var components = URLComponents(
            url: endpoint("api", "v1", "records"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "offset", value: "\(offset)"),
            URLQueryItem(name: "studyId", value: "\(studyID)"),
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: LocalizedContentView.localized.rawValue)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendRecordsPage.self, from: data)
    }

    func fetchNotifications(
        registration: RemotePushRegistration,
        limit: Int = 30,
        offset: Int = 0
    ) async throws -> BackendNotificationsPage {
        var components = URLComponents(
            url: endpoint("api", "v1", "notifications"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(max(1, min(limit, 100)))"),
            URLQueryItem(name: "offset", value: "\(max(0, offset))")
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendNotificationsPage.self, from: data)
    }

    func fetchNotificationUnreadCount(registration: RemotePushRegistration) async throws -> Int {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notifications", "unread-count")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(NotificationUnreadCountResponse.self, from: data).unreadCount
    }

    func markNotificationRead(registration: RemotePushRegistration, notificationID: String) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notifications", notificationID, "read")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func markAllNotificationsRead(registration: RemotePushRegistration) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notifications", "read-all")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func deleteNotification(registration: RemotePushRegistration, notificationID: String) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notifications", notificationID)
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func deleteAllNotifications(registration: RemotePushRegistration) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "notifications")
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func fetchSettings(registration: RemotePushRegistration) async throws -> BackendStudySettings {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "settings")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStudySettings.self, from: data)
    }

    func fetchAPIStatus(registration: RemotePushRegistration) async throws -> BackendAPIStatus {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "api")
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
            url: endpoint("api", "v1", "api", "validate")
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
        sort: BackendStatsSort = .level,
        limit: Int = 8,
        offset: Int = 0
    ) async throws -> BackendStats {
        var components = URLComponents(
            url: endpoint("api", "v1", "stats"),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "period", value: period.rawValue),
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

    func fetchStatsActivity(
        registration: RemotePushRegistration,
        startAt: Date? = nil,
        endAt: Date? = nil
    ) async throws -> BackendStatsActivity {
        var components = URLComponents(
            url: endpoint("api", "v1", "stats", "activity"),
            resolvingAgainstBaseURL: false
        )
        var queryItems: [URLQueryItem] = []
        if let startAt {
            queryItems.append(URLQueryItem(name: "startAt", value: Self.dateFormatter.string(from: startAt)))
        }
        if let endAt {
            queryItems.append(URLQueryItem(name: "endAt", value: Self.dateFormatter.string(from: endAt)))
        }
        components?.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStatsActivity.self, from: data)
    }

    func fetchStudyGrowth(
        registration: RemotePushRegistration,
        startAt: Date? = nil,
        endAt: Date? = nil
    ) async throws -> BackendStudyGrowth {
        var components = URLComponents(
            url: endpoint("api", "v1", "stats", "studies"),
            resolvingAgainstBaseURL: false
        )
        var queryItems: [URLQueryItem] = []
        if let startAt {
            queryItems.append(URLQueryItem(name: "startAt", value: Self.dateFormatter.string(from: startAt)))
        }
        if let endAt {
            queryItems.append(URLQueryItem(name: "endAt", value: Self.dateFormatter.string(from: endAt)))
        }
        components?.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(BackendStudyGrowth.self, from: data)
    }

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int = 20,
        offset: Int = 0,
        excludeDeviceID: String? = nil,
        language: AppLanguage = .korean
    ) async throws -> CommunityQuestionsResponse {
        let normalizedQuery = query?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let apiVersion = "v2"
        let path = normalizedQuery.isEmpty ? ["api", apiVersion, "public", "questions"] : ["api", apiVersion, "public", "questions", "search"]
        var components = URLComponents(
            url: endpoint(path),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "limit", value: "\(max(1, min(limit, 100)))"),
            URLQueryItem(name: "offset", value: "\(max(0, offset))"),
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: LocalizedContentView.localized.rawValue)
        ]
        if !normalizedQuery.isEmpty {
            queryItems.append(URLQueryItem(name: "query", value: normalizedQuery))
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

    func fetchLikedPublicQuestions(
        registration: RemotePushRegistration,
        query: String? = nil,
        limit: Int = 20,
        offset: Int = 0,
        language: AppLanguage = .korean,
        view: LocalizedContentView = .localized
    ) async throws -> CommunityQuestionsResponse {
        let normalizedQuery = query?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var components = URLComponents(
            url: endpoint("api", "v1", "public", "questions", "liked"),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "limit", value: "\(max(1, min(limit, 100)))"),
            URLQueryItem(name: "offset", value: "\(max(0, offset))"),
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: view.rawValue)
        ]
        if !normalizedQuery.isEmpty {
            queryItems.append(URLQueryItem(name: "query", value: normalizedQuery))
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

    func fetchPublicQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        language: AppLanguage = .korean,
        view: LocalizedContentView = .localized
    ) async throws -> CommunityQuestion {
        var components = URLComponents(
            url: endpoint("api", "v1", "public", "questions", questionID),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: view.rawValue)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(CommunityQuestion.self, from: data)
    }

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        var request = loginRequest(
            registration: registration,
            url: endpoint("api", "v1", "auth", "google")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(GoogleLoginRequest(idToken: idToken))
        let data = try await perform(request)
        let response = try decoder.decode(CommunityLoginResponse.self, from: data)
        let updatedRegistration = RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: registration.apnsToken,
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
        )
        return CommunityLoginResult(profile: response.profile, registration: updatedRegistration)
    }

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        var request = loginRequest(
            registration: registration,
            url: endpoint("api", "v1", "auth", "apple")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AppleLoginRequest(idToken: idToken))
        let data = try await perform(request)
        let response = try decoder.decode(CommunityLoginResponse.self, from: data)
        let updatedRegistration = RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: registration.apnsToken,
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
        )
        return CommunityLoginResult(profile: response.profile, registration: updatedRegistration)
    }

    func requestEmailVerificationCode(
        registration: RemotePushRegistration,
        email: String
    ) async throws -> EmailVerificationCodeResult {
        var request = loginRequest(
            registration: registration,
            url: endpoint("api", "v1", "auth", "email", "code")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(EmailVerificationCodeRequest(email: email))
        let data = try await perform(request)
        let response = try decoder.decode(EmailVerificationCodeResponse.self, from: data)
        return EmailVerificationCodeResult(email: response.email, expiresInSeconds: response.expiresInSeconds)
    }

    func loginWithEmail(
        registration: RemotePushRegistration,
        email: String,
        password: String,
        verificationCode: String?
    ) async throws -> CommunityLoginResult {
        var request = loginRequest(
            registration: registration,
            url: endpoint("api", "v1", "auth", "email")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            EmailLoginRequest(
                email: email,
                password: password,
                verificationCode: verificationCode?.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        )
        let data = try await perform(request)
        let response = try decoder.decode(CommunityLoginResponse.self, from: data)
        let updatedRegistration = RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: registration.apnsToken,
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
        )
        return CommunityLoginResult(profile: response.profile, registration: updatedRegistration)
    }

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "profile")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func fetchAvatarCatalog(registration: RemotePushRegistration) async throws -> AvatarCatalogResponse {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "profile", "avatar", "catalog")
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(AvatarCatalogResponse.self, from: data)
    }

    func updateProfileAvatar(
        registration: RemotePushRegistration,
        avatarMode: String,
        avatarConfig: [String: String],
        avatarColorSeed: String? = nil
    ) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "profile", "avatar")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            AvatarUpdateRequest(
                avatarMode: avatarMode,
                avatarConfig: avatarConfig,
                avatarColorSeed: avatarColorSeed
            )
        )
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?,
        avatarSymbolName: String? = nil,
        avatarColorSeed: String? = nil,
        avatarMode: String? = nil,
        avatarConfig: [String: String]? = nil,
        allowPublicQuestions: Bool? = nil
    ) async throws -> CommunityUserProfile {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "profile")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            ProfileUpdateRequest(
                displayName: displayName,
                bio: bio,
                avatarSymbolName: avatarSymbolName,
                avatarColorSeed: avatarColorSeed,
                avatarMode: avatarMode,
                avatarConfig: avatarConfig,
                allowPublicQuestions: allowPublicQuestions
            )
        )
        let data = try await perform(request)
        return try decoder.decode(CommunityUserProfile.self, from: data)
    }

    func withdrawMyProfile(registration: RemotePushRegistration) async throws -> RemotePushRegistration {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "profile")
        )
        request.httpMethod = "DELETE"
        let data = try await perform(request)
        let response = try decoder.decode(AccessTokenResponse.self, from: data)
        return RemotePushRegistration(
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret,
            apnsToken: registration.apnsToken,
            accessToken: response.accessToken,
            accessTokenExpiresAt: response.accessTokenExpiresAt
        )
    }

    func reportCommunityQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "public", "questions", questionID, "report")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(ReportQuestionRequest(reason: reason, message: message))
        _ = try await perform(request)
    }

    func setCommunityUserBlocked(
        registration: RemotePushRegistration,
        userID: Int,
        blocked: Bool
    ) async throws -> CommunityUserBlockState {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "community", "users", String(userID), "block")
        )
        request.httpMethod = blocked ? "PUT" : "DELETE"
        let data = try await perform(request)
        return try decoder.decode(CommunityUserBlockState.self, from: data)
    }

    func submitAppFeedback(
        registration: RemotePushRegistration,
        content: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "feedback")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(SubmitFeedbackRequest(content: content))
        _ = try await perform(request)
    }

    func recordNativeAdvertisementView(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "native-ad-selections", selectionID, "view")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func fetchNativeAdvertisementFallback(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws -> CommunityNativeAdvertisement? {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v2", "native-ad-slots", slotID, "fallback")
        )
        request.httpMethod = "POST"
        let data = try await perform(request)
        guard !data.isEmpty else {
            return nil
        }
        return try decoder.decode(CommunityNativeAdvertisement.self, from: data)
    }

    func recordAdMobNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws {
        try await recordAdMobNativeAdvertisementEvent(
            registration: registration,
            slotID: slotID,
            action: "impression"
        )
    }

    func recordAdMobNativeAdvertisementClick(
        registration: RemotePushRegistration,
        slotID: String
    ) async throws {
        try await recordAdMobNativeAdvertisementEvent(
            registration: registration,
            slotID: slotID,
            action: "click"
        )
    }

    private func recordAdMobNativeAdvertisementEvent(
        registration: RemotePushRegistration,
        slotID: String,
        action: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v2", "native-ad-slots", slotID, action)
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(NativeAdvertisementSlotEventRequest(provider: "ADMOB"))
        _ = try await perform(request)
    }

    func recordNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "native-ad-selections", selectionID, "impression")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "native-ad-selections", selectionID, "not-interested")
        )
        request.httpMethod = "POST"
        _ = try await perform(request)
    }

    func setCommunityQuestionLike(
        registration: RemotePushRegistration,
        questionID: String,
        isLiked: Bool
    ) async throws -> CommunityLikeState {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "public", "questions", questionID, "like")
        )
        request.httpMethod = isLiked ? "PUT" : "DELETE"
        let data = try await perform(request)
        return try decoder.decode(CommunityLikeState.self, from: data)
    }

    func fetchCommunityQuestionComments(
        registration: RemotePushRegistration,
        questionID: String,
        limit: Int = 30,
        offset: Int = 0,
        language: AppLanguage = .korean,
        view: LocalizedContentView = .localized
    ) async throws -> CommunityCommentsResponse {
        var components = URLComponents(
            url: endpoint("api", "v1", "public", "questions", questionID, "comments"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "limit", value: "\(max(1, min(limit, 100)))"),
            URLQueryItem(name: "offset", value: "\(max(0, offset))"),
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: view.rawValue)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }

        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(CommunityCommentsResponse.self, from: data)
    }

    func createCommunityQuestionComment(
        registration: RemotePushRegistration,
        questionID: String,
        body: String,
        sourceLanguage: String
    ) async throws -> CommunityQuestionComment {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "public", "questions", questionID, "comments")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            CommunityCommentRequest(body: body, sourceLanguage: sourceLanguage)
        )
        let data = try await perform(request)
        return try decoder.decode(CommunityQuestionComment.self, from: data)
    }

    func deleteCommunityQuestionComment(
        registration: RemotePushRegistration,
        questionID: String,
        commentID: String
    ) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "public", "questions", questionID, "comments", commentID)
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int,
        idempotencyKey: String
    ) async throws -> QuestionGenerationAccepted {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "studies", String(studyID), "questions")
        )
        request.httpMethod = "POST"
        request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key")
        let data = try await perform(request)
        return try decoder.decode(QuestionGenerationAccepted.self, from: data)
    }

    func fetchQuestionGenerationProcess(
        registration: RemotePushRegistration,
        correlationID: String
    ) async throws -> QuestionGenerationProcess {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "question-processes", correlationID)
        )
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(QuestionGenerationProcess.self, from: data)
    }

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "records", recordID, "answer")
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AnswerRequest(answer: answer, sourceLanguage: sourceLanguage))
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func fetchAnswerGradingProcess(
        registration: RemotePushRegistration,
        correlationID: String,
        afterEventID: Int64
    ) async throws -> AnswerGradingProcess {
        var components = URLComponents(
            url: endpoint("api", "v1", "answer-processes", correlationID),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "after", value: String(afterEventID))]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }
        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(AnswerGradingProcess.self, from: data)
    }

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "records", recordID, "answer")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(AnswerRequest(answer: answer, sourceLanguage: sourceLanguage))
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "records", recordID, "skip")
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
            url: endpoint("api", "v1", "records", recordID)
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func updateRecordPublicity(
        registration: RemotePushRegistration,
        recordID: String,
        isPublic: Bool
    ) async throws -> StudyRecord {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "records", recordID, "publicity")
        )
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(RecordPublicityRequest(isPublic: isPublic))
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    func clearRecords(registration: RemotePushRegistration) async throws {
        var request = authenticatedRequest(
            registration: registration,
            url: endpoint("api", "v1", "records")
        )
        request.httpMethod = "DELETE"
        _ = try await perform(request)
    }

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String,
        language: AppLanguage = .korean,
        view: LocalizedContentView = .localized
    ) async throws -> StudyRecord {
        var components = URLComponents(
            url: endpoint("api", "v1", "records", recordID),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "tl", value: language.backendCode),
            URLQueryItem(name: "view", value: view.rawValue)
        ]
        guard let url = components?.url else {
            throw RemotePushBackendError.invalidResponse
        }
        var request = authenticatedRequest(registration: registration, url: url)
        request.httpMethod = "GET"
        let data = try await perform(request)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    private func perform(
        _ request: URLRequest,
        ignoresHTTPStatus: Bool = false
    ) async throws -> Data {
        var request = request
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let startedAt = Date()
        let requestLog = APITrafficLogEntry(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "<unknown>",
            requestHeaders: Self.safeHeaderLog(for: request),
            requestBody: Self.safeBodyLog(data: request.httpBody)
        )
        var didPostTrafficLog = false

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
                didPostTrafficLog = true
                throw RemotePushBackendError.invalidResponse
            }

            let statusCode = httpResponse.statusCode
            let responseBodyText = String(data: data, encoding: .utf8) ?? ""

            if ignoresHTTPStatus {
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
                didPostTrafficLog = true
                return data
            }

            if !(200..<300).contains(statusCode) {
                let backendError = Self.decodeBackendAPIError(from: data)
                let entry = APITrafficLogEntry(
                    id: requestLog.id,
                    method: requestLog.method,
                    url: requestLog.url,
                    statusCode: statusCode,
                    durationMS: durationMS,
                    requestHeaders: requestLog.requestHeaders,
                    requestBody: requestLog.requestBody,
                    responseBody: Self.safeResponseBody(responseBodyText),
                    error: backendError?.message ?? "HTTP \(statusCode)",
                    isError: true
                )
                NotificationCenter.default.post(
                    name: APITrafficNotification.didReceiveLog,
                    object: self,
                    userInfo: [APITrafficNotification.userInfoKey: entry]
                )
                didPostTrafficLog = true
                if statusCode == 401 {
                    NotificationCenter.default.post(
                        name: BackendAuthorizationNotification.didReceiveUnauthorized,
                        object: self
                    )
                }
                throw RemotePushBackendError.httpStatus(statusCode, responseBodyText, backendError)
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
            didPostTrafficLog = true

            return data
        } catch {
            guard !didPostTrafficLog else {
                throw error
            }

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
        for sensitiveKey in ["X-Client-Secret"] {
            if safeHeaders[sensitiveKey] != nil {
                safeHeaders[sensitiveKey] = "[REDACTED]"
            }
        }

        return safeHeaders
            .map { "\($0.key): \($0.value)" }
            .sorted()
            .joined(separator: "\n")
    }

    private static func decodeBackendAPIError(from data: Data) -> BackendAPIError? {
        guard !data.isEmpty else {
            return nil
        }

        return try? JSONDecoder().decode(BackendAPIErrorResponse.self, from: data).error
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
            .replacingOccurrences(
                of: #"("verificationCode"\s*:\s*)\"[^\"]+\""#,
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
        endpoint(components)
    }

    private func endpoint(_ components: [String]) -> URL {
        components.reduce(baseURL) { partialURL, component in
            partialURL.appendingPathComponent(component)
        }
    }

    private func authenticatedRequest(registration: RemotePushRegistration, url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(registration.deviceID, forHTTPHeaderField: "X-Device-Id")
        request.setValue(registration.clientSecret, forHTTPHeaderField: "X-Client-Secret")
        request.setValue(Self.currentAppVersion, forHTTPHeaderField: "X-App-Version")
        request.setValue(Self.currentAppBuild, forHTTPHeaderField: "X-App-Build")
        if registration.hasAccessToken,
           let accessToken = registration.accessToken,
           !accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func loginRequest(registration: RemotePushRegistration, url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(registration.deviceID, forHTTPHeaderField: "X-Device-Id")
        request.setValue(registration.clientSecret, forHTTPHeaderField: "X-Client-Secret")
        request.setValue(Self.currentAppVersion, forHTTPHeaderField: "X-App-Version")
        request.setValue(Self.currentAppBuild, forHTTPHeaderField: "X-App-Build")
        return request
    }

    private static var currentAppVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let trimmed = version?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "0" : trimmed
    }

    private static var currentAppBuild: String {
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        let trimmed = build?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "0" : trimmed
    }

    private static let dateFormatter = ISO8601DateFormatter()

    private struct RegisterDeviceRequest: Encodable {
        var installationId: String
        var apnsToken: String
        var platform: String
        var apnsEnvironment: String
        var language: String
        var timezone: String
        var appVersion: String
        var appBuild: String
    }

    private struct AppUpdateCheckRequest: Encodable {
        var platform: String
        var currentVersion: String
        var currentBuild: String
        var language: String
    }

    private struct AppUpdateEventRequest: Encodable {
        var event: BackendAppUpdateEvent
    }

    private struct PushTokenRequest: Encodable {
        var apnsToken: String
        var apnsEnvironment: String
    }

    private struct RegisterDeviceResponse: Decodable {
        var deviceID: String
        var clientSecret: String
        var accessToken: String
        var accessTokenExpiresAt: Date

        enum CodingKeys: String, CodingKey {
            case deviceID = "deviceId"
            case clientSecret
            case accessToken
            case accessTokenExpiresAt
        }
    }

    private struct AccessTokenResponse: Decodable {
        var accessToken: String
        var accessTokenExpiresAt: Date
    }

    private struct TermsAgreementRequest: Encodable {
        var type: String
        var version: String?
        var contentHash: String?
        var action: String
        var source: String
    }

    private struct NotificationPreferenceRequest: Encodable {
        var type: String
        var enabled: Bool
    }

    private struct ScheduleRequest: Encodable {
        var topic: String
        var difficultyLevel: Int
        var intervalMinutes: Int
        var enabled: Bool
        var openAIAPIKey: String?
        var notificationSound: String?
        var customPrompt: String?
        var appLanguage: String
        var openAIModel: String
        var maxHistoryCount: Int
        var schedules: [ScheduleItemRequest]

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
            case schedules
        }
    }

    private struct ScheduleItemRequest: Encodable {
        var topic: String
        var difficultyLevel: Int
        var customPrompt: String?
        var openAIModel: String

        enum CodingKeys: String, CodingKey {
            case topic
            case difficultyLevel
            case customPrompt
            case openAIModel = "openaiModel"
        }
    }

    private struct CreateStudyRequest: Encodable {
        var topic: String
        var difficultyLevel: Int
        var intervalMinutes: Int
        var enabled: Bool
        var notificationSound: String?
        var customPrompt: String?
        var openAIModel: String
        var maxHistoryCount: Int

        enum CodingKeys: String, CodingKey {
            case topic
            case difficultyLevel
            case intervalMinutes
            case enabled
            case notificationSound
            case customPrompt
            case openAIModel = "openaiModel"
            case maxHistoryCount
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(topic, forKey: .topic)
            try container.encode(difficultyLevel, forKey: .difficultyLevel)
            try container.encode(intervalMinutes, forKey: .intervalMinutes)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(notificationSound, forKey: .notificationSound)
            if let customPrompt {
                try container.encode(customPrompt, forKey: .customPrompt)
            } else {
                try container.encodeNil(forKey: .customPrompt)
            }
            try container.encode(openAIModel, forKey: .openAIModel)
            try container.encode(maxHistoryCount, forKey: .maxHistoryCount)
        }
    }

    private struct CreateStudyTopicRequest: Encodable {
        var topic: String
        var difficultyLevel: Int
        var activeForQuestions: Bool
        var sortOrder: Int
    }

    private struct AnswerRequest: Encodable {
        var answer: String
        var sourceLanguage: String
    }

    private struct StudyTopicActivationRequest: Encodable {
        var active: Bool
    }

    private struct GoogleLoginRequest: Encodable {
        var idToken: String
    }

    private struct AppleLoginRequest: Encodable {
        var idToken: String
    }

    private struct EmailVerificationCodeRequest: Encodable {
        var email: String
    }

    private struct EmailVerificationCodeResponse: Decodable {
        var email: String
        var expiresInSeconds: Int
    }

    private struct EmailLoginRequest: Encodable {
        var email: String
        var password: String
        var verificationCode: String?
    }

    private struct ProfileUpdateRequest: Encodable {
        var displayName: String?
        var bio: String?
        var avatarSymbolName: String?
        var avatarColorSeed: String?
        var avatarMode: String?
        var avatarConfig: [String: String]?
        var allowPublicQuestions: Bool?
    }

    private struct AvatarUpdateRequest: Encodable {
        var avatarMode: String
        var avatarConfig: [String: String]
        var avatarColorSeed: String?
    }

    private struct CommunityLoginResponse: Decodable {
        var profile: CommunityUserProfile
        var accessToken: String
        var accessTokenExpiresAt: Date
    }

    private struct RecordPublicityRequest: Encodable {
        var isPublic: Bool
    }

    private struct ReportQuestionRequest: Encodable {
        var reason: String
        var message: String
    }

    private struct SubmitFeedbackRequest: Encodable {
        var content: String
    }

    private struct NativeAdvertisementSlotEventRequest: Encodable {
        var provider: String
    }

    private struct AppleTransactionSyncRequest: Encodable {
        var signedTransaction: String
        var environment: String
        var invoiceNumber: UUID?
    }

    private struct RevenueCatTransactionConfirmRequest: Encodable {
        let transactionId: String
    }

    private struct BillingCheckoutRequest: Encodable {
        var productId: String
        var idempotencyKey: String
    }

    private struct BillingActionRequest: Encodable {
        var idempotencyKey: String
        var reason: String?
    }

    private struct ReferralRedemptionRequest: Encodable {
        var code: String
    }

    private struct CommunityCommentRequest: Encodable {
        var body: String
        var sourceLanguage: String
    }

    private struct OpenAIModelDescriptor: Decodable {
        var id: String
        var displayName: String
        var supportsTextVerbosity: Bool
    }
}

struct BackendStudyPage: Decodable, Equatable {
    var studies: [BackendStudyRoom]
    var totalCount: Int
    var limit: Int
    var offset: Int
    var serverTime: Date

    init(
        studies: [BackendStudyRoom] = [],
        totalCount: Int = 0,
        limit: Int = 0,
        offset: Int = 0,
        serverTime: Date = Date()
    ) {
        self.studies = studies
        self.totalCount = totalCount
        self.limit = limit
        self.offset = offset
        self.serverTime = serverTime
    }

    enum CodingKeys: String, CodingKey {
        case studies
        case totalCount
        case limit
        case offset
        case serverTime
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        totalCount = try container.decodeIfPresent(Int.self, forKey: .totalCount) ?? 0
        studies = try container.decodeRequiredCollectionWhenPopulated(
            [BackendStudyRoom].self,
            forKey: .studies,
            expectedCount: totalCount
        )
        totalCount = max(totalCount, studies.count)
        limit = try container.decodeIfPresent(Int.self, forKey: .limit) ?? studies.count
        offset = try container.decodeIfPresent(Int.self, forKey: .offset) ?? 0
        serverTime = try container.decodeIfPresent(Date.self, forKey: .serverTime) ?? Date()
    }
}

private struct StudyTopicSuggestionsResponse: Decodable {
    var parentStudyId: Int
    var suggestions: [String]
}

struct BackendStudyRoom: Decodable, Equatable, Identifiable {
    var id: Int
    var topic: String
    var parentStudyId: Int?
    var sortOrder: Int
    var difficultyLevel: Int
    var intervalMinutes: Int
    var enabled: Bool
    var activeForQuestions: Bool
    var notificationSound: String?
    var customPrompt: String
    var openAIModel: String
    var maxHistoryCount: Int
    var nextDueAt: Date?
    var lastSentAt: Date?
    var lastError: String?
    var pendingQuestion: StudyRecord?
    var latestQuestion: StudyRecord?
    var createdAt: Date
    var updatedAt: Date

    init(
        id: Int,
        topic: String,
        parentStudyId: Int? = nil,
        sortOrder: Int = 0,
        difficultyLevel: Int,
        intervalMinutes: Int,
        enabled: Bool,
        activeForQuestions: Bool = true,
        notificationSound: String?,
        customPrompt: String,
        openAIModel: String,
        maxHistoryCount: Int,
        nextDueAt: Date?,
        lastSentAt: Date?,
        lastError: String?,
        pendingQuestion: StudyRecord?,
        latestQuestion: StudyRecord? = nil,
        createdAt: Date,
        updatedAt: Date
    ) {
        self.id = id
        self.topic = topic
        self.parentStudyId = parentStudyId
        self.sortOrder = sortOrder
        self.difficultyLevel = difficultyLevel
        self.intervalMinutes = intervalMinutes
        self.enabled = enabled
        self.activeForQuestions = activeForQuestions
        self.notificationSound = notificationSound
        self.customPrompt = customPrompt
        self.openAIModel = openAIModel
        self.maxHistoryCount = maxHistoryCount
        self.nextDueAt = nextDueAt
        self.lastSentAt = lastSentAt
        self.lastError = lastError
        self.pendingQuestion = pendingQuestion
        self.latestQuestion = latestQuestion
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case topic
        case parentStudyId
        case sortOrder
        case difficultyLevel
        case intervalMinutes
        case enabled
        case activeForQuestions
        case notificationSound
        case customPrompt
        case openAIModel = "openaiModel"
        case maxHistoryCount
        case nextDueAt
        case lastSentAt
        case lastError
        case pendingQuestion
        case latestQuestion
        case createdAt
        case updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        topic = try container.decode(String.self, forKey: .topic)
        parentStudyId = try container.decodeIfPresent(Int.self, forKey: .parentStudyId)
        sortOrder = try container.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        difficultyLevel = try container.decode(Int.self, forKey: .difficultyLevel)
        intervalMinutes = try container.decode(Int.self, forKey: .intervalMinutes)
        enabled = try container.decode(Bool.self, forKey: .enabled)
        activeForQuestions = try container.decodeIfPresent(Bool.self, forKey: .activeForQuestions) ?? true
        notificationSound = try container.decodeIfPresent(String.self, forKey: .notificationSound)
        customPrompt = try container.decode(String.self, forKey: .customPrompt)
        openAIModel = try container.decode(String.self, forKey: .openAIModel)
        maxHistoryCount = try container.decode(Int.self, forKey: .maxHistoryCount)
        nextDueAt = try container.decodeIfPresent(Date.self, forKey: .nextDueAt)
        lastSentAt = try container.decodeIfPresent(Date.self, forKey: .lastSentAt)
        lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
        pendingQuestion = try container.decodeIfPresent(StudyRecord.self, forKey: .pendingQuestion)
        latestQuestion = try container.decodeIfPresent(StudyRecord.self, forKey: .latestQuestion)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
    }
}

struct BackendQuestionQuota: Decodable, Equatable {
    var usedCount: Int
    var monthlyLimit: Int
    var remainingCount: Int
    var resetAt: Date
    var tierCode: String
    var periodStartedAt: Date?
    var reservedCount: Int
    var baseLimit: Int
    var bonusLimit: Int
    var anchorType: String
    var policyVersion: Int

    private enum CodingKeys: String, CodingKey {
        case usedCount, monthlyLimit, remainingCount, resetAt, tierCode, periodStartedAt
        case reservedCount, baseLimit, bonusLimit, anchorType, policyVersion
    }

    init(
        usedCount: Int,
        monthlyLimit: Int,
        remainingCount: Int,
        resetAt: Date,
        tierCode: String,
        periodStartedAt: Date?,
        reservedCount: Int,
        baseLimit: Int,
        bonusLimit: Int,
        anchorType: String,
        policyVersion: Int
    ) {
        self.usedCount = usedCount
        self.monthlyLimit = monthlyLimit
        self.remainingCount = remainingCount
        self.resetAt = resetAt
        self.tierCode = tierCode
        self.periodStartedAt = periodStartedAt
        self.reservedCount = reservedCount
        self.baseLimit = baseLimit
        self.bonusLimit = bonusLimit
        self.anchorType = anchorType
        self.policyVersion = policyVersion
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        usedCount = try values.decode(Int.self, forKey: .usedCount)
        monthlyLimit = try values.decode(Int.self, forKey: .monthlyLimit)
        remainingCount = try values.decode(Int.self, forKey: .remainingCount)
        resetAt = try values.decode(Date.self, forKey: .resetAt)
        tierCode = try values.decodeIfPresent(String.self, forKey: .tierCode) ?? "TIER1"
        periodStartedAt = try values.decodeIfPresent(Date.self, forKey: .periodStartedAt)
        reservedCount = try values.decodeIfPresent(Int.self, forKey: .reservedCount) ?? 0
        baseLimit = try values.decodeIfPresent(Int.self, forKey: .baseLimit) ?? monthlyLimit
        bonusLimit = try values.decodeIfPresent(Int.self, forKey: .bonusLimit) ?? 0
        anchorType = try values.decodeIfPresent(String.self, forKey: .anchorType) ?? "ACCOUNT_CREATED"
        policyVersion = try values.decodeIfPresent(Int.self, forKey: .policyVersion) ?? 1
    }
}

struct BackendBillingStatus: Decodable, Equatable {
    var tierCode: String
    var adFree: Bool
    var source: String
    var accessStatus: String
    var renewalStatus: String
    var productId: String?
    var startedAt: Date?
    var expiresAt: Date?
    var willRenew: Bool
    var pendingChange: String?
    var planTransition: BackendBillingPlanTransition?
    var synchronizedAt: Date
    var quota: BackendBillingQuotaStatus

    var isEntitlementActive: Bool {
        accessStatus == "ACTIVE" || accessStatus == "GRACE_PERIOD"
    }

    enum CodingKeys: String, CodingKey {
        case tierCode
        case adFree
        case source
        case accessStatus
        case renewalStatus
        case productId
        case startedAt
        case expiresAt
        case willRenew
        case pendingChange
        case planTransition
        case synchronizedAt
        case quota
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        tierCode = try values.decode(String.self, forKey: .tierCode)
        // Missing entitlement data cannot prove that an authenticated account is ad eligible.
        adFree = try values.decodeIfPresent(Bool.self, forKey: .adFree) ?? true
        source = try values.decode(String.self, forKey: .source)
        accessStatus = try values.decode(String.self, forKey: .accessStatus)
        renewalStatus = try values.decode(String.self, forKey: .renewalStatus)
        productId = try values.decodeIfPresent(String.self, forKey: .productId)
        startedAt = try values.decodeIfPresent(Date.self, forKey: .startedAt)
        expiresAt = try values.decodeIfPresent(Date.self, forKey: .expiresAt)
        willRenew = try values.decode(Bool.self, forKey: .willRenew)
        pendingChange = try values.decodeIfPresent(String.self, forKey: .pendingChange)
        planTransition = try values.decodeIfPresent(BackendBillingPlanTransition.self, forKey: .planTransition)
        synchronizedAt = try values.decode(Date.self, forKey: .synchronizedAt)
        quota = try values.decode(BackendBillingQuotaStatus.self, forKey: .quota)
    }
}

struct BackendReferralSummary: Decodable, Equatable, Sendable {
    var code: String
    var successfulReferralCount: Int
    var rewardMonthsEarned: Int
    var rewardStartsAt: Date?
    var rewardEndsAt: Date?
    var hasRedeemedReferral: Bool
}

struct BackendBillingPlanTransition: Decodable, Equatable {
    var currentTierCode: String
    var currentProductId: String?
    var currentPlanEndsAt: Date
    var nextTierCode: String
    var nextProductId: String?
    var nextPlanStartsAt: Date
}

struct BackendBillingQuotaStatus: Decodable, Equatable {
    var periodStartedAt: Date
    var resetAt: Date
    var anchorType: String
    var baseLimit: Int
    var bonusLimit: Int
    var usedCount: Int
    var reservedCount: Int
    var remainingCount: Int
    var policyVersion: Int
}

struct BackendBillingCatalog: Decodable, Equatable {
    var appAccountToken: UUID
    var products: [BackendBillingTierProduct]
}

struct BackendBillingTierProduct: Decodable, Equatable, Identifiable {
    var tierCode: String
    var adFree: Bool
    var description: String
    var monthlyQuestionLimit: Int
    var productId: String
    var productType: String
    var billingPeriod: String?
    var sortOrder: Int

    var id: String { productId }

    init(
        tierCode: String,
        adFree: Bool? = nil,
        description: String,
        monthlyQuestionLimit: Int,
        productId: String,
        productType: String,
        billingPeriod: String?,
        sortOrder: Int
    ) {
        self.tierCode = tierCode
        self.adFree = adFree ?? (tierCode.caseInsensitiveCompare("TIER1") != .orderedSame)
        self.description = description
        self.monthlyQuestionLimit = monthlyQuestionLimit
        self.productId = productId
        self.productType = productType
        self.billingPeriod = billingPeriod
        self.sortOrder = sortOrder
    }

    enum CodingKeys: String, CodingKey {
        case tierCode
        case adFree
        case description
        case monthlyQuestionLimit
        case productId
        case productType
        case billingPeriod
        case sortOrder
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let tierCode = try values.decode(String.self, forKey: .tierCode)
        self.init(
            tierCode: tierCode,
            adFree: try values.decodeIfPresent(Bool.self, forKey: .adFree),
            description: try values.decode(String.self, forKey: .description),
            monthlyQuestionLimit: try values.decode(Int.self, forKey: .monthlyQuestionLimit),
            productId: try values.decode(String.self, forKey: .productId),
            productType: try values.decode(String.self, forKey: .productType),
            billingPeriod: try values.decodeIfPresent(String.self, forKey: .billingPeriod),
            sortOrder: try values.decode(Int.self, forKey: .sortOrder)
        )
    }
}

struct BackendBillingInvoice: Decodable, Equatable, Identifiable {
    var id: Int64
    var invoiceNumber: UUID
    var type: String? = nil
    var originalInvoiceId: Int64? = nil
    var tierCode: String
    var productId: String
    var status: String
    var version: Int64
    var paymentId: Int64?
    var transactionId: String?
    var originalTransactionId: String?
    var paymentStatus: String?
    var priceMilliunits: Int64?
    var currency: String?
    var purchaseAt: Date?
    var expiresAt: Date?
    var createdAt: Date
    var updatedAt: Date
    var fulfilledAt: Date? = nil
    var latestEventType: String? = nil

    var isApplied: Bool {
        (type ?? "NORMAL") == "NORMAL"
            && status == "COMPLETED"
            && paymentStatus == "SETTLED"
            && fulfilledAt != nil
            && transactionId != nil
    }

    var isRefundable: Bool {
        (type ?? "NORMAL") == "NORMAL"
            && status == "COMPLETED"
            && paymentStatus.map { ["SETTLED", "REFUND_DECLINED", "REFUND_REVERSED"].contains($0) } == true
    }

    var isSubscription: Bool {
        originalTransactionId != nil
    }

    var isCancellable: Bool {
        (type ?? "NORMAL") == "NORMAL" && isSubscription && status == "COMPLETED"
    }

    var requiresCustomerCenterResolution: Bool {
        (type ?? "NORMAL") == "NORMAL"
            && status == "FAILED"
            && latestEventType == "CANCELLED"
    }
}

struct BackendBillingInvoicePage: Decodable, Equatable {
    var limit: Int
    var offset: Int
    var invoices: [BackendBillingInvoice]
}

private struct BackendBillingInvoiceDetail: Decodable {
    var invoice: BackendBillingInvoice
}

struct BackendBillingAction: Decodable, Equatable, Identifiable {
    var actionId: UUID
    var actionType: String
    var status: String
    var invoiceId: Int64
    var paymentId: Int64
    var providerTransactionId: String
    var providerOriginalTransactionId: String
    var reason: String?
    var requestedAt: Date
    var completedAt: Date?
    var clientAction: String

    var id: UUID { actionId }
}

enum QuestionGenerationStatus: String, Codable, Equatable {
    case queued = "QUEUED"
    case generating = "GENERATING"
    case translating = "TRANSLATING"
    case completed = "COMPLETED"
    case failed = "FAILED"
}

enum QuestionGenerationStep: String, Codable, Equatable {
    case queued = "QUEUED"
    case generating = "GENERATING"
    case translating = "TRANSLATING"
    case completed = "COMPLETED"
}

struct QuestionGenerationAccepted: Codable, Equatable {
    var correlationID: String
    var studyID: String
    var topicID: String
    var status: QuestionGenerationStatus
    var pollAfterMilliseconds: Int
    var submittedAt: Date

    enum CodingKeys: String, CodingKey {
        case correlationID = "correlationId"
        case studyID = "studyId"
        case topicID = "topicId"
        case status
        case pollAfterMilliseconds = "pollAfterMs"
        case submittedAt
    }
}

struct QuestionGenerationProcessError: Codable, Equatable {
    var code: String
    var message: String
    var retryable: Bool
}

struct QuestionGenerationProcess: Codable, Equatable {
    var correlationID: String
    var status: QuestionGenerationStatus
    var currentStep: QuestionGenerationStep
    var terminal: Bool
    var pollAfterMilliseconds: Int?
    var questionID: String?
    var question: StudyRecord?
    var failedStep: QuestionGenerationStep?
    var error: QuestionGenerationProcessError?
    var updatedAt: Date
    var completedAt: Date?

    enum CodingKeys: String, CodingKey {
        case correlationID = "correlationId"
        case status
        case currentStep
        case terminal
        case pollAfterMilliseconds = "pollAfterMs"
        case questionID = "questionId"
        case question
        case failedStep
        case error
        case updatedAt
        case completedAt
    }
}

struct PendingQuestionGenerationProcess: Codable, Equatable {
    var idempotencyKey: String
    var correlationID: String?
    var studyID: Int
    var studyCategoryID: String?
    var submittedAt: Date
}

struct BackendRecordsPage: Decodable, Equatable {
    var records: [StudyRecord]
    var totalCount: Int
    var limit: Int
    var offset: Int

    init(records: [StudyRecord] = [], totalCount: Int = 0, limit: Int = 0, offset: Int = 0) {
        self.records = records
        self.totalCount = totalCount
        self.limit = limit
        self.offset = offset
    }

    enum CodingKeys: String, CodingKey {
        case records
        case totalCount
        case limit
        case offset
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        totalCount = try container.decodeIfPresent(Int.self, forKey: .totalCount) ?? 0
        records = try container.decodeRequiredCollectionWhenPopulated(
            [StudyRecord].self,
            forKey: .records,
            expectedCount: totalCount
        )
        totalCount = max(totalCount, records.count)
        limit = try container.decodeIfPresent(Int.self, forKey: .limit) ?? records.count
        offset = try container.decodeIfPresent(Int.self, forKey: .offset) ?? 0
    }
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

    init(openAIKeyConfigured: Bool, isValid: Bool, openAIModel: String) {
        self.openAIKeyConfigured = openAIKeyConfigured
        self.isValid = isValid
        self.openAIModel = openAIModel
    }

    enum CodingKeys: String, CodingKey {
        case openAIKeyConfigured = "openaiKeyConfigured"
        case isValid
        case valid
        case openAIModel = "openaiModel"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        openAIKeyConfigured = try container.decode(Bool.self, forKey: .openAIKeyConfigured)
        isValid = try container.decodeIfPresent(Bool.self, forKey: .isValid)
            ?? container.decode(Bool.self, forKey: .valid)
        openAIModel = try container.decode(String.self, forKey: .openAIModel)
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

struct BackendStatsActivity: Decodable, Equatable {
    var days: [BackendStatsActivityDay]
    var streakDays: Int
    var monthAnswerCount: Int
    var generatedAt: Date
}

struct BackendStatsActivityDay: Decodable, Equatable, Identifiable {
    var date: Date
    var answerCount: Int
    var topicCount: Int
    var topics: [String]
    var bestLevel: Double?

    var id: Date { date }
}

struct BackendStudyGrowth: Decodable, Equatable {
    var roots: [BackendStudyGrowthRoot]
    var nodes: [BackendStudyGrowthNode]
    var startAt: Date
    var endAt: Date
    var generatedAt: Date
}

struct BackendStudyGrowthRoot: Decodable, Equatable, Identifiable {
    var studyId: Int
    var topic: String
    var activeForQuestions: Bool
    var currentLevel: Double?
    var previousLevel: Double?
    var growth: Double?
    var answerCount: Int
    var measuredTopicCount: Int
    var totalTopicCount: Int
    var trend: [Double]
    var trendPoints: [BackendStudyGrowthTrendPoint]? = nil
    var profile: BackendStudyGrowthProfile?

    var id: Int { studyId }
}

struct BackendStudyGrowthProfile: Decodable, Equatable {
    var achievement: Double?
    var challenge: Double?
    var completion: Double?
    var breadth: Double?
    var depth: Double?
}

struct BackendStudyGrowthNode: Decodable, Equatable, Identifiable {
    var studyId: Int
    var parentStudyId: Int?
    var rootStudyId: Int
    var topic: String
    var sortOrder: Int
    var depth: Int
    var childCount: Int
    var activeForQuestions: Bool
    var currentLevel: Double?
    var previousLevel: Double?
    var growth: Double?
    var answerCount: Int
    var measuredTopicCount: Int
    var totalTopicCount: Int
    var latestAt: Date?
    var trend: [Double]
    var trendPoints: [BackendStudyGrowthTrendPoint]? = nil

    var id: Int { studyId }
}

struct BackendStudyGrowthTrendPoint: Decodable, Equatable, Identifiable {
    var measuredAt: Date
    var level: Double

    var id: Date { measuredAt }
}

struct CommunityQuestion: Decodable, Equatable, Identifiable {
    var id: String
    var question: String
    var answer: String?
    var gradingResult: GradingResult?
    var topic: String
    var difficultyLevel: Int
    var status: String
    var source: String
    var createdAt: Date
    var answeredAt: Date?
    var author: CommunityUserProfile?
    var likeCount: Int
    var commentCount: Int
    var viewCount: Int
    var isLikedByMe: Bool
    var localization: RecordLocalizationMetadata?

    enum CodingKeys: String, CodingKey {
        case id
        case question
        case answer
        case gradingResult
        case topic
        case difficultyLevel
        case status
        case source
        case createdAt
        case answeredAt
        case author
        case likeCount
        case commentCount
        case viewCount
        case isLikedByMe
        case likedByMe
        case localization
    }

    init(
        id: String,
        question: String,
        answer: String?,
        gradingResult: GradingResult?,
        topic: String,
        difficultyLevel: Int,
        status: String,
        source: String,
        createdAt: Date,
        answeredAt: Date?,
        author: CommunityUserProfile?,
        likeCount: Int = 0,
        commentCount: Int = 0,
        viewCount: Int = 0,
        isLikedByMe: Bool = false,
        localization: RecordLocalizationMetadata? = nil
    ) {
        self.id = id
        self.question = question
        self.answer = answer
        self.gradingResult = gradingResult
        self.topic = topic
        self.difficultyLevel = difficultyLevel
        self.status = status
        self.source = source
        self.createdAt = createdAt
        self.answeredAt = answeredAt
        self.author = author
        self.likeCount = likeCount
        self.commentCount = commentCount
        self.viewCount = viewCount
        self.isLikedByMe = isLikedByMe
        self.localization = localization
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        question = try container.decode(String.self, forKey: .question)
        answer = try container.decodeIfPresent(String.self, forKey: .answer)
        gradingResult = try container.decodeIfPresent(GradingResult.self, forKey: .gradingResult)
        topic = try container.decode(String.self, forKey: .topic)
        difficultyLevel = try container.decode(Int.self, forKey: .difficultyLevel)
        status = try container.decode(String.self, forKey: .status)
        source = try container.decode(String.self, forKey: .source)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        answeredAt = try container.decodeIfPresent(Date.self, forKey: .answeredAt)
        author = try container.decodeIfPresent(CommunityUserProfile.self, forKey: .author)
        likeCount = try container.decodeIfPresent(Int.self, forKey: .likeCount) ?? 0
        commentCount = try container.decodeIfPresent(Int.self, forKey: .commentCount) ?? 0
        viewCount = try container.decodeIfPresent(Int.self, forKey: .viewCount) ?? 0
        isLikedByMe = try container.decodeIfPresent(Bool.self, forKey: .isLikedByMe)
            ?? container.decodeIfPresent(Bool.self, forKey: .likedByMe)
            ?? false
        localization = try container.decodeIfPresent(RecordLocalizationMetadata.self, forKey: .localization)
    }
}

struct CommunityLikeState: Decodable, Equatable {
    var questionID: String
    var likeCount: Int
    var isLikedByMe: Bool

    init(questionID: String, likeCount: Int, isLikedByMe: Bool) {
        self.questionID = questionID
        self.likeCount = likeCount
        self.isLikedByMe = isLikedByMe
    }

    enum CodingKeys: String, CodingKey {
        case questionID = "questionId"
        case likeCount
        case isLikedByMe
        case likedByMe
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        questionID = try container.decode(String.self, forKey: .questionID)
        likeCount = try container.decode(Int.self, forKey: .likeCount)
        isLikedByMe = try container.decodeIfPresent(Bool.self, forKey: .isLikedByMe)
            ?? container.decode(Bool.self, forKey: .likedByMe)
    }
}

struct CommunityUserBlockState: Decodable, Equatable {
    var userID: Int
    var blocked: Bool

    init(userID: Int, blocked: Bool) {
        self.userID = userID
        self.blocked = blocked
    }

    enum CodingKeys: String, CodingKey {
        case userID = "userId"
        case blocked
    }
}

struct CommunityQuestionComment: Decodable, Equatable, Identifiable {
    var id: String
    var questionID: String
    var body: String
    var createdAt: Date
    var author: CommunityUserProfile
    var localization: ContentLocalizationMetadata?

    enum CodingKeys: String, CodingKey {
        case id
        case questionID = "questionId"
        case body
        case createdAt
        case author
        case localization
    }
}

struct CommunityUserProfile: Codable, Equatable, Identifiable {
    var id: Int
    var displayName: String
    var status: String
    var provider: String
    var email: String
    var bio: String
    var avatarURL: URL?
    var avatarSymbolName: String
    var avatarColorSeed: String
    var avatarMode: String
    var avatarConfig: [String: String]?
    var allowPublicQuestions: Bool = true
    var pageAccess: CommunityPageAccess = .restricted

    enum CodingKeys: String, CodingKey {
        case id
        case displayName
        case status
        case provider
        case email
        case bio
        case avatarURL = "avatarUrl"
        case avatarSymbolName
        case avatarColorSeed
        case avatarMode
        case avatarConfig
        case allowPublicQuestions
        case pageAccess
    }

    init(
        id: Int,
        displayName: String,
        status: String = "ANONYMOUS",
        provider: String = "ANONYMOUS",
        email: String = "",
        bio: String,
        avatarURL: URL?,
        avatarSymbolName: String = "pixel-fox",
        avatarColorSeed: String = "avatar-color-mint",
        avatarMode: String = "LEGACY",
        avatarConfig: [String: String]? = nil,
        allowPublicQuestions: Bool = true,
        pageAccess: CommunityPageAccess = .restricted
    ) {
        self.id = id
        self.displayName = displayName
        self.status = status
        self.provider = provider
        self.email = email
        self.bio = bio
        self.avatarURL = avatarURL
        self.avatarSymbolName = avatarSymbolName
        self.avatarColorSeed = avatarColorSeed
        self.avatarMode = avatarMode
        self.avatarConfig = avatarConfig
        self.allowPublicQuestions = allowPublicQuestions
        self.pageAccess = pageAccess
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        displayName = try container.decode(String.self, forKey: .displayName)
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? "ACTIVE"
        provider = try container.decodeIfPresent(String.self, forKey: .provider) ?? "ANONYMOUS"
        email = try container.decodeIfPresent(String.self, forKey: .email) ?? ""
        bio = try container.decodeIfPresent(String.self, forKey: .bio) ?? ""
        avatarURL = try container.decodeIfPresent(URL.self, forKey: .avatarURL)
        avatarSymbolName = try container.decodeIfPresent(String.self, forKey: .avatarSymbolName) ?? "pixel-fox"
        avatarColorSeed = try container.decodeIfPresent(String.self, forKey: .avatarColorSeed) ?? "avatar-color-mint"
        avatarMode = try container.decodeIfPresent(String.self, forKey: .avatarMode) ?? "LEGACY"
        avatarConfig = try container.decodeIfPresent([String: String].self, forKey: .avatarConfig)
        pageAccess = try container.decodeIfPresent(CommunityPageAccess.self, forKey: .pageAccess) ?? .restricted
        allowPublicQuestions = try container.decodeIfPresent(Bool.self, forKey: .allowPublicQuestions)
            ?? pageAccess.publicQuestions
    }
}

struct CommunityPageAccess: Codable, Equatable {
    var publicQuestions: Bool
    var statistics: Bool
    var studyDetail: Bool
    var records: Bool

    static let restricted = CommunityPageAccess(
        publicQuestions: true,
        statistics: false,
        studyDetail: false,
        records: false
    )

    enum CodingKeys: String, CodingKey {
        case publicQuestions
        case statistics
        case studyDetail
        case records
    }
}

struct CommunityQuestionsResponse: Decodable, Equatable {
    var questions: [CommunityQuestion]
    var items: [CommunityFeedItem]
    var totalCount: Int
    var limit: Int
    var offset: Int

    init(
        questions: [CommunityQuestion] = [],
        items: [CommunityFeedItem]? = nil,
        totalCount: Int = 0,
        limit: Int = 0,
        offset: Int = 0
    ) {
        self.questions = questions
        self.items = items ?? questions.map(CommunityFeedItem.publicQuestion)
        self.totalCount = totalCount
        self.limit = limit
        self.offset = offset
    }

    enum CodingKeys: String, CodingKey {
        case questions
        case items
        case totalCount
        case limit
        case offset
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        totalCount = try container.decodeIfPresent(Int.self, forKey: .totalCount) ?? 0
        questions = try container.decodeRequiredCollectionWhenPopulated(
            [CommunityQuestion].self,
            forKey: .questions,
            expectedCount: totalCount
        )
        items = try container.decodeIfPresent([CommunityFeedItem].self, forKey: .items)
            ?? questions.map(CommunityFeedItem.publicQuestion)
        totalCount = max(totalCount, questions.count)
        limit = try container.decodeIfPresent(Int.self, forKey: .limit) ?? questions.count
        offset = try container.decodeIfPresent(Int.self, forKey: .offset) ?? 0
    }
}

enum CommunityFeedItem: Decodable, Equatable, Identifiable {
    case publicQuestion(CommunityQuestion)
    case advertisement(CommunityNativeAdvertisement)
    case nativeAdSlot(CommunityNativeAdvertisementSlot)

    var id: String {
        switch self {
        case .publicQuestion(let question):
            return "question-\(question.id)"
        case .advertisement(let advertisement):
            return "advertisement-\(advertisement.selectionID)"
        case .nativeAdSlot(let slot):
            return "native-ad-slot-\(slot.slotID)"
        }
    }

    private enum CodingKeys: String, CodingKey {
        case type
        case question
        case advertisement
        case nativeAdSlot
    }

    private enum ItemType: String, Decodable {
        case publicQuestion = "PUBLIC_QUESTION"
        case advertisement = "ADVERTISEMENT"
        case nativeAdSlot = "NATIVE_AD_SLOT"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(ItemType.self, forKey: .type) {
        case .publicQuestion:
            self = .publicQuestion(try container.decode(CommunityQuestion.self, forKey: .question))
        case .advertisement:
            self = .advertisement(
                try container.decode(CommunityNativeAdvertisement.self, forKey: .advertisement)
            )
        case .nativeAdSlot:
            self = .nativeAdSlot(
                try container.decode(CommunityNativeAdvertisementSlot.self, forKey: .nativeAdSlot)
            )
        }
    }
}

struct CommunityNativeAdvertisementSlot: Decodable, Equatable, Identifiable {
    var slotID: String
    var placement: String

    var id: String { slotID }

    enum CodingKeys: String, CodingKey {
        case slotID = "slotId"
        case placement
    }
}

struct CommunityNativeAdvertisement: Decodable, Equatable, Identifiable {
    var selectionID: String
    var campaignID: String
    var providerName: String? = nil
    var disclosureLabel: String
    var title: String
    var body: String?
    var imageURL: String? = nil
    var affiliateDisclosure: String? = nil
    var deepLink: String

    var id: String { selectionID }

    enum CodingKeys: String, CodingKey {
        case selectionID = "selectionId"
        case campaignID = "campaignId"
        case providerName
        case disclosureLabel
        case title
        case body
        case imageURL = "imageUrl"
        case affiliateDisclosure
        case deepLink
    }
}

enum NativeAdvertisementReportReason: String, Equatable {
    case inappropriate = "INAPPROPRIATE"
    case ageInappropriate = "AGE_INAPPROPRIATE"
}

enum NativeAdvertisementReportPayload {
    static func content(
        reason: NativeAdvertisementReportReason,
        advertisement: CommunityNativeAdvertisement,
        slotID: String?
    ) -> String {
        [
            "[AD_REPORT_V1]",
            "reason=\(reason.rawValue)",
            "selectionId=\(safeField(advertisement.selectionID))",
            "campaignId=\(safeField(advertisement.campaignID))",
            "slotId=\(safeField(slotID ?? "LEGACY_V1"))",
            "provider=\(safeField(advertisement.providerName ?? "BUDDYSTUDY"))",
        ].joined(separator: "\n")
    }

    private static func safeField(_ value: String) -> String {
        let normalized = value
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(normalized.prefix(180))
    }
}

@MainActor
final class NativeAdvertisementFallbackRequestCoordinator {
    private var tasksBySlotID: [
        String: Task<CommunityNativeAdvertisement?, Never>
    ] = [:]

    func resolve(
        slotID: String,
        loader: @escaping @MainActor () async -> CommunityNativeAdvertisement?
    ) async -> CommunityNativeAdvertisement? {
        if let task = tasksBySlotID[slotID] {
            return await task.value
        }

        let task = Task { @MainActor in
            await loader()
        }
        tasksBySlotID[slotID] = task
        return await task.value
    }
}

struct CommunityCommentsResponse: Decodable, Equatable {
    var comments: [CommunityQuestionComment]
    var totalCount: Int
    var limit: Int
    var offset: Int

    init(comments: [CommunityQuestionComment] = [], totalCount: Int = 0, limit: Int = 0, offset: Int = 0) {
        self.comments = comments
        self.totalCount = totalCount
        self.limit = limit
        self.offset = offset
    }

    enum CodingKeys: String, CodingKey {
        case comments
        case totalCount
        case limit
        case offset
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        totalCount = try container.decodeIfPresent(Int.self, forKey: .totalCount) ?? 0
        comments = try container.decodeRequiredCollectionWhenPopulated(
            [CommunityQuestionComment].self,
            forKey: .comments,
            expectedCount: totalCount
        )
        totalCount = max(totalCount, comments.count)
        limit = try container.decodeIfPresent(Int.self, forKey: .limit) ?? comments.count
        offset = try container.decodeIfPresent(Int.self, forKey: .offset) ?? 0
    }
}

struct BackendAppNotification: Decodable, Equatable, Identifiable {
    var id: String
    var type: String
    var title: String
    var body: String
    var threadType: String?
    var threadId: String?
    var deepLink: String?
    var isRead: Bool
    var createdAt: Date
    var readAt: Date?

    init(
        id: String,
        type: String,
        title: String,
        body: String,
        threadType: String? = nil,
        threadId: String? = nil,
        deepLink: String? = nil,
        isRead: Bool,
        createdAt: Date,
        readAt: Date? = nil
    ) {
        self.id = id
        self.type = type
        self.title = title
        self.body = body
        self.threadType = threadType
        self.threadId = threadId
        self.deepLink = deepLink
        self.isRead = isRead
        self.createdAt = createdAt
        self.readAt = readAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case title
        case body
        case threadType
        case threadId
        case deepLink
        case isRead
        case read
        case createdAt
        case readAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        title = try container.decode(String.self, forKey: .title)
        body = try container.decode(String.self, forKey: .body)
        threadType = try container.decodeIfPresent(String.self, forKey: .threadType)
        threadId = try container.decodeIfPresent(String.self, forKey: .threadId)
        deepLink = try container.decodeIfPresent(String.self, forKey: .deepLink)
        isRead = try container.decodeIfPresent(Bool.self, forKey: .isRead)
            ?? container.decode(Bool.self, forKey: .read)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        readAt = try container.decodeIfPresent(Date.self, forKey: .readAt)
    }
}

struct BackendNotificationsPage: Decodable, Equatable {
    var notifications: [BackendAppNotification]
    var unreadCount: Int
    var totalCount: Int
    var limit: Int
    var offset: Int

    init(
        notifications: [BackendAppNotification] = [],
        unreadCount: Int = 0,
        totalCount: Int = 0,
        limit: Int = 0,
        offset: Int = 0
    ) {
        self.notifications = notifications
        self.unreadCount = unreadCount
        self.totalCount = totalCount
        self.limit = limit
        self.offset = offset
    }

    enum CodingKeys: String, CodingKey {
        case notifications
        case unreadCount
        case totalCount
        case limit
        case offset
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        unreadCount = try container.decodeIfPresent(Int.self, forKey: .unreadCount) ?? 0
        totalCount = try container.decodeIfPresent(Int.self, forKey: .totalCount) ?? 0
        notifications = try container.decodeRequiredCollectionWhenPopulated(
            [BackendAppNotification].self,
            forKey: .notifications,
            expectedCount: max(unreadCount, totalCount)
        )
        totalCount = max(totalCount, notifications.count)
        limit = try container.decodeIfPresent(Int.self, forKey: .limit) ?? notifications.count
        offset = try container.decodeIfPresent(Int.self, forKey: .offset) ?? 0
    }
}

private extension KeyedDecodingContainer {
    func decodeRequiredCollectionWhenPopulated<Element: Decodable>(
        _ type: [Element].Type,
        forKey key: Key,
        expectedCount: Int
    ) throws -> [Element] {
        if contains(key) {
            return try decode(type, forKey: key)
        }
        guard expectedCount > 0 else {
            return []
        }
        throw DecodingError.keyNotFound(
            key,
            DecodingError.Context(
                codingPath: codingPath,
                debugDescription: "Response reports \(expectedCount) items but omits the \(key.stringValue) collection."
            )
        )
    }
}

private struct NotificationUnreadCountResponse: Decodable {
    var unreadCount: Int
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

    init(
        topic: String,
        difficultyLevel: Int,
        intervalMinutes: Int,
        enabled: Bool,
        notificationSound: String?,
        customPrompt: String,
        appLanguage: String,
        openAIModel: String,
        maxHistoryCount: Int,
        isQuestionPublic: Bool,
        openAIKeyConfigured: Bool,
        nextDueAt: Date? = nil,
        lastError: String? = nil
    ) {
        self.topic = topic
        self.difficultyLevel = difficultyLevel
        self.intervalMinutes = intervalMinutes
        self.enabled = enabled
        self.notificationSound = notificationSound
        self.customPrompt = customPrompt
        self.appLanguage = appLanguage
        self.openAIModel = openAIModel
        self.maxHistoryCount = maxHistoryCount
        self.isQuestionPublic = isQuestionPublic
        self.openAIKeyConfigured = openAIKeyConfigured
        self.nextDueAt = nextDueAt
        self.lastError = lastError
    }

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
        intervalMinutes = try container.decode(Int.self, forKey: .intervalMinutes)
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        notificationSound = try container.decodeIfPresent(String.self, forKey: .notificationSound)
        customPrompt = try container.decodeIfPresent(String.self, forKey: .customPrompt) ?? ""
        appLanguage = try container.decodeIfPresent(String.self, forKey: .appLanguage) ?? "ko"
        openAIModel = try container.decodeIfPresent(String.self, forKey: .openAIModel) ?? StudySettings.defaultOpenAIModel
        maxHistoryCount = try container.decodeIfPresent(Int.self, forKey: .maxHistoryCount) ?? 100
        isQuestionPublic = try container.decodeIfPresent(Bool.self, forKey: .isQuestionPublic) ?? true
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
            isQuestionPublic: isQuestionPublic,
            studyCategories: fallback.studyCategories,
            selectedStudyCategoryID: fallback.selectedStudyCategoryID
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
            backendDateOnlyFormatter.date(from: value) ??
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

    nonisolated static var backendDateOnlyFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }
}

struct BackendAPIErrorResponse: Decodable {
    var error: BackendAPIError
}

struct BackendAccessState: Codable, Equatable {
    var user: BackendAccessUser
    var pageAccess: BackendPageAccess

    static let signedOut = BackendAccessState(
        user: BackendAccessUser(id: 0, status: "ANONYMOUS", displayName: "Buddy", createdAt: nil),
        pageAccess: .signedOut
    )
}

struct BackendAccessUser: Codable, Equatable {
    var id: Int64
    var status: String
    var displayName: String
    var createdAt: Date?
}

struct BackendPageAccess: Codable, Equatable {
    var home: Bool
    var publicQuestions: Bool
    var myStudies: Bool
    var studyRoom: Bool
    var records: Bool
    var stats: Bool
    var profile: Bool
    var developer: Bool
    var admin: Bool

    static let signedOut = BackendPageAccess(
        home: true,
        publicQuestions: true,
        myStudies: false,
        studyRoom: false,
        records: false,
        stats: false,
        profile: false,
        developer: false,
        admin: false
    )
}

struct BackendAPIError: Decodable, Equatable {
    var code: String
    var numericCode: Int?
    var description: String?
    var messageKey: String?
    var debugDescription: String?
    var message: String
    var requestID: String?
    var status: Int?
    var requiredPermissions: [String]?
    var requiredTerms: [BackendTerms]?
    var requiredActions: [String]?
    var metadata: BackendAPIErrorMetadata?

    private enum CodingKeys: String, CodingKey {
        case code
        case errorCode
        case description
        case messageKey
        case debugDescription
        case message
        case requestID = "requestId"
        case status
        case requiredPermissions
        case requiredTerms
        case requiredActions
        case metadata
    }

    init(
        code: String,
        numericCode: Int? = nil,
        description: String? = nil,
        messageKey: String? = nil,
        debugDescription: String? = nil,
        message: String,
        requestID: String? = nil,
        status: Int? = nil,
        requiredPermissions: [String]? = nil,
        requiredTerms: [BackendTerms]? = nil,
        requiredActions: [String]? = nil,
        metadata: BackendAPIErrorMetadata? = nil
    ) {
        self.code = code
        self.numericCode = numericCode
        self.description = description
        self.messageKey = messageKey
        self.debugDescription = debugDescription
        self.message = message
        self.requestID = requestID
        self.status = status
        self.requiredPermissions = requiredPermissions
        self.requiredTerms = requiredTerms
        self.requiredActions = requiredActions
        self.metadata = metadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let errorCode = try container.decodeIfPresent(String.self, forKey: .errorCode) {
            code = errorCode
            numericCode = try? container.decodeIfPresent(Int.self, forKey: .code)
        } else if let stringCode = try container.decodeIfPresent(String.self, forKey: .code) {
            code = stringCode
            numericCode = Int(stringCode)
        } else if let intCode = try container.decodeIfPresent(Int.self, forKey: .code) {
            code = String(intCode)
            numericCode = intCode
        } else {
            code = ""
            numericCode = nil
        }
        description = try container.decodeIfPresent(String.self, forKey: .description)
        messageKey = try container.decodeIfPresent(String.self, forKey: .messageKey)
        debugDescription = try container.decodeIfPresent(String.self, forKey: .debugDescription)
        message = try container.decodeIfPresent(String.self, forKey: .message) ?? description ?? ""
        requestID = try container.decodeIfPresent(String.self, forKey: .requestID)
        status = try container.decodeIfPresent(Int.self, forKey: .status)
        requiredPermissions = try? container.decodeIfPresent([String].self, forKey: .requiredPermissions)
        requiredTerms = try? container.decodeIfPresent([BackendTerms].self, forKey: .requiredTerms)
        requiredActions = try? container.decodeIfPresent([String].self, forKey: .requiredActions)
        metadata = try? container.decodeIfPresent(BackendAPIErrorMetadata.self, forKey: .metadata)
    }

}

struct BackendAPIErrorMetadata: Decodable, Equatable {
    var quotaPeriod: String?
    var quotaResetAt: String?
    var quotaTimeZone: String?
    var remaining: Int64?
    var required: Int64?
    var quotaResetDate: Date? {
        guard let quotaResetAt else {
            return nil
        }
        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractionalFormatter.date(from: quotaResetAt) {
            return date
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: quotaResetAt)
    }

}

enum BackendTermsAgreementAction: String {
    case agreed = "AGREED"
    case withdrawn = "WITHDRAWN"
}

enum BackendTermsAgreementSource: String {
    case signup = "SIGNUP"
    case settings = "SETTINGS"
    case profile = "PROFILE"
    case requiredGate = "REQUIRED_GATE"
    case migration = "MIGRATION"
}

enum BackendTermsType: String, Codable, Equatable {
    case termsOfService = "TERMS_OF_SERVICE"
    case privacyPolicy = "PRIVACY_POLICY"
    case marketingNotification = "MARKETING_NOTIFICATION"

    init(code: String) {
        self = BackendTermsType(rawValue: code) ?? .termsOfService
    }
}

struct BackendTerms: Codable, Equatable, Identifiable {
    var type: BackendTermsType
    var code: String
    var version: String
    var title: String
    var url: URL
    var contentHash: String
    var required: Bool = true
    var mutable: Bool = false
    var agreed: Bool = false

    var id: String { "\(code):\(version):\(contentHash)" }

    init(
        type: BackendTermsType? = nil,
        code: String,
        version: String,
        title: String,
        url: URL,
        contentHash: String,
        required: Bool = true,
        mutable: Bool = false,
        agreed: Bool = false
    ) {
        self.code = code
        self.type = type ?? BackendTermsType(code: code)
        self.version = version
        self.title = title
        self.url = url
        self.contentHash = contentHash
        self.required = required
        self.mutable = mutable
        self.agreed = agreed
    }

    private enum CodingKeys: String, CodingKey {
        case type
        case code
        case version
        case title
        case url
        case contentHash
        case required
        case mutable
        case agreed
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let code = try container.decode(String.self, forKey: .code)
        self.init(
            type: try container.decodeIfPresent(BackendTermsType.self, forKey: .type) ?? BackendTermsType(code: code),
            code: code,
            version: try container.decode(String.self, forKey: .version),
            title: try container.decode(String.self, forKey: .title),
            url: try container.decode(URL.self, forKey: .url),
            contentHash: try container.decode(String.self, forKey: .contentHash),
            required: try container.decodeIfPresent(Bool.self, forKey: .required) ?? true,
            mutable: try container.decodeIfPresent(Bool.self, forKey: .mutable) ?? false,
            agreed: try container.decodeIfPresent(Bool.self, forKey: .agreed) ?? false
        )
    }
}

enum BackendTermsPresentationPolicy {
    static let localizedPrivacyPolicyVersion = "2026-08-25"
    static let localizedPrivacyPolicyContentHash =
        "13f2e4925ad4a28f39304570e68309a960c2460b3bdf87466898718648228a21"

    static func documentURL(for term: BackendTerms, language: AppLanguage) -> URL {
        guard term.type == .privacyPolicy,
              term.version == localizedPrivacyPolicyVersion,
              term.contentHash == localizedPrivacyPolicyContentHash else {
            return term.url
        }
        return AppLegalLinks.privacyPolicyURL(language: language)
    }
}

enum BackendNotificationPreferenceType: String, Codable, Equatable {
    case questionNotification = "QUESTION_NOTIFICATION"
    case marketingNotification = "MARKETING_NOTIFICATION"

    var key: String {
        switch self {
        case .questionNotification:
            return "question_notification"
        case .marketingNotification:
            return "marketing_notification"
        }
    }

    init(key: String) {
        switch key {
        case "marketing_notification":
            self = .marketingNotification
        default:
            self = .questionNotification
        }
    }
}

struct BackendNotificationPreference: Codable, Equatable, Identifiable {
    var type: BackendNotificationPreferenceType
    var key: String
    var enabled: Bool

    var id: String { key }

    init(type: BackendNotificationPreferenceType? = nil, key: String, enabled: Bool) {
        self.key = key
        self.type = type ?? BackendNotificationPreferenceType(key: key)
        self.enabled = enabled
    }

    private enum CodingKeys: String, CodingKey {
        case type
        case key
        case enabled
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let key = try container.decode(String.self, forKey: .key)
        self.init(
            type: try container.decodeIfPresent(BackendNotificationPreferenceType.self, forKey: .type) ?? BackendNotificationPreferenceType(key: key),
            key: key,
            enabled: try container.decode(Bool.self, forKey: .enabled)
        )
    }
}

struct BackendPermissionEvaluation: Decodable, Equatable, Identifiable {
    var permissionCode: String
    var granted: Bool
    var failureCode: String?
    var reason: String?
    var requiredTerms: [BackendTerms]
    var requiredActions: [String]

    var id: String { permissionCode }
}

struct BackendPermissionEvaluations: Decodable, Equatable {
    var permissions: [BackendPermissionEvaluation]
}

enum RemotePushBackendError: LocalizedError {
    case invalidResponse
    case httpStatus(Int, String, BackendAPIError?)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Invalid backend response."
        case .httpStatus(let statusCode, let body, let apiError):
            if let apiError {
                if let requestID = apiError.requestID, !requestID.isEmpty {
                    return "\(apiError.message) (\(apiError.code), \(requestID))"
                }
                return "\(apiError.message) (\(apiError.code))"
            }

            if let legacyMessage = Self.legacyMessage(from: body) {
                return legacyMessage
            }

            return "Backend request failed: HTTP \(statusCode)"
        }
    }

    var backendCode: String? {
        switch self {
        case .httpStatus(_, _, let apiError):
            return apiError?.code
        case .invalidResponse:
            return nil
        }
    }

    var backendMessage: String? {
        switch self {
        case .httpStatus(_, _, let apiError):
            return apiError?.message
        case .invalidResponse:
            return nil
        }
    }

    var responseBody: String? {
        switch self {
        case .httpStatus(_, let body, _):
            return body
        case .invalidResponse:
            return nil
        }
    }

    private static func legacyMessage(from body: String) -> String? {
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let detail = object["detail"] else {
            return nil
        }

        if let message = detail as? String, !message.isEmpty {
            return message
        }

        return nil
    }

}

extension AppLanguage {
    init?(backendCode: String) {
        switch backendCode {
        case "ko":
            self = .korean
        case "en":
            self = .english
        case "ja":
            self = .japanese
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
        case .japanese:
            return "ja"
        }
    }
}

enum ContentLanguageRecognizer {
    static func detect(_ text: String, fallback: AppLanguage) -> String {
        let candidate = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard candidate.count >= 4 else {
            return fallback.backendCode
        }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(candidate)
        guard let language = recognizer.dominantLanguage else {
            return fallback.backendCode
        }
        switch language {
        case .korean:
            return "ko"
        case .english:
            return "en"
        case .japanese:
            return "ja"
        default:
            return fallback.backendCode
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
