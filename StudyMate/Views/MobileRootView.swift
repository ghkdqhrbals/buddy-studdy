import SwiftUI
#if os(iOS)
import UIKit
#endif

struct MobileRootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        let strings = appState.strings

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
                    HistoryView()
                        .padding(.horizontal, 16)
                }
                .tabItem {
                    Label(strings.tabRecords, systemImage: "clock.arrow.circlepath")
                }
                .tag(AppTab.records)

                NavigationStack {
                    StatisticsView()
                        .padding(.horizontal, 16)
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
            .alert(item: $appState.pageAccessPrompt) { prompt in
                Alert(
                    title: Text(prompt.title),
                    message: Text(prompt.message),
                    primaryButton: .default(Text(strings.signInWithGoogle)) {
                        appState.dismissPageAccessPrompt()
                        appState.signInToCommunity()
                    },
                    secondaryButton: .cancel(Text(strings.cancel)) {
                        appState.dismissPageAccessPrompt()
                    }
                )
            }
            .onAppear {
                appState.normalizeSelectedTabForMobile()
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

private struct MobileHomeView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedHomeScope: HomeFeedScope = .all
    @State private var editMode: EditMode = .inactive
    @State private var hasLoadedCommunityQuestions = false
    @State private var editingStudyCategory: StudyCategory?
    @State private var isAddingStudyCategory = false
    @State private var selectedCommunityQuestion: CommunityQuestion?
    @State private var isShowingProfileSettings = false
    @State private var isPullRefreshing = false
    @State private var isSearchVisible = false
    @State private var homeStudySearchText = ""
    @State private var communitySearchDebounceTask: Task<Void, Never>?
    @State private var searchFocusTask: Task<Void, Never>?
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
        let categories = appState.studyCategoriesForDisplay
        let query = trimmedHomeStudySearchText
        guard !query.isEmpty else {
            return categories
        }

        return categories.filter { category in
            category.matchesHomeSearch(query, appLanguage: strings.language)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            List {
                MobileRootLargeTitle(strings.tabHome)
                    .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 8, trailing: 0))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)

                Picker("", selection: $selectedHomeScope) {
                    ForEach(HomeFeedScope.allCases) { scope in
                        Text(scope.title(strings: strings))
                            .tag(scope)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 10, trailing: 0))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)

                if isPullRefreshing {
                    HStack {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                        Spacer()
                    }
                    .padding(.vertical, 10)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .accessibilityLabel(strings.refresh)
                }

                if selectedHomeScope == .my, !appState.isCommunitySignedIn {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(strings.myStudyLoginHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Button {
                                appState.signInToCommunity()
                            } label: {
                                GoogleSignInButtonLabel(title: strings.signInWithGoogle)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 4)
                    }
                } else if selectedHomeScope == .my {
                    Section {
                        if filteredStudyCategories.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(strings.noMatchingTopics)
                                    .font(.subheadline.weight(.semibold))

                                Text(strings.noMatchingTopicsDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 8)
                        } else {
                            ForEach(filteredStudyCategories) { category in
                                if editMode.isEditing {
                                    MobileHomeCategoryRow(
                                        category: category,
                                        isActive: appState.settings.selectedStudyCategoryID == category.id,
                                        strings: strings
                                    )
                                } else {
                                    Button {
                                        appState.selectStudyCategory(category.id)
                                    } label: {
                                        MobileHomeCategoryRow(
                                            category: category,
                                            isActive: appState.settings.selectedStudyCategoryID == category.id,
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

                                        if appState.settings.selectedStudyCategoryID != category.id {
                                            Button {
                                                appState.activateStudyCategory(category.id)
                                            } label: {
                                                Label(strings.activateStudy, systemImage: "checkmark.circle")
                                            }
                                        }
                                    }
                                }
                            }
                            .onMove { offsets, destination in
                                guard trimmedHomeStudySearchText.isEmpty else {
                                    return
                                }

                                appState.moveStudyCategories(from: offsets, to: destination)
                            }
                        }
                    }
                } else if selectedHomeScope == .all {
                    Section {
                        if appState.isLoadingCommunityQuestions && appState.communityQuestions.isEmpty {
                            ProgressView()
                                .frame(maxWidth: .infinity, alignment: .center)
                                .padding(.vertical, 8)
                        }

                        if appState.communityQuestions.isEmpty && !appState.isLoadingCommunityQuestions {
                            MobileCommunityEmptyState(strings: strings)
                                .frame(maxWidth: .infinity, minHeight: 320)
                                .listRowInsets(EdgeInsets(top: 18, leading: 0, bottom: 18, trailing: 0))
                                .listRowSeparator(.hidden)
                        } else {
                            ForEach(appState.communityQuestions) { question in
                                Button {
                                    selectedCommunityQuestion = question
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

                            if appState.isLoadingCommunityQuestions && appState.canLoadCommunityQuestions {
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
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .refreshable {
                await refreshHomeData()
            }
            .searchSafeRefreshControlOffset(offset: 36, isRefreshing: isPullRefreshing, hidesSystemIndicator: true)
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
                ToolbarItem(placement: .topBarTrailing) {
                    homeToolbarSearchControl(strings: strings)
                }
                .sharedBackgroundVisibility(isHomeSearchActive ? .hidden : .automatic)

                if shouldShowHomeAddToolbarButton {
                    ToolbarItem(placement: .topBarTrailing) {
                        homeAddToolbarButton(strings: strings)
                    }
                    .sharedBackgroundVisibility(.hidden)
                }
            } else {
                ToolbarItem(placement: .topBarTrailing) {
                    homeToolbarSearchControl(strings: strings)
                }

                if shouldShowHomeAddToolbarButton {
                    ToolbarItem(placement: .topBarTrailing) {
                        homeAddToolbarButton(strings: strings)
                    }
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
        .onChange(of: appState.communitySearchText) {
            scheduleCommunitySearchReload()
        }
        .onDisappear {
            communitySearchDebounceTask?.cancel()
            searchFocusTask?.cancel()
            searchFocusTask = nil
            if activeTrimmedSearchText.isEmpty {
                closeHomeSearch(clearText: false)
            }
        }
        .sheet(isPresented: $isShowingProfileSettings) {
            MobileProfileSettingsSheet()
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
        .sheet(item: $selectedCommunityQuestion) { question in
            CommunityQuestionDetailSheet(question: question)
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
              let profileID = appState.communityProfile?.id else {
            return nil
        }

        return "user-\(profileID)"
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
                guard selectedHomeScope == .all else {
                    return
                }

                Task {
                    await appState.loadCommunityQuestions(reset: true, userInitiated: true)
                }
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
            Button {
                showHomeSearch()
            } label: {
                MobileToolbarIconButtonLabel(systemName: "magnifyingglass")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(strings.search)

            if selectedHomeScope == .my, appState.isCommunitySignedIn {
                Button {
                    isAddingStudyCategory = true
                } label: {
                    MobileToolbarIconButtonLabel(systemName: "plus")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(strings.newStudyCategory)
            }
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
    private func refreshHomeData() async {
        isPullRefreshing = true
        defer {
            isPullRefreshing = false
        }

        switch selectedHomeScope {
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
    private func scheduleCommunitySearchReload() {
        communitySearchDebounceTask?.cancel()
        guard selectedHomeScope == .all else {
            return
        }

        communitySearchDebounceTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else {
                return
            }

            hasLoadedCommunityQuestions = true
            await appState.loadCommunityQuestions(reset: true, userInitiated: false)
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
private struct HomeProfileAvatar: View {
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
        PersonAvatarGlyph(colorSeed: defaultColorSeed, usesNeutralColor: usesNeutralColor)
            .frame(width: size, height: size)
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

private struct PersonAvatarGlyph: View {
    var colorSeed: String
    var usesNeutralColor: Bool = false

    var body: some View {
        GeometryReader { proxy in
            let iconSize = min(proxy.size.width, proxy.size.height) * 0.48

            ZStack {
                Circle()
                    .fill(usesNeutralColor ? Color.secondary.opacity(0.42) : stableAvatarColor(seed: colorSeed))

                Image(systemName: "person.fill")
                    .font(.system(size: iconSize, weight: .semibold))
                    .foregroundStyle(.white)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }

    private func stableAvatarColor(seed: String) -> Color {
        let normalizedSeed = seed.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = normalizedSeed.isEmpty ? "buddy-studdy-default-profile" : normalizedSeed
        let hash = source.unicodeScalars.reduce(UInt32(2_166_136_261)) { value, scalar in
            (value ^ UInt32(scalar.value)) &* 16_777_619
        }

        let hue = Double(hash % 360) / 360.0
        return Color(hue: hue, saturation: 0.48, brightness: 0.74)
    }
}

private struct MobileProfileSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var profileDisplayName = ""
    @State private var allowPublicQuestionsAccess = true
    @State private var isConfirmingWithdrawal = false
    @State private var isShowingEmailSignIn = false

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        let strings = appState.strings

        NavigationStack {
            Form {
                if appState.isCommunitySignedIn {
                    Section {
                        HStack(spacing: 14) {
                            HomeProfileAvatar(
                                symbolName: ProfileAvatarOption.defaultSymbolName,
                                displayName: profileDisplayName,
                                imageData: nil,
                                colorSeed: appState.communityProfile.map { "user-\($0.id)" },
                                usesNeutralColor: appState.communityProfile == nil,
                                size: 54
                            )

                            TextField(strings.profileDisplayName, text: $profileDisplayName)
                                .font(.headline)
                                .textInputAutocapitalization(.words)
                                .submitLabel(.done)
                        }
                        .padding(.vertical, 4)
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

                    Section {
                        Button(role: .destructive) {
                            isConfirmingWithdrawal = true
                        } label: {
                            Text(strings.deleteAccount)
                        }
                    } footer: {
                        Text(strings.deleteAccountNotice)
                    }
                } else {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            HomeProfileAvatar(
                                symbolName: ProfileAvatarOption.defaultSymbolName,
                                displayName: nil,
                                imageData: nil,
                                colorSeed: nil,
                                usesNeutralColor: true,
                                size: 58
                            )

                            Text(strings.communityLoginHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Button {
                                appState.signInToCommunity()
                            } label: {
                                GoogleSignInButtonLabel(title: strings.signInWithGoogle)
                            }
                            .buttonStyle(.plain)

                            Button {
                                isShowingEmailSignIn = true
                            } label: {
                                Label(strings.signInWithEmail, systemImage: "envelope.fill")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .padding(.horizontal, 12)
                                    .background(Color.secondary.opacity(0.06))
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
                                    }
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
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
                                displayName: profileDisplayName,
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
                    .disabled(appState.isUpdatingCommunityProfile)
                }
            }
            .onAppear {
                profileDisplayName = appState.communityProfile?.displayName ?? ""
                allowPublicQuestionsAccess = appState.communityProfile?.pageAccess.publicQuestions ?? true
                Task {
                    await appState.loadCommunityProfile()
                    profileDisplayName = appState.communityProfile?.displayName ?? profileDisplayName
                    allowPublicQuestionsAccess = appState.communityProfile?.pageAccess.publicQuestions ?? allowPublicQuestionsAccess
                }
            }
            .confirmationDialog(
                strings.deleteAccount,
                isPresented: $isConfirmingWithdrawal,
                titleVisibility: .visible
            ) {
                Button(strings.deleteAccount, role: .destructive) {
                    Task {
                        await appState.withdrawCommunityAccount()
                        dismiss()
                    }
                }
                Button(strings.cancel, role: .cancel) {}
            } message: {
                Text(strings.deleteAccountConfirmMessage)
            }
            .disabled(appState.isWithdrawingCommunityAccount)
            .overlay {
                if appState.isWithdrawingCommunityAccount {
                    ProgressView()
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
    }

    private func profileConfirmationTitle(strings: AppStrings) -> String {
        guard appState.isCommunitySignedIn else {
            return strings.done
        }

        return strings.save
    }

}

private struct EmailSignInSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var isSubmitting = false
    var onSignedIn: () -> Void

    private var strings: AppStrings {
        appState.strings
    }

    private var canSubmit: Bool {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalizedEmail.contains("@") && password.count >= 6 && !isSubmitting
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

                    SecureField(strings.password, text: $password)
                        .textContentType(.password)
                        .submitLabel(.done)
                } footer: {
                    Text(strings.emailLoginHelp)
                }
            }
            .keyboardDoneToolbar(strings.done)
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
                            let didSignIn = await appState.signInToCommunity(email: email, password: password)
                            isSubmitting = false
                            if didSignIn {
                                onSignedIn()
                            }
                        }
                    } label: {
                        if isSubmitting {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text(strings.done)
                        }
                    }
                    .disabled(!canSubmit)
                }
            }
        }
    }
}

private enum ProfileAvatarOption {
    static let defaultSymbolName = "person.fill"

    static let all = [
        defaultSymbolName,
        "graduationcap.fill",
        "book.fill",
        "brain.head.profile",
        "sparkles",
        "star.fill",
        "bolt.fill",
        "leaf.fill"
    ]

    static func glyphName(for symbolName: String) -> String {
        canonicalName(for: symbolName)
    }

    static func canonicalName(for symbolName: String) -> String {
        switch symbolName {
        case "person.crop.circle.fill", "pencil.tip":
            return defaultSymbolName
        case "graduationcap.circle.fill":
            return "graduationcap.fill"
        case "book.circle.fill":
            return "book.fill"
        case "star.circle.fill":
            return "star.fill"
        case "bolt.circle.fill":
            return "bolt.fill"
        case "leaf.circle.fill":
            return "leaf.fill"
        default:
            return symbolName
        }
    }
}

private struct GoogleSignInButtonLabel: View {
    var title: String

    var body: some View {
        HStack(spacing: 10) {
            GoogleMark()
                .frame(width: 18, height: 18)

            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .background(Color.secondary.opacity(0.06))
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .foregroundStyle(.primary)
    }
}

private struct GoogleMark: View {
    var body: some View {
        Canvas { context, size in
            let lineWidth = max(2.4, size.width * 0.16)
            let radius = min(size.width, size.height) / 2 - lineWidth / 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)

            func arc(_ start: Double, _ end: Double, _ color: Color) {
                var path = Path()
                path.addArc(
                    center: center,
                    radius: radius,
                    startAngle: .degrees(start),
                    endAngle: .degrees(end),
                    clockwise: false
                )
                context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            }

            arc(-35, 42, Color(red: 66 / 255, green: 133 / 255, blue: 244 / 255))
            arc(42, 150, Color(red: 234 / 255, green: 67 / 255, blue: 53 / 255))
            arc(150, 218, Color(red: 251 / 255, green: 188 / 255, blue: 5 / 255))
            arc(218, 322, Color(red: 52 / 255, green: 168 / 255, blue: 83 / 255))

            var crossbar = Path()
            crossbar.move(to: CGPoint(x: center.x, y: center.y))
            crossbar.addLine(to: CGPoint(x: size.width - lineWidth * 0.4, y: center.y))
            context.stroke(
                crossbar,
                with: .color(Color(red: 66 / 255, green: 133 / 255, blue: 244 / 255)),
                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
            )
        }
        .accessibilityHidden(true)
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
            sanitizedOpenAIModel,
            difficulty.displayName(language: appLanguage),
            "level \(difficulty.level)",
            "레벨 \(difficulty.level)"
        ]

        return searchableFields.contains { field in
            field.localizedCaseInsensitiveContains(query)
        }
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
    var isActive: Bool
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(isActive ? Color.green : Color.clear)
                .frame(width: 4, height: 34)

            VStack(alignment: .leading, spacing: 2) {
                Text(category.title)
                    .lineLimit(1)
                    .fontWeight(isActive ? .semibold : .regular)

                Text("\(category.difficulty.displayName(language: strings.language)) · \(category.sanitizedOpenAIModel)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

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

    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var difficultyLevel: Double
    @State private var customPrompt: String
    @State private var openAIModel: String

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
        _openAIModel = State(initialValue: category?.sanitizedOpenAIModel ?? StudySettings.defaultOpenAIModel)
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

                    Picker(strings.openAIModel, selection: $openAIModel) {
                        ForEach(modelOptions) { option in
                            Text(option.displayName).tag(option.id)
                        }
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
                        onSave(title, Difficulty(level: resolvedDifficultyLevel), customPrompt, openAIModel)
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

    private var modelOptions: [OpenAIModelOption] {
        let options = appState.openAIModelOptions.isEmpty ? OpenAIModelOption.all : appState.openAIModelOptions
        if options.contains(where: { $0.id == openAIModel }) {
            return options
        }

        return options + [OpenAIModelOption(id: openAIModel, displayName: openAIModel, supportsTextVerbosity: false)]
    }
}

private struct MobileCommunityQuestionRow: View {
    var question: CommunityQuestion

    private static let statusDateFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(question.question)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                Text(question.topic.isEmpty ? "Swift" : question.topic)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Text("Lv.\(question.difficultyLevel)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                Text(Self.statusDateFormatter.localizedString(for: question.createdAt, relativeTo: Date()))
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                if let author = question.author {
                    HStack(spacing: 4) {
                        HomeProfileAvatar(
                            symbolName: ProfileAvatarOption.defaultSymbolName,
                            displayName: author.displayName,
                            colorSeed: "user-\(author.id)",
                            size: 16
                        )

                        Text(author.displayName)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .accessibilityElement(children: .combine)
                }

                Spacer(minLength: 2)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct CommunityQuestionDetailSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    var question: CommunityQuestion

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    communityQuestionMeta

                    CommunityMessageBubble(role: .question) {
                        Text(question.question)
                            .font(.body)
                            .foregroundStyle(.white)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if let answer = question.answer?.trimmingCharacters(in: .whitespacesAndNewlines),
                       !answer.isEmpty {
                        CommunityAnswerMessage(answer: answer, author: question.author)
                    }

                    if let gradingResult = question.gradingResult {
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

                    Button(role: .destructive) {
                        Task {
                            await appState.reportCommunityQuestion(
                                question,
                                reason: strings.reportReasonInappropriate
                            )
                            dismiss()
                        }
                    } label: {
                        Label(strings.report, systemImage: "exclamationmark.bubble")
                    }
                    .buttonStyle(.borderless)
                    .padding(.top, 8)
                }
                .padding(16)
            }
            .navigationTitle(strings.communityQuestion)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.done) {
                        dismiss()
                    }
                }
            }
        }
    }

    private var communityQuestionMeta: some View {
        HStack(spacing: 8) {
            Text(question.topic.isEmpty ? "Swift" : question.topic)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text("Lv.\(question.difficultyLevel)")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let answeredAt = question.answeredAt {
                Text(answeredAt, style: .date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
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
                        symbolName: ProfileAvatarOption.defaultSymbolName,
                        displayName: author.displayName,
                        colorSeed: "user-\(author.id)",
                        size: 30
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
    @State private var apiKey = ""
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
                    Text(strings.onboardingFreeNote)
                        .fontWeight(.semibold)
                }

                Section(strings.onboardingLanguage) {
                    Picker(strings.appLanguage, selection: $language) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.displayName).tag(language)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(strings.onboardingOpenAI) {
                    SecureField(strings.openAIAPIKey, text: $apiKey)
                        .textContentType(.password)

                    Text(strings.onboardingAPIKeyHelp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        HStack(spacing: 4) {
                            Text(strings.onboardingCreateAPIKeyHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Link(strings.onboardingCreateAPIKeyAction, destination: URL(string: "https://platform.openai.com/settings/organization/api-keys")!)
                                .font(.caption)
                        }
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
                            await appState.completeOnboarding(settings: pendingSettings, apiKey: apiKey)
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
                apiKey = appState.apiKey
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
    @State private var showsAPIKey = false

    private static let feedbackURL = URL(string: "mailto:ghkdqhrbals@gmail.com?subject=BuddyStuddy%20Feedback")!

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

                    if appState.isCommunitySignedIn {
                        Toggle(
                            strings.questionVisibility,
                            isOn: Binding(
                                get: { appState.draftSettings.isQuestionPublic },
                                set: { appState.setDraftQuestionPublicity($0) }
                            )
                        )

                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            Group {
                                if showsAPIKey {
                                    TextField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                                } else {
                                    SecureField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                                }
                            }
                            .textContentType(.password)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            #endif

                            Button {
                                showsAPIKey.toggle()
                            } label: {
                                Image(systemName: showsAPIKey ? "eye.slash" : "eye")
                                    .font(.system(size: 17, weight: .semibold))
                                    .frame(width: 32, height: 32)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(showsAPIKey ? strings.hide : strings.show)
                        }

                        if let validationMessage = appState.apiKeyValidationMessage {
                            Text(validationMessage)
                                .font(.caption)
                                .foregroundStyle(.red)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, 2)
                } header: {
                    HStack(spacing: 6) {
                        Text("OpenAI")
                        Link("settings", destination: URL(string: "https://platform.openai.com/")!)
                            .font(.caption)
                            .textCase(nil)
                    }
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
            }

            Link(strings.feedbackLink, destination: Self.feedbackURL)
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
                settingsSaveToolbarButton(strings: strings)
            }
            #else
            ToolbarItem(placement: .confirmationAction) {
                settingsSaveToolbarButton(strings: strings)
            }
            #endif
        }
        .contentShape(Rectangle())
        .background {
            SettingsDebugLongPressInstaller {
                appState.requestDebugPanelIfEnabledOrEnableOnDemand()
            }
        }
        .overlay {
            if appState.isAPIDebugPanelPresented {
                Color.black.opacity(0.12)
                    .ignoresSafeArea()
                    .onTapGesture {
                        appState.isAPIDebugPanelPresented = false
                    }

                MovableAPIDebugPanel(
                    logs: appState.apiTrafficLogs,
                    isPresented: $appState.isAPIDebugPanelPresented
                )
                .padding(18)
                .transition(.scale(scale: 0.96).combined(with: .opacity))
                .zIndex(1)
            }
        }
        .animation(.snappy(duration: 0.18), value: appState.isAPIDebugPanelPresented)
        .onAppear {
            appState.beginSettingsEditing()
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
