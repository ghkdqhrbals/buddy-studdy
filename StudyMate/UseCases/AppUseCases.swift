import Foundation

@MainActor
protocol TermsRepository {
    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms]

    func saveAgreement(
        registration: RemotePushRegistration,
        code: String,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations
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
        code: String,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations {
        try await backendClient.saveTermsAgreement(
            registration: registration,
            code: code,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await backendClient.fetchPermissionEvaluations(registration: registration)
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
        code: String,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource = .settings
    ) async throws -> BackendPermissionEvaluations {
        try await repository.saveAgreement(
            registration: registration,
            code: code,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await repository.fetchPermissionEvaluations(registration: registration)
    }
}

@MainActor
struct AppUseCases {
    let backendIdentity: BackendIdentityUseCase
    let googleSignIn: GoogleSignInUseCase
    let refreshPageAccess: RefreshPageAccessUseCase
    let studyRoom: StudyRoomUseCase
    let records: RecordsUseCase
    let notifications: NotificationsUseCase
    let stats: StatsUseCase
    let settings: SettingsUseCase
    let terms: TermsUseCase
    let community: CommunityUseCase

    init(backendClient: RemotePushBackendClientProtocol) {
        let googleSignInRepository = OAuthGoogleSignInRepository()
        let identityRepository = RemoteIdentityRepository(backendClient: backendClient)
        let communityRepository = RemoteCommunityRepository(backendClient: backendClient)
        let studyRoomRepository = RemoteStudyRoomRepository(backendClient: backendClient)
        let recordsRepository = RemoteRecordsRepository(backendClient: backendClient)
        let statsRepository = RemoteStatsRepository(backendClient: backendClient)
        let notificationsRepository = RemoteNotificationsRepository(backendClient: backendClient)
        let settingsRepository = RemoteSettingsRepository(backendClient: backendClient)
        let pageAccessRepository = RemotePageAccessRepository(backendClient: backendClient)
        let termsRepository = RemoteTermsRepository(backendClient: backendClient)
        backendIdentity = BackendIdentityUseCase(repository: identityRepository)
        googleSignIn = GoogleSignInUseCase(repository: googleSignInRepository)
        refreshPageAccess = RefreshPageAccessUseCase(repository: pageAccessRepository)
        studyRoom = StudyRoomUseCase(repository: studyRoomRepository)
        records = RecordsUseCase(repository: recordsRepository)
        notifications = NotificationsUseCase(repository: notificationsRepository)
        stats = StatsUseCase(repository: statsRepository)
        settings = SettingsUseCase(repository: settingsRepository)
        terms = TermsUseCase(repository: termsRepository)
        community = CommunityUseCase(repository: communityRepository)
    }
}
