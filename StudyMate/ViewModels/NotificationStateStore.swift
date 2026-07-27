import Foundation

@MainActor
struct NotificationStateStore {
    var notifications: [BackendAppNotification] = []
    var unreadCount = 0
    var totalCount = 0
    var isLoading = false
    var errorMessage: String?

    mutating func beginLoading() {
        isLoading = true
        errorMessage = nil
    }

    mutating func finishLoading() {
        isLoading = false
    }

    mutating func applyUnreadCount(_ count: Int) {
        unreadCount = max(0, count)
    }

    mutating func applyPage(_ page: BackendNotificationsPage, reset: Bool) {
        unreadCount = max(0, page.unreadCount)
        totalCount = max(0, page.totalCount)

        if reset {
            notifications = page.notifications
            return
        }

        let existing = Set(notifications.map(\.id))
        notifications.append(contentsOf: page.notifications.filter { !existing.contains($0.id) })
    }

    mutating func applyError(_ message: String?) {
        errorMessage = message
    }

    mutating func markRead(notificationID: String, at readAt: Date) {
        guard let index = notifications.firstIndex(where: { $0.id == notificationID }),
              !notifications[index].isRead else {
            return
        }

        notifications[index].isRead = true
        notifications[index].readAt = readAt
        unreadCount = max(0, unreadCount - 1)
    }

    mutating func markAllRead(at readAt: Date) {
        for index in notifications.indices where !notifications[index].isRead {
            notifications[index].isRead = true
            notifications[index].readAt = readAt
        }
        unreadCount = 0
    }

    mutating func delete(notificationID: String) {
        guard let index = notifications.firstIndex(where: { $0.id == notificationID }) else {
            return
        }

        let removed = notifications.remove(at: index)
        totalCount = max(0, totalCount - 1)
        if !removed.isRead {
            unreadCount = max(0, unreadCount - 1)
        }
    }

    mutating func deleteAll() {
        notifications = []
        totalCount = 0
        unreadCount = 0
    }

    mutating func reset() {
        notifications = []
        unreadCount = 0
        totalCount = 0
        isLoading = false
        errorMessage = nil
    }

    func canLoadMore(current notification: BackendAppNotification) -> Bool {
        notification.id == notifications.last?.id && notifications.count < totalCount
    }
}
