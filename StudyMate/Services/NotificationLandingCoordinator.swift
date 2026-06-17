import Foundation

@MainActor
final class NotificationLandingCoordinator {
    private unowned let appState: AppState

    init(appState: AppState) {
        self.appState = appState
    }

    @discardableResult
    func land(userInfo: [AnyHashable: Any], replyText: String? = nil) async -> Bool {
        if let recordID = StudyNotificationPayload.backendRecordID(from: userInfo) {
            return await land(recordID: recordID, replyText: replyText)
        }

        if let questionCreatedAt = StudyNotificationPayload.questionCreatedAt(from: userInfo) {
            return appState.openRecordFromNotification(
                recordID: nil,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText
            )
        }

        if let route = StudyNotificationPayload.appRoute(from: userInfo) {
            return await land(route: route, replyText: replyText)
        }

        appState.logRemoteNotificationEvent(
            "알림 payload를 라우팅하지 못했습니다. keys=\(StudyNotificationPayload.keySummary(from: userInfo))",
            isWarning: true
        )
        return false
    }

    @discardableResult
    func land(route: AppRoute, replyText: String? = nil) async -> Bool {
        if case .recordDetail(let recordID) = route {
            return await land(recordID: recordID, replyText: replyText)
        }

        appState.logRemoteNotificationEvent("알림 route를 열었습니다. route=\(route)")
        return appState.openRouteFromNotification(route)
    }

    @discardableResult
    func land(recordID: String, replyText: String? = nil) async -> Bool {
        do {
            let record = try await appState.fetchBackendNotificationRecord(recordID: recordID, replyText: replyText)
            let didOpen = appState.openNotificationRecord(record)
            let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            appState.notificationLandingMessage = nil
            appState.statusMessage = trimmedReply.isEmpty
                ? (record.gradingResult == nil ? "알림에서 열린 질문입니다." : "알림에서 기록을 열었습니다.")
                : "알림 답장을 기록에 저장했습니다."
            appState.logRemoteNotificationEvent("알림 record를 열었습니다. recordID=\(recordID), didOpen=\(didOpen)")
            return didOpen
        } catch {
            appState.showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            appState.logRemoteNotificationEvent("알림 record 라우팅 실패: \(error.localizedDescription)", isWarning: true)
            return false
        }
    }

    func routeForNotificationListSelection(_ notification: BackendAppNotification) async -> AppRoute? {
        if let recordID = recordID(from: notification) {
            do {
                let record = try await appState.fetchBackendNotificationRecord(recordID: recordID)
                if record.gradingResult == nil {
                    _ = appState.openNotificationRecord(record)
                    return nil
                }
                return appState.notificationRoute(for: record)
            } catch {
                appState.logRemoteNotificationEvent("알림함 record 라우팅 실패: \(error.localizedDescription)", isWarning: true)
                return .recordDetail(recordID: recordID)
            }
        }

        guard let deepLink = notification.deepLink,
              let url = URL(string: deepLink),
              let route = AppRoute(url: url) else {
            return nil
        }

        return route
    }

    private func recordID(from notification: BackendAppNotification) -> String? {
        if let deepLink = notification.deepLink,
           let url = URL(string: deepLink),
           case .recordDetail(let recordID) = AppRoute(url: url) {
            return recordID
        }

        if notification.threadType == "study_question",
           let threadID = notification.threadId,
           !threadID.isEmpty {
            return threadID
        }

        return nil
    }
}
