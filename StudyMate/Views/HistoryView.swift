import SwiftUI
#if os(iOS)
import UIKit
#endif

struct HistoryView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedRecordID: String?
    @State private var searchText = ""
    @State private var visibleCount = 0
    @State private var showsRecordSettings = false
    @State private var isRefreshing = false
    @State private var isSearchVisible = false
    @State private var searchFocusTask: Task<Void, Never>?
    @State private var recordSearchDebounceTask: Task<Void, Never>?
    @FocusState private var isSearchFocused: Bool

    private let pageSize = 10

    private var orderedRecords: [StudyRecord] {
        recordsSource
            .filter { $0.gradingResult != nil }
            .sorted { sortDate(for: $0) > sortDate(for: $1) }
    }

    private var recordsSource: [StudyRecord] {
        if !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let searchResults = appState.recordSearchResults {
            return searchResults
        }
        return appState.studyRecords
    }

    private var filteredRecords: [StudyRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else {
            return orderedRecords
        }
        if appState.recordSearchResults != nil {
            return orderedRecords
        }

        return orderedRecords.filter { record in
            record.question.question.lowercased().contains(query) ||
                record.topic.lowercased().contains(query) ||
                (record.answer ?? "").lowercased().contains(query) ||
                record.difficulty.displayName(language: appState.settings.appLanguage).lowercased().contains(query)
        }
    }

    private var visibleRecords: [StudyRecord] {
        let clamped = min(max(visibleCount, 0), filteredRecords.count)
        return Array(filteredRecords.prefix(clamped))
    }

    private var hasMoreRecords: Bool {
        visibleRecords.count < filteredRecords.count
    }

    private func resetVisibleCount() {
        visibleCount = min(pageSize, max(filteredRecords.count, 0))
    }

    private func reconcileVisibleCount() {
        let total = filteredRecords.count
        if total == 0 {
            visibleCount = 0
            return
        }

        let minimumVisible = min(pageSize, total)
        visibleCount = min(max(visibleCount, minimumVisible), total)
    }

    private func ensureVisibleCount(atLeast minimum: Int) {
        let clampedMinimum = min(max(minimum, 1), max(filteredRecords.count, 0))
        if visibleCount < clampedMinimum {
            visibleCount = clampedMinimum
        }
    }

    var body: some View {
        let strings = appState.strings
        let displayedRecords = filteredRecords
        let displayedVisibleRecords = visibleRecords

        VStack(spacing: 0) {
            MobileRootLargeTitle(strings.tabRecords)
                .padding(.top, 6)
                .padding(.bottom, 8)

            ScrollView {
                LazyVStack(spacing: 8) {
                    if orderedRecords.isEmpty {
                        ContentUnavailableView(
                            strings.noRecords,
                            systemImage: "clock.arrow.circlepath",
                            description: Text(strings.noRecordsDescription)
                        )
                        .frame(maxWidth: .infinity, minHeight: 360)
                    } else {
                        Text(strings.filteredRecordCount(displayedVisibleRecords.count, total: displayedRecords.count))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        if displayedRecords.isEmpty {
                            ContentUnavailableView(
                                strings.noSearchResults,
                                systemImage: "magnifyingglass",
                                description: Text(strings.noSearchResultsDescription)
                            )
                            .frame(maxWidth: .infinity, minHeight: 320)
                        } else {
                            ForEach(displayedVisibleRecords) { record in
                                HistoryRow(
                                    record: record,
                                    strings: strings,
                                    isSelected: selectedRecordID == record.id
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    selectedRecordID = record.id
                                }
                                .contextMenu {
                                    if appState.isCommunitySessionActive {
                                        Button {
                                            appState.updateStudyRecordPublicity(record, isPublic: !record.isPublic)
                                        } label: {
                                            Label(
                                                record.isPublic ? strings.makeQuestionPrivate : strings.makeQuestionPublic,
                                                systemImage: record.isPublic ? "lock.fill" : "globe"
                                            )
                                        }
                                    }

                                    Button(role: .destructive) {
                                        delete(record)
                                    } label: {
                                        Label(strings.clear, systemImage: "trash")
                                    }
                                }
                                .onAppear {
                                    loadNextPageIfNeeded(for: record)
                                }
                            }

                            if hasMoreRecords {
                                HStack {
                                    Spacer()

                                    ProgressView()
                                        .controlSize(.small)

                                    Spacer()
                                }
                                .padding(.vertical, 8)
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)
            .refreshable {
                await refreshRecords()
            }
            .searchSafeRefreshControlOffset()
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .mobileToolbarSearchable(
            isPresented: isSearchVisible || !searchText.isEmpty,
            text: $searchText,
            prompt: strings.searchRecords,
            focus: $isSearchFocused
        )
        .toolbar {
            #if os(iOS)
            if #available(iOS 26.0, *) {
                if !isRecordSearchActive {
                    ToolbarItem(placement: .topBarTrailing) {
                        recordSettingsToolbarControl(strings: strings)
                    }
                    .sharedBackgroundVisibility(.hidden)
                }

                ToolbarItem(placement: .topBarTrailing) {
                    recordToolbarSearchControl(strings: strings)
                }
                .sharedBackgroundVisibility(isRecordSearchActive ? .hidden : .automatic)
            } else {
                if !isRecordSearchActive {
                    ToolbarItem(placement: .topBarTrailing) {
                        recordSettingsToolbarControl(strings: strings)
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    recordToolbarSearchControl(strings: strings)
                }
            }
            #else
            ToolbarItemGroup(placement: .primaryAction) {
                recordToolbarItems(strings: strings)
            }
            #endif
        }
        .onChange(of: appState.studyRecords.count) {
            reconcileVisibleCount()
        }
        .onChange(of: searchText) {
            resetVisibleCount()
            selectedRecordID = nil
            appState.clearBackendRecordSearchResults()
        }
        .onChange(of: appState.focusedRecordRequest) {
            showFocusedRecord()
        }
        .onAppear {
            resetVisibleCount()
            if appState.focusedRecordRequest != nil {
                showFocusedRecord()
            }
            Task {
                await refreshRecords()
            }
        }
        .onChange(of: isSearchFocused) { _, isFocused in
            guard !isFocused,
                  searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return
            }

            closeRecordSearch(clearText: false)
        }
        .onDisappear {
            recordSearchDebounceTask?.cancel()
            recordSearchDebounceTask = nil
            searchFocusTask?.cancel()
            searchFocusTask = nil
        }
        .sheet(isPresented: $showsRecordSettings) {
            RecordSettingsSheet()
        }
        #if os(iOS)
        .navigationDestination(item: $selectedRecordID) { recordID in
            recordDetailDestination(recordID: recordID, strings: strings)
        }
        #else
        .sheet(isPresented: selectedRecordSheetBinding) {
            if let recordID = selectedRecordID {
                recordDetailDestination(recordID: recordID, strings: strings)
                    .frame(width: 460, height: 620)
                    .padding()
            }
        }
        #endif
    }

    private var isRecordSearchActive: Bool {
        isSearchVisible || !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func recordToolbarSearchControl(strings: AppStrings) -> some View {
        #if os(iOS)
        MobileExpandingToolbarSearch(
            isExpanded: isRecordSearchActive,
            text: $searchText,
            prompt: strings.searchRecords,
            focus: $isSearchFocused,
            closeAccessibilityLabel: strings.clearSearch,
            width: min(UIScreen.main.bounds.width - 32, 430),
            collapsedWidth: 34,
            onSubmit: {
                submitRecordSearch()
            },
            onClose: {
                closeRecordSearch(clearText: true)
            }
        ) {
            recordSearchToolbarButton(strings: strings)
        }
        #else
        TextField(strings.searchRecords, text: $searchText)
            .textFieldStyle(.roundedBorder)
            .frame(width: 220)
        #endif
    }

    @ViewBuilder
    private func recordToolbarItems(strings: AppStrings) -> some View {
        HStack(spacing: 16) {
            recordSearchToolbarButton(strings: strings)
            recordSettingsToolbarControl(strings: strings)
        }
        .fixedSize()
    }

    private func recordSearchToolbarButton(strings: AppStrings) -> some View {
        Button {
            showRecordSearch()
        } label: {
            #if os(iOS)
            MobileToolbarIconButtonLabel(systemName: "magnifyingglass")
            #else
            Image(systemName: "magnifyingglass")
            #endif
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.search)
    }

    @ViewBuilder
    private func recordSettingsToolbarControl(strings: AppStrings) -> some View {
        Menu {
            Button {
                showsRecordSettings = true
            } label: {
                Label(strings.recordSettings, systemImage: "slider.horizontal.3")
            }
        } label: {
            #if os(iOS)
            MobileToolbarIconButtonLabel(systemName: "ellipsis")
            #else
            Image(systemName: "ellipsis")
            #endif
        }
        .accessibilityLabel(strings.recordSettings)
    }

    @MainActor
    private func showRecordSearch() {
        if isSearchVisible || !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            isSearchFocused = false
            closeRecordSearch(clearText: true)
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
    private func closeRecordSearch(clearText: Bool) {
        searchFocusTask?.cancel()
        searchFocusTask = nil
        isSearchFocused = false

        if clearText {
            searchText = ""
            appState.clearBackendRecordSearchResults()
        }

        withAnimation(.smooth(duration: 0.22)) {
            isSearchVisible = false
        }
    }

    private func loadNextPageIfNeeded(for record: StudyRecord) {
        guard hasMoreRecords else {
            return
        }
        guard let index = filteredRecords.firstIndex(where: { $0.id == record.id }) else {
            return
        }
        guard !isRefreshing else {
            return
        }
        if index >= max(visibleRecords.count - 2, 0) {
            visibleCount = min(visibleRecords.count + pageSize, filteredRecords.count)
        }
    }

    private func submitRecordSearch() {
        recordSearchDebounceTask?.cancel()
        let query = searchText
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            appState.clearBackendRecordSearchResults()
            return
        }

        recordSearchDebounceTask = Task { @MainActor in
            guard !Task.isCancelled else {
                return
            }
            await appState.searchBackendRecords(query: query)
            resetVisibleCount()
        }
    }

    private func sortDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }

    private func showFocusedRecord() {
        guard let request = appState.focusedRecordRequest,
              let index = orderedRecords.firstIndex(where: { $0.id == request.recordID }) else {
            return
        }

        searchText = ""
        ensureVisibleCount(atLeast: index + 1)
        selectedRecordID = request.recordID
    }

    @ViewBuilder
    private func recordDetailDestination(recordID: String, strings: AppStrings) -> some View {
        if let record = record(for: recordID) {
            #if os(iOS)
            if let question = record.asCommunityQuestion(author: appState.communityProfile) {
                CommunityQuestionDetailView(question: question)
                    .navigationTitle(strings.browseQuestions)
                    .navigationBarTitleDisplayMode(.inline)
            } else {
                StudyRecordDetailView(record: record)
                    .padding(.horizontal, 16)
                    .navigationTitle(strings.recordDetail)
                    .navigationBarTitleDisplayMode(.inline)
            }
            #else
            StudyRecordDetailView(record: record)
                .padding(.horizontal, 16)
                .navigationTitle(strings.recordDetail)
            #endif
        } else {
            ContentUnavailableView(
                strings.notificationQuestionMissingTitle,
                systemImage: "trash",
                description: Text(strings.notificationQuestionUnavailableHelp)
            )
            .navigationTitle(strings.recordDetail)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
    }

    private func record(for recordID: String) -> StudyRecord? {
        appState.studyRecords.first { $0.id == recordID } ??
            orderedRecords.first { $0.id == recordID }
    }

    private var selectedRecordSheetBinding: Binding<Bool> {
        Binding(
            get: { selectedRecordID != nil },
            set: { isPresented in
                if !isPresented {
                    selectedRecordID = nil
                }
            }
        )
    }

    private func refreshRecords() async {
        guard !isRefreshing else {
            return
        }

        await MainActor.run {
            isRefreshing = true
        }

        await appState.refreshBackendRecords()

        await MainActor.run {
            reconcileVisibleCount()
            if appState.focusedRecordRequest != nil {
                showFocusedRecord()
            }
            isRefreshing = false
        }
    }

    private func delete(_ record: StudyRecord) {
        withAnimation(.easeOut(duration: 0.22)) {
            appState.deleteStudyRecord(record)
            reconcileVisibleCount()
            if selectedRecordID == record.id {
                selectedRecordID = nil
            }
        }
    }
}

private struct RecordSettingsSheet: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var showsDeleteConfirmation = false

    var body: some View {
        let strings = appState.settingsEditorStrings

        NavigationStack {
            Form {
                Section {
                    Stepper(
                        "\(strings.maxRecordCount): \(appState.draftSettings.sanitizedMaxHistoryCount)",
                        value: $appState.draftSettings.maxHistoryCount,
                        in: 10...10_000,
                        step: 100
                    )

                    TextField(
                        "100",
                        value: $appState.draftSettings.maxHistoryCount,
                        format: .number
                    )
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
                } footer: {
                    Text(strings.recordLimitHelp(limit: appState.draftSettings.sanitizedMaxHistoryCount, count: appState.studyRecords.count))
                }

                Section {
                    Button(role: .destructive) {
                        showsDeleteConfirmation = true
                    } label: {
                        Label(strings.deleteRecords, systemImage: "trash")
                    }
                    .disabled(appState.studyRecords.isEmpty)
                } footer: {
                    Text(strings.deleteRecordsHelp)
                }
            }
            .keyboardDoneToolbar(strings.done)
            .navigationTitle(strings.recordSettings)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(strings.cancel) {
                        appState.cancelSettingsEditing()
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.save) {
                        appState.saveSettings()
                        dismiss()
                    }
                }
            }
            .confirmationDialog(strings.deleteRecords, isPresented: $showsDeleteConfirmation) {
                Button(strings.deleteRecords, role: .destructive) {
                    appState.clearStudyRecords()
                }
            } message: {
                Text(strings.deleteRecordsHelp)
            }
            .onAppear {
                appState.beginSettingsEditing()
            }
            .onDisappear {
                appState.cancelSettingsEditing()
            }
        }
    }
}

private struct HistoryRow: View {
    var record: StudyRecord
    var strings: AppStrings
    var isSelected: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.75) : Color.clear)
                .frame(width: 4, height: 42)

            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline) {
                    HStack(spacing: 6) {
                        if !record.isPublic {
                            Image(systemName: "lock.fill")
                                .font(.caption2.weight(.semibold))
                                .accessibilityLabel(strings.makeQuestionPrivate)
                        }

                        Text(record.topic.isEmpty ? strings.studyFallback : record.topic)
                            .lineLimit(1)

                        Text("·")

                        Text(record.difficulty.displayName(language: strings.language))
                            .lineLimit(1)

                        Text("·")

                        Text(record.question.createdAt, formatter: Self.dateFormatter)
                            .lineLimit(1)
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)

                    Spacer(minLength: 8)

                    if let result = record.gradingResult {
                        Text("\(result.score)/100")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(scoreColor(result.score))
                            .lineLimit(1)
                    } else {
                        Text(strings.ungraded)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Text(record.question.question)
                    .font(.body)
                    .lineLimit(2)

                RecordStatsMeta(record: record)
            }

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.secondary.opacity(0.1) : Color.secondary.opacity(0.055))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(isSelected ? Color.secondary.opacity(0.18) : Color.clear, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .textSelection(.disabled)
    }

    private func scoreColor(_ score: Int) -> Color {
        switch score {
        case 70...100:
            .green
        case 40..<70:
            .orange
        default:
            .red
        }
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter
    }()
}

private struct RecordStatsMeta: View {
    var record: StudyRecord

    var body: some View {
        HStack(spacing: 10) {
            Label("\(record.viewCount)", systemImage: "eye")
            Label("\(record.commentCount)", systemImage: "bubble.right")
            Label("\(record.likeCount)", systemImage: "heart")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
}
