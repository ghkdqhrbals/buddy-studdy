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
        let communityRepository = RemoteCommunityRepository(backendClient: backendClient)
        let studyRoomRepository = RemoteStudyRoomRepository(backendClient: backendClient)
        let recordsRepository = RemoteRecordsRepository(backendClient: backendClient)
        backendIdentity = BackendIdentityUseCase(backendClient: backendClient)
        googleSignIn = GoogleSignInUseCase()
        refreshPageAccess = RefreshPageAccessUseCase(backendClient: backendClient)
        studyRoom = StudyRoomUseCase(repository: studyRoomRepository)
        records = RecordsUseCase(repository: recordsRepository)
        notifications = NotificationsUseCase(backendClient: backendClient)
        stats = StatsUseCase(backendClient: backendClient)
        settings = SettingsUseCase(backendClient: backendClient)
        community = CommunityUseCase(repository: communityRepository)
    }
}
