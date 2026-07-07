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
        ZStack {
            if let appState {
                MobileRootView()
                    .environmentObject(appState)
            } else {
                Color(.systemBackground)
            }

            #if DEBUG
            if let appState {
                FloatingAPIDebugOverlay()
                    .environmentObject(appState)
                    .zIndex(2)
            }
            #endif
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

#if DEBUG
private struct FloatingAPIDebugOverlay: View {
    @EnvironmentObject private var appState: AppState
    @State private var isVisible = true
    @State private var isExpanded = false
    @State private var committedOffset = CGSize(width: 12, height: 74)
    @State private var suppressTapAction = false
    @State private var selectedLogID: APITrafficLogEntry.ID?
    @GestureState private var dragTranslation: CGSize = .zero

    private var recentLogs: [APITrafficLogEntry] {
        Array(appState.apiTrafficLogs.prefix(100))
    }

    private var selectedLog: APITrafficLogEntry? {
        if let selectedLogID,
           let selectedLog = recentLogs.first(where: { $0.id == selectedLogID }) {
            return selectedLog
        }

        return recentLogs.first
    }

    private var latestLog: APITrafficLogEntry? {
        appState.apiTrafficLogs.first
    }

    var body: some View {
        GeometryReader { geometry in
            content(for: geometry.size)
                .frame(width: panelWidth(for: geometry.size), alignment: .leading)
                .frame(maxHeight: panelMaxHeight(for: geometry.size))
                .offset(
                    x: displayOffset(for: geometry.size).width,
                    y: displayOffset(for: geometry.size).height
                )
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
    private func content(for size: CGSize) -> some View {
        if isVisible {
            panel(in: size)
        } else {
            Button {
                runTapAction {
                    isVisible = true
                }
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
            .simultaneousGesture(dragGesture(in: size))
        }
    }

    private func panel(in size: CGSize) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    runTapAction {
                        isExpanded.toggle()
                    }
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
                    runTapAction {
                        isExpanded.toggle()
                    }
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
                    runTapAction {
                        isExpanded = false
                        isVisible = false
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .contentShape(Rectangle())
            .simultaneousGesture(dragGesture(in: size))

            if isExpanded {
                Divider()

                if recentLogs.isEmpty {
                    Text("아직 API 요청이 없습니다.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Recent API")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.secondary)

                            Spacer()

                            Text("\(recentLogs.count)/100")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }

                        ScrollView {
                            VStack(alignment: .leading, spacing: apiLogRowSpacing) {
                                ForEach(recentLogs) { log in
                                    Button {
                                        selectedLogID = log.id
                                    } label: {
                                        apiLogRow(log, isSelected: selectedLog?.id == log.id)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(height: apiLogListHeight, alignment: .top)

                        if let selectedLog {
                            Divider()

                            ScrollView {
                                VStack(alignment: .leading, spacing: 8) {
                                    debugSection(title: "Request", value: requestText(for: selectedLog))
                                    debugSection(title: "Response", value: responseText(for: selectedLog))

                                    if let error = selectedLog.error, !error.isEmpty {
                                        debugSection(title: "Error", value: error, isError: true)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .frame(maxHeight: 220)
                        }
                    }
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

    private func apiLogRow(_ entry: APITrafficLogEntry, isSelected: Bool) -> some View {
        HStack(spacing: 7) {
            Text(entry.method)
                .font(.caption2.weight(.bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(statusColor(for: entry), in: RoundedRectangle(cornerRadius: 6, style: .continuous))

            VStack(alignment: .leading, spacing: 1) {
                Text(shortURL(entry.url))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Text("\(entry.statusCode.map(String.init) ?? "pending") · \(entry.durationText) · \(entry.createdAt.formatted(date: .omitted, time: .standard))")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.16) : Color.primary.opacity(0.04))
        )
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(isSelected ? Color.accentColor.opacity(0.35) : Color.clear, lineWidth: 1)
        }
        .frame(height: apiLogRowHeight)
        .contentShape(Rectangle())
    }

    private var apiLogListHeight: CGFloat {
        guard !recentLogs.isEmpty else {
            return 0
        }

        let visibleRows = min(recentLogs.count, 3)
        let contentHeight = (CGFloat(visibleRows) * apiLogRowHeight)
            + (CGFloat(max(0, visibleRows - 1)) * apiLogRowSpacing)
        return min(apiLogListMaxHeight, contentHeight)
    }

    private var apiLogRowHeight: CGFloat {
        54
    }

    private var apiLogRowSpacing: CGFloat {
        6
    }

    private var apiLogListMaxHeight: CGFloat {
        (apiLogRowHeight * 3) + (apiLogRowSpacing * 2)
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
        return min(isExpanded ? 430 : 64, max(80, size.height - 24))
    }

    private func panelEstimatedHeight(for size: CGSize) -> CGFloat {
        guard isVisible else {
            return 36
        }
        return min(isExpanded ? 430 : 64, panelMaxHeight(for: size))
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
            .onChanged { value in
                if dragDistance(value.translation) > 6 {
                    suppressTapAction = true
                }
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
                if suppressTapAction {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                        suppressTapAction = false
                    }
                }
            }
    }

    private func runTapAction(_ action: () -> Void) {
        guard !suppressTapAction else {
            return
        }
        action()
    }

    private func dragDistance(_ translation: CGSize) -> CGFloat {
        hypot(translation.width, translation.height)
    }

    private var latestTitle: String {
        guard let selectedLog = selectedLog ?? latestLog else {
            return "대기 중"
        }

        return "\(selectedLog.method) \(shortURL(selectedLog.url))"
    }

    private var latestSubtitle: String {
        guard let selectedLog = selectedLog ?? latestLog else {
            return "최근 요청/응답 없음"
        }

        let status = selectedLog.statusCode.map(String.init) ?? "pending"
        return "\(status) · \(selectedLog.durationText) · \(recentLogs.count) logs"
    }

    private var statusColor: Color {
        guard let selectedLog = selectedLog ?? latestLog else {
            return .secondary
        }

        return statusColor(for: selectedLog)
    }

    private func statusColor(for entry: APITrafficLogEntry) -> Color {
        if entry.isError {
            return .red
        }

        guard let statusCode = entry.statusCode else {
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
