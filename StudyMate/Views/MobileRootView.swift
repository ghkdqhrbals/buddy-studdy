import SwiftUI
#if os(iOS)
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
                                StudyView(preferredCategoryID: route.categoryID)
                                    .padding(.horizontal, 16)
                                    .mobileTabTitle(studyScreenTitle(for: route))
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
                                    prompt: appState.pageAccessPrompt,
                                    onLogin: { isRecordsLoginPagePresented = true }
                                )
                                .padding(.horizontal, 16)
                            } else {
                                HistoryView()
                                    .padding(.horizontal, 16)
                            }
                        }
                        .navigationDestination(isPresented: $isRecordsLoginPagePresented) {
                            MobileLoginPage(prompt: appState.pageAccessPrompt)
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
                                    prompt: appState.pageAccessPrompt,
                                    onLogin: { isStatisticsLoginPagePresented = true }
                                )
                                .padding(.horizontal, 16)
                            } else {
                                StatisticsView()
                                    .padding(.horizontal, 16)
                            }
                        }
                        .navigationDestination(isPresented: $isStatisticsLoginPagePresented) {
                            MobileLoginPage(prompt: appState.pageAccessPrompt)
                                .padding(.horizontal, 16)
                        }
                    }
                    .tabItem {
                        Label(strings.tabStatistics, systemImage: "chart.xyaxis.line")
                    }
                    .tag(AppTab.statistics)

                    NavigationStack {
                        MobileSettingsView()
                    }
                    .tabItem {
                        Label(strings.tabSettings, systemImage: "gearshape.fill")
                    }
                    .tag(AppTab.settings)
                }
                .background(Color(.systemBackground))
                .onAppear {
                    appState.normalizeSelectedTabForMobile()
                }
            }
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

private struct MobileProtectedLoginGate: View {
    @EnvironmentObject private var appState: AppState
    var prompt: PageAccessPrompt?
    var onLogin: () -> Void

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            MobileLoginLogo(size: 58)
                .padding(.bottom, 4)

            Text(prompt?.title ?? strings.pageAccessRequiresLogin)
                .font(.title3.weight(.semibold))

            Text(strings.protectedPageLoginHelp)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Button(action: onLogin) {
                SignInButtonLabel(title: strings.communityLogin, isPrimary: true)
            }
            .buttonStyle(.plain)

            Spacer(minLength: 0)
        }
        .padding(.top, 24)
    }
}

private struct MobileLoginPage: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var isShowingEmailSignIn = false
    var prompt: PageAccessPrompt?

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 18)

            MobileLoginLogo(size: 72)

            VStack(spacing: 8) {
                Text(strings.communityLogin)
                    .font(.title2.weight(.bold))

                Text(prompt?.title ?? strings.loginPageHelp)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

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

            Spacer(minLength: 0)

            loginAgreement
        }
        .padding(20)
        .navigationTitle(strings.communityLogin)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: appState.isCommunitySignedIn) { _, isSignedIn in
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
        VStack(spacing: 4) {
            Text(strings.loginAgreementPrefix)
                .foregroundStyle(.secondary)

            HStack(spacing: 4) {
                Link(strings.termsOfService, destination: AppLegalLinks.termsOfServiceURL)
                    .tint(.accentColor)
                Text(strings.loginAgreementConjunction)
                    .foregroundStyle(.secondary)
                Link(strings.privacyPolicy, destination: AppLegalLinks.privacyPolicyURL)
                    .tint(.accentColor)
            }

            Text(strings.loginAgreementSuffix)
                .foregroundStyle(.secondary)
        }
        .font(.footnote)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }
}

private struct MobileLoginLogo: View {
    var size: CGFloat

    var body: some View {
        Image("LaunchLogo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous))
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

private struct MobileHomeView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedHomeScope: HomeFeedScope = .all
    @State private var editMode: EditMode = .inactive
    @State private var hasLoadedCommunityQuestions = false
    @State private var editingStudyCategory: StudyCategory?
    @State private var isAddingStudyCategory = false
    @State private var selectedCommunityQuestionRoute: CommunityQuestionRoute?
    @State private var notificationForwardRoute: NotificationForwardRoute?
    @State private var isShowingNotifications = false
    @State private var isShowingProfileSettings = false
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
        if !query.isEmpty,
           query == submittedHomeStudySearchText,
           let searchResults = appState.homeStudySearchResults {
            return searchResults
        }

        let categories = appState.studyCategoriesForDisplay
        guard !query.isEmpty else {
            return categories
        }

        return categories.filter { category in
            category.matchesHomeSearch(query, appLanguage: strings.language)
        }
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
            MobileNotificationsView(forwardedRoute: $notificationForwardRoute)
                .padding(.horizontal, 16)
                .mobileTabTitle(strings.notificationInbox)
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

                if !isHomeSearchActive {
                    ToolbarItem(placement: .topBarTrailing) {
                        notificationToolbarButton(strings: strings)
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

                if !isHomeSearchActive {
                    ToolbarItem(placement: .topBarTrailing) {
                        notificationToolbarButton(strings: strings)
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
            if newScope == .all {
                editMode = .inactive
                Task {
                    await loadCommunityQuestionsIfNeeded(userInitiated: false)
                }
            }
        }
        .onChange(of: appState.isCommunitySignedIn) { _, isSignedIn in
            hasLoadedCommunityQuestions = false
            guard isSignedIn, selectedHomeScope == .all else {
                return
            }

            Task {
                await loadCommunityQuestionsIfNeeded(userInitiated: false)
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
        .navigationDestination(item: $selectedCommunityQuestionRoute) { route in
            if let question = appState.communityQuestions.first(where: { $0.id == route.id }) {
                CommunityQuestionDetailView(question: question)
            } else {
                ContentUnavailableView(strings.communityQuestion, systemImage: "bubble.left.and.bubble.right")
            }
        }
    }

    private func handleAppRouteRequest(_ request: AppRouteRequest?) {
        guard let request else {
            return
        }

        if request.presentation == .notificationInbox {
            notificationForwardRoute = NotificationForwardRoute(route: request.route)
            isShowingNotifications = true
            appState.appRouteRequest = nil
            return
        }

        switch request.route {
        case .profile:
            isShowingProfileSettings = true
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
        if selectedHomeScope == .my, !appState.isCommunitySignedIn {
            myStudyLoginSection
        } else if selectedHomeScope == .my {
            myStudySection
        } else {
            communityQuestionSection
        }
    }

    private var myStudyLoginSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text(strings.myStudyLoginHelp)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                NavigationLink {
                    MobileLoginPage(
                        prompt: PageAccessPrompt(
                            title: strings.pageAccessRequiresLogin,
                            message: ""
                        )
                    )
                } label: {
                    SignInButtonLabel(title: strings.communityLogin, isPrimary: true)
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 4)
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
        } else {
            Button {
                appState.openStudyCategory(category.id)
            } label: {
                MobileHomeCategoryRow(
                    category: category,
                    hasPendingQuestion: appState.pendingQuestionCount(for: category) > 0,
                    strings: strings
                )
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button {
                    editingStudyCategory = category
                } label: {
                    Label(strings.edit, systemImage: "pencil")
                }
            }
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

                ForEach(appState.communityQuestions) { question in
                    communityQuestionRow(question)
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

    private func communityQuestionRow(_ question: CommunityQuestion) -> some View {
        Button {
            selectedCommunityQuestionRoute = CommunityQuestionRoute(id: question.id)
        } label: {
            MobileCommunityQuestionRow(question: question)
        }
        .buttonStyle(.plain)
        .contextMenu {
            if appState.isCommunitySignedIn {
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
        !isHomeSearchActive && selectedHomeScope == .my && appState.isCommunitySignedIn
    }

    private var profileToolbarControl: some View {
        let strings = appState.strings

        return HomeProfileAvatar(
            symbolName: appState.profileAvatarSymbolName,
            displayName: appState.communityProfile?.displayName,
            imageData: nil,
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
                imageData: nil,
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
        guard appState.isCommunitySignedIn,
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
            if selectedHomeScope == .my, appState.isCommunitySignedIn {
                Button {
                    isAddingStudyCategory = true
                } label: {
                    MobileToolbarIconButtonLabel(systemName: "plus")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(strings.newStudyCategory)
            }

            notificationToolbarButton(strings: strings)

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

    private func notificationToolbarButton(strings: AppStrings) -> some View {
        Button {
            isShowingNotifications = true
        } label: {
            ZStack(alignment: .topTrailing) {
                MobileToolbarIconButtonLabel(systemName: "bell.fill")

                if appState.notificationUnreadCount > 0 {
                    Text(appState.notificationUnreadCount > 99 ? "99+" : "\(appState.notificationUnreadCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 18, minHeight: 18)
                        .background(Color.red, in: Capsule())
                        .offset(x: 7, y: -5)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.notificationInbox)
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
                    Text(strings.noNotifications)
                        .font(.title2.weight(.bold))
                    Text(strings.noNotificationsDescription)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, minHeight: 320, alignment: .center)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            } else {
                ForEach(appState.notifications) { notification in
                    Button {
                        Task {
                            await appState.markNotificationRead(notification)
                            if let route = appState.notificationLandingCoordinator.routeForNotificationListSelection(notification) {
                                forwardedRoute = NotificationForwardRoute(route: route)
                            }
                        }
                    } label: {
                        MobileNotificationRow(notification: notification, referenceDate: openedAt)
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
        if record.gradingResult == nil {
            StudyView(preferredCategoryID: appState.categoryIDForStudyTopic(record.topic))
                .padding(.horizontal, 16)
        } else if let question = record.asCommunityQuestion(author: appState.communityProfile) {
            CommunityQuestionDetailView(question: question)
        } else {
            StudyRecordDetailView(record: record)
                .padding(.horizontal, 16)
        }
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

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        Group {
            if let question = appState.communityQuestions.first(where: { $0.id == questionID }) {
                CommunityQuestionDetailView(question: question)
            } else {
                ContentUnavailableView(strings.communityQuestion, systemImage: "bubble.left.and.bubble.right")
            }
        }
        .navigationTitle(strings.browseQuestions)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if !appState.communityQuestions.contains(where: { $0.id == questionID }) {
                await appState.loadCommunityQuestions(reset: true, userInitiated: false)
            }
        }
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

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle()
                .fill(notification.isRead ? Color.secondary.opacity(0.18) : Color.accentColor)
                .frame(width: 10, height: 10)
                .padding(.top, 8)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(notification.title)
                        .font(.body.weight(notification.isRead ? .semibold : .bold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Spacer(minLength: 8)

                    Text(StudyDateDisplayFormatter.relativeOrShortDateString(for: notification.createdAt, relativeTo: referenceDate))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Text(notification.body)
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
    var imageData: Data? = nil
    var colorSeed: String? = "profile"
    var usesNeutralColor: Bool = false
    var size: CGFloat = 34

    var body: some View {
        Group {
            #if os(iOS)
            if let imageData,
               let uiImage = UIImage(data: imageData) {
                avatarPhoto(uiImage)
            } else {
                defaultGlyph
            }
            #else
            defaultGlyph
            #endif
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
    }

    private var defaultGlyph: some View {
        ProfileAvatarSprite(
            symbolName: symbolName,
            colorSeed: defaultColorSeed,
            usesNeutralColor: usesNeutralColor,
            size: size
        )
    }

    private var defaultColorSeed: String {
        let trimmedDisplayName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let trimmedColorSeed = colorSeed?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !trimmedColorSeed.isEmpty {
            return trimmedColorSeed
        }

        if !trimmedDisplayName.isEmpty {
            return trimmedDisplayName
        }

        return symbolName
    }

    #if os(iOS)
    private func avatarPhoto(_ uiImage: UIImage) -> some View {
        Image(uiImage: uiImage)
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(Circle())
    }
    #endif
}

struct ProfileAvatarSprite: View {
    var symbolName: String
    var colorSeed: String
    var usesNeutralColor: Bool
    var size: CGFloat

    var body: some View {
        let palette = PixelAvatarPalette(seed: colorSeed, usesNeutralColor: usesNeutralColor)

        Circle()
            .fill(palette.background)
            .overlay {
                Image(ProfileAvatarOption.assetName(for: symbolName))
                    .resizable()
                    .interpolation(.none)
                    .antialiased(false)
                    .scaledToFit()
                    .scaleEffect(1.12)
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
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
}

private struct MobileProfileSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var profileDisplayName = ""
    @State private var draftAvatarSymbolName = ProfileAvatarOption.defaultSymbolName
    @State private var draftAvatarColorSeed = ""
    @State private var allowPublicQuestionsAccess = true
    @State private var isShowingEmailSignIn = false
    @State private var isShowingCustomColorEditor = false
    @State private var isShowingAvatarCustomization = false
    @State private var isLoadingProfileDraft = false
    @State private var wasSignedInWhenOpened = false

    private var strings: AppStrings {
        appState.strings
    }

    private var trimmedProfileDisplayName: String {
        profileDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var hasProfileChanges: Bool {
        guard appState.isCommunitySignedIn else {
            return false
        }

        let profile = appState.communityProfile
        let currentDisplayName = profile?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let currentPublicQuestions = profile?.pageAccess.publicQuestions ?? true
        let currentAvatarSymbolName = ProfileAvatarOption.canonicalName(for: profile?.avatarSymbolName ?? appState.profileAvatarSymbolName)
        let currentAvatarColorSeed = profile?.avatarColorSeed ?? appState.profileAvatarColorSeed

        return trimmedProfileDisplayName != currentDisplayName
            || allowPublicQuestionsAccess != currentPublicQuestions
            || draftAvatarSymbolName != currentAvatarSymbolName
            || draftAvatarColorSeed != currentAvatarColorSeed
    }

    private var canSaveProfile: Bool {
        appState.isCommunitySignedIn
            && !appState.isUpdatingCommunityProfile
            && !trimmedProfileDisplayName.isEmpty
            && hasProfileChanges
    }

    private var profileAccountText: String {
        guard let profile = appState.communityProfile else {
            return ""
        }

        switch profile.provider.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "EMAIL":
            let email = profile.email.trimmingCharacters(in: .whitespacesAndNewlines)
            return email.isEmpty ? "Email" : email
        case "GOOGLE":
            return "Google"
        default:
            let provider = profile.provider.trimmingCharacters(in: .whitespacesAndNewlines)
            return provider.isEmpty ? profile.displayName : provider.capitalized
        }
    }

    var body: some View {
        let strings = appState.strings

        NavigationStack {
            Form {
                if appState.isCommunitySignedIn, appState.communityProfile == nil {
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
                                    imageData: nil,
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
                } else if appState.isCommunitySignedIn {
                    Section {
                        VStack(alignment: .center, spacing: 14) {
                            HomeProfileAvatar(
                                symbolName: draftAvatarSymbolName,
                                displayName: profileDisplayName,
                                imageData: nil,
                                colorSeed: draftAvatarColorSeed,
                                usesNeutralColor: appState.communityProfile == nil,
                                size: 94
                            )
                            .padding(.top, 4)

                            TextField(strings.profileDisplayName, text: $profileDisplayName)
                                .font(.title2.weight(.bold))
                                .multilineTextAlignment(.center)
                                .textInputAutocapitalization(.words)
                                .submitLabel(.done)
                                .padding(.vertical, 12)
                                .padding(.horizontal, 12)
                                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                            if !profileAccountText.isEmpty {
                                HStack(spacing: 8) {
                                    Image(systemName: "person.crop.circle.badge.checkmark")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(strings.profileAccount)
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        Text(profileAccountText)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                            .lineLimit(1)
                                    }
                                }
                                .padding(.vertical, 9)
                                .padding(.horizontal, 13)
                                .frame(maxWidth: .infinity, alignment: .center)
                                .background(Color.secondary.opacity(0.06), in: Capsule())
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .listRowBackground(Color.clear)

                    Section {
                        DisclosureGroup(isExpanded: $isShowingAvatarCustomization) {
                            VStack(alignment: .leading, spacing: 18) {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(strings.profileCharacter)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.secondary)

                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 60, maximum: 66), spacing: 10)], spacing: 10) {
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
                                        HStack(spacing: 12) {
                                            ForEach(ProfileAvatarColorOption.all) { option in
                                                Button {
                                                    draftAvatarColorSeed = option.id
                                                } label: {
                                                    colorChoice(
                                                        color: option.color,
                                                        isSelected: draftAvatarColorSeed == option.id
                                                    )
                                                }
                                                .buttonStyle(.plain)
                                            }

                                            Button {
                                                isShowingCustomColorEditor = true
                                            } label: {
                                                customColorChoice(isSelected: ProfileAvatarCustomColor(seed: draftAvatarColorSeed) != nil)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                        .padding(.vertical, 2)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                            .padding(.top, 12)
                        } label: {
                            HStack(spacing: 12) {
                                HomeProfileAvatar(
                                    symbolName: draftAvatarSymbolName,
                                    displayName: profileDisplayName,
                                    imageData: nil,
                                    colorSeed: draftAvatarColorSeed,
                                    usesNeutralColor: false,
                                    size: 40
                                )
                                Text(strings.profileAvatar)
                                    .font(.body.weight(.semibold))
                                Spacer(minLength: 0)
                            }
                        }
                    }

                    Section {
                        Toggle(strings.publicQuestionsPage, isOn: $allowPublicQuestionsAccess)
                    } footer: {
                        Text(strings.publicQuestionsPageHelp)
                    }

                    Section {
                        Button(role: .destructive) {
                            appState.signOutFromCommunity()
                            dismiss()
                        } label: {
                            Text(strings.communityLogout)
                        }
                    }
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
            .navigationTitle(strings.profile)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        guard appState.isCommunitySignedIn else {
                            dismiss()
                            return
                        }

                        Task {
                            await appState.updateCommunityProfile(
                                displayName: trimmedProfileDisplayName,
                                avatarSymbolName: draftAvatarSymbolName,
                                avatarColorSeed: draftAvatarColorSeed,
                                pageAccess: CommunityPageAccess(
                                    publicQuestions: allowPublicQuestionsAccess,
                                    statistics: true,
                                    studyDetail: true,
                                    records: true
                                )
                            )
                            dismiss()
                        }
                    } label: {
                        if appState.isUpdatingCommunityProfile {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text(profileConfirmationTitle(strings: strings))
                        }
                    }
                    .disabled(appState.isCommunitySignedIn ? !canSaveProfile : appState.isUpdatingCommunityProfile)
                }
            }
            .onAppear {
                wasSignedInWhenOpened = appState.isCommunitySignedIn
                resetDraftProfile()
                Task {
                    isLoadingProfileDraft = true
                    await appState.loadCommunityProfile()
                    resetDraftProfile()
                    isLoadingProfileDraft = false
                }
            }
            .onChange(of: appState.communityProfile) { _, profile in
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
                allowPublicQuestionsAccess = profile.pageAccess.publicQuestions
            }
            .onChange(of: appState.isCommunitySignedIn) { _, isSignedIn in
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
            .sheet(isPresented: $isShowingCustomColorEditor) {
                ProfileAvatarColorEditorSheet(
                    initialColor: ProfileAvatarCustomColor.from(seed: draftAvatarColorSeed)
                ) { color in
                    draftAvatarColorSeed = color.seed
                }
                .environmentObject(appState)
            }
        }
    }

    private func resetDraftProfile() {
        profileDisplayName = appState.communityProfile?.displayName ?? ""
        draftAvatarSymbolName = ProfileAvatarOption.canonicalName(for: appState.communityProfile?.avatarSymbolName ?? appState.profileAvatarSymbolName)
        draftAvatarColorSeed = appState.communityProfile?.avatarColorSeed ?? appState.profileAvatarColorSeed
        allowPublicQuestionsAccess = appState.communityProfile?.pageAccess.publicQuestions ?? true
    }

    private func profileConfirmationTitle(strings: AppStrings) -> String {
        guard appState.isCommunitySignedIn else {
            return strings.done
        }

        return strings.save
    }

    private func avatarChoice(symbolName: String, colorSeed: String, isSelected: Bool) -> some View {
        ZStack(alignment: .bottomTrailing) {
            HomeProfileAvatar(
                symbolName: symbolName,
                displayName: nil,
                imageData: nil,
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

    private func colorChoice(color: Color, isSelected: Bool) -> some View {
        ZStack {
            Circle()
                .fill(color)

            if isSelected {
                Image(systemName: "checkmark")
                    .font(.system(size: 16, weight: .black))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.28), radius: 2, x: 0, y: 1)
            }
        }
        .frame(width: 46, height: 46)
        .overlay {
            Circle()
                .stroke(isSelected ? Color.primary.opacity(0.78) : Color.secondary.opacity(0.16), lineWidth: isSelected ? 2 : 1)
        }
        .overlay {
            Circle()
                .stroke(Color.white.opacity(0.18), lineWidth: 1)
                .padding(4)
        }
        .padding(1)
    }

    private func customColorChoice(isSelected: Bool) -> some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .fill(
                    AngularGradient(
                        colors: [.red, .orange, .yellow, .green, .cyan, .blue, .purple, .red],
                        center: .center
                    )
                )
                .frame(width: 58, height: 46)

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 16, weight: .bold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.accentColor)
                    .offset(x: 3, y: 3)
            }
        }
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(isSelected ? Color.primary.opacity(0.72) : Color.secondary.opacity(0.16), lineWidth: isSelected ? 2 : 1)
        }
        .accessibilityLabel(strings.customProfileColor)
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
    static let defaultSymbolName = "pixel-fox-scholar"

    static let all = [
        defaultSymbolName,
        "pixel-cat-laptop",
        "pixel-cat-geek",
        "pixel-cat-student",
        "pixel-cat-wizard",
        "pixel-cat-gamer",
        "pixel-cat-robot",
        "pixel-fox-scholar",
        "pixel-fox-arctic-coder",
        "pixel-fox-detective",
        "pixel-fox-headset",
        "pixel-rabbit-pencil",
        "pixel-rabbit-hacker",
        "pixel-rabbit-wizard",
        "pixel-rabbit-gamer",
        "pixel-dog-dachshund-student",
        "pixel-dog-corgi-reader",
        "pixel-dog-shiba-geek",
        "pixel-dog-robot-puppy"
    ]

    static func assetName(for symbolName: String) -> String {
        switch canonicalName(for: symbolName) {
        case "pixel-fox-scholar":
            return "ProfileAvatarFoxScholar"
        case "pixel-fox-arctic-coder":
            return "ProfileAvatarFoxArcticCoder"
        case "pixel-fox-detective":
            return "ProfileAvatarFoxDetective"
        case "pixel-fox-headset":
            return "ProfileAvatarFoxHeadset"
        case "pixel-cat-laptop":
            return "ProfileAvatarCatLaptop"
        case "pixel-cat-geek":
            return "ProfileAvatarCatGeek"
        case "pixel-cat-student":
            return "ProfileAvatarCatStudent"
        case "pixel-cat-wizard":
            return "ProfileAvatarCatWizard"
        case "pixel-cat-gamer":
            return "ProfileAvatarCatGamer"
        case "pixel-cat-robot":
            return "ProfileAvatarCatRobot"
        case "pixel-rabbit-pencil":
            return "ProfileAvatarRabbitPencil"
        case "pixel-rabbit-hacker":
            return "ProfileAvatarRabbitHacker"
        case "pixel-rabbit-wizard":
            return "ProfileAvatarRabbitWizard"
        case "pixel-rabbit-gamer":
            return "ProfileAvatarRabbitGamer"
        case "pixel-dog-dachshund-student":
            return "ProfileAvatarDogDachshundStudent"
        case "pixel-dog-corgi-reader":
            return "ProfileAvatarDogCorgiReader"
        case "pixel-dog-shiba-geek":
            return "ProfileAvatarDogShibaGeek"
        case "pixel-dog-robot-puppy":
            return "ProfileAvatarDogRobotPuppy"
        default:
            return "ProfileAvatarCatLaptop"
        }
    }

    static func glyphName(for symbolName: String) -> String {
        canonicalName(for: symbolName)
    }

    static func canonicalName(for symbolName: String) -> String {
        switch symbolName {
        case "person.fill", "person.crop.circle.fill", "pencil.tip", "pixel-buddy":
            return defaultSymbolName
        case "pixel-fox", "pixel-fox-student", "pixel-book-pup", "pixel-dachshund", "pixel-pencil-pup", "pixel-sleepy-pup":
            return defaultSymbolName
        case "pixel-cat":
            return "pixel-cat-laptop"
        case "pixel-rabbit":
            return "pixel-rabbit-pencil"
        case "pixel-dog":
            return "pixel-dog-dachshund-student"
        default:
            return all.contains(symbolName) ? symbolName : defaultSymbolName
        }
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
        Text(title)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 11)
        .padding(.horizontal, 12)
        .background(isPrimary ? Color.accentColor.opacity(0.14) : Color.secondary.opacity(0.06))
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(isPrimary ? Color.accentColor.opacity(0.22) : Color.secondary.opacity(0.16), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .foregroundStyle(.primary)
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

private struct MobileHomeCategoryRow: View {
    var category: StudyCategory
    var hasPendingQuestion: Bool
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(category.title)
                    .lineLimit(1)
                    .fontWeight(.regular)

                Text(category.difficulty.displayName(language: strings.language))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
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
        .padding(.vertical, 7)
        .frame(minHeight: 46)
        .contentShape(Rectangle())
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
                        Label(strings.recommendedPrompt, systemImage: "sparkles")
                    }

                    TextEditor(text: $customPrompt)
                        .frame(minHeight: 130)
                }

                if let onDelete {
                    Section {
                        Button(role: .destructive) {
                            onDelete()
                            dismiss()
                        } label: {
                            Text(strings.clear)
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
        }
    }

    private var resolvedDifficultyLevel: Int {
        min(max(Int(difficultyLevel.rounded()), 1), 10)
    }
}

private struct MobileCommunityQuestionRow: View {
    var question: CommunityQuestion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CommunityQuestionTopMeta(question: question)

            Text(question.question)
                .font(.body.weight(.medium))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            CommunityQuestionStatsMeta(question: question)
        }
        .padding(.vertical, 8)
    }
}

struct CommunityQuestionDetailView: View {
    @EnvironmentObject private var appState: AppState
    var question: CommunityQuestion
    @State private var displayQuestion: CommunityQuestion
    @State private var comments: [CommunityQuestionComment] = []
    @State private var commentsTotalCount = 0
    @State private var isLoadingComments = false
    @State private var commentDraft = ""
    @State private var isSendingComment = false
    @State private var deletingCommentIDs: Set<String> = []
    @FocusState private var isCommentInputFocused: Bool

    init(question: CommunityQuestion) {
        self.question = question
        _displayQuestion = State(initialValue: question)
    }

    private var strings: AppStrings {
        appState.strings
    }

    private var canWriteCommunityReaction: Bool {
        appState.isCommunitySignedIn
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                communityQuestionMeta

                CommunityMessageBubble(role: .question) {
                    Text(displayQuestion.question)
                        .font(.body)
                        .foregroundStyle(.white)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let answer = displayQuestion.answer?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !answer.isEmpty {
                    CommunityAnswerMessage(answer: answer, author: displayQuestion.author)
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

                            Text(gradingResult.feedback)
                                .font(.body)

                            Text(gradingResult.explanation)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                communityActions

                Divider()

                commentsSection
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
            await loadQuestionDetail()
            await loadComments()
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

    private var commentsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(strings.comments)
                .font(.headline)

            if isLoadingComments && comments.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
            } else if comments.isEmpty {
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
        isLoadingComments = true
        defer { isLoadingComments = false }
        guard let response = await appState.loadCommunityQuestionComments(questionID: displayQuestion.id) else {
            return
        }

        comments = response.comments
        commentsTotalCount = response.totalCount
        displayQuestion.commentCount = response.totalCount
    }

    private func loadQuestionDetail() async {
        guard let question = await appState.loadCommunityQuestionDetail(questionID: displayQuestion.id) else {
            return
        }
        displayQuestion = question
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
    var author: CommunityUserProfile?

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Spacer(minLength: 24)

            Text(answer)
                .font(.body)
                .foregroundStyle(.white)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.leading)
                .padding(.vertical, 11)
                .padding(.horizontal, 12)
                .frame(maxWidth: 260, alignment: .leading)
                .background(CommunityMessageBubbleRole.answer.foregroundBackground)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            if let author {
                VStack(spacing: 3) {
                    HomeProfileAvatar(
                        symbolName: author.avatarSymbolName,
                        displayName: author.displayName,
                        colorSeed: author.avatarColorSeed,
                        size: 34
                    )

                    Text(author.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                .frame(width: 42)
                .accessibilityElement(children: .combine)
            }
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
            Color.green.opacity(0.92)
        case .answer:
            Color.accentColor
        case .feedback:
            Color.secondary.opacity(0.06)
        }
    }

    var borderColor: Color {
        switch self {
        case .question, .answer:
            Color.clear
        case .feedback:
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

        VStack(spacing: 0) {
            Form {
                Section(strings.studySettings) {
                    Stepper(
                        strings.questionInterval(minutes: appState.draftSettings.sanitizedIntervalMinutes),
                        value: $appState.draftSettings.intervalMinutes,
                        in: 1...240
                    )

                }

                Section(strings.generalSettings) {
                    Picker(
                        strings.appLanguage,
                        selection: Binding(
                            get: { appState.draftSettings.appLanguage },
                            set: { appState.updateDraftAppLanguage($0) }
                        )
                    ) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.displayName).tag(language)
                        }
                    }

                    Button {
                        appState.openSystemNotificationSettings()
                    } label: {
                        Label(strings.openNotificationSettings, systemImage: "bell.badge")
                    }

                    Picker(
                        strings.notificationSound,
                        selection: Binding(
                            get: { appState.draftSettings.notificationSound },
                            set: { appState.setDraftNotificationSound($0) }
                        )
                    ) {
                        ForEach(NotificationSoundOption.allCases) { sound in
                            Text(sound.displayName(language: appState.draftSettings.appLanguage)).tag(sound)
                        }
                    }
                }

                Section(strings.developerOptions) {
                    Toggle(
                        strings.debuggingMode,
                        isOn: Binding(
                            get: { appState.isDebuggingEnabled },
                            set: { appState.setDebuggingEnabled($0) }
                        )
                    )

                    if appState.isDebuggingEnabled {
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
            }

            HStack(spacing: 14) {
                Link(strings.feedbackLink, destination: Self.feedbackURL)

                Text("·")
                    .foregroundStyle(.tertiary)

                Link(destination: Self.kofiTipURL) {
                    Label(strings.tipMe, systemImage: "heart.fill")
                        .labelStyle(.titleAndIcon)
                }
                .accessibilityLabel(strings.supportDeveloper)
            }
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 8)
                .padding(.bottom, 10)
        }
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

#if os(iOS)
private struct SettingsDebugLongPressInstaller: UIViewRepresentable {
    let onLongPress: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onLongPress: onLongPress)
    }

    func makeUIView(context: Context) -> UIView {
        let view = WindowTrackingView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.onMoveToWindow = { markerView in
            context.coordinator.attach(from: markerView)
        }
        DispatchQueue.main.async {
            context.coordinator.attach(from: view)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onLongPress = onLongPress
        DispatchQueue.main.async {
            context.coordinator.attach(from: uiView)
        }
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class WindowTrackingView: UIView {
        var onMoveToWindow: ((UIView) -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onMoveToWindow?(self)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onLongPress: () -> Void
        private weak var attachedView: UIView?
        private weak var recognizer: UILongPressGestureRecognizer?

        init(onLongPress: @escaping () -> Void) {
            self.onLongPress = onLongPress
        }

        func attach(from markerView: UIView) {
            guard let targetView = markerView.window else {
                return
            }

            if attachedView === targetView, recognizer != nil {
                return
            }

            detach()
            let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            recognizer.minimumPressDuration = 0.75
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            targetView.addGestureRecognizer(recognizer)
            self.attachedView = targetView
            self.recognizer = recognizer
        }

        func detach() {
            if let recognizer, let attachedView {
                attachedView.removeGestureRecognizer(recognizer)
            }

            self.recognizer = nil
            self.attachedView = nil
        }

        @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began else {
                return
            }

            onLongPress()
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}
#else
private struct SettingsDebugLongPressInstaller: View {
    let onLongPress: () -> Void

    var body: some View {
        Color.clear
            .onLongPressGesture(minimumDuration: 0.75, perform: onLongPress)
    }
}
#endif

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

private struct MobileLogPageButton: View {
    var systemImage: String
    var isDisabled: Bool
    var action: () -> Void

    var body: some View {
        Button {
            guard !isDisabled else {
                return
            }

            action()
        } label: {
            Image(systemName: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(isDisabled ? .tertiary : .primary)
                .frame(width: 34, height: 30)
                .background(Color.secondary.opacity(isDisabled ? 0.04 : 0.08))
                .clipShape(RoundedRectangle(cornerRadius: 7))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

private struct MobileLogRow: View {
    var entry: AppLogEntry

    var body: some View {
        Text(lineText)
            .font(.system(size: 10, weight: .regular, design: .monospaced))
            .foregroundStyle(color)
            .lineSpacing(0)
            .lineLimit(1)
            .truncationMode(.middle)
            .padding(.vertical, 1)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var lineText: String {
        "\(Self.dateFormatter.string(from: entry.createdAt)) \(entry.level.displayName.uppercased()) \(entry.message)"
    }

    private var color: Color {
        switch entry.level {
        case .info:
            .primary
        case .warning:
            .orange
        case .error:
            .red
        }
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .medium
        return formatter
    }()
}
