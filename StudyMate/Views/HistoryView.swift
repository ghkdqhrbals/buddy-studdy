import SwiftUI

struct HistoryView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedRecordID: String?
    @State private var openSwipeRecordID: String?
    @State private var searchText = ""
    @State private var visibleCount = 0
    @State private var showsRecordSettings = false

    private let pageSize = 10

    private var orderedRecords: [StudyRecord] {
        appState.studyRecords.sorted { lhs, rhs in
            let lhsIsUngraded = lhs.gradingResult == nil
            let rhsIsUngraded = rhs.gradingResult == nil

            if lhsIsUngraded != rhsIsUngraded {
                return lhsIsUngraded
            }

            return sortDate(for: lhs) > sortDate(for: rhs)
        }
    }

    private var filteredRecords: [StudyRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else {
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

        VStack(alignment: .leading, spacing: 12) {
            if appState.studyRecords.isEmpty {
                ContentUnavailableView(
                    strings.noRecords,
                    systemImage: "clock.arrow.circlepath",
                    description: Text(strings.noRecordsDescription)
                )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(displayedVisibleRecords) { record in
                            VStack(alignment: .leading, spacing: 8) {
                                HistoryRow(record: record, strings: strings, isSelected: selectedRecordID == record.id)
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                    selectedRecordID = selectedRecordID == record.id ? nil : record.id
                                }

                                if selectedRecordID == record.id {
                                    InlineStudyRecordDetail(record: record)
                                    .transition(.opacity.combined(with: .move(edge: .top)))
                                }
                            }
                            .onAppear {
                                loadNextPageIfNeeded(for: record)
                            }
                            .listRowInsets(EdgeInsets(top: 5, leading: 0, bottom: 5, trailing: 0))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    delete(record)
                                } label: {
                                    Label(strings.clear, systemImage: "trash")
                                }
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
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .frame(maxHeight: .infinity)
                    .refreshable {
                        await appState.refreshVisibleData()
                    }
                }
            }
        }
        .padding(.top, 10)
        .frame(maxHeight: .infinity, alignment: .top)
        .searchable(text: $searchText, prompt: strings.searchRecords)
        .onChange(of: appState.studyRecords.count) {
            reconcileVisibleCount()
            if let openSwipeRecordID,
               !appState.studyRecords.contains(where: { $0.id == openSwipeRecordID }) {
                self.openSwipeRecordID = nil
            }
        }
        .onChange(of: searchText) {
            resetVisibleCount()
            selectedRecordID = nil
            openSwipeRecordID = nil
        }
        .onChange(of: appState.focusedRecordRequest) {
            showFocusedRecord()
        }
        .onAppear {
            resetVisibleCount()
            if appState.focusedRecordRequest != nil {
                showFocusedRecord()
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        showsRecordSettings = true
                    } label: {
                        Label(strings.recordSettings, systemImage: "slider.horizontal.3")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(strings.recordSettings)
            }
        }
        .sheet(isPresented: $showsRecordSettings) {
            RecordSettingsSheet()
        }
    }

    private func loadNextPageIfNeeded(for record: StudyRecord) {
        guard hasMoreRecords else {
            return
        }
        guard let index = filteredRecords.firstIndex(where: { $0.id == record.id }) else {
            return
        }
        if index >= max(visibleRecords.count - 2, 0) {
            withAnimation(.easeOut(duration: 0.2)) {
                visibleCount = min(visibleRecords.count + pageSize, filteredRecords.count)
            }
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

    private func closeOpenSwipe(animated: Bool) {
        guard openSwipeRecordID != nil else {
            return
        }

        if animated {
            withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.9)) {
                openSwipeRecordID = nil
            }
        } else {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                openSwipeRecordID = nil
            }
        }
    }

    private func delete(_ record: StudyRecord) {
        withAnimation(.easeOut(duration: 0.22)) {
            appState.deleteStudyRecord(record)
            reconcileVisibleCount()
            openSwipeRecordID = nil
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
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 6) {
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

                Spacer()

                if let result = record.gradingResult {
                    Text("\(result.score)/100")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(scoreColor(result.score))
                } else {
                    Text(strings.ungraded)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Text(record.question.question)
                .font(.body)
                .lineLimit(2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.08))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(isSelected ? Color.accentColor.opacity(0.45) : Color.clear, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
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

private struct InlineStudyRecordDetail: View {
    var record: StudyRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            StudyRecordDetailView(record: record)
                .frame(minHeight: 320)
        }
        .padding(12)
        .background {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.platformControlBackground)
        }
        .overlay(alignment: .topLeading) {
            Triangle()
                .fill(Color.platformControlBackground)
                .frame(width: 14, height: 8)
                .offset(x: 22, y: -7)
        }
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
        }
    }
}

private extension Color {
    static var platformControlBackground: Color {
        #if os(macOS)
        Color(nsColor: .controlBackgroundColor)
        #else
        Color(uiColor: .secondarySystemBackground)
        #endif
    }
}

private struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}
