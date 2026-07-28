import SwiftUI
#if os(iOS)
import SafariServices
import UIKit
#endif

struct MobileRootView: View {
    @EnvironmentObject private var appState: AppState
    @State private var isRecordsLoginPagePresented = false
    @State private var isStatisticsLoginPagePresented = false

    var body: some View {
        let strings = appState.strings

        Group {
            if !appState.hasCompletedOnboarding {
                MobileOnboardingView()
            } else {
                TabView(selection: selectedMobileTab) {
                    NavigationStack {
                        MobileHomeView()
                            .padding(.horizontal, 16)
                            .navigationDestination(item: $appState.homeStudyRoute) { route in
                                if route.showsTree,
                                   let categoryID = route.categoryID,
                                   let studyID = Int(categoryID) {
                                    MobileStudyTreeView(rootStudyID: studyID)
                                } else {
                                    StudyView(preferredCategoryID: route.categoryID)
                                        .padding(.horizontal, 16)
                                        .mobileTabTitle(studyScreenTitle(for: route))
                                }
                            }
                    }
                    .tabItem {
                        Label(strings.tabHome, systemImage: "house.fill")
                    }
                    .tag(AppTab.home)

                    NavigationStack {
                        Group {
                            if appState.shouldShowRecordsLoginPage {
                                MobileProtectedLoginGate(
                                    page: .records,
                                    onLogin: { isRecordsLoginPagePresented = true }
                                )
                                .padding(.horizontal, 16)
                                .onAppear {
                                    appState.logMobileAuthView(
                                        "mobile_render_login_gate",
                                        page: .records,
                                        reason: "records-tab"
                                    )
                                }
                            } else {
                                HistoryView()
                                    .padding(.horizontal, 16)
                                    .onAppear {
                                        appState.logMobileAuthView(
                                            "mobile_render_protected_content",
                                            page: .records,
                                            reason: "records-tab"
                                        )
                                    }
                            }
                        }
                        .navigationDestination(isPresented: $isRecordsLoginPagePresented) {
                            MobileLoginPage()
                                .padding(.horizontal, 16)
                        }
                    }
                    .tabItem {
                        Label(strings.tabRecords, systemImage: "clock.arrow.circlepath")
                    }
                    .tag(AppTab.records)

                    NavigationStack {
                        Group {
                            if appState.shouldShowStatisticsLoginPage {
                                MobileProtectedLoginGate(
                                    page: .statistics,
                                    onLogin: { isStatisticsLoginPagePresented = true }
                                )
                                .padding(.horizontal, 16)
                                .onAppear {
                                    appState.logMobileAuthView(
                                        "mobile_render_login_gate",
                                        page: .statistics,
                                        reason: "statistics-tab"
                                    )
                                }
                            } else {
                                StatisticsView()
                                    .padding(.horizontal, 16)
                                    .onAppear {
                                        appState.logMobileAuthView(
                                            "mobile_render_protected_content",
                                            page: .statistics,
                                            reason: "statistics-tab"
                                        )
                                    }
                            }
                        }
                        .navigationDestination(isPresented: $isStatisticsLoginPagePresented) {
                            MobileLoginPage()
                                .padding(.horizontal, 16)
                        }
                    }
                    .tabItem {
                        Label(strings.tabStatistics, systemImage: "chart.xyaxis.line")
                    }
                    .tag(AppTab.statistics)

                    NavigationStack {
                        MobileNotificationsTab()
                    }
                    .tabItem {
                        Label(strings.notifications, systemImage: "bell.fill")
                    }
                    .badge(appState.notificationUnreadCount)
                    .tag(AppTab.notifications)
                }
                .background(Color(.systemBackground))
                .onAppear {
                    appState.normalizeSelectedTabForMobile()
                }
                #if DEBUG
                .background {
                    AppDebugSettingsTabLongPressBridge {
                        appState.requestDebugPanelIfEnabledOrEnableOnDemand()
                    }
                    .frame(width: 0, height: 0)
                }
                #endif
            }
        }
        .fullScreenCover(isPresented: $appState.isRequiredTermsGatePresented) {
            MobileRequiredTermsGateSheet()
                .environmentObject(appState)
        }
    }

    private var selectedMobileTab: Binding<AppTab> {
        Binding(
            get: { appState.mobileVisibleTab },
            set: { newTab in
                appState.setSelectedTab(newTab)
            }
        )
    }

    private func studyScreenTitle(for route: HomeStudyRoute) -> String {
        if let categoryID = route.categoryID,
           let category = appState.settings.category(for: categoryID) {
            return appState.strings.homePath(category.title)
        }

        return appState.strings.tabStudy
    }
}

private struct MobileNotificationsTab: View {
    @EnvironmentObject private var appState: AppState
    @State private var isPresented = true
    @State private var forwardedRoute: NotificationForwardRoute?

    var body: some View {
        MobileNotificationsView(
            isPresented: $isPresented,
            forwardedRoute: $forwardedRoute
        )
        .padding(.horizontal, 16)
        .mobileTabTitle(appState.strings.notificationInbox)
        .onChange(of: isPresented) { _, newValue in
            guard !newValue else {
                return
            }
            isPresented = true
            appState.setSelectedTab(.home)
        }
    }
}

private struct MobileProtectedLoginGate: View {
    @EnvironmentObject private var appState: AppState
    var page: MobileProtectedLoginPage
    var onLogin: () -> Void

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MobileRootLargeTitle(page.title(strings: strings))
                .padding(.top, 6)
                .padding(.bottom, 8)

            MobileProtectedLoginPrompt(
                page: page,
                strings: strings,
                onLogin: onLogin
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(.systemBackground))
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}

private enum MobileProtectedLoginPage {
    case myStudy
    case records
    case statistics

    func title(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return HomeFeedScope.my.title(strings: strings)
        case .records:
            return strings.tabRecords
        case .statistics:
            return strings.tabStatistics
        }
    }

    func benefitText(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyLoginBenefit
        case .records:
            return strings.recordsLoginBenefit
        case .statistics:
            return strings.statisticsLoginBenefit
        }
    }

    func loginActionTitle(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyLoginAction
        case .records:
            return strings.recordsLoginAction
        case .statistics:
            return strings.statisticsLoginAction
        }
    }

    func previewTitle(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyGuestPreviewTitle
        case .records:
            return strings.recordsGuestPreviewTitle
        case .statistics:
            return strings.statisticsGuestPreviewTitle
        }
    }

    func previewSubtitle(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyGuestPreviewSubtitle
        case .records:
            return strings.recordsGuestPreviewSubtitle
        case .statistics:
            return strings.statisticsGuestPreviewSubtitle
        }
    }

    func loginFooterTitle(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyGuestLoginTitle
        case .records:
            return strings.recordsGuestLoginTitle
        case .statistics:
            return strings.statisticsGuestLoginTitle
        }
    }

    func loginFooterSubtitle(strings: AppStrings) -> String {
        switch self {
        case .myStudy:
            return strings.myStudyGuestLoginSubtitle
        case .records:
            return strings.recordsGuestLoginSubtitle
        case .statistics:
            return strings.statisticsGuestLoginSubtitle
        }
    }
}

private struct MobileProtectedLoginPrompt: View {
    var page: MobileProtectedLoginPage
    var strings: AppStrings
    var onLogin: () -> Void

    @ViewBuilder
    var body: some View {
        switch page {
        case .myStudy:
            MobileMyStudyLoginExperience(strings: strings, onLogin: onLogin)
        case .records, .statistics:
            MobileProtectedLoginExperience(
                page: page,
                strings: strings,
                onLogin: onLogin
            )
        }
    }
}

private struct MobileProtectedLoginExperience: View {
    var page: MobileProtectedLoginPage
    var strings: AppStrings
    var onLogin: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                MobileProtectedLoginPreview(page: page, strings: strings)
                    .padding(.top, 16)
                    .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)

            MobileProtectedLoginFooter(
                page: page,
                strings: strings,
                onLogin: onLogin
            )
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private struct MobileProtectedLoginPreview: View {
    var page: MobileProtectedLoginPage
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            VStack(alignment: .leading, spacing: 8) {
                Text(page.previewTitle(strings: strings))
                    .font(.title2.weight(.bold))
                    .foregroundStyle(.primary)

                Text(page.previewSubtitle(strings: strings))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if page == .records {
                MobileGuestWeeklySummary(strings: strings)
            } else {
                MobileGuestGrassSummary(strings: strings)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text(page == .records ? strings.guestRecentRecords : strings.guestTopicProgress)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)

                if page == .records {
                    MobileGuestRecordPreview(strings: strings)
                } else {
                    MobileGuestStatisticsPreview(strings: strings)
                }
            }
        }
        .frame(maxWidth: 620, alignment: .leading)
    }
}

private struct MobileGuestWeeklySummary: View {
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(strings.guestWeeklySummary)
                .font(.subheadline.weight(.semibold))

            HStack(alignment: .bottom, spacing: 0) {
                MobileGuestMetric(
                    title: strings.guestStudyDays,
                    value: "4",
                    suffix: strings.language == .korean ? "일" : ""
                )

                Divider()
                    .frame(height: 44)

                MobileGuestMetric(
                    title: strings.guestActivities,
                    value: "12",
                    suffix: strings.language == .korean ? "회" : ""
                )

                Divider()
                    .frame(height: 44)

                MobileGuestMetric(
                    title: strings.guestAverageScore,
                    value: "80",
                    suffix: strings.language == .korean ? "점" : ""
                )
            }

            HStack(alignment: .bottom, spacing: 12) {
                ForEach(Array([0.72, 0.88, 0.64, 1.0, 0.28, 0.18, 0.12].enumerated()), id: \.offset) { index, value in
                    VStack(spacing: 7) {
                        Capsule()
                            .fill(index < 4 ? Color.green.opacity(0.82) : Color.secondary.opacity(0.14))
                            .frame(height: 7 + (30 * value))

                        Text(weekdayLabel(at: index))
                            .font(.caption2)
                            .foregroundStyle(index == 3 ? Color.primary : Color.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .bottom)
                }
            }
            .frame(height: 54, alignment: .bottom)
        }
        .padding(18)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func weekdayLabel(at index: Int) -> String {
        let korean = ["월", "화", "수", "목", "금", "토", "일"]
        let english = ["M", "T", "W", "T", "F", "S", "S"]
        return strings.language == .korean ? korean[index] : english[index]
    }
}

private struct MobileGuestGrassSummary: View {
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .bottom, spacing: 0) {
                MobileGuestMetric(
                    title: strings.guestStudyDays,
                    value: "18",
                    suffix: strings.language == .korean ? "일" : ""
                )

                Divider()
                    .frame(height: 44)

                MobileGuestMetric(
                    title: strings.guestTopicCount,
                    value: "3",
                    suffix: strings.language == .korean ? "개" : ""
                )

                Divider()
                    .frame(height: 44)

                MobileGuestMetric(
                    title: strings.guestAverageScore,
                    value: "80",
                    suffix: strings.language == .korean ? "점" : ""
                )
            }

            HStack(alignment: .top, spacing: 3) {
                ForEach(0..<18, id: \.self) { week in
                    VStack(spacing: 3) {
                        ForEach(0..<7, id: \.self) { day in
                            RoundedRectangle(cornerRadius: 2, style: .continuous)
                                .fill(grassColor(level: sampleLevel(week: week, day: day)))
                                .frame(maxWidth: .infinity)
                                .aspectRatio(1, contentMode: .fit)
                        }
                    }
                }
            }
        }
        .padding(18)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityHidden(true)
    }

    private func sampleLevel(week: Int, day: Int) -> Int {
        let value = (week * 11 + day * 7 + 3) % 13
        switch value {
        case 0...4:
            return 0
        case 5...7:
            return 1
        case 8...9:
            return 2
        case 10...11:
            return 3
        default:
            return 4
        }
    }

    private func grassColor(level: Int) -> Color {
        switch level {
        case 4:
            return Color.green.opacity(0.92)
        case 3:
            return Color.green.opacity(0.72)
        case 2:
            return Color.green.opacity(0.52)
        case 1:
            return Color.green.opacity(0.30)
        default:
            return Color.secondary.opacity(0.13)
        }
    }
}

private struct MobileMyStudyLoginExperience: View {
    var strings: AppStrings
    var onLogin: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Text(strings.myStudyGuestPreviewTitle)
                    .font(.title2.weight(.bold))

                Text(strings.myStudyGuestPreviewSubtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text(strings.myStudyGuestLoginTitle)
                    .font(.subheadline.weight(.semibold))

                Text(strings.myStudyGuestLoginSubtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button(action: onLogin) {
                    MobilePrimaryLoginButtonLabel(title: strings.myStudyLoginAction)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: 620, alignment: .leading)
    }
}

private struct MobileGuestMetric: View {
    var title: String
    var value: String
    var suffix: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            HStack(alignment: .firstTextBaseline, spacing: 1) {
                Text(value)
                    .font(.title2.weight(.semibold))
                if !suffix.isEmpty {
                    Text(suffix)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
    }
}

private struct MobileGuestRecordPreview: View {
    var strings: AppStrings

    private var rows: [(topic: String, question: String, score: Int)] {
        [
            (strings.guestPreviewRecordTopicOne, strings.guestPreviewRecordQuestionOne, 92),
            (strings.guestPreviewRecordTopicTwo, strings.guestPreviewRecordQuestionTwo, 78),
            (strings.guestPreviewRecordTopicThree, strings.guestPreviewRecordQuestionThree, 88)
        ]
    }

    var body: some View {
        VStack(spacing: 10) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text(row.topic)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Text(row.question)
                            .font(.subheadline)
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                    }

                    Spacer(minLength: 8)

                    Text("\(row.score)/100")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.green)
                }
                .padding(14)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .opacity([0.82, 0.56, 0.34][index])
            }
        }
        .accessibilityHidden(true)
    }
}

private struct MobileGuestStatisticsPreview: View {
    var strings: AppStrings

    private var rows: [(topic: String, progress: String, value: Double)] {
        [
            (strings.guestPreviewRecordTopicOne, strings.guestPreviewProgressOne, 0.86),
            (strings.guestPreviewRecordTopicTwo, strings.guestPreviewProgressTwo, 0.64),
            (strings.guestPreviewRecordTopicThree, strings.guestPreviewProgressThree, 0.76)
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                VStack(alignment: .leading, spacing: 9) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(row.topic)
                            .font(.subheadline.weight(.semibold))

                        Spacer(minLength: 12)

                        Text(row.progress)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    ProgressView(value: row.value)
                        .tint(.green)
                }
                .padding(.vertical, 14)

                if index < rows.count - 1 {
                    Divider()
                }
            }
        }
        .padding(.horizontal, 16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .opacity(0.78)
        .accessibilityHidden(true)
    }
}

private struct MobileProtectedLoginFooter: View {
    var page: MobileProtectedLoginPage
    var strings: AppStrings
    var onLogin: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Divider()

            VStack(alignment: .leading, spacing: 5) {
                Text(page.loginFooterTitle(strings: strings))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(page.loginFooterSubtitle(strings: strings))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Button(action: onLogin) {
                MobilePrimaryLoginButtonLabel(title: page.loginActionTitle(strings: strings))
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: 620, alignment: .leading)
        .background(Color(.systemBackground))
    }
}

private struct MobilePrimaryLoginButtonLabel: View {
    var title: String

    var body: some View {
        HStack(spacing: 12) {
            Spacer()

            Text(title)
                .font(.headline.weight(.semibold))

            Spacer()

            Image(systemName: "arrow.right")
                .font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(Color(.systemBackground))
        .padding(.horizontal, 18)
        .frame(maxWidth: .infinity, minHeight: 50)
        .background(Color.primary, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
    }
}

private struct MobileRequiredTermsGateSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var marketingAgreed = false
    @State private var legalWebRoute: MobileLegalWebRoute?
    @State private var isSavingAgreements = false

    private var strings: AppStrings { appState.strings }

    private var termsOfService: BackendTerms? {
        appState.activeTerms.first { $0.type == .termsOfService }
    }

    private var privacyPolicy: BackendTerms? {
        appState.activeTerms.first { $0.type == .privacyPolicy }
    }

    private var marketingTerms: BackendTerms? {
        appState.activeTerms.first { $0.type == .marketingNotification }
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(strings.requiredTermsGateTitle)
                        .font(.title2.weight(.bold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(strings.requiredTermsGateSubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(spacing: 0) {
                    requiredGateRow(
                        title: termsTitle(strings.termsOfService, required: true),
                        isChecked: true,
                        url: termsOfService?.url ?? AppLegalLinks.termsOfServiceURL(language: appState.settings.appLanguage)
                    )
                    Divider().padding(.leading, 34)
                    requiredGateRow(
                        title: termsTitle(strings.privacyPolicy, required: true),
                        isChecked: true,
                        url: privacyPolicy?.url ?? AppLegalLinks.privacyPolicyURL(language: appState.settings.appLanguage)
                    )
                    if let marketingTerms {
                        Divider().padding(.leading, 34)
                        requiredGateRow(
                            title: termsTitle(strings.marketingNotifications, required: false),
                            isChecked: marketingAgreed,
                            url: marketingTerms.url,
                            togglesSelection: true
                        )
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 14)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))

                Spacer(minLength: 0)

                Button {
                    Task {
                        await agreeTerms(includeMarketing: marketingTerms != nil)
                    }
                } label: {
                    HStack {
                        Spacer()
                        if isSavingAgreements {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Text(marketingTerms == nil ? strings.agreeAndStart : strings.agreeAllAndStart)
                                .font(.headline.weight(.bold))
                        }
                        Spacer()
                    }
                    .frame(minHeight: 54)
                    .foregroundStyle(.white)
                    .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(isSavingAgreements)

                Button {
                    Task {
                        await agreeTerms(includeMarketing: false)
                    }
                } label: {
                    Text(strings.agreeRequiredOnlyAndStart)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.plain)
                .disabled(isSavingAgreements)
            }
            .padding(24)
            .navigationTitle(strings.operatingTerms)
            .navigationBarTitleDisplayMode(.inline)
            .task {
                await appState.refreshTermsAndNotificationPreferences(reason: "required-terms-gate")
                marketingAgreed = marketingTerms?.agreed == true
            }
            .sheet(item: $legalWebRoute) { route in
                #if os(iOS)
                MobileLegalWebView(url: route.url)
                    .ignoresSafeArea()
                #else
                Link(route.url.absoluteString, destination: route.url)
                    .padding()
                #endif
            }
        }
    }

    private func requiredGateRow(
        title: String,
        isChecked: Bool,
        url: URL,
        togglesSelection: Bool = false
    ) -> some View {
        Button {
            if togglesSelection {
                marketingAgreed.toggle()
            } else {
                legalWebRoute = MobileLegalWebRoute(url: url)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isChecked ? Color.accentColor : Color.secondary.opacity(0.45))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.body.weight(.semibold))
                }
                Spacer()
                Button {
                    legalWebRoute = MobileLegalWebRoute(url: url)
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 14)
        }
        .buttonStyle(.plain)
    }

    private func termsTitle(_ title: String, required: Bool) -> String {
        let suffix = required ? strings.requiredTermsBadge : strings.optionalTermsBadge
        return "\(title) [\(suffix)]"
    }

    @MainActor
    private func agreeTerms(includeMarketing: Bool) async {
        guard !isSavingAgreements else {
            return
        }
        isSavingAgreements = true
        defer {
            isSavingAgreements = false
        }

        let requiredTypes: [BackendTermsType] = [.termsOfService, .privacyPolicy]
        for type in requiredTypes {
            guard await appState.saveTermsAgreement(type: type, isAgreed: true, source: .requiredGate) else {
                return
            }
        }
        if includeMarketing, marketingTerms != nil {
            guard await appState.saveTermsAgreement(
                type: .marketingNotification,
                isAgreed: true,
                source: .requiredGate
            ) else {
                return
            }
            marketingAgreed = true
        }
        appState.isRequiredTermsGatePresented = false
        dismiss()
    }
}

private struct MobileLoginPage: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var isShowingEmailSignIn = false

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 72)

            MobileLoginLogo(size: 96)

            Spacer(minLength: 0)

            VStack(spacing: 10) {
                Button {
                    appState.signInToCommunity()
                } label: {
                    SignInButtonLabel(title: strings.signInWithGoogle, isPrimary: true)
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 18)

            Button {
                isShowingEmailSignIn = true
            } label: {
                SignInButtonLabel(title: strings.signInWithEmail, isPrimary: false)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 34)

            loginAgreement
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 24)
        .navigationTitle(strings.communityLogin)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            appState.logMobileAuthView("mobile_login_page_appear", reason: "MobileLoginPage")
        }
        .onChange(of: appState.isCommunitySessionActive) { _, isSignedIn in
            appState.logMobileAuthView(
                "mobile_login_page_session_change",
                reason: "MobileLoginPage",
                extra: ["isSignedIn=\(isSignedIn)"]
            )
            guard isSignedIn else {
                return
            }
            appState.dismissPageAccessPrompt()
            dismiss()
        }
        .sheet(isPresented: $isShowingEmailSignIn) {
            EmailSignInSheet {
                isShowingEmailSignIn = false
                appState.dismissPageAccessPrompt()
                dismiss()
            }
            .environmentObject(appState)
        }
    }

    private var loginAgreement: some View {
        HStack(spacing: 4) {
            Text(strings.loginAgreementPrefix)
                .foregroundStyle(.secondary)
            Link(strings.termsOfService, destination: AppLegalLinks.termsOfServiceURL(language: appState.settings.appLanguage))
                .tint(.accentColor)
            Text(strings.loginAgreementConjunction)
                .foregroundStyle(.secondary)
            Link(strings.privacyPolicy, destination: AppLegalLinks.privacyPolicyURL(language: appState.settings.appLanguage))
                .tint(.accentColor)
            Text(strings.loginAgreementSuffix)
                .foregroundStyle(.secondary)
        }
        .font(.footnote)
        .lineLimit(1)
        .minimumScaleFactor(0.72)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }
}

private struct MobileLoginLogo: View {
    var size: CGFloat

    var body: some View {
        Image("BuddyStudyLoginLogo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

private struct CommunityQuestionRoute: Identifiable, Hashable {
    var id: String
}

private struct NotificationForwardRoute: Identifiable, Hashable {
    let id = UUID()
    var route: AppRoute
}

private enum MobileHomeFeedItem: Identifiable {
    case question(CommunityQuestion)
    case feedbackPrompt

    var id: String {
        switch self {
        case .question(let question):
            return "question-\(question.id)"
        case .feedbackPrompt:
            return "feedback-prompt"
        }
    }
}

private struct MobileHomeView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedHomeScope: HomeFeedScope = .all
    @State private var editMode: EditMode = .inactive
    @State private var hasLoadedCommunityQuestions = false
    @State private var editingStudyCategory: StudyCategory?
    @State private var editingStudyRoom: BackendStudyRoom?
    @State private var isAddingStudyCategory = false
    @State private var selectedCommunityQuestionRoute: CommunityQuestionRoute?
    @State private var notificationForwardRoute: NotificationForwardRoute?
    @State private var isHomeLoginPagePresented = false
    @State private var isShowingNotifications = false
    @State private var isShowingProfileSettings = false
    @State private var isShowingSettings = false
    @State private var isShowingFeedback = false
    @State private var isShowingEmailSignIn = false
    @State private var isSearchVisible = false
    @State private var homeStudySearchText = ""
    @State private var submittedHomeStudySearchText = ""
    @State private var searchFocusTask: Task<Void, Never>?
    @State private var homeRefreshTask: Task<Void, Never>?
    @State private var refreshingHomeScope: HomeFeedScope?
    @FocusState private var isSearchFocused: Bool

    private var strings: AppStrings {
        appState.strings
    }

    private var trimmedHomeStudySearchText: String {
        homeStudySearchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var activeSearchText: Binding<String> {
        Binding(
            get: {
                selectedHomeScope == .my ? homeStudySearchText : appState.communitySearchText
            },
            set: { newValue in
                if selectedHomeScope == .my {
                    homeStudySearchText = newValue
                } else {
                    appState.communitySearchText = newValue
                }
            }
        )
    }

    private var activeTrimmedSearchText: String {
        activeSearchText.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filteredStudyCategories: [StudyCategory] {
        let query = trimmedHomeStudySearchText
        let categories = appState.rootStudyCategoriesForDisplay
        guard !query.isEmpty else {
            return categories
        }

        let matchingBackendRootIDs: Set<Int>
        if query == submittedHomeStudySearchText,
           let searchResults = appState.homeStudySearchResults {
            matchingBackendRootIDs = Set(
                searchResults.compactMap { category in
                    guard let room = appState.backendStudyRoom(categoryID: category.id) else {
                        return nil
                    }
                    return appState.rootStudyRoom(for: room.id)?.id
                }
            )
        } else {
            matchingBackendRootIDs = []
        }

        return categories.filter { category in
            categoryMatchesHomeSearch(category, query: query)
                || (Int(category.id).map { matchingBackendRootIDs.contains($0) } ?? false)
        }
    }

    private func categoryMatchesHomeSearch(_ category: StudyCategory, query: String) -> Bool {
        if category.matchesHomeSearch(query, appLanguage: strings.language) {
            return true
        }

        guard let rootStudyID = Int(category.id) else {
            return false
        }

        return flattenedStudyTopics(rootStudyID: rootStudyID).contains { item in
            item.room.topic.localizedCaseInsensitiveContains(query)
        }
    }

    private func studyOutlineSnapshot(for category: StudyCategory) -> MobileHomeStudyOutlineSnapshot? {
        guard let rootStudyID = Int(category.id),
              let rootRoom = appState.backendStudyRoom(id: rootStudyID) else {
            return nil
        }

        let allTopics = flattenedStudyTopics(rootStudyID: rootStudyID)
        let query = trimmedHomeStudySearchText
        let searchResults: [BackendStudyRoom]?
        if query.isEmpty || rootRoom.topic.localizedCaseInsensitiveContains(query) {
            searchResults = nil
        } else {
            searchResults = allTopics.compactMap {
                $0.room.topic.localizedCaseInsensitiveContains(query)
                    ? $0.room
                    : nil
            }
        }
        let rooms = [rootRoom] + allTopics.map(\.room)
        let childrenByParent = Dictionary(
            grouping: allTopics.map(\.room),
            by: { $0.parentStudyId ?? rootStudyID }
        ).mapValues { children in
            children.sorted {
                if $0.sortOrder == $1.sortOrder {
                    return $0.id < $1.id
                }
                return $0.sortOrder < $1.sortOrder
            }
        }

        return MobileHomeStudyOutlineSnapshot(
            root: rootRoom,
            roomsByID: Dictionary(uniqueKeysWithValues: rooms.map { ($0.id, $0) }),
            childrenByParent: childrenByParent,
            parentByID: Dictionary(
                uniqueKeysWithValues: allTopics.compactMap { item in
                    item.room.parentStudyId.map { (item.room.id, $0) }
                }
            ),
            searchQuery: query,
            searchResults: searchResults
        )
    }

    private func flattenedStudyTopics(rootStudyID: Int) -> [MobileHomeStudyTopicItem] {
        let childrenByParent = Dictionary(
            grouping: appState.backendStudyRooms.filter { $0.parentStudyId != nil },
            by: { $0.parentStudyId ?? 0 }
        )
        var result: [MobileHomeStudyTopicItem] = []
        var visited = Set<Int>([rootStudyID])

        func appendChildren(parentID: Int) {
            let children = (childrenByParent[parentID] ?? []).sorted {
                if $0.sortOrder == $1.sortOrder {
                    return $0.id < $1.id
                }
                return $0.sortOrder < $1.sortOrder
            }

            for child in children where visited.insert(child.id).inserted {
                result.append(MobileHomeStudyTopicItem(room: child))
                appendChildren(parentID: child.id)
            }
        }

        appendChildren(parentID: rootStudyID)
        return result
    }

    private var communityFeedItems: [MobileHomeFeedItem] {
        var items = appState.communityQuestions.map(MobileHomeFeedItem.question)
        guard items.count >= 4 else {
            return items
        }
        items.insert(.feedbackPrompt, at: 4)
        return items
    }

    private var isRefreshingSelectedHomeScope: Bool {
        refreshingHomeScope == selectedHomeScope && homeRefreshTask != nil
    }

    private var isRefreshingMyStudyContent: Bool {
        selectedHomeScope == .my && isRefreshingSelectedHomeScope
    }

    private var isRefreshingCommunityContent: Bool {
        selectedHomeScope == .all && isRefreshingSelectedHomeScope
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            homeTitleHeader
            homeScopePickerHeader

            List {
                homeContentSection
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .refreshable {
                startHomeRefresh()
            }
        }
        .background(Color(.systemBackground))
        .environment(\.editMode, $editMode)
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .mobileToolbarSearchable(
            isPresented: isSearchVisible || !activeTrimmedSearchText.isEmpty,
            text: activeSearchText,
            prompt: strings.topicSearch,
            focus: $isSearchFocused
        )
        .navigationDestination(isPresented: $isShowingNotifications) {
            MobileNotificationsView(
                isPresented: $isShowingNotifications,
                forwardedRoute: $notificationForwardRoute
            )
                .padding(.horizontal, 16)
                .mobileTabTitle(strings.notificationInbox)
        }
        .navigationDestination(isPresented: $isHomeLoginPagePresented) {
            MobileLoginPage()
                .padding(.horizontal, 16)
        }
        .navigationDestination(isPresented: $isShowingSettings) {
            MobileSettingsView()
        }
        .navigationDestination(isPresented: $isShowingFeedback) {
            MobileFeedbackView()
        }
        .toolbar {
            #if os(iOS)
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarLeading) {
                    if !isHomeSearchActive {
                        profileToolbarControl
                    }
                }
                .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .topBarLeading) {
                    if !isHomeSearchActive {
                        profileToolbarControl
                    }
                }
            }

            if #available(iOS 26.0, *) {
                if shouldShowHomeAddToolbarButton {
                    ToolbarItem(placement: .topBarTrailing) {
                        homeAddToolbarButton(strings: strings)
                    }
                    .sharedBackgroundVisibility(.hidden)
                }

                ToolbarItem(placement: .topBarTrailing) {
                    homeToolbarSearchControl(strings: strings)
                }
                .sharedBackgroundVisibility(isHomeSearchActive ? .hidden : .automatic)
            } else {
                if shouldShowHomeAddToolbarButton {
                    ToolbarItem(placement: .topBarTrailing) {
                        homeAddToolbarButton(strings: strings)
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    homeToolbarSearchControl(strings: strings)
                }
            }
            #else
            ToolbarItemGroup(placement: .primaryAction) {
                profileToolbarButton
                homeToolbarItems(strings: strings)
            }
            #endif
        }
        .task {
            await loadCommunityQuestionsIfNeeded(userInitiated: false)
            await appState.refreshNotificationUnreadCount()
        }
        .onAppear {
            handleAppRouteRequest(appState.appRouteRequest)
        }
        .onChange(of: appState.appRouteRequest) { _, request in
            handleAppRouteRequest(request)
        }
        .onChange(of: isSearchFocused) { _, isFocused in
            guard !isFocused,
                  activeTrimmedSearchText.isEmpty else {
                return
            }

            closeHomeSearch(clearText: false)
        }
        .onChange(of: selectedHomeScope) { _, newScope in
            appState.logMobileAuthView(
                "mobile_home_scope_change",
                page: newScope == .my ? .myStudies : .publicQuestions,
                reason: "selectedHomeScope",
                extra: ["scope=\(String(describing: newScope))"]
            )
            if newScope == .all {
                editMode = .inactive
                Task {
                    await loadCommunityQuestionsIfNeeded(userInitiated: false)
                }
            } else if appState.isCommunitySessionActive {
                Task {
                    await appState.refreshQuestionQuota()
                }
            }
        }
        .onChange(of: appState.isCommunitySessionActive) { _, isSignedIn in
            appState.logMobileAuthView(
                "mobile_home_session_change",
                reason: "MobileHomeView",
                extra: ["isSignedIn=\(isSignedIn)", "scope=\(String(describing: selectedHomeScope))"]
            )
            hasLoadedCommunityQuestions = false
            guard isSignedIn else {
                return
            }

            if selectedHomeScope == .all {
                Task {
                    await loadCommunityQuestionsIfNeeded(userInitiated: false)
                }
            } else {
                Task {
                    await appState.refreshQuestionQuota()
                }
            }
        }
        .onChange(of: homeStudySearchText) {
            if trimmedHomeStudySearchText != submittedHomeStudySearchText {
                appState.clearBackendStudySearchResults()
            }
        }
        .onDisappear {
            searchFocusTask?.cancel()
            searchFocusTask = nil
            if activeTrimmedSearchText.isEmpty {
                closeHomeSearch(clearText: false)
            }
        }
        .sheet(isPresented: $isShowingProfileSettings) {
            MobileProfileSettingsSheet()
        }
        .sheet(isPresented: $isShowingEmailSignIn) {
            EmailSignInSheet {
                isShowingEmailSignIn = false
            }
            .environmentObject(appState)
        }
        .sheet(isPresented: $isAddingStudyCategory) {
            StudyCategoryEditorSheet(category: nil, strings: strings, onDelete: nil) { title, difficulty, prompt, model in
                appState.addStudyCategory(title, difficulty: difficulty, customPrompt: prompt, openAIModel: model)
            }
        }
        .sheet(item: $editingStudyCategory) { category in
            StudyCategoryEditorSheet(category: category, strings: strings, onDelete: {
                appState.deleteStudyCategory(id: category.id)
            }) { title, difficulty, prompt, model in
                appState.updateStudyCategory(
                    id: category.id,
                    title: title,
                    difficulty: difficulty,
                    customPrompt: prompt,
                    openAIModel: model
                )
            }
        }
        .sheet(item: $editingStudyRoom) { room in
            StudyTopicLevelSheet(
                room: room,
                strings: strings,
                onDelete: {
                    appState.deleteStudyCategory(id: String(room.id))
                }
            ) { title, difficulty, isActive in
                appState.updateStudyTreeCategory(
                    roomID: room.id,
                    title: title,
                    difficulty: difficulty
                )
                if isActive != room.activeForQuestions {
                    appState.setStudyTopicActive(studyID: room.id, active: isActive)
                }
            }
        }
        .navigationDestination(item: $selectedCommunityQuestionRoute) { route in
            NotificationCommunityQuestionDestination(questionID: route.id)
        }
    }

    private func handleAppRouteRequest(_ request: AppRouteRequest?) {
        guard let request else {
            return
        }

        if request.presentation == .notificationInbox {
            if request.route == .home {
                notificationForwardRoute = nil
                isShowingNotifications = false
                appState.appRouteRequest = nil
                return
            }
            notificationForwardRoute = NotificationForwardRoute(route: request.route)
            isShowingNotifications = true
            appState.appRouteRequest = nil
            return
        }

        switch request.route {
        case .profile:
            isShowingProfileSettings = true
        case .settings, .settingsOpenAI:
            isShowingSettings = true
        case .studyList:
            selectedHomeScope = .my
        case .publicQuestions:
            selectedHomeScope = .all
            Task { @MainActor in
                await loadCommunityQuestionsIfNeeded(userInitiated: false)
            }
        case .publicQuestion(let id):
            selectedHomeScope = .all
            selectedCommunityQuestionRoute = CommunityQuestionRoute(id: id)
            Task { @MainActor in
                await loadCommunityQuestionsIfNeeded(userInitiated: false)
                selectedCommunityQuestionRoute = CommunityQuestionRoute(id: id)
            }
        default:
            break
        }

        appState.appRouteRequest = nil
    }

    private var homeTitleHeader: some View {
        MobileRootLargeTitle(strings.tabHome)
            .padding(.top, 6)
            .padding(.bottom, 8)
    }

    private var homeScopePickerHeader: some View {
        Picker("", selection: $selectedHomeScope) {
            ForEach(HomeFeedScope.allCases) { scope in
                Text(scope.title(strings: strings))
                    .tag(scope)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private var homeContentSection: some View {
        if selectedHomeScope == .my, !appState.isCommunitySessionActive {
            myStudyLoginSection
                .onAppear {
                    appState.logMobileAuthView(
                        "mobile_render_login_gate",
                        page: .myStudies,
                        reason: "home-my-study"
                    )
                }
        } else if selectedHomeScope == .my {
            myStudySection
                .onAppear {
                    appState.logMobileAuthView(
                        "mobile_render_protected_content",
                        page: .myStudies,
                        reason: "home-my-study"
                    )
                }
        } else {
            communityQuestionSection
                .onAppear {
                    appState.logMobileAuthView(
                        "mobile_render_public_content",
                        page: .publicQuestions,
                        reason: "home-public"
                    )
                }
        }
    }

    private var myStudyLoginSection: some View {
        Section {
            MobileProtectedLoginPrompt(
                page: .myStudy,
                strings: strings,
                onLogin: { isHomeLoginPagePresented = true }
            )
            .padding(.vertical, 12)
            .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 18, trailing: 0))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    private var myStudySection: some View {
        Section {
            if filteredStudyCategories.isEmpty {
                if isRefreshingMyStudyContent {
                    MobileHomeRefreshIndicator()
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 34)
                        .listRowSeparator(.hidden)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(strings.noMatchingTopics)
                            .font(.subheadline.weight(.semibold))

                        Text(strings.noMatchingTopicsDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }
            } else {
                if isRefreshingMyStudyContent {
                    MobileHomeRefreshIndicator()
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 8)
                        .listRowSeparator(.hidden)
                }

                ForEach(filteredStudyCategories) { category in
                    myStudyCategoryRow(category)
                }
                .onMove { offsets, destination in
                    guard trimmedHomeStudySearchText.isEmpty else {
                        return
                    }

                    appState.moveStudyCategories(from: offsets, to: destination)
                }
            }
        }
    }

    @ViewBuilder
    private func myStudyCategoryRow(_ category: StudyCategory) -> some View {
        if editMode.isEditing {
            MobileHomeCategoryRow(
                category: category,
                hasPendingQuestion: appState.pendingQuestionCount(for: category) > 0,
                strings: strings
            )
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        } else {
            if let snapshot = studyOutlineSnapshot(for: category) {
                MobileHomeStudyOutlineRow(
                    snapshot: snapshot,
                    strings: strings,
                    pendingQuestionCount: { room in
                        appState.pendingQuestionCount(categoryID: String(room.id))
                    },
                    onAction: { action in
                        handleStudyOutlineAction(action, category: category)
                    }
                )
            } else {
                Button {
                    appState.openStudyTree(category.id)
                } label: {
                    MobileHomeCategoryRow(
                        category: category,
                        hasPendingQuestion: appState.pendingQuestionCount(for: category) > 0,
                        strings: strings
                    )
                }
                .buttonStyle(.plain)
                .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .contextMenu {
                    Button {
                        editingStudyCategory = category
                    } label: {
                        Label(strings.editStudyCategory, systemImage: "pencil")
                    }

                    Button {
                        appState.openStudyTree(category.id)
                    } label: {
                        Label(
                            strings.viewFullStudyTree,
                            systemImage: "point.3.connected.trianglepath.dotted"
                        )
                    }
                }
            }
        }
    }

    private func handleStudyOutlineAction(
        _ action: MobileHomeStudyOutlineAction,
        category: StudyCategory
    ) {
        switch action {
        case let .openTopic(room):
            appState.openStudyCategory(String(room.id))
        case let .configureTopic(room):
            editingStudyRoom = room
        case .configureRoot:
            editingStudyCategory = category
        case .openTree:
            appState.openStudyTree(category.id)
        }
    }

    private var communityQuestionSection: some View {
        Section {
            if appState.communityQuestions.isEmpty {
                if isRefreshingCommunityContent {
                    MobileHomeRefreshIndicator()
                        .frame(maxWidth: .infinity, minHeight: 320)
                        .listRowInsets(EdgeInsets(top: 18, leading: 0, bottom: 18, trailing: 0))
                        .listRowSeparator(.hidden)
                } else {
                    MobileCommunityEmptyState(strings: strings)
                        .frame(maxWidth: .infinity, minHeight: 320)
                        .listRowInsets(EdgeInsets(top: 18, leading: 0, bottom: 18, trailing: 0))
                        .listRowSeparator(.hidden)
                }
            } else {
                if isRefreshingCommunityContent {
                    MobileHomeRefreshIndicator()
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 8)
                        .listRowSeparator(.hidden)
                }

                ForEach(communityFeedItems) { item in
                    communityFeedRow(item)
                }

                if appState.isLoadingCommunityQuestions &&
                    !isRefreshingCommunityContent &&
                    appState.canLoadCommunityQuestions {
                    HStack {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                        Spacer()
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }

    @ViewBuilder
    private func communityFeedRow(_ item: MobileHomeFeedItem) -> some View {
        switch item {
        case .question(let question):
            communityQuestionRow(question)
        case .feedbackPrompt:
            Button {
                isShowingFeedback = true
            } label: {
                MobileFeedbackPromptRow(strings: strings)
            }
            .buttonStyle(.plain)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
            .listRowBackground(Color.clear)
        }
    }

    private func communityQuestionRow(_ question: CommunityQuestion) -> some View {
        Button {
            selectedCommunityQuestionRoute = CommunityQuestionRoute(id: question.id)
        } label: {
            MobileCommunityQuestionRow(question: question)
        }
        .buttonStyle(.plain)
        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
        .listRowBackground(Color.clear)
        .contextMenu {
            if appState.isCommunitySessionActive {
                Button(role: .destructive) {
                    Task {
                        await appState.reportCommunityQuestion(
                            question,
                            reason: strings.reportReasonInappropriate
                        )
                    }
                } label: {
                    Label(strings.report, systemImage: "exclamationmark.bubble")
                }
            }
        }
        .onAppear {
            appState.shouldLoadNextCommunityQuestion(after: question.id)
        }
    }

    private var isHomeSearchActive: Bool {
        isSearchVisible || !activeTrimmedSearchText.isEmpty
    }

    private var shouldShowHomeAddToolbarButton: Bool {
        !isHomeSearchActive && selectedHomeScope == .my && appState.isCommunitySessionActive
    }

    private var profileToolbarControl: some View {
        let strings = appState.strings

        return HomeProfileAvatar(
            symbolName: appState.profileAvatarSymbolName,
            displayName: appState.communityProfile?.displayName,
            colorSeed: signedInProfileColorSeed,
            usesNeutralColor: signedInProfileColorSeed == nil,
            size: 34
        )
        .frame(width: 34, height: 34)
        .contentShape(Circle())
        .onTapGesture {
            isShowingProfileSettings = true
        }
        .accessibilityLabel(strings.profile)
        .accessibilityAddTraits(.isButton)
    }

    private var profileToolbarButton: some View {
        let strings = appState.strings

        return Button {
            isShowingProfileSettings = true
        } label: {
            HomeProfileAvatar(
                symbolName: appState.profileAvatarSymbolName,
                displayName: appState.communityProfile?.displayName,
                colorSeed: signedInProfileColorSeed,
                usesNeutralColor: signedInProfileColorSeed == nil,
                size: 34
            )
            .frame(width: 34, height: 34)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .contentShape(Circle())
        .accessibilityLabel(strings.profile)
    }

    private var signedInProfileColorSeed: String? {
        guard appState.isCommunitySessionActive,
              !appState.profileAvatarColorSeed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        return appState.profileAvatarColorSeed
    }

    private func homeToolbarSearchControl(strings: AppStrings) -> some View {
        MobileExpandingToolbarSearch(
            isExpanded: isHomeSearchActive,
            text: activeSearchText,
            prompt: strings.topicSearch,
            focus: $isSearchFocused,
            closeAccessibilityLabel: strings.clearSearch,
            width: min(UIScreen.main.bounds.width - 32, 430),
            collapsedWidth: 34,
            onSubmit: {
                submitHomeSearch()
            },
            onClose: {
                closeHomeSearch(clearText: true)
            }
        ) {
            homeSearchToolbarButton(strings: strings)
        }
    }

    @ViewBuilder
    private func homeToolbarItems(strings: AppStrings) -> some View {
        HStack(spacing: 16) {
            if selectedHomeScope == .my, appState.isCommunitySessionActive {
                Button {
                    isAddingStudyCategory = true
                } label: {
                    MobileToolbarIconButtonLabel(systemName: "plus")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(strings.newStudyCategory)
            }

            Button {
                showHomeSearch()
            } label: {
                MobileToolbarIconButtonLabel(systemName: "magnifyingglass")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(strings.search)
        }
        .fixedSize()
    }

    private func homeSearchToolbarButton(strings: AppStrings) -> some View {
        Button {
            showHomeSearch()
        } label: {
            MobileToolbarIconButtonLabel(systemName: "magnifyingglass")
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.search)
    }

    private func homeAddToolbarButton(strings: AppStrings) -> some View {
        Button {
            isAddingStudyCategory = true
        } label: {
            MobileToolbarIconButtonLabel(systemName: "plus")
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.newStudyCategory)
    }

    @MainActor
    private func showHomeSearch() {
        if isSearchVisible || !activeTrimmedSearchText.isEmpty {
            isSearchFocused = false
            closeHomeSearch(clearText: true)
            return
        }

        withAnimation(.smooth(duration: 0.28)) {
            isSearchVisible = true
        }
        searchFocusTask?.cancel()
        searchFocusTask = Task { @MainActor in
            await Task.yield()
            try? await Task.sleep(nanoseconds: 60_000_000)
            guard !Task.isCancelled else {
                return
            }
            isSearchFocused = true
        }
    }

    @MainActor
    private func closeHomeSearch(clearText: Bool) {
        searchFocusTask?.cancel()
        searchFocusTask = nil
        isSearchFocused = false

        if clearText {
            setActiveSearchText("")
            submittedHomeStudySearchText = ""
            if selectedHomeScope == .my {
                appState.clearBackendStudySearchResults()
            }
        }

        withAnimation(.smooth(duration: 0.22)) {
            isSearchVisible = false
        }
    }

    @MainActor
    private func setActiveSearchText(_ value: String) {
        if selectedHomeScope == .my {
            homeStudySearchText = value
        } else {
            appState.communitySearchText = value
        }
    }

    @MainActor
    private func startHomeRefresh() {
        if homeRefreshTask != nil {
            return
        }

        let scope = selectedHomeScope
        refreshingHomeScope = scope
        homeRefreshTask = Task { @MainActor in
            defer {
                if refreshingHomeScope == scope {
                    refreshingHomeScope = nil
                }
                homeRefreshTask = nil
            }

            await refreshHomeData(for: scope)
        }
    }

    @MainActor
    private func refreshHomeData(for scope: HomeFeedScope) async {
        switch scope {
        case .my:
            await appState.refreshVisibleData()
        case .all:
            hasLoadedCommunityQuestions = true
            await appState.loadCommunityQuestions(reset: true, userInitiated: true)
        }
    }

    @MainActor
    private func loadCommunityQuestionsIfNeeded(userInitiated: Bool) async {
        guard selectedHomeScope == .all else {
            return
        }

        guard userInitiated || !hasLoadedCommunityQuestions else {
            return
        }

        hasLoadedCommunityQuestions = true
        await appState.loadCommunityQuestions(reset: true, userInitiated: userInitiated)
    }

    @MainActor
    private func submitHomeSearch() {
        switch selectedHomeScope {
        case .all:
            hasLoadedCommunityQuestions = true
            Task {
                await appState.loadCommunityQuestions(reset: true, userInitiated: true)
            }
        case .my:
            let query = trimmedHomeStudySearchText
            submittedHomeStudySearchText = query
            guard !query.isEmpty else {
                appState.clearBackendStudySearchResults()
                return
            }

            Task {
                await appState.searchBackendStudies(query: query)
            }
        }
    }
}

private enum HomeFeedScope: String, CaseIterable, Identifiable {
    case all
    case my

    var id: String {
        rawValue
    }

    func title(strings: AppStrings) -> String {
        switch self {
        case .my:
            strings.homeScopeMy
        case .all:
            strings.homeScopeAll
        }
    }
}

private struct MobileNotificationsView: View {
    @EnvironmentObject private var appState: AppState
    @Binding var isPresented: Bool
    @Binding var forwardedRoute: NotificationForwardRoute?
    @State private var openedAt = Date()

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        List {
            if appState.isLoadingNotifications && appState.notifications.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if appState.notifications.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(appState.notificationErrorMessage == nil ? strings.noNotifications : strings.unableToLoadNotifications)
                        .font(.title2.weight(.bold))
                    Text(appState.notificationErrorMessage ?? strings.noNotificationsDescription)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if appState.notificationErrorMessage != nil {
                        Button(strings.retry) {
                            Task {
                                await appState.loadNotifications(reset: true)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 320, alignment: .center)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            } else {
                ForEach(appState.notifications) { notification in
                    Button {
                        let route = appState.notificationLandingCoordinator.routeForNotificationListSelection(notification)
                        if route == .home {
                            forwardedRoute = nil
                            isPresented = false
                        } else {
                            forwardedRoute = NotificationForwardRoute(route: route)
                        }
                        appState.logRemoteNotificationEvent(
                            "알림 목록에서 목적지를 열었습니다. notificationID=\(notification.id), route=\(route)"
                        )
                        Task {
                            await appState.markNotificationRead(notification)
                        }
                    } label: {
                        MobileNotificationRow(
                            notification: notification,
                            referenceDate: openedAt,
                            strings: strings
                        )
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            Task {
                                await appState.deleteNotification(notification)
                            }
                        } label: {
                            Label(strings.deleteNotification, systemImage: "trash")
                        }
                    }
                    .task {
                        await appState.loadMoreNotificationsIfNeeded(current: notification)
                    }
                }

                if appState.isLoadingNotifications {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 12)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable {
            await appState.loadNotifications(reset: true)
        }
        .task {
            openedAt = Date()
            await appState.loadNotifications(reset: true)
        }
        .navigationDestination(item: $forwardedRoute) { route in
            NotificationRouteDestination(route: route.route)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        Task {
                            await appState.markAllNotificationsRead()
                        }
                    } label: {
                        Label(strings.markAllNotificationsRead, systemImage: "checkmark.circle")
                    }
                    .disabled(appState.notificationUnreadCount == 0)

                    Button(role: .destructive) {
                        Task {
                            await appState.deleteAllNotifications()
                        }
                    } label: {
                        Label(strings.deleteAllNotifications, systemImage: "trash")
                    }
                    .disabled(appState.notifications.isEmpty)
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.title3.weight(.semibold))
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                        .accessibilityLabel(strings.more)
                }
            }
        }
    }
}

private struct NotificationRouteDestination: View {
    @EnvironmentObject private var appState: AppState
    var route: AppRoute

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        switch route {
        case .studyRoom(let categoryID):
            StudyView(preferredCategoryID: categoryID)
                .padding(.horizontal, 16)
                .navigationTitle(studyTitle(for: categoryID))
                .navigationBarTitleDisplayMode(.inline)
        case .recordDetail(let recordID):
            NotificationRecordDestination(recordID: recordID)
        case .publicQuestion(let id):
            NotificationCommunityQuestionDestination(questionID: id)
        case .records:
            HistoryView()
                .padding(.horizontal, 16)
                .navigationTitle(strings.tabRecords)
                .navigationBarTitleDisplayMode(.inline)
        case .publicQuestions:
            NotificationPublicQuestionsDestination()
        case .studyList:
            NotificationStudyListDestination()
        case .statistics:
            StatisticsView()
                .padding(.horizontal, 16)
                .navigationTitle(strings.tabStatistics)
                .navigationBarTitleDisplayMode(.inline)
        case .settings, .settingsOpenAI:
            MobileSettingsView()
                .navigationTitle(strings.tabSettings)
                .navigationBarTitleDisplayMode(.inline)
        case .profile:
            MobileProfileSettingsSheet()
        case .home:
            NotificationStudyListDestination()
        }
    }

    private func studyTitle(for categoryID: String?) -> String {
        if let categoryID,
           let category = appState.settings.category(for: categoryID) {
            return category.title
        }
        return strings.tabStudy
    }
}

private struct NotificationRecordDestination: View {
    @EnvironmentObject private var appState: AppState
    var recordID: String
    @State private var loadedRecord: StudyRecord?
    @State private var isLoading = true

    private var strings: AppStrings {
        appState.strings
    }

    private var record: StudyRecord? {
        loadedRecord ?? appState.studyRecords.first(where: { $0.id == recordID })
    }

    var body: some View {
        Group {
            if let record {
                recordContent(record)
            } else if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 260, alignment: .center)
            } else {
                ContentUnavailableView(
                    strings.notificationQuestionMissingTitle,
                    systemImage: "trash",
                    description: Text(strings.notificationQuestionUnavailableHelp)
                )
            }
        }
        .navigationTitle(strings.recordDetail)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadRecordIfNeeded()
        }
    }

    @ViewBuilder
    private func recordContent(_ record: StudyRecord) -> some View {
        StudyRecordDetailView(record: record)
            .padding(.horizontal, 16)
    }

    private func loadRecordIfNeeded() async {
        guard record == nil else {
            return
        }

        isLoading = true
        defer { isLoading = false }

        do {
            loadedRecord = try await appState.fetchBackendNotificationRecord(recordID: recordID)
        } catch {
            await appState.refreshBackendRecords()
        }
    }
}

private struct NotificationCommunityQuestionDestination: View {
    @EnvironmentObject private var appState: AppState
    var questionID: String
    @State private var loadedQuestion: CommunityQuestion?
    @State private var isLoading = true

    private var strings: AppStrings {
        appState.strings
    }

    private var question: CommunityQuestion? {
        loadedQuestion ?? appState.communityQuestions.first(where: { $0.id == questionID })
    }

    var body: some View {
        Group {
            if let question {
                CommunityQuestionDetailView(question: question)
            } else if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 260, alignment: .center)
            } else {
                ContentUnavailableView(strings.communityQuestion, systemImage: "bubble.left.and.bubble.right")
            }
        }
        .navigationTitle(strings.browseQuestions)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: questionID) {
            await loadQuestionIfNeeded()
        }
    }

    private func loadQuestionIfNeeded() async {
        if let existing = appState.communityQuestions.first(where: { $0.id == questionID }) {
            loadedQuestion = existing
            isLoading = false
            return
        }

        isLoading = true
        loadedQuestion = await appState.loadCommunityQuestionDetail(questionID: questionID)
        isLoading = false
    }
}

private struct NotificationPublicQuestionsDestination: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedQuestionRoute: CommunityQuestionRoute?

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        List {
            if appState.communityQuestions.isEmpty && !appState.isLoadingCommunityQuestions {
                MobileCommunityEmptyState(strings: strings)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(appState.communityQuestions) { question in
                    Button {
                        selectedQuestionRoute = CommunityQuestionRoute(id: question.id)
                    } label: {
                        MobileCommunityQuestionRow(question: question)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle(strings.browseQuestions)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $selectedQuestionRoute) { route in
            if let question = appState.communityQuestions.first(where: { $0.id == route.id }) {
                CommunityQuestionDetailView(question: question)
            } else {
                ContentUnavailableView(strings.communityQuestion, systemImage: "bubble.left.and.bubble.right")
            }
        }
        .task {
            if appState.communityQuestions.isEmpty {
                await appState.loadCommunityQuestions(reset: true, userInitiated: false)
            }
        }
    }
}

struct StudyTreePlacement: Identifiable {
    var room: BackendStudyRoom
    var center: CGPoint
    var id: Int { room.id }
}

struct StudyTreeEdge: Identifiable {
    var parentID: Int
    var childID: Int
    var parent: CGPoint
    var child: CGPoint
    var id = UUID()
}

struct StudyTreeLayoutSnapshot {
    static let nodeSize = CGSize(width: 112, height: 112)
    private static let margin: CGFloat = 44
    private static let siblingSpacing: CGFloat = 42
    private static let levelSpacing: CGFloat = 74

    var placements: [StudyTreePlacement]
    var centerByRoomID: [Int: CGPoint]
    var edges: [StudyTreeEdge]
    var size: CGSize

    init(root: BackendStudyRoom, rooms: [BackendStudyRoom]) {
        let roomByID = Dictionary(uniqueKeysWithValues: rooms.map { ($0.id, $0) })
        let childrenByParent = Dictionary(
            grouping: rooms.filter { $0.parentStudyId != nil },
            by: { $0.parentStudyId! }
        ).mapValues {
            $0.sorted {
                if $0.sortOrder == $1.sortOrder {
                    return $0.id < $1.id
                }
                return $0.sortOrder < $1.sortOrder
            }
        }

        var logicalPositions: [Int: CGPoint] = [:]
        var visited = Set<Int>()
        var nextLeaf: CGFloat = 0
        _ = Self.assignLogicalPosition(
            roomID: root.id,
            depth: 0,
            childrenByParent: childrenByParent,
            nextLeaf: &nextLeaf,
            visited: &visited,
            positions: &logicalPositions
        )

        let maxDepth = logicalPositions.values.map(\.y).max() ?? 0
        let maxLeaf = logicalPositions.values.map(\.x).max() ?? 0
        let contentWidth = (maxLeaf + 1) * (Self.nodeSize.width + Self.siblingSpacing) - Self.siblingSpacing + Self.margin * 2
        let verticalHeight = (maxDepth + 1) * (Self.nodeSize.height + Self.levelSpacing) - Self.levelSpacing + Self.margin * 2

        func renderedCenter(_ point: CGPoint) -> CGPoint {
            CGPoint(
                x: Self.margin
                    + Self.nodeSize.width / 2
                    + point.x * (Self.nodeSize.width + Self.siblingSpacing),
                y: Self.margin + Self.nodeSize.height / 2 + point.y * (Self.nodeSize.height + Self.levelSpacing)
            )
        }

        placements = logicalPositions.compactMap { id, point in
            guard let room = roomByID[id] else {
                return nil
            }
            return StudyTreePlacement(room: room, center: renderedCenter(point))
        }
        .sorted {
            if $0.center.y == $1.center.y {
                return $0.center.x < $1.center.x
            }
            return $0.center.y < $1.center.y
        }
        centerByRoomID = Dictionary(
            uniqueKeysWithValues: placements.map { ($0.id, $0.center) }
        )

        edges = logicalPositions.flatMap { parentID, parentPoint in
            (childrenByParent[parentID] ?? []).compactMap { child in
                guard let childPoint = logicalPositions[child.id] else {
                    return nil
                }
                return StudyTreeEdge(
                    parentID: parentID,
                    childID: child.id,
                    parent: renderedCenter(parentPoint),
                    child: renderedCenter(childPoint)
                )
            }
        }

        size = CGSize(width: contentWidth, height: verticalHeight)
    }

    private static func assignLogicalPosition(
        roomID: Int,
        depth: Int,
        childrenByParent: [Int: [BackendStudyRoom]],
        nextLeaf: inout CGFloat,
        visited: inout Set<Int>,
        positions: inout [Int: CGPoint]
    ) -> CGFloat {
        guard visited.insert(roomID).inserted else {
            return nextLeaf
        }

        let children = childrenByParent[roomID] ?? []
        let childPositions = children.map { child in
            assignLogicalPosition(
                roomID: child.id,
                depth: depth + 1,
                childrenByParent: childrenByParent,
                nextLeaf: &nextLeaf,
                visited: &visited,
                positions: &positions
            )
        }

        let leafPosition: CGFloat
        if let first = childPositions.first,
           let last = childPositions.last {
            leafPosition = (first + last) / 2
        } else {
            leafPosition = nextLeaf
            nextLeaf += 1
        }
        positions[roomID] = CGPoint(x: leafPosition, y: CGFloat(depth))
        return leafPosition
    }
}

private enum StudyTopicAddMode: String {
    case recommendation
    case manual
}

private enum StudyTreeSelectionMode {
    case activation
    case deletion
}

private struct StudyTopicAddRequest: Identifiable {
    let id = UUID()
    var parent: BackendStudyRoom
    var mode: StudyTopicAddMode
}

private struct StudyTopicAddOutcome {
    var addedTopics: [String]
    var failedTopics: [String]
}

struct MobileStudyTreeView: View {
    @EnvironmentObject private var appState: AppState
    @State private var addRequest: StudyTopicAddRequest?
    @State private var editingRoom: BackendStudyRoom?
    @State private var selectedRoomID: Int?
    @State private var selectedRoomIDs = Set<Int>()
    @State private var nodeOffsets: [Int: CGSize] = [:]
    @State private var dragStartOffsets: [Int: CGSize] = [:]
    @State private var dragStartCanvasTranslations: [Int: CGSize] = [:]
    @State private var dragStartViewportOffsets: [Int: CGPoint] = [:]
    @State private var dragStartCanvasAlignmentInsets: [Int: CGSize] = [:]
    @State private var zoomScale: CGFloat = 1
    @State private var zoomStartScale: CGFloat = 1
    @State private var zoomStartViewportOffset: CGPoint = .zero
    @State private var zoomStartCanvasAlignmentInset: CGSize = .zero
    @State private var isZoomGestureActive = false
    @State private var viewportOffset: CGPoint = .zero
    @State private var treeViewportSize: CGSize = .zero
    @State private var canvasAlignmentInset: CGSize = .zero
    @State private var hasLoadedTreeState = false
    @State private var hasFinishedInitialRefresh = false
    @State private var hasAppliedInitialViewportFit = false
    @State private var hasUserInteractedWithTree = false
    @State private var shouldFitInitialViewport = false
    @State private var isPreparingInitialViewport = true
    @State private var selectionMode: StudyTreeSelectionMode?
    @State private var showsDeleteConfirmation = false
    @State private var deletionCandidate: BackendStudyRoom?

    var rootStudyID: Int

    private var strings: AppStrings {
        appState.strings
    }

    private var root: BackendStudyRoom? {
        appState.backendStudyRoom(id: rootStudyID)
    }

    private var isSelectionMode: Bool {
        selectionMode != nil
    }

    private var snapshot: StudyTreeLayoutSnapshot? {
        guard let root else {
            return nil
        }
        return StudyTreeLayoutSnapshot(
            root: root,
            rooms: appState.backendStudyRooms
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            if isSelectionMode {
                HStack(spacing: 10) {
                    Text(strings.selectedTopicCount(selectedRoomIDs.count))
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(strings.done) {
                        completeSelection()
                    }
                    .disabled(selectionMode == .deletion && selectedRoomIDs.isEmpty)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemBackground))
            }

            if let snapshot {
                GeometryReader { geometry in
                    let canvasLayout = expandedCanvasLayout(for: snapshot)
                    let scaledCanvasSize = CGSize(
                        width: canvasLayout.size.width * zoomScale,
                        height: canvasLayout.size.height * zoomScale
                    )
                    ZStack(alignment: .bottom) {
                        ScrollView([.horizontal, .vertical]) {
                            ZStack(alignment: .topLeading) {
                                ZStack(alignment: .topLeading) {
                                    Canvas { context, _ in
                                        for edge in snapshot.edges {
                                            let parent = edge.parent.adding(
                                                renderedNodeOffset(
                                                    for: edge.parentID,
                                                    canvasLayout: canvasLayout
                                                )
                                            )
                                            let child = edge.child.adding(
                                                renderedNodeOffset(
                                                    for: edge.childID,
                                                    canvasLayout: canvasLayout
                                                )
                                            )
                                            guard let geometry = StudyTreeEdgePolicy.directionalGeometry(
                                                parent: parent,
                                                child: child,
                                                nodeRadius: StudyTreeLayoutSnapshot.nodeSize.width / 2 + 4
                                            ) else {
                                                continue
                                            }
                                            var path = Path()
                                            path.move(to: geometry.start)
                                            let midpoint = (geometry.start.y + geometry.end.y) / 2
                                            path.addCurve(
                                                to: geometry.end,
                                                control1: CGPoint(x: geometry.start.x, y: midpoint),
                                                control2: CGPoint(x: geometry.end.x, y: midpoint)
                                            )
                                            let edgeColor = Color.secondary.opacity(0.48)
                                            context.stroke(path, with: .color(edgeColor), lineWidth: 1.7)

                                            var arrow = Path()
                                            arrow.move(to: geometry.end)
                                            arrow.addLine(to: geometry.arrowLeft)
                                            arrow.addLine(to: geometry.arrowRight)
                                            arrow.closeSubpath()
                                            context.fill(arrow, with: .color(edgeColor))
                                        }
                                    }

                                    ForEach(snapshot.placements) { placement in
                                        StudyTreeNode(
                                            room: placement.room,
                                            strings: strings,
                                            hasPendingQuestion:
                                                appState.pendingQuestionCount(
                                                    categoryID: String(placement.room.id)
                                                ) > 0,
                                            isSelectionMode: isSelectionMode,
                                            isSelected: selectedRoomIDs.contains(placement.room.id),
                                            onOpen: { selectedRoomID = placement.room.id },
                                            onSelect: { toggleSelection(placement.room.id) },
                                            onAddRecommendedChild: {
                                                addRequest = StudyTopicAddRequest(
                                                    parent: placement.room,
                                                    mode: .recommendation
                                                )
                                            },
                                            onAddManualChild: {
                                                addRequest = StudyTopicAddRequest(
                                                    parent: placement.room,
                                                    mode: .manual
                                                )
                                            },
                                            onEdit: { editingRoom = placement.room },
                                            onDelete: { deletionCandidate = placement.room }
                                        )
                                        .position(placement.center)
                                        .offset(
                                            renderedNodeOffset(
                                                for: placement.room.id,
                                                canvasLayout: canvasLayout
                                            )
                                        )
                                        .highPriorityGesture(
                                            nodeDragGesture(for: placement.room.id, in: snapshot)
                                        )
                                    }
                                }
                                .frame(width: canvasLayout.size.width, height: canvasLayout.size.height)
                                .scaleEffect(zoomScale, anchor: .topLeading)
                                .frame(
                                    width: scaledCanvasSize.width,
                                    height: scaledCanvasSize.height,
                                    alignment: .topLeading
                                )
                                .offset(
                                    x: canvasAlignmentInset.width,
                                    y: canvasAlignmentInset.height
                                )
                            }
                            .frame(
                                width: max(
                                    geometry.size.width,
                                    scaledCanvasSize.width + canvasAlignmentInset.width
                                ),
                                height: max(
                                    geometry.size.height,
                                    scaledCanvasSize.height + canvasAlignmentInset.height
                                ),
                                alignment: .topLeading
                            )
                            .background {
                                StudyTreeScrollViewportBridge(
                                    contentOffset: viewportOffset,
                                    isReportingEnabled:
                                        dragStartOffsets.isEmpty
                                            && !isZoomGestureActive
                                ) { offset in
                                    viewportOffset = offset
                                } onContentOffsetSettled: { offset in
                                    saveViewport(contentOffset: offset)
                                }
                                .frame(width: 0, height: 0)
                                .allowsHitTesting(false)
                            }
                        }
                        .scrollDisabled(!dragStartOffsets.isEmpty)
                        .opacity(isPreparingInitialViewport ? 0 : 1)
                        .allowsHitTesting(!isPreparingInitialViewport)
                        .simultaneousGesture(viewportPanGesture)
                        .simultaneousGesture(zoomGesture)

                        if isPreparingInitialViewport {
                            ProgressView()
                                .controlSize(.regular)
                        }
                    }
                    .onAppear {
                        updateTreeViewportSize(geometry.size, snapshot: snapshot)
                    }
                    .onChange(of: geometry.size) { _, newSize in
                        updateTreeViewportSize(newSize, snapshot: snapshot)
                    }
                }
            } else {
                Text(strings.loading)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 260)
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(strings.studyTree)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(strings.studyTree)
                    .font(.headline)
            }
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarTrailing) {
                    if !isSelectionMode {
                        treeOptionsMenu
                    }
                }
                .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .topBarTrailing) {
                    if !isSelectionMode {
                        treeOptionsMenu
                    }
                }
            }
        }
        .navigationDestination(item: $selectedRoomID) { roomID in
            if let room = appState.backendStudyRoom(id: roomID) {
                StudyView(preferredCategoryID: String(room.id))
                    .padding(.horizontal, 16)
                    .mobileTabTitle(room.topic)
            }
        }
        .sheet(item: $addRequest) { request in
            StudyTopicAddSheet(
                parent: request.parent,
                strings: strings,
                initialMode: request.mode
            ) { titles, difficulty in
                await addChildStudyTopics(
                    titles,
                    difficulty: difficulty,
                    parent: request.parent
                )
            }
            .environmentObject(appState)
        }
        .sheet(item: $editingRoom) { room in
            StudyTopicLevelSheet(
                room: room,
                strings: strings,
                onDelete: {
                    appState.deleteStudyCategory(id: String(room.id))
                }
            ) { title, difficulty, isActive in
                appState.updateStudyTreeCategory(
                    roomID: room.id,
                    title: title,
                    difficulty: difficulty
                )
                if isActive != room.activeForQuestions {
                    appState.setStudyTopicActive(studyID: room.id, active: isActive)
                }
            }
        }
        .task {
            loadTreeState()
            await appState.refreshVisibleData()
            hasFinishedInitialRefresh = true
            guard let snapshot else {
                isPreparingInitialViewport = false
                return
            }
            sanitizeNodeOffsets(for: snapshot)
            fitInitialViewportIfNeeded(for: snapshot)
        }
        .confirmationDialog(
            strings.deleteSelectedTopics,
            isPresented: $showsDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(strings.deleteNotification, role: .destructive) {
                appState.deleteStudyCategories(ids: selectedRoomIDs)
                endSelection()
            }
            Button(strings.cancel, role: .cancel) {}
        }
        .confirmationDialog(
            deletionCandidate.map { strings.deleteStudySubtree($0.topic) } ?? strings.deleteStudy,
            isPresented: Binding(
                get: { deletionCandidate != nil },
                set: { isPresented in
                    if !isPresented {
                        deletionCandidate = nil
                    }
                }
            ),
            titleVisibility: .visible
        ) {
            if let deletionCandidate {
                Button(strings.deleteStudy, role: .destructive) {
                    appState.deleteStudyCategory(id: String(deletionCandidate.id))
                    self.deletionCandidate = nil
                }
            }
            Button(strings.cancel, role: .cancel) {
                deletionCandidate = nil
            }
        }
    }

    private var treeOptionsMenu: some View {
        Menu {
            Button {
                beginSelection(.activation)
            } label: {
                Label(strings.activateTopics, systemImage: "checkmark.circle")
            }
            Button(role: .destructive) {
                beginSelection(.deletion)
            } label: {
                Label(strings.deleteTopics, systemImage: "trash")
            }
            Button {
                resetTreeLayout()
            } label: {
                Label(strings.resetTreeLayout, systemImage: "arrow.counterclockwise")
            }
        } label: {
            Image(systemName: "ellipsis")
                .frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.more)
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                cancelPendingInitialViewportFit()
                if !isZoomGestureActive {
                    isZoomGestureActive = true
                    zoomStartScale = zoomScale
                    zoomStartViewportOffset = viewportOffset
                    zoomStartCanvasAlignmentInset = canvasAlignmentInset
                }
                let nextScale = min(
                    max(
                        zoomStartScale * value.magnification,
                        StudyTreeViewportPolicy.minimumZoomScale
                    ),
                    StudyTreeViewportPolicy.maximumZoomScale
                )
                let anchor = CGPoint(
                    x: value.startAnchor.x * treeViewportSize.width,
                    y: value.startAnchor.y * treeViewportSize.height
                )
                let canvasSize = snapshot.map {
                    expandedCanvasLayout(for: $0).size
                } ?? treeViewportSize
                let targetAlignmentInset =
                    StudyTreeViewportPolicy.centeredCanvasAlignmentInset(
                        canvasSize: canvasSize,
                        viewportSize: treeViewportSize,
                        zoomScale: nextScale
                    )
                viewportOffset = StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                    startOffset: zoomStartViewportOffset,
                    anchor: anchor,
                    canvasSize: canvasSize,
                    viewportSize: treeViewportSize,
                    startAlignmentInset: zoomStartCanvasAlignmentInset,
                    targetAlignmentInset: targetAlignmentInset,
                    startScale: zoomStartScale,
                    targetScale: nextScale
                )
                canvasAlignmentInset = targetAlignmentInset
                zoomScale = nextScale
            }
            .onEnded { _ in
                isZoomGestureActive = false
                zoomStartScale = zoomScale
                zoomStartViewportOffset = viewportOffset
                saveViewport()
            }
    }

    private var viewportPanGesture: some Gesture {
        DragGesture(minimumDistance: 3)
            .onChanged { _ in
                cancelPendingInitialViewportFit()
            }
    }

    private func nodeDragGesture(
        for roomID: Int,
        in snapshot: StudyTreeLayoutSnapshot
    ) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .global)
            .onChanged { value in
                guard !isSelectionMode else { return }
                cancelPendingInitialViewportFit()
                let initial = dragStartOffsets[roomID]
                    ?? StudyTreeCanvasPolicy.sanitizedOffset(nodeOffsets[roomID] ?? .zero)
                if dragStartOffsets[roomID] == nil {
                    let startLayout = expandedCanvasLayout(for: snapshot)
                    dragStartCanvasTranslations[roomID] = startLayout.translation
                    dragStartViewportOffsets[roomID] = viewportOffset
                    dragStartCanvasAlignmentInsets[roomID] = canvasAlignmentInset
                }
                dragStartOffsets[roomID] = initial
                let proposedOffset = CGSize(
                    width: initial.width + value.translation.width / zoomScale,
                    height: initial.height + value.translation.height / zoomScale
                )
                nodeOffsets[roomID] = StudyTreeCanvasPolicy.sanitizedOffset(proposedOffset)
                let expandedLayout = expandedCanvasLayout(for: snapshot)
                let compensation = StudyTreeViewportPolicy
                    .compensationPreservingCanvasTranslation(
                        startOffset: dragStartViewportOffsets[roomID] ?? viewportOffset,
                        startAlignmentInset:
                            dragStartCanvasAlignmentInsets[roomID] ?? canvasAlignmentInset,
                        startCanvasTranslation:
                            dragStartCanvasTranslations[roomID] ?? expandedLayout.translation,
                        targetCanvasTranslation: expandedLayout.translation,
                        zoomScale: zoomScale
                    )
                viewportOffset = compensation.viewportOffset
                canvasAlignmentInset = compensation.alignmentInset
            }
            .onEnded { _ in
                dragStartOffsets[roomID] = nil
                dragStartCanvasTranslations[roomID] = nil
                dragStartViewportOffsets[roomID] = nil
                dragStartCanvasAlignmentInsets[roomID] = nil
                saveNodeOffsets()
                saveViewport()
            }
    }

    private func toggleSelection(_ roomID: Int) {
        if selectedRoomIDs.contains(roomID) {
            selectedRoomIDs.remove(roomID)
        } else {
            selectedRoomIDs.insert(roomID)
        }
    }

    private func completeSelection() {
        switch selectionMode {
        case .activation:
            saveTopicActivationSelection()
        case .deletion:
            showsDeleteConfirmation = true
        case nil:
            break
        }
    }

    private func beginSelection(_ mode: StudyTreeSelectionMode) {
        selectionMode = mode
        switch mode {
        case .activation:
            selectedRoomIDs = Set(
                snapshot?.placements.compactMap { placement in
                    placement.room.activeForQuestions ? placement.room.id : nil
                } ?? []
            )
        case .deletion:
            selectedRoomIDs = []
        }
    }

    private func saveTopicActivationSelection() {
        guard let snapshot else {
            endSelection()
            return
        }
        let activeRoomIDs = Set(
            snapshot.placements.compactMap { placement in
                placement.room.activeForQuestions ? placement.room.id : nil
            }
        )
        appState.setStudyTopicsActive(
            studyIDs: selectedRoomIDs.subtracting(activeRoomIDs),
            active: true
        )
        appState.setStudyTopicsActive(
            studyIDs: activeRoomIDs.subtracting(selectedRoomIDs),
            active: false
        )
        endSelection()
    }

    private func addChildStudyTopics(
        _ titles: [String],
        difficulty: Difficulty,
        parent: BackendStudyRoom
    ) async -> StudyTopicAddOutcome {
        let existingRoomIDs = Set(snapshot?.placements.map(\.id) ?? [])
        let addedTopics = await appState.addChildStudyCategories(
            titles,
            parentStudyID: parent.id,
            difficulty: difficulty,
            customPrompt: StudySettings.defaultCustomPrompt,
            openAIModel: parent.openAIModel
        )
        let addedTopicSet = Set(addedTopics)
        let outcome = StudyTopicAddOutcome(
            addedTopics: addedTopics,
            failedTopics: titles.filter { !addedTopicSet.contains($0) }
        )
        guard !addedTopics.isEmpty, let updatedSnapshot = snapshot else {
            return outcome
        }

        let updatedRoomIDs = Set(updatedSnapshot.placements.map(\.id))
        let newRoomIDs = updatedRoomIDs.subtracting(existingRoomIDs)
        guard !newRoomIDs.isEmpty else {
            return outcome
        }
        nodeOffsets = StudyTreeCanvasPolicy.offsetsPlacingNewNodesWithoutSameLevelOverlap(
            newRoomIDs: newRoomIDs,
            baseCenters: updatedSnapshot.centerByRoomID,
            nodeOffsets: nodeOffsets,
            nodeSize: StudyTreeLayoutSnapshot.nodeSize
        )
        saveNodeOffsets()
        return outcome
    }

    private func endSelection() {
        selectionMode = nil
        selectedRoomIDs = []
    }

    private func resetTreeLayout() {
        guard let snapshot else {
            nodeOffsets = [:]
            saveNodeOffsets()
            return
        }

        cancelPendingInitialViewportFit()
        withAnimation(.snappy) {
            nodeOffsets = [:]
            applyFittedViewport(for: snapshot)
        }
        saveNodeOffsets()
        saveViewport()
    }

    private func loadTreeState() {
        guard !hasLoadedTreeState else {
            return
        }
        nodeOffsets = appState.loadStudyTreeNodeOffsets(rootStudyID: rootStudyID)
        let viewport = appState.loadStudyTreeViewport(rootStudyID: rootStudyID)
        let needsInitialFit = !appState.hasStudyTreeViewport(rootStudyID: rootStudyID)
            || viewport.canvasAlignmentX == nil
            || viewport.canvasAlignmentY == nil
        shouldFitInitialViewport = needsInitialFit
        isPreparingInitialViewport = needsInitialFit
        zoomScale = viewport.zoomScale
        zoomStartScale = viewport.zoomScale
        viewportOffset = CGPoint(
            x: viewport.contentOffsetX,
            y: viewport.contentOffsetY
        )
        zoomStartViewportOffset = viewportOffset
        canvasAlignmentInset = needsInitialFit
            ? .zero
            : CGSize(
                width: viewport.canvasAlignmentX ?? 0,
                height: viewport.canvasAlignmentY ?? 0
            )
        hasLoadedTreeState = true
    }

    private func expandedCanvasLayout(
        for snapshot: StudyTreeLayoutSnapshot
    ) -> StudyTreeCanvasLayout {
        StudyTreeCanvasPolicy.expandedLayout(
            baseCenters: snapshot.centerByRoomID,
            nodeOffsets: nodeOffsets,
            baseCanvasSize: snapshot.size,
            nodeSize: StudyTreeLayoutSnapshot.nodeSize
        )
    }

    private func renderedNodeOffset(
        for roomID: Int,
        canvasLayout: StudyTreeCanvasLayout
    ) -> CGSize {
        let nodeOffset = StudyTreeCanvasPolicy.sanitizedOffset(nodeOffsets[roomID] ?? .zero)
        return CGSize(
            width: nodeOffset.width + canvasLayout.translation.width,
            height: nodeOffset.height + canvasLayout.translation.height
        )
    }

    private func sanitizeNodeOffsets(for snapshot: StudyTreeLayoutSnapshot) {
        let sanitizedOffsets = nodeOffsets.reduce(into: [Int: CGSize]()) { result, entry in
            guard snapshot.centerByRoomID[entry.key] != nil else {
                return
            }
            result[entry.key] = StudyTreeCanvasPolicy.sanitizedOffset(entry.value)
        }
        guard sanitizedOffsets != nodeOffsets else {
            return
        }
        nodeOffsets = sanitizedOffsets
        saveNodeOffsets()
    }

    private func updateTreeViewportSize(
        _ size: CGSize,
        snapshot: StudyTreeLayoutSnapshot
    ) {
        guard size.width > 0, size.height > 0 else {
            return
        }
        treeViewportSize = size
        fitInitialViewportIfNeeded(for: snapshot)
    }

    private func fitInitialViewportIfNeeded(for snapshot: StudyTreeLayoutSnapshot) {
        guard StudyTreeViewportPolicy.shouldApplyInitialFit(
            isRequested: shouldFitInitialViewport,
            hasApplied: hasAppliedInitialViewportFit,
            hasUserInteracted: hasUserInteractedWithTree,
            hasFinishedRefresh: hasFinishedInitialRefresh,
            viewportSize: treeViewportSize
        ) else {
            return
        }
        hasAppliedInitialViewportFit = true
        shouldFitInitialViewport = false
        applyFittedViewport(for: snapshot)
        saveViewport()
        withAnimation(.easeOut(duration: 0.2)) {
            isPreparingInitialViewport = false
        }
    }

    private func cancelPendingInitialViewportFit() {
        hasUserInteractedWithTree = true
        shouldFitInitialViewport = false
        isPreparingInitialViewport = false
    }

    private func applyFittedViewport(for snapshot: StudyTreeLayoutSnapshot) {
        let canvasLayout = expandedCanvasLayout(for: snapshot)
        let fittedScale = StudyTreeViewportPolicy.fittedZoomScale(
            canvasSize: canvasLayout.size,
            viewportSize: treeViewportSize
        )
        zoomScale = fittedScale
        zoomStartScale = fittedScale
        canvasAlignmentInset = StudyTreeViewportPolicy.centeredCanvasAlignmentInset(
            canvasSize: canvasLayout.size,
            viewportSize: treeViewportSize,
            zoomScale: fittedScale
        )
        zoomStartCanvasAlignmentInset = canvasAlignmentInset
        viewportOffset = .zero
        zoomStartViewportOffset = .zero
    }

    private func saveNodeOffsets() {
        appState.saveStudyTreeNodeOffsets(nodeOffsets, rootStudyID: rootStudyID)
    }

    private func saveViewport(contentOffset: CGPoint? = nil) {
        guard hasLoadedTreeState, !shouldFitInitialViewport else {
            return
        }
        let contentOffset = contentOffset ?? viewportOffset
        appState.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: zoomScale,
                contentOffsetX: contentOffset.x,
                contentOffsetY: contentOffset.y,
                canvasAlignmentX: canvasAlignmentInset.width,
                canvasAlignmentY: canvasAlignmentInset.height
            ),
            rootStudyID: rootStudyID
        )
    }
}

private struct StudyTreeScrollViewportBridge: UIViewRepresentable {
    var contentOffset: CGPoint
    var isReportingEnabled: Bool
    var onContentOffsetChange: (CGPoint) -> Void
    var onContentOffsetSettled: (CGPoint) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            isReportingEnabled: isReportingEnabled,
            onContentOffsetChange: onContentOffsetChange,
            onContentOffsetSettled: onContentOffsetSettled
        )
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        context.coordinator.update(
            from: view,
            contentOffset: contentOffset,
            isReportingEnabled: isReportingEnabled,
            onContentOffsetChange: onContentOffsetChange,
            onContentOffsetSettled: onContentOffsetSettled
        )
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.update(
            from: view,
            contentOffset: contentOffset,
            isReportingEnabled: isReportingEnabled,
            onContentOffsetChange: onContentOffsetChange,
            onContentOffsetSettled: onContentOffsetSettled
        )
    }

    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    @MainActor
    final class Coordinator: NSObject {
        private weak var scrollView: UIScrollView?
        private var scrollCompletionTask: Task<Void, Never>?
        private var retryTask: Task<Void, Never>?
        private var requestedContentOffset: CGPoint = .zero
        private var onContentOffsetChange: (CGPoint) -> Void
        private var onContentOffsetSettled: (CGPoint) -> Void
        private var isApplyingContentOffset = false
        private var isReportingEnabled: Bool
        private var requiresNewPanBeforeReporting = false
        private var retryCount = 0

        init(
            isReportingEnabled: Bool,
            onContentOffsetChange: @escaping (CGPoint) -> Void,
            onContentOffsetSettled: @escaping (CGPoint) -> Void
        ) {
            self.isReportingEnabled = isReportingEnabled
            self.onContentOffsetChange = onContentOffsetChange
            self.onContentOffsetSettled = onContentOffsetSettled
            super.init()
        }

        func update(
            from view: UIView,
            contentOffset: CGPoint,
            isReportingEnabled: Bool,
            onContentOffsetChange: @escaping (CGPoint) -> Void,
            onContentOffsetSettled: @escaping (CGPoint) -> Void
        ) {
            requestedContentOffset = CGPoint(
                x: max(0, contentOffset.x),
                y: max(0, contentOffset.y)
            )
            self.isReportingEnabled = isReportingEnabled
            if !isReportingEnabled {
                scrollCompletionTask?.cancel()
                requiresNewPanBeforeReporting = true
            }
            self.onContentOffsetChange = onContentOffsetChange
            self.onContentOffsetSettled = onContentOffsetSettled
            attachIfNeeded(from: view)
            applyRequestedContentOffset(from: view)
        }

        func detach() {
            scrollCompletionTask?.cancel()
            retryTask?.cancel()
            scrollView?.panGestureRecognizer.removeTarget(
                self,
                action: #selector(handlePanGesture(_:))
            )
            scrollView = nil
        }

        private func attachIfNeeded(from view: UIView) {
            guard scrollView == nil,
                  let scrollView = view.studyTreeEnclosingScrollView() else {
                return
            }

            self.scrollView = scrollView
            scrollView.panGestureRecognizer.addTarget(
                self,
                action: #selector(handlePanGesture(_:))
            )
        }

        private func applyRequestedContentOffset(from view: UIView) {
            guard let scrollView = scrollView ?? view.studyTreeEnclosingScrollView() else {
                scheduleRetry(from: view)
                return
            }

            if self.scrollView == nil {
                attachIfNeeded(from: view)
            }

            guard scrollView.contentSize.width > 0,
                  scrollView.contentSize.height > 0 else {
                scheduleRetry(from: view)
                return
            }

            retryCount = 0
            let leadingInset = CGSize(
                width: scrollView.adjustedContentInset.left,
                height: scrollView.adjustedContentInset.top
            )
            let maximumOffset =
                StudyTreeViewportPolicy.maximumNormalizedContentOffset(
                    contentSize: scrollView.contentSize,
                    viewportSize: scrollView.bounds.size,
                    totalInset: CGSize(
                        width: scrollView.adjustedContentInset.left
                            + scrollView.adjustedContentInset.right,
                        height: scrollView.adjustedContentInset.top
                            + scrollView.adjustedContentInset.bottom
                    )
                )
            let clampedNormalizedOffset = CGPoint(
                x: min(requestedContentOffset.x, maximumOffset.x),
                y: min(requestedContentOffset.y, maximumOffset.y)
            )
            let rawTargetOffset = StudyTreeViewportPolicy.rawContentOffset(
                normalizedContentOffset: clampedNormalizedOffset,
                leadingInset: leadingInset
            )
            guard abs(scrollView.contentOffset.x - rawTargetOffset.x) > 0.5
                    || abs(scrollView.contentOffset.y - rawTargetOffset.y) > 0.5 else {
                return
            }

            isApplyingContentOffset = true
            scrollView.setContentOffset(rawTargetOffset, animated: false)
            isApplyingContentOffset = false
        }

        private func scheduleRetry(from view: UIView) {
            guard retryCount < 12, retryTask == nil else {
                return
            }
            retryCount += 1
            retryTask = Task { @MainActor [weak self, weak view] in
                try? await Task.sleep(for: .milliseconds(50))
                guard let self, let view, !Task.isCancelled else {
                    return
                }
                self.retryTask = nil
                self.attachIfNeeded(from: view)
                self.applyRequestedContentOffset(from: view)
            }
        }

        @objc
        private func handlePanGesture(_ gesture: UIPanGestureRecognizer) {
            guard let scrollView,
                  isReportingEnabled else {
                return
            }

            switch gesture.state {
            case .began:
                scrollCompletionTask?.cancel()
                requiresNewPanBeforeReporting = false
                reportCurrentContentOffset(from: scrollView)
            case .changed:
                guard !requiresNewPanBeforeReporting else {
                    return
                }
                reportCurrentContentOffset(from: scrollView)
            case .ended, .cancelled:
                guard !requiresNewPanBeforeReporting else {
                    return
                }
                trackScrollCompletion(from: scrollView)
            default:
                break
            }
        }

        private func reportCurrentContentOffset(from scrollView: UIScrollView) {
            guard !isApplyingContentOffset,
                  isReportingEnabled,
                  !requiresNewPanBeforeReporting else {
                return
            }
            let reportedContentOffset =
                StudyTreeViewportPolicy.normalizedContentOffset(
                    rawContentOffset: scrollView.contentOffset,
                    leadingInset: CGSize(
                        width: scrollView.adjustedContentInset.left,
                        height: scrollView.adjustedContentInset.top
                    )
                )
            requestedContentOffset = reportedContentOffset
            onContentOffsetChange(reportedContentOffset)
        }

        private func trackScrollCompletion(from scrollView: UIScrollView) {
            scrollCompletionTask?.cancel()
            scrollCompletionTask = Task { @MainActor [weak self, weak scrollView] in
                try? await Task.sleep(for: .milliseconds(16))
                guard let self, let scrollView, !Task.isCancelled else {
                    return
                }
                repeat {
                    self.reportCurrentContentOffset(from: scrollView)
                    guard scrollView.isDecelerating else {
                        break
                    }
                    try? await Task.sleep(for: .milliseconds(16))
                } while !Task.isCancelled

                guard !Task.isCancelled,
                      self.isReportingEnabled,
                      !self.requiresNewPanBeforeReporting else {
                    return
                }
                self.onContentOffsetSettled(self.requestedContentOffset)
            }
        }
    }
}

private extension UIView {
    func studyTreeEnclosingScrollView() -> UIScrollView? {
        var current = superview
        while let view = current {
            if let scrollView = view as? UIScrollView {
                return scrollView
            }
            current = view.superview
        }
        return nil
    }
}

private struct StudyTreeNode: View {
    var room: BackendStudyRoom
    var strings: AppStrings
    var hasPendingQuestion: Bool
    var isSelectionMode: Bool
    var isSelected: Bool
    var onOpen: () -> Void
    var onSelect: () -> Void
    var onAddRecommendedChild: () -> Void
    var onAddManualChild: () -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void

    private var levelProgressColor: Color {
        room.activeForQuestions ? Color.green : Color.secondary.opacity(0.6)
    }

    private var levelFillFraction: CGFloat {
        StudyTreeNodeStylePolicy.levelFillFraction(room.difficultyLevel)
    }

    var body: some View {
        Button {
            isSelectionMode ? onSelect() : onOpen()
        } label: {
            VStack(spacing: 5) {
                Text(room.topic)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)

                Text(StudyTreeNodeStylePolicy.levelText(room.difficultyLevel))
                    .font(.caption2.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(room.activeForQuestions ? Color.green : Color.secondary)
            }
            .padding(12)
            .frame(
                width: StudyTreeLayoutSnapshot.nodeSize.width,
                height: StudyTreeLayoutSnapshot.nodeSize.height,
                alignment: .center
            )
            .background {
                Circle()
                    .fill(Color(.secondarySystemBackground))
            }
            .overlay {
                Circle()
                    .strokeBorder(Color.secondary.opacity(0.22), lineWidth: 2.5)
            }
            .overlay {
                Circle()
                    .trim(from: 0, to: levelFillFraction)
                    .stroke(
                        levelProgressColor,
                        style: StrokeStyle(lineWidth: 3, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .padding(1.5)
            }
            .overlay {
                if isSelected {
                    Circle()
                        .strokeBorder(Color.accentColor, lineWidth: 3)
                        .padding(-4)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .contentShape(.contextMenuPreview, Circle())
        .contextMenu {
            if !isSelectionMode {
                Button(action: onEdit) {
                    Label(strings.editStudyCategory, systemImage: "pencil")
                }
                Button(role: .destructive, action: onDelete) {
                    Label(strings.deleteStudy, systemImage: "trash")
                }
            }
        }
        .overlay(alignment: .topLeading) {
            if isSelectionMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .background(Color(.systemBackground), in: Circle())
                    .offset(x: -3, y: -3)
            }
        }
        .overlay(alignment: .topTrailing) {
            if !isSelectionMode, hasPendingQuestion {
                Text("1")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(Color.red, in: Circle())
                    .overlay {
                        Circle()
                            .stroke(Color(.systemBackground), lineWidth: 2)
                    }
                    .offset(x: 5, y: -5)
                    .accessibilityLabel(strings.pendingQuestionCount(1))
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if !isSelectionMode {
                Menu {
                    Button(action: onAddRecommendedChild) {
                        Label(strings.recommendSubstudy, systemImage: "sparkles")
                    }
                    Button(action: onAddManualChild) {
                        Label(strings.addTopicManually, systemImage: "square.and.pencil")
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.caption.weight(.bold))
                        .frame(width: 28, height: 28)
                        .background(Color(.systemBackground), in: Circle())
                }
                .accessibilityLabel(strings.addSubstudy)
                .offset(x: 4, y: 4)
            }
        }
        .frame(
            width: StudyTreeLayoutSnapshot.nodeSize.width,
            height: StudyTreeLayoutSnapshot.nodeSize.height
        )
        .accessibilityAction(named: strings.editStudyCategory, onEdit)
        .accessibilityAction(named: strings.deleteStudy, onDelete)
    }
}

private extension CGPoint {
    func adding(_ offset: CGSize) -> CGPoint {
        CGPoint(x: x + offset.width, y: y + offset.height)
    }
}

private struct StudyTopicAddSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    var parent: BackendStudyRoom
    var strings: AppStrings
    var initialMode: StudyTopicAddMode
    var onAdd: ([String], Difficulty) async -> StudyTopicAddOutcome

    @State private var suggestions: [String] = []
    @State private var difficultyLevel: Double
    @State private var manualTopic = ""
    @State private var mode: StudyTopicAddMode
    @State private var selectedSuggestions = Set<String>()
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var inlineMessage: String?

    init(
        parent: BackendStudyRoom,
        strings: AppStrings,
        initialMode: StudyTopicAddMode,
        onAdd: @escaping ([String], Difficulty) async -> StudyTopicAddOutcome
    ) {
        self.parent = parent
        self.strings = strings
        self.initialMode = initialMode
        self.onAdd = onAdd
        _difficultyLevel = State(initialValue: Double(parent.difficultyLevel))
        _mode = State(initialValue: initialMode)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    VStack(spacing: 4) {
                        Text(strings.addSubstudy)
                            .font(.headline)
                        Text(parent.topic)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    Picker("", selection: $mode) {
                        Text(strings.recommendSubstudyTab)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .tag(StudyTopicAddMode.recommendation)
                        Text(strings.addTopicManually)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .tag(StudyTopicAddMode.manual)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()

                    Group {
                        if mode == .recommendation {
                            recommendationPicker
                        } else {
                            TextField(strings.studyTopic, text: $manualTopic)
                                .textInputAutocapitalization(.sentences)
                                .submitLabel(.done)
                                .onSubmit {
                                    guard !selectedTopics.isEmpty, !isSaving else { return }
                                    add(selectedTopics)
                                }
                            .padding(.horizontal, 14)
                            .frame(height: 50)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 112, alignment: .top)

                    VStack(spacing: 8) {
                        HStack {
                            Text(strings.difficulty)
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Text("\(resolvedDifficulty) · \(Difficulty(level: resolvedDifficulty).displayName(language: strings.language))")
                                .font(.subheadline)
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                        Slider(value: $difficultyLevel, in: 1...10, step: 1)
                        if mode == .recommendation, selectedTopics.count > 1 {
                            Text(strings.sharedDifficultyDescription(selectedTopics.count))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }

                    if let inlineMessage {
                        Text(inlineMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Button {
                        add(selectedTopics)
                    } label: {
                        HStack(spacing: 8) {
                            if isSaving {
                                ProgressView()
                                    .tint(.white)
                            }
                            Text(
                                mode == .recommendation
                                    ? strings.addSelectedSubstudies(selectedTopics.count)
                                    : strings.addSubstudy
                            )
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 8))
                    .disabled(selectedTopics.isEmpty || isSaving)
                }
                .padding(20)
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }
            }
            .task {
                if initialMode == .recommendation {
                    await loadSuggestions()
                } else {
                    isLoading = false
                }
            }
            .onChange(of: mode) { _, newMode in
                guard newMode == .recommendation, suggestions.isEmpty, !isLoading else {
                    return
                }
                Task { await loadSuggestions() }
            }
            .interactiveDismissDisabled(isSaving)
        }
        .presentationDetents([.height(520), .large])
    }

    private var resolvedDifficulty: Int {
        min(max(Int(difficultyLevel.rounded()), 1), 10)
    }

    private var selectedTopics: [String] {
        switch mode {
        case .recommendation:
            return suggestions.filter(selectedSuggestions.contains)
        case .manual:
            let topic = manualTopic.trimmingCharacters(in: .whitespacesAndNewlines)
            return topic.isEmpty ? [] : [topic]
        }
    }

    @ViewBuilder
    private var recommendationPicker: some View {
        if isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 100)
        } else if suggestions.isEmpty {
            VStack(spacing: 10) {
                Text(
                    appState.studyTreeDepth(for: parent.id) >= 5
                        ? strings.studyTopicDepthLimit
                        : strings.recommendedTopicsEmpty
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if appState.studyTreeDepth(for: parent.id) < 5 {
                    Button(strings.retry) {
                        Task { await loadSuggestions() }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 100)
        } else {
            VStack(spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(strings.recommendSubstudy)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(strings.selectedTopicCount(selectedSuggestions.count))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(
                        selectedSuggestions.count == suggestions.count
                            ? strings.deselectAll
                            : strings.selectAll
                    ) {
                        if selectedSuggestions.count == suggestions.count {
                            selectedSuggestions.removeAll()
                        } else {
                            selectedSuggestions = Set(suggestions)
                        }
                        inlineMessage = nil
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.plain)
                    .disabled(isSaving)

                    Button {
                        Task { await loadSuggestions() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(strings.refreshRecommendations)
                    .disabled(isLoading || isSaving)
                }

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 8),
                        GridItem(.flexible(), spacing: 8)
                    ],
                    spacing: 8
                ) {
                    ForEach(suggestions.prefix(10), id: \.self) { topic in
                        let isSelected = selectedSuggestions.contains(topic)
                        Button {
                            if isSelected {
                                selectedSuggestions.remove(topic)
                            } else {
                                selectedSuggestions.insert(topic)
                            }
                            inlineMessage = nil
                        } label: {
                            HStack(alignment: .top, spacing: 6) {
                                Text(topic)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(3)
                                    .truncationMode(.tail)
                                    .multilineTextAlignment(.leading)
                                    .allowsTightening(true)
                                    .minimumScaleFactor(0.85)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                                    .fixedSize()
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .background(
                                isSelected
                                    ? Color.accentColor.opacity(0.08)
                                    : Color(.secondarySystemBackground)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .stroke(
                                        isSelected ? Color.accentColor : Color.secondary.opacity(0.18),
                                        lineWidth: 1
                                    )
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(isSaving)
                    }
                }
            }
        }
    }

    private func loadSuggestions() async {
        isLoading = true
        inlineMessage = nil
        let loadedSuggestions = await appState.suggestChildStudyTopics(parentStudyID: parent.id)
        let retainedSelection = selectedSuggestions.intersection(loadedSuggestions)
        suggestions = loadedSuggestions
        selectedSuggestions = retainedSelection.isEmpty
            ? Set(loadedSuggestions.prefix(1))
            : retainedSelection
        isLoading = false
    }

    private func add(_ rawTopics: [String]) {
        var normalizedTopics = Set<String>()
        let topics = rawTopics.compactMap { rawTopic -> String? in
            let topic = rawTopic.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !topic.isEmpty else {
                return nil
            }
            let normalized = normalizedTopic(topic)
            guard normalizedTopics.insert(normalized).inserted else {
                return nil
            }
            return topic
        }
        guard !topics.isEmpty else {
            return
        }

        let existingTopics = Set(
            appState.backendStudyRooms.map { normalizedTopic($0.topic) }
        )
        let newTopics = topics.filter {
            !existingTopics.contains(normalizedTopic($0))
        }
        guard !newTopics.isEmpty else {
            inlineMessage = strings.duplicateStudyTopic
            return
        }

        isSaving = true
        inlineMessage = nil
        Task {
            let outcome = await onAdd(
                newTopics,
                Difficulty(level: resolvedDifficulty)
            )
            isSaving = false
            if outcome.failedTopics.isEmpty {
                dismiss()
            } else {
                suggestions.removeAll {
                    outcome.addedTopics.contains($0)
                }
                selectedSuggestions = Set(outcome.failedTopics)
                inlineMessage = strings.partialSubstudyAddFailure(
                    added: outcome.addedTopics.count,
                    failed: outcome.failedTopics.count
                )
            }
        }
    }

    private func normalizedTopic(_ topic: String) -> String {
        topic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .filter { !$0.isWhitespace }
    }
}

struct StudyTopicLevelSheet: View {
    @Environment(\.dismiss) private var dismiss

    var room: BackendStudyRoom
    var strings: AppStrings
    var onDelete: () -> Void
    var onSave: (String, Difficulty, Bool) -> Void

    @State private var title: String
    @State private var difficultyLevel: Double
    @State private var isActive: Bool
    @State private var showsDeleteConfirmation = false

    init(
        room: BackendStudyRoom,
        strings: AppStrings,
        onDelete: @escaping () -> Void,
        onSave: @escaping (String, Difficulty, Bool) -> Void
    ) {
        self.room = room
        self.strings = strings
        self.onDelete = onDelete
        self.onSave = onSave
        _title = State(initialValue: room.topic)
        _difficultyLevel = State(initialValue: Double(room.difficultyLevel))
        _isActive = State(initialValue: room.activeForQuestions)
    }

    private var canSave: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(strings.studyTopic, text: $title)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(strings.difficulty)
                            Spacer()
                            Text(Difficulty(level: resolvedDifficulty).displayName(language: strings.language))
                                .fontWeight(.semibold)
                        }
                        Slider(value: $difficultyLevel, in: 1...10, step: 1)
                    }
                }

                Section {
                    Toggle(strings.questionTopicToggle, isOn: $isActive)

                    Text(strings.questionRotationHelp)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button(strings.deleteStudy, role: .destructive) {
                        showsDeleteConfirmation = true
                    }
                }
            }
            .navigationTitle(strings.editStudyCategory)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.save) {
                        onSave(
                            title.trimmingCharacters(in: .whitespacesAndNewlines),
                            Difficulty(level: resolvedDifficulty),
                            isActive
                        )
                        dismiss()
                    }
                    .disabled(!canSave)
                }
            }
            .confirmationDialog(strings.deleteStudy, isPresented: $showsDeleteConfirmation) {
                Button(strings.deleteStudy, role: .destructive) {
                    onDelete()
                    dismiss()
                }
                Button(strings.cancel, role: .cancel) {}
            }
        }
    }

    private var resolvedDifficulty: Int {
        min(max(Int(difficultyLevel.rounded()), 1), 10)
    }
}

private struct NotificationStudyListDestination: View {
    @EnvironmentObject private var appState: AppState

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        List(appState.studyCategoriesForDisplay) { category in
            NavigationLink {
                StudyView(preferredCategoryID: category.id)
                    .padding(.horizontal, 16)
                    .navigationTitle(category.title)
                    .navigationBarTitleDisplayMode(.inline)
            } label: {
                MobileHomeCategoryRow(
                    category: category,
                    hasPendingQuestion: appState.pendingQuestionCount(for: category) > 0,
                    strings: strings
                )
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle(strings.tabStudy)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct MobileNotificationRow: View {
    var notification: BackendAppNotification
    var referenceDate: Date
    var strings: AppStrings

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle()
                .fill(notification.isRead ? Color.secondary.opacity(0.18) : Color.accentColor)
                .frame(width: 10, height: 10)
                .padding(.top, 8)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(
                        strings.notificationTitle(
                            type: notification.type,
                            threadType: notification.threadType,
                            fallback: notification.title
                        )
                    )
                        .font(.body.weight(notification.isRead ? .semibold : .bold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Spacer(minLength: 8)

                    Text(
                        StudyDateDisplayFormatter.relativeOrShortDateString(
                            for: notification.createdAt,
                            relativeTo: referenceDate,
                            language: strings.language
                        )
                    )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Text(MarkdownContent.plainText(notification.body))
                    .font(.subheadline)
                    .foregroundStyle(notification.isRead ? .secondary : .primary)
                    .lineLimit(3)
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}

struct HomeProfileAvatar: View {
    var symbolName: String
    var displayName: String?
    var colorSeed: String? = "profile"
    var usesNeutralColor: Bool = false
    var size: CGFloat = 34

    var body: some View {
        PixelAvatarGlyph(
            avatarName: ProfileAvatarOption.canonicalName(for: symbolName),
            colorSeed: colorSeed ?? "avatar-color-sage",
            usesNeutralColor: usesNeutralColor
        )
        .frame(width: size, height: size)
        .clipShape(Circle())
        .contentShape(Circle())
        .accessibilityLabel(displayName ?? "")
    }
}

enum BuddyStudyAvatar {
    static let assetName = "BuddyStudyBrandLogo"
    static let symbolName = "pixel-fox"
}

struct ProfileAvatarSprite: View {
    var symbolName: String
    var colorSeed: String
    var usesNeutralColor: Bool
    var size: CGFloat

    var body: some View {
        PixelAvatarGlyph(
            avatarName: ProfileAvatarOption.canonicalName(for: symbolName),
            colorSeed: colorSeed,
            usesNeutralColor: usesNeutralColor
        )
            .frame(width: size, height: size)
            .clipShape(Circle())
    }
}

enum AvatarBuilderVisualRegistry {
    static let supportedBaseItemKeys: Set<String> = [
        "base-cat",
        "base-fox",
        "base-rabbit",
        "base-dog"
    ]

    static let supportedPartItemKeys: Set<String> = [
        "background-teal",
        "background-indigo",
        "background-slate",
        "top-hoodie-blue",
        "top-varsity-green",
        "top-sweater-rose",
        "bottom-denim-pants",
        "bottom-jogger-black",
        "bottom-shorts-tan",
        "shoes-white-sneakers",
        "shoes-brown-loafers",
        "shoes-blue-boots",
        "hat-beanie-navy",
        "hat-cap-orange",
        "hat-grad-black",
        "item-laptop",
        "item-book",
        "item-pencil"
    ]

    static let supportedItemKeys = supportedBaseItemKeys.union(supportedPartItemKeys)

    static func supportsItemKey(_ itemKey: String) -> Bool {
        supportedItemKeys.contains(normalized(itemKey))
    }

    static func baseItemKey(forSymbolName symbolName: String) -> String {
        let canonicalName = ProfileAvatarOption.canonicalName(for: symbolName)
        if canonicalName.contains("cat") {
            return "base-cat"
        }
        if canonicalName.contains("rabbit") {
            return "base-rabbit"
        }
        if canonicalName.contains("dog") {
            return "base-dog"
        }
        return "base-fox"
    }

    static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

private struct AvatarBuilderSprite: View {
    var config: [String: String]
    var catalog: AvatarCatalogResponse
    var fallbackSymbolName: String
    var colorSeed: String
    var usesNeutralColor: Bool
    var size: CGFloat

    private var resolvedConfig: [String: String] {
        catalog.defaultConfig.merging(config) { _, current in current }
    }

    private var baseKey: String {
        if let key = resolvedConfig["base"],
           AvatarBuilderVisualRegistry.supportedBaseItemKeys.contains(AvatarBuilderVisualRegistry.normalized(key)) {
            return AvatarBuilderVisualRegistry.normalized(key)
        }
        return AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: fallbackSymbolName)
    }

    private var backgroundColor: Color {
        if let item = catalog.item(for: resolvedConfig["background"]) {
            return Color.avatarHex(item.colorHex, fallback: PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor).background)
        }
        return PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor).background
    }

    private var baseAccentColor: Color {
        if let item = catalog.item(for: baseKey) {
            return Color.avatarHex(item.colorHex, fallback: PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor).accent)
        }
        return PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor).accent
    }

    var body: some View {
        Circle()
            .fill(backgroundColor)
            .overlay {
                BuddySnooAvatar(
                    baseKey: baseKey,
                    baseAccentColor: baseAccentColor,
                    topItem: item(forSlot: "top"),
                    bottomItem: item(forSlot: "bottom"),
                    shoesItem: item(forSlot: "shoes"),
                    hatItem: item(forSlot: "hat"),
                    heldItem: item(forSlot: "item")
                )
                .padding(size * 0.035)
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
    }

    private func item(forSlot slot: String) -> AvatarCatalogItem? {
        catalog.item(for: resolvedConfig[slot])
    }
}

private struct BuddySnooAvatar: View {
    var baseKey: String
    var baseAccentColor: Color
    var topItem: AvatarCatalogItem?
    var bottomItem: AvatarCatalogItem?
    var shoesItem: AvatarCatalogItem?
    var hatItem: AvatarCatalogItem?
    var heldItem: AvatarCatalogItem?

    private let outlineColor = Color.black.opacity(0.72)
    private let bodyColor = Color(red: 0.97, green: 0.985, blue: 1.0)

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let lineWidth = max(side * 0.015, 1)

            ZStack {
                shadow(side)
                antenna(side: side, lineWidth: lineWidth)
                baseCostumeBack(side: side, lineWidth: lineWidth)
                legs(side: side, lineWidth: lineWidth)
                torso(side: side, lineWidth: lineWidth)
                arms(side: side, lineWidth: lineWidth)
                heldItemView(side: side, lineWidth: lineWidth)
                head(side: side, lineWidth: lineWidth)
                face(side: side, lineWidth: lineWidth)
                hat(side: side, lineWidth: lineWidth)
            }
            .frame(width: side, height: side)
            .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func color(for item: AvatarCatalogItem?, fallback: Color) -> Color {
        guard let item else { return fallback }
        return Color.avatarHex(item.colorHex, fallback: fallback)
    }

    private func matches(_ item: AvatarCatalogItem?, _ token: String) -> Bool {
        guard let item else { return false }
        let normalizedToken = token.lowercased()
        return item.key.lowercased().contains(normalizedToken)
            || item.assetName.lowercased().contains(normalizedToken)
    }

    private func shadow(_ side: CGFloat) -> some View {
        Ellipse()
            .fill(Color.black.opacity(0.18))
            .frame(width: side * 0.56, height: side * 0.10)
            .position(x: side * 0.50, y: side * 0.88)
    }

    private func antenna(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            Path { path in
                path.move(to: CGPoint(x: side * 0.43, y: side * 0.19))
                path.addQuadCurve(
                    to: CGPoint(x: side * 0.35, y: side * 0.08),
                    control: CGPoint(x: side * 0.34, y: side * 0.13)
                )
            }
            .stroke(outlineColor, style: StrokeStyle(lineWidth: lineWidth * 1.45, lineCap: .round))

            Circle()
                .fill(bodyColor)
                .frame(width: side * 0.085, height: side * 0.085)
                .overlay(Circle().stroke(outlineColor, lineWidth: lineWidth))
                .position(x: side * 0.34, y: side * 0.075)
        }
    }

    @ViewBuilder
    private func baseCostumeBack(side: CGFloat, lineWidth: CGFloat) -> some View {
        switch baseKey {
        case "base-rabbit":
            rabbitEars(side: side, lineWidth: lineWidth)
        case "base-dog":
            dogEars(side: side, lineWidth: lineWidth)
        case "base-cat":
            catEars(side: side, lineWidth: lineWidth)
        default:
            foxEars(side: side, lineWidth: lineWidth)
        }
    }

    private func catEars(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            earTriangle(side: side, width: 0.17, height: 0.17, rotation: -18, x: 0.34, y: 0.20)
            earTriangle(side: side, width: 0.17, height: 0.17, rotation: 18, x: 0.66, y: 0.20)
        }
    }

    private func foxEars(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            earTriangle(side: side, width: 0.19, height: 0.20, rotation: -24, x: 0.32, y: 0.21)
            earTriangle(side: side, width: 0.19, height: 0.20, rotation: 24, x: 0.68, y: 0.21)
        }
    }

    private func rabbitEars(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            rabbitEar(side: side, lineWidth: lineWidth, rotation: -17, x: 0.39, y: 0.13)
            rabbitEar(side: side, lineWidth: lineWidth, rotation: 17, x: 0.61, y: 0.13)
        }
    }

    private func dogEars(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            Ellipse()
                .fill(baseAccentColor.opacity(0.92))
                .frame(width: side * 0.18, height: side * 0.25)
                .overlay(Ellipse().stroke(outlineColor.opacity(0.65), lineWidth: lineWidth))
                .rotationEffect(.degrees(22))
                .position(x: side * 0.32, y: side * 0.30)
            Ellipse()
                .fill(baseAccentColor.opacity(0.92))
                .frame(width: side * 0.18, height: side * 0.25)
                .overlay(Ellipse().stroke(outlineColor.opacity(0.65), lineWidth: lineWidth))
                .rotationEffect(.degrees(-22))
                .position(x: side * 0.68, y: side * 0.30)
        }
    }

    private func earTriangle(side: CGFloat, width: CGFloat, height: CGFloat, rotation: Double, x: CGFloat, y: CGFloat) -> some View {
        AvatarTriangle()
            .fill(baseAccentColor.opacity(0.96))
            .frame(width: side * width, height: side * height)
            .overlay {
                AvatarTriangle()
                    .fill(Color(red: 1.0, green: 0.72, blue: 0.66).opacity(0.78))
                    .padding(side * 0.028)
            }
            .overlay(AvatarTriangle().stroke(outlineColor.opacity(0.62), lineWidth: max(side * 0.012, 1)))
            .rotationEffect(.degrees(rotation))
            .position(x: side * x, y: side * y)
    }

    private func rabbitEar(side: CGFloat, lineWidth: CGFloat, rotation: Double, x: CGFloat, y: CGFloat) -> some View {
        Capsule()
            .fill(bodyColor)
            .frame(width: side * 0.105, height: side * 0.28)
            .overlay {
                Capsule()
                    .fill(baseAccentColor.opacity(0.24))
                    .padding(side * 0.018)
            }
            .overlay(Capsule().stroke(outlineColor.opacity(0.62), lineWidth: lineWidth))
            .rotationEffect(.degrees(rotation))
            .position(x: side * x, y: side * y)
    }

    private func legs(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            Capsule()
                .fill(bodyColor)
                .frame(width: side * 0.095, height: side * 0.24)
                .overlay(Capsule().stroke(outlineColor.opacity(0.50), lineWidth: lineWidth))
                .position(x: side * 0.43, y: side * 0.70)
            Capsule()
                .fill(bodyColor)
                .frame(width: side * 0.095, height: side * 0.24)
                .overlay(Capsule().stroke(outlineColor.opacity(0.50), lineWidth: lineWidth))
                .position(x: side * 0.57, y: side * 0.70)

            bottom(side: side, lineWidth: lineWidth)
            shoes(side: side, lineWidth: lineWidth)
        }
    }

    @ViewBuilder
    private func bottom(side: CGFloat, lineWidth: CGFloat) -> some View {
        let bottomColor = color(for: bottomItem, fallback: Color(red: 0.22, green: 0.33, blue: 0.56))

        if matches(bottomItem, "shorts") {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.035, style: .continuous)
                    .fill(bottomColor)
                    .frame(width: side * 0.135, height: side * 0.115)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.035, style: .continuous).stroke(outlineColor.opacity(0.34), lineWidth: lineWidth))
                    .position(x: side * 0.43, y: side * 0.63)
                RoundedRectangle(cornerRadius: side * 0.035, style: .continuous)
                    .fill(bottomColor)
                    .frame(width: side * 0.135, height: side * 0.115)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.035, style: .continuous).stroke(outlineColor.opacity(0.34), lineWidth: lineWidth))
                    .position(x: side * 0.57, y: side * 0.63)
            }
        } else {
            ZStack {
                Capsule()
                    .fill(bottomColor)
                    .frame(width: side * 0.10, height: side * 0.205)
                    .overlay(Capsule().stroke(outlineColor.opacity(0.30), lineWidth: lineWidth))
                    .position(x: side * 0.43, y: side * 0.70)
                Capsule()
                    .fill(bottomColor)
                    .frame(width: side * 0.10, height: side * 0.205)
                    .overlay(Capsule().stroke(outlineColor.opacity(0.30), lineWidth: lineWidth))
                    .position(x: side * 0.57, y: side * 0.70)
                if matches(bottomItem, "denim") {
                    Rectangle()
                        .fill(Color.white.opacity(0.20))
                        .frame(width: side * 0.016, height: side * 0.14)
                        .position(x: side * 0.50, y: side * 0.68)
                }
            }
        }
    }

    private func shoes(side: CGFloat, lineWidth: CGFloat) -> some View {
        let shoeColor = color(for: shoesItem, fallback: Color(red: 0.96, green: 0.97, blue: 1.0))
        let isBoot = matches(shoesItem, "boots")
        let isLoafer = matches(shoesItem, "loafers")
        let shoeHeight = side * (isBoot ? 0.070 : 0.055)
        let shoeWidth = side * (isBoot ? 0.135 : 0.155)

        return ZStack {
            shoeShape(color: shoeColor, isBoot: isBoot, isLoafer: isLoafer, lineWidth: lineWidth)
                .frame(width: shoeWidth, height: shoeHeight)
                .position(x: side * 0.42, y: side * 0.84)
            shoeShape(color: shoeColor, isBoot: isBoot, isLoafer: isLoafer, lineWidth: lineWidth)
                .frame(width: shoeWidth, height: shoeHeight)
                .position(x: side * 0.58, y: side * 0.84)
        }
    }

    private func shoeShape(color: Color, isBoot: Bool, isLoafer: Bool, lineWidth: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: isBoot ? lineWidth * 1.8 : lineWidth * 3.2, style: .continuous)
            .fill(color)
            .overlay(RoundedRectangle(cornerRadius: isBoot ? lineWidth * 1.8 : lineWidth * 3.2, style: .continuous).stroke(outlineColor.opacity(0.38), lineWidth: lineWidth))
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(Color.black.opacity(isLoafer ? 0.18 : 0.10))
                    .frame(height: lineWidth * 1.4)
            }
    }

    private func torso(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: side * 0.085, style: .continuous)
                .fill(bodyColor)
                .frame(width: side * 0.34, height: side * 0.25)
                .overlay(RoundedRectangle(cornerRadius: side * 0.085, style: .continuous).stroke(outlineColor.opacity(0.52), lineWidth: lineWidth))
                .position(x: side * 0.50, y: side * 0.56)

            top(side: side, lineWidth: lineWidth)
        }
    }

    @ViewBuilder
    private func top(side: CGFloat, lineWidth: CGFloat) -> some View {
        let topColor = color(for: topItem, fallback: baseAccentColor)

        if matches(topItem, "varsity") {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.075, style: .continuous)
                    .fill(topColor)
                    .frame(width: side * 0.36, height: side * 0.245)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.075, style: .continuous).stroke(outlineColor.opacity(0.35), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.56)
                RoundedRectangle(cornerRadius: side * 0.025, style: .continuous)
                    .fill(bodyColor.opacity(0.88))
                    .frame(width: side * 0.095, height: side * 0.22)
                    .position(x: side * 0.50, y: side * 0.57)
                Circle()
                    .fill(Color.white.opacity(0.65))
                    .frame(width: side * 0.040, height: side * 0.040)
                    .position(x: side * 0.45, y: side * 0.52)
                Circle()
                    .fill(Color.white.opacity(0.65))
                    .frame(width: side * 0.040, height: side * 0.040)
                    .position(x: side * 0.55, y: side * 0.52)
            }
        } else if matches(topItem, "sweater") {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.09, style: .continuous)
                    .fill(topColor)
                    .frame(width: side * 0.35, height: side * 0.235)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.09, style: .continuous).stroke(outlineColor.opacity(0.32), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.56)
                HStack(spacing: side * 0.018) {
                    ForEach(0..<4, id: \.self) { _ in
                        Capsule()
                            .fill(Color.white.opacity(0.22))
                            .frame(width: side * 0.016, height: side * 0.055)
                    }
                }
                .position(x: side * 0.50, y: side * 0.62)
            }
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.09, style: .continuous)
                    .fill(topColor)
                    .frame(width: side * 0.36, height: side * 0.245)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.09, style: .continuous).stroke(outlineColor.opacity(0.34), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.56)
                RoundedRectangle(cornerRadius: side * 0.04, style: .continuous)
                    .fill(Color.white.opacity(0.24))
                    .frame(width: side * 0.16, height: side * 0.035)
                    .position(x: side * 0.50, y: side * 0.47)
                Capsule()
                    .fill(Color.white.opacity(0.42))
                    .frame(width: side * 0.018, height: side * 0.075)
                    .rotationEffect(.degrees(15))
                    .position(x: side * 0.47, y: side * 0.52)
                Capsule()
                    .fill(Color.white.opacity(0.42))
                    .frame(width: side * 0.018, height: side * 0.075)
                    .rotationEffect(.degrees(-15))
                    .position(x: side * 0.53, y: side * 0.52)
            }
        }
    }

    private func arms(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            Capsule()
                .fill(bodyColor)
                .frame(width: side * 0.085, height: side * 0.23)
                .overlay(Capsule().stroke(outlineColor.opacity(0.48), lineWidth: lineWidth))
                .rotationEffect(.degrees(18))
                .position(x: side * 0.33, y: side * 0.60)
            Capsule()
                .fill(bodyColor)
                .frame(width: side * 0.085, height: side * 0.23)
                .overlay(Capsule().stroke(outlineColor.opacity(0.48), lineWidth: lineWidth))
                .rotationEffect(.degrees(heldItem == nil ? -18 : -32))
                .position(x: side * (heldItem == nil ? 0.67 : 0.69), y: side * (heldItem == nil ? 0.60 : 0.61))
        }
    }

    private func head(side: CGFloat, lineWidth: CGFloat) -> some View {
        Ellipse()
            .fill(bodyColor)
            .frame(width: side * 0.50, height: side * 0.38)
            .overlay(Ellipse().stroke(outlineColor.opacity(0.70), lineWidth: lineWidth * 1.10))
            .position(x: side * 0.50, y: side * 0.32)
    }

    private func face(side: CGFloat, lineWidth: CGFloat) -> some View {
        ZStack {
            Circle()
                .fill(outlineColor)
                .frame(width: side * 0.032, height: side * 0.032)
                .position(x: side * 0.43, y: side * 0.31)
            Circle()
                .fill(outlineColor)
                .frame(width: side * 0.032, height: side * 0.032)
                .position(x: side * 0.57, y: side * 0.31)
            Path { path in
                path.move(to: CGPoint(x: side * 0.46, y: side * 0.39))
                path.addQuadCurve(
                    to: CGPoint(x: side * 0.56, y: side * 0.39),
                    control: CGPoint(x: side * 0.51, y: side * 0.43)
                )
            }
            .stroke(outlineColor.opacity(0.75), style: StrokeStyle(lineWidth: max(lineWidth * 0.9, 1), lineCap: .round))
            Circle()
                .fill(baseAccentColor.opacity(0.20))
                .frame(width: side * 0.055, height: side * 0.030)
                .position(x: side * 0.38, y: side * 0.37)
            Circle()
                .fill(baseAccentColor.opacity(0.20))
                .frame(width: side * 0.055, height: side * 0.030)
                .position(x: side * 0.62, y: side * 0.37)
        }
    }

    @ViewBuilder
    private func hat(side: CGFloat, lineWidth: CGFloat) -> some View {
        let hatColor = color(for: hatItem, fallback: Color(red: 0.12, green: 0.18, blue: 0.32))

        if matches(hatItem, "grad") {
            ZStack {
                AvatarDiamond()
                    .fill(hatColor)
                    .frame(width: side * 0.40, height: side * 0.15)
                    .overlay(AvatarDiamond().stroke(outlineColor.opacity(0.45), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.16)
                RoundedRectangle(cornerRadius: side * 0.025, style: .continuous)
                    .fill(hatColor)
                    .frame(width: side * 0.17, height: side * 0.075)
                    .position(x: side * 0.50, y: side * 0.22)
                Capsule()
                    .fill(Color.yellow.opacity(0.85))
                    .frame(width: side * 0.014, height: side * 0.14)
                    .position(x: side * 0.66, y: side * 0.23)
            }
        } else if matches(hatItem, "cap") {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.075, style: .continuous)
                    .fill(hatColor)
                    .frame(width: side * 0.31, height: side * 0.125)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.075, style: .continuous).stroke(outlineColor.opacity(0.40), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.17)
                Capsule()
                    .fill(hatColor.opacity(0.92))
                    .frame(width: side * 0.28, height: side * 0.045)
                    .rotationEffect(.degrees(-5))
                    .position(x: side * 0.62, y: side * 0.21)
            }
        } else if hatItem != nil {
            ZStack {
                RoundedRectangle(cornerRadius: side * 0.07, style: .continuous)
                    .fill(hatColor)
                    .frame(width: side * 0.32, height: side * 0.13)
                    .overlay(RoundedRectangle(cornerRadius: side * 0.07, style: .continuous).stroke(outlineColor.opacity(0.38), lineWidth: lineWidth))
                    .position(x: side * 0.50, y: side * 0.17)
                Capsule()
                    .fill(Color.white.opacity(0.18))
                    .frame(width: side * 0.29, height: side * 0.026)
                    .position(x: side * 0.50, y: side * 0.20)
                Circle()
                    .fill(hatColor)
                    .frame(width: side * 0.055, height: side * 0.055)
                    .position(x: side * 0.58, y: side * 0.105)
            }
        }
    }

    @ViewBuilder
    private func heldItemView(side: CGFloat, lineWidth: CGFloat) -> some View {
        let itemColor = color(for: heldItem, fallback: Color(red: 0.40, green: 0.46, blue: 0.55))

        if matches(heldItem, "book") {
            RoundedRectangle(cornerRadius: side * 0.030, style: .continuous)
                .fill(itemColor)
                .frame(width: side * 0.25, height: side * 0.17)
                .overlay(RoundedRectangle(cornerRadius: side * 0.030, style: .continuous).stroke(outlineColor.opacity(0.38), lineWidth: lineWidth))
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(Color.white.opacity(0.30))
                        .frame(width: side * 0.018)
                        .padding(.leading, side * 0.055)
                }
                .rotationEffect(.degrees(-4))
                .position(x: side * 0.68, y: side * 0.62)
        } else if matches(heldItem, "pencil") {
            ZStack {
                Capsule()
                    .fill(itemColor)
                    .frame(width: side * 0.30, height: side * 0.052)
                    .overlay(Capsule().stroke(outlineColor.opacity(0.32), lineWidth: lineWidth))
                AvatarTriangle()
                    .fill(Color.orange.opacity(0.92))
                    .frame(width: side * 0.060, height: side * 0.060)
                    .rotationEffect(.degrees(90))
                    .offset(x: side * 0.155)
            }
            .rotationEffect(.degrees(-32))
            .position(x: side * 0.70, y: side * 0.62)
        } else if heldItem != nil {
            RoundedRectangle(cornerRadius: side * 0.035, style: .continuous)
                .fill(itemColor)
                .frame(width: side * 0.31, height: side * 0.19)
                .overlay(RoundedRectangle(cornerRadius: side * 0.035, style: .continuous).stroke(outlineColor.opacity(0.38), lineWidth: lineWidth))
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(Color.white.opacity(0.18))
                        .frame(height: side * 0.10)
                }
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(Color.black.opacity(0.18))
                        .frame(height: side * 0.030)
                }
                .rotationEffect(.degrees(-2))
                .position(x: side * 0.68, y: side * 0.66)
        }
    }
}

private struct AvatarDiamond: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.midY))
        path.closeSubpath()
        return path
    }
}

private struct AvatarTriangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

struct PixelAvatarGlyph: View {
    var avatarName: String
    var colorSeed: String
    var usesNeutralColor: Bool = false

    var body: some View {
        GeometryReader { proxy in
            let length = min(proxy.size.width, proxy.size.height)
            let palette = PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor)
            let cells = PixelAvatarPattern.cells(for: avatarName)
            let pixelSize = length / 11
            let origin = CGPoint(x: (proxy.size.width - pixelSize * 9) / 2, y: (proxy.size.height - pixelSize * 9) / 2)

            ZStack {
                Circle()
                    .fill(palette.background)

                ForEach(cells) { cell in
                    Rectangle()
                        .fill(palette.color(for: cell.tone))
                        .frame(width: pixelSize, height: pixelSize)
                        .position(
                            x: origin.x + CGFloat(cell.x) * pixelSize + pixelSize / 2,
                            y: origin.y + CGFloat(cell.y) * pixelSize + pixelSize / 2
                        )
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

struct PixelAvatarCell: Identifiable {
    enum Tone {
        case outline
        case shade
        case skin
        case light
        case accent
        case eye
    }

    let id = UUID()
    var x: Int
    var y: Int
    var tone: Tone
}

struct PixelAvatarPalette {
    var seed: String
    var usesNeutralColor: Bool

    var background: Color {
        usesNeutralColor ? Color.secondary.opacity(0.42) : accent.opacity(0.92)
    }

    var accent: Color {
        if let customColor = ProfileAvatarCustomColor(seed: seed)?.color {
            return customColor
        }

        if let option = ProfileAvatarColorOption.option(for: seed) {
            return option.color
        }

        return stableAvatarColor(seed: seed)
    }

    func color(for tone: PixelAvatarCell.Tone) -> Color {
        switch tone {
        case .outline:
            return usesNeutralColor ? Color.white.opacity(0.88) : Color.black.opacity(0.70)
        case .shade:
            return usesNeutralColor ? Color.white.opacity(0.32) : accent.opacity(0.62)
        case .skin:
            return usesNeutralColor ? Color.white.opacity(0.86) : Color(red: 1.0, green: 0.79, blue: 0.58)
        case .light:
            return Color.white.opacity(0.96)
        case .accent:
            return usesNeutralColor ? Color.white.opacity(0.68) : accent.lighter()
        case .eye:
            return usesNeutralColor ? Color.black.opacity(0.55) : Color.black.opacity(0.82)
        }
    }

    private func stableAvatarColor(seed: String) -> Color {
        let normalizedSeed = seed.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = normalizedSeed.isEmpty ? "buddy-study-default-profile" : normalizedSeed
        let hash = source.unicodeScalars.reduce(UInt32(2_166_136_261)) { value, scalar in
            (value ^ UInt32(scalar.value)) &* 16_777_619
        }

        let hue = Double(hash % 360) / 360.0
        return Color(hue: hue, saturation: 0.48, brightness: 0.74)
    }
}

enum PixelAvatarPattern {
    static func cells(for avatarName: String) -> [PixelAvatarCell] {
        switch avatarName {
        case "pixel-scholar":
            return scholar
        case "pixel-coder":
            return coder
        case "pixel-explorer":
            return explorer
        case "pixel-artist":
            return artist
        case "pixel-star":
            return star
        case "pixel-girl":
            return girl
        case "pixel-princess":
            return princess
        case "pixel-flower":
            return flower
        case "pixel-hero":
            return hero
        case "pixel-wizard":
            return wizard
        case "pixel-robot":
            return robot
        case "pixel-chef":
            return chef
        case "pixel-pilot":
            return pilot
        case "pixel-nurse":
            return nurse
        case "pixel-knight":
            return knight
        case "pixel-dancer":
            return dancer
        case "pixel-gamer":
            return gamer
        case "pixel-scientist":
            return scientist
        case "pixel-astronaut":
            return astronaut
        case "pixel-dachshund":
            return dachshund
        case "pixel-pencil-pup":
            return pencilPup
        case "pixel-sleepy-pup":
            return sleepyPup
        case "pixel-book-pup":
            return bookPup
        case "pixel-cat":
            return cat
        case "pixel-bear":
            return bear
        case "pixel-rabbit":
            return rabbit
        case "pixel-penguin":
            return penguin
        case "pixel-fox":
            return fox
        case "pixel-chick":
            return chick
        case "pixel-tutor-bot":
            return tutorBot
        case "pixel-study-mage":
            return studyMage
        default:
            return buddy
        }
    }

    private static func row(_ y: Int, _ xRange: ClosedRange<Int>, _ tone: PixelAvatarCell.Tone) -> [PixelAvatarCell] {
        xRange.map { PixelAvatarCell(x: $0, y: y, tone: tone) }
    }

    private static func points(_ tone: PixelAvatarCell.Tone, _ values: (Int, Int)...) -> [PixelAvatarCell] {
        values.map { PixelAvatarCell(x: $0.0, y: $0.1, tone: tone) }
    }

    private static var baseFace: [PixelAvatarCell] {
        row(3, 3...5, .skin)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + row(6, 3...5, .skin)
            + points(.eye, (3, 4), (5, 4))
            + row(7, 3...5, .outline)
    }

    static var buddy: [PixelAvatarCell] {
        row(1, 3...5, .outline)
            + row(2, 2...6, .accent)
            + points(.outline, (2, 3), (6, 3), (2, 4), (6, 4), (2, 5), (6, 5), (3, 6), (5, 6))
            + baseFace
            + row(8, 2...6, .accent)
    }

    static var scholar: [PixelAvatarCell] {
        row(1, 2...6, .outline)
            + row(2, 1...7, .accent)
            + row(3, 3...5, .outline)
            + points(.accent, (1, 3), (7, 3), (1, 4), (7, 4))
            + baseFace
            + row(8, 2...6, .outline)
    }

    static var coder: [PixelAvatarCell] {
        row(1, 3...5, .shade)
            + row(2, 2...6, .outline)
            + row(3, 2...6, .skin)
            + points(.outline, (1, 4), (2, 4), (6, 4), (7, 4), (1, 5), (7, 5))
            + baseFace
            + points(.light, (2, 4), (6, 4))
            + row(8, 1...7, .accent)
    }

    static var explorer: [PixelAvatarCell] {
        row(1, 2...6, .accent)
            + row(2, 1...7, .outline)
            + row(3, 2...6, .shade)
            + baseFace
            + points(.outline, (1, 5), (7, 5), (2, 6), (6, 6))
            + row(8, 2...6, .accent)
    }

    static var artist: [PixelAvatarCell] {
        points(.accent, (3, 1), (4, 1), (5, 1), (2, 2), (6, 2))
            + points(.light, (6, 1), (7, 2))
            + row(3, 2...6, .skin)
            + baseFace
            + points(.accent, (1, 7), (2, 8), (6, 8), (7, 7))
    }

    static var star: [PixelAvatarCell] {
        points(.light, (4, 0), (3, 1), (4, 1), (5, 1), (2, 2), (6, 2))
            + row(3, 2...6, .accent)
            + baseFace
            + points(.light, (1, 4), (7, 4), (2, 7), (6, 7))
            + row(8, 3...5, .accent)
    }

    static var girl: [PixelAvatarCell] {
        row(1, 3...5, .outline)
            + row(2, 2...6, .accent)
            + points(.accent, (1, 3), (7, 3), (1, 4), (7, 4), (2, 5), (6, 5), (2, 6), (6, 6))
            + baseFace
            + points(.light, (3, 7), (5, 7))
            + row(8, 2...6, .accent)
    }

    static var princess: [PixelAvatarCell] {
        points(.light, (2, 0), (4, 0), (6, 0), (3, 1), (4, 1), (5, 1))
            + row(2, 2...6, .accent)
            + points(.accent, (1, 3), (7, 3), (1, 4), (7, 4), (2, 6), (6, 6))
            + baseFace
            + row(8, 2...6, .light)
    }

    static var flower: [PixelAvatarCell] {
        points(.light, (4, 0), (3, 1), (5, 1), (4, 2))
            + row(2, 2...6, .accent)
            + points(.accent, (1, 3), (7, 3), (1, 4), (7, 4), (2, 5), (6, 5))
            + baseFace
            + points(.light, (2, 7), (6, 7))
            + row(8, 2...6, .accent)
    }

    static var hero: [PixelAvatarCell] {
        row(1, 2...6, .outline)
            + row(2, 2...6, .accent)
            + points(.light, (3, 3), (5, 3), (2, 7), (6, 7))
            + baseFace
            + row(8, 1...7, .accent)
    }

    static var wizard: [PixelAvatarCell] {
        points(.accent, (4, 0), (3, 1), (4, 1), (5, 1), (2, 2), (3, 2), (4, 2), (5, 2), (6, 2))
            + points(.light, (5, 0), (6, 1))
            + baseFace
            + row(8, 2...6, .accent)
    }

    static var robot: [PixelAvatarCell] {
        row(1, 3...5, .outline)
            + row(2, 2...6, .shade)
            + row(3, 2...6, .light)
            + row(4, 2...6, .light)
            + points(.eye, (3, 4), (5, 4), (4, 6))
            + row(5, 2...6, .shade)
            + row(8, 2...6, .accent)
    }

    static var chef: [PixelAvatarCell] {
        points(.light, (3, 0), (4, 0), (5, 0), (2, 1), (4, 1), (6, 1))
            + row(2, 2...6, .light)
            + baseFace
            + row(8, 2...6, .accent)
    }

    static var pilot: [PixelAvatarCell] {
        row(1, 2...6, .accent)
            + row(2, 1...7, .outline)
            + points(.light, (2, 3), (6, 3), (2, 4), (6, 4))
            + baseFace
            + row(8, 2...6, .shade)
    }

    static var nurse: [PixelAvatarCell] {
        row(1, 2...6, .light)
            + points(.accent, (4, 0), (4, 1), (3, 1), (5, 1))
            + row(2, 2...6, .outline)
            + baseFace
            + row(8, 2...6, .light)
    }

    static var knight: [PixelAvatarCell] {
        row(1, 3...5, .outline)
            + row(2, 2...6, .shade)
            + points(.outline, (1, 3), (7, 3), (1, 4), (7, 4))
            + baseFace
            + points(.accent, (3, 7), (4, 7), (5, 7), (4, 8))
    }

    static var dancer: [PixelAvatarCell] {
        points(.accent, (2, 1), (3, 1), (5, 1), (6, 1), (1, 4), (7, 4))
            + row(2, 2...6, .accent)
            + baseFace
            + points(.light, (2, 8), (6, 8))
    }

    static var gamer: [PixelAvatarCell] {
        row(1, 2...6, .shade)
            + points(.outline, (1, 4), (2, 4), (6, 4), (7, 4))
            + baseFace
            + points(.accent, (1, 8), (2, 8), (5, 8), (6, 8), (7, 8))
    }

    static var scientist: [PixelAvatarCell] {
        row(1, 3...5, .shade)
            + row(2, 2...6, .light)
            + points(.outline, (2, 4), (3, 4), (5, 4), (6, 4))
            + baseFace
            + row(8, 2...6, .light)
    }

    static var astronaut: [PixelAvatarCell] {
        row(1, 2...6, .light)
            + points(.outline, (1, 2), (7, 2), (1, 3), (7, 3), (1, 4), (7, 4))
            + baseFace
            + points(.accent, (2, 8), (3, 8), (5, 8), (6, 8))
    }

    static var dachshund: [PixelAvatarCell] {
        row(3, 2...6, .shade)
            + row(4, 1...7, .accent)
            + row(5, 1...7, .accent)
            + points(.outline, (1, 3), (7, 3), (0, 4), (8, 4), (0, 5), (8, 5), (2, 6), (6, 6))
            + points(.light, (3, 4), (4, 4), (5, 4), (3, 5), (4, 5), (5, 5))
            + points(.eye, (2, 4), (6, 4), (4, 6))
            + row(8, 2...6, .accent)
    }

    static var pencilPup: [PixelAvatarCell] {
        row(2, 2...6, .accent)
            + points(.outline, (1, 3), (7, 3), (1, 4), (7, 4), (2, 6), (6, 6))
            + baseFace
            + points(.light, (6, 1), (7, 1), (5, 2), (6, 2), (4, 3), (5, 3))
            + points(.outline, (8, 1), (7, 2), (6, 3))
            + row(8, 2...6, .accent)
    }

    static var sleepyPup: [PixelAvatarCell] {
        row(1, 3...5, .shade)
            + row(2, 2...6, .accent)
            + points(.outline, (1, 3), (7, 3), (1, 4), (7, 4), (2, 6), (6, 6))
            + row(3, 3...5, .skin)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + points(.outline, (3, 4), (5, 4), (4, 6))
            + row(8, 1...7, .light)
    }

    static var bookPup: [PixelAvatarCell] {
        row(1, 3...5, .accent)
            + row(2, 2...6, .accent)
            + points(.outline, (1, 3), (7, 3), (1, 4), (7, 4))
            + baseFace
            + points(.light, (1, 7), (2, 7), (3, 7), (5, 7), (6, 7), (7, 7), (1, 8), (2, 8), (3, 8), (5, 8), (6, 8), (7, 8))
            + points(.outline, (4, 7), (4, 8))
    }

    static var cat: [PixelAvatarCell] {
        points(.outline, (2, 1), (6, 1), (1, 2), (3, 2), (5, 2), (7, 2))
            + row(3, 2...6, .accent)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + points(.eye, (3, 4), (5, 4), (4, 5))
            + points(.outline, (2, 6), (6, 6), (3, 7), (5, 7))
            + row(8, 2...6, .accent)
    }

    static var bear: [PixelAvatarCell] {
        points(.outline, (2, 1), (6, 1))
            + points(.accent, (2, 2), (6, 2))
            + row(2, 3...5, .outline)
            + row(3, 2...6, .accent)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + points(.eye, (3, 4), (5, 4), (4, 5))
            + row(8, 2...6, .accent)
    }

    static var rabbit: [PixelAvatarCell] {
        points(.light, (2, 0), (6, 0), (2, 1), (6, 1), (3, 2), (5, 2))
            + row(3, 2...6, .light)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + points(.eye, (3, 4), (5, 4), (4, 6))
            + points(.outline, (2, 7), (6, 7))
            + row(8, 3...5, .accent)
    }

    static var penguin: [PixelAvatarCell] {
        row(1, 3...5, .outline)
            + row(2, 2...6, .outline)
            + row(3, 2...6, .shade)
            + row(4, 2...6, .light)
            + row(5, 2...6, .light)
            + points(.eye, (3, 3), (5, 3))
            + points(.accent, (4, 4), (1, 6), (7, 6), (3, 8), (5, 8))
            + row(6, 3...5, .light)
    }

    static var fox: [PixelAvatarCell] {
        points(.outline, (2, 1), (6, 1), (1, 2), (3, 2), (5, 2), (7, 2))
            + row(3, 2...6, .accent)
            + points(.accent, (1, 4), (7, 4), (2, 5), (6, 5))
            + row(4, 3...5, .skin)
            + row(5, 3...5, .skin)
            + points(.eye, (3, 4), (5, 4), (4, 6))
            + row(8, 2...6, .accent)
    }

    static var chick: [PixelAvatarCell] {
        points(.accent, (4, 0), (3, 1), (5, 1))
            + row(2, 2...6, .light)
            + row(3, 2...6, .skin)
            + row(4, 2...6, .skin)
            + row(5, 3...5, .skin)
            + points(.eye, (3, 3), (5, 3))
            + points(.accent, (4, 4), (2, 7), (6, 7), (3, 8), (5, 8))
    }

    static var tutorBot: [PixelAvatarCell] {
        points(.accent, (4, 0))
            + row(1, 3...5, .outline)
            + row(2, 2...6, .shade)
            + row(3, 2...6, .light)
            + row(4, 2...6, .light)
            + points(.eye, (3, 4), (5, 4))
            + points(.accent, (1, 5), (7, 5), (4, 6))
            + row(8, 2...6, .accent)
    }

    static var studyMage: [PixelAvatarCell] {
        points(.accent, (4, 0), (3, 1), (4, 1), (5, 1), (2, 2), (3, 2), (4, 2), (5, 2), (6, 2))
            + points(.light, (5, 0), (6, 1), (2, 7), (6, 7))
            + row(3, 2...6, .skin)
            + row(4, 2...6, .skin)
            + row(5, 2...6, .skin)
            + points(.eye, (3, 4), (5, 4), (4, 6))
            + row(8, 2...6, .accent)
    }
}

extension Color {
    func lighter() -> Color {
        self.opacity(0.82)
    }

    static func avatarHex(_ value: String, fallback: Color) -> Color {
        let normalized = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        guard normalized.count == 6,
              let integer = Int(normalized, radix: 16) else {
            return fallback
        }
        let red = Double((integer >> 16) & 0xFF) / 255.0
        let green = Double((integer >> 8) & 0xFF) / 255.0
        let blue = Double(integer & 0xFF) / 255.0
        return Color(red: red, green: green, blue: blue)
    }
}

private struct MobileProfileSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    private var strings: AppStrings {
        appState.strings
    }

    private var appVersionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        let parts = [version, build.map { "(\($0))" }]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? "-" : parts.joined(separator: " ")
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink {
                        MobileProfileEditorView()
                    } label: {
                        profileDestinationLabel(
                            title: strings.avatar,
                            subtitle: appState.communityProfile?.displayName,
                            systemImage: "person.crop.circle"
                        )
                    }

                    NavigationLink {
                        MobileSettingsView()
                    } label: {
                        profileDestinationLabel(
                            title: strings.tabSettings,
                            subtitle: nil,
                            systemImage: "gearshape"
                        )
                    }
                }

                if appState.isCommunitySessionActive {
                    Section {
                        NavigationLink {
                            MobileQuestionUsageView()
                        } label: {
                            profileDestinationLabel(
                                title: strings.usage,
                                subtitle: nil,
                                systemImage: "chart.bar"
                            )
                        }
                    }
                }

                if appState.isCommunitySessionActive {
                    Section {
                        NavigationLink {
                            MobileNotificationSettingsView()
                        } label: {
                            profileDestinationLabel(
                                title: strings.notificationSettings,
                                subtitle: nil,
                                systemImage: "bell"
                            )
                        }

                        NavigationLink {
                            MobileTermsSettingsView()
                        } label: {
                            profileDestinationLabel(
                                title: strings.operatingTerms,
                                subtitle: nil,
                                systemImage: "doc.text"
                            )
                        }
                    }
                }

                Section {
                    HStack {
                        Text(strings.appVersion)
                        Spacer()
                        Text(appVersionText)
                            .foregroundStyle(.secondary)
                    }
                }

                if appState.isCommunitySessionActive {
                    Section {
                        Button(role: .destructive) {
                            appState.signOutFromCommunity()
                            dismiss()
                        } label: {
                            Text(strings.communityLogout)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .navigationTitle(strings.profile)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.done) {
                        dismiss()
                    }
                }
            }
            .task {
                if appState.isCommunitySessionActive {
                    await appState.loadCommunityProfile()
                }
            }
        }
    }

    private func profileDestinationLabel(
        title: String,
        subtitle: String?,
        systemImage: String
    ) -> some View {
        HStack(spacing: 13) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(.primary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 3)
    }
}

private struct MobileQuestionUsageView: View {
    @EnvironmentObject private var appState: AppState

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        List {
            Section {
                if let quota = appState.questionQuota {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(strings.monthlyQuestionQuota)
                                .font(.headline)

                            Spacer(minLength: 12)

                            Text(strings.monthlyQuotaUsage(
                                remaining: quota.remainingCount,
                                limit: quota.monthlyLimit
                            ))
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(quota.remainingCount == 0 ? Color.orange : Color.secondary)
                        }

                        ProgressView(
                            value: Double(quota.usedCount),
                            total: Double(max(quota.monthlyLimit, 1))
                        )
                        .tint(quota.remainingCount == 0 ? .orange : .accentColor)

                        Text(strings.monthlyQuotaReset(quota.resetAt))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                } else {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(strings.loading)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(strings.usage)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await appState.refreshQuestionQuota()
        }
    }
}

private struct MobileProfileEditorView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var profileDisplayName = ""
    @State private var draftAvatarSymbolName = BuddyStudyAvatar.symbolName
    @State private var draftAvatarColorSeed = "avatar-color-sage"
    @State private var isShowingEmailSignIn = false
    @State private var isLoadingProfileDraft = false
    @State private var wasSignedInWhenOpened = false

    private var strings: AppStrings {
        appState.strings
    }

    private var trimmedProfileDisplayName: String {
        profileDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var hasProfileChanges: Bool {
        guard appState.isCommunitySessionActive else {
            return false
        }

        let profile = appState.communityProfile
        let currentDisplayName = profile?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let currentAvatar = ProfileAvatarOption.canonicalName(
            for: profile?.avatarSymbolName ?? appState.profileAvatarSymbolName
        )
        let currentColor = profile?.avatarColorSeed ?? appState.profileAvatarColorSeed

        return trimmedProfileDisplayName != currentDisplayName
            || draftAvatarSymbolName != currentAvatar
            || draftAvatarColorSeed != currentColor
    }

    private var canSaveProfile: Bool {
        appState.isCommunitySessionActive
            && !appState.isUpdatingCommunityProfile
            && !trimmedProfileDisplayName.isEmpty
            && hasProfileChanges
    }

    var body: some View {
        let strings = appState.strings

        Form {
                if appState.isCommunitySessionActive, appState.communityProfile == nil {
                    Section {
                        VStack(spacing: 14) {
                            if isLoadingProfileDraft {
                                ProgressView()
                                    .controlSize(.regular)
                                Text(strings.loading)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            } else {
                                HomeProfileAvatar(
                                    symbolName: ProfileAvatarOption.defaultSymbolName,
                                    displayName: nil,
                                    colorSeed: nil,
                                    usesNeutralColor: true,
                                    size: 58
                                )
                                Text(strings.profileRequestFailed)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                Button {
                                    Task {
                                        isLoadingProfileDraft = true
                                        await appState.loadCommunityProfile()
                                        resetDraftProfile()
                                        isLoadingProfileDraft = false
                                    }
                                } label: {
                                    Text(strings.retry)
                                        .font(.subheadline.weight(.semibold))
                                        .frame(maxWidth: .infinity)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                } else if appState.isCommunitySessionActive {
                    Section {
                        VStack(alignment: .center, spacing: 14) {
                            HomeProfileAvatar(
                                symbolName: draftAvatarSymbolName,
                                displayName: profileDisplayName,
                                colorSeed: draftAvatarColorSeed,
                                usesNeutralColor: false,
                                size: 94
                            )
                            .padding(.top, 4)

                            pixelAvatarPicker(strings: strings)

                            TextField(strings.profileDisplayName, text: $profileDisplayName)
                                .font(.title2.weight(.bold))
                                .multilineTextAlignment(.center)
                                .textInputAutocapitalization(.words)
                                .submitLabel(.done)
                                .padding(.vertical, 12)
                                .padding(.horizontal, 12)
                                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .listRowBackground(Color.clear)

                } else {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(strings.communityLoginHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Button {
                                appState.signInToCommunity()
                            } label: {
                                SignInButtonLabel(title: strings.signInWithGoogle, isPrimary: true)
                            }
                            .buttonStyle(.plain)

                            Button {
                                isShowingEmailSignIn = true
                            } label: {
                                SignInButtonLabel(title: strings.signInWithEmail, isPrimary: false)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .keyboardDoneToolbar(strings.done)
            .navigationTitle(strings.avatar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        guard appState.isCommunitySessionActive else {
                            dismiss()
                            return
                        }

                        Task {
                            let didUpdate = await appState.updateCommunityProfile(
                                displayName: trimmedProfileDisplayName,
                                avatarSymbolName: draftAvatarSymbolName,
                                avatarColorSeed: draftAvatarColorSeed,
                                avatarMode: "PIXEL",
                                avatarConfig: nil
                            )
                            if didUpdate {
                                dismiss()
                            }
                        }
                    } label: {
                        if appState.isUpdatingCommunityProfile {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text(profileConfirmationTitle(strings: strings))
                        }
                    }
                    .disabled(appState.isCommunitySessionActive ? !canSaveProfile : appState.isUpdatingCommunityProfile)
                }
            }
            .onAppear {
                wasSignedInWhenOpened = appState.isCommunitySessionActive
                appState.logMobileAuthView(
                    "mobile_profile_sheet_appear",
                    page: .profile,
                    reason: "MobileProfileSettingsSheet",
                    extra: ["hasProfile=\(appState.communityProfile != nil)"]
                )
                resetDraftProfile()
                Task {
                    isLoadingProfileDraft = true
                    await appState.loadCommunityProfile()
                    await appState.refreshTermsAndNotificationPreferences(reason: "profile-settings")
                    resetDraftProfile()
                    isLoadingProfileDraft = false
                }
            }
            .onChange(of: appState.communityProfile) { _, profile in
                appState.logMobileAuthView(
                    "mobile_profile_state_change",
                    page: .profile,
                    reason: "communityProfile",
                    extra: ["hasProfile=\(profile != nil)", "provider=\(profile?.provider ?? "-")"]
                )
                guard isLoadingProfileDraft || !hasProfileChanges else {
                    return
                }

                guard let profile else {
                    resetDraftProfile()
                    return
                }

                profileDisplayName = profile.displayName
                draftAvatarSymbolName = ProfileAvatarOption.canonicalName(for: profile.avatarSymbolName)
                draftAvatarColorSeed = profile.avatarColorSeed
            }
            .onChange(of: appState.isCommunitySessionActive) { _, isSignedIn in
                appState.logMobileAuthView(
                    "mobile_profile_session_change",
                    page: .profile,
                    reason: "MobileProfileSettingsSheet",
                    extra: ["isSignedIn=\(isSignedIn)"]
                )
                if isSignedIn, !wasSignedInWhenOpened {
                    dismiss()
                }
            }
            .sheet(isPresented: $isShowingEmailSignIn) {
                EmailSignInSheet {
                    isShowingEmailSignIn = false
                    dismiss()
                }
                .environmentObject(appState)
            }
    }

    private func resetDraftProfile() {
        profileDisplayName = appState.communityProfile?.displayName ?? ""
        draftAvatarSymbolName = ProfileAvatarOption.canonicalName(
            for: appState.communityProfile?.avatarSymbolName ?? appState.profileAvatarSymbolName
        )
        let savedColor = appState.communityProfile?.avatarColorSeed ?? appState.profileAvatarColorSeed
        draftAvatarColorSeed = savedColor.isEmpty ? "avatar-color-sage" : savedColor
    }

    private func profileConfirmationTitle(strings: AppStrings) -> String {
        guard appState.isCommunitySessionActive else {
            return strings.done
        }

        return strings.save
    }

    private func avatarChoice(symbolName: String, colorSeed: String, isSelected: Bool) -> some View {
        ZStack(alignment: .bottomTrailing) {
            HomeProfileAvatar(
                symbolName: symbolName,
                displayName: nil,
                colorSeed: colorSeed,
                usesNeutralColor: false,
                size: 54
            )

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 15, weight: .bold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.accentColor)
                    .background(Color.primary.opacity(0.12), in: Circle())
                    .offset(x: 2, y: 2)
            }
        }
        .frame(width: 62, height: 62)
        .background(isSelected ? Color.primary.opacity(0.08) : Color.secondary.opacity(0.04), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(isSelected ? Color.primary.opacity(0.35) : Color.secondary.opacity(0.08), lineWidth: 1)
        }
    }

    private func pixelAvatarPicker(strings: AppStrings) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Text(strings.profileCharacter)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                LazyVGrid(columns: [GridItem(.adaptive(minimum: 58, maximum: 66), spacing: 8)], spacing: 8) {
                    ForEach(ProfileAvatarOption.all, id: \.self) { option in
                        Button {
                            draftAvatarSymbolName = option
                        } label: {
                            avatarChoice(
                                symbolName: option,
                                colorSeed: draftAvatarColorSeed,
                                isSelected: draftAvatarSymbolName == option
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text(strings.profileColor)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(ProfileAvatarColorOption.all.prefix(10)) { option in
                            Button {
                                draftAvatarColorSeed = option.id
                            } label: {
                                colorChoice(
                                    color: option.color,
                                    isSelected: draftAvatarColorSeed == option.id
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(option.id)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func colorChoice(color: Color, isSelected: Bool) -> some View {
        ZStack {
            Circle()
                .fill(color)

            if isSelected {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .black))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: 38, height: 38)
        .overlay {
            Circle()
                .stroke(isSelected ? Color.primary.opacity(0.75) : Color.secondary.opacity(0.15), lineWidth: isSelected ? 2 : 1)
        }
    }
}

private struct MobileTermsSettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var legalWebRoute: MobileLegalWebRoute?

    private var strings: AppStrings { appState.strings }

    private var visibleTerms: [BackendTerms] {
        let order: [BackendTermsType] = [.termsOfService, .privacyPolicy, .marketingNotification]
        return order.compactMap { type in
            appState.activeTerms.first { $0.type == type } ?? fallbackTerms(type: type)
        }
    }

    var body: some View {
        List {
            Section {
                ForEach(visibleTerms) { term in
                    termsRow(term)
                }
            } footer: {
                Text(strings.termsConsentHelp)
            }
        }
        .navigationTitle(strings.operatingTerms)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await appState.refreshTermsAndNotificationPreferences(reason: "terms-settings")
        }
        .refreshable {
            await appState.refreshTermsAndNotificationPreferences(reason: "terms-settings-refresh")
        }
        .sheet(item: $legalWebRoute) { route in
            #if os(iOS)
            MobileLegalWebView(url: route.url)
                .ignoresSafeArea()
            #else
            Link(route.url.absoluteString, destination: route.url)
                .padding()
            #endif
        }
    }

    private func termsRow(_ term: BackendTerms) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title(for: term))
                    .font(.body.weight(.semibold))
                Text(term.version)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            Button(strings.details) {
                legalWebRoute = MobileLegalWebRoute(url: term.url)
            }
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderless)

            Toggle(
                "",
                isOn: Binding(
                    get: { term.required || term.agreed },
                    set: { nextValue in
                        guard term.mutable else {
                            return
                        }
                        appState.saveTermsAgreementInBackground(type: term.type, isAgreed: nextValue, source: .profile)
                    }
                )
            )
            .labelsHidden()
            .disabled(!term.mutable)
        }
        .padding(.vertical, 4)
    }

    private func fallbackTerms(type: BackendTermsType) -> BackendTerms {
        switch type {
        case .termsOfService:
            return BackendTerms(
                type: type,
                code: type.rawValue,
                version: "-",
                title: strings.termsOfService,
                url: AppLegalLinks.termsOfServiceURL(language: appState.settings.appLanguage),
                contentHash: "-",
                required: true,
                mutable: false,
                agreed: true
            )
        case .privacyPolicy:
            return BackendTerms(
                type: type,
                code: type.rawValue,
                version: "-",
                title: strings.privacyPolicy,
                url: AppLegalLinks.privacyPolicyURL(language: appState.settings.appLanguage),
                contentHash: "-",
                required: true,
                mutable: false,
                agreed: true
            )
        case .marketingNotification:
            return BackendTerms(
                type: type,
                code: type.rawValue,
                version: "-",
                title: strings.marketingNotifications,
                url: AppLegalLinks.marketingNotificationURL(language: appState.settings.appLanguage),
                contentHash: "-",
                required: false,
                mutable: true,
                agreed: false
            )
        }
    }

    private func title(for term: BackendTerms) -> String {
        let baseTitle: String
        switch term.type {
        case .termsOfService:
            baseTitle = strings.termsOfService
        case .privacyPolicy:
            baseTitle = strings.privacyPolicy
        case .marketingNotification:
            baseTitle = strings.marketingNotifications
        }
        let suffix = term.required ? strings.requiredTermsBadge : strings.optionalTermsBadge
        return "\(baseTitle) [\(suffix)]"
    }
}

private struct MobileNotificationSettingsView: View {
    @EnvironmentObject private var appState: AppState

    private var strings: AppStrings { appState.strings }

    private var isQuestionEnabled: Bool {
        appState.notificationPreferences.first { $0.type == .questionNotification }?.enabled ?? false
    }

    private var isMarketingEnabled: Bool {
        appState.notificationPreferences.first { $0.type == .marketingNotification }?.enabled ?? false
    }

    var body: some View {
        List {
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(strings.questionNotifications)
                        Text(strings.questionNotificationsHelp)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Toggle(
                        "",
                        isOn: Binding(
                            get: { isQuestionEnabled },
                            set: { enabled in
                                Task { await saveQuestionPreference(enabled: enabled) }
                            }
                        )
                    )
                    .labelsHidden()
                }

                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(strings.marketingNotifications)
                        Text(strings.marketingNotificationsHelp)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Toggle(
                        "",
                        isOn: Binding(
                            get: { isMarketingEnabled },
                            set: { enabled in
                                Task { await saveMarketingPreference(enabled: enabled) }
                            }
                        )
                    )
                    .labelsHidden()
                }
            }
        }
        .navigationTitle(strings.notificationSettings)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await appState.refreshTermsAndNotificationPreferences(reason: "notification-settings")
        }
        .refreshable {
            await appState.refreshTermsAndNotificationPreferences(reason: "notification-settings-refresh")
        }
    }

    private func saveQuestionPreference(enabled: Bool) async {
        if enabled {
            guard await appState.ensureSystemNotificationPermissionForPreferenceEnable(reason: "question-notification") else {
                return
            }
        }
        appState.saveNotificationPreferenceInBackground(type: .questionNotification, enabled: enabled)
    }

    private func saveMarketingPreference(enabled: Bool) async {
        if enabled {
            guard await appState.ensureSystemNotificationPermissionForPreferenceEnable(reason: "marketing-notification") else {
                return
            }
        }
        appState.saveNotificationPreferenceInBackground(type: .marketingNotification, enabled: enabled)
    }
}

private struct ProfileAvatarColorEditorSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var hue: Double
    @State private var saturation: Double
    var onApply: (ProfileAvatarCustomColor) -> Void

    private var strings: AppStrings {
        appState.strings
    }

    private var selectedColor: ProfileAvatarCustomColor {
        ProfileAvatarCustomColor(hue: hue, saturation: saturation)
    }

    init(initialColor: ProfileAvatarCustomColor, onApply: @escaping (ProfileAvatarCustomColor) -> Void) {
        let hsv = initialColor.hsvApproximation
        _hue = State(initialValue: hsv.hue)
        _saturation = State(initialValue: hsv.saturation)
        self.onApply = onApply
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                VStack(spacing: 12) {
                    Circle()
                        .fill(selectedColor.color)
                        .frame(width: 72, height: 72)
                        .overlay {
                            Circle()
                                .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                        }

                    Text("RGB \(selectedColor.red), \(selectedColor.green), \(selectedColor.blue)")
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 14)

                ProfileAvatarSpectrumPicker(hue: $hue, saturation: $saturation)
                    .frame(height: 190)
                    .padding(.horizontal, 4)

                Text(strings.customProfileColor)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)

                Spacer(minLength: 0)
            }
            .padding(20)
            .navigationTitle(strings.profileColor)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.done) {
                        onApply(selectedColor)
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct ProfileAvatarSpectrumPicker: View {
    @Binding var hue: Double
    @Binding var saturation: Double

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let x = CGFloat(hue) * size.width
            let y = (1 - CGFloat(saturation)) * size.height

            ZStack(alignment: .topLeading) {
                LinearGradient(
                    colors: stride(from: 0.0, through: 1.0, by: 0.1).map {
                        Color(hue: $0, saturation: 1, brightness: 0.95)
                    },
                    startPoint: .leading,
                    endPoint: .trailing
                )
                .overlay {
                    LinearGradient(
                        colors: [.white, .white.opacity(0)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .blendMode(.screen)
                }
                .overlay {
                    LinearGradient(
                        colors: [.black.opacity(0), .black.opacity(0.18)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }

                Circle()
                    .strokeBorder(.white, lineWidth: 3)
                    .background(Circle().fill(Color(hue: hue, saturation: saturation, brightness: 0.92)))
                    .shadow(color: .black.opacity(0.28), radius: 4, x: 0, y: 2)
                    .frame(width: 34, height: 34)
                    .position(x: min(max(x, 17), size.width - 17), y: min(max(y, 17), size.height - 17))
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        hue = min(1, max(0, Double(value.location.x / max(size.width, 1))))
                        saturation = min(1, max(0, 1 - Double(value.location.y / max(size.height, 1))))
                    }
            )
        }
        .accessibilityHidden(true)
    }
}

private struct EmailSignInSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var verificationCode = ""
    @State private var isSubmitting = false
    @State private var isSendingCode = false
    @State private var didSendCode = false
    @State private var requiresVerification = false
    @FocusState private var focusedField: Field?
    var onSignedIn: () -> Void

    private enum Field {
        case email
        case password
        case verificationCode
    }

    private var strings: AppStrings {
        appState.strings
    }

    private var canSubmit: Bool {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCode = verificationCode.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasRequiredCode = !requiresVerification || trimmedCode.count >= 4
        return normalizedEmail.contains("@") && password.count >= 6 && hasRequiredCode && !isSubmitting && !isSendingCode
    }

    private var canSendCode: Bool {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalizedEmail.contains("@") && !isSendingCode && !isSubmitting
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(strings.email, text: $email)
                        .textInputAutocapitalization(.never)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focusedField, equals: .email)

                    SecureField(strings.password, text: $password)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .focused($focusedField, equals: .password)

                    if requiresVerification {
                        TextField(strings.emailVerificationCode, text: $verificationCode)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .submitLabel(.done)
                            .focused($focusedField, equals: .verificationCode)
                    }
                } footer: {
                    if requiresVerification {
                        Text(strings.emailVerificationRequired)
                    }
                }

                if requiresVerification {
                    Section {
                    Button {
                        Task {
                            isSendingCode = true
                            let didSend = await appState.requestEmailVerificationCode(email: email)
                            didSendCode = didSend
                            isSendingCode = false
                        }
                    } label: {
                        HStack {
                            Text(didSendCode ? strings.resendVerificationCode : strings.sendVerificationCode)
                            Spacer()
                            if isSendingCode {
                                ProgressView()
                                    .controlSize(.small)
                            }
                        }
                    }
                    .disabled(!canSendCode)

                    if didSendCode {
                        Text(strings.emailVerificationSent)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    if let message = appState.communityErrorMessage,
                       !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    }
                } else if let message = appState.communityErrorMessage,
                          !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Section {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .keyboardDoneToolbar(strings.done)
            .onChange(of: email) { _, _ in
                requiresVerification = false
                didSendCode = false
                verificationCode = ""
                appState.communityErrorMessage = nil
            }
            .navigationTitle(strings.signInWithEmail)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            isSubmitting = true
                            let code = verificationCode.trimmingCharacters(in: .whitespacesAndNewlines)
                            let result = await appState.signInToCommunity(
                                email: email,
                                password: password,
                                verificationCode: code.isEmpty ? nil : code
                            )
                            isSubmitting = false
                            switch result {
                            case .signedIn:
                                onSignedIn()
                            case .verificationRequired:
                                withAnimation(.snappy(duration: 0.2)) {
                                    requiresVerification = true
                                }
                                didSendCode = false
                                focusedField = .verificationCode
                            case .failed:
                                break
                            }
                        }
                    } label: {
                        if isSubmitting {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text(strings.communityLogin)
                        }
                    }
                    .disabled(!canSubmit)
                }
            }
        }
    }
}

enum ProfileAvatarOption {
    static let defaultSymbolName = BuddyStudyAvatar.symbolName

    static let all = [
        defaultSymbolName,
        "pixel-cat",
        "pixel-rabbit",
        "pixel-penguin",
        "pixel-scholar",
        "pixel-coder",
        "pixel-explorer",
        "pixel-gamer",
        "pixel-scientist",
        "pixel-astronaut",
        "pixel-knight",
        "pixel-wizard",
        "pixel-tutor-bot"
    ]

    static func glyphName(for symbolName: String) -> String {
        canonicalName(for: symbolName)
    }

    static func canonicalName(for symbolName: String) -> String {
        let normalized = symbolName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if all.contains(normalized) {
            return normalized
        }
        if normalized.contains("cat") {
            return "pixel-cat"
        }
        if normalized.contains("rabbit") {
            return "pixel-rabbit"
        }
        if normalized.contains("penguin") {
            return "pixel-penguin"
        }
        if normalized.contains("dog") || normalized.contains("pup") || normalized.contains("dachshund") {
            return "pixel-explorer"
        }
        if normalized.contains("robot") {
            return "pixel-tutor-bot"
        }
        if normalized.contains("coder") || normalized.contains("hacker") {
            return "pixel-coder"
        }
        if normalized.contains("wizard") || normalized.contains("mage") {
            return "pixel-wizard"
        }
        if normalized.contains("gamer") {
            return "pixel-gamer"
        }
        return defaultSymbolName
    }
}

private struct ProfileAvatarColorOption: Identifiable {
    var id: String
    var color: Color

    static let all: [ProfileAvatarColorOption] = [
        ProfileAvatarColorOption(id: "avatar-color-sage", color: Color(red: 0.39, green: 0.60, blue: 0.43)),
        ProfileAvatarColorOption(id: "avatar-color-mint", color: Color(red: 0.20, green: 0.72, blue: 0.52)),
        ProfileAvatarColorOption(id: "avatar-color-teal", color: Color(red: 0.16, green: 0.62, blue: 0.70)),
        ProfileAvatarColorOption(id: "avatar-color-sky", color: Color(red: 0.20, green: 0.50, blue: 0.86)),
        ProfileAvatarColorOption(id: "avatar-color-denim", color: Color(red: 0.24, green: 0.38, blue: 0.68)),
        ProfileAvatarColorOption(id: "avatar-color-indigo", color: Color(red: 0.38, green: 0.34, blue: 0.72)),
        ProfileAvatarColorOption(id: "avatar-color-violet", color: Color(red: 0.50, green: 0.36, blue: 0.82)),
        ProfileAvatarColorOption(id: "avatar-color-plum", color: Color(red: 0.60, green: 0.34, blue: 0.62)),
        ProfileAvatarColorOption(id: "avatar-color-rose", color: Color(red: 0.84, green: 0.33, blue: 0.47)),
        ProfileAvatarColorOption(id: "avatar-color-coral", color: Color(red: 0.92, green: 0.43, blue: 0.34)),
        ProfileAvatarColorOption(id: "avatar-color-amber", color: Color(red: 0.86, green: 0.56, blue: 0.20)),
        ProfileAvatarColorOption(id: "avatar-color-gold", color: Color(red: 0.76, green: 0.64, blue: 0.24)),
        ProfileAvatarColorOption(id: "avatar-color-lime", color: Color(red: 0.48, green: 0.70, blue: 0.28)),
        ProfileAvatarColorOption(id: "avatar-color-olive", color: Color(red: 0.47, green: 0.52, blue: 0.31)),
        ProfileAvatarColorOption(id: "avatar-color-cocoa", color: Color(red: 0.55, green: 0.39, blue: 0.28)),
        ProfileAvatarColorOption(id: "avatar-color-slate", color: Color(red: 0.33, green: 0.39, blue: 0.48)),
        ProfileAvatarColorOption(id: "avatar-color-graphite", color: Color(red: 0.36, green: 0.38, blue: 0.42)),
        ProfileAvatarColorOption(id: "avatar-color-charcoal", color: Color(red: 0.20, green: 0.22, blue: 0.25))
    ]

    static func option(for seed: String) -> ProfileAvatarColorOption? {
        all.first { $0.id == seed }
    }
}

private struct ProfileAvatarCustomColor: Equatable {
    var red: Int
    var green: Int
    var blue: Int

    var seed: String {
        "avatar-rgb-\(Self.clamped(red))-\(Self.clamped(green))-\(Self.clamped(blue))"
    }

    var color: Color {
        Color(
            red: Double(Self.clamped(red)) / 255,
            green: Double(Self.clamped(green)) / 255,
            blue: Double(Self.clamped(blue)) / 255
        )
    }

    init(red: Int, green: Int, blue: Int) {
        self.red = Self.clamped(red)
        self.green = Self.clamped(green)
        self.blue = Self.clamped(blue)
    }

    init?(seed: String) {
        let parts = seed.split(separator: "-")
        guard parts.count == 5,
              parts[0] == "avatar",
              parts[1] == "rgb",
              let red = Int(parts[2]),
              let green = Int(parts[3]),
              let blue = Int(parts[4]) else {
            return nil
        }

        self.init(red: red, green: green, blue: blue)
    }

    init(hue: Double, saturation: Double) {
        let clampedHue = min(1, max(0, hue))
        let clampedSaturation = min(1, max(0, saturation))
        let brightness = 0.92
        let sector = clampedHue * 6
        let chroma = brightness * clampedSaturation
        let x = chroma * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let match = brightness - chroma
        let rgb: (Double, Double, Double)

        switch sector {
        case 0..<1:
            rgb = (chroma, x, 0)
        case 1..<2:
            rgb = (x, chroma, 0)
        case 2..<3:
            rgb = (0, chroma, x)
        case 3..<4:
            rgb = (0, x, chroma)
        case 4..<5:
            rgb = (x, 0, chroma)
        default:
            rgb = (chroma, 0, x)
        }

        self.init(
            red: Int(((rgb.0 + match) * 255).rounded()),
            green: Int(((rgb.1 + match) * 255).rounded()),
            blue: Int(((rgb.2 + match) * 255).rounded())
        )
    }

    var hsvApproximation: (hue: Double, saturation: Double) {
        let r = Double(red) / 255
        let g = Double(green) / 255
        let b = Double(blue) / 255
        let maxValue = max(r, g, b)
        let minValue = min(r, g, b)
        let delta = maxValue - minValue
        let saturation = maxValue == 0 ? 0 : delta / maxValue
        let hue: Double

        if delta == 0 {
            hue = 0
        } else if maxValue == r {
            hue = ((g - b) / delta).truncatingRemainder(dividingBy: 6) / 6
        } else if maxValue == g {
            hue = (((b - r) / delta) + 2) / 6
        } else {
            hue = (((r - g) / delta) + 4) / 6
        }

        return (hue < 0 ? hue + 1 : hue, saturation)
    }

    static func from(seed: String) -> ProfileAvatarCustomColor {
        if let customColor = ProfileAvatarCustomColor(seed: seed) {
            return customColor
        }
        if let preset = ProfileAvatarColorOption.option(for: seed),
           let components = preset.color.avatarRGBComponents {
            return ProfileAvatarCustomColor(red: components.red, green: components.green, blue: components.blue)
        }
        return ProfileAvatarCustomColor(red: 244, green: 181, blue: 94)
    }

    private static func clamped(_ value: Int) -> Int {
        min(255, max(0, value))
    }
}

private extension Color {
    var avatarRGBComponents: (red: Int, green: Int, blue: Int)? {
        #if canImport(UIKit)
        let uiColor = UIColor(self)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return nil
        }
        return (
            Int((red * 255).rounded()),
            Int((green * 255).rounded()),
            Int((blue * 255).rounded())
        )
        #else
        return nil
        #endif
    }
}

private struct SignInButtonLabel: View {
    var title: String
    var isPrimary: Bool

    var body: some View {
        let buttonShape = RoundedRectangle(cornerRadius: 24, style: .continuous)

        Text(title)
            .font(.body.weight(.semibold))
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 58)
            .padding(.horizontal, 18)
            .background {
                buttonShape
                    .fill(isPrimary ? Color.primary : Color.secondary.opacity(0.14))
            }
            .overlay {
                buttonShape
                    .stroke(isPrimary ? Color.clear : Color.secondary.opacity(0.12), lineWidth: 1)
            }
            .contentShape(buttonShape)
            .foregroundStyle(isPrimary ? Color(UIColor.systemBackground) : Color.primary)
    }
}

private extension StudyCategory {
    func matchesHomeSearch(_ rawQuery: String, appLanguage: AppLanguage) -> Bool {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return true
        }

        let searchableFields = [
            normalizedTitle,
            normalizedCustomPrompt,
            difficulty.displayName(language: appLanguage),
            "level \(difficulty.level)",
            "레벨 \(difficulty.level)"
        ]

        return searchableFields.contains { field in
            field.localizedCaseInsensitiveContains(query)
        }
    }
}

private struct MobileHomeRefreshIndicator: View {
    var body: some View {
        ProgressView()
            .controlSize(.regular)
    }
}

private struct MobileCommunityEmptyState: View {
    let strings: AppStrings

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 54, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(strings.noCommunityQuestions)
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.vertical, 34)
    }
}

private struct MobileHomeStudyTopicItem {
    var room: BackendStudyRoom
}

private enum MobileHomeStudyOutlineAction {
    case openTopic(BackendStudyRoom)
    case configureTopic(BackendStudyRoom)
    case configureRoot
    case openTree
}

private enum MobileStudyHierarchyPosition {
    case root(continues: Bool)
    case child(isLast: Bool)
}

private struct MobileHomeStudyOutlineSnapshot {
    var root: BackendStudyRoom
    var roomsByID: [Int: BackendStudyRoom]
    var childrenByParent: [Int: [BackendStudyRoom]]
    var parentByID: [Int: Int]
    var searchQuery: String
    var searchResults: [BackendStudyRoom]?

    func room(id: Int) -> BackendStudyRoom? {
        roomsByID[id]
    }

    func children(of roomID: Int) -> [BackendStudyRoom] {
        childrenByParent[roomID] ?? []
    }

    func path(to roomID: Int) -> [BackendStudyRoom] {
        StudyOutlinePolicy.ancestorPath(
            rootID: root.id,
            targetID: roomID,
            parentByID: parentByID
        ).compactMap { roomsByID[$0] }
    }
}

private struct MobileHomeStudyOutlineRow: View {
    private enum GroupPosition {
        case standalone
        case top
        case middle
        case bottom
    }

    var snapshot: MobileHomeStudyOutlineSnapshot
    var strings: AppStrings
    var pendingQuestionCount: (BackendStudyRoom) -> Int
    var onAction: (MobileHomeStudyOutlineAction) -> Void
    @Environment(\.accessibilityReduceMotion) private var accessibilityReduceMotion
    @State private var currentBranchID: Int?
    @State private var isExpanded = true
    @State private var isChangingBranch = false
    @State private var isBranchContentRevealed = true
    @State private var branchTransitionDirection = 1.0
    @State private var branchUnlockTask: Task<Void, Never>?

    private var currentBranch: BackendStudyRoom {
        currentBranchID.flatMap(snapshot.room(id:)) ?? snapshot.root
    }

    private var currentPath: [BackendStudyRoom] {
        snapshot.path(to: currentBranch.id)
    }

    private var currentChildren: [BackendStudyRoom] {
        snapshot.children(of: currentBranch.id)
    }

    private var visibleChildren: [BackendStudyRoom] {
        Array(currentChildren.prefix(StudyOutlinePolicy.childPreviewLimit))
    }

    private var hasRootChildren: Bool {
        !snapshot.children(of: snapshot.root.id).isEmpty
    }

    @ViewBuilder
    var body: some View {
        studyCard(
            position: isExpanded && hasRootChildren ? .top : .standalone
        ) {
            studyNavigationRow(
                room: snapshot.root,
                isRoot: true,
                isChildListExpanded: isExpanded,
                onOpenChildren: {
                    isExpanded.toggle()
                }
            )
        }
        .onChange(of: snapshot.searchQuery) {
            branchUnlockTask?.cancel()
            isBranchContentRevealed = true
            isChangingBranch = false
            currentBranchID = nil
        }
        .onDisappear {
            branchUnlockTask?.cancel()
        }

        if isExpanded && hasRootChildren {
            if let searchResults = snapshot.searchResults {
                searchResultRows(searchResults)
            } else {
                branchRows
            }
        }
    }

    @ViewBuilder
    private var branchRows: some View {
        studyCard(position: .middle) {
            VStack(spacing: 0) {
                Divider()
                    .padding(.leading, 14)

                branchPathHeader
            }
        }

        ForEach(Array(visibleChildren.enumerated()), id: \.element.id) { index, room in
            studyCard(
                position: index == visibleChildren.indices.last ? .bottom : .middle
            ) {
                VStack(spacing: 0) {
                    Divider()
                        .padding(.leading, 50)

                    studyNavigationRow(
                        room: room,
                        isRoot: false,
                        isLastSibling: index == visibleChildren.indices.last,
                        onOpenChildren: snapshot.children(of: room.id).isEmpty
                            ? nil
                            : {
                                replaceBranch(with: room.id, direction: 1)
                            }
                    )
                }
            }
            .opacity(isBranchContentRevealed ? 1 : 0.72)
            .offset(
                x: isBranchContentRevealed
                    ? 0
                    : branchTransitionDirection * 8
            )
            .animation(
                .easeOut(duration: 0.18).delay(Double(index) * 0.025),
                value: isBranchContentRevealed
            )
            .allowsHitTesting(!isChangingBranch)
        }
    }

    @ViewBuilder
    private func studyCard<Content: View>(
        position: GroupPosition,
        @ViewBuilder content: () -> Content
    ) -> some View {
        let topRadius: CGFloat = position == .standalone || position == .top ? 18 : 0
        let bottomRadius: CGFloat = position == .standalone || position == .bottom ? 18 : 0
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: topRadius,
            bottomLeadingRadius: bottomRadius,
            bottomTrailingRadius: bottomRadius,
            topTrailingRadius: topRadius,
            style: .continuous
        )

        content()
            .background(
                Color(.secondarySystemBackground),
                in: shape
            )
            .clipShape(shape)
            .overlay {
                shape
                    .stroke(Color.primary.opacity(0.04), lineWidth: 1)
            }
            .contentShape(shape)
            .listRowInsets(
                EdgeInsets(
                    top: position == .standalone || position == .top ? 6 : 0,
                    leading: 0,
                    bottom: position == .standalone || position == .bottom ? 6 : 0,
                    trailing: 0
                )
            )
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    private var branchPathHeader: some View {
        HStack(spacing: 10) {
            MobileStudyHierarchyContinuation()
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(currentPath.map(\.topic).joined(separator: "  ›  "))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.head)

                Text(strings.childTopics)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "\(currentPath.map(\.topic).joined(separator: ", ")), \(strings.childTopics)"
            )

            if currentBranch.id != snapshot.root.id {
                Button {
                    let parentID = snapshot.parentByID[currentBranch.id]
                        .flatMap(snapshot.room(id:))?.id
                    replaceBranch(with: parentID, direction: -1)
                } label: {
                    Label(strings.moveToParentTopic, systemImage: "chevron.left")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(minHeight: 48)
        .opacity(isBranchContentRevealed ? 1 : 0.78)
        .offset(
            x: isBranchContentRevealed
                ? 0
                : branchTransitionDirection * 6
        )
        .animation(
            .easeOut(duration: 0.16),
            value: isBranchContentRevealed
        )
    }

    @ViewBuilder
    private func searchResultRows(_ results: [BackendStudyRoom]) -> some View {
        let visibleResults = Array(results.prefix(StudyOutlinePolicy.childPreviewLimit))

        ForEach(Array(visibleResults.enumerated()), id: \.element.id) { index, room in
            studyCard(
                position: index == visibleResults.indices.last ? .bottom : .middle
            ) {
                VStack(spacing: 0) {
                    Divider()
                        .padding(.leading, 50)

                    Button {
                        onAction(.openTopic(room))
                    } label: {
                        studyDestinationContent(
                            room: room,
                            isRoot: false,
                            childCount: snapshot.children(of: room.id).count,
                            showsDisclosure: true,
                            hierarchyPosition: .child(
                                isLast: index == visibleResults.indices.last
                            ),
                            ancestorPath: snapshot.path(to: room.id)
                                .dropLast()
                                .map(\.topic)
                                .joined(separator: "  ›  ")
                        )
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(.plain)
                    .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .contextMenu {
                        topicActions(for: room)
                    }
                    .accessibilityAction(named: strings.editStudyCategory) {
                        onAction(.configureTopic(room))
                    }
                    .accessibilityAction(named: strings.viewFullStudyTree) {
                        onAction(.openTree)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func topicActions(for room: BackendStudyRoom) -> some View {
        Button {
            onAction(.configureTopic(room))
        } label: {
            Label(strings.editStudyCategory, systemImage: "pencil")
        }

        Button {
            onAction(.openTree)
        } label: {
            Label(
                strings.viewFullStudyTree,
                systemImage: "point.3.connected.trianglepath.dotted"
            )
        }
    }

    @ViewBuilder
    private var rootActions: some View {
        Button {
            onAction(.configureRoot)
        } label: {
            Label(strings.editStudyCategory, systemImage: "pencil")
        }

        Button {
            onAction(.openTree)
        } label: {
            Label(
                strings.viewFullStudyTree,
                systemImage: "point.3.connected.trianglepath.dotted"
            )
        }
    }

    private func studyNavigationRow(
        room: BackendStudyRoom,
        isRoot: Bool,
        isLastSibling: Bool = false,
        isChildListExpanded: Bool? = nil,
        onOpenChildren: (() -> Void)?
    ) -> some View {
        let childCount = snapshot.children(of: room.id).count

        return HStack(spacing: 8) {
            studyDestinationButton(
                room: room,
                isRoot: isRoot,
                childCount: childCount,
                hierarchyPosition: isRoot
                    ? .root(continues: isChildListExpanded == true && childCount > 0)
                    : .child(isLast: isLastSibling)
            )

            if childCount > 0, let onOpenChildren {
                let childNavigationButton = Button(action: onOpenChildren) {
                    childTopicActionLabel(
                        childCount: childCount,
                        isExpanded: isChildListExpanded
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .frame(width: 70)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .layoutPriority(1)

                if isRoot {
                    childNavigationButton
                        .contextMenu {
                            rootActions
                        }
                } else {
                    childNavigationButton
                        .contextMenu {
                            topicActions(for: room)
                        }
                }
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: isRoot ? 70 : 64)
    }

    @ViewBuilder
    private func studyDestinationButton(
        room: BackendStudyRoom,
        isRoot: Bool,
        childCount: Int,
        hierarchyPosition: MobileStudyHierarchyPosition
    ) -> some View {
        let button = Button {
            onAction(.openTopic(room))
        } label: {
            studyDestinationContent(
                room: room,
                isRoot: isRoot,
                childCount: childCount,
                showsDisclosure: childCount == 0,
                hierarchyPosition: hierarchyPosition
            )
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())

        if isRoot {
            button
                .contentShape(
                    .contextMenuPreview,
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                )
                .contextMenu {
                    rootActions
                }
                .accessibilityAction(named: strings.editStudyCategory) {
                    onAction(.configureRoot)
                }
                .accessibilityAction(named: strings.viewFullStudyTree) {
                    onAction(.openTree)
                }
        } else {
            button
                .contentShape(
                    .contextMenuPreview,
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                )
                .contextMenu {
                    topicActions(for: room)
                }
                .accessibilityAction(named: strings.editStudyCategory) {
                    onAction(.configureTopic(room))
                }
                .accessibilityAction(named: strings.viewFullStudyTree) {
                    onAction(.openTree)
                }
        }
    }

    private func studyDestinationContent(
        room: BackendStudyRoom,
        isRoot: Bool,
        childCount: Int,
        showsDisclosure: Bool,
        hierarchyPosition: MobileStudyHierarchyPosition,
        ancestorPath: String? = nil
    ) -> some View {
        let pendingCount = pendingQuestionCount(room)
        let levelText = StudyTreeNodeStylePolicy.levelText(room.difficultyLevel)

        return HStack(spacing: 12) {
            MobileStudyHierarchyMarker(
                position: hierarchyPosition,
                isActive: room.activeForQuestions,
                strings: strings
            )
            .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                if let ancestorPath, !ancestorPath.isEmpty {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.turn.down.right")
                            .font(.caption2.weight(.semibold))

                        Text(ancestorPath)
                            .lineLimit(1)
                            .truncationMode(.head)
                    }
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                }

                Text(room.topic)
                    .font(isRoot ? .body.weight(.semibold) : .subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 5) {
                    if !isRoot {
                        Text(levelText)
                            .font(.caption.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(
                                room.activeForQuestions ? Color.green : Color.secondary
                            )

                        Text("·")
                            .foregroundStyle(.tertiary)
                    }

                    HStack(spacing: 3) {
                        Text(strings.studyAction)

                        Image(systemName: "arrow.up.right")
                    }
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityValue(
                childCount > 0
                    ? "\(isRoot ? "" : "\(levelText), ")\(strings.childTopicCount(childCount))"
                    : "\(isRoot ? "" : "\(levelText), ")\(strings.openStudyPage)"
            )

            if pendingCount > 0 {
                Text("\(pendingCount)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(minWidth: 22, minHeight: 22)
                    .background(Color.red, in: Circle())
                    .accessibilityLabel(strings.pendingQuestionCount(pendingCount))
            }

            if showsDisclosure {
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(minHeight: isRoot ? 70 : 64)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(room.topic), \(strings.openStudyPage)")
    }

    private func replaceBranch(with roomID: Int?, direction: Double) {
        guard !isChangingBranch else {
            return
        }

        branchUnlockTask?.cancel()
        isChangingBranch = true

        if accessibilityReduceMotion {
            currentBranchID = roomID
            isBranchContentRevealed = true
            isChangingBranch = false
            return
        }

        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            branchTransitionDirection = direction
            isBranchContentRevealed = false
            currentBranchID = roomID
        }

        branchUnlockTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(16))
            guard !Task.isCancelled else {
                return
            }

            isBranchContentRevealed = true
            try? await Task.sleep(for: .milliseconds(240))
            guard !Task.isCancelled else {
                return
            }

            isChangingBranch = false
        }
    }

    private func childTopicActionLabel(
        childCount: Int,
        isExpanded: Bool?
    ) -> some View {
        HStack(spacing: 4) {
            Text(strings.childTopicAction(childCount))
                .lineLimit(1)

            Image(systemName: isExpanded == true ? "chevron.up" : "chevron.down")
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color(.tertiarySystemFill), in: Capsule())
        .accessibilityLabel(
            isExpanded.map {
                $0 ? strings.collapseStudyTopics : strings.expandStudyTopics
            } ?? strings.childTopicCount(childCount)
        )
    }
}

private struct MobileStudyHierarchyContinuation: View {
    var body: some View {
        GeometryReader { proxy in
            Path { path in
                path.move(to: CGPoint(x: 8, y: 0))
                path.addLine(to: CGPoint(x: 8, y: proxy.size.height))
            }
            .stroke(
                Color.secondary.opacity(0.28),
                style: StrokeStyle(lineWidth: 1.5, lineCap: .round)
            )
        }
        .accessibilityHidden(true)
    }
}

private struct MobileStudyHierarchyMarker: View {
    var position: MobileStudyHierarchyPosition
    var isActive: Bool
    var strings: AppStrings

    var body: some View {
        GeometryReader { proxy in
            let centerY = proxy.size.height / 2
            let trunkX: CGFloat = 8
            let childX: CGFloat = 24
            let lineColor = Color.secondary.opacity(0.28)

            ZStack(alignment: .topLeading) {
                Path { path in
                    switch position {
                    case let .root(continues):
                        if continues {
                            path.move(to: CGPoint(x: trunkX, y: centerY))
                            path.addLine(to: CGPoint(x: trunkX, y: proxy.size.height))
                        }
                    case let .child(isLast):
                        path.move(to: CGPoint(x: trunkX, y: 0))
                        path.addLine(
                            to: CGPoint(
                                x: trunkX,
                                y: isLast ? centerY : proxy.size.height
                            )
                        )
                        path.move(to: CGPoint(x: trunkX, y: centerY))
                        path.addLine(to: CGPoint(x: childX, y: centerY))
                    }
                }
                .stroke(
                    lineColor,
                    style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round)
                )

                Circle()
                    .fill(
                        isActive
                            ? Color.green
                            : Color.secondary.opacity(0.5)
                    )
                    .frame(
                        width: isRoot ? 12 : 9,
                        height: isRoot ? 12 : 9
                    )
                    .overlay {
                        Circle()
                            .stroke(
                                isActive
                                    ? Color.green.opacity(0.18)
                                    : Color.secondary.opacity(0.12),
                                lineWidth: isRoot ? 5 : 3
                            )
                    }
                    .position(
                        x: isRoot ? trunkX : childX,
                        y: centerY
                    )
            }
        }
        .accessibilityLabel(isActive ? strings.questionTopicActive : strings.questionTopicInactive)
    }

    private var isRoot: Bool {
        if case .root = position {
            return true
        }
        return false
    }
}

private struct MobileHomeCategoryRow: View {
    var category: StudyCategory
    var hasPendingQuestion: Bool
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(category.title)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .font(.body.weight(.semibold))
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if hasPendingQuestion {
                ZStack {
                    Circle()
                        .fill(Color.green.opacity(0.14))
                        .frame(width: 22, height: 22)
                    Circle()
                        .fill(Color.green)
                        .frame(width: 8, height: 8)
                }
                .accessibilityLabel(strings.pendingQuestionLimitTitle)
            }

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(14)
        .frame(minHeight: 70)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.04), lineWidth: 1)
        }
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

}

private struct StudyCategoryEditorSheet: View {
    var category: StudyCategory?
    var strings: AppStrings
    var onDelete: (() -> Void)?
    var onSave: (String, Difficulty, String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var difficultyLevel: Double
    @State private var customPrompt: String
    @State private var showsDeleteConfirmation = false

    init(
        category: StudyCategory?,
        strings: AppStrings,
        onDelete: (() -> Void)? = nil,
        onSave: @escaping (String, Difficulty, String, String) -> Void
    ) {
        self.category = category
        self.strings = strings
        self.onDelete = onDelete
        self.onSave = onSave
        _title = State(initialValue: category?.title ?? "")
        _difficultyLevel = State(initialValue: Double((category?.difficulty ?? .beginner).level))
        _customPrompt = State(initialValue: category?.customPrompt ?? StudySettings.defaultCustomPrompt)
    }

    private var canSave: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(strings.studySettings) {
                    TextField(strings.studyTopic, text: $title)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(strings.difficulty)
                            Spacer()
                            Text(Difficulty(level: resolvedDifficultyLevel).displayName(language: strings.language))
                                .fontWeight(.semibold)
                                .monospacedDigit()
                        }

                        Slider(value: $difficultyLevel, in: 1...10, step: 1)

                        HStack {
                            Text("1")
                            Spacer()
                            Text("10")
                        }
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    }
                }

                Section(strings.relatedPrompt) {
                    Menu {
                        ForEach(RecommendedPrompt.allCases) { prompt in
                            Button(prompt.title(language: strings.language)) {
                                customPrompt = prompt.text(language: strings.language)
                            }
                        }
                    } label: {
                        Text(strings.recommendedPrompt)
                    }

                    TextEditor(text: $customPrompt)
                        .frame(minHeight: 130)
                }

                if onDelete != nil {
                    Section {
                        Button(role: .destructive) {
                            showsDeleteConfirmation = true
                        } label: {
                            Text(strings.deleteStudy)
                        }
                    }
                }
            }
            .keyboardDoneToolbar(strings.done)
            .navigationTitle(category == nil ? strings.newStudyCategory : strings.editStudyCategory)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.save) {
                        onSave(title, Difficulty(level: resolvedDifficultyLevel), customPrompt, category?.sanitizedOpenAIModel ?? StudySettings.defaultOpenAIModel)
                        dismiss()
                    }
                    .disabled(!canSave)
                }
            }
            .confirmationDialog(strings.deleteStudy, isPresented: $showsDeleteConfirmation) {
                Button(strings.deleteStudy, role: .destructive) {
                    onDelete?()
                    dismiss()
                }
                Button(strings.cancel, role: .cancel) {}
            }
        }
    }

    private var resolvedDifficultyLevel: Int {
        min(max(Int(difficultyLevel.rounded()), 1), 10)
    }
}

private struct MobileFeedbackPromptRow: View {
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                Text("BuddyStudy")
                Text(strings.feedback)
                Spacer(minLength: 0)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .lineLimit(1)

            Text(strings.feedbackPromptTitle)
                .font(.body.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .truncationMode(.tail)

            HStack(spacing: 5) {
                Image(systemName: "text.bubble")
                Text(strings.feedbackPromptBody)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer(minLength: 0)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

private enum MobileFeedbackCategory: String, CaseIterable, Identifiable {
    case general = "GENERAL"
    case bug = "BUG"
    case feature = "FEATURE"

    var id: String { rawValue }

    func title(strings: AppStrings) -> String {
        switch self {
        case .general:
            return strings.feedbackCategoryGeneral
        case .bug:
            return strings.feedbackCategoryBug
        case .feature:
            return strings.feedbackCategoryFeature
        }
    }
}

private struct MobileFeedbackView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var category: MobileFeedbackCategory = .general
    @State private var message = ""
    @State private var isSubmitting = false

    private var strings: AppStrings { appState.strings }
    private var normalizedMessage: String {
        message.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        Form {
            Section(strings.feedbackCategory) {
                Picker(strings.feedbackCategory, selection: $category) {
                    ForEach(MobileFeedbackCategory.allCases) { category in
                        Text(category.title(strings: strings)).tag(category)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section(strings.feedbackMessage) {
                ZStack(alignment: .topLeading) {
                    if normalizedMessage.isEmpty {
                        Text(strings.feedbackMessagePlaceholder)
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                            .allowsHitTesting(false)
                    }
                    TextEditor(text: $message)
                        .frame(minHeight: 180)
                }
            }
        }
        .navigationTitle(strings.feedback)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    submit()
                } label: {
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Text(strings.feedbackSubmit)
                    }
                }
                .disabled(isSubmitting || normalizedMessage.count < 2)
            }
        }
    }

    private func submit() {
        let submittedMessage = normalizedMessage
        guard submittedMessage.count >= 2 else {
            return
        }
        isSubmitting = true
        Task {
            let submitted = await appState.submitAppFeedback(
                category: category.rawValue,
                message: submittedMessage
            )
            isSubmitting = false
            if submitted {
                dismiss()
            }
        }
    }
}

private struct MobileCommunityQuestionRow: View {
    var question: CommunityQuestion

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            CommunityQuestionTopMeta(question: question)

            Text(MarkdownContent.plainText(question.question))
                .font(.body.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .truncationMode(.tail)

            CommunityQuestionStatsMeta(question: question)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum CommunityQuestionDetailContentSource: Equatable {
    case community
    case record(isPublic: Bool)

    var showsCommunityInteractions: Bool {
        switch self {
        case .community:
            true
        case let .record(isPublic):
            isPublic
        }
    }
}

struct CommunityQuestionDetailView: View {
    @EnvironmentObject private var appState: AppState
    var question: CommunityQuestion
    var contentSource: CommunityQuestionDetailContentSource
    @State private var displayQuestion: CommunityQuestion
    @State private var comments: [CommunityQuestionComment] = []
    @State private var commentsTotalCount = 0
    @State private var hasLoadedComments = false
    @State private var commentDraft = ""
    @State private var isSendingComment = false
    @State private var deletingCommentIDs: Set<String> = []
    @State private var isShowingOriginal = false
    @State private var originalAvailable: Bool
    @FocusState private var isCommentInputFocused: Bool

    init(
        question: CommunityQuestion,
        contentSource: CommunityQuestionDetailContentSource = .community
    ) {
        self.question = question
        self.contentSource = contentSource
        _displayQuestion = State(initialValue: question)
        _commentsTotalCount = State(initialValue: question.commentCount)
        _originalAvailable = State(
            initialValue: question.localization?.containsTranslation == true ||
                question.localization?.question.originalAvailable == true
        )
    }

    private var strings: AppStrings {
        appState.strings
    }

    private var canWriteCommunityReaction: Bool {
        contentSource.showsCommunityInteractions && appState.isCommunitySessionActive
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                communityQuestionMeta

                localizationControl

                CommunityMessageBubble(role: .question) {
                    MarkdownMessageText(markdown: displayQuestion.question)
                        .font(.body)
                        .foregroundStyle(.primary)
                        .tint(.accentColor)
                        .textSelection(.enabled)
                }

                if let answer = displayQuestion.answer?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !answer.isEmpty {
                    CommunityAnswerMessage(answer: answer)
                }

                if let gradingResult = displayQuestion.gradingResult {
                    CommunityMessageBubble(role: .feedback) {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Label(gradingResult.gradeTitle(strings: strings), systemImage: gradingResult.gradeIconName)
                                Spacer(minLength: 12)
                                Text("\(gradingResult.score)/100")
                                    .font(.headline)
                            }

                            MarkdownMessageText(markdown: gradingResult.feedback)
                                .font(.body)

                            MarkdownMessageText(markdown: gradingResult.explanation)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if contentSource.showsCommunityInteractions {
                    communityActions

                    Divider()

                    commentsSection
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(strings.communityQuestion)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button(strings.done) {
                    isCommentInputFocused = false
                }
            }
        }
        .task(id: displayQuestion.id) {
            if contentSource.showsCommunityInteractions {
                applyCachedComments()
            }
            async let questionLoad: Void = loadQuestionDetail()
            async let commentsLoad: Void = loadCommentsIfAvailable()
            _ = await (questionLoad, commentsLoad)
        }
    }

    private var communityQuestionMeta: some View {
        HStack(spacing: 9) {
            if let author = displayQuestion.author {
                HomeProfileAvatar(
                    symbolName: author.avatarSymbolName,
                    displayName: author.displayName,
                    colorSeed: author.avatarColorSeed,
                    size: 34
                )
            }

            VStack(alignment: .leading, spacing: 3) {
                if let author = displayQuestion.author, !author.displayName.isEmpty {
                    Text(author.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }

                HStack(spacing: 6) {
                    Text(displayQuestion.topic.isEmpty ? "Swift" : displayQuestion.topic)
                        .lineLimit(1)

                    Text("Lv.\(displayQuestion.difficultyLevel)")
                        .fixedSize(horizontal: true, vertical: false)

                    if let answeredAt = displayQuestion.answeredAt {
                        Text(answeredAt, style: .date)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if displayQuestion.author == nil {
                Spacer(minLength: 0)
            }
        }
    }

    private var communityActions: some View {
        HStack(spacing: 14) {
            Button {
                toggleLike()
            } label: {
                Label("\(displayQuestion.likeCount)", systemImage: displayQuestion.isLikedByMe ? "heart.fill" : "heart")
                    .font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.plain)
            .disabled(!canWriteCommunityReaction)
            .foregroundStyle(displayQuestion.isLikedByMe ? .red : .primary)
            .opacity(canWriteCommunityReaction ? 1 : 0.45)
            .transaction { transaction in
                transaction.animation = nil
            }

            Label("\(commentsTotalCount)", systemImage: "bubble.right")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)

            Label("\(displayQuestion.viewCount)", systemImage: "eye")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)

            Spacer()
        }
    }

    @ViewBuilder
    private var localizationControl: some View {
        if originalAvailable || isShowingOriginal {
            HStack(spacing: 6) {
                if !isShowingOriginal {
                    Text(strings.translatedIntoLanguage)
                        .foregroundStyle(.secondary)
                    Text("·")
                        .foregroundStyle(.tertiary)
                }
                Button(isShowingOriginal ? strings.showTranslation : strings.showOriginal) {
                    Task { await switchContentView() }
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
            }
            .font(.caption.weight(.medium))
            .accessibilityElement(children: .combine)
        }
    }

    private var commentsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(strings.comments)
                .font(.headline)

            if hasLoadedComments && comments.isEmpty {
                Text(strings.noComments)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            } else {
                ForEach(comments) { comment in
                    CommunityCommentRow(
                        comment: comment,
                        canDelete: canDeleteComment(comment),
                        isDeleting: deletingCommentIDs.contains(comment.id),
                        deleteTitle: strings.clear
                    ) {
                        deleteComment(comment)
                    }
                }
            }

            HStack(alignment: .bottom, spacing: 8) {
                TextField(canWriteCommunityReaction ? strings.writeComment : strings.signInToComment, text: $commentDraft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...4)
                    .padding(.vertical, 8)
                    .disabled(!canWriteCommunityReaction)
                    .focused($isCommentInputFocused)

                Button {
                    sendComment()
                } label: {
                    ZStack {
                        if isSendingComment {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Image(systemName: "arrow.up.circle.fill")
                                .font(.system(size: 30, weight: .semibold))
                                .symbolRenderingMode(.hierarchical)
                        }
                    }
                    .frame(width: 40, height: 40)
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(canSendComment ? Color.accentColor : Color.secondary.opacity(0.45))
                .disabled(!canSendComment)
                .opacity(canWriteCommunityReaction ? 1 : 0.45)
            }
            .padding(.leading, 12)
            .padding(.trailing, 8)
            .padding(.vertical, 6)
            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 19, style: .continuous))
            .padding(.bottom, 12)
        }
    }

    private var canSendComment: Bool {
        canWriteCommunityReaction && !commentDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSendingComment
    }

    private func canDeleteComment(_ comment: CommunityQuestionComment) -> Bool {
        guard canWriteCommunityReaction,
              appState.isCurrentCommunityUser(id: comment.author.id) else {
            return false
        }

        return true
    }

    private func toggleLike() {
        guard canWriteCommunityReaction else {
            return
        }

        let next = !displayQuestion.isLikedByMe
        let previous = displayQuestion
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            displayQuestion.isLikedByMe = next
            displayQuestion.likeCount = max(0, displayQuestion.likeCount + (next ? 1 : -1))
        }

        Task {
            if let state = await appState.setCommunityQuestionLike(displayQuestion, isLiked: next) {
                withTransaction(transaction) {
                    displayQuestion.isLikedByMe = state.isLikedByMe
                    displayQuestion.likeCount = state.likeCount
                }
            } else {
                withTransaction(transaction) {
                    displayQuestion = previous
                }
            }
        }
    }

    private func loadComments() async {
        guard let response = await appState.loadCommunityQuestionComments(
            questionID: displayQuestion.id,
            refresh: true
        ) else {
            return
        }

        applyComments(response)
        guard response.comments.contains(where: { $0.localization?.isPending == true }) else {
            return
        }
        for delay in [1, 2, 4] {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled,
                  let retried = await appState.loadCommunityQuestionComments(
                    questionID: displayQuestion.id,
                    refresh: true
                  ) else {
                return
            }
            applyComments(retried)
            if !retried.comments.contains(where: { $0.localization?.isPending == true }) {
                return
            }
        }
    }

    private func loadCommentsIfAvailable() async {
        guard contentSource.showsCommunityInteractions else {
            hasLoadedComments = true
            return
        }
        await loadComments()
    }

    private func applyCachedComments() {
        guard let response = appState.cachedCommunityQuestionComments(questionID: displayQuestion.id) else {
            return
        }
        applyComments(response)
    }

    private func applyComments(_ response: CommunityCommentsResponse) {
        comments = response.comments
        commentsTotalCount = response.totalCount
        displayQuestion.commentCount = response.totalCount
        hasLoadedComments = true
    }

    private func loadQuestionDetail() async {
        guard let question = await loadQuestion(view: .localized) else {
            return
        }
        displayQuestion = question
        originalAvailable = originalAvailable ||
            question.localization?.containsTranslation == true ||
            question.localization?.question.originalAvailable == true
        guard question.localization?.containsPendingTranslation == true else {
            return
        }
        for delay in [1, 2, 4] {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled,
                  let retried = await loadQuestion(view: .localized) else {
                return
            }
            displayQuestion = retried
            originalAvailable = originalAvailable || retried.localization?.containsTranslation == true
            if retried.localization?.containsPendingTranslation != true {
                return
            }
        }
    }

    private func switchContentView() async {
        let target: LocalizedContentView = isShowingOriginal ? .localized : .original
        guard let loaded = await loadQuestion(view: target) else {
            return
        }
        displayQuestion = loaded
        if contentSource.showsCommunityInteractions {
            if let response = await appState.loadCommunityQuestionComments(
                questionID: loaded.id,
                refresh: true,
                view: target
            ) {
                applyComments(response)
            }
        }
        isShowingOriginal = target == .original
        originalAvailable = true
    }

    private func loadQuestion(view: LocalizedContentView) async -> CommunityQuestion? {
        switch contentSource {
        case .community:
            return await appState.loadCommunityQuestionDetail(
                questionID: displayQuestion.id,
                view: view
            )
        case .record:
            guard let record = await appState.loadStudyRecordDetail(
                recordID: displayQuestion.id,
                view: view
            ) else {
                return nil
            }
            return record.asQuestionBrowseQuestion(author: displayQuestion.author)
        }
    }

    private func sendComment() {
        guard canWriteCommunityReaction else {
            return
        }

        let body = commentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, !isSendingComment else {
            return
        }

        isSendingComment = true
        Task {
            defer {
                Task { @MainActor in
                    isSendingComment = false
                }
            }
            guard let comment = await appState.createCommunityQuestionComment(questionID: displayQuestion.id, body: body) else {
                return
            }
            await MainActor.run {
                comments.append(comment)
                commentsTotalCount += 1
                displayQuestion.commentCount = commentsTotalCount
                commentDraft = ""
                isCommentInputFocused = false
            }
        }
    }

    private func deleteComment(_ comment: CommunityQuestionComment) {
        guard canDeleteComment(comment),
              !deletingCommentIDs.contains(comment.id) else {
            return
        }

        deletingCommentIDs.insert(comment.id)
        Task {
            let didDelete = await appState.deleteCommunityQuestionComment(questionID: displayQuestion.id, commentID: comment.id)
            await MainActor.run {
                deletingCommentIDs.remove(comment.id)
                guard didDelete else {
                    return
                }

                comments.removeAll { $0.id == comment.id }
                commentsTotalCount = max(0, commentsTotalCount - 1)
                displayQuestion.commentCount = commentsTotalCount
            }
        }
    }
}

private struct CommunityCommentRow: View {
    var comment: CommunityQuestionComment
    var canDelete: Bool
    var isDeleting: Bool
    var deleteTitle: String
    var onDelete: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            HomeProfileAvatar(
                symbolName: comment.author.avatarSymbolName,
                displayName: comment.author.displayName,
                colorSeed: comment.author.avatarColorSeed,
                size: 30
            )

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 5) {
                    Text(comment.author.displayName)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.tail)

                    Text(StudyDateDisplayFormatter.relativeOrShortDateString(for: comment.createdAt))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .fixedSize(horizontal: true, vertical: false)
                }
                .foregroundStyle(.secondary)

                Text(comment.body)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 2)
            .frame(maxWidth: .infinity, alignment: .leading)

            if canDelete {
                Menu {
                    Button(role: .destructive) {
                        onDelete()
                    } label: {
                        Label(deleteTitle, systemImage: "trash")
                    }
                    .disabled(isDeleting)
                } label: {
                    if isDeleting {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "ellipsis")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct CommunityAnswerMessage: View {
    var answer: String

    var body: some View {
        HStack(alignment: .bottom) {
            Spacer(minLength: 24)

            MarkdownMessageText(markdown: answer, fillsWidth: false)
                .font(.body)
                .foregroundStyle(.white)
                .tint(.white)
                .textSelection(.enabled)
                .multilineTextAlignment(.leading)
                .padding(.vertical, 11)
                .padding(.horizontal, 12)
                .frame(maxWidth: 260, alignment: .leading)
                .background(CommunityMessageBubbleRole.answer.foregroundBackground)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

private enum CommunityMessageBubbleRole: Equatable {
    case question
    case answer
    case feedback

    var alignment: Alignment {
        switch self {
        case .question, .feedback:
            .leading
        case .answer:
            .trailing
        }
    }

    var foregroundBackground: Color {
        switch self {
        case .question:
            Color.secondary.opacity(0.08)
        case .answer:
            Color.green.opacity(0.92)
        case .feedback:
            Color.secondary.opacity(0.08)
        }
    }

    var borderColor: Color {
        switch self {
        case .answer:
            Color.clear
        case .question, .feedback:
            Color.secondary.opacity(0.12)
        }
    }
}

private struct CommunityMessageBubble<Content: View>: View {
    var role: CommunityMessageBubbleRole
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if role == .answer {
                Spacer(minLength: 42)
            }

            content()
                .padding(.vertical, 11)
                .padding(.horizontal, 12)
                .frame(maxWidth: role == .answer ? 280 : .infinity, alignment: .leading)
                .background(role.foregroundBackground)
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(role.borderColor, lineWidth: 1)
                }
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            if role != .answer {
                Spacer(minLength: 42)
            }
        }
        .frame(maxWidth: .infinity, alignment: role.alignment)
    }
}

private extension View {
    @ViewBuilder
    func mobileTabTitle(_ title: String) -> some View {
        #if os(iOS)
        self
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
        #else
        self.navigationTitle(title)
        #endif
    }
}

private struct MobileOnboardingView: View {
    @EnvironmentObject private var appState: AppState
    @State private var language: AppLanguage = .korean
    @State private var topic = ""
    @State private var difficultyLevel = Difficulty.beginner.level
    @State private var intervalMinutes = 15
    @State private var isCompleting = false

    private var strings: AppStrings {
        AppStrings(language: language)
    }

    private var canStart: Bool {
        !isCompleting
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(strings.onboardingSubtitle)
                }

                Section(strings.onboardingLanguage) {
                    Picker(strings.appLanguage, selection: $language) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.displayName).tag(language)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(strings.onboardingStudySetup) {
                    TextField(strings.studyTopic, text: $topic)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(strings.difficulty)
                            Spacer()
                            Text(Difficulty(level: difficultyLevel).displayName(language: language))
                                .fontWeight(.semibold)
                                .monospacedDigit()
                        }

                        Slider(
                            value: Binding(
                                get: { Double(difficultyLevel) },
                                set: { difficultyLevel = min(max(Int($0.rounded()), 1), 10) }
                            ),
                            in: 1...10,
                            step: 1
                        )

                        HStack {
                            Text("1")
                            Spacer()
                            Text("10")
                        }
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    }

                    Stepper(
                        strings.questionInterval(minutes: intervalMinutes),
                        value: $intervalMinutes,
                        in: 1...240
                    )
                }
            }
            .keyboardDoneToolbar(strings.done)
            .navigationTitle(strings.onboardingTitle)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(strings.onboardingSkip) {
                        appState.skipOnboarding()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task {
                            isCompleting = true
                            await appState.completeOnboarding(settings: pendingSettings)
                            isCompleting = false
                        }
                    } label: {
                        if isCompleting || appState.isValidatingAPIKey {
                            ProgressView()
                        } else {
                            Text(strings.onboardingStart)
                        }
                    }
                    .disabled(!canStart)
                }
            }
                .onAppear {
                language = appState.settings.appLanguage
                let fallbackTopic = StudySettings.fallbackTopic(for: language)
                topic = appState.settings.topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? fallbackTopic
                    : appState.settings.topic
                difficultyLevel = appState.settings.difficulty.level
                intervalMinutes = appState.settings.sanitizedIntervalMinutes
            }
        }
    }

    private var pendingSettings: StudySettings {
        let resolvedTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty ? StudySettings.fallbackTopic(for: language) : topic.trimmingCharacters(in: .whitespacesAndNewlines)

        return StudySettings(
            topic: resolvedTopic,
            difficulty: Difficulty(level: difficultyLevel),
            appLanguage: language,
            language: language.studyLanguage,
            openAIModel: appState.settings.sanitizedOpenAIModel,
            notificationSound: appState.settings.notificationSound,
            customPrompt: appState.settings.customPrompt,
            intervalMinutes: intervalMinutes,
            maxHistoryCount: appState.settings.sanitizedMaxHistoryCount,
            studyCategories: [
                StudyCategory(
                    title: resolvedTopic,
                    difficulty: Difficulty(level: difficultyLevel),
                    customPrompt: appState.settings.customPrompt
                )
            ],
            selectedStudyCategoryID: nil
        )
    }
}

private struct MobileSettingsView: View {
    @EnvironmentObject private var appState: AppState

    private static let feedbackURL = URL(string: "mailto:ghkdqhrbals@gmail.com?subject=BuddyStudy%20Feedback")!
    private static let kofiTipURL = URL(string: "https://ko-fi.com/gyumin")!

    var body: some View {
        let strings = appState.settingsEditorStrings

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                if appState.isCommunitySessionActive {
                    MobileSettingsCard(
                        title: strings.accountSettings,
                        systemImage: "person.crop.circle.badge.gearshape"
                    ) {
                        NavigationLink {
                            MobileAccountSettingsView()
                        } label: {
                            MobileSettingsRow(
                                systemImage: "person.crop.circle.badge.gearshape",
                                title: strings.accountSettings,
                                value: strings.accountSettingsHelp,
                                showsChevron: true
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                if appState.isCommunitySessionActive {
                    MobileSettingsCard(
                        title: strings.publicQuestionsPage,
                        systemImage: "person.2"
                    ) {
                        VStack(alignment: .leading, spacing: 8) {
                            Toggle(
                                isOn: Binding(
                                    get: {
                                        appState.communityProfile?.allowPublicQuestions ?? true
                                    },
                                    set: { allowed in
                                        Task {
                                            await appState.setPublicQuestionsAllowed(allowed)
                                        }
                                    }
                                )
                            ) {
                                Text(strings.publicQuestionsPage)
                                    .font(.body.weight(.medium))
                            }

                            Text(strings.publicQuestionsPageHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .disabled(
                            appState.communityProfile == nil ||
                                appState.isUpdatingCommunityProfile
                        )
                    }
                }

                MobileSettingsCard(
                    title: strings.learningRhythmSettings,
                    systemImage: "timer"
                ) {
                    Stepper(
                        value: $appState.draftSettings.intervalMinutes,
                        in: 1...240
                    ) {
                        MobileSettingsRow(
                            systemImage: "clock.arrow.2.circlepath",
                            title: strings.studySettings,
                            value: strings.questionInterval(minutes: appState.draftSettings.sanitizedIntervalMinutes)
                        )
                    }
                }

                MobileSettingsCard(
                    title: strings.appEnvironmentSettings,
                    systemImage: "slider.horizontal.3"
                ) {
                    Menu {
                        ForEach(AppLanguage.allCases) { language in
                            Button {
                                appState.updateDraftAppLanguage(language)
                            } label: {
                                if appState.draftSettings.appLanguage == language {
                                    Label(language.displayName, systemImage: "checkmark")
                                } else {
                                    Text(language.displayName)
                                }
                            }
                        }
                    } label: {
                        MobileSettingsRow(
                            systemImage: "globe",
                            title: strings.appLanguage,
                            value: appState.draftSettings.appLanguage.displayName
                        )
                    }
                    .buttonStyle(.plain)

                    Divider()

                    Button {
                        appState.openSystemNotificationSettings()
                    } label: {
                        MobileSettingsRow(
                            systemImage: "bell.badge",
                            title: strings.notifications,
                            value: strings.openNotificationSettings,
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)

                    Divider()

                    Menu {
                        ForEach(NotificationSoundOption.allCases) { sound in
                            Button {
                                appState.setDraftNotificationSound(sound)
                            } label: {
                                if appState.draftSettings.notificationSound == sound {
                                    Label(
                                        sound.displayName(language: appState.draftSettings.appLanguage),
                                        systemImage: "checkmark"
                                    )
                                } else {
                                    Text(sound.displayName(language: appState.draftSettings.appLanguage))
                                }
                            }
                        }
                    } label: {
                        MobileSettingsRow(
                            systemImage: "speaker.wave.2.fill",
                            title: strings.notificationSound,
                            value: appState.draftSettings.notificationSound.displayName(language: appState.draftSettings.appLanguage)
                        )
                    }
                    .buttonStyle(.plain)
                }

                MobileSettingsCard(
                    title: strings.developerOptions,
                    systemImage: "hammer"
                ) {
                    Toggle(isOn: Binding(
                            get: { appState.isDebuggingEnabled },
                            set: { appState.setDebuggingEnabled($0) }
                        )) {
                        MobileSettingsRow(
                            systemImage: "ladybug.fill",
                            title: strings.debuggingMode,
                            value: appState.isDebuggingEnabled ? strings.enabledStatus : strings.disabledStatus
                        )
                    }
                    .tint(.green)

                    if appState.isDebuggingEnabled {
                        Divider()

                        VStack(alignment: .leading, spacing: 6) {
                            TextField(
                                strings.debugBackendBaseURL,
                                text: $appState.draftDebugBackendBaseURL,
                                prompt: Text(strings.debugBackendBaseURLPlaceholder)
                            )
                            #if os(iOS)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            #endif

                            if !appState.isDraftDebugBackendBaseURLValid {
                                Text(strings.debugBackendBaseURLInvalid)
                                    .font(.caption)
                                    .foregroundStyle(.red)
                            }

                            Text(strings.debugBackendBaseURLHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                HStack(spacing: 14) {
                    Link(strings.feedbackLink, destination: Self.feedbackURL)

                    Text("·")
                        .foregroundStyle(.tertiary)

                    Link(strings.tipMe, destination: Self.kofiTipURL)
                    .accessibilityLabel(strings.supportDeveloper)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 80)
        }
        .background(Color(.systemBackground))
        .keyboardDoneToolbar(strings.done)
        .navigationTitle(strings.tabSettings)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.large)
        #endif
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .topBarTrailing) {
                if appState.isLoadingBackendSettingsForEditing {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    settingsSaveToolbarButton(strings: strings)
                }
            }
            #else
            ToolbarItem(placement: .confirmationAction) {
                settingsSaveToolbarButton(strings: strings)
            }
            #endif
        }
        .contentShape(Rectangle())
        .disabled(appState.isLoadingBackendSettingsForEditing)
        .onAppear {
            appState.beginSettingsEditing()
            Task {
                await appState.loadBackendSettingsForEditing()
            }
        }
        .onDisappear {
            appState.cancelSettingsEditing()
        }
    }

    private func settingsSaveToolbarButton(strings: AppStrings) -> some View {
        Button {
            Task {
                await appState.saveSettingsAndValidateAPIKey()
            }
        } label: {
            MobileSettingsSaveButtonLabel(
                title: appState.isValidatingAPIKey
                    ? strings.checking
                    : (appState.hasUnsavedSettingsChanges ? strings.save : strings.saved),
                isDirty: appState.hasUnsavedSettingsChanges,
                isLoading: appState.isValidatingAPIKey
            )
        }
        .buttonStyle(.plain)
        .disabled(appState.isValidatingAPIKey)
    }
}

private struct MobileAccountSettingsView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var isShowingWithdrawalConfirmation = false

    private var strings: AppStrings { appState.strings }

    private var accountLabel: String {
        guard let profile = appState.communityProfile else {
            return strings.profileRequestFailed
        }
        let email = profile.email.trimmingCharacters(in: .whitespacesAndNewlines)
        return email.isEmpty ? profile.displayName : email
    }

    var body: some View {
        List {
            Section(strings.profileAccount) {
                Text(accountLabel)
            }

            Section {
                Button(role: .destructive) {
                    isShowingWithdrawalConfirmation = true
                } label: {
                    HStack {
                        Text(strings.deleteAccount)
                        Spacer()
                        if appState.isWithdrawingCommunityAccount {
                            ProgressView()
                                .controlSize(.small)
                        }
                    }
                }
                .disabled(appState.isWithdrawingCommunityAccount)
            } footer: {
                Text(strings.deleteAccountNotice)
            }
        }
        .navigationTitle(strings.accountSettings)
        .navigationBarTitleDisplayMode(.inline)
        .alert(strings.deleteAccount, isPresented: $isShowingWithdrawalConfirmation) {
            Button(strings.cancel, role: .cancel) {}
            Button(strings.deleteAccount, role: .destructive) {
                Task {
                    if await appState.withdrawCommunityAccount() {
                        dismiss()
                    }
                }
            }
        } message: {
            Text(strings.deleteAccountConfirmMessage)
        }
    }
}

private struct MobileSettingsCard<Content: View>: View {
    var title: String
    @ViewBuilder var content: Content

    init(
        title: String,
        systemImage _: String,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.secondary)

            VStack(spacing: 12) {
                content
            }
        }
        .padding(18)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

private struct MobileSettingsRow: View {
    var title: String
    var value: String
    var showsChevron = false

    init(
        systemImage _: String,
        title: String,
        value: String,
        showsChevron: Bool = false
    ) {
        self.title = title
        self.value = value
        self.showsChevron = showsChevron
    }

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(value)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
    }
}

private struct MobileLegalWebRoute: Identifiable {
    let id = UUID()
    let url: URL
}

#if os(iOS)
private struct MobileLegalWebView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
#endif

private struct MobileSettingsSaveButtonLabel: View {
    var title: String
    var isDirty: Bool
    var isLoading: Bool

    var body: some View {
        HStack(spacing: 6) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
                    .tint(foregroundColor)
            }

            Text(title)
                .font(.subheadline.weight(isDirty ? .semibold : .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .foregroundStyle(foregroundColor)
        .frame(minWidth: 54, minHeight: 34)
        .contentShape(Rectangle())
    }

    private var foregroundColor: Color {
        if isDirty {
            return Color.accentColor
        }

        return .secondary
    }
}

private struct MobileAPIDebugSheet: View {
    let logs: [APITrafficLogEntry]
    @Binding var isPresented: Bool
    @EnvironmentObject private var appState: AppState

    var body: some View {
        let strings = appState.settingsEditorStrings

        NavigationStack {
            Group {
                if logs.isEmpty {
                    ContentUnavailableView(
                        strings.noLogs,
                        systemImage: "network.slash",
                        description: Text(strings.noLogsDescription)
                    )
                } else {
                    List(logs) { log in
                        APITrafficLogItemView(entry: log)
                            .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(strings.apiDebugWindowTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(strings.done) {
                        isPresented = false
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct MovableAPIDebugPanel: View {
    let logs: [APITrafficLogEntry]
    @Binding var isPresented: Bool
    @EnvironmentObject private var appState: AppState
    @State private var dragOffset: CGSize = .zero
    @State private var committedOffset: CGSize = .zero

    var body: some View {
        let strings = appState.settingsEditorStrings
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Text(strings.apiDebugWindowTitle)
                    .font(.headline)
                Spacer()
                Button {
                    isPresented = false
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground))

            Divider()

            Group {
                if logs.isEmpty {
                    ContentUnavailableView(strings.noLogs, systemImage: "wifi.slash", description: Text(strings.noLogsDescription))
                        .frame(maxHeight: .infinity, alignment: .center)
                } else {
                    List(logs) { log in
                        APITrafficLogItemView(entry: log)
                            .listRowInsets(EdgeInsets(top: 4, leading: 10, bottom: 4, trailing: 10))
                    }
                    .listStyle(.plain)
                }
            }
        }
        .frame(maxWidth: 680, maxHeight: 440)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.22), radius: 18, y: 10)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.secondary.opacity(0.25))
        )
        .offset(x: dragOffset.width + committedOffset.width, y: dragOffset.height + committedOffset.height)
        .gesture(
            DragGesture()
                .onChanged { value in
                    dragOffset = value.translation
                }
                .onEnded { value in
                    committedOffset.width += value.translation.width
                    committedOffset.height += value.translation.height
                    dragOffset = .zero
                }
        )
    }
}

private struct APITrafficLogItemView: View {
    let entry: APITrafficLogEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(entry.createdAt.formatted(date: .omitted, time: .standard))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()
                Text(entry.compactSummary)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(entry.isError ? .red : .secondary)
                    .lineLimit(1)
            }
            if !entry.requestHeaders.isEmpty {
                Text("Headers: \(entry.requestHeaders)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if !entry.requestBody.isEmpty {
                Text("Request: \(entry.requestBody)")
                    .font(.caption2)
                    .foregroundStyle(.primary)
                    .lineLimit(4)
            }
            if !entry.responseBody.isEmpty {
                Text("Response: \(entry.responseBody)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
            }
            if let error = entry.error {
                Text("Error: \(error)")
                    .font(.caption2)
                    .foregroundStyle(.red)
            }
        }
        .padding(.vertical, 6)
    }
}
