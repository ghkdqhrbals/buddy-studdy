import Foundation

@MainActor
protocol ServiceAvailabilityRepository {
    func fetch(language: AppLanguage) async -> BackendServiceAvailability?
}

@MainActor
struct RemoteServiceAvailabilityRepository: ServiceAvailabilityRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetch(language: AppLanguage) async -> BackendServiceAvailability? {
        await backendClient.fetchServiceAvailability(language: language)
    }
}

@MainActor
struct ServiceAvailabilityUseCase {
    private let repository: ServiceAvailabilityRepository

    init(repository: ServiceAvailabilityRepository) {
        self.repository = repository
    }

    func fetch(language: AppLanguage) async -> BackendServiceAvailability? {
        await repository.fetch(language: language)
    }
}

@MainActor
protocol AppUpdateRepository {
    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision
    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws
}

@MainActor
struct RemoteAppUpdateRepository: AppUpdateRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        try await backendClient.checkAppUpdate(registration: registration, language: language)
    }

    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {
        try await backendClient.recordAppUpdateEvent(
            registration: registration,
            campaignID: campaignID,
            event: event
        )
    }
}

@MainActor
struct AppUpdateUseCase {
    private let repository: AppUpdateRepository

    init(repository: AppUpdateRepository) {
        self.repository = repository
    }

    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        try await repository.check(registration: registration, language: language)
    }

    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {
        try await repository.record(registration: registration, campaignID: campaignID, event: event)
    }
}

@MainActor
protocol TermsRepository {
    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms]

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
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
}

@MainActor
struct RemoteTermsRepository: TermsRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms] {
        try await backendClient.fetchActiveTerms(registration: registration)
    }

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations {
        try await backendClient.saveTermsAgreement(
            registration: registration,
            type: type,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await backendClient.fetchPermissionEvaluations(registration: registration)
    }

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference] {
        try await backendClient.fetchNotificationPreferences(registration: registration)
    }

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference {
        try await backendClient.saveNotificationPreference(
            registration: registration,
            type: type,
            enabled: enabled
        )
    }
}

@MainActor
struct TermsUseCase {
    private let repository: TermsRepository

    init(repository: TermsRepository) {
        self.repository = repository
    }

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms] {
        try await repository.fetchActiveTerms(registration: registration)
    }

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource = .settings
    ) async throws -> BackendPermissionEvaluations {
        try await repository.saveAgreement(
            registration: registration,
            type: type,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await repository.fetchPermissionEvaluations(registration: registration)
    }

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference] {
        try await repository.fetchNotificationPreferences(registration: registration)
    }

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference {
        try await repository.saveNotificationPreference(
            registration: registration,
            type: type,
            enabled: enabled
        )
    }
}

@MainActor
struct AppUseCases {
    let serviceAvailability: ServiceAvailabilityUseCase
    let appUpdate: AppUpdateUseCase
    let backendIdentity: BackendIdentityUseCase
    let googleSignIn: GoogleSignInUseCase
    let studyRoom: StudyRoomUseCase
    let records: RecordsUseCase
    let notifications: NotificationsUseCase
    let stats: StatsUseCase
    let settings: SettingsUseCase
    let terms: TermsUseCase
    let community: CommunityUseCase

    init(backendClient: RemotePushBackendClientProtocol) {
        let serviceAvailabilityRepository = RemoteServiceAvailabilityRepository(backendClient: backendClient)
        let appUpdateRepository = RemoteAppUpdateRepository(backendClient: backendClient)
        let googleSignInRepository = OAuthGoogleSignInRepository()
        let identityRepository = RemoteIdentityRepository(backendClient: backendClient)
        let communityRepository = RemoteCommunityRepository(backendClient: backendClient)
        let studyRoomRepository = RemoteStudyRoomRepository(backendClient: backendClient)
        let recordsRepository = RemoteRecordsRepository(backendClient: backendClient)
        let statsRepository = RemoteStatsRepository(backendClient: backendClient)
        let notificationsRepository = RemoteNotificationsRepository(backendClient: backendClient)
        let settingsRepository = RemoteSettingsRepository(backendClient: backendClient)
        let termsRepository = RemoteTermsRepository(backendClient: backendClient)
        serviceAvailability = ServiceAvailabilityUseCase(repository: serviceAvailabilityRepository)
        appUpdate = AppUpdateUseCase(repository: appUpdateRepository)
        backendIdentity = BackendIdentityUseCase(repository: identityRepository)
        googleSignIn = GoogleSignInUseCase(repository: googleSignInRepository)
        studyRoom = StudyRoomUseCase(repository: studyRoomRepository)
        records = RecordsUseCase(repository: recordsRepository)
        notifications = NotificationsUseCase(repository: notificationsRepository)
        stats = StatsUseCase(repository: statsRepository)
        settings = SettingsUseCase(repository: settingsRepository)
        terms = TermsUseCase(repository: termsRepository)
        community = CommunityUseCase(repository: communityRepository)
    }
}
