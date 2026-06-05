import SwiftUI
#if canImport(UIKit)
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
                        .mobileTabTitle(strings.tabHome)
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
                        .mobileTabTitle(strings.tabRecords)
                }
                .tabItem {
                    Label(strings.tabRecords, systemImage: "clock.arrow.circlepath")
                }
                .tag(AppTab.records)

                NavigationStack {
                    StatisticsView()
                        .padding(.horizontal, 16)
                        .mobileTabTitle(strings.tabStatistics)
                }
                .tabItem {
                    Label(strings.tabStatistics, systemImage: "chart.xyaxis.line")
                }
                .tag(AppTab.statistics)

                NavigationStack {
                    MobileSettingsView()
                        .mobileTabTitle(strings.tabSettings)
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
    @State private var editMode: EditMode = .inactive
    @State private var hasLoadedCommunityQuestions = false
    @State private var editingStudyCategory: StudyCategory?
    @State private var isAddingStudyCategory = false
    @State private var selectedCommunityQuestion: CommunityQuestion?
    @State private var isShowingProfileSettings = false

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        List {
            Section(strings.studyCategories) {
                ForEach(appState.studyCategoriesForDisplay) { category in
                    if editMode.isEditing {
                        MobileHomeCategoryRow(
                            category: category,
                            isActive: appState.settings.selectedStudyCategoryID == category.id,
                            strings: strings
                        )
                    } else {
                        Button {
                            appState.homeStudyRoute = HomeStudyRoute(categoryID: category.id)
                        } label: {
                            MobileHomeCategoryRow(
                                category: category,
                                isActive: appState.settings.selectedStudyCategoryID == category.id,
                                strings: strings
                            )
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                editingStudyCategory = category
                            } label: {
                                Label(strings.edit, systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            if appState.settings.selectedStudyCategoryID != category.id {
                                Button {
                                    appState.activateStudyCategory(category.id)
                                } label: {
                                    Label(strings.activateStudy, systemImage: "checkmark.circle")
                                }
                                .tint(.green)
                            }
                        }
                    }
                }
                .onMove(perform: appState.moveStudyCategories)

                Text(strings.studyProfileHelp)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if appState.isCommunitySignedIn {
                Section {
                    if let message = appState.communityErrorMessage {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    if appState.isLoadingCommunityQuestions && appState.communityQuestions.isEmpty {
                        ProgressView()
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding(.vertical, 8)
                    }

                    if appState.communityQuestions.isEmpty && !appState.isLoadingCommunityQuestions {
                        Text(strings.noCommunityQuestions)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 8)
                    } else {
                        ForEach(appState.communityQuestions) { question in
                            Button {
                                selectedCommunityQuestion = question
                            } label: {
                                MobileCommunityQuestionRow(question: question)
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
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

                    Button(role: .destructive) {
                        appState.signOutFromCommunity()
                    } label: {
                        Text(strings.communityLogout)
                    }
                } header: {
                    Text(strings.communityFeed)
                } footer: {
                    Text(appState.communityQuestions.isEmpty ? strings.communitySearchHelp : strings.communityQuestionLimit)
                        .foregroundStyle(.secondary)
                }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(strings.communityLoginHelp)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Button {
                            appState.signInToCommunity()
                        } label: {
                            Label(strings.communityLogin, systemImage: "person.crop.circle.badge.checkmark")
                        }
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text(strings.communityFeed)
                }
            }
        }
        .background(Color(.systemBackground))
        .searchable(
            text: $appState.communitySearchText,
            placement: .navigationBarDrawer(displayMode: .automatic),
            prompt: strings.topicSearch
        )
        .environment(\.editMode, $editMode)
        .task {
            guard appState.isCommunitySignedIn, !hasLoadedCommunityQuestions else {
                return
            }

            hasLoadedCommunityQuestions = true
            await appState.loadCommunityQuestions(reset: true, userInitiated: false)
        }
        .refreshable {
            await appState.refreshVisibleData()
            if appState.isCommunitySignedIn {
                await appState.loadCommunityQuestions(reset: true, userInitiated: false)
            }
        }
        .onSubmit(of: .search) {
            if appState.isCommunitySignedIn {
                appState.refreshCommunityQuestions(userInitiated: true)
            }
        }
        .onChange(of: appState.communitySearchText) { _, newValue in
            if appState.isCommunitySignedIn,
               newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                appState.refreshCommunityQuestions(userInitiated: false)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    isShowingProfileSettings = true
                } label: {
                    HomeProfileAvatar(profile: appState.communityProfile)
                }
                .accessibilityLabel(strings.profile)
            }

            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 12) {
                    Button {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                    } label: {
                        Text(editMode.isEditing ? strings.done : strings.edit)
                    }

                    Button {
                        isAddingStudyCategory = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
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
}

private struct HomeProfileAvatar: View {
    var profile: CommunityUserProfile?
    var size: CGFloat = 34

    var body: some View {
        ZStack {
            Circle()
                .fill(Color(.secondarySystemBackground))

            if let avatarURL = profile?.avatarURL {
                AsyncImage(url: avatarURL) { image in
                    image
                        .resizable()
                        .scaledToFill()
                } placeholder: {
                    Image(systemName: "person.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
            } else if let initial = profile?.displayName.trimmingCharacters(in: .whitespacesAndNewlines).first {
                Text(String(initial).uppercased())
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.primary)
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay {
            Circle()
                .strokeBorder(Color(.separator).opacity(0.5), lineWidth: 0.6)
        }
        .contentShape(Circle())
    }
}

private struct MobileProfileSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var profileDisplayName = ""
    @State private var profileBio = ""

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        NavigationStack {
            Form {
                if appState.isCommunitySignedIn {
                    Section {
                        HStack(spacing: 14) {
                            HomeProfileAvatar(profile: appState.communityProfile, size: 54)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(appState.communityProfile?.displayName ?? strings.profile)
                                    .font(.headline)
                                    .lineLimit(1)

                                if let bio = appState.communityProfile?.bio,
                                   !bio.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text(bio)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }

                    Section(strings.profile) {
                        TextField(strings.profileDisplayName, text: $profileDisplayName)
                            .textInputAutocapitalization(.words)

                        TextField(strings.profileBio, text: $profileBio, axis: .vertical)
                            .lineLimit(3...5)

                        Button {
                            Task {
                                await appState.updateCommunityProfile(
                                    displayName: profileDisplayName,
                                    bio: profileBio
                                )
                            }
                        } label: {
                            if appState.isUpdatingCommunityProfile {
                                ProgressView()
                            } else {
                                Text(strings.save)
                            }
                        }
                        .disabled(appState.isUpdatingCommunityProfile)
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
                            HomeProfileAvatar(profile: nil, size: 58)

                            Text(strings.communityLoginHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Button {
                                appState.signInToCommunity()
                            } label: {
                                Label(strings.communityLogin, systemImage: "person.crop.circle.badge.checkmark")
                            }
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
                    Button(strings.done) {
                        dismiss()
                    }
                }
            }
            .onAppear {
                profileDisplayName = appState.communityProfile?.displayName ?? ""
                profileBio = appState.communityProfile?.bio ?? ""
                Task {
                    await appState.loadCommunityProfile()
                    profileDisplayName = appState.communityProfile?.displayName ?? profileDisplayName
                    profileBio = appState.communityProfile?.bio ?? profileBio
                }
            }
        }
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
                HStack(spacing: 6) {
                    Text(category.title)
                        .lineLimit(1)

                    if isActive {
                        Text(strings.activeStudy)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }

                Text("\(category.difficulty.displayName(language: strings.language)) · \(category.sanitizedOpenAIModel)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 6)
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

                    Text(strings.difficultyScaleHint)
                        .font(.caption2)
                        .foregroundStyle(.secondary)

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

                Section {
                    Text(strings.studyProfileHelp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
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
                    Text(author.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
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
            Form {
                Section(strings.communityQuestion) {
                    Text(question.question)
                        .font(.body)
                        .textSelection(.enabled)

                    LabeledContent(strings.topic, value: question.topic.isEmpty ? "Swift" : question.topic)
                    LabeledContent(strings.level, value: "Lv.\(question.difficultyLevel)")
                }

                if let author = question.author {
                    Section(strings.profile) {
                        HStack(spacing: 12) {
                            AsyncImage(url: author.avatarURL) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.title2)
                                    .foregroundStyle(.secondary)
                            }
                            .frame(width: 42, height: 42)
                            .clipShape(Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text(author.displayName)
                                    .font(.headline)
                                if !author.bio.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text(author.bio)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                Section {
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
                }
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
    @State private var profileDisplayName = ""
    @State private var profileBio = ""

    var body: some View {
        let strings = appState.settingsEditorStrings

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

                    Text(strings.questionVisibilityHelp)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Text(strings.studyProfileHelp)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if appState.isCommunitySignedIn {
                Section(strings.profile) {
                    TextField(strings.profileDisplayName, text: $profileDisplayName)
                        .textInputAutocapitalization(.words)

                    TextField(strings.profileBio, text: $profileBio, axis: .vertical)
                        .lineLimit(2...4)

                    Button {
                        Task {
                            await appState.updateCommunityProfile(
                                displayName: profileDisplayName,
                                bio: profileBio
                            )
                        }
                    } label: {
                        if appState.isUpdatingCommunityProfile {
                            ProgressView()
                        } else {
                            Text(strings.save)
                        }
                    }
                    .disabled(appState.isUpdatingCommunityProfile)
                }
            }

            Section("OpenAI") {
                HStack {
                    Group {
                        if showsAPIKey {
                            TextField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                        } else {
                            SecureField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                        }
                    }
                    .textContentType(.password)

                    Button(showsAPIKey ? strings.hide : strings.show) {
                        showsAPIKey.toggle()
                    }
                }

                if let statusMessage = appState.statusMessage {
                    Text(statusMessage)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                if let validationMessage = appState.apiKeyValidationMessage {
                    Text(validationMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(strings.openAIBillingHelp)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Link(strings.openAIUsageAndCostsPage, destination: URL(string: "https://platform.openai.com/usage")!)
                    Link(strings.openAIBillingPage, destination: URL(string: "https://platform.openai.com/settings/organization/billing/overview")!)
                }
                .padding(.vertical, 2)
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
        .keyboardDoneToolbar(strings.done)
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
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(strings.tabSettings)
                    .font(.headline)
                    .onLongPressGesture(minimumDuration: 0.75) {
                        appState.requestDebugPanelIfEnabledOrEnableOnDemand()
                    }
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task {
                        await appState.saveSettingsAndValidateAPIKey()
                    }
                } label: {
                    if appState.isValidatingAPIKey {
                        ProgressView()
                    } else {
                        Text(appState.hasUnsavedSettingsChanges ? strings.save : strings.saved)
                    }
                }
                .disabled(appState.isValidatingAPIKey)
            }
        }
        .onAppear {
            appState.beginSettingsEditing()
            profileDisplayName = appState.communityProfile?.displayName ?? ""
            profileBio = appState.communityProfile?.bio ?? ""
            Task {
                await appState.loadCommunityProfile()
                profileDisplayName = appState.communityProfile?.displayName ?? profileDisplayName
                profileBio = appState.communityProfile?.bio ?? profileBio
            }
        }
        .onDisappear {
            appState.cancelSettingsEditing()
        }
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
