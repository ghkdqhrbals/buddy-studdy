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
    @State private var bootstrapError: String?
    @State private var isShowingStartupSplash = true

    var body: some View {
        ZStack {
            if let appState {
                MobileRootView()
                    .environmentObject(appState)
            } else {
                Color(.systemBackground)
            }

            if isShowingStartupSplash {
                BuddyStuddyStartupSplashView(message: bootstrapError)
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .background(Color(.systemBackground))
        .task {
            guard !didBootstrap else {
                return
            }

            didBootstrap = true
            let minimumSplashTask = Task {
                try? await Task.sleep(for: .milliseconds(3_000))
            }

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
            await minimumSplashTask.value

            withAnimation(.easeOut(duration: 0.28)) {
                isShowingStartupSplash = false
            }
        }
    }
}

private struct BuddyStuddyStartupSplashView: View {
    var message: String?
    @State private var sceneIndex = 0

    private let sceneDuration: Duration = .milliseconds(750)
    private let transitionDuration = 0.3

    var body: some View {
        GeometryReader { geometry in
            let scene = FoxStartupScene.scenes[sceneIndex]
            let iconSize = min(max(geometry.size.width * 0.34, 132), 188)

            ZStack {
                Color(red: 1.0, green: 0.992, blue: 0.972)
                    .ignoresSafeArea()

                VStack(spacing: 18) {
                    Spacer()

                    ZStack {
                        BuddyStuddyStartupSceneView(scene: scene, iconSize: iconSize)
                            .id(sceneIndex)
                            .transition(.opacity)
                    }
                    .frame(height: iconSize + 36)
                    .animation(.easeInOut(duration: transitionDuration), value: sceneIndex)

                    if let message {
                        Text(message)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(.secondary)
                            .contentTransition(.opacity)
                    }

                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal, 24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: sceneDuration)
                guard !Task.isCancelled else {
                    return
                }

                withAnimation(.easeInOut(duration: transitionDuration)) {
                    sceneIndex = (sceneIndex + 1) % FoxStartupScene.scenes.count
                }
            }
        }
    }
}

private struct BuddyStuddyStartupSceneView: View {
    var scene: FoxStartupScene
    var iconSize: CGFloat

    var body: some View {
        GeometryReader { _ in
            ZStack(alignment: .bottom) {
                Capsule()
                    .fill(.black.opacity(0.10))
                    .frame(
                        width: iconSize * scene.shadowWidthRatio,
                        height: 13
                    )
                    .blur(radius: 2)
                    .offset(y: iconSize * 0.43)

                Image("SplashFox")
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: iconSize, height: iconSize)
                    .rotationEffect(.degrees(scene.rotationDegrees))
                    .offset(y: -scene.lift)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct FoxStartupScene {
    var lift: CGFloat
    var rotationDegrees: Double
    var shadowWidthRatio: CGFloat

    static let scenes = [
        FoxStartupScene(lift: 0, rotationDegrees: -2.4, shadowWidthRatio: 0.55),
        FoxStartupScene(lift: 10, rotationDegrees: 2.8, shadowWidthRatio: 0.61),
        FoxStartupScene(lift: 0, rotationDegrees: -1.8, shadowWidthRatio: 0.55),
        FoxStartupScene(lift: 10, rotationDegrees: 2.2, shadowWidthRatio: 0.61),
    ]
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
