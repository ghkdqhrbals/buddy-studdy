import Foundation
import CloudKit
#if os(macOS)
import AppKit
#elseif os(iOS)
import AudioToolbox
import AVFoundation
import UIKit
#endif
import SwiftUI
@preconcurrency import UserNotifications

enum StudyNotificationAction {
    static let category = "STUDY_QUESTION_CATEGORY"
    static let reply = "STUDY_QUESTION_REPLY"
    static let ignore = "STUDY_QUESTION_IGNORE"
    static let otherAnswer = "STUDY_QUESTION_OTHER_ANSWER"
    static let questionCreatedAt = "questionCreatedAt"
    static let recordID = "recordId"
}

enum StudyNotificationRouting {
    static func isIgnored(_ actionIdentifier: String) -> Bool {
        actionIdentifier == StudyNotificationAction.ignore ||
            actionIdentifier == UNNotificationDismissActionIdentifier
    }

    static func shouldOpenStudyImmediately(actionIdentifier: String) -> Bool {
        actionIdentifier == UNNotificationDefaultActionIdentifier
    }

    static func shouldOpenStudyImmediately(
        actionIdentifier: String,
        isApplicationInactive: Bool
    ) -> Bool {
        shouldOpenStudyImmediately(actionIdentifier: actionIdentifier) && isApplicationInactive
    }

    #if os(iOS)
    static func shouldOpenStudyImmediately(
        actionIdentifier: String,
        applicationState: UIApplication.State
    ) -> Bool {
        shouldOpenStudyImmediately(
            actionIdentifier: actionIdentifier,
            isApplicationInactive: applicationState == .inactive
        )
    }
    #endif
}

enum StudyNotificationPayload {
    static func appRoute(from userInfo: [AnyHashable: Any]) -> AppRoute? {
        let dictionaries = cloudKitDictionaries(from: userInfo)

        for dictionary in dictionaries {
            if let route = appRouteFromDeepLink(in: dictionary) {
                return route
            }
        }

        for dictionary in dictionaries {
            let params = routeParams(from: dictionary)
            for key in ["route", "screen", "destination"] {
                if let value = stringValue(dictionary[key]),
                   let route = AppRoute(route: value, params: params) {
                    return route
                }
            }
        }

        return nil
    }

    private static func appRouteFromDeepLink(in dictionary: [AnyHashable: Any]) -> AppRoute? {
        for key in ["deepLink", "url", "landingUrl"] {
            if let value = stringValue(dictionary[key]),
               let url = URL(string: value),
               let route = AppRoute(url: url) {
                return route
            }
        }

        return nil
    }

    static func backendRecordID(from userInfo: [AnyHashable: Any]) -> String? {
        let candidateKeys = [
            StudyNotificationAction.recordID,
            "recordID",
            "recordId",
            "id"
        ]

        for key in candidateKeys {
            if let value = stringValue(userInfo[key]) {
                return value
            }
        }

        for dictionary in cloudKitDictionaries(from: userInfo) {
            for key in candidateKeys {
                if let value = stringValue(dictionary[key]) {
                    return value
                }
            }
        }

        if case .recordDetail(let recordID) = appRoute(from: userInfo) {
            return recordID
        }

        return nil
    }

    static func questionCreatedAt(from userInfo: [AnyHashable: Any]) -> TimeInterval? {
        let candidateKeys = [
            StudyNotificationAction.questionCreatedAt,
            "createdAt",
            "questionCreatedAt"
        ]

        for key in candidateKeys {
            if let timeInterval = timeIntervalValue(userInfo[key]) {
                return timeInterval
            }
        }

        for dictionary in cloudKitDictionaries(from: userInfo) {
            for key in candidateKeys where key != StudyNotificationAction.questionCreatedAt {
                if let timeInterval = timeIntervalValue(dictionary[key]) {
                    return timeInterval
                }
            }
        }

        return nil
    }

    static func cloudQuestionPushRecordName(from userInfo: [AnyHashable: Any]) -> String? {
        guard hasCloudKitQuestionPushShape(userInfo) else {
            return nil
        }

        if let notification = CKNotification(fromRemoteNotificationDictionary: userInfo) as? CKQueryNotification,
           notification.subscriptionID == CloudSyncService.questionPushSubscriptionID,
           let recordName = notification.recordID?.recordName {
            return recordName
        }

        return rawCloudQuestionPushRecordName(from: userInfo)
    }

    static func isCloudQuestionPush(from userInfo: [AnyHashable: Any]) -> Bool {
        guard hasCloudKitQuestionPushShape(userInfo) else {
            return false
        }

        if cloudQuestionPushRecordName(from: userInfo) != nil {
            return true
        }

        if let notification = CKNotification(fromRemoteNotificationDictionary: userInfo) as? CKQueryNotification {
            return notification.subscriptionID == CloudSyncService.questionPushSubscriptionID
        }

        return cloudKitDictionaries(from: userInfo).contains { dictionary in
            stringValue(dictionary["sid"]) == CloudSyncService.questionPushSubscriptionID ||
                stringValue(dictionary["subscriptionID"]) == CloudSyncService.questionPushSubscriptionID ||
                stringValue(dictionary["subscriptionId"]) == CloudSyncService.questionPushSubscriptionID
        }
    }

    static func keySummary(from userInfo: [AnyHashable: Any]) -> String {
        userInfo.keys
            .map { String(describing: $0) }
            .sorted()
            .joined(separator: ",")
    }

    private static func rawCloudQuestionPushRecordName(from userInfo: [AnyHashable: Any]) -> String? {
        for dictionary in cloudKitDictionaries(from: userInfo) {
            let subscriptionID = stringValue(dictionary["sid"]) ??
                stringValue(dictionary["subscriptionID"]) ??
                stringValue(dictionary["subscriptionId"])
            let recordName = stringValue(dictionary["rid"]) ??
                stringValue(dictionary["recordName"]) ??
                stringValue(dictionary["recordID"]) ??
                stringValue(dictionary["recordId"])

            if let recordName,
               subscriptionID == nil || subscriptionID == CloudSyncService.questionPushSubscriptionID {
                return recordName
            }
        }

        return nil
    }

    private static func hasCloudKitQuestionPushShape(_ userInfo: [AnyHashable: Any]) -> Bool {
        cloudKitDictionaries(from: userInfo).contains { dictionary in
            stringValue(dictionary["sid"]) == CloudSyncService.questionPushSubscriptionID ||
                stringValue(dictionary["subscriptionID"]) == CloudSyncService.questionPushSubscriptionID ||
                stringValue(dictionary["subscriptionId"]) == CloudSyncService.questionPushSubscriptionID ||
                dictionary["rid"] != nil ||
                dictionary["recordName"] != nil ||
                dictionary["recordID"] != nil ||
                dictionary["recordId"] != nil
        }
    }

    private static func cloudKitDictionaries(from userInfo: [AnyHashable: Any]) -> [[AnyHashable: Any]] {
        var dictionaries: [[AnyHashable: Any]] = [userInfo]

        if let cloudKitDictionary = dictionaryValue(userInfo["ck"]) {
            dictionaries.append(cloudKitDictionary)

            if let queryDictionary = dictionaryValue(cloudKitDictionary["qry"]) {
                dictionaries.append(queryDictionary)
            }
        }

        if let queryDictionary = dictionaryValue(userInfo["qry"]) {
            dictionaries.append(queryDictionary)
        }

        return dictionaries
    }

    private static func dictionaryValue(_ value: Any?) -> [AnyHashable: Any]? {
        if let dictionary = value as? [AnyHashable: Any] {
            return dictionary
        }

        if let dictionary = value as? [String: Any] {
            var converted: [AnyHashable: Any] = [:]
            for (key, value) in dictionary {
                converted[key] = value
            }
            return converted
        }

        return nil
    }

    private static func routeParams(from dictionary: [AnyHashable: Any]) -> [String: String] {
        var params: [String: String] = [:]

        for (key, value) in dictionary {
            let normalizedKey = String(describing: key)
            if let string = stringValue(value) {
                params[normalizedKey] = string
            }
        }

        if let nestedParams = dictionaryValue(dictionary["params"]) ?? dictionaryValue(dictionary["parameters"]) {
            for (key, value) in nestedParams {
                if let string = stringValue(value) {
                    params[String(describing: key)] = string
                }
            }
        }

        return params
    }

    private static func stringValue(_ value: Any?) -> String? {
        if let string = value as? String, !string.isEmpty {
            return string
        }

        return nil
    }

    private static func timeIntervalValue(_ value: Any?) -> TimeInterval? {
        if let timeInterval = value as? TimeInterval {
            return timeInterval
        }

        if let number = value as? NSNumber {
            return number.doubleValue
        }

        if let string = value as? String,
           let doubleValue = Double(string) {
            return doubleValue
        }

        if let string = value as? String,
           let date = ISO8601DateFormatter.studyMateDate(from: string) {
            return date.timeIntervalSince1970
        }

        if let date = value as? Date {
            return date.timeIntervalSince1970
        }

        return nil
    }
}

private extension ISO8601DateFormatter {
    static func studyMateDate(from value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: value) {
            return date
        }

        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractionalFormatter.date(from: value)
    }
}

@MainActor
protocol NotificationServicing: AnyObject {
    func requestAuthorizationIfNeeded(language: AppLanguage) async -> Bool
    func openSystemNotificationSettings()
    func playPreview(sound: NotificationSoundOption)
    func showQuestionNotification(
        question: QuestionItem,
        title: String,
        subtitle: String?,
        sound: NotificationSoundOption,
        language: AppLanguage,
        deliveryDate: Date?
    ) async -> Bool
    func cancelQuestionNotification(for question: QuestionItem)
    func cancelQuestionNotifications(for questions: [QuestionItem])
    func pendingQuestionNotificationCount() async -> Int
}

@MainActor
final class NotificationService: NotificationServicing {
    #if os(macOS)
    private var previewSound: NSSound?
    #elseif os(iOS)
    private var previewPlayer: AVAudioPlayer?
    #endif

    func requestAuthorizationIfNeeded(language: AppLanguage) async -> Bool {
        let center = UNUserNotificationCenter.current()
        StudyNotificationDelegate.shared.register(language: language)
        let settings = await center.notificationSettings()

        switch settings.authorizationStatus {
        case .authorized, .provisional:
            registerForRemoteNotificationsIfAvailable()
            return true
        case .denied:
            return false
        case .notDetermined:
            do {
                let isAuthorized = try await center.requestAuthorization(options: [.alert, .sound, .badge])
                if isAuthorized {
                    registerForRemoteNotificationsIfAvailable()
                }
                return isAuthorized
            } catch {
                return false
            }
        case .ephemeral:
            registerForRemoteNotificationsIfAvailable()
            return true
        @unknown default:
            return false
        }
    }

    private func registerForRemoteNotificationsIfAvailable() {
        #if os(iOS)
        if Thread.isMainThread {
            UIApplication.shared.registerForRemoteNotifications()
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
        #endif
    }

    func openSystemNotificationSettings() {
        #if os(macOS)
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") else {
            return
        }

        NSWorkspace.shared.open(url)
        #elseif os(iOS)
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        if Thread.isMainThread {
            UIApplication.shared.open(url)
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.open(url)
            }
        }
        #endif
    }

    func playPreview(sound: NotificationSoundOption) {
        #if os(macOS)
        previewSound?.stop()
        previewSound = nil
        #elseif os(iOS)
        previewPlayer?.stop()
        previewPlayer = nil
        #endif

        switch sound {
        case .defaultSound:
            #if os(macOS)
            NSSound.beep()
            #elseif os(iOS)
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
            try? AVAudioSession.sharedInstance().setActive(true)
            AudioServicesPlaySystemSound(1007)
            #endif
        case .none:
            return
        case .softPing, .chime, .pop, .bell, .tap:
            guard let fileName = sound.bundledFileName else {
                #if os(macOS)
                NSSound.beep()
                #elseif os(iOS)
                AudioServicesPlaySystemSound(1007)
                #endif
                return
            }

            let resourceName = NSString(string: fileName).deletingPathExtension
            let fileExtension = NSString(string: fileName).pathExtension

            #if os(macOS)
            guard let url = Bundle.main.url(forResource: resourceName, withExtension: fileExtension),
                  let sound = NSSound(contentsOf: url, byReference: false) else {
                NSSound.beep()
                return
            }

            previewSound = sound
            sound.play()
            #elseif os(iOS)
            guard let url = Bundle.main.url(forResource: resourceName, withExtension: fileExtension) else {
                try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
                try? AVAudioSession.sharedInstance().setActive(true)
                AudioServicesPlaySystemSound(1007)
                return
            }

            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
                try AVAudioSession.sharedInstance().setActive(true)
                let player = try AVAudioPlayer(contentsOf: url)
                player.volume = 1
                player.prepareToPlay()
                previewPlayer = player
                player.play()
            } catch {
                try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
                try? AVAudioSession.sharedInstance().setActive(true)
                AudioServicesPlaySystemSound(1007)
            }
            #endif
        }
    }

    @discardableResult
    func showQuestionNotification(
        question: QuestionItem,
        title: String,
        subtitle: String?,
        sound: NotificationSoundOption,
        language: AppLanguage,
        deliveryDate: Date? = nil
    ) async -> Bool {
        guard await requestAuthorizationIfNeeded(language: language) else {
            return false
        }

        let content = UNMutableNotificationContent()
        content.title = title
        content.subtitle = subtitle ?? ""
        content.body = question.question
        content.sound = sound.userNotificationSound
        content.categoryIdentifier = StudyNotificationAction.category
        content.threadIdentifier = "StudyMate.question"
        #if os(macOS)
        content.summaryArgument = question.question
        #endif
        content.userInfo = [
            StudyNotificationAction.questionCreatedAt: question.createdAt.timeIntervalSince1970
        ]

        let trigger: UNNotificationTrigger?
        if let deliveryDate {
            let timeInterval = deliveryDate.timeIntervalSinceNow
            trigger = timeInterval > 1
                ? UNTimeIntervalNotificationTrigger(timeInterval: timeInterval, repeats: false)
                : nil
        } else {
            trigger = nil
        }

        let request = UNNotificationRequest(
            identifier: Self.questionNotificationIdentifier(for: question),
            content: content,
            trigger: trigger
        )

        do {
            try await UNUserNotificationCenter.current().add(request)
            return true
        } catch {
            return false
        }
    }

    func cancelQuestionNotification(for question: QuestionItem) {
        cancelQuestionNotifications(for: [question])
    }

    func cancelQuestionNotifications(for questions: [QuestionItem]) {
        let identifiers = questions.map(Self.questionNotificationIdentifier(for:))
        guard !identifiers.isEmpty else {
            return
        }

        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func pendingQuestionNotificationCount() async -> Int {
        await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
                let count = requests.filter {
                    $0.identifier.hasPrefix(Self.questionNotificationIdentifierPrefix)
                }.count
                continuation.resume(returning: count)
            }
        }
    }

    nonisolated private static let questionNotificationIdentifierPrefix = "study-question-"

    private static func questionNotificationIdentifier(for question: QuestionItem) -> String {
        "\(questionNotificationIdentifierPrefix)\(question.createdAt.timeIntervalSince1970)"
    }
}

private extension NotificationSoundOption {
    var userNotificationSound: UNNotificationSound? {
        switch self {
        case .defaultSound:
            .default
        case .none:
            nil
        case .softPing, .chime, .pop, .bell, .tap:
            bundledFileName.map {
                UNNotificationSound(named: UNNotificationSoundName(rawValue: $0))
            } ?? .default
        }
    }
}

final class StudyNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    nonisolated(unsafe) static let shared = StudyNotificationDelegate()

    @MainActor
    private weak var appState: AppState?
    @MainActor
    private var pendingLocalResponses: [PendingLocalNotificationResponse] = []
    @MainActor
    private var pendingAppRoutes: [AppRoute] = []

    private struct PendingLocalNotificationResponse {
        var actionIdentifier: String
        var recordID: String?
        var questionCreatedAt: TimeInterval?
        var replyText: String?
        var openStudy: Bool
    }

    @MainActor
    func configure(appState: AppState) {
        self.appState = appState
        register(language: appState.settings.appLanguage)
        processPendingAppRoutes()
        processPendingLocalResponsesIfActive()
    }

    @MainActor
    private func enqueueAppRoute(_ route: AppRoute) {
        guard let appState else {
            pendingAppRoutes.append(route)
            return
        }

        appState.openRoute(route)
    }

    @MainActor
    private func processPendingAppRoutes() {
        guard let appState else {
            return
        }

        let routes = pendingAppRoutes
        pendingAppRoutes.removeAll()
        for route in routes {
            appState.openRoute(route)
        }
    }

    @MainActor
    func enqueueLocalResponse(
        actionIdentifier: String,
        recordID: String?,
        questionCreatedAt: TimeInterval?,
        replyText: String?,
        openStudy: Bool
    ) {
        pendingLocalResponses.append(
            PendingLocalNotificationResponse(
                actionIdentifier: actionIdentifier,
                recordID: recordID,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText,
                openStudy: openStudy
            )
        )
        processPendingLocalResponsesIfActive()
    }

    @MainActor
    func processPendingLocalResponsesIfActive() {
        #if os(iOS)
        guard UIApplication.shared.applicationState == .active else {
            return
        }
        #endif

        processPendingLocalResponses()
    }

    @MainActor
    private func processPendingLocalResponses() {
        let pendingResponses = pendingLocalResponses
        pendingLocalResponses.removeAll()
        for response in pendingResponses {
            if response.openStudy {
                handle(
                    actionIdentifier: response.actionIdentifier,
                    recordID: response.recordID,
                    questionCreatedAt: response.questionCreatedAt,
                    replyText: response.replyText
                )
            } else {
                handleQuietly(
                    actionIdentifier: response.actionIdentifier,
                    recordID: response.recordID,
                    questionCreatedAt: response.questionCreatedAt,
                    replyText: response.replyText
                )
            }
        }
    }

    func register(language: AppLanguage = .korean) {
        let strings = AppStrings(language: language)
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        #if os(iOS)
        let replyActionOptions: UNNotificationActionOptions = []
        #else
        let replyActionOptions: UNNotificationActionOptions = [.foreground]
        #endif

        let replyAction = UNTextInputNotificationAction(
            identifier: StudyNotificationAction.reply,
            title: strings.reply,
            options: replyActionOptions,
            textInputButtonTitle: strings.send,
            textInputPlaceholder: strings.answerPlaceholder
        )

        let otherAnswerAction = UNNotificationAction(
            identifier: StudyNotificationAction.otherAnswer,
            title: strings.otherAnswer,
            options: replyActionOptions
        )

        let ignoreAction = UNNotificationAction(
            identifier: StudyNotificationAction.ignore,
            title: strings.ignore,
            options: []
        )

        let category = UNNotificationCategory(
            identifier: StudyNotificationAction.category,
            actions: [replyAction, otherAnswerAction, ignoreAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        center.setNotificationCategories([category])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        #if os(iOS)
        if StudyNotificationPayload.isCloudQuestionPush(
            from: notification.request.content.userInfo
        ) {
            let userInfo = notification.request.content.userInfo
            Task { @MainActor in
                await StudyRemoteNotificationBridge.shared.handleRemoteNotification(
                    userInfo: userInfo,
                    openStudy: false
                )
            }
        } else if StudyNotificationPayload.backendRecordID(
            from: notification.request.content.userInfo
        ) != nil {
            let userInfo = notification.request.content.userInfo
            Task { @MainActor in
                await StudyRemoteNotificationBridge.shared.handleRemoteNotification(
                    userInfo: userInfo,
                    openStudy: false
                )
            }
        }
        #endif

        let presentationOptions: UNNotificationPresentationOptions = notification.request.content.sound == nil
            ? [.banner]
            : [.banner, .sound]
        return presentationOptions
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let actionIdentifier = response.actionIdentifier
        let replyText = (response as? UNTextInputNotificationResponse)?.userText
        let userInfo = response.notification.request.content.userInfo
        let recordID = StudyNotificationPayload.backendRecordID(from: userInfo)
        let questionCreatedAt = StudyNotificationPayload.questionCreatedAt(from: userInfo)

        #if os(iOS)
        if !StudyNotificationRouting.isIgnored(actionIdentifier),
           StudyNotificationRouting.shouldOpenStudyImmediately(
               actionIdentifier: actionIdentifier,
               applicationState: UIApplication.shared.applicationState
           ) {
            Task { @MainActor in
                if let route = StudyNotificationPayload.appRoute(from: userInfo) {
                    StudyNotificationDelegate.shared.enqueueAppRoute(route)
                } else if StudyNotificationPayload.backendRecordID(from: userInfo) != nil {
                    StudyRemoteNotificationBridge.shared.enqueueNotificationResponse(
                        userInfo: userInfo,
                        actionIdentifier: actionIdentifier,
                        replyText: replyText
                    )
                }
            }
            completionHandler()
            return
        }

        if StudyNotificationPayload.isCloudQuestionPush(from: userInfo) {
            Task { @MainActor in
                StudyRemoteNotificationBridge.shared.enqueueNotificationResponse(
                    userInfo: userInfo,
                    actionIdentifier: actionIdentifier,
                    replyText: replyText
                )
            }
            completionHandler()
            return
        }

        Task { @MainActor in
            let shouldOpenStudy = StudyNotificationRouting.shouldOpenStudyImmediately(
                actionIdentifier: actionIdentifier,
                applicationState: UIApplication.shared.applicationState
            )
            StudyNotificationDelegate.shared.enqueueLocalResponse(
                actionIdentifier: actionIdentifier,
                recordID: recordID,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText,
                openStudy: shouldOpenStudy
            )
        }
        completionHandler()
        #else
        Task { @MainActor in
            StudyNotificationDelegate.shared.handle(
                actionIdentifier: actionIdentifier,
                recordID: recordID,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText
            )
        }
        completionHandler()
        #endif
    }

    @MainActor
    func handle(actionIdentifier: String, recordID: String?, questionCreatedAt: TimeInterval?, replyText: String?) {
        guard let appState else {
            pendingLocalResponses.append(
                PendingLocalNotificationResponse(
                    actionIdentifier: actionIdentifier,
                    recordID: recordID,
                    questionCreatedAt: questionCreatedAt,
                    replyText: replyText,
                    openStudy: true
                )
            )
            return
        }

        switch actionIdentifier {
        case StudyNotificationAction.ignore, UNNotificationDismissActionIdentifier:
            appState.statusMessage = "질문을 무시했습니다."
            appState.logRemoteNotificationEvent("알림 응답을 무시했습니다.")

        case StudyNotificationAction.reply:
            let didOpen = appState.openRecordFromNotification(recordID: recordID, questionCreatedAt: questionCreatedAt, replyText: replyText)
            appState.logRemoteNotificationEvent("알림 답장 랜딩 처리: didOpen=\(didOpen), createdAt=\(questionCreatedAt?.description ?? "-")")
            #if os(macOS)
            StudyWindowPresenter.shared.show(appState: appState)
            #endif

        case StudyNotificationAction.otherAnswer:
            let didOpen = appState.openRecordFromNotification(recordID: recordID, questionCreatedAt: questionCreatedAt)
            appState.statusMessage = "다른 응답을 입력하세요."
            appState.logRemoteNotificationEvent("알림 다른 응답 랜딩 처리: didOpen=\(didOpen), createdAt=\(questionCreatedAt?.description ?? "-")")
            #if os(macOS)
            StudyWindowPresenter.shared.show(appState: appState)
            #endif

        case UNNotificationDefaultActionIdentifier:
            let didOpen = appState.openRecordFromNotification(recordID: recordID, questionCreatedAt: questionCreatedAt)
            appState.logRemoteNotificationEvent("알림 기본 탭 랜딩 처리: didOpen=\(didOpen), createdAt=\(questionCreatedAt?.description ?? "-")")
            #if os(macOS)
            StudyWindowPresenter.shared.show(appState: appState)
            #endif

        default:
            let didOpen = appState.openRecordFromNotification(recordID: recordID, questionCreatedAt: questionCreatedAt)
            appState.logRemoteNotificationEvent("알림 action 랜딩 처리: action=\(actionIdentifier), didOpen=\(didOpen), createdAt=\(questionCreatedAt?.description ?? "-")")
            #if os(macOS)
            StudyWindowPresenter.shared.show(appState: appState)
            #endif
        }
    }

    @MainActor
    private func handleQuietly(actionIdentifier: String, recordID: String?, questionCreatedAt: TimeInterval?, replyText: String?) {
        guard let appState else {
            pendingLocalResponses.append(
                PendingLocalNotificationResponse(
                    actionIdentifier: actionIdentifier,
                    recordID: recordID,
                    questionCreatedAt: questionCreatedAt,
                    replyText: replyText,
                    openStudy: false
                )
            )
            return
        }

        switch actionIdentifier {
        case StudyNotificationAction.ignore, UNNotificationDismissActionIdentifier:
            appState.logRemoteNotificationEvent("알림 응답을 조용히 무시했습니다.")

        case StudyNotificationAction.reply:
            let didSave = appState.saveNotificationReplyFromNotification(
                recordID: recordID,
                questionCreatedAt: questionCreatedAt,
                replyText: replyText
            )
            appState.logRemoteNotificationEvent(
                "알림 답장을 조용히 저장했습니다. didSave=\(didSave), createdAt=\(questionCreatedAt?.description ?? "-")"
            )

        default:
            appState.logRemoteNotificationEvent(
                "foreground가 아닌 알림 action을 조용히 처리했습니다. action=\(actionIdentifier), createdAt=\(questionCreatedAt?.description ?? "-")"
            )
        }
    }

}

#if os(iOS)
@MainActor
final class StudyRemoteNotificationBridge {
    static let shared = StudyRemoteNotificationBridge()

    private weak var appState: AppState?
    private var pendingNotifications: [PendingRemoteNotification] = []
    private var pendingDeviceToken: Data?

    private struct PendingRemoteNotification {
        var userInfo: [AnyHashable: Any]
        var openStudy: Bool
        var actionIdentifier: String?
        var replyText: String?
    }

    private init() {}

    func configure(appState: AppState) {
        self.appState = appState
        processPendingNotificationsIfActive()
        processPendingDeviceTokenIfNeeded()
    }

    func enqueueNotificationResponse(
        userInfo: [AnyHashable: Any],
        actionIdentifier: String,
        replyText: String?
    ) {
        guard !StudyNotificationRouting.isIgnored(actionIdentifier) else {
            appState?.logRemoteNotificationEvent(
                "CloudKit push 알림 응답을 무시했습니다. action=\(actionIdentifier)"
            )
            return
        }

        let applicationState = UIApplication.shared.applicationState
        let shouldOpenStudy = StudyNotificationRouting.shouldOpenStudyImmediately(
            actionIdentifier: actionIdentifier,
            applicationState: applicationState
        )

        pendingNotifications.append(
            PendingRemoteNotification(
                userInfo: userInfo,
                openStudy: shouldOpenStudy,
                actionIdentifier: actionIdentifier,
                replyText: replyText
            )
        )
        appState?.logRemoteNotificationEvent(
            "CloudKit push 알림 응답을 큐에 저장했습니다. action=\(actionIdentifier), openStudy=\(shouldOpenStudy), appState=\(Self.applicationStateName(applicationState))"
        )
        processPendingNotificationsIfActive()
    }

    func processPendingNotificationsIfActive() {
        guard UIApplication.shared.applicationState == .active else {
            return
        }

        Task { @MainActor in
            await processPendingNotifications()
        }
    }

    private func processPendingNotifications() async {
        guard appState != nil else {
            return
        }

        let pendingNotifications = pendingNotifications
        self.pendingNotifications.removeAll()
        for notification in pendingNotifications {
            if let actionIdentifier = notification.actionIdentifier {
                await handleNotificationResponse(
                    userInfo: notification.userInfo,
                    actionIdentifier: actionIdentifier,
                    shouldOpenStudy: notification.openStudy,
                    replyText: notification.replyText
                )
            } else if notification.openStudy {
                await handleNotificationTap(
                    userInfo: notification.userInfo,
                    replyText: notification.replyText
                )
            } else {
                await handleRemoteNotification(
                    userInfo: notification.userInfo,
                    openStudy: false,
                    replyText: notification.replyText
                )
            }
        }
    }

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        pendingDeviceToken = deviceToken
        appState?.logRemoteNotificationEvent(
            "iPhone push 등록 성공: tokenBytes=\(deviceToken.count)"
        )
        processPendingDeviceTokenIfNeeded()
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        appState?.logRemoteNotificationEvent(
            "iPhone push 등록 실패: \(error.localizedDescription)",
            isWarning: true
        )
    }

    private func processPendingDeviceTokenIfNeeded() {
        guard let pendingDeviceToken, let appState else {
            return
        }

        self.pendingDeviceToken = nil
        Task { @MainActor in
            await appState.registerRemotePushDeviceToken(pendingDeviceToken)
        }
    }

    @discardableResult
    func handleNotificationResponse(
        userInfo: [AnyHashable: Any],
        actionIdentifier: String,
        replyText: String?
    ) async -> Bool {
        guard !StudyNotificationRouting.isIgnored(actionIdentifier) else {
            appState?.logRemoteNotificationEvent(
                "CloudKit push 알림 응답을 무시했습니다. action=\(actionIdentifier)"
            )
            return false
        }

        let shouldOpenStudy = StudyNotificationRouting.shouldOpenStudyImmediately(
            actionIdentifier: actionIdentifier,
            applicationState: UIApplication.shared.applicationState
        )

        return await handleNotificationResponse(
            userInfo: userInfo,
            actionIdentifier: actionIdentifier,
            shouldOpenStudy: shouldOpenStudy,
            replyText: replyText
        )
    }

    @discardableResult
    private func handleNotificationResponse(
        userInfo: [AnyHashable: Any],
        actionIdentifier: String,
        shouldOpenStudy: Bool,
        replyText: String?
    ) async -> Bool {
        if shouldOpenStudy {
            appState?.logRemoteNotificationEvent(
                "CloudKit push 알림을 명시적으로 열었습니다. action=\(actionIdentifier)"
            )
            return await handleNotificationTap(
                userInfo: userInfo,
                replyText: replyText
            )
        }

        let didHandle = await handleRemoteNotification(
            userInfo: userInfo,
            openStudy: false,
            replyText: replyText
        )
        appState?.logRemoteNotificationEvent(
            "CloudKit push 알림 action을 조용히 처리했습니다. action=\(actionIdentifier), didHandle=\(didHandle)"
        )
        return didHandle
    }

    @discardableResult
    func handleNotificationTap(userInfo: [AnyHashable: Any], replyText: String?) async -> Bool {
        guard let appState else {
            pendingNotifications.append(
                PendingRemoteNotification(
                    userInfo: userInfo,
                    openStudy: true,
                    actionIdentifier: nil,
                    replyText: replyText
                )
            )
            return false
        }

        if let route = StudyNotificationPayload.appRoute(from: userInfo) {
            appState.logRemoteNotificationEvent("Push 딥링크 route를 열었습니다. route=\(route)")
            return appState.openRoute(route)
        }

        appState.prepareToOpenQuestionFromNotification()
        let didHandle = await handleRemoteNotification(
            userInfo: userInfo,
            openStudy: true,
            replyText: replyText
        )

        guard didHandle else {
            let recordID = StudyNotificationPayload.backendRecordID(from: userInfo)
            if let questionCreatedAt = StudyNotificationPayload.questionCreatedAt(from: userInfo) {
                return appState.openRecordFromNotification(
                    recordID: recordID,
                    questionCreatedAt: questionCreatedAt,
                    replyText: replyText
                )
            }

            appState.openRecordFromNotification(recordID: recordID, questionCreatedAt: nil, replyText: replyText)
            appState.logRemoteNotificationEvent(
                "CloudKit push 알림 payload를 라우팅하지 못했습니다. keys=\(StudyNotificationPayload.keySummary(from: userInfo))",
                isWarning: true
            )
            return false
        }

        return true
    }

    @discardableResult
    func handleRemoteNotification(
        userInfo: [AnyHashable: Any],
        openStudy: Bool,
        replyText: String? = nil
    ) async -> Bool {
        guard let appState else {
            pendingNotifications.append(
                PendingRemoteNotification(
                    userInfo: userInfo,
                    openStudy: openStudy,
                    actionIdentifier: nil,
                    replyText: replyText
                )
            )
            return false
        }

        if let recordID = StudyNotificationPayload.backendRecordID(from: userInfo) {
            return await appState.handleBackendRecordPush(
                recordID: recordID,
                openStudy: openStudy,
                replyText: replyText
            )
        }

        guard let recordName = Self.cloudQuestionPushRecordName(from: userInfo) else {
            if openStudy {
                appState.openRecordFromNotification(questionCreatedAt: nil, replyText: replyText)
            }
            appState.logRemoteNotificationEvent(
                "CloudKit push recordName을 찾지 못했습니다. keys=\(StudyNotificationPayload.keySummary(from: userInfo))",
                isWarning: openStudy
            )
            return false
        }

        return await appState.handleCloudQuestionPush(
            recordName: recordName,
            openStudy: openStudy,
            replyText: replyText
        )
    }

    nonisolated static func cloudQuestionPushRecordName(from userInfo: [AnyHashable: Any]) -> String? {
        StudyNotificationPayload.cloudQuestionPushRecordName(from: userInfo)
    }

    private nonisolated static func applicationStateName(_ state: UIApplication.State) -> String {
        switch state {
        case .active:
            return "active"
        case .inactive:
            return "inactive"
        case .background:
            return "background"
        @unknown default:
            return "unknown"
        }
    }
}
#endif

#if os(macOS)
@MainActor
final class StudyWindowPresenter {
    static let shared = StudyWindowPresenter()

    private var window: NSWindow?

    private init() {}

    func show(appState: AppState) {
        let targetScreen = Self.targetScreen(for: window)

        if window == nil {
            window = NSApp.windows.first {
                $0.title == "AI Teacher" || $0.title == "AI 선생님"
            }
        }

        if window == nil {
            let rootView = RootView()
                .environmentObject(appState)
                .frame(width: 560, height: 700)

            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 560, height: 700),
                styleMask: [.titled, .closable, .miniaturizable],
                backing: .buffered,
                defer: false
            )
            window.title = "AI Teacher"
            window.isReleasedWhenClosed = false
            window.minSize = NSSize(width: 560, height: 700)
            window.maxSize = NSSize(width: 560, height: 700)
            window.contentViewController = NSHostingController(rootView: rootView)
            self.window = window
        }

        if let window {
            prepareForActiveDesktop(window)
            move(window, to: targetScreen)
            window.deminiaturize(nil)
            window.orderFrontRegardless()
        }

        NSApp.activate(ignoringOtherApps: true)
        window?.level = .floating
        window?.makeKeyAndOrderFront(nil)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            self?.window?.level = .normal
        }
    }

    private func prepareForActiveDesktop(_ window: NSWindow) {
        window.collectionBehavior.insert(.moveToActiveSpace)
    }

    private func move(_ window: NSWindow, to screen: NSScreen) {
        let visibleFrame = screen.visibleFrame
        let size = window.frame.size
        let width = min(size.width, visibleFrame.width)
        let height = min(size.height, visibleFrame.height)
        let origin = NSPoint(
            x: visibleFrame.midX - width / 2,
            y: visibleFrame.midY - height / 2
        )

        window.setFrame(NSRect(origin: origin, size: NSSize(width: width, height: height)), display: true)
    }

    private static func targetScreen(for window: NSWindow?) -> NSScreen {
        if let screen = screenContainingMouse() {
            return screen
        }

        guard let screen = window?.screen ?? NSScreen.main ?? NSScreen.screens.first else {
            preconditionFailure("StudyMate requires at least one display.")
        }

        return screen
    }

    private static func screenContainingMouse() -> NSScreen? {
        let mouseLocation = NSEvent.mouseLocation
        return NSScreen.screens.first { screen in
            screen.frame.contains(mouseLocation)
        }
    }
}
#endif
