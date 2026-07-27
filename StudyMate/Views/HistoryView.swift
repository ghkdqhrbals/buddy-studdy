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
    @State private var pendingRecordDeletion: StudyRecord?
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

    private var weeklyRecords: [StudyRecord] {
        guard let interval = Self.weekCalendar.dateInterval(of: .weekOfYear, for: Date()) else {
            return []
        }
        return appState.studyRecords.filter { record in
            guard record.gradingResult != nil else {
                return false
            }
            return interval.contains(sortDate(for: record))
        }
    }

    private var weeklyStudyDays: Int {
        Set(weeklyRecords.map { Self.weekCalendar.startOfDay(for: sortDate(for: $0)) }).count
    }

    private var weeklyAverageScore: Int {
        let scores = weeklyRecords.compactMap(\.gradingResult?.score)
        guard !scores.isEmpty else {
            return 0
        }
        return Int((Double(scores.reduce(0, +)) / Double(scores.count)).rounded())
    }

    private var weeklyActivityCounts: [Int] {
        guard let interval = Self.weekCalendar.dateInterval(of: .weekOfYear, for: Date()) else {
            return Array(repeating: 0, count: 7)
        }
        return weeklyRecords.reduce(into: Array(repeating: 0, count: 7)) { counts, record in
            let day = Self.weekCalendar.startOfDay(for: sortDate(for: record))
            let offset = Self.weekCalendar.dateComponents([.day], from: interval.start, to: day).day ?? 0
            guard counts.indices.contains(offset) else {
                return
            }
            counts[offset] += 1
        }
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
                LazyVStack(spacing: 12) {
                    if orderedRecords.isEmpty {
                        ContentUnavailableView(
                            strings.noRecords,
                            systemImage: "clock.arrow.circlepath",
                            description: Text(strings.noRecordsDescription)
                        )
                        .frame(maxWidth: .infinity, minHeight: 360)
                    } else {
                        if !isRecordSearchActive {
                            HistoryWeeklySummaryCard(
                                studyDays: weeklyStudyDays,
                                activityCount: weeklyRecords.count,
                                averageScore: weeklyAverageScore,
                                dailyCounts: weeklyActivityCounts,
                                strings: strings
                            )
                            .padding(.bottom, 8)
                        }

                        HStack(alignment: .firstTextBaseline) {
                            Text(strings.recentLearningRecords)
                                .font(.title3.weight(.bold))

                            Spacer()

                            Text(strings.filteredRecordCount(displayedVisibleRecords.count, total: displayedRecords.count))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

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
                .padding(.top, 8)
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
        .confirmationDialog(
            strings.deleteRecordHelp,
            isPresented: Binding(
                get: { pendingRecordDeletion != nil },
                set: { isPresented in
                    if !isPresented {
                        pendingRecordDeletion = nil
                    }
                }
            ),
            titleVisibility: .visible
        ) {
            if let pendingRecordDeletion {
                Button(strings.clear, role: .destructive) {
                    delete(pendingRecordDeletion)
                    self.pendingRecordDeletion = nil
                }
            }
            Button(strings.cancel, role: .cancel) {
                pendingRecordDeletion = nil
            }
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
            Group {
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
            }
            .toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarTrailing) {
                        recordDetailActionsMenu(record: record, strings: strings)
                    }
                    .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        recordDetailActionsMenu(record: record, strings: strings)
                    }
                }
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

    private func recordDetailActionsMenu(record: StudyRecord, strings: AppStrings) -> some View {
        Menu {
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
                pendingRecordDeletion = record
            } label: {
                Label(strings.clear, systemImage: "trash")
            }
        } label: {
            MobileToolbarIconButtonLabel(systemName: "ellipsis")
        }
        .accessibilityLabel(strings.more)
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

    private static var weekCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale.current
        calendar.timeZone = .current
        calendar.firstWeekday = 2
        return calendar
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
        HStack(alignment: .top, spacing: 12) {
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
                            .font(.title3.weight(.bold))
                            .foregroundStyle(scoreColor(result.score))
                            .lineLimit(1)
                    } else {
                        Text(strings.ungraded)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Text(MarkdownContent.plainText(record.question.question))
                    .font(.body.weight(.medium))
                    .lineLimit(2)

                RecordStatsMeta(record: record)
            }

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            isSelected ? Color.green.opacity(0.10) : Color(.secondarySystemBackground)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(isSelected ? Color.green.opacity(0.34) : Color.primary.opacity(0.04), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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

private struct HistoryWeeklySummaryCard: View {
    var studyDays: Int
    var activityCount: Int
    var averageScore: Int
    var dailyCounts: [Int]
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(strings.guestWeeklySummary)
                .font(.title3.weight(.bold))

            HStack(alignment: .bottom, spacing: 0) {
                HistoryWeeklyMetric(
                    title: strings.guestStudyDays,
                    value: "\(studyDays)",
                    suffix: strings.language == .korean ? "일" : ""
                )

                Divider()
                    .frame(height: 52)

                HistoryWeeklyMetric(
                    title: strings.guestActivities,
                    value: "\(activityCount)",
                    suffix: strings.language == .korean ? "회" : ""
                )

                Divider()
                    .frame(height: 52)

                HistoryWeeklyMetric(
                    title: strings.guestAverageScore,
                    value: "\(averageScore)",
                    suffix: strings.language == .korean ? "점" : ""
                )
            }

            HStack(alignment: .bottom, spacing: 12) {
                ForEach(Array(dailyCounts.enumerated()), id: \.offset) { index, count in
                    VStack(spacing: 8) {
                        Capsule()
                            .fill(count > 0 ? Color.green.opacity(0.82) : Color.secondary.opacity(0.13))
                            .frame(height: barHeight(for: count))

                        Text(weekdayLabel(at: index))
                            .font(.caption)
                            .foregroundStyle(isToday(index: index) ? Color.primary : Color.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .bottom)
                }
            }
            .frame(height: 72, alignment: .bottom)
        }
        .padding(20)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func barHeight(for count: Int) -> CGFloat {
        let maximum = max(dailyCounts.max() ?? 0, 1)
        guard count > 0 else {
            return 8
        }
        return 14 + (CGFloat(count) / CGFloat(maximum) * 34)
    }

    private func weekdayLabel(at index: Int) -> String {
        let korean = ["월", "화", "수", "목", "금", "토", "일"]
        let english = ["M", "T", "W", "T", "F", "S", "S"]
        return strings.language == .korean ? korean[index] : english[index]
    }

    private func isToday(index: Int) -> Bool {
        let weekday = Calendar.current.component(.weekday, from: Date())
        let mondayBasedIndex = (weekday + 5) % 7
        return mondayBasedIndex == index
    }
}

private struct HistoryWeeklyMetric: View {
    var title: String
    var value: String
    var suffix: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .monospacedDigit()

                if !suffix.isEmpty {
                    Text(suffix)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
    }
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
