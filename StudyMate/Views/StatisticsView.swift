import SwiftUI
#if os(iOS)
import UIKit
#endif

struct StatisticsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedRecord: StudyRecord?
    @State private var selectedPeriod: StatisticsPeriod = .all
    @State private var customStartDate = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
    @State private var customEndDate = Date()
    @State private var topicPage = 0
    @State private var isPullRefreshing = false

    private static let topicPageSize = 8

    private var totalTopicCount: Int {
        appState.backendStats?.totalTopics ?? topicStats.count
    }

    private var responseCount: Int {
        appState.backendStats?.totalResponses ?? 0
    }

    private var topicStats: [TopicStat] {
        (appState.backendStats?.topics ?? [])
            .compactMap(TopicStat.init(backend:))
    }

    private var topicPageCount: Int {
        max(1, (max(totalTopicCount, topicStats.count) + Self.topicPageSize - 1) / Self.topicPageSize)
    }

    private var boundedTopicPage: Int {
        min(max(topicPage, 0), topicPageCount - 1)
    }

    private var topicPageStartIndex: Int {
        boundedTopicPage * Self.topicPageSize
    }

    private var pagedTopicStats: [TopicStat] {
        Array(topicStats.dropFirst(topicPageStartIndex).prefix(Self.topicPageSize))
    }

    private var visibleStatRecords: [StudyRecord] {
        var seenIDs: Set<String> = []
        return topicStats
            .flatMap(\.records)
            .sorted { Self.statsDate(for: $0) > Self.statsDate(for: $1) }
            .filter { record in
                let id = record.id
                guard !seenIDs.contains(id) else {
                    return false
                }
                seenIDs.insert(id)
                return true
            }
    }

    var body: some View {
        let strings = appState.strings
        let count = responseCount
        let pageCount = topicPageCount

        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    MobileRootLargeTitle(strings.tabStatistics)
                        .padding(.top, 6)
                        .padding(.bottom, 8)

                    StatisticsPeriodControls(
                        selectedPeriod: $selectedPeriod,
                        customStartDate: $customStartDate,
                        customEndDate: $customEndDate,
                        strings: strings
                    )

                    if count > 0, let statsErrorMessage = appState.backendStatsErrorMessage {
                        Text(statsErrorMessage)
                            .font(.caption2)
                            .foregroundStyle(.orange)
                            .lineLimit(2)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }

                    if appState.isBackendStatsLoading && appState.backendStats == nil {
                        ProgressView()
                            .controlSize(.small)
                            .frame(maxWidth: .infinity, minHeight: 220)
                    } else if count == 0 {
                        ContentUnavailableView(
                            strings.noStatsRecords,
                            systemImage: "doc.text.magnifyingglass"
                        )
                        .frame(maxWidth: .infinity, minHeight: 280)
                    } else {
                        StatsOverviewSection(
                            totalResponses: count,
                            totalTopics: totalTopicCount,
                            records: visibleStatRecords,
                            activityDateRange: activityDateRange,
                            strings: strings
                        )

                        TopicBrowserSection(
                            stats: pagedTopicStats,
                            currentPage: boundedTopicPage,
                            pageCount: pageCount,
                            strings: strings,
                            onPreviousPage: {
                                topicPage = max(boundedTopicPage - 1, 0)
                                loadStats()
                            },
                            onNextPage: {
                                topicPage = min(boundedTopicPage + 1, topicPageCount - 1)
                                loadStats()
                            }
                        )
                    }
                }
                .padding(.trailing, 8)
                .padding(.bottom, 24)
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .refreshable {
                await refreshStats()
            }
            .searchSafeRefreshControlOffset(isRefreshing: isPullRefreshing)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .recordDetailPresentation(selectedRecord: $selectedRecord, strings: strings)
        .onChange(of: selectedPeriod) {
            resetTopicPaging()
            loadStats()
        }
        .onChange(of: customStartDate) {
            resetTopicPaging()
            if selectedPeriod == .custom {
                loadStats()
            }
        }
        .onChange(of: customEndDate) {
            resetTopicPaging()
            if selectedPeriod == .custom {
                loadStats()
            }
        }
        .onAppear {
            loadStats()
        }
    }

    private var selectedPeriodStartAt: Date? {
        periodBounds(for: selectedPeriod).startAt
    }

    private var selectedPeriodEndAt: Date? {
        periodBounds(for: selectedPeriod).endAt
    }

    private var activityDateRange: ClosedRange<Date> {
        Self.activityDateRange(
            for: selectedPeriod,
            customStartDate: customStartDate,
            customEndDate: customEndDate,
            records: visibleStatRecords
        )
    }

    private static func activityDateRange(
        for period: StatisticsPeriod,
        customStartDate: Date,
        customEndDate: Date,
        records: [StudyRecord]
    ) -> ClosedRange<Date> {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())

        func rangeFrom(daysIncludingToday dayCount: Int) -> ClosedRange<Date> {
            let start = calendar.date(byAdding: .day, value: -(max(dayCount, 1) - 1), to: today) ?? today
            return start...today
        }

        switch period {
        case .today:
            return today...today
        case .last7Days:
            return rangeFrom(daysIncludingToday: 7)
        case .last30Days:
            return rangeFrom(daysIncludingToday: 30)
        case .last90Days:
            return rangeFrom(daysIncludingToday: 90)
        case .custom:
            let start = calendar.startOfDay(for: min(customStartDate, customEndDate))
            let end = calendar.startOfDay(for: max(customStartDate, customEndDate))
            return start...end
        case .all:
            let recordDates = records.map { calendar.startOfDay(for: statsDate(for: $0)) }
            guard let earliest = recordDates.min(), let latest = recordDates.max() else {
                return rangeFrom(daysIncludingToday: 35)
            }
            return earliest...max(latest, today)
        }
    }

    private func periodBounds(for period: StatisticsPeriod) -> (startAt: Date?, endAt: Date?) {
        guard period == .custom else {
            return (nil, nil)
        }

        let start = min(customStartDate, customEndDate)
        let startAt = Calendar.current.startOfDay(for: start)
        let end = max(customStartDate, customEndDate)
        let dayStart = Calendar.current.startOfDay(for: end)
        let endAt = Calendar.current.date(byAdding: .day, value: 1, to: dayStart)
        return (startAt, endAt)
    }

    private func loadStats() {
        loadStats(
            period: selectedPeriod,
            startAt: selectedPeriodStartAt,
            endAt: selectedPeriodEndAt,
            limit: Self.topicPageSize,
            offset: max(topicPage * Self.topicPageSize, 0)
        )
    }

    private func loadStats(
        period: StatisticsPeriod = .all,
        startAt: Date? = nil,
        endAt: Date? = nil,
        limit: Int = Self.topicPageSize,
        offset: Int = 0
    ) {
        let requestOffset = max(offset, 0)
        Task {
            await appState.fetchBackendStats(
                period: period.backendPeriod,
                sort: .count,
                startAt: startAt,
                endAt: endAt,
                limit: limit,
                offset: requestOffset
            )
        }
    }

    @MainActor
    private func refreshStats() async {
        isPullRefreshing = true
        defer {
            isPullRefreshing = false
        }

        await appState.fetchBackendStats(
            period: selectedPeriod.backendPeriod,
            sort: .count,
            startAt: selectedPeriodStartAt,
            endAt: selectedPeriodEndAt,
            limit: Self.topicPageSize,
            offset: max(topicPage * Self.topicPageSize, 0)
        )
    }

    private func resetTopicPaging() {
        topicPage = 0
    }

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }
}

private extension View {
    @ViewBuilder
    func recordDetailPresentation(selectedRecord: Binding<StudyRecord?>, strings: AppStrings) -> some View {
        #if os(iOS)
        sheet(item: selectedRecord) { record in
            NavigationStack {
                StudyRecordDetailView(record: record)
                    .padding()
                    .navigationTitle(strings.records)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button(strings.done) {
                                selectedRecord.wrappedValue = nil
                            }
                        }
                    }
            }
        }
        #else
        popover(item: selectedRecord, arrowEdge: .trailing) { record in
            StudyRecordDetailView(record: record)
                .frame(width: 420)
                .frame(minHeight: 360)
                .padding()
        }
        #endif
    }
}

struct StudyRecordDetailView: View {
    @EnvironmentObject private var appState: AppState
    var record: StudyRecord
    @State private var draftAnswer: String
    @State private var showsHint = false
    #if os(iOS)
    @FocusState private var isAnswerEditorFocused: Bool
    #endif

    init(record: StudyRecord) {
        self.record = record
        _draftAnswer = State(initialValue: record.answer ?? "")
    }

    var body: some View {
        let displayedRecord = latestRecord

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                RecordDetailHeader(record: displayedRecord, strings: appState.strings, language: appState.settings.appLanguage)

                VStack(alignment: .leading, spacing: 12) {
                    RecordChatBubble(role: .question) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(displayedRecord.question.question.breakingLongTokens())
                                .font(.body)
                                .foregroundStyle(.white)
                                .textSelection(.enabled)

                            hintView(for: displayedRecord)
                        }
                    }

                    if let answer = displayedRecord.answer,
                       !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                       displayedRecord.gradingResult != nil {
                        RecordChatBubble(role: .answer) {
                            Text(answer.breakingLongTokens())
                                .font(.body)
                                .foregroundStyle(.white)
                                .textSelection(.enabled)
                        }
                    } else if displayedRecord.gradingResult == nil {
                        RecordChatBubble(role: .input) {
                            RecordAnswerInput(
                                strings: appState.strings,
                                draftAnswer: $draftAnswer,
                                isGradingAnswer: appState.isGradingAnswer,
                                canSubmitAnswer: canSubmitAnswer,
                                onSubmit: {
                                    submitAnswer(for: displayedRecord)
                                }
                            )
                            #if os(iOS)
                            .focused($isAnswerEditorFocused)
                            #endif
                        }
                    }

                    if let result = displayedRecord.gradingResult {
                        RecordChatBubble(role: .feedback) {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(alignment: .firstTextBaseline) {
                                    Label(result.gradeTitle(strings: appState.strings), systemImage: result.gradeIconName)
                                        .font(.subheadline.weight(.semibold))
                                    Spacer(minLength: 12)
                                    Text("\(result.score)/100")
                                        .font(.headline)
                                        .foregroundStyle(scoreColor(result.score))
                                        .lineLimit(1)
                                }

                                Text(result.feedback.breakingLongTokens())
                                    .font(.body)
                                    .textSelection(.enabled)
                                Text(result.explanation.breakingLongTokens())
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            }
            .padding(.top, 10)
            .padding(.bottom, 22)
        }
        #if os(iOS)
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneToolbar(appState.strings.done)
        #endif
    }

    private var latestRecord: StudyRecord {
        appState.studyRecords.first {
            $0.id == record.id ||
                SettingsStore.normalizedQuestionText($0.question.question) == SettingsStore.normalizedQuestionText(record.question.question)
        } ?? record
    }

    private var canSubmitAnswer: Bool {
        !appState.isGradingAnswer &&
            !draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @ViewBuilder
    private func hintView(for record: StudyRecord) -> some View {
        if let hint = record.question.expectedAnswerHint,
           !hint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    showsHint.toggle()
                } label: {
                    Label(showsHint ? appState.strings.hideHint : appState.strings.showHint, systemImage: "lightbulb")
                }
                .buttonStyle(.borderless)
                .font(.caption)
                .foregroundStyle(.white)
                .tint(.white)

                if showsHint {
                    Text(hint.breakingLongTokens())
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                        .textSelection(.enabled)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 4)
        }
    }

    private func submitAnswer(for record: StudyRecord) {
        guard canSubmitAnswer else {
            return
        }

        #if os(iOS)
        isAnswerEditorFocused = false
        #endif

        Task {
            await appState.gradeRecord(record, answer: draftAnswer)
        }
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
}

private struct RecordDetailHeader: View {
    var record: StudyRecord
    var strings: AppStrings
    var language: AppLanguage

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(record.topic.isEmpty ? strings.studyFallback : record.topic)
                    .font(.headline)
                    .lineLimit(2)

                Spacer(minLength: 8)

                if let score = record.gradingResult?.score {
                    Text("\(score)/100")
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                } else {
                    Text(strings.ungraded)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            HStack(spacing: 6) {
                Text(record.difficulty.displayName(language: language))
                Text("·")
                Text((record.answeredAt ?? record.question.createdAt).formatted(date: .abbreviated, time: .shortened))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(1)

            HStack(spacing: 12) {
                Label("\(record.viewCount)", systemImage: "eye")
                Label("\(record.commentCount)", systemImage: "bubble.right")
                Label("\(record.likeCount)", systemImage: "heart")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private enum RecordChatBubbleRole {
    case question
    case answer
    case feedback
    case input

    var alignment: Alignment {
        switch self {
        case .answer, .input:
            .trailing
        case .question, .feedback:
            .leading
        }
    }

    var fill: Color {
        switch self {
        case .question:
            Color.green.opacity(0.92)
        case .answer:
            Color.accentColor.opacity(0.92)
        case .feedback:
            Color.secondary.opacity(0.06)
        case .input:
            Color.clear
        }
    }

    var border: Color {
        switch self {
        case .feedback:
            Color.secondary.opacity(0.12)
        default:
            Color.clear
        }
    }
}

private struct RecordChatBubble<Content: View>: View {
    var role: RecordChatBubbleRole
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if role == .answer || role == .input {
                Spacer(minLength: 34)
            }

            if role == .input {
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if role == .answer {
                content()
                    .padding(.vertical, 11)
                    .padding(.horizontal, 12)
                    .frame(minWidth: 44, maxWidth: 280, alignment: .trailing)
                    .background(role.fill)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            } else {
                content()
                    .padding(.vertical, 11)
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(role.fill)
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(role.border, lineWidth: 1)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            }

            if role == .question || role == .feedback {
                Spacer(minLength: 34)
            }
        }
        .frame(maxWidth: .infinity, alignment: role.alignment)
    }
}

private struct RecordAnswerInput: View {
    var strings: AppStrings
    @Binding var draftAnswer: String
    var isGradingAnswer: Bool
    var canSubmitAnswer: Bool
    var onSubmit: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(strings.answerPlaceholder, text: $draftAnswer, axis: .vertical)
                .font(.body)
                .textFieldStyle(.plain)
                .lineLimit(1...5)
                .frame(minHeight: 32)

            Button {
                onSubmit()
            } label: {
                ZStack {
                    Circle()
                        .fill(canSubmitAnswer ? Color.accentColor : Color.secondary.opacity(0.18))

                    if isGradingAnswer {
                        ProgressView()
                            .controlSize(.small)
                            .tint(.white)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(canSubmitAnswer ? .white : .secondary)
                    }
                }
                .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .disabled(!canSubmitAnswer)
            .accessibilityLabel(strings.gradeAnswer)
        }
        .padding(.vertical, 6)
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .background(inputBackground)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var inputBackground: Color {
        #if os(iOS)
        Color(.secondarySystemBackground)
        #elseif os(macOS)
        Color(nsColor: .controlBackgroundColor)
        #else
        Color.secondary.opacity(0.08)
        #endif
    }
}

private struct DetailSection: View {
    var title: String
    var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(breakableText)
                .textSelection(.enabled)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var breakableText: String {
        text.breakingLongTokens()
    }
}

private extension String {
    func breakingLongTokens(every limit: Int = 28) -> String {
        var result = ""
        var token = ""

        func appendToken() {
            guard !token.isEmpty else {
                return
            }

            for (index, character) in token.enumerated() {
                if index > 0 && index % limit == 0 {
                    result.append("\u{200B}")
                }
                result.append(character)
            }
            token = ""
        }

        for character in self {
            if character.isWhitespace {
                appendToken()
                result.append(character)
            } else {
                token.append(character)
            }
        }

        appendToken()
        return result
    }
}

private enum StatisticsPeriod: String, CaseIterable, Identifiable {
    case all
    case today
    case last7Days
    case last30Days
    case last90Days
    case custom

    var id: String { rawValue }

    func title(strings: AppStrings) -> String {
        switch self {
        case .all:
            return strings.allPeriods
        case .today:
            return strings.today
        case .last7Days:
            return strings.last7Days
        case .last30Days:
            return strings.last30Days
        case .last90Days:
            return strings.last90Days
        case .custom:
            return strings.customPeriod
        }
    }

    func shortTitle(strings: AppStrings) -> String {
        switch self {
        case .all:
            return strings.allPeriods
        case .today:
            return strings.today
        case .last7Days:
            return strings.language == .korean ? "7일" : "7d"
        case .last30Days:
            return strings.language == .korean ? "30일" : "30d"
        case .last90Days:
            return strings.language == .korean ? "90일" : "90d"
        case .custom:
            return strings.language == .korean ? "직접" : "Custom"
        }
    }
}

private extension StatisticsPeriod {
    var backendPeriod: BackendStatsPeriod {
        switch self {
        case .all:
            return .all
        case .today:
            return .today
        case .last7Days:
            return .last7
        case .last30Days:
            return .last30
        case .last90Days:
            return .last90
        case .custom:
            return .all
        }
    }
}

private struct StatisticsPeriodControls: View {
    @Binding var selectedPeriod: StatisticsPeriod
    @Binding var customStartDate: Date
    @Binding var customEndDate: Date
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker(strings.period, selection: $selectedPeriod) {
                ForEach(StatisticsPeriod.allCases) { period in
                    Text(period.shortTitle(strings: strings)).tag(period)
                }
            }
            .labelsHidden()
            .pickerStyle(.segmented)

            if selectedPeriod == .custom {
                HStack(spacing: 10) {
                    DatePicker(
                        strings.startDate,
                        selection: $customStartDate,
                        displayedComponents: .date
                    )

                    DatePicker(
                        strings.endDate,
                        selection: $customEndDate,
                        displayedComponents: .date
                    )
                }
                .font(.caption)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(Color.secondary.opacity(0.04))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary.opacity(0.1), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

}

private struct TopicStat: Identifiable {
    var topicKey: String
    var topic: String
    var topicAliases: [String]
    var count: Int
    var average: Int
    var best: Int
    var correctRate: Int
    var levelRange: TopicLevelRange
    var records: [StudyRecord]
    var latestDate: Date

    var id: String { topicKey }
}

private extension TopicStat {
    init?(backend: BackendTopicStats) {
        let levelRange = TopicLevelRange.from(backend.levelRange)

        self.init(
            topicKey: backend.topicKey,
            topic: backend.topic,
            topicAliases: backend.topicAliases,
            count: backend.count,
            average: backend.average,
            best: backend.best,
            correctRate: backend.correctRate,
            levelRange: levelRange,
            records: backend.records,
            latestDate: backend.latestAt
        )
    }
}

struct TopicLevelRange: Equatable {
    var level: Difficulty
    var average: Int
    var sampleCount: Int
    var centerLevel: Double
    var lowerBound: Double
    var upperBound: Double

    var startDifficulty: Difficulty {
        difficulty(at: lowerBound)
    }

    var endDifficulty: Difficulty {
        difficulty(at: min(upperBound, 0.999_999))
    }

    var compactRangeText: String {
        "\(startDifficulty.level)-\(endDifficulty.level)"
    }

    var width: Double {
        upperBound - lowerBound
    }

    static func calculate(records: [StudyRecord]) -> TopicLevelRange? {
        let scoredRecords = records.compactMap { record -> (difficulty: Difficulty, score: Int)? in
            guard let score = record.gradingResult?.score else {
                return nil
            }

            return (record.difficulty, min(max(score, 0), 100))
        }
        guard !scoredRecords.isEmpty else {
            return nil
        }

        let estimates = scoredRecords.map { estimatedLevel(difficulty: $0.difficulty, score: $0.score) }
        let centerLevel = estimates.reduce(0, +) / Double(estimates.count)
        let variance: Double
        if estimates.count > 1 {
            let sumOfSquares = estimates.reduce(0) { partialResult, estimate in
                partialResult + pow(estimate - centerLevel, 2)
            }
            variance = sumOfSquares / Double(estimates.count - 1)
        } else {
            variance = 0
        }

        let averageScore = Int((Double(scoredRecords.map(\.score).reduce(0, +)) / Double(scoredRecords.count)).rounded())
        let evidenceSpread = sqrt(variance)
        let sampleUncertainty = 0.9 / sqrt(Double(scoredRecords.count))
        let conflictUncertainty = evidenceSpread * 0.55
        let minimumHalfWidth = minimumHalfWidth(sampleCount: scoredRecords.count)
        let halfWidth = min(4.0, max(minimumHalfWidth, sampleUncertainty + conflictUncertainty))

        return make(
            centerLevel: centerLevel,
            average: averageScore,
            sampleCount: scoredRecords.count,
            halfWidth: halfWidth
        )
    }

    static func calculate(level: Difficulty, average: Int, sampleCount: Int) -> TopicLevelRange {
        let clampedAverage = min(max(average, 0), 100)
        let centerLevel = estimatedLevel(difficulty: level, score: clampedAverage)
        let halfWidth = max(minimumHalfWidth(sampleCount: sampleCount), 0.9 / sqrt(Double(max(sampleCount, 1))))

        return make(
            centerLevel: centerLevel,
            average: clampedAverage,
            sampleCount: sampleCount,
            halfWidth: halfWidth
        )
    }

    private static func make(
        centerLevel: Double,
        average: Int,
        sampleCount: Int,
        halfWidth: Double
    ) -> TopicLevelRange {
        let clampedCenter = min(max(centerLevel, 1), 10)
        let lowerLevel = max(1, clampedCenter - halfWidth)
        let upperLevel = min(10, clampedCenter + halfWidth)
        let lowerBound = progress(forLevelValue: lowerLevel)
        let upperBound = max(lowerBound + 0.025, progress(forLevelValue: upperLevel))

        return TopicLevelRange(
            level: Difficulty(level: Int(clampedCenter.rounded())),
            average: average,
            sampleCount: sampleCount,
            centerLevel: clampedCenter,
            lowerBound: lowerBound,
            upperBound: min(1, upperBound)
        )
    }

    private static func estimatedLevel(difficulty: Difficulty, score: Int) -> Double {
        let clampedScore = min(max(score, 0), 100)
        let levelValue = Double(difficulty.level) + (Double(clampedScore) - 70) / 35
        return min(max(levelValue, 1), 10)
    }

    private static func minimumHalfWidth(sampleCount: Int) -> Double {
        switch sampleCount {
        case 8...:
            0.3
        case 4...:
            0.45
        default:
            0.65
        }
    }

    private static func progress(forLevelValue levelValue: Double) -> Double {
        min(max((levelValue - 0.5) / Double(Difficulty.allCases.count), 0), 1)
    }

    private func difficulty(at progress: Double) -> Difficulty {
        let clampedProgress = min(max(progress, 0), 0.999_999)
        let index = Int((clampedProgress * Double(Difficulty.allCases.count)).rounded(.down))
        return Difficulty.allCases[min(max(index, 0), Difficulty.allCases.count - 1)]
    }
}

private extension TopicLevelRange {
    static func from(_ backendRange: BackendTopicLevelRange) -> TopicLevelRange {
        let average = min(max(backendRange.average, 0), 100)
        let centerDifficulty = Difficulty(level: backendRange.level)
        let centerLevel = estimatedLevel(difficulty: centerDifficulty, score: average)
        let lowerBound = normalizeProgress(backendRange.lowerBound)
        let upperBound = normalizeProgress(backendRange.upperBound)

        return TopicLevelRange(
            level: Difficulty(level: Int(centerLevel.rounded())),
            average: average,
            sampleCount: max(backendRange.sampleCount, 1),
            centerLevel: centerLevel,
            lowerBound: lowerBound,
            upperBound: max(lowerBound + 0.025, upperBound)
        )
    }

    private static func normalizeProgress(_ value: Double) -> Double {
        if value >= 0 && value <= 1 {
            return value
        }

        let progress = (value - 0.5) / Double(Difficulty.allCases.count)
        return min(max(progress, 0), 1)
    }
}

private struct StatsOverviewSection: View {
    var totalResponses: Int
    var totalTopics: Int
    var records: [StudyRecord]
    var activityDateRange: ClosedRange<Date>
    var strings: AppStrings

    private var latestRecordDate: Date? {
        records.map(Self.statsDate(for:)).max()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 10) {
                StatsHeroMetric(value: "\(totalResponses)", label: strings.responses)
                StatsHeroMetric(value: "\(totalTopics)", label: strings.activeTopics)
            }

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(strings.recentActivity)
                        .font(.headline.weight(.semibold))
                    Spacer()
                    if let latestRecordDate {
                        Text(latestRecordDate, formatter: Self.relativeDateFormatter)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }

                StatsActivityGrid(records: records, dateRange: activityDateRange)
            }
        }
        .padding(14)
        .background(Color.secondary.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }

    private static let relativeDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d"
        return formatter
    }()
}

private struct StatsHeroMetric: View {
    var value: String
    var label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct StatsActivityGrid: View {
    var records: [StudyRecord]
    var dateRange: ClosedRange<Date>
    @State private var selectedDay: ActivityDay?

    private var days: [ActivityDay] {
        let calendar = Calendar.current
        let startDate = calendar.startOfDay(for: dateRange.lowerBound)
        let endDate = calendar.startOfDay(for: max(dateRange.upperBound, dateRange.lowerBound))
        let dayCount = max(calendar.dateComponents([.day], from: startDate, to: endDate).day ?? 0, 0)
        let counts = Dictionary(grouping: records) { record in
            calendar.startOfDay(for: record.answeredAt ?? record.question.createdAt)
        }
        .mapValues(\.count)

        return (0...dayCount).map { offset in
            let date = calendar.date(byAdding: .day, value: offset, to: startDate) ?? startDate
            return ActivityDay(date: date, count: counts[date] ?? 0)
        }
    }

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 5), count: 7)

        VStack(alignment: .leading, spacing: 8) {
            LazyVGrid(columns: columns, spacing: 5) {
                ForEach(days) { day in
                    Button {
                        withAnimation(.smooth(duration: 0.18)) {
                            selectedDay = day
                        }
                    } label: {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(color(for: day.count))
                            .frame(height: 20)
                            .overlay {
                                if selectedDay?.id == day.id {
                                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                                        .stroke(Color.primary.opacity(0.72), lineWidth: 1.5)
                                }
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Self.accessibilityText(for: day))
                }
            }

            if let selectedDay {
                Text(Self.displayText(for: selectedDay))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func color(for count: Int) -> Color {
        switch count {
        case 4...:
            return .accentColor
        case 3:
            return .accentColor.opacity(0.78)
        case 2:
            return .accentColor.opacity(0.54)
        case 1:
            return .accentColor.opacity(0.28)
        default:
            return Color.secondary.opacity(0.13)
        }
    }

    private static func displayText(for day: ActivityDay) -> String {
        "\(dateFormatter.string(from: day.date)) · \(day.count)"
    }

    private static func accessibilityText(for day: ActivityDay) -> String {
        "\(dateFormatter.string(from: day.date)), \(day.count)"
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d"
        return formatter
    }()
}

private struct ActivityDay: Identifiable {
    var date: Date
    var count: Int

    var id: Date { date }
}

private struct TopicBrowserSection: View {
    var stats: [TopicStat]
    var currentPage: Int
    var pageCount: Int
    var strings: AppStrings
    var onPreviousPage: () -> Void
    var onNextPage: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if stats.isEmpty {
                ContentUnavailableView(
                    strings.noMatchingTopics,
                    systemImage: "line.3.horizontal.decrease.circle",
                    description: Text(strings.noMatchingTopicsDescription)
                )
                .frame(maxWidth: .infinity, minHeight: 180)
            } else {
                VStack(spacing: 8) {
                    ForEach(stats) { stat in
                        TopicStatRow(stat: stat, strings: strings)
                    }
                }
            }

            if pageCount > 1 {
                HStack(spacing: 10) {
                    Spacer()

                    Button(action: onPreviousPage) {
                        Image(systemName: "chevron.left")
                    }
                    .buttonStyle(.borderless)
                    .disabled(currentPage == 0)
                    .help(strings.previousPage)

                    Text("\(currentPage + 1)/\(pageCount)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(1)

                    Button(action: onNextPage) {
                        Image(systemName: "chevron.right")
                    }
                    .buttonStyle(.borderless)
                    .disabled(currentPage >= pageCount - 1)
                    .help(strings.nextPage)
                }
                .padding(.top, 2)
                .padding(.trailing, 4)
            }
        }
    }
}

private struct TopicStatRow: View {
    var stat: TopicStat
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(stat.topic)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.tail)

                    HStack(spacing: 8) {
                        Text("\(strings.level) \(stat.levelRange.compactRangeText)")
                        Text("·")
                        Text(stat.latestDate, formatter: Self.dateFormatter)
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 0) {
                    Text("\(stat.count)")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                    Text(strings.responsesShort)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            CompactLevelRangeBar(range: stat.levelRange)
        }
        .padding(14)
        .background(Color.secondary.opacity(0.052))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d"
        return formatter
    }()
}

private struct CompactLevelRangeBar: View {
    var range: TopicLevelRange

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                HStack(spacing: 2) {
                    ForEach(Difficulty.allCases) { difficulty in
                        Capsule()
                            .fill(difficulty == range.level ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.14))
                    }
                }

                Capsule()
                    .fill(Color.accentColor.opacity(0.82))
                    .frame(width: max(4, proxy.size.width * (range.upperBound - range.lowerBound)))
                    .offset(x: proxy.size.width * range.lowerBound)
            }
        }
        .frame(height: 10)
    }
}

private struct SelectedTopicSection: View {
    var stat: TopicStat
    var records: [StudyRecord]
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(stat.topic)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer()

                Text(strings.topicTrend)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            LevelRangeSummary(stat: stat, strings: strings)

            if stat.topicAliases.count > 1 {
                Text(strings.groupedTopics(stat.topicAliases.joined(separator: " · ")))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }

            LevelRangeBar(range: stat.levelRange, strings: strings)

            TopicLevelTrendChart(records: records, strings: strings)
                .frame(height: 150)
        }
    }
}

private struct TopicLevelTrendChart: View {
    var records: [StudyRecord]
    var strings: AppStrings

    private var points: [LevelTrendPoint] {
        var accumulated: [StudyRecord] = []
        return records
            .sorted { Self.statsDate(for: $0) < Self.statsDate(for: $1) }
            .compactMap { record in
                accumulated.append(record)
                guard let range = TopicLevelRange.calculate(records: accumulated) else {
                    return nil
                }

                return LevelTrendPoint(
                    date: Self.statsDate(for: record),
                    progress: (range.lowerBound + range.upperBound) / 2
                )
            }
    }

    var body: some View {
        GeometryReader { proxy in
            let chartPoints = chartPoints(in: proxy.size)
            let axisWidth: CGFloat = 28

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.secondary.opacity(0.04))
                    .overlay {
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.1), lineWidth: 1)
                    }

                Path { path in
                    guard let first = chartPoints.first else {
                        return
                    }

                    path.move(to: first)
                    for point in chartPoints.dropFirst() {
                        path.addLine(to: point)
                    }
                }
                .stroke(Color.accentColor.opacity(0.78), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

                ForEach(Array(chartPoints.enumerated()), id: \.offset) { _, point in
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 5, height: 5)
                        .position(point)
                }

                VStack {
                    HStack {
                        Text("10")
                            .frame(width: axisWidth, alignment: .leading)
                        Spacer()
                    }
                    Spacer()
                    HStack {
                        Text("1")
                            .frame(width: axisWidth, alignment: .leading)
                        Spacer()
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(8)

                VStack {
                    Spacer()

                    HStack {
                        if let firstPoint = points.first {
                            Text(firstPoint.date, formatter: Self.axisDateFormatter)
                        }

                        Spacer()

                        if points.count > 1,
                           let latestPoint = points.last {
                            Text(latestPoint.date, formatter: Self.axisDateFormatter)
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.leading, axisWidth + 12)
                    .padding(.trailing, 8)
                    .padding(.bottom, 6)
                }
            }
        }
        .accessibilityLabel(strings.topicTrend)
    }

    private func chartPoints(in size: CGSize) -> [CGPoint] {
        guard !points.isEmpty else {
            return []
        }

        let leadingPadding: CGFloat = 42
        let trailingPadding: CGFloat = 14
        let topPadding: CGFloat = 18
        let bottomPadding: CGFloat = 34
        let width = max(size.width - leadingPadding - trailingPadding, 1)
        let height = max(size.height - topPadding - bottomPadding, 1)
        let denominator = max(points.count - 1, 1)

        return points.enumerated().map { index, point in
            let x = leadingPadding + width * CGFloat(index) / CGFloat(denominator)
            let y = topPadding + height * (1 - CGFloat(min(max(point.progress, 0), 1)))
            return CGPoint(x: x, y: y)
        }
    }

    private static let axisDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d HH:mm"
        return formatter
    }()

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }
}

private struct LevelTrendPoint {
    var date: Date
    var progress: Double
}

private struct LevelRangeSummary: View {
    var stat: TopicStat
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(strings.currentTopicLevel(stat.levelRange.level.displayName(language: strings.language)))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)

            Text(
                strings.topicLevelRange(
                    stat.levelRange.startDifficulty.displayName(language: strings.language),
                    stat.levelRange.endDifficulty.displayName(language: strings.language),
                    average: stat.levelRange.average,
                    count: stat.levelRange.sampleCount
                )
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .truncationMode(.tail)
        }
    }
}

private struct LevelRangeBar: View {
    var range: TopicLevelRange
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    HStack(spacing: 2) {
                        ForEach(Difficulty.allCases) { difficulty in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(difficulty == range.level ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.12))
                        }
                    }

                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.accentColor.opacity(0.72))
                        .frame(width: max(4, proxy.size.width * (range.upperBound - range.lowerBound)))
                        .offset(x: proxy.size.width * range.lowerBound)
                }
            }
            .frame(height: 10)

            HStack(spacing: 2) {
                ForEach(Difficulty.allCases) { difficulty in
                    Text(difficulty.shortDisplayName(language: strings.language))
                        .font(.system(size: 8, weight: difficulty == range.level ? .semibold : .regular))
                        .foregroundStyle(difficulty == range.level ? .primary : .secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .accessibilityLabel(
            strings.topicLevelRange(
                range.startDifficulty.displayName(language: strings.language),
                range.endDifficulty.displayName(language: strings.language),
                average: range.average,
                count: range.sampleCount
            )
        )
    }
}

private struct ScoreDistributionSection: View {
    var records: [StudyRecord]
    var strings: AppStrings

    private var buckets: [ScoreBucket] {
        [
            ScoreBucket(title: strings.excellentScores, count: count(in: 90...100)),
            ScoreBucket(title: strings.goodScores, count: count(in: 70...89)),
            ScoreBucket(title: strings.partialScores, count: count(in: 40...69)),
            ScoreBucket(title: strings.lowScores, count: count(in: 0...39))
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings.scoreDistribution)
                .font(.subheadline)
                .fontWeight(.semibold)

            VStack(spacing: 7) {
                ForEach(buckets) { bucket in
                    HStack(spacing: 8) {
                        Text(bucket.title)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(width: 54, alignment: .leading)

                        ProgressView(value: Double(bucket.count), total: Double(max(records.count, 1)))
                            .tint(Color.secondary.opacity(0.65))

                        Text("\(bucket.count)")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .frame(width: 24, alignment: .trailing)
                    }
                }
            }
            .padding(10)
            .background(Color.secondary.opacity(0.045))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.secondary.opacity(0.1), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private func count(in range: ClosedRange<Int>) -> Int {
        records.filter { record in
            guard let score = record.gradingResult?.score else {
                return false
            }

            return range.contains(score)
        }.count
    }
}

private extension Difficulty {
    var levelIndex: Int {
        level - 1
    }

    func shortDisplayName(language: AppLanguage) -> String {
        "\(level)"
    }
}

private struct ScoreBucket: Identifiable {
    var title: String
    var count: Int

    var id: String { title }
}

private struct ScoreRecordRow: View {
    var index: Int
    var record: StudyRecord
    var strings: AppStrings

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(index)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 24, alignment: .leading)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(Self.statsDate(for: record), formatter: Self.dateFormatter)
                    Text("·")
                    Text(record.topic.isEmpty ? strings.studyFallback : record.topic)
                    Text("·")
                    Text(record.difficulty.displayName(language: strings.language))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

                Text(record.question.question)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text("\(record.gradingResult?.score ?? 0)")
                .font(.headline)
        }
        .contentShape(Rectangle())
        .padding(9)
        .background(Color.secondary.opacity(0.04))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary.opacity(0.1), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter
    }()

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }

}

private struct ScoreLineChart: View {
    var records: [StudyRecord]

    private var scores: [Int] {
        records.compactMap { $0.gradingResult?.score }
    }

    var body: some View {
        GeometryReader { proxy in
            let points = chartPoints(in: proxy.size)
            let axisWidth: CGFloat = 28

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.secondary.opacity(0.04))
                    .overlay {
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.1), lineWidth: 1)
                    }

                Path { path in
                    guard let first = points.first else {
                        return
                    }

                    path.move(to: first)
                    for point in points.dropFirst() {
                        path.addLine(to: point)
                    }
                }
                .stroke(Color.secondary.opacity(0.85), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

                ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                    Circle()
                        .fill(Color.secondary)
                        .frame(width: 5, height: 5)
                        .position(point)
                }

                VStack {
                    HStack {
                        Text("100")
                            .frame(width: axisWidth, alignment: .leading)
                        Spacer()
                    }
                    Spacer()
                    HStack {
                        Text("0")
                            .frame(width: axisWidth, alignment: .leading)
                        Spacer()
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(8)

                VStack {
                    Spacer()

                    HStack {
                        if let firstRecord = records.first {
                            let first = Self.statsDate(for: firstRecord)
                            Text(first, formatter: Self.axisDateFormatter)
                        }

                        Spacer()

                        if records.count > 1,
                           let latestRecord = records.last {
                            let latest = Self.statsDate(for: latestRecord)
                            Text(latest, formatter: Self.axisDateFormatter)
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.leading, axisWidth + 12)
                    .padding(.trailing, 8)
                    .padding(.bottom, 6)
                }
            }
        }
    }

    private func chartPoints(in size: CGSize) -> [CGPoint] {
        guard !scores.isEmpty else {
            return []
        }

        let leadingPadding: CGFloat = 42
        let trailingPadding: CGFloat = 14
        let topPadding: CGFloat = 18
        let bottomPadding: CGFloat = 34
        let width = max(size.width - leadingPadding - trailingPadding, 1)
        let height = max(size.height - topPadding - bottomPadding, 1)
        let denominator = max(scores.count - 1, 1)

        return scores.enumerated().map { index, score in
            let x = leadingPadding + width * CGFloat(index) / CGFloat(denominator)
            let y = topPadding + height * (1 - CGFloat(min(max(score, 0), 100)) / 100)
            return CGPoint(x: x, y: y)
        }
    }

    private static let axisDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d HH:mm"
        return formatter
    }()

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }
}
