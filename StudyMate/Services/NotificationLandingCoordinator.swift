import Foundation

enum NotificationRouteResolver {
    static func recognizes(notificationType: String?) -> Bool {
        switch normalized(notificationType) {
        case "study_question", "question_created", "thread_activity", "comment", "question_comment", "like", "question_like":
            return true
        default:
            return false
        }
    }

    static func route(
        deepLink: String?,
        threadType: String?,
        threadID: String?,
        notificationType: String?
    ) -> AppRoute {
        if let deepLink = trimmed(deepLink),
           let url = URL(string: deepLink),
           let route = AppRoute(url: url) {
            return route
        }

        let normalizedThreadType = normalized(threadType)
        let normalizedNotificationType = normalized(notificationType)
        let normalizedThreadID = trimmed(threadID)

        if let normalizedThreadID {
            switch normalizedThreadType {
            case "study_question", "study_record", "record", "records":
                return .recordDetail(recordID: normalizedThreadID)
            case "question", "public_question", "community_question", "comment", "like", "thread_activity":
                return .publicQuestion(id: normalizedThreadID)
            case "study", "studies", "study_room":
                return .studyRoom(categoryID: normalizedThreadID)
            default:
                break
            }

            switch normalizedNotificationType {
            case "study_question", "question_created":
                return .recordDetail(recordID: normalizedThreadID)
            case "thread_activity", "comment", "question_comment", "like", "question_like":
                return .publicQuestion(id: normalizedThreadID)
            default:
                break
            }
        }

        switch normalizedThreadType {
        case "study_question", "study_record", "record", "records", "study", "studies", "study_room":
            return .studyList
        case "question", "public_question", "community_question", "comment", "like", "thread_activity":
            return .publicQuestions
        default:
            break
        }

        switch normalizedNotificationType {
        case "study_question", "question_created":
            return .studyList
        case "thread_activity", "comment", "question_comment", "like", "question_like":
            return .publicQuestions
        default:
            return .home
        }
    }

    static func route(for notification: BackendAppNotification) -> AppRoute {
        route(
            deepLink: notification.deepLink,
            threadType: notification.threadType,
            threadID: notification.threadId,
            notificationType: notification.type
        )
    }

    private static func trimmed(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else {
            return nil
        }
        return value
    }

    private static func normalized(_ value: String?) -> String {
        guard let value = trimmed(value) else {
            return ""
        }
        return value
            .lowercased()
            .replacingOccurrences(of: "-", with: "_")
            .replacingOccurrences(of: ".", with: "_")
            .replacingOccurrences(of: " ", with: "_")
    }
}

@MainActor
final class NotificationLandingCoordinator {
    private unowned let appState: AppState

    init(appState: AppState) {
        self.appState = appState
    }

    @discardableResult
    func land(userInfo: [AnyHashable: Any], replyText: String? = nil) async -> Bool {
        if let notificationID = StudyNotificationPayload.appNotificationID(from: userInfo) {
            Task { @MainActor in
                await appState.markNotificationRead(notificationID: notificationID)
            }
        }

        if let route = StudyNotificationPayload.appRoute(from: userInfo) {
            return await land(route: route, replyText: replyText)
        }

        if let recordID = StudyNotificationPayload.backendRecordID(from: userInfo) {
            return await land(recordID: recordID, replyText: replyText)
        }

        if let questionCreatedAt = StudyNotificationPayload.questionCreatedAt(from: userInfo) {
            let didOpen = appState.openRecordFromNotification(
                recordID: nil,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText
            )
            if didOpen {
                AppAnalytics.notificationOpened(kind: .question)
            }
            return didOpen
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
        let didOpen = appState.openRouteFromNotification(route)
        if didOpen {
            AppAnalytics.notificationOpened(kind: analyticsKind(for: route))
        }
        return didOpen
    }

    @discardableResult
    func land(recordID: String, replyText: String? = nil) async -> Bool {
        let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmedReply.isEmpty else {
            appState.notificationLandingMessage = nil
            appState.statusMessage = appState.strings.openingNotificationQuestion
            appState.logRemoteNotificationEvent("알림 record route를 즉시 열었습니다. recordID=\(recordID)")
            let didOpen = appState.openRouteFromNotification(.recordDetail(recordID: recordID))
            if didOpen {
                AppAnalytics.notificationOpened(kind: .question)
            }
            return didOpen
        }

        do {
            let record = try await appState.fetchBackendNotificationRecord(recordID: recordID, replyText: replyText)
            let didOpen = appState.openNotificationRecord(record)
            appState.notificationLandingMessage = nil
            appState.statusMessage = trimmedReply.isEmpty
                ? (record.gradingResult == nil ? "알림에서 열린 질문입니다." : "알림에서 기록을 열었습니다.")
                : "알림 답장을 기록에 저장했습니다."
            appState.logRemoteNotificationEvent("알림 record를 열었습니다. recordID=\(recordID), didOpen=\(didOpen)")
            if didOpen {
                AppAnalytics.notificationOpened(kind: .question)
            }
            return didOpen
        } catch {
            appState.showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            appState.logRemoteNotificationEvent("알림 record 라우팅 실패: \(error.localizedDescription)", isWarning: true)
            return false
        }
    }

    func routeForNotificationListSelection(_ notification: BackendAppNotification) -> AppRoute {
        NotificationRouteResolver.route(for: notification)
    }

    private func analyticsKind(for route: AppRoute) -> AppAnalyticsNotificationKind {
        switch route {
        case .recordDetail, .studyList, .studyRoom:
            return .question
        case .publicQuestion, .publicQuestions:
            return .community
        default:
            return .general
        }
    }
}
