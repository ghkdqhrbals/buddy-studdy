import SwiftUI
import UIKit
import BackgroundTasks

@main
@MainActor
struct StudyMateiOSApp: App {
    @UIApplicationDelegateAdaptor(StudyMateiOSAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var appState: AppState?

    var body: some Scene {
        WindowGroup {
            StudyMateiOSBootstrapView(appState: $appState)
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
                StudyMateBackgroundRefreshBridge.shared.schedule()
                Task {
                    await appState.prepareBackgroundQuestionNotifications()
                }
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
    @State private var bootstrapError: String?

    var body: some View {
        Group {
            if let appState {
                MobileRootView()
                    .environmentObject(appState)
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("BuddyStuddy")
                        .font(.headline)
                    if let bootstrapError {
                        Text(bootstrapError)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemBackground))
            }
        }
        .task {
            guard !didBootstrap else {
                return
            }

            didBootstrap = true
            bootstrapError = "Preparing BuddyStuddy..."
            bootstrapError = "Loading settings..."
            let state = AppState()
            bootstrapError = "Preparing notifications..."
            StudyNotificationDelegate.shared.configure(appState: state)
            StudyRemoteNotificationBridge.shared.configure(appState: state)
            StudyMateBackgroundRefreshBridge.shared.configure(appState: state)
            appState = state
            bootstrapError = nil
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
        guard isRegistered else {
            appState?.logRemoteNotificationEvent(
                "iPhone background refresh 등록 전이라 예약을 건너뛰었습니다.",
                isWarning: true
            )
            return
        }

        let request = BGAppRefreshTaskRequest(identifier: Self.identifier)
        let minimumWakeUpDate = Date(timeIntervalSinceNow: 60)
        let requestedWakeUpDate = appState?.backgroundRefreshEarliestBeginDate() ?? Date(timeIntervalSinceNow: 15 * 60)
        request.earliestBeginDate = max(minimumWakeUpDate, requestedWakeUpDate)

        do {
            try BGTaskScheduler.shared.submit(request)
            appState?.logRemoteNotificationEvent("iPhone background refresh를 예약했습니다: \(request.earliestBeginDate?.description ?? "-")")
        } catch {
            appState?.logRemoteNotificationEvent(
                "iPhone background refresh 예약 실패: \(error.localizedDescription)",
                isWarning: true
            )
        }
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
        schedule()

        let worker = Task { @MainActor in
            await appState?.handleBackgroundRefresh() ?? false
        }

        task.expirationHandler = {
            worker.cancel()
        }

        let didUpdate = await worker.value
        task.setTaskCompleted(success: didUpdate)
    }
}
