import Foundation

@MainActor
struct AppUseCases {
    let refreshPageAccess: RefreshPageAccessUseCase
    let studyRoom: StudyRoomUseCase
    let records: RecordsUseCase
    let notifications: NotificationsUseCase
    let stats: StatsUseCase
    let settings: SettingsUseCase
    let community: CommunityUseCase

    init(backendClient: RemotePushBackendClientProtocol) {
        refreshPageAccess = RefreshPageAccessUseCase(backendClient: backendClient)
        studyRoom = StudyRoomUseCase(backendClient: backendClient)
        records = RecordsUseCase(backendClient: backendClient)
        notifications = NotificationsUseCase(backendClient: backendClient)
        stats = StatsUseCase(backendClient: backendClient)
        settings = SettingsUseCase(backendClient: backendClient)
        community = CommunityUseCase(backendClient: backendClient)
    }
}
