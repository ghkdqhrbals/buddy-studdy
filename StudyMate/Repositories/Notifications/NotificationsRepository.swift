import Foundation

enum NotificationOpenSource: String, Encodable {
    case push = "PUSH"
    case inbox = "INBOX"
}

@MainActor
protocol NotificationsRepository {
    func fetchNotifications(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendNotificationsPage

    func fetchUnreadCount(registration: RemotePushRegistration) async throws -> Int

    func markRead(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws

    func recordOpen(
        registration: RemotePushRegistration,
        notificationID: String,
        source: NotificationOpenSource
    ) async throws

    func markAllRead(registration: RemotePushRegistration) async throws

    func deleteNotification(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws

    func deleteAllNotifications(registration: RemotePushRegistration) async throws
}
