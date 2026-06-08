import SwiftUI

struct RootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        let strings = appState.strings

        if !appState.hasCompletedOnboarding {
            OnboardingView()
        } else {
            TabView(selection: settingsAwareSelectedTab) {
                HomeView()
                    .contentPadding()
                    .tabItem {
                        Label(strings.tabHome, systemImage: "house.fill")
                    }
                    .tag(AppTab.home)

                StudyView()
                    .contentPadding()
                    .tabItem {
                        Label(strings.tabStudy, systemImage: "book.fill")
                    }
                    .tag(AppTab.study)

                SettingsView()
                    .tabItem {
                        Label(strings.tabSettings, systemImage: "gearshape.fill")
                    }
                    .tag(AppTab.settings)

                HistoryView()
                    .contentPadding()
                    .tabItem {
                        Label(strings.tabRecords, systemImage: "clock.arrow.circlepath")
                    }
                    .tag(AppTab.records)

                StatisticsView()
                    .contentPadding()
                    .tabItem {
                        Label(strings.tabStatistics, systemImage: "chart.xyaxis.line")
                    }
                    .tag(AppTab.statistics)
            }
            .frame(maxHeight: .infinity)
        }
    }

    private var settingsAwareSelectedTab: Binding<AppTab> {
        Binding(
            get: { appState.selectedTab },
            set: { newTab in
                appState.setSelectedTab(newTab)
            }
        )
    }
}

private struct HomeView: View {
    @EnvironmentObject private var appState: AppState
    #if os(iOS)
    @State private var editMode: EditMode = .inactive
    #else
    @State private var isEditingCategories = false
    #endif

    private var isCategoryEditing: Bool {
        #if os(iOS)
        editMode.isEditing
        #else
        isEditingCategories
        #endif
    }

    private var strings: AppStrings {
        appState.strings
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(strings.studyCategories)
                    .font(.headline)

                Spacer()

                HStack(spacing: 8) {
                    #if os(iOS)
                    Button {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                    } label: {
                        Text(editMode.isEditing ? strings.done : strings.edit)
                    }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    #endif
                }
            }
            .padding(.vertical, 8)

            VStack(spacing: 10) {
                List {
                    Section(strings.studyCategories) {
                        ForEach(appState.studyCategoriesForDisplay) { category in
                            HomeCategoryRow(
                                title: category.title,
                                isSelected: appState.selectedStudyCategoryIDForDisplay == category.id
                            )
                            .contentShape(Rectangle())
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .onTapGesture {
                                guard !isCategoryEditing else {
                                    return
                                }

                                appState.selectStudyCategory(category.id)
                            }
                            .overlay(alignment: .leading) {
                                Color.clear
                                    .contentShape(Rectangle())
                            }
                        }
                        .onDelete(perform: appState.deleteStudyCategories)
                        .onMove(perform: appState.moveStudyCategories)
                    }

                    CommunityFeedSection()
                }
                .listStyle(.inset)
                .searchable(text: $appState.communitySearchText, prompt: strings.topicSearch)
                #if os(iOS)
                .environment(\.editMode, $editMode)
                #endif
                .onAppear {
                    appState.refreshCommunityQuestions(userInitiated: false)
                }
                .onSubmit(of: .search) {
                    appState.refreshCommunityQuestions(userInitiated: true)
                }
                .onChange(of: appState.communitySearchText) { _, newValue in
                    if newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        appState.refreshCommunityQuestions(userInitiated: false)
                    }
                }
            }
            .frame(maxHeight: .infinity)
        }
    }
}

private struct HomeCategoryRow: View {
    var title: String
    var isSelected: Bool

    var body: some View {
        HStack {
            Text(title)
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)

            if isSelected {
                Text("•")
                    .font(.title3.bold())
                    .foregroundStyle(Color.accentColor)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

private struct CommunityFeedSection: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        let strings = appState.strings

        Section {
            Button {
                appState.refreshCommunityQuestions(userInitiated: true)
            } label: {
                Label(strings.refresh, systemImage: "arrow.clockwise")
            }
            .buttonStyle(.plain)
            .disabled(appState.isLoadingCommunityQuestions)

            if let message = appState.communityErrorMessage {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if appState.isLoadingCommunityQuestions {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            }

            if appState.communityQuestions.isEmpty && !appState.isLoadingCommunityQuestions {
                ContentUnavailableView(
                    strings.noCommunityQuestions,
                    systemImage: "person.3.fill",
                    description: Text(strings.communitySearchHelp)
                )
                .frame(minHeight: 120)
            } else {
                ForEach(appState.communityQuestions) { question in
                    CommunityQuestionRow(question: question)
                        .onAppear {
                            appState.shouldLoadNextCommunityQuestion(after: question.id)
                        }
                }

                if appState.canLoadCommunityQuestions {
                    Button(strings.nextPage) {
                        Task {
                            await appState.loadNextCommunityPage()
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(appState.isLoadingCommunityQuestions)
                }
            }
        } header: {
            Text(strings.communityFeed)
        } footer: {
            if !appState.communityQuestions.isEmpty {
                Text(strings.communityQuestionLimit)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct CommunityQuestionRow: View {
    var question: CommunityQuestion

    private static let statusDateFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(question.question)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)

            CommunityQuestionStatsMeta(question: question)

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

                Spacer(minLength: 4)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct OnboardingView: View {
    @EnvironmentObject private var appState: AppState
    @State private var language: AppLanguage = .korean
    @State private var apiKey = ""
    @State private var showsAPIKey = false
    @State private var topic = ""
    @State private var difficultyLevel = Difficulty.beginner.level
    @State private var intervalMinutes = 15
    @State private var didSeedFields = false
    @State private var isCompleting = false

    private var strings: AppStrings {
        AppStrings(language: language)
    }

    private var canStart: Bool {
        !isCompleting
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "book.pages.fill")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(Color.accentColor)

                    Text(strings.onboardingTitle)
                        .font(.system(size: 24, weight: .bold))
                }

                Text(strings.onboardingSubtitle)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                Text(strings.onboardingFreeNote)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 28)
            .padding(.top, 28)
            .padding(.bottom, 18)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    OnboardingSection(title: strings.onboardingLanguage) {
                        Picker(strings.appLanguage, selection: $language) {
                            ForEach(AppLanguage.allCases) { language in
                                Text(language.displayName).tag(language)
                            }
                        }
                        .pickerStyle(.segmented)

                        Text(strings.appLanguageHelp)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    OnboardingSection(title: strings.onboardingOpenAI) {
                        HStack(spacing: 8) {
                            Group {
                                if showsAPIKey {
                                    TextField("", text: $apiKey)
                                } else {
                                    SecureField("", text: $apiKey)
                                }
                            }
                            .textFieldStyle(.roundedBorder)

                            Button(strings.paste) {
                                Task {
                                    if let key = await appState.readClipboardOpenAIAPIKeyForSettingsPaste() {
                                        apiKey = key
                                    }
                                }
                            }

                            Button(showsAPIKey ? strings.hide : strings.show) {
                                showsAPIKey.toggle()
                            }
                            .frame(width: 56)
                        }

                        Text(strings.onboardingAPIKeyHelp)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        HStack(spacing: 4) {
                            Text(strings.onboardingCreateAPIKeyHelp)
                                .font(.caption)

                            Link(strings.onboardingCreateAPIKeyAction, destination: URL(string: "https://platform.openai.com/settings/organization/api-keys")!)
                                .font(.caption)
                        }
                    }

                    OnboardingSection(title: strings.onboardingStudySetup) {
                        LabeledContent(strings.studyTopic) {
                            TextField("", text: $topic)
                                .textFieldStyle(.roundedBorder)
                                .frame(maxWidth: 260)
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(strings.difficulty)
                                Spacer()
                                Text(Difficulty(level: difficultyLevel).displayName(language: language))
                                    .foregroundStyle(.secondary)
                            }

                            Slider(
                                value: Binding(
                                    get: { Double(difficultyLevel) },
                                    set: { difficultyLevel = Int($0.rounded()) }
                                ),
                                in: 1...10,
                                step: 1
                            )

                            Text(strings.difficultyScaleHint)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Stepper(
                            strings.questionInterval(minutes: intervalMinutes),
                            value: $intervalMinutes,
                            in: 1...240
                        )
                    }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 22)
            }

            Divider()

            HStack(spacing: 10) {
                Button(strings.onboardingSkip) {
                    appState.skipOnboarding()
                }

                Spacer()

                Button {
                    Task {
                        isCompleting = true
                        await appState.completeOnboarding(
                            settings: pendingSettings,
                            apiKey: apiKey
                        )
                        isCompleting = false
                    }
                } label: {
                    if isCompleting || appState.isValidatingAPIKey {
                        HStack(spacing: 6) {
                            ProgressView()
                                .controlSize(.small)
                            Text(strings.checking)
                        }
                    } else {
                        Text(strings.onboardingStart)
                    }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!canStart)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 18)
        }
        .onAppear(perform: seedFieldsIfNeeded)
    }

    private var pendingSettings: StudySettings {
        let trimmedTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedTopic = trimmedTopic.isEmpty ? StudySettings.fallbackTopic(for: language) : trimmedTopic

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

    private func seedFieldsIfNeeded() {
        guard !didSeedFields else {
            return
        }

        language = appState.settings.appLanguage
        apiKey = appState.apiKey
        let fallbackTopic = StudySettings.fallbackTopic(for: language)
        topic = appState.settings.topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? fallbackTopic
            : appState.settings.topic
        difficultyLevel = appState.settings.difficulty.level
        intervalMinutes = appState.settings.sanitizedIntervalMinutes
        didSeedFields = true
    }
}

private struct OnboardingSection<Content: View>: View {
    var title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.headline)

            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension View {
    func contentPadding() -> some View {
        padding(.leading, 12)
            .padding(.trailing, 18)
            .padding(.top, 18)
            .padding(.bottom, 16)
    }
}
