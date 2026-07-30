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

    private var forcesMaintenancePreview: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["BUDDYSTUDY_DEBUG_MAINTENANCE"] == "1"
        #else
        false
        #endif
    }

    var body: some View {
        ZStack {
            if let appState {
                StudyMateiOSRootContent(
                    appState: appState,
                    forcesMaintenancePreview: forcesMaintenancePreview
                )
            } else {
                Color(.systemBackground)
            }

            if let appState {
                FloatingDebugLogOverlay()
                    .environmentObject(appState)
                    .zIndex(2)
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

private struct StudyMateiOSRootContent: View {
    @ObservedObject var appState: AppState
    let forcesMaintenancePreview: Bool

    var body: some View {
        ZStack {
            Group {
                if (forcesMaintenancePreview || appState.isServiceUnderMaintenance)
                    && !appState.isMaintenanceBypassedForDeveloper {
                    ServiceMaintenanceView()
                } else {
                    MobileRootView()
                }
            }

            if let decision = appState.appUpdateDecision {
                AppUpdatePromptView(decision: decision)
                    .transition(.opacity.combined(with: .scale(scale: 0.97)))
                    .zIndex(10)
            }
        }
        .environmentObject(appState)
        .animation(.easeOut(duration: 0.2), value: appState.appUpdateDecision?.campaignID)
    }
}

private struct AppUpdatePromptView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.openURL) private var openURL
    let decision: BackendAppUpdateDecision

    var body: some View {
        ZStack {
            Color.black.opacity(0.52)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            VStack(spacing: 0) {
                Image(systemName: "arrow.down.app.fill")
                    .font(.system(size: 31, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 62, height: 62)
                    .background(Color.accentColor.opacity(0.12), in: Circle())

                Text(decision.title?.nonEmpty ?? defaultTitle)
                    .font(.system(size: 22, weight: .bold))
                    .multilineTextAlignment(.center)
                    .padding(.top, 20)

                Text(decision.message?.nonEmpty ?? defaultMessage)
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .padding(.top, 10)

                if let targetVersion = decision.targetVersion?.nonEmpty {
                    Text(versionLabel(targetVersion, build: decision.targetBuild))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .padding(.top, 12)
                }

                Button {
                    appState.recordAppStoreOpened()
                    if let value = decision.appStoreURL,
                       let url = URL(string: value) {
                        openURL(url)
                    }
                } label: {
                    Text(appState.strings.updateNow)
                        .font(.system(size: 16, weight: .bold))
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 16))
                .padding(.top, 24)

                if !decision.isForced {
                    Button(appState.strings.updateLater) {
                        appState.dismissOptionalAppUpdate()
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 15)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
            .frame(maxWidth: 360)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(Color.primary.opacity(0.08), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.22), radius: 28, y: 14)
            .padding(.horizontal, 24)
            .accessibilityAddTraits(.isModal)
        }
    }

    private var defaultTitle: String {
        switch appState.settings.appLanguage {
        case .korean: "새 버전이 준비됐어요"
        case .english: "A new version is ready"
        case .japanese: "新しいバージョンがあります"
        }
    }

    private var defaultMessage: String {
        switch appState.settings.appLanguage {
        case .korean: "더 안정적인 학습 경험을 위해 앱을 업데이트해 주세요."
        case .english: "Update the app for a more reliable study experience."
        case .japanese: "より安定した学習体験のため、アプリをアップデートしてください。"
        }
    }

    private func versionLabel(_ version: String, build: String?) -> String {
        let suffix = build?.nonEmpty.map { " (\($0))" } ?? ""
        return "v\(version)\(suffix)"
    }
}

private struct ServiceMaintenanceView: View {
    @EnvironmentObject private var appState: AppState
    @State private var hiddenTapCount = 0
    @State private var hiddenTapWindowStartedAt: Date?
    @State private var showsDeveloperCodeEntry = false

    var body: some View {
        let strings = appState.strings
        let availability = appState.serviceAvailability

        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer(minLength: 80)

                Image("BuddyStudyBrandLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 88, height: 88)
                    .clipShape(RoundedRectangle(cornerRadius: 19, style: .continuous))
                    .accessibilityHidden(true)

                VStack(spacing: 12) {
                    Text(availability.title?.nonEmpty ?? strings.maintenanceDefaultTitle)
                        .font(.system(size: 25, weight: .bold))
                        .multilineTextAlignment(.center)

                    Text(availability.message?.nonEmpty ?? strings.maintenanceDefaultMessage)
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(4)
                        .frame(maxWidth: 330)
                }
                .padding(.top, 28)
                .contentShape(Rectangle())
                .onTapGesture {
                    registerHiddenDeveloperTap()
                }

                if let endsAt = availability.endsAt {
                    VStack(spacing: 6) {
                        Text(strings.maintenancePlannedEnd)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(endsAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.system(size: 16, weight: .semibold))
                    }
                    .padding(.top, 28)
                } else {
                    Text(strings.maintenanceNoPlannedEnd)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 24)
                }

                Spacer()

                Button {
                    Task {
                        await appState.refreshServiceAvailability()
                    }
                } label: {
                    HStack(spacing: 8) {
                        if appState.isCheckingServiceAvailability {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                        Text(
                            appState.isCheckingServiceAvailability
                                ? strings.maintenanceChecking
                                : strings.maintenanceRetry
                        )
                    }
                    .font(.system(size: 16, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .foregroundStyle(.white)
                    .background(Color.accentColor)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(appState.isCheckingServiceAvailability)
                .padding(.horizontal, 24)
                .padding(.bottom, 30)
            }
        }
        .sheet(isPresented: $showsDeveloperCodeEntry) {
            MaintenanceDeveloperAccessSheet()
                .environmentObject(appState)
        }
    }

    private func registerHiddenDeveloperTap() {
        let now = Date()
        if let startedAt = hiddenTapWindowStartedAt,
           now.timeIntervalSince(startedAt) <= 2 {
            hiddenTapCount += 1
        } else {
            hiddenTapWindowStartedAt = now
            hiddenTapCount = 1
        }

        guard hiddenTapCount >= 5 else {
            return
        }
        hiddenTapCount = 0
        hiddenTapWindowStartedAt = nil
        showsDeveloperCodeEntry = true
    }
}

private struct MaintenanceDeveloperAccessSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var hasSubmitted = false
    @FocusState private var isCodeFieldFocused: Bool

    var body: some View {
        let strings = appState.strings

        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "wrench.and.screwdriver.fill")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)

                Text(strings.maintenanceDeveloperAccessHelp)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                TextField(
                    strings.promotionCodePlaceholder,
                    text: $code
                )
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.characters)
                .keyboardType(.asciiCapable)
                .autocorrectionDisabled()
                .textContentType(.oneTimeCode)
                .submitLabel(.done)
                .focused($isCodeFieldFocused)
                .onChange(of: code) { _, newValue in
                    let formatted = DeveloperPromotionCodeVerifier.formattedInput(newValue)
                    if formatted != code {
                        code = formatted
                    }
                    hasSubmitted = false
                }
                .onSubmit {
                    submitCode()
                }
                .padding(.horizontal, 14)
                .frame(height: 50)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                if hasSubmitted,
                   let message = appState.promotionCodeMessage {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(appState.hasPromotionCodeError ? .red : .green)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Button {
                    submitCode()
                } label: {
                    HStack(spacing: 8) {
                        if appState.isRedeemingPromotionCode {
                            ProgressView()
                                .tint(.white)
                        }
                        Text(strings.applyPromotionCode)
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                }
                .buttonStyle(.borderedProminent)
                .disabled(code.count != 19 || appState.isRedeemingPromotionCode)

                Spacer(minLength: 0)
            }
            .padding(24)
            .navigationTitle(strings.maintenanceDeveloperAccessTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.height(340)])
        .presentationDragIndicator(.visible)
        .onAppear {
            isCodeFieldFocused = true
        }
    }

    private func submitCode() {
        guard code.count == 19, !appState.isRedeemingPromotionCode else {
            return
        }
        hasSubmitted = true
        Task {
            guard await appState.redeemDeveloperPromotionCode(code) else {
                return
            }
            await appState.bypassMaintenanceForDeveloper()
            dismiss()
        }
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private enum DebugLogTab: String, CaseIterable, Identifiable {
    case app = "APP"
    case api = "API"

    var id: String { rawValue }
}

private struct FloatingDebugLogOverlay: View {
    @EnvironmentObject private var appState: AppState
    @State private var isExpanded = false
    @State private var committedOffset = CGSize(width: 12, height: 74)
    @State private var suppressTapAction = false
    @State private var selectedLogTab = DebugLogTab.app
    @State private var selectedAPILogID: APITrafficLogEntry.ID?
    @State private var selectedAppLogID: AppLogEntry.ID?
    @GestureState private var dragTranslation: CGSize = .zero

    private var strings: AppStrings {
        AppStrings(language: appState.settings.appLanguage)
    }

    private var recentAPILogs: [APITrafficLogEntry] {
        Array(appState.apiTrafficLogs.prefix(100))
    }

    private var recentAppLogs: [AppLogEntry] {
        Array(appState.appLogs.prefix(50))
    }

    private var selectedAPILog: APITrafficLogEntry? {
        if let selectedAPILogID,
           let selectedLog = recentAPILogs.first(where: { $0.id == selectedAPILogID }) {
            return selectedLog
        }

        return recentAPILogs.first
    }

    private var selectedAppLog: AppLogEntry? {
        if let selectedAppLogID,
           let selectedLog = recentAppLogs.first(where: { $0.id == selectedAppLogID }) {
            return selectedLog
        }

        return recentAppLogs.first
    }

    private var latestAPILog: APITrafficLogEntry? {
        appState.apiTrafficLogs.first
    }

    private var latestAppLog: AppLogEntry? {
        appState.appLogs.first
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
        if appState.isAPIDebugPanelPresented {
            panel(in: size)
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
                    Text(selectedLogTab.rawValue)
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

                if isExpanded {
                    Button {
                        runTapAction {
                            selectedAPILogID = nil
                            selectedAppLogID = nil
                            appState.resetDebugLogs()
                        }
                    } label: {
                        Image(systemName: "trash")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.red)
                            .frame(width: 24, height: 24)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(strings.resetDebugLogs)
                }

                Button {
                    runTapAction {
                        isExpanded = false
                        appState.isAPIDebugPanelPresented = false
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
                Picker("Log type", selection: $selectedLogTab) {
                    ForEach(DebugLogTab.allCases) { tab in
                        Text(tab.rawValue).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: selectedLogTab) { _, tab in
                    if tab == .app {
                        appState.loadAppLogPage(0)
                    }
                }

                switch selectedLogTab {
                case .app:
                    appLogContent
                case .api:
                    apiLogContent
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

    @ViewBuilder
    private var apiLogContent: some View {
        if recentAPILogs.isEmpty {
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

                    Text("\(recentAPILogs.count)/100")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: apiLogRowSpacing) {
                        ForEach(recentAPILogs) { log in
                            Button {
                                selectedAPILogID = log.id
                            } label: {
                                apiLogRow(log, isSelected: selectedAPILog?.id == log.id)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: apiLogListHeight, alignment: .top)

                if let selectedAPILog {
                    Divider()

                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            debugSection(title: "Request", value: requestText(for: selectedAPILog))
                            debugSection(title: "Response", value: responseText(for: selectedAPILog))

                            if let error = selectedAPILog.error, !error.isEmpty {
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

    @ViewBuilder
    private var appLogContent: some View {
        if recentAppLogs.isEmpty {
            Text("아직 APP 로그가 없습니다.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Recent APP")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)

                    Spacer()

                    Text("\(appState.appLogPageStart)-\(appState.appLogPageEnd) / \(appState.appLogTotalCount)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: apiLogRowSpacing) {
                        ForEach(recentAppLogs) { log in
                            Button {
                                selectedAppLogID = log.id
                            } label: {
                                appLogRow(log, isSelected: selectedAppLog?.id == log.id)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: appLogListHeight, alignment: .top)

                if let selectedAppLog {
                    Divider()
                    ScrollView {
                        debugSection(
                            title: "\(selectedAppLog.level.rawValue.uppercased()) · \(selectedAppLog.createdAt.formatted(date: .numeric, time: .standard))",
                            value: selectedAppLog.message,
                            isError: selectedAppLog.level == .error
                        )
                    }
                    .frame(maxHeight: 220)
                }
            }
        }
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
        .padding(.vertical, 4)
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

    private func appLogRow(_ entry: AppLogEntry, isSelected: Bool) -> some View {
        HStack(spacing: 7) {
            Circle()
                .fill(statusColor(for: entry))
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 1) {
                Text(entry.message)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text("\(entry.level.rawValue.uppercased()) · \(entry.createdAt.formatted(date: .omitted, time: .standard))")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.16) : Color.primary.opacity(0.04))
        )
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(isSelected ? Color.accentColor.opacity(0.35) : Color.clear, lineWidth: 1)
        }
        .frame(minHeight: apiLogRowHeight)
        .contentShape(Rectangle())
    }

    private var apiLogListHeight: CGFloat {
        guard !recentAPILogs.isEmpty else {
            return 0
        }

        let visibleRows = min(recentAPILogs.count, 3)
        let contentHeight = (CGFloat(visibleRows) * apiLogRowHeight)
            + (CGFloat(max(0, visibleRows - 1)) * apiLogRowSpacing)
        return min(apiLogListMaxHeight, contentHeight)
    }

    private var appLogListHeight: CGFloat {
        guard !recentAppLogs.isEmpty else {
            return 0
        }

        let visibleRows = min(recentAppLogs.count, 3)
        let contentHeight = (CGFloat(visibleRows) * apiLogRowHeight)
            + (CGFloat(max(0, visibleRows - 1)) * apiLogRowSpacing)
        return min(apiLogListMaxHeight, contentHeight)
    }

    private var apiLogRowHeight: CGFloat {
        46
    }

    private var apiLogRowSpacing: CGFloat {
        3
    }

    private var apiLogListMaxHeight: CGFloat {
        (apiLogRowHeight * 3) + (apiLogRowSpacing * 2)
    }

    private func panelWidth(for size: CGSize) -> CGFloat {
        return min(max(size.width - 24, 220), isExpanded ? 380 : 300)
    }

    private func panelMaxHeight(for size: CGSize) -> CGFloat {
        return min(isExpanded ? 430 : 64, max(80, size.height - 24))
    }

    private func panelEstimatedHeight(for size: CGSize) -> CGFloat {
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
        switch selectedLogTab {
        case .app:
            return (selectedAppLog ?? latestAppLog)?.message ?? "APP 로그 대기 중"
        case .api:
            guard let selectedLog = selectedAPILog ?? latestAPILog else {
                return "API 로그 대기 중"
            }
            return "\(selectedLog.method) \(shortURL(selectedLog.url))"
        }
    }

    private var latestSubtitle: String {
        switch selectedLogTab {
        case .app:
            guard let log = selectedAppLog ?? latestAppLog else {
                return "최근 APP 로그 없음"
            }
            return "\(log.level.rawValue.uppercased()) · \(log.createdAt.formatted(date: .omitted, time: .standard))"
        case .api:
            guard let selectedLog = selectedAPILog ?? latestAPILog else {
                return "최근 API 요청/응답 없음"
            }
            let status = selectedLog.statusCode.map(String.init) ?? "pending"
            return "\(status) · \(selectedLog.durationText) · \(recentAPILogs.count) logs"
        }
    }

    private var statusColor: Color {
        switch selectedLogTab {
        case .app:
            guard let log = selectedAppLog ?? latestAppLog else {
                return .secondary
            }
            return statusColor(for: log)
        case .api:
            guard let log = selectedAPILog ?? latestAPILog else {
                return .secondary
            }
            return statusColor(for: log)
        }
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

    private func statusColor(for entry: AppLogEntry) -> Color {
        switch entry.level {
        case .info:
            return .blue
        case .warning:
            return .orange
        case .error:
            return .red
        }
    }

    private func requestText(for entry: APITrafficLogEntry) -> String {
        [
            "\(entry.method) \(entry.url)",
            entry.requestHeaders.isEmpty ? "" : entry.requestHeaders,
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

final class StudyMateiOSAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        AppAnalytics.start()
        SentryMonitoring.start()
        // A notification response can arrive before the SwiftUI bootstrap task creates AppState.
        // Install the delegate during application launch so the response is queued instead of lost.
        StudyNotificationDelegate.shared.register()
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
