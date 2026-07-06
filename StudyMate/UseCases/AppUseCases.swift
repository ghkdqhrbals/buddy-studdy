import Foundation

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
        backendIdentity = BackendIdentityUseCase(repository: identityRepository)
        googleSignIn = GoogleSignInUseCase(repository: googleSignInRepository)
        refreshPageAccess = RefreshPageAccessUseCase(repository: pageAccessRepository)
        studyRoom = StudyRoomUseCase(repository: studyRoomRepository)
        records = RecordsUseCase(repository: recordsRepository)
        notifications = NotificationsUseCase(repository: notificationsRepository)
        stats = StatsUseCase(repository: statsRepository)
        settings = SettingsUseCase(repository: settingsRepository)
        community = CommunityUseCase(repository: communityRepository)
    }
}
