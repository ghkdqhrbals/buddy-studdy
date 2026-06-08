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

private struct CommunityQuestionRoute: Identifiable, Hashable {
    var id: String
}

private struct MobileHomeView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedHomeScope: HomeFeedScope = .all
    @State private var editMode: EditMode = .inactive
    @State private var hasLoadedCommunityQuestions = false
    @State private var editingStudyCategory: StudyCategory?
    @State private var isAddingStudyCategory = false
    @State private var selectedCommunityQuestionRoute: CommunityQuestionRoute?
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
                homeTitleRow
                homeScopePickerRow
                homeRefreshRow
                homeContentSection
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
        .navigationDestination(item: $selectedCommunityQuestionRoute) { route in
            if let question = appState.communityQuestions.first(where: { $0.id == route.id }) {
                CommunityQuestionDetailView(question: question)
            } else {
                ContentUnavailableView(strings.communityQuestion, systemImage: "bubble.left.and.bubble.right")
            }
        }
    }

    private var homeTitleRow: some View {
        MobileRootLargeTitle(strings.tabHome)
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 8, trailing: 0))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    private var homeScopePickerRow: some View {
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
    }

    @ViewBuilder
    private var homeRefreshRow: some View {
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
    }

    private var myStudySection: some View {
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
                    communityQuestionRow(question)
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
        PixelAvatarGlyph(
            avatarName: ProfileAvatarOption.glyphName(for: symbolName),
            colorSeed: defaultColorSeed,
            usesNeutralColor: usesNeutralColor
        )
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

private struct PixelAvatarGlyph: View {
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

private struct PixelAvatarCell: Identifiable {
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

private struct PixelAvatarPalette {
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
        let source = normalizedSeed.isEmpty ? "buddy-studdy-default-profile" : normalizedSeed
        let hash = source.unicodeScalars.reduce(UInt32(2_166_136_261)) { value, scalar in
            (value ^ UInt32(scalar.value)) &* 16_777_619
        }

        let hue = Double(hash % 360) / 360.0
        return Color(hue: hue, saturation: 0.48, brightness: 0.74)
    }
}

private enum PixelAvatarPattern {
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
}

private extension Color {
    func lighter() -> Color {
        self.opacity(0.82)
    }
}

private struct MobileProfileSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var profileDisplayName = ""
    @State private var allowPublicQuestionsAccess = true
    @State private var isConfirmingWithdrawal = false
    @State private var isShowingEmailSignIn = false
    @State private var isShowingCustomColorEditor = false
    @State private var wasSignedInWhenOpened = false

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
                                symbolName: appState.profileAvatarSymbolName,
                                displayName: profileDisplayName,
                                imageData: nil,
                                colorSeed: appState.profileAvatarColorSeed,
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

                    Section(strings.profileCharacter) {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                            ForEach(ProfileAvatarOption.all, id: \.self) { option in
                                Button {
                                    appState.updateCommunityProfileAvatar(symbolName: option)
                                } label: {
                                    HomeProfileAvatar(
                                        symbolName: option,
                                        displayName: profileDisplayName,
                                        imageData: nil,
                                        colorSeed: appState.profileAvatarColorSeed,
                                        usesNeutralColor: false,
                                        size: 50
                                    )
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .fill(Color.secondary.opacity(ProfileAvatarOption.canonicalName(for: appState.profileAvatarSymbolName) == option ? 0.16 : 0.06))
                                    )
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .stroke(
                                                ProfileAvatarOption.canonicalName(for: appState.profileAvatarSymbolName) == option ? Color.primary.opacity(0.32) : Color.secondary.opacity(0.08),
                                                lineWidth: 1
                                            )
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 2)
                    }

                    Section(strings.profileColor) {
                        HStack(spacing: 12) {
                            ForEach(ProfileAvatarColorOption.all) { option in
                                Button {
                                    appState.updateCommunityProfileAvatar(colorSeed: option.id)
                                } label: {
                                    Circle()
                                        .fill(option.color)
                                        .frame(width: 28, height: 28)
                                        .overlay {
                                            if appState.profileAvatarColorSeed == option.id {
                                                Image(systemName: "checkmark")
                                                    .font(.caption.weight(.bold))
                                                    .foregroundStyle(.white)
                                            }
                                        }
                                        .overlay {
                                            Circle()
                                                .stroke(Color.primary.opacity(appState.profileAvatarColorSeed == option.id ? 0.42 : 0.10), lineWidth: 1)
                                        }
                                }
                                .buttonStyle(.plain)
                            }

                            Button {
                                isShowingCustomColorEditor = true
                            } label: {
                                Circle()
                                    .fill(
                                        AngularGradient(
                                            colors: [.red, .orange, .yellow, .green, .blue, .purple, .red],
                                            center: .center
                                        )
                                    )
                                    .frame(width: 28, height: 28)
                                    .overlay {
                                        if ProfileAvatarCustomColor(seed: appState.profileAvatarColorSeed) != nil {
                                            Image(systemName: "slider.horizontal.3")
                                                .font(.caption2.weight(.bold))
                                                .foregroundStyle(.white)
                                        }
                                    }
                                    .overlay {
                                        Circle()
                                            .stroke(Color.primary.opacity(ProfileAvatarCustomColor(seed: appState.profileAvatarColorSeed) != nil ? 0.42 : 0.10), lineWidth: 1)
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(strings.customProfileColor)
                        }
                        .padding(.vertical, 6)
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
                                .font(.caption2)
                                .foregroundStyle(.red.opacity(0.72))
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                        .buttonStyle(.plain)
                    } footer: {
                        Text(strings.deleteAccountNotice)
                            .font(.caption2)
                            .foregroundStyle(.secondary.opacity(0.78))
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
                                avatarSymbolName: appState.profileAvatarSymbolName,
                                avatarColorSeed: appState.profileAvatarColorSeed,
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
                wasSignedInWhenOpened = appState.isCommunitySignedIn
                profileDisplayName = appState.communityProfile?.displayName ?? ""
                allowPublicQuestionsAccess = appState.communityProfile?.pageAccess.publicQuestions ?? true
                Task {
                    await appState.loadCommunityProfile()
                    profileDisplayName = appState.communityProfile?.displayName ?? profileDisplayName
                    allowPublicQuestionsAccess = appState.communityProfile?.pageAccess.publicQuestions ?? allowPublicQuestionsAccess
                }
            }
            .onChange(of: appState.communityProfile) { _, profile in
                guard let profile else {
                    profileDisplayName = ""
                    allowPublicQuestionsAccess = true
                    return
                }

                profileDisplayName = profile.displayName
                allowPublicQuestionsAccess = profile.pageAccess.publicQuestions
            }
            .onChange(of: appState.isCommunitySignedIn) { _, isSignedIn in
                if isSignedIn, !wasSignedInWhenOpened {
                    dismiss()
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
            .sheet(isPresented: $isShowingCustomColorEditor) {
                ProfileAvatarColorEditorSheet(
                    initialColor: ProfileAvatarCustomColor.from(seed: appState.profileAvatarColorSeed)
                ) { color in
                    appState.updateCommunityProfileAvatar(colorSeed: color.seed)
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

private struct ProfileAvatarColorEditorSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var red: Double
    @State private var green: Double
    @State private var blue: Double
    var onApply: (ProfileAvatarCustomColor) -> Void

    private var strings: AppStrings {
        appState.strings
    }

    private var selectedColor: ProfileAvatarCustomColor {
        ProfileAvatarCustomColor(red: Int(red.rounded()), green: Int(green.rounded()), blue: Int(blue.rounded()))
    }

    init(initialColor: ProfileAvatarCustomColor, onApply: @escaping (ProfileAvatarCustomColor) -> Void) {
        _red = State(initialValue: Double(initialColor.red))
        _green = State(initialValue: Double(initialColor.green))
        _blue = State(initialValue: Double(initialColor.blue))
        self.onApply = onApply
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 16) {
                        Circle()
                            .fill(selectedColor.color)
                            .frame(width: 64, height: 64)
                            .overlay {
                                Circle()
                                    .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                            }

                        VStack(alignment: .leading, spacing: 4) {
                            Text(strings.customProfileColor)
                                .font(.headline)
                            Text("RGB \(selectedColor.red), \(selectedColor.green), \(selectedColor.blue)")
                                .font(.footnote.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                rgbSlider(title: strings.red, value: $red, tint: .red)
                rgbSlider(title: strings.green, value: $green, tint: .green)
                rgbSlider(title: strings.blue, value: $blue, tint: .blue)
            }
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

    private func rgbSlider(title: String, value: Binding<Double>, tint: Color) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("\(Int(value.wrappedValue.rounded()))")
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                Slider(value: value, in: 0...255, step: 1)
                    .tint(tint)
            }
            .padding(.vertical, 2)
        }
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
    var onSignedIn: () -> Void

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

                    SecureField(strings.password, text: $password)
                        .textContentType(.password)
                        .submitLabel(.done)

                    if requiresVerification {
                        TextField(strings.emailVerificationCode, text: $verificationCode)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .submitLabel(.done)
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
                                requiresVerification = true
                                didSendCode = false
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

private enum ProfileAvatarOption {
    static let defaultSymbolName = "pixel-buddy"

    static let all = [
        defaultSymbolName,
        "pixel-scholar",
        "pixel-coder",
        "pixel-explorer",
        "pixel-artist",
        "pixel-star",
        "pixel-girl",
        "pixel-princess",
        "pixel-flower",
        "pixel-hero",
        "pixel-wizard",
        "pixel-robot",
        "pixel-chef",
        "pixel-pilot",
        "pixel-nurse",
        "pixel-knight",
        "pixel-dancer",
        "pixel-gamer",
        "pixel-scientist",
        "pixel-astronaut"
    ]

    static func glyphName(for symbolName: String) -> String {
        canonicalName(for: symbolName)
    }

    static func canonicalName(for symbolName: String) -> String {
        switch symbolName {
        case "person.fill", "person.crop.circle.fill", "pencil.tip":
            return defaultSymbolName
        case "graduationcap.circle.fill":
            return "pixel-scholar"
        case "book.circle.fill":
            return "pixel-buddy"
        case "star.circle.fill":
            return "pixel-star"
        case "bolt.circle.fill":
            return "pixel-coder"
        case "leaf.circle.fill":
            return "pixel-explorer"
        case "graduationcap.fill":
            return "pixel-scholar"
        case "book.fill", "brain.head.profile":
            return "pixel-buddy"
        case "sparkles", "star.fill":
            return "pixel-star"
        case "bolt.fill":
            return "pixel-coder"
        case "leaf.fill":
            return "pixel-explorer"
        default:
            return all.contains(symbolName) ? symbolName : defaultSymbolName
        }
    }
}

private struct ProfileAvatarColorOption: Identifiable {
    var id: String
    var color: Color

    static let all: [ProfileAvatarColorOption] = [
        ProfileAvatarColorOption(id: "avatar-color-mint", color: Color(red: 0.20, green: 0.72, blue: 0.52)),
        ProfileAvatarColorOption(id: "avatar-color-sky", color: Color(red: 0.20, green: 0.50, blue: 0.86)),
        ProfileAvatarColorOption(id: "avatar-color-violet", color: Color(red: 0.50, green: 0.36, blue: 0.82)),
        ProfileAvatarColorOption(id: "avatar-color-rose", color: Color(red: 0.84, green: 0.33, blue: 0.47)),
        ProfileAvatarColorOption(id: "avatar-color-amber", color: Color(red: 0.86, green: 0.56, blue: 0.20)),
        ProfileAvatarColorOption(id: "avatar-color-teal", color: Color(red: 0.16, green: 0.62, blue: 0.70)),
        ProfileAvatarColorOption(id: "avatar-color-graphite", color: Color(red: 0.36, green: 0.38, blue: 0.42)),
        ProfileAvatarColorOption(id: "avatar-color-lime", color: Color(red: 0.48, green: 0.70, blue: 0.28))
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

    static func from(seed: String) -> ProfileAvatarCustomColor {
        if let customColor = ProfileAvatarCustomColor(seed: seed) {
            return customColor
        }
        if let preset = ProfileAvatarColorOption.option(for: seed),
           let components = preset.color.avatarRGBComponents {
            return ProfileAvatarCustomColor(red: components.red, green: components.green, blue: components.blue)
        }
        return ProfileAvatarCustomColor(red: 78, green: 163, blue: 122)
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
    var hasPendingQuestion: Bool
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(category.title)
                    .lineLimit(1)
                    .fontWeight(.regular)

                Text("\(category.difficulty.displayName(language: strings.language)) · \(category.sanitizedOpenAIModel)")
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

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(question.question)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)

            CommunityQuestionStatsMeta(question: question)
        }
        .padding(.vertical, 4)
    }
}

private struct CommunityQuestionDetailView: View {
    @EnvironmentObject private var appState: AppState
    var question: CommunityQuestion
    @State private var displayQuestion: CommunityQuestion
    @State private var comments: [CommunityQuestionComment] = []
    @State private var commentsTotalCount = 0
    @State private var isLoadingComments = false
    @State private var commentDraft = ""
    @State private var isSendingComment = false

    init(question: CommunityQuestion) {
        self.question = question
        _displayQuestion = State(initialValue: question)
    }

    private var strings: AppStrings {
        appState.strings
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

                Button(role: .destructive) {
                    Task {
                        await appState.reportCommunityQuestion(
                            displayQuestion,
                            reason: strings.reportReasonInappropriate
                        )
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
        .task(id: displayQuestion.id) {
            await loadQuestionDetail()
            await loadComments()
        }
    }

    private var communityQuestionMeta: some View {
        HStack(spacing: 8) {
            Text(displayQuestion.topic.isEmpty ? "Swift" : displayQuestion.topic)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text("Lv.\(displayQuestion.difficultyLevel)")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let answeredAt = displayQuestion.answeredAt {
                Text(answeredAt, style: .date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
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
            .foregroundStyle(displayQuestion.isLikedByMe ? .red : .primary)

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
                    CommunityCommentRow(comment: comment)
                }
            }

            HStack(alignment: .bottom, spacing: 8) {
                TextField(strings.writeComment, text: $commentDraft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...4)
                    .padding(.vertical, 9)
                    .padding(.horizontal, 12)
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))

                Button {
                    sendComment()
                } label: {
                    if isSendingComment {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.title2)
                    }
                }
                .buttonStyle(.plain)
                .disabled(commentDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSendingComment)
            }
        }
    }

    private func toggleLike() {
        let next = !displayQuestion.isLikedByMe
        displayQuestion.isLikedByMe = next
        displayQuestion.likeCount = max(0, displayQuestion.likeCount + (next ? 1 : -1))

        Task {
            await appState.setCommunityQuestionLike(question, isLiked: next)
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
            }
        }
    }
}

private struct CommunityCommentRow: View {
    var comment: CommunityQuestionComment

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            HomeProfileAvatar(
                symbolName: comment.author.avatarSymbolName,
                displayName: comment.author.displayName,
                colorSeed: comment.author.avatarColorSeed,
                size: 26
            )

            VStack(alignment: .leading, spacing: 3) {
                Text(comment.author.displayName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                Text(comment.body)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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
