import SwiftUI
import UIKit
import BackgroundTasks

@main
@MainActor
struct StudyMateiOSApp: App {
    @UIApplicationDelegateAdaptor(StudyMateiOSAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var appState: AppState?
    @State private var pendingDeepLinkURLs: [URL] = []

    var body: some Scene {
        WindowGroup {
            StudyMateiOSBootstrapView(appState: $appState)
                .onOpenURL { url in
                    guard let appState else {
                        pendingDeepLinkURLs.append(url)
                        return
                    }

                    appState.openDeepLink(url)
                }
                .onChange(of: appState != nil) { _, isReady in
                    guard isReady, let appState else {
                        return
                    }

                    let urls = pendingDeepLinkURLs
                    pendingDeepLinkURLs.removeAll()
                    for url in urls {
                        appState.openDeepLink(url)
                    }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            guard let appState else {
                return
            }

            switch phase {
            case .active:
                Task {
                    StudyNotificationDelegate.shared.processPendingLocalResponsesIfActive()
                    StudyRemoteNotificationBridge.shared.processPendingNotificationsIfActive()
                    await appState.handleAppBecameActive()
                    StudyNotificationDelegate.shared.processPendingLocalResponsesIfActive()
                    StudyRemoteNotificationBridge.shared.processPendingNotificationsIfActive()
                }
            case .background:
                appState.logRemoteNotificationEvent("iOS background 진입: 서버/APNs 스케줄러가 예약 질문을 담당하므로 앱 내부 background 작업은 시작하지 않습니다.")
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }
}

private struct StudyMateiOSBootstrapView: View {
    @Binding var appState: AppState?
    @State private var didBootstrap = false

    var body: some View {
        Group {
            if let appState {
                MobileRootView()
                    .environmentObject(appState)
            } else {
                Color(.systemBackground)
            }
        }
        .background(Color(.systemBackground))
        .task {
            guard !didBootstrap else {
                return
            }

            didBootstrap = true
            let state = AppState()
            StudyNotificationDelegate.shared.configure(appState: state)
            StudyRemoteNotificationBridge.shared.configure(appState: state)
            StudyMateBackgroundRefreshBridge.shared.configure(appState: state)
            appState = state
            await state.start()
        }
    }
}

final class StudyMateiOSAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        StudyMateBackgroundRefreshBridge.shared.register()
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task { @MainActor in
            StudyRemoteNotificationBridge.shared.didRegisterForRemoteNotifications(deviceToken: deviceToken)
        }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task { @MainActor in
            StudyRemoteNotificationBridge.shared.didFailToRegisterForRemoteNotifications(error: error)
        }
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Task { @MainActor in
            let didUpdate = await StudyRemoteNotificationBridge.shared.handleRemoteNotification(
                userInfo: userInfo,
                openStudy: false
            )
            completionHandler(didUpdate ? .newData : .noData)
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        Task { @MainActor in
            StudyMateBackgroundRefreshBridge.shared.schedule()
        }
    }
}

final class StudyMateBackgroundRefreshBridge: @unchecked Sendable {
    static let shared = StudyMateBackgroundRefreshBridge()
    static let identifier = "io.github.ghkdqhrbals.StudyMate.refresh"

    private struct RefreshTaskBox: @unchecked Sendable {
        let task: BGAppRefreshTask
    }

    @MainActor
    private weak var appState: AppState?
    private let lock = NSLock()
    private var didRegister = false

    private init() {}

    @MainActor
    func configure(appState: AppState) {
        self.appState = appState
    }

    func register() {
        lock.lock()
        if didRegister {
            lock.unlock()
            return
        }
        didRegister = true
        lock.unlock()

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.identifier,
            using: nil
        ) { task in
            Self.handleRegisteredTask(task)
        }
    }

    @MainActor
    func schedule() {
        appState?.logRemoteNotificationEvent("iPhone background refresh는 비활성화되어 있습니다. 예약 질문은 백엔드 스케줄러와 APNs만 담당합니다.")
    }

    private var isRegistered: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }
        return didRegister
    }

    private static func handleRegisteredTask(_ task: BGTask) {
        guard let refreshTask = task as? BGAppRefreshTask else {
            task.setTaskCompleted(success: false)
            return
        }

        let taskBox = RefreshTaskBox(task: refreshTask)
        Task { @MainActor in
            await shared.handle(taskBox: taskBox)
        }
    }

    @MainActor
    private func handle(taskBox: RefreshTaskBox) async {
        let task = taskBox.task
        appState?.logRemoteNotificationEvent("iPhone background refresh 실행을 무시했습니다. 서버/APNs 스케줄러가 예약 질문을 담당합니다.")
        task.setTaskCompleted(success: true)
    }
}
