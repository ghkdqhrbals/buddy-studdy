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
                StartupPixelFoxSplashView {
                    isShowingStartupSplash = false
                }
                .transition(.opacity)
                .zIndex(1)
            }

            #if DEBUG
            if let appState {
                FloatingAPIDebugOverlay()
                    .environmentObject(appState)
                    .zIndex(2)
            }
            #endif
        }
        .animation(.easeOut(duration: 0.25), value: isShowingStartupSplash)
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

private struct StartupPixelFoxSplashView: View {
    var onFinished: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.02, green: 0.35, blue: 0.95)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                Image("PixelFoxBackpackMascot")
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 220, height: 220)
                    .accessibilityHidden(true)

                Text("BuddyStudy @ghkdqhrbals")
                    .font(.system(size: 18, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .monospaced()
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityLabel("BuddyStudy by ghkdqhrbals")
            }
            .padding(.horizontal, 24)
        }
        .task {
            try? await Task.sleep(for: .seconds(1))
            onFinished()
        }
    }
}

#if DEBUG
private struct FloatingAPIDebugOverlay: View {
    @EnvironmentObject private var appState: AppState
    @State private var isVisible = true
    @State private var isExpanded = false
    @State private var committedOffset = CGSize(width: 12, height: 74)
    @GestureState private var dragTranslation: CGSize = .zero

    private var latestLog: APITrafficLogEntry? {
        appState.apiTrafficLogs.first
    }

    var body: some View {
        GeometryReader { geometry in
            content
                .frame(width: panelWidth(for: geometry.size), alignment: .leading)
                .frame(maxHeight: panelMaxHeight(for: geometry.size))
                .offset(
                    x: displayOffset(for: geometry.size).width,
                    y: displayOffset(for: geometry.size).height
                )
                .simultaneousGesture(dragGesture(in: geometry.size))
                .animation(.smooth(duration: 0.18), value: isExpanded)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .allowsHitTesting(true)
                .onChange(of: isExpanded) {
                    withAnimation(.smooth(duration: 0.18)) {
                        committedOffset = boundedOffset(for: geometry.size)
                    }
                }
                .onChange(of: geometry.size) {
                    committedOffset = boundedOffset(for: geometry.size)
                }
        }
        .ignoresSafeArea(.keyboard)
    }

    @ViewBuilder
    private var content: some View {
        if isVisible {
            panel
        } else {
            Button {
                isVisible = true
            } label: {
                HStack(spacing: 6) {
                    Text("API")
                        .font(.caption.weight(.bold))
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(statusColor, in: Capsule())
                .shadow(color: .black.opacity(0.18), radius: 10, y: 5)
            }
            .buttonStyle(.plain)
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    isExpanded.toggle()
                } label: {
                    Text("API")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .background(statusColor, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                }
                .buttonStyle(.plain)

                Button {
                    isExpanded.toggle()
                } label: {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(latestTitle)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)

                            Text(latestSubtitle)
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: 4)

                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button {
                    isExpanded = false
                    isVisible = false
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            if isExpanded {
                Divider()

                if let latestLog {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            debugSection(title: "Request", value: requestText(for: latestLog))
                            debugSection(title: "Response", value: responseText(for: latestLog))

                            if let error = latestLog.error, !error.isEmpty {
                                debugSection(title: "Error", value: error, isError: true)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 280)
                } else {
                    Text("아직 API 요청이 없습니다.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.primary.opacity(0.12), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.18), radius: 12, y: 6)
    }

    private func debugSection(title: String, value: String, isError: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption2.weight(.bold))
                .foregroundStyle(.secondary)

            Text(value.isEmpty ? "-" : value)
                .font(.caption2.monospaced())
                .foregroundStyle(isError ? .red : .primary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func panelWidth(for size: CGSize) -> CGFloat {
        guard isVisible else {
            return 64
        }
        return min(max(size.width - 24, 220), isExpanded ? 380 : 300)
    }

    private func panelMaxHeight(for size: CGSize) -> CGFloat {
        guard isVisible else {
            return 36
        }
        return min(isExpanded ? 360 : 64, max(80, size.height - 24))
    }

    private func panelEstimatedHeight(for size: CGSize) -> CGFloat {
        guard isVisible else {
            return 36
        }
        return min(isExpanded ? 360 : 64, panelMaxHeight(for: size))
    }

    private func boundedOffset(for size: CGSize, proposed proposedOffset: CGSize? = nil) -> CGSize {
        let offset = proposedOffset ?? committedOffset
        let margin: CGFloat = 12
        let panelWidth = panelWidth(for: size)
        let panelHeight = panelEstimatedHeight(for: size)
        let maxX = max(margin, size.width - panelWidth - margin)
        let maxY = max(margin, size.height - panelHeight - margin)

        return CGSize(
            width: min(max(offset.width, margin), maxX),
            height: min(max(offset.height, margin), maxY)
        )
    }

    private func displayOffset(for size: CGSize) -> CGSize {
        let baseOffset = boundedOffset(for: size)
        let proposedOffset = CGSize(
            width: baseOffset.width + dragTranslation.width,
            height: baseOffset.height + dragTranslation.height
        )
        return boundedOffset(for: size, proposed: proposedOffset)
    }

    private func dragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 3, coordinateSpace: .global)
            .updating($dragTranslation) { value, state, transaction in
                transaction.disablesAnimations = true
                state = value.translation
            }
            .onEnded { value in
                let startOffset = boundedOffset(for: size)
                let proposedOffset = CGSize(
                    width: startOffset.width + value.translation.width,
                    height: startOffset.height + value.translation.height
                )

                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    committedOffset = boundedOffset(for: size, proposed: proposedOffset)
                }
            }
    }

    private var latestTitle: String {
        guard let latestLog else {
            return "대기 중"
        }

        return "\(latestLog.method) \(shortURL(latestLog.url))"
    }

    private var latestSubtitle: String {
        guard let latestLog else {
            return "최근 요청/응답 없음"
        }

        let status = latestLog.statusCode.map(String.init) ?? "pending"
        return "\(status) · \(latestLog.durationText)"
    }

    private var statusColor: Color {
        guard let latestLog else {
            return .secondary
        }

        if latestLog.isError {
            return .red
        }

        guard let statusCode = latestLog.statusCode else {
            return .orange
        }

        switch statusCode {
        case 200..<300:
            return .green
        case 400..<600:
            return .red
        default:
            return .orange
        }
    }

    private func requestText(for entry: APITrafficLogEntry) -> String {
        [
            "\(entry.method) \(entry.url)",
            entry.requestBody.isEmpty ? "" : entry.requestBody,
        ]
        .filter { !$0.isEmpty }
        .joined(separator: "\n")
    }

    private func responseText(for entry: APITrafficLogEntry) -> String {
        let status = entry.statusCode.map { "HTTP \($0)" } ?? "HTTP -"
        return [
            "\(status) · \(entry.durationText)",
            entry.responseBody,
        ]
        .filter { !$0.isEmpty }
        .joined(separator: "\n")
    }

    private func shortURL(_ value: String) -> String {
        guard let url = URL(string: value) else {
            return value
        }

        if let query = url.query, !query.isEmpty {
            return "\(url.path)?\(query)"
        }

        return url.path.isEmpty ? value : url.path
    }
}
#endif

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
