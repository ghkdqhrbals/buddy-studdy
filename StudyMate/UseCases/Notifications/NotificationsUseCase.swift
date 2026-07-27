import Foundation

@MainActor
struct NotificationsUseCase {
    private let repository: NotificationsRepository

    init(repository: NotificationsRepository) {
        self.repository = repository
    }

    func fetchNotifications(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int
    ) async throws -> BackendNotificationsPage {
        try await repository.fetchNotifications(
            registration: registration,
            limit: limit,
            offset: offset
        )
    }

    func fetchUnreadCount(registration: RemotePushRegistration) async throws -> Int {
        try await repository.fetchUnreadCount(registration: registration)
    }

    func markRead(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws {
        try await repository.markRead(
            registration: registration,
            notificationID: notificationID
        )
    }

    func markAllRead(registration: RemotePushRegistration) async throws {
        try await repository.markAllRead(registration: registration)
    }

    func deleteNotification(
        registration: RemotePushRegistration,
        notificationID: String
    ) async throws {
        try await repository.deleteNotification(
            registration: registration,
            notificationID: notificationID
        )
    }

    func deleteAllNotifications(registration: RemotePushRegistration) async throws {
        try await repository.deleteAllNotifications(registration: registration)
    }
}
