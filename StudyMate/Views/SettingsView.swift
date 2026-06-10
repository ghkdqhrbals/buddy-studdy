import SwiftUI
#if os(macOS)
import AppKit
#endif

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selection: SettingsCategory = .study
    @State private var debugPanelOffset: CGSize = .zero

    private var visibleCategories: [SettingsCategory] {
        SettingsCategory.visible
    }

    var body: some View {
        let strings = appState.settingsEditorStrings

        HStack(spacing: 0) {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(visibleCategories) { category in
                        Button {
                            selection = category
                        } label: {
                            HStack(spacing: 8) {
                                Text(category.title(strings: strings))
                                Spacer(minLength: 0)
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color.primary)
                            .padding(.horizontal, 12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .frame(height: 32)
                            .background(selection == category ? Color.secondary.opacity(0.14) : Color.clear)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 12)

                Spacer()
            }
            .frame(width: 136)
            .background(Color(nsColor: .controlBackgroundColor))

            Divider()

            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        switch selection {
                        case .general:
                            GeneralSettingsSection()

                        case .secrets:
                            SecretsSettingsSection()

                        case .study:
                            StudySettingsSection()
                        }
                    }
                    .padding(.leading, 20)
                    .padding(.trailing, 28)
                    .padding(.top, 20)
                    .padding(.bottom, 28)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                }

                Divider()

                HStack(spacing: 12) {
                    Spacer()

                    Button {
                        Task {
                            await appState.saveSettingsAndValidateAPIKey()
                        }
                    } label: {
                        if appState.isValidatingAPIKey {
                            HStack(spacing: 6) {
                                ProgressView()
                                    .controlSize(.small)
                                Text(strings.checking)
                            }
                        } else if appState.hasUnsavedSettingsChanges {
                            Text(strings.save)
                        } else {
                            Text(strings.saved)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(appState.hasUnsavedSettingsChanges ? Color.accentColor : Color.gray.opacity(0.6))
                    .keyboardShortcut(.defaultAction)
                    .disabled(appState.isValidatingAPIKey)
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 20)
            }
        }
        .overlay(alignment: .center) {
            if appState.isAPIDebugPanelPresented {
                APIDebugPanel(
                    logs: appState.apiTrafficLogs,
                    isPresented: $appState.isAPIDebugPanelPresented
                )
                .offset(debugPanelOffset)
            }
        }
        .contentShape(Rectangle())
        .zIndex(1000)
        .highPriorityGesture(
            LongPressGesture(minimumDuration: 0.75)
                .onEnded { _ in
                    appState.requestDebugPanelIfEnabledOrEnableOnDemand()
                }
        )
        .onAppear {
            appState.beginSettingsEditing()
        }
        .onDisappear {
            appState.cancelSettingsEditing()
        }
    }
}

private struct APIDebugPanel: View {
    let logs: [APITrafficLogEntry]
    @Binding var isPresented: Bool
    @EnvironmentObject private var appState: AppState

    @State private var dragOffset: CGSize = .zero
    @State private var accumulatedOffset: CGSize = .zero

    var body: some View {
        let strings = appState.settingsEditorStrings
        VStack(spacing: 10) {
            HStack {
                Text(strings.apiDebugWindowTitle)
                    .font(.headline)
                Spacer()
                Button(role: .cancel) {
                    isPresented = false
                } label: {
                    Text("✕")
                        .font(.title3)
                }
                .buttonStyle(.plain)
            }
            Divider()

            if logs.isEmpty {
                ContentUnavailableView(strings.noLogs, systemImage: "wifi.slash", description: Text(strings.noLogsDescription))
                    .frame(height: 120)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(logs) { log in
                            APITrafficRow(entry: log)
                        }
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: 620, maxHeight: 420)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color(nsColor: .windowBackgroundColor))
                .shadow(radius: 12)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.secondary.opacity(0.2))
        )
        .padding()
        .offset(x: dragOffset.width + accumulatedOffset.width, y: dragOffset.height + accumulatedOffset.height)
        .gesture(
            DragGesture()
                .onChanged { value in
                    dragOffset = value.translation
                }
                .onEnded { value in
                    accumulatedOffset.width += value.translation.width
                    accumulatedOffset.height += value.translation.height
                    dragOffset = .zero
                }
        )
        .onTapGesture {}
    }

    private struct APITrafficRow: View {
        let entry: APITrafficLogEntry

        var body: some View {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(timeText)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
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
                        .textSelection(.enabled)
                }

                if !entry.requestBody.isEmpty {
                    Text("Request: \(entry.requestBody)")
                        .font(.caption2)
                        .foregroundStyle(.primary)
                        .lineLimit(4)
                        .textSelection(.enabled)
                }

                if !entry.responseBody.isEmpty {
                    Text("Response: \(entry.responseBody)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                        .textSelection(.enabled)
                }

                if let error = entry.error {
                    Text("Error: \(error)")
                        .font(.caption2)
                        .foregroundStyle(.red)
                }
            }
            .padding(.vertical, 4)
            .padding(.horizontal, 8)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(entry.isError ? Color.red.opacity(0.08) : Color.secondary.opacity(0.06))
            )
        }

        private var timeText: String {
            entry.createdAt.formatted(date: .omitted, time: .standard)
        }
    }
}

private enum SettingsCategory: String, CaseIterable, Identifiable {
    case general
    case secrets
    case study

    var id: String { rawValue }

    static var visible: [SettingsCategory] {
        [.study, .general, .secrets]
    }

    func title(strings: AppStrings) -> String {
        switch self {
        case .general:
            strings.general
        case .secrets:
            strings.secrets
        case .study:
            strings.study
        }
    }
}

private struct SettingsPanel<Content: View>: View {
    var title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)

            content
        }
        .frame(maxWidth: 440, alignment: .leading)
    }
}

private struct GeneralSettingsSection: View {
    @EnvironmentObject private var appState: AppState
    @ObservedObject private var updateService = UpdateService.shared
    @State private var showsUninstallConfirmation = false

    var body: some View {
        let strings = appState.settingsEditorStrings

        SettingsPanel(title: strings.generalSettings) {
            Picker(
                strings.appLanguage,
                selection: Binding(
                    get: { appState.draftSettings.appLanguage },
                    set: { appState.updateDraftAppLanguage($0) }
                )
            ) {
                ForEach(AppLanguage.allCases) { language in
                    Text(appState.draftSettings.appLanguage == language ? "✓ \(language.displayName)" : language.displayName)
                        .tag(language)
                }
            }
            .pickerStyle(.menu)

            Text(strings.appLanguageHelp)
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(strings.notifications)
                .font(.subheadline)
                .fontWeight(.semibold)

            Button {
                appState.openSystemNotificationSettings()
            } label: {
                Label(strings.openNotificationSettings, systemImage: "bell.badge")
            }

            Text(strings.notificationPermissionHelp)
                .font(.caption)
                .foregroundStyle(.secondary)

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
            .pickerStyle(.menu)

            Text(strings.notificationSoundHelp)
                .font(.caption)
                .foregroundStyle(.secondary)

            Divider()

            Text(strings.updates)
                .font(.subheadline)
                .fontWeight(.semibold)

            if !updateService.canUseUpdates {
                Text(strings.updateInstallHelp)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Toggle(
                strings.automaticallyCheckForUpdates,
                isOn: Binding(
                    get: { updateService.automaticallyChecksForUpdates },
                    set: { updateService.setAutomaticallyChecksForUpdates($0) }
                )
            )
            .disabled(!updateService.canUseUpdates)

            Toggle(
                strings.automaticallyDownloadUpdates,
                isOn: Binding(
                    get: { updateService.automaticallyDownloadsUpdates },
                    set: { updateService.setAutomaticallyDownloadsUpdates($0) }
                )
            )
            .disabled(!updateService.canUseUpdates || !updateService.automaticallyChecksForUpdates)

            Button {
                updateService.checkForUpdates()
            } label: {
                Label(strings.checkForUpdates, systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(!updateService.canUseUpdates || !updateService.canCheckForUpdates)

            Text(strings.updateHelp)
                .font(.caption)
                .foregroundStyle(.secondary)

            Divider()

            Button(role: .destructive) {
                showsUninstallConfirmation = true
            } label: {
                Label(strings.uninstall, systemImage: "trash")
            }

            Text(strings.uninstallHelp)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .confirmationDialog(
            strings.uninstallConfirmationTitle,
            isPresented: $showsUninstallConfirmation,
            titleVisibility: .visible
        ) {
            Button(strings.uninstall, role: .destructive) {
                appState.uninstallApplication()
            }
        } message: {
            Text(strings.uninstallConfirmationMessage)
        }
    }
}

private struct SecretsSettingsSection: View {
    @EnvironmentObject private var appState: AppState
    @State private var showsAPIKey = false

    var body: some View {
        let strings = appState.settingsEditorStrings

            SettingsPanel(title: "OpenAI") {
                VStack(alignment: .leading, spacing: 8) {
                Text(strings.openAIAPIKey)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack(spacing: 8) {
                    Group {
                        if showsAPIKey {
                            TextField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                        } else {
                            SecureField(strings.openAIAPIKey, text: $appState.draftAPIKey)
                        }
                    }
                    .textFieldStyle(.roundedBorder)

                    Button {
                        showsAPIKey.toggle()
                    } label: {
                        Image(systemName: showsAPIKey ? "eye.slash" : "eye")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showsAPIKey ? strings.hide : strings.show)

                    Button(strings.paste) {
                        appState.applyClipboardOpenAIAPIKey()
                    }
                }

                if let validationMessage = appState.apiKeyValidationMessage {
                    Text(validationMessage)
                        .font(.caption2)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text(strings.openAIModel)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Picker(strings.openAIModel, selection: $appState.draftSettings.openAIModel) {
                    ForEach(appState.openAIModelOptions.isEmpty ? OpenAIModelOption.all : appState.openAIModelOptions) { option in
                        Text(option.displayName).tag(option.id)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)

            }

            Divider()

            VStack(alignment: .leading, spacing: 10) {
                Text(strings.openAIBilling)
                    .font(.subheadline)
                    .fontWeight(.semibold)

                HStack(spacing: 12) {
                    Button {
                        appState.openOpenAIUsageDashboardPage()
                    } label: {
                        Text(strings.openAIUsageAndCostsPage)
                            .foregroundStyle(.blue)
                    }
                    .buttonStyle(.plain)
                    .help(strings.openAIUsageAndCostsPage)

                    Button {
                        appState.openOpenAIBillingPage()
                    } label: {
                        Text(strings.openAIBillingPage)
                            .foregroundStyle(.blue)
                    }
                    .buttonStyle(.plain)
                    .help(strings.openAIBillingPage)
                }
            }
        }
    }
}

private struct StudySettingsSection: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        let strings = appState.settingsEditorStrings

        SettingsPanel(title: strings.studySettings) {
            VStack(alignment: .leading, spacing: 6) {
                Text(strings.currentStudyCategory)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(appState.draftSettings.topic.isEmpty ? strings.studyFallback : appState.draftSettings.topic)
                    .font(.headline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 8))

                Button {
                    appState.selectedTab = .home
                } label: {
                    Label(strings.editInHome, systemImage: "list.bullet.rectangle")
                }
                .buttonStyle(.borderless)
            }

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(strings.difficulty)
                    Spacer()
                    Stepper(
                        value: Binding(
                            get: { appState.draftSettings.difficulty.level },
                            set: { appState.draftSettings.difficulty = Difficulty(level: $0) }
                        ),
                        in: 1...10
                    ) {
                        Text(appState.draftSettings.difficulty.displayName(language: appState.draftSettings.appLanguage))
                            .fontWeight(.semibold)
                            .monospacedDigit()
                    }
                }

                Slider(
                    value: Binding(
                        get: { Double(appState.draftSettings.difficulty.level) },
                        set: { appState.draftSettings.difficulty = Difficulty(level: Int($0.rounded())) }
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
                value: $appState.draftSettings.intervalMinutes,
                in: 1...240,
                step: 1
            ) {
                Text(strings.questionInterval(minutes: appState.draftSettings.sanitizedIntervalMinutes))
            }

            Menu {
                ForEach(RecommendedPrompt.allCases) { prompt in
                    Button(prompt.title(language: appState.draftSettings.appLanguage)) {
                        appState.draftSettings.customPrompt = prompt.text(language: appState.draftSettings.appLanguage)
                    }
                }
            } label: {
                Label(strings.recommendedPrompt, systemImage: "sparkles")
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(strings.relatedPrompt)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextEditor(text: $appState.draftSettings.customPrompt)
                    .frame(minHeight: 150)
                    .padding(6)
                    .overlay {
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.24))
                    }
            }
        }
    }
}

private struct RecordsSettingsSection: View {
    @EnvironmentObject private var appState: AppState
    @State private var showsDeleteConfirmation = false

    var body: some View {
        let strings = appState.settingsEditorStrings

        SettingsPanel(title: strings.records) {
            HStack(spacing: 10) {
                Text(strings.maxRecordCount)

                TextField(
                    "100",
                    value: $appState.draftSettings.maxHistoryCount,
                    format: .number
                )
                .textFieldStyle(.roundedBorder)
                .frame(width: 80)

                Stepper(
                    "",
                    value: $appState.draftSettings.maxHistoryCount,
                    in: 10...10_000,
                    step: 100
                )
                .labelsHidden()

                Text(strings.countUnit)
                    .foregroundStyle(.secondary)
            }

            Text(strings.recordLimitHelp(limit: appState.draftSettings.sanitizedMaxHistoryCount, count: appState.studyRecords.count))
                .font(.caption)
                .foregroundStyle(.secondary)

            Divider()

            Button(role: .destructive) {
                showsDeleteConfirmation = true
            } label: {
                Label(strings.deleteRecords, systemImage: "trash")
            }
            .disabled(appState.studyRecords.isEmpty)

            Text(strings.deleteRecordsHelp)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .confirmationDialog(strings.deleteRecords, isPresented: $showsDeleteConfirmation) {
            Button(strings.deleteRecords, role: .destructive) {
                appState.clearStudyRecords()
            }
        }
    }
}

private struct LogRow: View {
    var entry: AppLogEntry

    var body: some View {
        Text(lineText)
            .font(.system(size: 10.5, weight: .regular, design: .monospaced))
            .foregroundStyle(color)
            .lineSpacing(0)
            .lineLimit(1)
            .truncationMode(.middle)
            .textSelection(.enabled)
            .help(entry.message)
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
