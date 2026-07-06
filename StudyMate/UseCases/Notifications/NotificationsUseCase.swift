import Foundation

@MainActor
struct NotificationsUseCase {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchNotifications(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendNotificationsPage {
        try await backendClient.fetchNotifications(
            registration: registration,
            limit: limit,
            offset: offset
        )
    }

    func fetchUnreadCount(registration: RemotePushRegistration) async throws -> Int {
        try await backendClient.fetchNotificationUnreadCount(registration: registration)
    }

    func markRead(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws {
        try await backendClient.markNotificationRead(
            registration: registration,
            notificationID: notificationID
        )
    }

    func deleteNotification(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws {
        try await backendClient.deleteNotification(
            registration: registration,
            notificationID: notificationID
        )
    }

    func deleteAllNotifications(registration: RemotePushRegistration) async throws {
        try await backendClient.deleteAllNotifications(registration: registration)
    }
}
